/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.gitsync;

import java.util.Locale;

/**
 * Turns git and JGit failures into something an operator can act on.
 *
 * <p>Without this the UI shows a serialised Java exception -- class name, detail message
 * and a forty-frame stack trace -- which tells the person reading it nothing about what to
 * do next. Worse, it leaks internal class and method names into a web page.
 *
 * <p>The technical text is not thrown away: it is returned separately so the UI can keep it
 * behind a disclosure for when the friendly message is not enough. The aim is a useful first
 * sentence, not a hidden one.
 */
final class GitErrors {

    private GitErrors() {
    }

    /**
     * A short, plain-language explanation with a suggested next step.
     *
     * <p>Matching is on message text because JGit reports most transport and push failures
     * as a generic {@code TransportException} or {@code IOException} -- the exception type
     * rarely distinguishes "wrong password" from "no such host".
     */
    static String friendly(Throwable t) {
        String raw = deepestMessage(t);
        if (raw == null || raw.isBlank()) {
            return "The git operation failed, and no reason was reported. "
                + "The engine log may have more.";
        }
        String m = raw.toLowerCase(Locale.ROOT);

        // ---- push rejections -------------------------------------------------
        if (m.contains("pre-receive hook declined")) {
            return "The remote refused the push: a pre-receive hook declined it. "
                + "That is usually branch protection -- the branch may only accept changes "
                + "through a merge request, or your token may not be allowed to push to it.";
        }
        if (m.contains("rejected_nonfastforward") || m.contains("non-fast-forward")) {
            return "The remote has commits this engine does not. Pull first, then push "
                + "again. Nothing was sent.";
        }
        if (m.contains("rejected_remote_changed")) {
            return "The branch moved on the remote while pushing. Pull and try again.";
        }
        if (m.contains("rejected_nodelete")) {
            return "The remote does not allow deleting that branch.";
        }
        if (m.contains("hook declined") || m.contains("rejected_other_reason")) {
            return "The remote refused the push. This is a server-side rule rather than a "
                + "problem with the engine -- the detail below is what the remote said.";
        }

        // ---- authentication and authorisation --------------------------------
        if (m.contains("not authorized") || m.contains("authentication is required")
            || m.contains("401")) {
            return "The remote rejected the credential. Check the token has not expired "
                + "and that the username matches what the host expects.";
        }
        if (m.contains("403") || m.contains("forbidden")) {
            return "The credential was accepted but is not allowed to do this. For a push, "
                + "the token usually needs write access to the repository.";
        }
        if (m.contains("auth fail") || m.contains("invalid privatekey")
            || m.contains("userauth_pubkey")) {
            return "SSH authentication failed. Check the private key is the whole file "
                + "including its header line, and that the matching public key is "
                + "registered on the remote.";
        }
        if (m.contains("unknownhostkey") || m.contains("host key")) {
            return "The server's host key was not accepted. Add its known_hosts entry in "
                + "the settings, or clear the entry if the host key has legitimately "
                + "changed.";
        }

        // ---- reachability ----------------------------------------------------
        if (m.contains("cannot open git-upload-pack")
            || m.contains("cannot open git-receive-pack")) {
            return "Could not reach the repository. Check the remote URL, and that the "
                + "engine's container can reach that host.";
        }
        if (m.contains("unknownhostexception") || m.contains("nodename nor servname")
            || m.contains("name or service not known") || m.contains("could not resolve")) {
            return "The remote host name could not be resolved from inside the engine's "
                + "container. A host that resolves on your machine will not necessarily "
                + "resolve in Docker.";
        }
        if (m.contains("connection refused") || m.contains("connect timed out")
            || m.contains("connection timed out")) {
            return "The remote host did not accept the connection. Check the port and any "
                + "firewall between the engine and the host.";
        }
        if (m.contains("certificate") || m.contains("sslhandshake")
            || m.contains("pkix path")) {
            return "The remote's TLS certificate was not trusted. If it is signed by an "
                + "internal CA, that CA has to be in the engine's truststore.";
        }

        // ---- repository and ref problems -------------------------------------
        if (m.contains("not found in upstream origin") || m.contains("remote branch")) {
            return "That branch does not exist on the remote. Pick one from the branch "
                + "list, or create it.";
        }
        if (m.contains("repository not found") || m.contains("404")) {
            return "The repository was not found at that URL. A private repository also "
                + "reports this when the credential cannot see it.";
        }
        if (m.contains("not a git repository")) {
            return "That path is not a git repository.";
        }
        if (m.contains("checkout conflict") || m.contains("would be overwritten")) {
            return "Local files would be overwritten. Commit or discard the pending "
                + "changes first.";
        }
        if (m.contains("nothing to push") || m.contains("up_to_date")) {
            return "Nothing to push -- the remote already has these commits.";
        }
        if (m.contains("empty") && m.contains("repository")) {
            return "The remote repository is empty. Commit and push once to seed it.";
        }

        // ---- local filesystem ------------------------------------------------
        if (m.contains("permission denied") || m.contains("read-only file system")) {
            return "The engine could not write to its working tree. Check the appdata "
                + "volume is writable.";
        }
        if (m.contains("no space left")) {
            return "The engine has run out of disk space for its working tree.";
        }

        // Nothing matched: return the reported message on its own. Still better than a
        // stack trace, and it keeps genuinely novel failures readable.
        return raw;
    }

    /**
     * The most specific message available.
     *
     * <p>JGit wraps liberally, and the useful text is almost always in the innermost cause
     * -- the outer layers say things like "Transport error".
     */
    static String deepestMessage(Throwable t) {
        String best = null;
        Throwable current = t;
        int guard = 0;
        while (current != null && guard++ < 12) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                best = message;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return best == null ? (t == null ? null : t.toString()) : best;
    }

    /** Short technical detail for a disclosure: type and message, never a stack trace. */
    static String technical(Throwable t) {
        if (t == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        Throwable current = t;
        int guard = 0;
        while (current != null && guard++ < 6) {
            if (out.length() > 0) {
                out.append("\ncaused by: ");
            }
            out.append(current.getClass().getSimpleName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                out.append(": ").append(current.getMessage());
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return out.toString();
    }
}
