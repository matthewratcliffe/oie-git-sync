/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.gitsync;

import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;

import java.util.Properties;

/**
 * Where this instance syncs from, and what it is allowed to do.
 *
 * <p>Stored through {@code ConfigurationController.saveProperty(pluginName, ...)}, which
 * writes to the {@code configuration} table. That means settings live in the database and
 * survive a container rebuild -- necessary here, because this image resets {@code conf/}
 * and {@code extensions/} from pristine copies on every boot.
 *
 * <p>The credential is encrypted with the engine's own {@link com.mirth.commons.encryption.Encryptor}
 * before being stored, so a database dump does not hand over push access to the
 * configuration repository. It is never returned by the REST API and never written into the
 * working tree.
 */
public final class GitSyncSettings {

    /** Property group; matches the plugin point name so it groups with the extension. */
    static final String GROUP = "Git Sync";

    // Keys as stored in the configuration table.
    private static final String K_REMOTE = "remoteUrl";
    private static final String K_BRANCH = "branch";
    private static final String K_AUTH = "authType";
    private static final String K_USERNAME = "username";
    private static final String K_SECRET = "secret";
    private static final String K_MODE = "mode";
    private static final String K_SUBDIR = "subdirectory";
    private static final String K_AUTHOR_NAME = "authorName";
    private static final String K_AUTHOR_EMAIL = "authorEmail";
    private static final String K_PULL_INTERVAL = "pullIntervalSeconds";
    private static final String K_SCOPE = "scope";
    private static final String K_KNOWN_HOSTS = "knownHosts";

    /** How the remote is authenticated. */
    public enum AuthType {
        /** No credential: a public or otherwise unauthenticated remote. */
        NONE,
        /** HTTPS with a token or password, sent as the HTTP password. */
        HTTPS_TOKEN,
        /** SSH with a private key, using JGit's own SSH transport. */
        SSH_KEY
    }

    /**
     * What this instance may do.
     *
     * <p>The distinction is the whole point of having a mode: a production engine should
     * follow a branch and never author to it, so a mistake in production cannot become a
     * commit that the next environment inherits.
     */
    public enum Mode {
        /** Pull only. Commit and push are rejected by the servlet. */
        READ_ONLY,
        /** Pull, commit and push. */
        READ_WRITE
    }

    private String remoteUrl = "";
    private String branch = "main";
    private AuthType authType = AuthType.NONE;
    private String username = "";
    /** Decrypted only in memory; see {@link #getSecret()}. */
    private String secret = "";
    private Mode mode = Mode.READ_ONLY;
    /** Optional path within the repository, so one repo can hold several environments. */
    private String subdirectory = "";
    private String authorName = "Open Integration Engine";
    private String authorEmail = "oie@localhost";
    /** 0 disables scheduled pulls; the UI and API still pull on demand. */
    private int pullIntervalSeconds = 0;
    /** Comma-separated {@link SyncScope} names, or empty for the default set. */
    private String scope = "";
    /**
     * known_hosts lines for the SSH remote. When empty, host key checking is disabled and
     * the plugin logs a warning -- the first connection then cannot detect a man in the
     * middle, so this is worth filling in.
     */
    private String knownHosts = "";

    public static GitSyncSettings load() {
        ConfigurationController config =
            ControllerFactory.getFactory().createConfigurationController();
        Properties stored = config.getPropertiesForGroup(GROUP);
        if (stored == null) {
            stored = new Properties();
        }

        GitSyncSettings s = new GitSyncSettings();
        s.remoteUrl = stored.getProperty(K_REMOTE, s.remoteUrl);
        s.branch = stored.getProperty(K_BRANCH, s.branch);
        s.authType = parseEnum(AuthType.class, stored.getProperty(K_AUTH), s.authType);
        s.username = stored.getProperty(K_USERNAME, s.username);
        s.mode = parseEnum(Mode.class, stored.getProperty(K_MODE), s.mode);
        s.subdirectory = normaliseSubdirectory(stored.getProperty(K_SUBDIR, s.subdirectory));
        s.authorName = stored.getProperty(K_AUTHOR_NAME, s.authorName);
        s.authorEmail = stored.getProperty(K_AUTHOR_EMAIL, s.authorEmail);
        s.pullIntervalSeconds = parseInt(stored.getProperty(K_PULL_INTERVAL), s.pullIntervalSeconds);
        s.scope = stored.getProperty(K_SCOPE, s.scope);
        s.knownHosts = stored.getProperty(K_KNOWN_HOSTS, s.knownHosts);

        String stashed = stored.getProperty(K_SECRET, "");
        if (stashed != null && !stashed.isEmpty()) {
            try {
                s.secret = config.getEncryptor().decrypt(stashed);
            } catch (Exception e) {
                // A credential that cannot be decrypted is treated as absent rather than
                // fatal: the keystore may have been replaced, and the operator needs the
                // settings page to load so they can re-enter it.
                s.secret = "";
            }
        }
        return s;
    }

    public void save() {
        ConfigurationController config =
            ControllerFactory.getFactory().createConfigurationController();

        config.saveProperty(GROUP, K_REMOTE, remoteUrl == null ? "" : remoteUrl);
        config.saveProperty(GROUP, K_BRANCH, branch == null ? "" : branch);
        config.saveProperty(GROUP, K_AUTH, authType.name());
        config.saveProperty(GROUP, K_USERNAME, username == null ? "" : username);
        config.saveProperty(GROUP, K_MODE, mode.name());
        config.saveProperty(GROUP, K_SUBDIR, subdirectory == null ? "" : subdirectory);
        config.saveProperty(GROUP, K_AUTHOR_NAME, authorName == null ? "" : authorName);
        config.saveProperty(GROUP, K_AUTHOR_EMAIL, authorEmail == null ? "" : authorEmail);
        config.saveProperty(GROUP, K_PULL_INTERVAL, Integer.toString(pullIntervalSeconds));
        config.saveProperty(GROUP, K_SCOPE, scope == null ? "" : scope);
        config.saveProperty(GROUP, K_KNOWN_HOSTS, knownHosts == null ? "" : knownHosts);

        if (secret != null && !secret.isEmpty()) {
            config.saveProperty(GROUP, K_SECRET, config.getEncryptor().encrypt(secret));
        }
    }

    /**
     * Clears the stored credential. Separate from {@link #save()} so that saving settings
     * without resupplying the secret keeps the existing one -- the UI never receives it and
     * so cannot send it back.
     */
    public static void clearSecret() {
        ControllerFactory.getFactory().createConfigurationController()
            .saveProperty(GROUP, K_SECRET, "");
    }

    public boolean isConfigured() {
        return remoteUrl != null && !remoteUrl.isBlank()
            && branch != null && !branch.isBlank();
    }

    public boolean canPush() {
        return mode == Mode.READ_WRITE;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Trims slashes so the value can be joined to the working tree path unambiguously. */
    private static String normaliseSubdirectory(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim().replace('\\', '/');
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        // Refuse traversal outright rather than sanitising into something surprising.
        if (trimmed.contains("..")) {
            throw new IllegalArgumentException("subdirectory must not contain '..'");
        }
        return trimmed;
    }

    public String getRemoteUrl() { return remoteUrl; }
    public void setRemoteUrl(String v) { this.remoteUrl = v == null ? "" : v.trim(); }

    public String getBranch() { return branch; }
    public void setBranch(String v) { this.branch = v == null ? "" : v.trim(); }

    public AuthType getAuthType() { return authType; }
    public void setAuthType(AuthType v) { this.authType = v == null ? AuthType.NONE : v; }

    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v == null ? "" : v.trim(); }

    public String getSecret() { return secret; }
    public void setSecret(String v) { this.secret = v == null ? "" : v; }

    public Mode getMode() { return mode; }
    public void setMode(Mode v) { this.mode = v == null ? Mode.READ_ONLY : v; }

    public String getSubdirectory() { return subdirectory; }
    public void setSubdirectory(String v) { this.subdirectory = normaliseSubdirectory(v); }

    public String getAuthorName() { return authorName; }
    public void setAuthorName(String v) { this.authorName = v == null ? "" : v.trim(); }

    public String getAuthorEmail() { return authorEmail; }
    public void setAuthorEmail(String v) { this.authorEmail = v == null ? "" : v.trim(); }

    public int getPullIntervalSeconds() { return pullIntervalSeconds; }
    public void setPullIntervalSeconds(int v) { this.pullIntervalSeconds = Math.max(0, v); }

    public String getScope() { return scope; }
    public void setScope(String v) { this.scope = v == null ? "" : v.trim(); }

    public String getKnownHosts() { return knownHosts; }
    public void setKnownHosts(String v) { this.knownHosts = v == null ? "" : v; }
}
