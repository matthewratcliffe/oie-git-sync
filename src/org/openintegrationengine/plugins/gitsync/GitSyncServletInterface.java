/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.gitsync;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.client.core.Operation;
import com.mirth.connect.client.core.api.BaseServletInterface;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.client.core.api.Param;


import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import java.util.Map;

/**
 * REST surface for the Git Sync plugin, served at {@code /api/gitsync}.
 *
 * <p>Everything the console UI needs, and nothing that only the UI could enforce: the
 * read-only mode check, the refusal to pull over uncommitted changes and the branch-switch
 * confirmation all live behind these endpoints, so bypassing the UI does not bypass them.
 *
 * <p>Responses are plain {@code Map}s rather than model classes. The engine's XStream
 * serialiser needs every custom type allow-listed, and a map of primitives avoids adding a
 * serialisation concern to what is otherwise a thin status API.
 */
@Path("/gitsync")
@Consumes({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
@Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
public interface GitSyncServletInterface extends BaseServletInterface {

    /**
     * Current branch, head commit, ahead/behind counts and the list of pending changes.
     *
     * <p>Computed by exporting engine state into the working tree first, so "pending
     * changes" means what the engine holds that git does not -- which is what the UI needs
     * to show and what blocks a pull.
     */
    @GET
    @Path("/status")
    @io.swagger.v3.oas.annotations.Operation(summary = "Current branch, head commit and pending changes.")
    @MirthOperation(name = "gitSyncStatus", display = "Get git sync status", auditable = false)
    Map<String, Object> getStatus(
        @Param("refresh")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "If true, re-export engine state into the working tree before comparing. "
            + "False returns the last known state without touching the engine.")
        @QueryParam("refresh") boolean refresh) throws ClientException;

    /** Settings without the credential; the secret is never returned. */
    @GET
    @Path("/settings")
    @io.swagger.v3.oas.annotations.Operation(summary = "Get the sync settings, excluding the credential.")
    @MirthOperation(name = "gitSyncGetSettings", display = "Get git sync settings", auditable = false)
    Map<String, Object> getSettings() throws ClientException;

    /**
     * Updates settings. An absent or empty {@code secret} leaves the stored credential
     * alone, so saving the form without retyping it does not wipe it.
     */
    @POST
    @Path("/settings")
    @io.swagger.v3.oas.annotations.Operation(summary = "Update the sync settings.")
    @MirthOperation(name = "gitSyncSetSettings", display = "Update git sync settings",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> setSettings(
        @Param(value = "settings", excludeFromAudit = true)
        @io.swagger.v3.oas.annotations.Parameter(description = "The settings to store.")
        Map<String, String> settings) throws ClientException;

    /** Local and remote branches. */
    @GET
    @Path("/branches")
    @io.swagger.v3.oas.annotations.Operation(summary = "List local and remote branches.")
    @MirthOperation(name = "gitSyncBranches", display = "List git branches", auditable = false)
    Map<String, Object> getBranches() throws ClientException;

    /**
     * Applies {@code origin/<branch>} to the engine.
     *
     * <p>Refuses with the pending-change list when the engine holds uncommitted changes,
     * unless {@code discardLocal} is set. A pull is a hard reset, so there is no merge that
     * could preserve both sides.
     */
    /**
     * Lists uncommitted changes with their added/removed line counts.
     *
     * <p>Separate from {@code /status} so the change list can be refreshed on its own, and
     * so a repository large enough for the counts to cost something does not slow down the
     * status the navigation menu reads on every page load.
     */
    @GET
    @Path("/changes")
    @io.swagger.v3.oas.annotations.Operation(
        summary = "List uncommitted changes with line counts.")
    @MirthOperation(name = "gitSyncChanges", display = "List git changes", auditable = false)
    Map<String, Object> changes(
        @Param("refresh")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "Re-export engine state first. Leave false straight after a refreshed status.")
        @QueryParam("refresh") boolean refresh) throws ClientException;

    /**
     * Returns the unified diff of one file, HEAD against the working tree.
     */
    @GET
    @Path("/diff")
    @io.swagger.v3.oas.annotations.Operation(summary = "Diff one file against HEAD.")
    @MirthOperation(name = "gitSyncDiff", display = "Diff a git file", auditable = false)
    Map<String, Object> diff(
        @Param("path")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "Repository-relative path of the file to diff.", required = true)
        @QueryParam("path") String path) throws ClientException;

    @POST
    @Path("/_pull")
    @io.swagger.v3.oas.annotations.Operation(summary = "Pull the configured branch and apply it to the engine.")
    @MirthOperation(name = "gitSyncPull", display = "Pull from git",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> pull(
        @Param("discardLocal")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "If true, discard uncommitted engine changes instead of refusing.")
        @QueryParam("discardLocal") boolean discardLocal) throws ClientException;

    /** Commits pending engine changes. Rejected in read-only mode. */
    @POST
    @Path("/_commit")
    @io.swagger.v3.oas.annotations.Operation(summary = "Commit pending engine changes.")
    @MirthOperation(name = "gitSyncCommit", display = "Commit to git",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> commit(
        @Param("message")
        @io.swagger.v3.oas.annotations.Parameter(description = "Commit message.")
        @QueryParam("message") String message,
        @Param("push")
        @io.swagger.v3.oas.annotations.Parameter(description = "Push immediately after committing.")
        @QueryParam("push") boolean push) throws ClientException;

    /** Pushes the current branch. Rejected in read-only mode. */
    @POST
    @Path("/_push")
    @io.swagger.v3.oas.annotations.Operation(summary = "Push the current branch.")
    @MirthOperation(name = "gitSyncPush", display = "Push to git",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> push() throws ClientException;

    /** Discards uncommitted engine changes, restoring the tree to HEAD. */
    @POST
    @Path("/_discard")
    @io.swagger.v3.oas.annotations.Operation(summary = "Discard uncommitted engine changes.")
    @MirthOperation(name = "gitSyncDiscard", display = "Discard local git changes",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> discard() throws ClientException;

    /**
     * Reports what switching to {@code branch} would change, without changing anything.
     *
     * <p>Exists so the confirmation can name the damage: switching replaces the engine's
     * channels, code templates and everything else in scope, and a generic warning teaches
     * people to click through it.
     *
     * <p>Also lists, as {@code orphans}, the channels, code templates and alerts this
     * engine holds that the target branch has no file for -- the objects the switch cannot
     * decide about on its own, named so the operator can.
     */
    @GET
    @Path("/branches/_previewSwitch")
    @io.swagger.v3.oas.annotations.Operation(summary = "Preview the effect of switching branch.")
    @MirthOperation(name = "gitSyncPreviewSwitch", display = "Preview git branch switch",
        auditable = false)
    Map<String, Object> previewSwitch(
        @Param("branch")
        @io.swagger.v3.oas.annotations.Parameter(description = "Branch to preview.", required = true)
        @QueryParam("branch") String branch) throws ClientException;

    /**
     * Switches branch and applies the new branch to the engine.
     *
     * <p>Requires {@code confirm=true}: this replaces configuration, so it must not be
     * reachable by accident.
     */
    @POST
    @Path("/branches/_switch")
    @io.swagger.v3.oas.annotations.Operation(summary = "Switch branch and apply it to the engine.")
    @MirthOperation(name = "gitSyncSwitchBranch", display = "Switch git branch",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> switchBranch(
        @Param("branch")
        @io.swagger.v3.oas.annotations.Parameter(description = "Branch to switch to.", required = true)
        @QueryParam("branch") String branch,
        @Param("confirm")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "Must be true. Switching replaces the engine's configuration.", required = true)
        @QueryParam("confirm") boolean confirm,
        @Param("discardLocal")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "If true, discard uncommitted engine changes instead of refusing.")
        @QueryParam("discardLocal") boolean discardLocal,
        @Param("orphanAction")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "What to do with channels, code templates and alerts this engine holds that "
            + "the branch does not describe: keep (default), disable, or delete. "
            + "Deleting a channel destroys its message history.")
        @QueryParam("orphanAction") String orphanAction) throws ClientException;

    /**
     * Discards all local state and resets the engine to {@code origin/<branch>}.
     *
     * <p>Not gated on push permission, unlike commit and push. It writes to the engine and
     * never to the remote, and putting a following instance back in step with its branch is
     * the reason pull-only mode exists -- refusing it there would remove the recovery path
     * from the instance most likely to need it.
     */
    @POST
    @Path("/branches/_forceReset")
    @io.swagger.v3.oas.annotations.Operation(
        summary = "Discard all local state and reset to the branch as it is on the remote.")
    @MirthOperation(name = "gitSyncForceReset", display = "Force reset to git branch",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> forceReset(
        @Param("branch")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "Branch to reset to. Defaults to the configured branch.")
        @QueryParam("branch") String branch,
        @Param("confirm")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "Must be true. Uncommitted changes and unpushed commits are destroyed.",
            required = true)
        @QueryParam("confirm") boolean confirm,
        @Param("orphanAction")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "What to do with objects this engine holds that the branch does not describe: "
            + "keep (default), disable, or delete.")
        @QueryParam("orphanAction") String orphanAction) throws ClientException;

    /**
     * Creates a branch from the current head and checks it out.
     *
     * <p>Non-destructive: the engine's configuration is unchanged, because the new branch
     * starts as whatever is already checked out.
     */
    @POST
    @Path("/branches/_create")
    @io.swagger.v3.oas.annotations.Operation(summary = "Create a branch from the current head.")
    @MirthOperation(name = "gitSyncCreateBranch", display = "Create git branch",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> createBranch(
        @Param("branch")
        @io.swagger.v3.oas.annotations.Parameter(description = "New branch name.", required = true)
        @QueryParam("branch") String branch,
        @Param("push")
        @io.swagger.v3.oas.annotations.Parameter(description = "Push the new branch to the remote.")
        @QueryParam("push") boolean push) throws ClientException;

    /**
     * Lists the branches on a remote using settings that have not been saved yet.
     *
     * <p>Exists so the settings form can offer a branch dropdown before any valid
     * configuration is stored. Uses ls-remote, so no clone and no working tree.
     */
    @POST
    @Path("/branches/_remote")
    @io.swagger.v3.oas.annotations.Operation(
        summary = "List a remote's branches using supplied, unsaved settings.")
    @MirthOperation(name = "gitSyncRemoteBranches", display = "List remote git branches",
        auditable = false)
    Map<String, Object> remoteBranches(
        @Param(value = "settings", excludeFromAudit = true)
        @io.swagger.v3.oas.annotations.Parameter(description =
            "Partial settings to probe with; a blank secret falls back to the stored one.")
        Map<String, String> settings) throws ClientException;

    /**
     * Checks whether the saved settings work: remote reachable, credential accepted, and
     * the configured branch present.
     *
     * <p>Separate from saving on purpose. Settings are persisted whether or not they are
     * valid, so a half-finished configuration is not lost; this is what the UI calls
     * afterwards to decide whether it can leave the settings form.
     */
    @POST
    @Path("/_validate")
    @io.swagger.v3.oas.annotations.Operation(summary = "Check whether the saved settings work.")
    @MirthOperation(name = "gitSyncValidate", display = "Validate git sync settings",
        auditable = false)
    Map<String, Object> validateSettings() throws ClientException;

    /** Verifies the remote is reachable with the stored credential. */
    @POST
    @Path("/_test")
    @io.swagger.v3.oas.annotations.Operation(summary = "Test connectivity to the remote.")
    @MirthOperation(name = "gitSyncTest", display = "Test git connection",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> testConnection() throws ClientException;
}
