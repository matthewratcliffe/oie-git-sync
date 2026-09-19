/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.gitsync;

import com.mirth.connect.model.ExtensionPermission;
import com.mirth.connect.plugins.ServicePlugin;
import com.mirth.connect.server.controllers.ControllerFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Lifecycle for the Git Sync extension.
 *
 * <p>Deliberately does nothing at startup beyond making the service available. An
 * automatic pull on boot is tempting for the GitOps case, but it would mean a container
 * restart silently rewrites the engine's configuration -- so it happens only when
 * {@code pullIntervalSeconds} is set, which is an explicit opt-in.
 */
public class GitSyncServicePlugin implements ServicePlugin {

    public static final String PLUGIN_POINT_NAME = "Git Sync";

    private static final Logger LOG = LogManager.getLogger(GitSyncServicePlugin.class);

    /**
     * Shared with the servlet, which is constructed per request by JAX-RS and so cannot
     * hold state of its own.
     */
    private static volatile GitSyncService service;

    private ScheduledExecutorService scheduler;

    public static GitSyncService service() {
        return service;
    }

    @Override
    public void init(Properties properties) {
        // The working tree lives under appdata/, which compose maps to a named volume, so a
        // clone survives container recreation. Putting it anywhere else in the image would
        // mean re-cloning the repository on every restart.
        String baseDir = ControllerFactory.getFactory()
            .createConfigurationController().getBaseDir();
        Path workTree = Path.of(baseDir, "appdata", "gitsync", "worktree");

        service = new GitSyncService(workTree);
        LOG.info("git sync working tree: {}", workTree);
    }

    @Override
    public void start() {
        GitSyncSettings settings = GitSyncSettings.load();
        if (!settings.isConfigured()) {
            LOG.info("git sync is installed but not configured; nothing scheduled");
            return;
        }

        int interval = settings.getPullIntervalSeconds();
        if (interval <= 0) {
            LOG.info("git sync configured for {} on branch {} ({}); scheduled pull disabled",
                settings.getRemoteUrl(), settings.getBranch(), settings.getMode());
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "git-sync-pull");
            // Daemon so a pending pull cannot delay engine shutdown.
            t.setDaemon(true);
            return t;
        });

        // Fixed delay, not fixed rate: a pull that takes longer than the interval should
        // not queue another behind it.
        scheduler.scheduleWithFixedDelay(this::scheduledPull, interval, interval,
            TimeUnit.SECONDS);
        LOG.info("git sync will pull {} every {}s", settings.getBranch(), interval);
    }

    /**
     * One scheduled pull.
     *
     * <p>Never allowed to throw: an exception escaping a scheduled task cancels all future
     * runs, which would turn one network blip into a permanently stopped sync.
     */
    private void scheduledPull() {
        GitSyncService current = service;
        if (current == null) {
            return;
        }
        try {
            Map<String, Object> result = current.pull(false);
            if (Boolean.TRUE.equals(result.get("ok"))) {
                LOG.debug("git sync scheduled pull: {}", result.get("applied"));
            } else {
                LOG.warn("git sync scheduled pull finished with problems: {}", result);
            }
        } catch (GitRepo.DirtyTreeException e) {
            // The engine holds changes that are not committed. Refusing is the designed
            // behaviour, so this is expected rather than an error -- but it does mean the
            // instance has stopped following the branch, which is worth saying out loud.
            LOG.warn("git sync scheduled pull skipped: {} ({} pending change(s))",
                e.getMessage(), e.getChanges().size());
        } catch (Exception e) {
            LOG.error("git sync scheduled pull failed", e);
        }
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        service = null;
    }

    @Override
    public void update(Properties properties) {
        // Settings live in the configuration table, not in plugin properties, so there is
        // nothing to reload here. A changed interval takes effect on the next restart; the
        // repository itself is reopened per operation, so remote, branch and credential
        // changes apply immediately.
    }

    @Override
    public Properties getDefaultProperties() {
        return new Properties();
    }

    @Override
    public ExtensionPermission[] getExtensionPermissions() {
        return new ExtensionPermission[] {
            new ExtensionPermission(PLUGIN_POINT_NAME, "Sync with Git",
                "Allows pulling, committing and pushing engine configuration.",
                new String[] {
                    "gitSyncStatus", "gitSyncGetSettings", "gitSyncSetSettings",
                    "gitSyncBranches", "gitSyncPull", "gitSyncCommit", "gitSyncPush",
                    "gitSyncDiscard", "gitSyncPreviewSwitch", "gitSyncSwitchBranch",
                    "gitSyncCreateBranch", "gitSyncTest"
                },
                new String[] {})
        };
    }

    @Override
    public String getPluginPointName() {
        return PLUGIN_POINT_NAME;
    }

    @Override
    public Map<String, Object> getObjectsForSwaggerExamples() {
        return Map.of();
    }
}
