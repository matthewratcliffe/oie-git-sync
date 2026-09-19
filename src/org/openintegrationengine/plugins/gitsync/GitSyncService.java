/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.gitsync;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ties the git side and the engine side together, and owns the ordering between them.
 *
 * <p>Every operation is serialised on this object. Two concurrent pulls, or a pull racing a
 * commit, would interleave a working-tree rewrite with a read of that same tree; the cost
 * of a lock here is irrelevant next to how confusing that failure would be.
 */
public final class GitSyncService {

    private static final Logger LOG = LogManager.getLogger(GitSyncService.class);

    private final Path workTree;

    GitSyncService(Path workTree) {
        this.workTree = workTree;
    }

    public Path getWorkTree() {
        return workTree;
    }

    /**
     * Opens the repository for one operation and closes it afterwards.
     *
     * <p>Held per-operation rather than for the plugin's lifetime so that a settings change
     * (a different remote, branch or credential) takes effect on the next call without a
     * restart, and so a broken repository cannot wedge the plugin permanently.
     */
    private interface RepoTask<T> {
        T run(GitRepo repo, GitSyncSettings settings, EngineConfigStore store) throws Exception;
    }

    private synchronized <T> T withRepo(RepoTask<T> task) throws Exception {
        GitSyncSettings settings = GitSyncSettings.load();
        if (!settings.isConfigured()) {
            throw new IllegalStateException(
                "git sync is not configured: set a remote URL and branch first");
        }
        EngineConfigStore store =
            new EngineConfigStore(EngineConfigStore.SyncScope.parse(settings.getScope()));

        try (GitRepo repo = new GitRepo(workTree, settings)) {
            repo.open();

            // Create the configured subdirectory if the branch does not have it yet, so
            // pointing an instance at environments/staging works against a repository
            // that has never held that folder.
            //
            // Note git tracks files, not directories: creating it here makes the local
            // path usable immediately, but it only appears in the repository once a
            // commit carries files inside it. That is the first commit this instance
            // makes, so no extra step is needed -- and it is why there is no attempt to
            // commit an empty directory, which git would simply ignore.
            Path configRoot = repo.configRoot();
            if (!Files.isDirectory(configRoot)) {
                Files.createDirectories(configRoot);
                LOG.info("git sync created subdirectory {} in the working tree; it enters "
                    + "the repository with the first commit that puts files in it",
                    settings.getSubdirectory());
            }

            return task.run(repo, settings, store);
        }
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    /**
     * @param refresh re-export engine state into the tree before comparing. The UI passes
     *     true so "pending changes" reflects the engine as it is now; a background caller
     *     can pass false to avoid the export cost.
     */
    public Map<String, Object> status(boolean refresh) throws Exception {
        return withRepo((repo, settings, store) -> {
            if (refresh) {
                store.export(repo.configRoot());
            }
            GitRepo.State state = repo.state();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", Boolean.TRUE);
            out.put("configured", Boolean.TRUE);
            out.put("branch", state.branch);
            // The branch the settings ask for, which is not always the branch the working
            // tree is on: saving a new branch in the settings form changes the intent, not
            // the checkout. Reported so the UI can offer the switch that closes the gap,
            // rather than showing a branch name that quietly means two different things.
            out.put("configuredBranch", settings.getBranch());
            out.put("branchMismatch",
                Boolean.valueOf(!state.branch.equals(settings.getBranch())));
            out.put("headCommit", state.headCommit);
            out.put("headShort", state.headCommit.length() >= 7
                ? state.headCommit.substring(0, 7) : state.headCommit);
            out.put("headMessage", state.headMessage);
            out.put("headAuthor", state.headAuthor);
            out.put("headTimestamp", state.headTimestamp);
            out.put("ahead", state.ahead);
            out.put("behind", state.behind);
            out.put("dirty", state.dirty);
            out.put("pendingChanges", new ArrayList<>(state.pendingChanges));
            out.put("canPush", settings.canPush());
            out.put("mode", settings.getMode().name());
            out.put("remoteUrl", settings.getRemoteUrl());
            // ArrayList, never List.of()/toList(): XStream serialises an immutable list
            // as java.util.ImmutableCollections$ListN with a CollSer payload, which is
            // Java serialization internals -- the elements are not recoverable from it by
            // any client. Every collection that crosses this API must be a plain one.
            out.put("scope", new ArrayList<>(EngineConfigStore.SyncScope
                .parse(settings.getScope())
                .stream().map(EngineConfigStore.SyncScope::token).sorted().toList()));
            return out;
        });
    }

    /** Status that never throws, for the nav indicator: an unconfigured plugin is normal. */
    public Map<String, Object> statusQuiet() {
        GitSyncSettings settings = GitSyncSettings.load();
        if (!settings.isConfigured()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", Boolean.TRUE);
            out.put("configured", Boolean.FALSE);
            return out;
        }
        try {
            return status(false);
        } catch (Exception e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", Boolean.FALSE);
            out.put("configured", Boolean.TRUE);
            out.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            return out;
        }
    }

    public String currentBranch() throws Exception {
        return withRepo((repo, settings, store) -> repo.state().branch);
    }

    public List<String> branches() throws Exception {
        return withRepo((repo, settings, store) -> repo.branches());
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    /** Settings for the UI, with the credential omitted rather than masked. */
    public static Map<String, Object> describeSettings(GitSyncSettings s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.TRUE);
        out.put("remoteUrl", s.getRemoteUrl());
        out.put("branch", s.getBranch());
        out.put("authType", s.getAuthType().name());
        out.put("username", s.getUsername());
        out.put("mode", s.getMode().name());
        out.put("subdirectory", s.getSubdirectory());
        out.put("authorName", s.getAuthorName());
        out.put("authorEmail", s.getAuthorEmail());
        out.put("pullIntervalSeconds", s.getPullIntervalSeconds());
        out.put("scope", s.getScope());
        out.put("knownHosts", s.getKnownHosts());
        // Whether one is stored, never the value itself.
        out.put("hasSecret", s.getSecret() != null && !s.getSecret().isEmpty());
        out.put("configured", s.isConfigured());
        return out;
    }

    /**
     * Persists whatever is usable and reports what was not.
     *
     * <p>Saving is deliberately not all-or-nothing. A half-typed remote URL or an
     * unparseable interval should not throw away the other nine fields the operator just
     * filled in -- they would have to retype the lot. So each field is applied if it can
     * be, skipped with a recorded problem if it cannot, and the result is saved either way.
     *
     * <p>Whether the settings actually <em>work</em> is a separate question, answered by
     * {@link #validate()}: the caller saves first, then validates, and only moves on when
     * validation passes.
     */
    public synchronized Map<String, Object> updateSettings(Map<String, String> incoming) {
        GitSyncSettings s = GitSyncSettings.load();
        List<String> problems = new ArrayList<>();

        if (incoming.containsKey("remoteUrl")) {
            s.setRemoteUrl(incoming.get("remoteUrl"));
        }
        if (incoming.containsKey("branch")) {
            s.setBranch(incoming.get("branch"));
        }
        if (incoming.containsKey("authType")) {
            String raw = incoming.get("authType");
            try {
                s.setAuthType(GitSyncSettings.AuthType.valueOf(raw.trim().toUpperCase()));
            } catch (Exception e) {
                problems.add("Authentication type \"" + raw + "\" is not recognised; kept "
                    + s.getAuthType().name() + ".");
            }
        }
        if (incoming.containsKey("username")) {
            s.setUsername(incoming.get("username"));
        }
        if (incoming.containsKey("mode")) {
            String raw = incoming.get("mode");
            try {
                s.setMode(GitSyncSettings.Mode.valueOf(raw.trim().toUpperCase()));
            } catch (Exception e) {
                problems.add("Mode \"" + raw + "\" is not recognised; kept "
                    + s.getMode().name() + ".");
            }
        }
        if (incoming.containsKey("subdirectory")) {
            try {
                s.setSubdirectory(incoming.get("subdirectory"));
            } catch (IllegalArgumentException e) {
                // Rejected rather than sanitised: a path with .. in it is either a mistake
                // or an attempt to escape the working tree, and quietly rewriting it into
                // something else would be worse than saying no.
                problems.add("Subdirectory rejected: " + e.getMessage()
                    + " Kept \"" + s.getSubdirectory() + "\".");
            }
        }
        if (incoming.containsKey("authorName")) {
            s.setAuthorName(incoming.get("authorName"));
        }
        if (incoming.containsKey("authorEmail")) {
            s.setAuthorEmail(incoming.get("authorEmail"));
        }
        if (incoming.containsKey("pullIntervalSeconds")) {
            String raw = incoming.get("pullIntervalSeconds");
            try {
                s.setPullIntervalSeconds(Integer.parseInt(raw.trim()));
            } catch (NumberFormatException e) {
                problems.add("Scheduled pull interval \"" + raw + "\" is not a number; kept "
                    + s.getPullIntervalSeconds() + ".");
            }
        }
        if (incoming.containsKey("scope")) {
            s.setScope(incoming.get("scope"));
        }
        if (incoming.containsKey("knownHosts")) {
            s.setKnownHosts(incoming.get("knownHosts"));
        }

        // An empty secret means "leave it alone", because the UI is never sent the current
        // one and so cannot send it back. Clearing is a separate, explicit action.
        String secret = incoming.get("secret");
        if (secret != null && !secret.isEmpty()) {
            s.setSecret(secret);
        }
        if ("true".equalsIgnoreCase(incoming.get("clearSecret"))) {
            GitSyncSettings.clearSecret();
            s.setSecret("");
        }

        s.save();
        LOG.info("git sync settings saved (remote={}, branch={}, mode={}, problems={})",
            s.getRemoteUrl(), s.getBranch(), s.getMode(), problems.size());

        Map<String, Object> out = new LinkedHashMap<>(describeSettings(GitSyncSettings.load()));
        // Saved is always true once we get here; valid is about the fields, and says
        // nothing yet about whether the remote is reachable.
        out.put("saved", Boolean.TRUE);
        out.put("valid", problems.isEmpty());
        out.put("problems", problems);
        return out;
    }

    /**
     * Lists the branches on a remote, using settings that have not been saved yet.
     *
     * <p>This is what lets the settings form offer a branch dropdown: the operator types a
     * remote URL and credential, and the list comes back before anything is persisted. It
     * uses ls-remote rather than a clone, so it is cheap and needs no working tree.
     *
     * <p>A blank secret falls back to the stored one, matching how saving behaves -- the
     * form never receives the current credential and so cannot send it back.
     */
    public Map<String, Object> remoteBranches(Map<String, String> supplied) {
        Map<String, Object> out = new LinkedHashMap<>();
        GitSyncSettings effective = GitSyncSettings.load();

        if (supplied != null) {
            if (supplied.containsKey("remoteUrl")) {
                effective.setRemoteUrl(supplied.get("remoteUrl"));
            }
            if (supplied.containsKey("username")) {
                effective.setUsername(supplied.get("username"));
            }
            if (supplied.containsKey("knownHosts")) {
                effective.setKnownHosts(supplied.get("knownHosts"));
            }
            if (supplied.containsKey("authType")) {
                try {
                    effective.setAuthType(GitSyncSettings.AuthType.valueOf(
                        supplied.get("authType").trim().toUpperCase()));
                } catch (Exception e) {
                    // Keep the stored type; the save path reports the bad value.
                }
            }
            String secret = supplied.get("secret");
            if (secret != null && !secret.isEmpty()) {
                effective.setSecret(secret);
            }
        }

        if (effective.getRemoteUrl() == null || effective.getRemoteUrl().isBlank()) {
            out.put("ok", Boolean.TRUE);
            out.put("branches", new ArrayList<String>());
            out.put("error", "Enter a remote URL first.");
            return out;
        }

        // Nothing is saved here, so this never leaves the instance configured differently
        // from how the operator left it.
        try (GitRepo probe = new GitRepo(workTree, effective)) {
            List<String> branches = probe.listRemoteBranches();
            out.put("ok", Boolean.TRUE);
            out.put("branches", branches);
            if (branches.isEmpty()) {
                out.put("error", "The remote has no branches yet.");
            }
        } catch (Exception e) {
            out.put("ok", Boolean.TRUE);
            out.put("branches", new ArrayList<String>());
            out.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return out;
    }

    /**
     * Checks whether the saved settings actually work, without changing anything.
     *
     * <p>Structured rather than thrown: the caller is asking precisely because it might
     * fail, and it needs the message to put on the form.
     */
    public Map<String, Object> validate() {
        Map<String, Object> out = new LinkedHashMap<>();
        GitSyncSettings settings = GitSyncSettings.load();

        if (!settings.isConfigured()) {
            out.put("ok", Boolean.TRUE);
            out.put("valid", Boolean.FALSE);
            out.put("error", "A remote URL and branch are required.");
            return out;
        }

        try {
            List<String> branches = withRepo((repo, s, store) -> {
                repo.fetch();
                return repo.branches();
            });
            boolean branchExists = branches.contains(settings.getBranch());

            out.put("ok", Boolean.TRUE);
            out.put("valid", branchExists);
            out.put("branches", branches);
            if (!branchExists) {
                // Reachable but pointed at a branch that is not there: a distinct problem
                // from a bad credential, and a different fix.
                out.put("error", "Connected, but branch \"" + settings.getBranch()
                    + "\" does not exist on the remote. Available: "
                    + String.join(", ", branches));
            }
        } catch (Exception e) {
            out.put("ok", Boolean.TRUE);
            out.put("valid", Boolean.FALSE);
            out.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Pull
    // ------------------------------------------------------------------

    /**
     * Pulls the branch and applies it to the engine.
     *
     * <p>Order matters: export first so the dirty check sees the engine's current state,
     * then reset, then apply. Exporting afterwards would compare against a tree that had
     * already been overwritten.
     */
    /**
     * The uncommitted changes with their line counts.
     *
     * <p>Each entry is one tab-separated string rather than a nested map. XStream renders a
     * list of maps in a shape the console's decoder cannot reliably unpick -- the same trap
     * that made an immutable list arrive as an undecodable blob -- and a flat list of
     * strings is a format that already works. The UI splits on the tab.
     */
    public Map<String, Object> changes(boolean refresh) throws Exception {
        return withRepo((repo, settings, store) -> {
            // Only on request: the page loads status with refresh=true a moment earlier,
            // and exporting twice means reading every channel out of the engine twice.
            if (refresh) {
                store.export(repo.configRoot());
            }
            List<String> rows = new ArrayList<>();
            for (GitRepo.FileChange c : repo.changes()) {
                rows.add(String.join("\t", c.path, c.change,
                    String.valueOf(c.added), String.valueOf(c.removed),
                    c.binary ? "binary" : "text"));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", Boolean.TRUE);
            // Plain ArrayList: see the note in status() about immutable collections.
            out.put("changes", rows);
            return out;
        });
    }

    /** The unified diff of one file, for the UI to show when a change is clicked. */
    public Map<String, Object> diff(String path) throws Exception {
        return withRepo((repo, settings, store) -> {
            // No export here: the change list the user clicked was produced by one, and
            // re-exporting on every click would cost a full engine read per file.
            String text = repo.diff(path);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", Boolean.TRUE);
            out.put("path", path);
            out.put("diff", text);
            out.put("empty", text.isEmpty());
            return out;
        });
    }

    public Map<String, Object> pull(boolean discardLocal) throws Exception {
        return withRepo((repo, settings, store) -> {
            store.export(repo.configRoot());
            String head = repo.pull(discardLocal);

            EngineConfigStore.Result applied = store.apply(repo.configRoot());
            List<String> invalid = store.findInvalidChannels(repo.configRoot());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", applied.ok() && invalid.isEmpty());
            out.put("headCommit", head);
            out.put("applied", applied.getCounts());
            out.put("problems", applied.getProblems());
            // Surfaced separately because the engine reports storing one of these as a
            // success: the channel saves, then never deploys and never runs.
            out.put("invalidChannels", invalid);

            // Re-export so the tree matches what the engine now holds; otherwise the next
            // status would report the engine's normalisation of the XML as a local change.
            store.export(repo.configRoot());
            LOG.info("git sync pulled {} ({})", head, applied.getCounts());
            return out;
        });
    }

    // ------------------------------------------------------------------
    // Commit, push, discard
    // ------------------------------------------------------------------

    public Map<String, Object> commit(String message, boolean push) throws Exception {
        return withRepo((repo, settings, store) -> {
            store.export(repo.configRoot());
            String commit = repo.commit(message);

            Map<String, Object> out = new LinkedHashMap<>();
            if (commit.isEmpty()) {
                out.put("ok", Boolean.TRUE);
                out.put("committed", Boolean.FALSE);
                out.put("message", "nothing to commit");
                return out;
            }
            out.put("ok", Boolean.TRUE);
            out.put("committed", Boolean.TRUE);
            out.put("commit", commit);
            if (push) {
                out.put("push", repo.pushCurrentBranch(true));
            }
            return out;
        });
    }

    public Map<String, Object> push() throws Exception {
        return withRepo((repo, settings, store) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", Boolean.TRUE);
            out.put("push", repo.pushCurrentBranch(true));
            return out;
        });
    }

    public Map<String, Object> discard() throws Exception {
        return withRepo((repo, settings, store) -> {
            repo.discardLocalChanges();
            // Put the engine back to what the tree says, so discarding means the engine
            // matches HEAD rather than just the files matching HEAD.
            EngineConfigStore.Result applied = store.apply(repo.configRoot());
            store.export(repo.configRoot());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", applied.ok());
            out.put("applied", applied.getCounts());
            out.put("problems", applied.getProblems());
            return out;
        });
    }

    // ------------------------------------------------------------------
    // Branches
    // ------------------------------------------------------------------

    public Map<String, Object> previewSwitch(String branch) throws Exception {
        return withRepo((repo, settings, store) -> {
            store.export(repo.configRoot());
            GitRepo.SwitchPreview p = repo.previewSwitch(branch);

            // Read the target branch out of the object database rather than checking it
            // out: the operator is being asked whether to delete these objects, and being
            // asked after the switch has already happened would be too late to say no.
            List<String> orphanRows = new ArrayList<>();
            String orphanError = null;
            try {
                for (EngineConfigStore.Orphan o
                        : store.findOrphans(repo.filesAt(branch))) {
                    orphanRows.add(o.row());
                }
            } catch (Exception e) {
                // Not fatal to the preview: the rest of it is still worth showing, and the
                // UI degrades to offering only the choice that destroys nothing.
                orphanError = e.getMessage() == null ? e.toString() : e.getMessage();
                LOG.warn("git sync could not work out what {} drops", branch, e);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", Boolean.TRUE);
            out.put("fromBranch", p.fromBranch);
            out.put("toBranch", p.toBranch);
            out.put("dirty", p.dirty);
            out.put("pendingChanges", new ArrayList<>(p.pendingChanges));
            out.put("incomingChanges", new ArrayList<>(p.incomingChanges));
            out.put("existsLocally", p.targetExistsLocally);
            out.put("existsOnRemote", p.targetExistsOnRemote);
            // What the UI needs to make the warning concrete rather than generic.
            // Plain ArrayList: see the note in status() about immutable collections.
            out.put("replacesScope", new ArrayList<>(EngineConfigStore.SyncScope
                .parse(settings.getScope())
                .stream().map(EngineConfigStore.SyncScope::token).sorted().toList()));
            // Objects this engine holds that the target branch has no file for, as
            // "<type>\t<id>\t<name>\t<deployed|undeployed>". What happens to them is the
            // operator's decision, so the UI needs them by name to ask the question.
            out.put("orphans", orphanRows);
            if (orphanError != null) {
                out.put("orphanError", orphanError);
            }
            return out;
        });
    }

    /**
     * Switches branch and makes the engine match it.
     *
     * <p>{@code orphanAction} decides the one thing a switch cannot work out for itself:
     * what to do with configuration this engine holds that the incoming branch does not
     * describe. Defaults to keeping it, so a caller that does not know about the question
     * gets the old, non-destructive behaviour.
     */
    public Map<String, Object> switchBranch(String branch, boolean discardLocal,
            EngineConfigStore.OrphanAction orphanAction) throws Exception {
        return withRepo((repo, settings, store) -> {
            store.export(repo.configRoot());
            repo.switchBranch(branch, true, discardLocal);

            // After the checkout the working tree *is* the branch, so it is the cheapest
            // and most honest source for the second look. Taken before apply() so the list
            // is the same one the preview showed and the counts below line up with it.
            List<EngineConfigStore.Orphan> orphans =
                store.findOrphans(EngineConfigStore.directory(repo.configRoot()));

            EngineConfigStore.Result applied = store.apply(repo.configRoot());
            EngineConfigStore.Result orphaned = store.applyOrphanAction(orphans, orphanAction);
            List<String> invalid = store.findInvalidChannels(repo.configRoot());
            // Last, so the tree reflects the deletions too; otherwise the next status would
            // report the objects this call just removed as local changes to commit.
            store.export(repo.configRoot());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", applied.ok() && orphaned.ok() && invalid.isEmpty());
            out.put("branch", branch);
            out.put("applied", applied.getCounts());
            out.put("problems", allProblems(applied, orphaned));
            out.put("invalidChannels", invalid);
            out.put("orphanAction", orphanAction.token());
            out.put("orphanCounts", orphaned.getCounts());
            out.put("orphans", rowsOf(orphans));
            LOG.info("git sync switched to {} and applied {}; {} object(s) the branch does "
                + "not have were handled with {}", branch, applied.getCounts(),
                orphans.size(), orphanAction.token());
            return out;
        });
    }

    /** Both results' problems in one list, so the UI has a single place to look. */
    private static List<String> allProblems(EngineConfigStore.Result... results) {
        List<String> out = new ArrayList<>();
        for (EngineConfigStore.Result r : results) {
            out.addAll(r.getProblems());
        }
        return out;
    }

    private static List<String> rowsOf(List<EngineConfigStore.Orphan> orphans) {
        List<String> rows = new ArrayList<>();
        for (EngineConfigStore.Orphan o : orphans) {
            rows.add(o.row());
        }
        return rows;
    }

    /**
     * Discards everything local and makes the engine match {@code origin/<branch>} exactly.
     *
     * <p>Reports what it destroyed rather than doing it silently. The caller confirmed
     * beforehand, but this list is the only record that survives: the commits themselves
     * do not.
     */
    public Map<String, Object> forceReset(String branch,
            EngineConfigStore.OrphanAction orphanAction) throws Exception {
        return withRepo((repo, settings, store) -> {
            // Export first so "discarded" names the engine's real drift, not whatever
            // happened to be left on disk by the previous operation.
            store.export(repo.configRoot());
            GitRepo.State before = repo.state();
            String target = (branch == null || branch.isBlank())
                ? settings.getBranch() : branch.trim();

            String head = repo.forceReset(target, true);

            // A force reset can change branch as well, so it faces the same question a
            // switch does: this engine may hold configuration the branch never described.
            List<EngineConfigStore.Orphan> orphans =
                store.findOrphans(EngineConfigStore.directory(repo.configRoot()));

            EngineConfigStore.Result applied = store.apply(repo.configRoot());
            EngineConfigStore.Result orphaned = store.applyOrphanAction(orphans, orphanAction);
            List<String> invalid = store.findInvalidChannels(repo.configRoot());
            store.export(repo.configRoot());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", applied.ok() && orphaned.ok() && invalid.isEmpty());
            out.put("branch", target);
            out.put("previousBranch", before.branch);
            out.put("headCommit", head);
            // Plain ArrayList: see the note in status() about immutable collections.
            out.put("discarded", new ArrayList<>(before.pendingChanges));
            out.put("discardedCommits", before.ahead);
            out.put("applied", applied.getCounts());
            out.put("problems", allProblems(applied, orphaned));
            out.put("invalidChannels", invalid);
            out.put("orphanAction", orphanAction.token());
            out.put("orphanCounts", orphaned.getCounts());
            out.put("orphans", rowsOf(orphans));
            LOG.warn("git sync force reset {} -> {}, discarded {} change(s) and {} unpushed "
                + "commit(s), applied {}", before.branch, target,
                before.pendingChanges.size(), before.ahead, applied.getCounts());
            return out;
        });
    }

    public Map<String, Object> createBranch(String branch, boolean push) throws Exception {
        return withRepo((repo, settings, store) -> {
            repo.createBranch(branch, push);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", Boolean.TRUE);
            out.put("branch", branch);
            out.put("pushed", push);
            return out;
        });
    }

    public Map<String, Object> testConnection() throws Exception {
        return withRepo((repo, settings, store) -> {
            repo.fetch();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", Boolean.TRUE);
            out.put("branches", repo.branches());
            return out;
        });
    }
}
