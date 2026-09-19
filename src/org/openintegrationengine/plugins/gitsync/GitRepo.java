/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.gitsync;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.FileHeader.PatchType;
import org.eclipse.jgit.patch.HunkHeader;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.io.NullOutputStream;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory;
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.FS;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The git side of the sync: a working tree under {@code appdata/} and the operations the
 * UI exposes.
 *
 * <p>Two rules are enforced here rather than in the UI, because a UI can be bypassed:
 *
 * <ul>
 *   <li><b>Pull refuses on a dirty tree.</b> Pull is a hard reset to {@code origin/<branch>},
 *       which would silently discard configuration the engine holds and git does not. So a
 *       pull with pending changes fails with the file list instead, and the operator either
 *       commits them or explicitly discards.</li>
 *   <li><b>Switching branches is destructive and says so.</b> A different branch means a
 *       different desired state, and adopting it replaces the engine's channels, code
 *       templates and the rest. It therefore requires an explicit confirmation flag, and
 *       {@link #previewSwitch} exists so the UI can show exactly what will change first.</li>
 * </ul>
 */
public final class GitRepo implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(GitRepo.class);
    private static final String ORIGIN = "origin";

    private final Path workTree;
    private final GitSyncSettings settings;
    private Git git;

    public GitRepo(Path workTree, GitSyncSettings settings) {
        this.workTree = workTree;
        this.settings = settings;
    }

    /** Where the configuration files live, honouring the optional subdirectory. */
    public Path configRoot() {
        String sub = settings.getSubdirectory();
        return sub == null || sub.isEmpty() ? workTree : workTree.resolve(sub);
    }

    // ------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------

    /**
     * Opens the working tree, cloning it if this is the first run.
     *
     * <p>A directory that exists but is not a repository is treated as an error rather than
     * being wiped: it is more likely a mount or path mistake than something to delete.
     */
    public synchronized void open() throws IOException, GitAPIException {
        if (git != null) {
            return;
        }
        if (!settings.isConfigured()) {
            throw new IllegalStateException("git sync is not configured");
        }

        Path dotGit = workTree.resolve(".git");
        if (Files.isDirectory(dotGit)) {
            git = Git.open(workTree.toFile());
            LOG.info("opened existing working tree at {}", workTree);
            return;
        }
        if (Files.exists(workTree) && !isEmptyDirectory(workTree)) {
            throw new IOException(workTree + " exists and is not a git repository; "
                + "move it aside or point the plugin somewhere else");
        }

        Files.createDirectories(workTree);
        LOG.info("cloning {} branch {} into {}",
            settings.getRemoteUrl(), settings.getBranch(), workTree);

        var clone = Git.cloneRepository()
            .setURI(settings.getRemoteUrl())
            .setDirectory(workTree.toFile())
            .setBranch(settings.getBranch())
            .setCloneAllBranches(true);
        applyAuth(clone::setCredentialsProvider, clone::setTransportConfigCallback);
        git = clone.call();
    }

    private static boolean isEmptyDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
    }

    @Override
    public synchronized void close() {
        if (git != null) {
            git.close();
            git = null;
        }
    }

    private Git require() {
        if (git == null) {
            throw new IllegalStateException("repository not open");
        }
        return git;
    }

    // ------------------------------------------------------------------
    // Authentication
    // ------------------------------------------------------------------

    /**
     * Wires whichever credential is configured onto a command.
     *
     * <p>SSH is configured per command through a {@link TransportConfigCallback} rather than
     * {@link SshSessionFactory#setInstance}, which is global: this runs inside the engine's
     * shared JVM and must not change SSH behaviour for anything else in it.
     */
    private void applyAuth(java.util.function.Consumer<CredentialsProvider> credentials,
                           java.util.function.Consumer<TransportConfigCallback> transport) {
        switch (settings.getAuthType()) {
            case HTTPS_TOKEN -> {
                // Most forges accept the token as the password with any non-empty
                // username; GitLab wants a real username or one of its sentinels, which
                // is why the username is configurable rather than hardcoded.
                String user = settings.getUsername() == null || settings.getUsername().isBlank()
                    ? "oauth2" : settings.getUsername();
                credentials.accept(
                    new UsernamePasswordCredentialsProvider(user, settings.getSecret()));
            }
            case SSH_KEY -> transport.accept(t -> {
                if (t instanceof SshTransport ssh) {
                    ssh.setSshSessionFactory(sshSessionFactory());
                }
            });
            case NONE -> { /* public remote */ }
            default -> { }
        }
    }

    private SshSessionFactory sshSessionFactory() {
        final byte[] key = settings.getSecret().getBytes(StandardCharsets.UTF_8);
        final String knownHosts = settings.getKnownHosts();
        final boolean strict = knownHosts != null && !knownHosts.isBlank();

        if (!strict) {
            // Worth being loud about: without a known-hosts entry the host key is accepted
            // unseen, so the first connection cannot detect a man in the middle.
            LOG.warn("git sync SSH host key checking is disabled because no known_hosts "
                + "entry is configured; set one to enable it");
        }

        return new JschConfigSessionFactory() {
            @Override
            protected void configure(OpenSshConfig.Host host, Session session) {
                session.setConfig("StrictHostKeyChecking", strict ? "yes" : "no");
                // The engine's own private key is the only identity that should be tried.
                session.setConfig("PreferredAuthentications", "publickey");
            }

            @Override
            protected JSch createDefaultJSch(FS fs) throws JSchException {
                JSch jsch = super.createDefaultJSch(fs);
                // Never fall back to keys lying around in the container's home directory.
                jsch.removeAllIdentity();
                jsch.addIdentity("oie-git-sync", key, null, null);
                if (strict) {
                    jsch.setKnownHosts(new java.io.ByteArrayInputStream(
                        knownHosts.getBytes(StandardCharsets.UTF_8)));
                }
                return jsch;
            }
        };
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    /** Current branch, head commit and pending changes. */
    public static final class State {
        public String branch = "";
        public String headCommit = "";
        public String headMessage = "";
        public String headAuthor = "";
        public long headTimestamp;
        public int ahead;
        public int behind;
        /** Paths the engine has changed relative to HEAD, relative to the work tree. */
        public final List<String> pendingChanges = new ArrayList<>();
        public boolean dirty;
    }

    public synchronized State state() throws IOException, GitAPIException {
        Git g = require();
        Repository repo = g.getRepository();
        State s = new State();

        s.branch = repo.getBranch();

        ObjectId head = repo.resolve(Constants.HEAD);
        if (head != null) {
            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit commit = walk.parseCommit(head);
                s.headCommit = commit.getName();
                s.headMessage = commit.getShortMessage();
                s.headAuthor = commit.getAuthorIdent() == null
                    ? "" : commit.getAuthorIdent().getName();
                s.headTimestamp = commit.getCommitTime() * 1000L;
            }
        }

        BranchTrackingStatus tracking = BranchTrackingStatus.of(repo, s.branch);
        if (tracking != null) {
            s.ahead = tracking.getAheadCount();
            s.behind = tracking.getBehindCount();
        }

        Status status = g.status().call();
        Set<String> changed = new TreeSet<>();
        changed.addAll(status.getModified());
        changed.addAll(status.getUntracked());
        changed.addAll(status.getMissing());
        changed.addAll(status.getAdded());
        changed.addAll(status.getChanged());
        changed.addAll(status.getRemoved());
        s.pendingChanges.addAll(changed);
        s.dirty = !changed.isEmpty();

        return s;
    }

    /** Local and remote-tracking branch names, without the {@code refs/} prefixes. */
    /**
     * One changed file with the size of the change, for the change list in the UI.
     *
     * <p>Counts rather than only names because "channels/foo.xml changed" says nothing
     * about whether a description was reworded or the whole channel was rebuilt, and that
     * is the difference between committing without looking and reading the diff first.
     */
    public static final class FileChange {
        public String path = "";
        /** add, modify, delete, rename or copy -- git's own vocabulary, lower-cased. */
        public String change = "";
        public int added;
        public int removed;
        public boolean binary;
    }

    /**
     * Diffs HEAD against the working tree.
     *
     * <p>Scoped to the working tree rather than the index on purpose: this plugin exports
     * engine state straight to files and never stages anything, so the index is always
     * empty of intent and staging would only add a step that nothing else here uses.
     *
     * @param path a single file to diff, or null/blank for every change
     */
    private List<DiffEntry> scanWorkTree(DiffFormatter df, String path) throws IOException {
        Repository repo = require().getRepository();
        ObjectId headTree = repo.resolve(Constants.HEAD + "^{tree}");

        AbstractTreeIterator oldTree;
        if (headTree == null) {
            // A repository with no commits yet: everything present counts as added.
            oldTree = new EmptyTreeIterator();
        } else {
            CanonicalTreeParser parser = new CanonicalTreeParser();
            try (var reader = repo.newObjectReader()) {
                parser.reset(reader, headTree);
            }
            oldTree = parser;
        }

        df.setRepository(repo);
        df.setDiffComparator(RawTextComparator.DEFAULT);
        df.setDetectRenames(true);
        if (path != null && !path.isBlank()) {
            df.setPathFilter(PathFilter.create(path));
        }
        return df.scan(oldTree, new FileTreeIterator(repo));
    }

    private static String pathOf(DiffEntry e) {
        return e.getChangeType() == DiffEntry.ChangeType.DELETE ? e.getOldPath() : e.getNewPath();
    }

    /** Every uncommitted change with its added/removed line counts. */
    public synchronized List<FileChange> changes() throws IOException, GitAPIException {
        List<FileChange> out = new ArrayList<>();
        try (DiffFormatter df = new DiffFormatter(NullOutputStream.INSTANCE)) {
            for (DiffEntry entry : scanWorkTree(df, null)) {
                FileChange c = new FileChange();
                c.path = pathOf(entry);
                c.change = entry.getChangeType().name().toLowerCase(Locale.ROOT);

                FileHeader header = df.toFileHeader(entry);
                if (header.getPatchType() != PatchType.UNIFIED) {
                    // A keystore or a PDF in the tree: reporting 0/0 would read as "no
                    // change at all", so the UI is told to say "binary" instead.
                    c.binary = true;
                } else {
                    for (HunkHeader hunk : header.getHunks()) {
                        for (Edit edit : hunk.toEditList()) {
                            c.removed += edit.getLengthA();
                            c.added += edit.getLengthB();
                        }
                    }
                }
                out.add(c);
            }
        }
        out.sort(Comparator.comparing(c -> c.path));
        return out;
    }

    /** The unified diff of one file, HEAD against the working tree. */
    public synchronized String diff(String path) throws IOException, GitAPIException {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (DiffFormatter df = new DiffFormatter(buf)) {
            List<DiffEntry> entries = scanWorkTree(df, path.trim());
            if (entries.isEmpty()) {
                return "";
            }
            for (DiffEntry entry : entries) {
                df.format(entry);
            }
            df.flush();
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    public synchronized List<String> branches() throws GitAPIException {
        Git g = require();
        Set<String> names = new LinkedHashSet<>();
        for (Ref ref : g.branchList().setListMode(ListBranchCommand.ListMode.ALL).call()) {
            String name = ref.getName();
            if (name.startsWith(Constants.R_HEADS)) {
                names.add(name.substring(Constants.R_HEADS.length()));
            } else if (name.startsWith(Constants.R_REMOTES + ORIGIN + "/")) {
                String shortName = name.substring((Constants.R_REMOTES + ORIGIN + "/").length());
                // origin/HEAD is a symbolic ref, not a branch anyone checks out.
                if (!"HEAD".equals(shortName)) {
                    names.add(shortName);
                }
            }
        }
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(Comparator.naturalOrder());
        return sorted;
    }

    /**
     * Lists the remote's branches without cloning anything.
     *
     * <p>Deliberately does not need {@link #open()}: the settings form has to offer a
     * branch list before any valid configuration exists, and cloning to find out what
     * branches there are would be both slow and circular.
     */
    public List<String> listRemoteBranches() throws GitAPIException {
        var ls = Git.lsRemoteRepository()
            .setRemote(settings.getRemoteUrl())
            .setHeads(true);
        applyAuth(ls::setCredentialsProvider, ls::setTransportConfigCallback);

        Set<String> names = new LinkedHashSet<>();
        for (Ref ref : ls.call()) {
            String name = ref.getName();
            if (name.startsWith(Constants.R_HEADS)) {
                names.add(name.substring(Constants.R_HEADS.length()));
            }
        }
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(Comparator.naturalOrder());
        return sorted;
    }

    public synchronized void fetch() throws GitAPIException {
        Git g = require();
        var fetch = g.fetch().setRemote(ORIGIN).setRemoveDeletedRefs(true);
        applyAuth(fetch::setCredentialsProvider, fetch::setTransportConfigCallback);
        fetch.call();
    }

    // ------------------------------------------------------------------
    // Pull
    // ------------------------------------------------------------------

    /** Thrown when an operation would discard configuration the engine holds. */
    public static final class DirtyTreeException extends Exception {
        private static final long serialVersionUID = 1L;
        private final List<String> changes;

        DirtyTreeException(String message, List<String> changes) {
            super(message);
            this.changes = List.copyOf(changes);
        }

        public List<String> getChanges() {
            return changes;
        }
    }

    /**
     * Fast-forwards the working tree to {@code origin/<branch>} by hard reset.
     *
     * <p>Refuses when the tree is dirty. A hard reset is the only sane way to make the tree
     * match the branch exactly -- a merge would invent a state that is in neither place --
     * but it discards whatever the engine has that git does not, so the caller has to deal
     * with that first. {@code discardLocal} is the explicit escape hatch.
     *
     * @return the new head commit id
     */
    public synchronized String pull(boolean discardLocal)
            throws IOException, GitAPIException, DirtyTreeException {
        Git g = require();
        fetch();

        // A pull hard-resets whatever is checked out onto origin/<configured branch>, so
        // running it while the two disagree would quietly move the checked-out branch to
        // another branch's commit -- a branch switch with none of a switch's confirmation,
        // preview or say over what happens to the configuration it strands. This is
        // reachable simply by saving a new branch in the settings form, so it is refused
        // here rather than assumed not to happen.
        String current = g.getRepository().getBranch();
        if (!settings.getBranch().equals(current)) {
            throw new IOException("the engine is on branch " + current + " but the configured "
                + "branch is " + settings.getBranch() + "; switch branch to adopt "
                + settings.getBranch() + ", or set the branch back to " + current
                + " before pulling");
        }

        if (!discardLocal) {
            State before = state();
            if (before.dirty) {
                throw new DirtyTreeException(
                    "the engine has changes that are not committed; commit or discard them "
                        + "before pulling", before.pendingChanges);
            }
        }

        String remoteRef = Constants.R_REMOTES + ORIGIN + "/" + settings.getBranch();
        if (g.getRepository().resolve(remoteRef) == null) {
            throw new IOException("branch " + settings.getBranch() + " does not exist on " + ORIGIN);
        }

        g.reset().setMode(ResetType.HARD).setRef(remoteRef).call();
        // Untracked leftovers would otherwise be applied as if they were desired state.
        g.clean().setCleanDirectories(true).setForce(true).setIgnore(false).call();

        ObjectId head = g.getRepository().resolve(Constants.HEAD);
        return head == null ? "" : head.getName();
    }

    /**
     * Throws away everything local and makes the tree exactly {@code origin/<branch>}.
     *
     * <p>Unlike {@link #pull(boolean)} this never refuses: refusing is the thing it exists
     * to bypass. It is the recovery path for a tree that has drifted far enough that
     * reconciling it is not worth anyone's time -- a channel edited directly on the server,
     * a half-finished export, a switch that left the engine and the branch disagreeing.
     * Uncommitted changes and any local commits that were never pushed are both gone
     * afterwards, which is why {@code confirmed} is not optional.
     *
     * <p>Doubles as a branch switch when {@code target} names another branch, because the
     * end state is the same either way: checked out on {@code target}, at the remote's
     * commit, with nothing local surviving.
     *
     * @return the new head commit id
     */
    public synchronized String forceReset(String target, boolean confirmed)
            throws IOException, GitAPIException {
        if (!confirmed) {
            throw new IllegalStateException(
                "a force reset discards local commits and changes and must be confirmed");
        }
        Git g = require();
        fetch();

        String branch = (target == null || target.isBlank())
            ? settings.getBranch() : target.trim();
        String remoteRef = Constants.R_REMOTES + ORIGIN + "/" + branch;
        if (g.getRepository().resolve(remoteRef) == null) {
            // Deliberately no fallback to a local branch of the same name: resetting onto a
            // commit that exists only here would leave the engine in a state no one else
            // can reproduce, which is the opposite of what a reset is for.
            throw new IOException("branch " + branch + " does not exist on " + ORIGIN);
        }

        // Clear the tree before checking out, so an uncommitted edit cannot block the
        // checkout and leave the reset half applied.
        g.reset().setMode(ResetType.HARD).call();
        g.clean().setCleanDirectories(true).setForce(true).call();

        if (!branch.equals(g.getRepository().getBranch())) {
            boolean localExists = g.getRepository().resolve(Constants.R_HEADS + branch) != null;
            var checkout = g.checkout().setName(branch);
            if (!localExists) {
                checkout.setCreateBranch(true)
                    .setStartPoint(ORIGIN + "/" + branch)
                    .setUpstreamMode(SetupUpstreamMode.TRACK);
            }
            checkout.call();
        }

        g.reset().setMode(ResetType.HARD).setRef(remoteRef).call();
        // Untracked leftovers would otherwise be applied as if they were desired state.
        g.clean().setCleanDirectories(true).setForce(true).setIgnore(false).call();

        settings.setBranch(branch);
        settings.save();

        ObjectId head = g.getRepository().resolve(Constants.HEAD);
        String id = head == null ? "" : head.getName();
        // WARN, not INFO: this is the one operation here that destroys work without a way
        // to get it back, so it should be findable in the log afterwards.
        LOG.warn("git sync force reset to {}/{} ({})", ORIGIN, branch,
            id.isEmpty() ? "unknown" : id);
        return id;
    }

    /**
     * The files a branch holds, read straight out of the object database.
     *
     * <p>Exists so the switch preview can answer "what does this engine have that the
     * branch does not?" <em>before</em> the checkout. Doing it afterwards would mean the
     * operator confirms the switch and only then learns which channels it strands, which
     * is the wrong order for a question whose answer might be "delete".
     *
     * <p>Honours the configured subdirectory, so the paths handed back are relative to the
     * config root exactly as the working-tree source produces them.
     */
    public synchronized EngineConfigStore.FileSource filesAt(String target) throws IOException {
        Repository repo = require().getRepository();

        // origin first: the preview is about the branch as the remote has it, which is also
        // what switchBranch() hard-resets onto. A stale local branch of the same name would
        // otherwise describe a state nobody is switching to.
        ObjectId commit = repo.resolve(Constants.R_REMOTES + ORIGIN + "/" + target);
        if (commit == null) {
            commit = repo.resolve(Constants.R_HEADS + target);
        }
        if (commit == null) {
            throw new IOException("branch " + target + " does not exist locally or on " + ORIGIN);
        }

        String sub = settings.getSubdirectory();
        final String prefix = sub == null || sub.isEmpty()
            ? "" : sub.replace('\\', '/') + "/";

        final java.util.Map<String, ObjectId> blobs = new java.util.LinkedHashMap<>();
        // Git stores no entry for a directory of its own, so the ones that matter are
        // collected on the way past their contents. exists("channels") has to be able to
        // answer, because an absent directory is what stops the orphan check from treating
        // a branch as having deleted everything of that type.
        final Set<String> dirs = new LinkedHashSet<>();

        try (RevWalk walk = new RevWalk(repo);
             org.eclipse.jgit.treewalk.TreeWalk tw = new org.eclipse.jgit.treewalk.TreeWalk(repo)) {
            RevCommit c = walk.parseCommit(commit);
            tw.addTree(c.getTree());
            tw.setRecursive(true);
            while (tw.next()) {
                String path = tw.getPathString();
                if (!path.startsWith(prefix)) {
                    continue;
                }
                String rel = path.substring(prefix.length());
                blobs.put(rel, tw.getObjectId(0));
                for (int slash = rel.indexOf('/'); slash >= 0; slash = rel.indexOf('/', slash + 1)) {
                    dirs.add(rel.substring(0, slash));
                }
            }
        }

        final Repository readFrom = repo;
        return new EngineConfigStore.FileSource() {
            @Override
            public boolean exists(String path) {
                return blobs.containsKey(path) || dirs.contains(path);
            }

            @Override
            public List<String> list(String dir) {
                String start = dir.endsWith("/") ? dir : dir + "/";
                List<String> out = new ArrayList<>();
                for (String path : blobs.keySet()) {
                    // Direct children only, matching what Files.list gives the working-tree
                    // source; a recursive walk would count templates as channels.
                    if (path.startsWith(start) && path.indexOf('/', start.length()) < 0) {
                        out.add(path);
                    }
                }
                out.sort(Comparator.naturalOrder());
                return out;
            }

            @Override
            public String read(String path) throws IOException {
                ObjectId id = blobs.get(path);
                if (id == null) {
                    throw new IOException(path + " is not in " + target);
                }
                return new String(readFrom.open(id).getBytes(), StandardCharsets.UTF_8);
            }
        };
    }

    // ------------------------------------------------------------------
    // Branch switching and creation
    // ------------------------------------------------------------------

    /** What adopting another branch would change, so the UI can warn specifically. */
    public static final class SwitchPreview {
        public String fromBranch = "";
        public String toBranch = "";
        public boolean dirty;
        public final List<String> pendingChanges = new ArrayList<>();
        /** Files that differ between the current head and the target branch. */
        public final List<String> incomingChanges = new ArrayList<>();
        public boolean targetExistsLocally;
        public boolean targetExistsOnRemote;
    }

    public synchronized SwitchPreview previewSwitch(String target)
            throws IOException, GitAPIException {
        Git g = require();
        fetch();

        SwitchPreview p = new SwitchPreview();
        p.fromBranch = g.getRepository().getBranch();
        p.toBranch = target;

        State now = state();
        p.dirty = now.dirty;
        p.pendingChanges.addAll(now.pendingChanges);

        p.targetExistsLocally = g.getRepository().resolve(Constants.R_HEADS + target) != null;
        p.targetExistsOnRemote =
            g.getRepository().resolve(Constants.R_REMOTES + ORIGIN + "/" + target) != null;

        ObjectId from = g.getRepository().resolve(Constants.HEAD);
        ObjectId to = p.targetExistsOnRemote
            ? g.getRepository().resolve(Constants.R_REMOTES + ORIGIN + "/" + target)
            : g.getRepository().resolve(Constants.R_HEADS + target);

        if (from != null && to != null && !from.equals(to)) {
            for (var entry : g.diff()
                    .setOldTree(treeFor(from))
                    .setNewTree(treeFor(to))
                    .call()) {
                p.incomingChanges.add(entry.getChangeType() + " "
                    + ("DELETE".equals(entry.getChangeType().name())
                        ? entry.getOldPath() : entry.getNewPath()));
            }
        }
        return p;
    }

    private org.eclipse.jgit.treewalk.AbstractTreeIterator treeFor(ObjectId commitId)
            throws IOException {
        Repository repo = require().getRepository();
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(commitId);
            var parser = new org.eclipse.jgit.treewalk.CanonicalTreeParser();
            try (var reader = repo.newObjectReader()) {
                parser.reset(reader, commit.getTree().getId());
            }
            return parser;
        }
    }

    /**
     * Checks out another branch, creating a local tracking branch when it only exists on
     * the remote.
     *
     * <p>Destructive by nature: the caller is expected to apply the new tree to the engine
     * afterwards, replacing channels, code templates and everything else in scope. Hence
     * {@code confirmed} -- it exists so that reaching this state requires an explicit act
     * rather than a stray request.
     */
    public synchronized void switchBranch(String target, boolean confirmed, boolean discardLocal)
            throws IOException, GitAPIException, DirtyTreeException {
        if (!confirmed) {
            throw new IllegalStateException(
                "switching branches replaces the engine's configuration and must be confirmed");
        }
        Git g = require();
        fetch();

        if (!discardLocal) {
            State before = state();
            if (before.dirty) {
                throw new DirtyTreeException(
                    "the engine has uncommitted changes; commit or discard them before "
                        + "switching branch", before.pendingChanges);
            }
        } else {
            g.reset().setMode(ResetType.HARD).call();
            g.clean().setCleanDirectories(true).setForce(true).call();
        }

        boolean localExists = g.getRepository().resolve(Constants.R_HEADS + target) != null;
        boolean remoteExists =
            g.getRepository().resolve(Constants.R_REMOTES + ORIGIN + "/" + target) != null;

        if (!localExists && !remoteExists) {
            throw new IOException("branch " + target + " does not exist locally or on " + ORIGIN);
        }

        var checkout = g.checkout().setName(target);
        if (!localExists) {
            checkout.setCreateBranch(true)
                .setStartPoint(ORIGIN + "/" + target)
                .setUpstreamMode(SetupUpstreamMode.TRACK);
        }
        checkout.call();

        if (remoteExists) {
            // Adopt the remote's state exactly; a stale local branch would otherwise win.
            g.reset().setMode(ResetType.HARD)
                .setRef(Constants.R_REMOTES + ORIGIN + "/" + target).call();
        }

        settings.setBranch(target);
        settings.save();
        LOG.info("git sync switched to branch {}", target);
    }

    /**
     * Creates a branch from the current head and checks it out.
     *
     * <p>Not destructive: the new branch starts as whatever the engine is already on, so no
     * configuration changes. Pushing it is optional so that a branch can be prepared before
     * anyone else sees it.
     */
    public synchronized void createBranch(String name, boolean push)
            throws IOException, GitAPIException {
        Git g = require();

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("branch name is required");
        }
        // Let git judge the name rather than inventing a second set of rules.
        if (!Repository.isValidRefName(Constants.R_HEADS + name)) {
            throw new IllegalArgumentException("not a valid branch name: " + name);
        }
        if (g.getRepository().resolve(Constants.R_HEADS + name) != null) {
            throw new IllegalArgumentException("branch already exists: " + name);
        }

        g.checkout().setCreateBranch(true).setName(name).call();
        settings.setBranch(name);
        settings.save();
        LOG.info("git sync created branch {}", name);

        if (push) {
            pushCurrentBranch(true);
        }
    }

    // ------------------------------------------------------------------
    // Commit and push
    // ------------------------------------------------------------------

    /**
     * Stages everything in the working tree and commits.
     *
     * @return the new commit id, or empty when there was nothing to commit
     */
    public synchronized String commit(String message)
            throws IOException, GitAPIException {
        Git g = require();

        State before = state();
        if (!before.dirty) {
            return "";
        }

        // Two calls: setUpdate(true) records deletions, the plain add records new and
        // modified files. Neither does both.
        g.add().addFilepattern(".").setUpdate(true).call();
        g.add().addFilepattern(".").call();

        PersonIdent author = new PersonIdent(
            settings.getAuthorName() == null || settings.getAuthorName().isBlank()
                ? "Open Integration Engine" : settings.getAuthorName(),
            settings.getAuthorEmail() == null ? "" : settings.getAuthorEmail());

        RevCommit commit = g.commit()
            .setMessage(message == null || message.isBlank()
                ? "Update configuration from Open Integration Engine" : message)
            .setAuthor(author)
            .setCommitter(author)
            .call();

        LOG.info("git sync committed {} ({} file(s))",
            commit.getName(), before.pendingChanges.size());
        return commit.getName();
    }

    /** Pushes the current branch, setting upstream when it has none. */
    public synchronized String pushCurrentBranch(boolean setUpstream)
            throws IOException, GitAPIException {
        Git g = require();
        String branch = g.getRepository().getBranch();

        var push = g.push()
            .setRemote(ORIGIN)
            .setRefSpecs(new RefSpec(Constants.R_HEADS + branch + ":" + Constants.R_HEADS + branch));
        applyAuth(push::setCredentialsProvider, push::setTransportConfigCallback);

        StringBuilder report = new StringBuilder();
        for (PushResult result : push.call()) {
            for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                if (report.length() > 0) {
                    report.append("; ");
                }
                report.append(update.getRemoteName()).append(' ').append(update.getStatus());
                if (update.getMessage() != null) {
                    report.append(" (").append(update.getMessage()).append(')');
                }
                // A rejection is a failure, not a detail to bury in a log line: it usually
                // means someone else pushed and this engine's history has diverged.
                if (update.getStatus() != RemoteRefUpdate.Status.OK
                    && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
                    throw new IOException("push rejected: " + report);
                }
            }
        }

        if (setUpstream) {
            var config = g.getRepository().getConfig();
            config.setString("branch", branch, "remote", ORIGIN);
            config.setString("branch", branch, "merge", Constants.R_HEADS + branch);
            config.save();
        }

        LOG.info("git sync pushed {}: {}", branch, report);
        return report.toString();
    }

    /** Discards everything the engine has that git does not. */
    public synchronized void discardLocalChanges() throws GitAPIException {
        Git g = require();
        g.reset().setMode(ResetType.HARD).call();
        g.clean().setCleanDirectories(true).setForce(true).call();
    }
}
