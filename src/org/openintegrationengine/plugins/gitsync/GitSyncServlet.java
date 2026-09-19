/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.gitsync;

import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.server.api.MirthServlet;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements {@link GitSyncServletInterface} by delegating to {@link GitSyncService}.
 *
 * <p>The servlet's own job is narrow: map service outcomes onto HTTP, and refuse what this
 * instance is not allowed to do. The interesting decisions live in the service and in
 * {@link GitRepo}, where they cannot be skipped by calling a different endpoint.
 */
public class GitSyncServlet extends MirthServlet implements GitSyncServletInterface {

    public GitSyncServlet(@Context HttpServletRequest request,
                          @Context SecurityContext securityContext) {
        super(request, securityContext, GitSyncServicePlugin.PLUGIN_POINT_NAME);
    }

    private static GitSyncService service() {
        GitSyncService s = GitSyncServicePlugin.service();
        if (s == null) {
            // The extension is installed but its ServicePlugin never started, which is a
            // server-side fault rather than a bad request.
            throw new MirthApiException(Response.Status.SERVICE_UNAVAILABLE);
        }
        return s;
    }

    /**
     * Rejects a write on a pull-only instance.
     *
     * <p>Enforced here rather than only hidden in the UI: the whole point of the read-only
     * mode is that a production engine cannot author to the branch it follows, and a mode
     * that a direct API call can step around would not deliver that.
     */
    private static void requireWritable() {
        if (!GitSyncSettings.load().canPush()) {
            throw new MirthApiException(Response
                .status(Response.Status.FORBIDDEN)
                .entity("this instance is configured read-only; commit and push are disabled")
                .build());
        }
    }

    private static Map<String, Object> ok() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.TRUE);
        return out;
    }

    /**
     * Maps a refusal onto 409 Conflict with the blocking file list attached.
     *
     * <p>409 rather than 400: the request was valid, the server's state makes it
     * unsafe, and the caller can resolve it by committing or discarding.
     */
    private static MirthApiException conflict(String message, List<String> changes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", Boolean.FALSE);
        body.put("error", message);
        body.put("pendingChanges", changes == null
            ? new ArrayList<String>() : new ArrayList<>(changes));
        return new MirthApiException(Response
            .status(Response.Status.CONFLICT)
            .entity(body)
            .build());
    }

    /**
     * A failed operation, described in plain language.
     *
     * <p>Returned as a 200 with {@code ok:false} rather than thrown. Throwing here means
     * the engine serialises the whole {@link Throwable} -- class name, message and a
     * forty-frame stack trace -- straight into the page, which tells the operator nothing
     * and leaks internal class names. A push the remote refused is also not a server
     * fault: it is the answer.
     */
    private static Map<String, Object> failure(Throwable t) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", Boolean.FALSE);
        body.put("error", GitErrors.friendly(t));
        // Kept for a disclosure in the UI, so the detail is available without being the
        // first thing anyone reads.
        body.put("detail", GitErrors.technical(t));
        return body;
    }

    private static MirthApiException badRequest(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", Boolean.FALSE);
        body.put("error", message);
        return new MirthApiException(Response
            .status(Response.Status.BAD_REQUEST)
            .entity(body)
            .build());
    }

    @Override
    public Map<String, Object> getStatus(boolean refresh) {
        GitSyncService svc = service();

        // Not configured is a normal state, not a fault: the extension can be installed
        // long before anyone points it at a repository, and the UI needs a clean
        // "configured: false" so it can show the settings form instead of an error.
        if (!GitSyncSettings.load().isConfigured()) {
            return svc.statusQuiet();
        }
        try {
            return svc.status(refresh);
        } catch (Exception e) {
            // A configured-but-broken repository (bad credential, unreachable remote) is
            // also something the page has to render rather than blow up on, so it comes
            // back as ok:false with the message instead of a stack trace.
            Map<String, Object> body = failure(e);
            body.put("configured", Boolean.TRUE);
            return body;
        }
    }

    @Override
    public Map<String, Object> getSettings() {
        return GitSyncService.describeSettings(GitSyncSettings.load());
    }

    @Override
    public Map<String, Object> setSettings(Map<String, String> settings) {
        if (settings == null) {
            throw badRequest("no settings supplied");
        }
        try {
            // Field-level problems come back inside the body rather than as a 4xx: the
            // settings are saved either way, and the caller needs the saved values plus
            // the list of what it could not use.
            return service().updateSettings(settings);
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> remoteBranches(Map<String, String> settings) {
        // An unreachable remote is the expected answer while someone is still typing, so
        // it comes back as an empty list plus a message rather than a failure status.
        return service().remoteBranches(settings);
    }

    @Override
    public Map<String, Object> validateSettings() {
        // Never a failure response: "these settings do not work" is the answer the caller
        // asked for, not an error in answering.
        return service().validate();
    }

    @Override
    public Map<String, Object> getBranches() {
        try {
            Map<String, Object> out = ok();
            out.put("branches", service().branches());
            out.put("current", service().currentBranch());
            return out;
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> changes(boolean refresh) {
        try {
            return service().changes(refresh);
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> diff(String path) {
        if (path == null || path.isBlank()) {
            throw badRequest("path is required");
        }
        try {
            return service().diff(path);
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> pull(boolean discardLocal) {
        try {
            return service().pull(discardLocal);
        } catch (GitRepo.DirtyTreeException e) {
            throw conflict(e.getMessage(), e.getChanges());
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> commit(String message, boolean push) {
        requireWritable();
        try {
            return service().commit(message, push);
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> push() {
        requireWritable();
        try {
            return service().push();
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> discard() {
        try {
            return service().discard();
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> previewSwitch(String branch) {
        if (branch == null || branch.isBlank()) {
            throw badRequest("branch is required");
        }
        try {
            return service().previewSwitch(branch);
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> switchBranch(String branch, boolean confirm,
            boolean discardLocal, String orphanAction) {
        if (branch == null || branch.isBlank()) {
            throw badRequest("branch is required");
        }
        if (!confirm) {
            // Not a 400 for a malformed request but for a missing acknowledgement: this
            // replaces the engine's configuration, so the caller has to say so explicitly.
            throw badRequest("switching branch replaces this engine's configuration; "
                + "resend with confirm=true");
        }
        try {
            return service().switchBranch(branch, discardLocal, orphan(orphanAction));
        } catch (GitRepo.DirtyTreeException e) {
            throw conflict(e.getMessage(), e.getChanges());
        } catch (Exception e) {
            return failure(e);
        }
    }

    /**
     * Reads the orphan action, refusing a value it does not recognise.
     *
     * <p>{@link EngineConfigStore.OrphanAction#parse} falls back to keeping, which is right
     * for an old client that does not send the parameter at all but wrong here: a caller
     * that meant {@code delete} and typed {@code remove} would be told the switch
     * succeeded while the objects it asked about are still running. Silence is the default;
     * a typo is a mistake worth naming.
     */
    private static EngineConfigStore.OrphanAction orphan(String raw) {
        if (raw == null || raw.isBlank()) {
            return EngineConfigStore.OrphanAction.KEEP;
        }
        EngineConfigStore.OrphanAction parsed = EngineConfigStore.OrphanAction.parse(raw);
        if (parsed == EngineConfigStore.OrphanAction.KEEP
                && !"keep".equalsIgnoreCase(raw.trim())) {
            throw badRequest("orphanAction \"" + raw + "\" is not recognised; use keep, "
                + "disable or delete");
        }
        return parsed;
    }

    @Override
    public Map<String, Object> forceReset(String branch, boolean confirm,
            String orphanAction) {
        // No requireWritable(): see the note on the interface method. This is a write to
        // the engine, not to the remote.
        if (!confirm) {
            throw badRequest("a force reset destroys uncommitted changes and unpushed "
                + "commits and replaces this engine's configuration; resend with "
                + "confirm=true");
        }
        try {
            return service().forceReset(branch, orphan(orphanAction));
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> createBranch(String branch, boolean push) {
        requireWritable();
        if (branch == null || branch.isBlank()) {
            throw badRequest("branch is required");
        }
        try {
            return service().createBranch(branch, push);
        } catch (IllegalArgumentException e) {
            // A bad branch name is the caller's mistake, and the message is already
            // plain ("not a valid branch name: ...").
            throw badRequest(e.getMessage());
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> testConnection() {
        try {
            return service().testConnection();
        } catch (Exception e) {
            // A failed connection test is a normal answer, not a server error: the
            // operator is testing precisely because it might not work.
            return failure(e);
        }
    }
}
