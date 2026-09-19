/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.gitsync;

import com.mirth.connect.client.core.ControllerException;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ChannelGroup;
import com.mirth.connect.model.ChannelTag;
import com.mirth.connect.model.ServerEventContext;
import com.mirth.connect.model.ServerSettings;
import com.mirth.connect.model.UpdateSettings;
import com.mirth.connect.model.alert.AlertModel;
import com.mirth.connect.model.codetemplates.CodeTemplate;
import com.mirth.connect.model.codetemplates.CodeTemplateLibrary;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.server.channel.ErrorTaskHandler;
import com.mirth.connect.server.controllers.AlertController;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.CodeTemplateController;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EngineController;
import com.mirth.connect.server.controllers.ExtensionController;
import com.mirth.connect.server.controllers.ScriptController;
import com.mirth.connect.util.ConfigurationProperty;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Translates between engine state and a directory of files.
 *
 * <p>The layout is deliberately identical to what {@code scripts/oie-config-pull.sh}
 * produces, so one repository serves both this plugin and the GitLab pipeline and neither
 * has to know the other exists:
 *
 * <pre>
 *   channels/&lt;name&gt;.xml
 *   code-templates/libraries.xml
 *   code-templates/templates/&lt;name&gt;.xml
 *   channel-groups.xml
 *   configuration-map.properties
 *   alerts/&lt;name&gt;.xml
 *   global-scripts.xml
 * </pre>
 *
 * <p>Objects go through {@link ObjectXMLSerializer}, the same serialiser the
 * Administrator's export uses, and are applied through the engine's own controllers rather
 * than the REST API. That matters for correctness: the controllers reject what they cannot
 * deserialise, whereas a channel pushed as XML that XStream cannot read is stored as an
 * {@code InvalidChannel} stub and reported as a successful save.
 *
 * <p>Files are named from the object's name, with the id kept inside the file. Renaming an
 * object therefore renames its file without changing its identity -- ids are what make the
 * apply step an upsert rather than a duplicate.
 */
public final class EngineConfigStore {

    private static final Logger LOG = LogManager.getLogger(EngineConfigStore.class);

    /** Object types the plugin can sync, selectable per instance. */
    public enum SyncScope {
        CHANNELS("channels"),
        CODE_TEMPLATES("code-templates"),
        CHANNEL_GROUPS("channel-groups"),
        CONFIGURATION_MAP("configuration-map"),
        ALERTS("alerts"),
        GLOBAL_SCRIPTS("global-scripts"),
        /** Settings &gt; Server. */
        SERVER_SETTINGS("server-settings"),
        /** Settings &gt; Administrator (update and notification preferences). */
        ADMINISTRATOR_SETTINGS("administrator-settings"),
        /** Settings &gt; Tags. */
        CHANNEL_TAGS("channel-tags"),
        /** Settings &gt; Resources. */
        RESOURCES("resources"),
        /** Settings &gt; Data Pruner (the plugin's own properties). */
        DATA_PRUNER("data-pruner"),
        /** Volume Monitor rules and settings (this repository's monitoring plugin). */
        VOLUME_MONITOR("volume-monitor");

        private final String token;

        SyncScope(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }

        /**
         * The object types that are the same in every environment.
         *
         * <p>Everything else is opt-in because it legitimately differs: alerts name real
         * recipients, global scripts often carry environment-specific behaviour, and the
         * server settings hold this instance's own name, SMTP host and ports. Syncing any
         * of those by default would let one environment overwrite another's identity.
         */
        public static Set<SyncScope> defaults() {
            return Set.of(CHANNELS, CODE_TEMPLATES, CHANNEL_GROUPS, CONFIGURATION_MAP);
        }

        public static Set<SyncScope> parse(String csv) {
            if (csv == null || csv.isBlank()) {
                return defaults();
            }
            Set<SyncScope> out = new java.util.LinkedHashSet<>();
            for (String part : csv.split(",")) {
                String t = part.trim().toLowerCase(Locale.ROOT);
                if (t.isEmpty()) {
                    continue;
                }
                for (SyncScope s : values()) {
                    if (s.token.equals(t) || s.name().toLowerCase(Locale.ROOT).equals(t)) {
                        out.add(s);
                    }
                }
            }
            return out.isEmpty() ? defaults() : out;
        }
    }

    /** Counts from an export or apply, for reporting back to the UI. */
    public static final class Result {
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        private final List<String> problems = new ArrayList<>();

        void count(String what, int n) {
            counts.merge(what, n, Integer::sum);
        }

        void problem(String message) {
            problems.add(message);
        }

        public Map<String, Integer> getCounts() {
            return counts;
        }

        public List<String> getProblems() {
            return problems;
        }

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    private final Set<SyncScope> scope;

    public EngineConfigStore(Set<SyncScope> scope) {
        this.scope = scope == null || scope.isEmpty() ? SyncScope.defaults() : scope;
    }

    private static ObjectXMLSerializer xml() {
        return ObjectXMLSerializer.getInstance();
    }

    private static ChannelController channels() {
        return ControllerFactory.getFactory().createChannelController();
    }

    private static CodeTemplateController codeTemplates() {
        return ControllerFactory.getFactory().createCodeTemplateController();
    }

    private static ConfigurationController configuration() {
        return ControllerFactory.getFactory().createConfigurationController();
    }

    private static AlertController alerts() {
        return ControllerFactory.getFactory().createAlertController();
    }

    private static ScriptController scripts() {
        return ControllerFactory.getFactory().createScriptController();
    }

    private static ExtensionController extensions() {
        return ControllerFactory.getFactory().createExtensionController();
    }

    private static EngineController engine() {
        return ControllerFactory.getFactory().createEngineController();
    }

    /** The plugin point name the Data Pruner stores its properties under. */
    private static final String DATA_PRUNER_PLUGIN = "Data Pruner";

    /**
     * The {@code configuration} table group the Volume Monitor writes to.
     *
     * <p>It does not use the extension plugin-properties mechanism the Data Pruner does, so
     * this scope goes through the configuration controller instead. The group name is the
     * monitor's {@code VolumeRuleStore.GROUP}; it is repeated here as a literal rather than
     * imported, because the two extensions are built and installed independently and this
     * one must keep working when the monitor is not installed at all.
     */
    private static final String VOLUME_MONITOR_GROUP = "Volume Monitor";

    /**
     * Properties rendered by hand rather than with {@link Properties#store}, which writes
     * a timestamp comment that would make every export a spurious diff. Sorted for the
     * same reason.
     */
    private static String renderProperties(Properties props, String heading) {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(heading).append(", exported by the Git Sync plugin.\n\n");
        if (props != null) {
            Map<String, String> sorted = new TreeMap<>();
            for (String key : props.stringPropertyNames()) {
                sorted.put(key, props.getProperty(key));
            }
            sorted.forEach((k, v) -> out.append(escape(k)).append(" = ")
                .append(v == null ? "" : escape(v)).append('\n'));
        }
        return out.toString();
    }

    private static Properties parseProperties(String text) {
        Properties props = new Properties();
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            props.setProperty(unescape(line.substring(0, eq).trim()),
                unescape(line.substring(eq + 1).trim()));
        }
        return props;
    }

    // ------------------------------------------------------------------
    // Engine -> files
    // ------------------------------------------------------------------

    /**
     * Writes current engine state into {@code root}, replacing the managed directories so
     * that an object deleted in the engine disappears from the tree (and therefore shows up
     * as a deletion in {@code git status}).
     */
    public Result export(Path root) throws IOException, ControllerException {
        Result result = new Result();
        Files.createDirectories(root);

        if (scope.contains(SyncScope.CHANNELS)) {
            Path dir = replaceDirectory(root.resolve("channels"));
            List<Channel> all = sortedByName(channels().getChannels(null),
                Channel::getName, Channel::getId);
            for (Channel channel : all) {
                write(dir.resolve(fileName(channel.getName(), channel.getId())),
                    xml().serialize(channel));
            }
            result.count("channels", all.size());
        }

        if (scope.contains(SyncScope.CODE_TEMPLATES)) {
            Path dir = replaceDirectory(root.resolve("code-templates"));
            Path templates = Files.createDirectories(dir.resolve("templates"));

            // Libraries carry membership, so they are written even when empty -- an empty
            // library list is a meaningful desired state.
            List<CodeTemplateLibrary> libraries = codeTemplates().getLibraries(null, false);
            if (libraries == null) {
                libraries = List.of();
            }
            write(dir.resolve("libraries.xml"), xml().serialize(new ArrayList<>(libraries)));
            result.count("codeTemplateLibraries", libraries.size());

            List<CodeTemplate> all = codeTemplates().getCodeTemplates(null);
            if (all == null) {
                all = List.of();
            }
            for (CodeTemplate template : sortedByName(all,
                    CodeTemplate::getName, CodeTemplate::getId)) {
                write(templates.resolve(fileName(template.getName(), template.getId())),
                    xml().serialize(template));
            }
            result.count("codeTemplates", all.size());
        }

        if (scope.contains(SyncScope.CHANNEL_GROUPS)) {
            List<ChannelGroup> groups = channels().getChannelGroups(null);
            if (groups == null) {
                groups = List.of();
            }
            write(root.resolve("channel-groups.xml"), xml().serialize(new ArrayList<>(groups)));
            result.count("channelGroups", groups.size());
        }

        if (scope.contains(SyncScope.CONFIGURATION_MAP)) {
            Map<String, ConfigurationProperty> map = configuration().getConfigurationProperties();
            if (map == null) {
                map = Map.of();
            }
            write(root.resolve("configuration-map.properties"), renderConfigurationMap(map));
            result.count("configurationMap", map.size());
        }

        if (scope.contains(SyncScope.ALERTS)) {
            Path dir = replaceDirectory(root.resolve("alerts"));
            List<AlertModel> all = alerts().getAlerts();
            if (all == null) {
                all = List.of();
            }
            for (AlertModel alert : sortedByName(all, AlertModel::getName, AlertModel::getId)) {
                write(dir.resolve(fileName(alert.getName(), alert.getId())),
                    xml().serialize(alert));
            }
            result.count("alerts", all.size());
        }

        if (scope.contains(SyncScope.GLOBAL_SCRIPTS)) {
            Map<String, String> global = scripts().getGlobalScripts();
            if (global == null) {
                global = Map.of();
            }
            // A TreeMap so the serialised order is stable and diffs stay readable.
            write(root.resolve("global-scripts.xml"),
                xml().serialize(new TreeMap<>(global)));
            result.count("globalScripts", global.size());
        }

        if (scope.contains(SyncScope.SERVER_SETTINGS)) {
            ServerSettings serverSettings = configuration().getServerSettings();
            if (serverSettings != null) {
                write(root.resolve("server-settings.xml"), xml().serialize(serverSettings));
                result.count("serverSettings", 1);
            }
        }

        if (scope.contains(SyncScope.ADMINISTRATOR_SETTINGS)) {
            UpdateSettings update = configuration().getUpdateSettings();
            if (update != null) {
                write(root.resolve("administrator-settings.xml"), xml().serialize(update));
                result.count("administratorSettings", 1);
            }
        }

        if (scope.contains(SyncScope.CHANNEL_TAGS)) {
            Set<ChannelTag> tags = configuration().getChannelTags();
            // Exported as a sorted list, not the Set: a HashSet iterates in an unstable
            // order, and an unstable order turns every export into a diff.
            List<ChannelTag> ordered = new ArrayList<>(tags == null ? Set.of() : tags);
            ordered.sort(Comparator.comparing(
                t -> t.getName() == null ? "" : t.getName(), String.CASE_INSENSITIVE_ORDER));
            write(root.resolve("channel-tags.xml"), xml().serialize(ordered));
            result.count("channelTags", ordered.size());
        }

        if (scope.contains(SyncScope.RESOURCES)) {
            // Already an XML document rather than a model object, so it goes out as-is.
            String resources = configuration().getResources();
            write(root.resolve("resources.xml"), resources == null ? "" : resources);
            result.count("resources", 1);
        }

        if (scope.contains(SyncScope.DATA_PRUNER)) {
            Properties pruner = extensions().getPluginProperties(DATA_PRUNER_PLUGIN, null);
            write(root.resolve("data-pruner.properties"),
                renderProperties(pruner, "Settings > Data Pruner"));
            result.count("dataPruner", pruner == null ? 0 : pruner.size());
        }

        if (scope.contains(SyncScope.VOLUME_MONITOR)) {
            // One properties file rather than a file per rule: the monitor already stores
            // each rule as a single self-describing line, so a properties file diffs one
            // line per changed rule, which is exactly the granularity a reviewer wants.
            Properties monitor = configuration().getPropertiesForGroup(VOLUME_MONITOR_GROUP);
            write(root.resolve("volume-monitor.properties"),
                renderProperties(monitor, "Volume Monitor rules and settings"));
            result.count("volumeMonitor", monitor == null ? 0 : monitor.size());
        }

        return result;
    }

    // ------------------------------------------------------------------
    // Files -> engine
    // ------------------------------------------------------------------

    /**
     * Applies the tree to the engine as an upsert keyed on each object's id.
     *
     * <p>Deliberately does not delete engine objects absent from the tree. Removing a
     * channel destroys its message history, which is not something a routine pull should do
     * as a side effect of a file being missing; deletions stay a deliberate act, the same
     * choice the CI pipeline makes by not pruning production.
     */
    public Result apply(Path root) throws IOException, ControllerException {
        Result result = new Result();
        ServerEventContext ctx = ServerEventContext.SYSTEM_USER_EVENT_CONTEXT;

        // Code templates first: a channel can reference a template, and applying the
        // channel before the template exists would deploy against a missing function.
        if (scope.contains(SyncScope.CODE_TEMPLATES)) {
            Path libraries = root.resolve("code-templates/libraries.xml");
            if (Files.isRegularFile(libraries)) {
                @SuppressWarnings("unchecked")
                List<CodeTemplateLibrary> list =
                    xml().deserializeList(read(libraries), CodeTemplateLibrary.class);
                codeTemplates().updateLibraries(list, ctx, true);
                result.count("codeTemplateLibraries", list.size());
            }

            int n = 0;
            for (Path file : xmlFilesIn(root.resolve("code-templates/templates"))) {
                try {
                    CodeTemplate template = xml().deserialize(read(file), CodeTemplate.class);
                    codeTemplates().updateCodeTemplate(template, ctx, true);
                    n++;
                } catch (Exception e) {
                    result.problem(relative(root, file) + ": " + e.getMessage());
                }
            }
            result.count("codeTemplates", n);
        }

        if (scope.contains(SyncScope.CHANNEL_GROUPS)) {
            Path file = root.resolve("channel-groups.xml");
            if (Files.isRegularFile(file)) {
                @SuppressWarnings("unchecked")
                List<ChannelGroup> list = xml().deserializeList(read(file), ChannelGroup.class);
                channels().updateChannelGroups(new java.util.LinkedHashSet<>(list), Set.of(), true);
                result.count("channelGroups", list.size());
            }
        }

        if (scope.contains(SyncScope.CHANNELS)) {
            int n = 0;
            for (Path file : xmlFilesIn(root.resolve("channels"))) {
                try {
                    Channel channel = xml().deserialize(read(file), Channel.class);
                    // override=true: the repository is the desired state, so it wins over
                    // the revision counter the Administrator bumps on local edits.
                    channels().updateChannel(channel, ctx, true, Calendar.getInstance());
                    n++;
                } catch (Exception e) {
                    result.problem(relative(root, file) + ": " + e.getMessage());
                }
            }
            result.count("channels", n);
        }

        if (scope.contains(SyncScope.CONFIGURATION_MAP)) {
            Path file = root.resolve("configuration-map.properties");
            if (Files.isRegularFile(file)) {
                Map<String, ConfigurationProperty> map = parseConfigurationMap(read(file));
                configuration().setConfigurationProperties(map, true);
                result.count("configurationMap", map.size());
            }
        }

        if (scope.contains(SyncScope.ALERTS)) {
            int n = 0;
            for (Path file : xmlFilesIn(root.resolve("alerts"))) {
                try {
                    AlertModel alert = xml().deserialize(read(file), AlertModel.class);
                    alerts().updateAlert(alert);
                    n++;
                } catch (Exception e) {
                    result.problem(relative(root, file) + ": " + e.getMessage());
                }
            }
            result.count("alerts", n);
        }

        if (scope.contains(SyncScope.GLOBAL_SCRIPTS)) {
            Path file = root.resolve("global-scripts.xml");
            if (Files.isRegularFile(file)) {
                @SuppressWarnings("unchecked")
                Map<String, String> global = xml().deserialize(read(file), TreeMap.class);
                scripts().setGlobalScripts(new LinkedHashMap<>(global));
                result.count("globalScripts", global.size());
            }
        }

        if (scope.contains(SyncScope.SERVER_SETTINGS)) {
            Path file = root.resolve("server-settings.xml");
            if (Files.isRegularFile(file)) {
                try {
                    configuration().setServerSettings(
                        xml().deserialize(read(file), ServerSettings.class));
                    result.count("serverSettings", 1);
                } catch (Exception e) {
                    result.problem("server-settings.xml: " + e.getMessage());
                }
            }
        }

        if (scope.contains(SyncScope.ADMINISTRATOR_SETTINGS)) {
            Path file = root.resolve("administrator-settings.xml");
            if (Files.isRegularFile(file)) {
                try {
                    configuration().setUpdateSettings(
                        xml().deserialize(read(file), UpdateSettings.class));
                    result.count("administratorSettings", 1);
                } catch (Exception e) {
                    result.problem("administrator-settings.xml: " + e.getMessage());
                }
            }
        }

        if (scope.contains(SyncScope.CHANNEL_TAGS)) {
            Path file = root.resolve("channel-tags.xml");
            if (Files.isRegularFile(file)) {
                try {
                    List<ChannelTag> tags =
                        xml().deserializeList(read(file), ChannelTag.class);
                    configuration().setChannelTags(new LinkedHashSet<>(tags));
                    result.count("channelTags", tags.size());
                } catch (Exception e) {
                    result.problem("channel-tags.xml: " + e.getMessage());
                }
            }
        }

        if (scope.contains(SyncScope.RESOURCES)) {
            Path file = root.resolve("resources.xml");
            if (Files.isRegularFile(file)) {
                String body = read(file).trim();
                // An empty file is treated as "not managed" rather than "remove every
                // resource", which would break every channel that references one.
                if (!body.isEmpty()) {
                    configuration().setResources(body);
                    result.count("resources", 1);
                }
            }
        }

        if (scope.contains(SyncScope.DATA_PRUNER)) {
            Path file = root.resolve("data-pruner.properties");
            if (Files.isRegularFile(file)) {
                try {
                    Properties props = parseProperties(read(file));
                    // merge=false: the file is the desired state, so a property deleted
                    // from it is removed rather than left behind on the engine.
                    extensions().setPluginProperties(DATA_PRUNER_PLUGIN, props, false);
                    result.count("dataPruner", props.size());
                } catch (Exception e) {
                    result.problem("data-pruner.properties: " + e.getMessage());
                }
            }
        }

        if (scope.contains(SyncScope.VOLUME_MONITOR)) {
            Path file = root.resolve("volume-monitor.properties");
            if (Files.isRegularFile(file)) {
                try {
                    Properties props = parseProperties(read(file));
                    Properties existing = configuration().getPropertiesForGroup(VOLUME_MONITOR_GROUP);
                    /*
                     * The file is the desired state, so a rule deleted from it is deleted
                     * here -- but key by key, not with removePropertiesForGroup followed by
                     * a rewrite. The monitor reads this group on a timer from another
                     * thread, and clearing the whole group first would give it a window in
                     * which every channel looks unmonitored.
                     */
                    if (existing != null) {
                        for (String key : existing.stringPropertyNames()) {
                            if (!props.containsKey(key)) {
                                configuration().removeProperty(VOLUME_MONITOR_GROUP, key);
                            }
                        }
                    }
                    for (String key : props.stringPropertyNames()) {
                        configuration().saveProperty(VOLUME_MONITOR_GROUP, key, props.getProperty(key));
                    }
                    result.count("volumeMonitor", props.size());
                } catch (Exception e) {
                    result.problem("volume-monitor.properties: " + describe(e));
                }
            }
        }

        if (!result.ok()) {
            LOG.warn("git sync applied with {} problem(s)", result.getProblems().size());
        }
        return result;
    }

    /**
     * Reads back every channel the tree names and reports any the engine could not
     * deserialise.
     *
     * <p>Worth doing explicitly: {@code updateChannel} can report success while the engine
     * stores Mirth's {@code InvalidChannel} stub, which never deploys and never runs. A
     * pull that leaves channels in that state has to be visible, not silent.
     */
    public List<String> findInvalidChannels(Path root) {
        List<String> invalid = new ArrayList<>();
        for (Path file : xmlFilesIn(root.resolve("channels"))) {
            String id = null;
            try {
                Channel wanted = xml().deserialize(read(file), Channel.class);
                id = wanted.getId();
            } catch (Exception e) {
                // Unreadable locally; apply() already reported it.
                continue;
            }
            List<Channel> found = channels().getChannels(Set.of(id));
            if (found == null || found.isEmpty()) {
                invalid.add(id + " (" + relative(root, file) + "): not present after apply");
                continue;
            }
            Channel stored = found.get(0);
            // The stub has no source connector properties and a fixed description.
            if (stored.getSourceConnector() == null
                || stored.getSourceConnector().getProperties() == null) {
                invalid.add(stored.getName() + " (" + relative(root, file)
                    + "): stored as an invalid channel");
            }
        }
        return invalid;
    }

    // ------------------------------------------------------------------
    // Objects the incoming branch does not contain
    // ------------------------------------------------------------------

    /**
     * What to do with engine objects the branch being adopted has no file for.
     *
     * <p>Adopting a branch is adopting a desired state, so an object that existed only on
     * the branch being left is, strictly, no longer wanted. But "no longer wanted" and
     * "safe to destroy" are not the same sentence: removing a channel takes its message
     * history with it, and that history is often the only record that a message was ever
     * received. So this is asked per switch, with the affected objects listed by name,
     * rather than decided once here as a policy.
     */
    public enum OrphanAction {
        /**
         * Leave them exactly as they are.
         *
         * <p>The engine ends up holding the union of both branches, which is what every
         * version before this one did. Right when the extra objects are this instance's
         * own and the branch was never meant to describe them.
         */
        KEEP("keep"),
        /**
         * Undeploy and disable channels, disable alerts. Delete nothing.
         *
         * <p>Usually the right answer: the engine stops behaving like the branch it left
         * straight away, nothing is destroyed, and a switch made in error costs a
         * re-enable rather than a restore from backup.
         */
        DISABLE("disable"),
        /**
         * Remove them, so the engine holds exactly what the branch describes.
         *
         * <p>A deleted channel takes its message history with it and there is no undo, so
         * this is never the default and never inferred -- the caller names it.
         */
        DELETE("delete");

        private final String token;

        OrphanAction(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }

        /** Anything unrecognised, including null and blank, is {@link #KEEP}. */
        public static OrphanAction parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return KEEP;
            }
            String t = raw.trim().toLowerCase(Locale.ROOT);
            for (OrphanAction a : values()) {
                if (a.token.equals(t) || a.name().toLowerCase(Locale.ROOT).equals(t)) {
                    return a;
                }
            }
            // Deliberately not an exception: the only unsafe misreading of a typo would be
            // turning it into a delete, and falling back to the harmless action cannot.
            return KEEP;
        }
    }

    /** One engine object that the incoming branch has no file for. */
    public static final class Orphan {
        public final String scope;
        public final String id;
        public final String name;
        public final boolean deployed;

        Orphan(String scope, String id, String name, boolean deployed) {
            this.scope = scope;
            this.id = id == null ? "" : id;
            this.name = name == null || name.isBlank() ? "(unnamed)" : name;
            this.deployed = deployed;
        }

        /**
         * Tab-separated for the API, for the same reason the change list is: XStream
         * renders a list of maps in a shape the console's decoder cannot reliably unpick,
         * and a flat list of strings is a format that already works here.
         */
        public String row() {
            return String.join("\t", scope, id, name, deployed ? "deployed" : "undeployed");
        }
    }

    /**
     * The branch's files, as the orphan check sees them.
     *
     * <p>An interface rather than a {@link Path} because the check runs against two
     * different sources: the preview reads the target branch straight out of git's object
     * database, before anything is checked out, and the switch itself reads the working
     * tree once the checkout has happened. Both answer the same two questions.
     */
    public interface FileSource {
        /** Whether a directory or file is present at all -- see the note in findOrphans. */
        boolean exists(String path);

        /** Paths under {@code dir}, relative to the config root. Empty when absent. */
        List<String> list(String dir);

        String read(String path) throws IOException;
    }

    /** A {@link FileSource} over a checked-out working tree. */
    public static FileSource directory(Path root) {
        return new FileSource() {
            @Override
            public boolean exists(String path) {
                return Files.exists(root.resolve(path));
            }

            @Override
            public List<String> list(String dir) {
                Path d = root.resolve(dir);
                if (!Files.isDirectory(d)) {
                    return List.of();
                }
                try (var stream = Files.list(d)) {
                    return stream.filter(Files::isRegularFile)
                        .map(p -> dir + "/" + p.getFileName())
                        .sorted()
                        .collect(Collectors.toList());
                } catch (IOException e) {
                    LOG.error("could not list {}", d, e);
                    return List.of();
                }
            }

            @Override
            public String read(String path) throws IOException {
                return EngineConfigStore.read(root.resolve(path));
            }
        };
    }

    /**
     * The object's own id: the first {@code <id>} element, which every exported object
     * carries as its first child.
     */
    private static final Pattern ID_ELEMENT = Pattern.compile("<id>\\s*([^<]+?)\\s*</id>");

    /**
     * Engine objects in scope that {@code desired} holds no file for.
     *
     * <p><b>An absent directory means "this branch does not describe that type", never
     * "delete everything of that type".</b> Git does not track empty directories, so a
     * branch whose channels were all deleted and a branch that never had a
     * {@code channels/} folder look identical once checked out. Reading the second as the
     * first would offer to delete every channel on the engine, message history included,
     * because of a folder that was never there. Each type is therefore skipped outright
     * unless its directory is present.
     *
     * <p>Ids are pulled out with a regex rather than by deserialising. A channel written by
     * a different engine version, or one this engine cannot read at all, still counts as
     * present -- and the failure that matters here is the other direction, where a file
     * that would not parse turns into an object offered for deletion.
     *
     * <p>Channel groups are absent from this check on purpose:
     * {@code ChannelController.updateChannelGroups} already treats the set it is given as
     * the complete list and deletes the rest, so {@link #apply} reconciles them in full
     * whenever the branch carries {@code channel-groups.xml}. Handling them here as well
     * would delete every group on the engine.
     */
    public List<Orphan> findOrphans(FileSource desired) throws ControllerException {
        List<Orphan> orphans = new ArrayList<>();

        if (scope.contains(SyncScope.CHANNELS) && desired.exists("channels")) {
            Set<String> wanted = idsIn(desired, "channels");
            Set<String> deployed = deployedChannelIds();
            for (Channel c : sortedByName(channels().getChannels(null),
                    Channel::getName, Channel::getId)) {
                if (c.getId() != null && !wanted.contains(c.getId())) {
                    orphans.add(new Orphan(SyncScope.CHANNELS.token(), c.getId(), c.getName(),
                        deployed.contains(c.getId())));
                }
            }
        }

        if (scope.contains(SyncScope.CODE_TEMPLATES)
                && desired.exists("code-templates/templates")) {
            Set<String> wanted = idsIn(desired, "code-templates/templates");
            List<CodeTemplate> all = codeTemplates().getCodeTemplates(null);
            for (CodeTemplate t : sortedByName(all, CodeTemplate::getName, CodeTemplate::getId)) {
                if (t.getId() != null && !wanted.contains(t.getId())) {
                    orphans.add(new Orphan(SyncScope.CODE_TEMPLATES.token(), t.getId(),
                        t.getName(), false));
                }
            }
        }

        if (scope.contains(SyncScope.ALERTS) && desired.exists("alerts")) {
            Set<String> wanted = idsIn(desired, "alerts");
            List<AlertModel> all = alerts().getAlerts();
            for (AlertModel a : sortedByName(all, AlertModel::getName, AlertModel::getId)) {
                if (a.getId() != null && !wanted.contains(a.getId())) {
                    orphans.add(new Orphan(SyncScope.ALERTS.token(), a.getId(), a.getName(),
                        false));
                }
            }
        }

        return orphans;
    }

    private static Set<String> idsIn(FileSource files, String dir) {
        Set<String> ids = new LinkedHashSet<>();
        for (String path : files.list(dir)) {
            if (!path.endsWith(".xml")) {
                continue;
            }
            try {
                Matcher m = ID_ELEMENT.matcher(files.read(path));
                if (m.find()) {
                    ids.add(m.group(1));
                } else {
                    // No id at all is not something an export produces, so the file is
                    // either hand-written or truncated. Logged rather than ignored, because
                    // the consequence is an object looking like one the branch dropped.
                    LOG.warn("git sync found no <id> in {}; anything it describes will look "
                        + "like it is missing from this branch", path);
                }
            } catch (IOException e) {
                LOG.error("could not read {} while looking for objects this branch drops",
                    path, e);
            }
        }
        return ids;
    }

    private static Set<String> deployedChannelIds() {
        try {
            Set<String> ids = engine().getDeployedIds();
            return ids == null ? Set.of() : ids;
        } catch (Exception e) {
            // The engine may not be running yet. Not knowing whether a channel is deployed
            // costs a word in the preview; it is not a reason to fail the check.
            LOG.debug("could not read deployed channel ids", e);
            return Set.of();
        }
    }

    /**
     * Carries out {@code action} on the objects {@link #findOrphans} returned.
     *
     * <p>Reports what actually happened rather than what was asked for: channel removal
     * goes through the engine's task queue, and a channel that refuses to stop is
     * something the operator has to hear about rather than something that quietly counts
     * as done.
     */
    public Result applyOrphanAction(List<Orphan> orphans, OrphanAction action) {
        Result result = new Result();
        if (orphans == null || orphans.isEmpty()
                || action == null || action == OrphanAction.KEEP) {
            return result;
        }
        ServerEventContext ctx = ServerEventContext.SYSTEM_USER_EVENT_CONTEXT;

        Set<String> channelIds = idsOf(orphans, SyncScope.CHANNELS);
        Set<String> templateIds = idsOf(orphans, SyncScope.CODE_TEMPLATES);
        Set<String> alertIds = idsOf(orphans, SyncScope.ALERTS);

        if (action == OrphanAction.DISABLE) {
            if (!channelIds.isEmpty()) {
                try {
                    // Undeploy first: the enabled flag governs the next deploy, so a
                    // channel already running would keep running until it is undeployed.
                    ErrorTaskHandler handler = new ErrorTaskHandler();
                    engine().undeployChannels(channelIds, ctx, handler);
                    if (handler.isErrored()) {
                        result.problem("undeploying channels this branch does not have: "
                            + describe(handler.getError()));
                    }
                    channels().setChannelEnabled(channelIds, ctx, false);
                    result.count("channelsDisabled", channelIds.size());
                } catch (Exception e) {
                    result.problem("disabling channels this branch does not have: "
                        + describe(e));
                }
            }
            for (String id : alertIds) {
                try {
                    alerts().disableAlert(id);
                    result.count("alertsDisabled", 1);
                } catch (Exception e) {
                    result.problem("disabling alert " + id + ": " + describe(e));
                }
            }
            if (!templateIds.isEmpty()) {
                // A code template has no enabled state -- it is a function a channel either
                // calls or does not. Counted rather than dropped silently, so the total in
                // the UI accounts for every object that was listed.
                result.count("codeTemplatesLeft", templateIds.size());
            }
            LOG.info("git sync disabled {} channel(s) and {} alert(s) the branch does not have",
                channelIds.size(), alertIds.size());
            return result;
        }

        // DELETE from here down.
        if (!channelIds.isEmpty()) {
            try {
                ErrorTaskHandler handler = new ErrorTaskHandler();
                // One blocking call that stops, undeploys and removes, so a deployed
                // channel cannot be left half-removed.
                engine().removeChannels(channelIds, ctx, handler);
                if (handler.isErrored()) {
                    result.problem("removing channels this branch does not have: "
                        + describe(handler.getError()));
                }
                // Counted from what survived, not from what was asked for.
                List<Channel> left = channels().getChannels(channelIds);
                int remaining = left == null ? 0 : left.size();
                result.count("channelsDeleted", channelIds.size() - remaining);
                if (remaining > 0) {
                    result.problem(remaining + " channel(s) this branch does not have could "
                        + "not be removed and are still on the engine");
                }
            } catch (Exception e) {
                result.problem("removing channels this branch does not have: " + describe(e));
            }
        }
        for (String id : templateIds) {
            try {
                codeTemplates().removeCodeTemplate(id, ctx);
                result.count("codeTemplatesDeleted", 1);
            } catch (Exception e) {
                result.problem("removing code template " + id + ": " + describe(e));
            }
        }
        for (String id : alertIds) {
            try {
                alerts().removeAlert(id);
                result.count("alertsDeleted", 1);
            } catch (Exception e) {
                result.problem("removing alert " + id + ": " + describe(e));
            }
        }
        // WARN rather than INFO: this destroys message history, so it should be findable in
        // the log afterwards without having known to look for it.
        LOG.warn("git sync deleted {} channel(s), {} code template(s) and {} alert(s) that "
            + "the branch does not have", channelIds.size(), templateIds.size(),
            alertIds.size());
        return result;
    }

    private static Set<String> idsOf(List<Orphan> orphans, SyncScope wanted) {
        Set<String> ids = new LinkedHashSet<>();
        for (Orphan o : orphans) {
            if (wanted.token().equals(o.scope) && !o.id.isEmpty()) {
                ids.add(o.id);
            }
        }
        return ids;
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    // ------------------------------------------------------------------
    // Configuration map as a properties file
    // ------------------------------------------------------------------

    /**
     * Rendered by hand rather than with {@link Properties#store} so the output has no
     * timestamp comment -- a timestamp would make every export a spurious diff.
     */
    private static String renderConfigurationMap(Map<String, ConfigurationProperty> map) {
        StringBuilder out = new StringBuilder();
        out.append("# Configuration map, exported by the Git Sync plugin.\n");
        out.append("# Values may be overridden per environment; see docs/git-sync.md.\n\n");
        new TreeMap<>(map).forEach((key, value) -> {
            String comment = value == null ? null : value.getComment();
            if (comment != null && !comment.isBlank()) {
                for (String line : comment.split("\r?\n")) {
                    out.append("# ").append(line).append('\n');
                }
            }
            out.append(escape(key)).append(" = ")
               .append(value == null || value.getValue() == null ? "" : escape(value.getValue()))
               .append('\n');
        });
        return out.toString();
    }

    private static Map<String, ConfigurationProperty> parseConfigurationMap(String text) {
        Map<String, ConfigurationProperty> map = new LinkedHashMap<>();
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = unescape(line.substring(0, eq).trim());
            String value = unescape(line.substring(eq + 1).trim());
            map.put(key, new ConfigurationProperty(value, ""));
        }
        return map;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "");
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\\\", "\\");
    }

    // ------------------------------------------------------------------
    // Filesystem helpers
    // ------------------------------------------------------------------

    /** Empties a managed directory so removals in the engine surface as file deletions. */
    private static Path replaceDirectory(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (var stream = Files.walk(dir)) {
                for (Path p : stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                    if (!p.equals(dir)) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        }
        return Files.createDirectories(dir);
    }

    private static List<Path> xmlFilesIn(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".xml"))
                .sorted()
                .collect(Collectors.toList());
        } catch (IOException e) {
            LOG.error("could not list {}", dir, e);
            return List.of();
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        // Normalised to LF: the tree is compared by git, and CRLF would make every file
        // look modified on a Windows checkout.
        Files.writeString(file, content.replace("\r\n", "\n"), StandardCharsets.UTF_8);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String relative(Path root, Path file) {
        try {
            return root.relativize(file).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    /**
     * A filesystem-safe name, falling back to the id when a name slugifies to nothing
     * (a channel named only in a non-Latin script, for instance).
     */
    static String fileName(String name, String id) {
        String slug = name == null ? "" : name.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) {
            slug = id == null ? "unnamed" : id;
        }
        if (slug.length() > 80) {
            slug = slug.substring(0, 80);
        }
        return slug + ".xml";
    }

    private static <T> List<T> sortedByName(List<T> items,
                                            java.util.function.Function<T, String> name,
                                            java.util.function.Function<T, String> id) {
        List<T> copy = new ArrayList<>(items == null ? List.of() : items);
        // Sorted by name then id so two exports of the same state produce the same tree and
        // an unchanged engine produces an empty diff.
        copy.sort(Comparator
            .comparing((T t) -> name.apply(t) == null ? "" : name.apply(t),
                String.CASE_INSENSITIVE_ORDER)
            .thenComparing(t -> id.apply(t) == null ? "" : id.apply(t)));
        return copy;
    }
}
