# Git Sync — OIE engine extension

Synchronises engine configuration with a git repository and branch, in the shape
Databricks Repos has: pull a branch into the engine, and commit/push engine
changes back where that is allowed.

**Status: builds, installs, loads and renders.** All seven Java sources compile
against the engine's own jars, the extension packages to a zip, the engine loads
it (`oie-check-extensions.sh "Git Sync"` passes), the REST API answers at
`/api/gitsync`, and both console surfaces render. **Not yet exercised against a
real git remote** — see [Remaining](#remaining).

## Design

| Decision | Choice | Why |
| --- | --- | --- |
| Where it runs | Java engine extension | The console's `server.js` backend only exists when the web administrator runs under Node; here it is a WAR inside the engine's Jetty. Its `/plugin-api` routes are also unauthenticated and reachable before login, so credentials there would need hand-rolled auth. The web-admin docs recommend an engine servlet for exactly this. |
| Git implementation | JGit 6.10.1, bundled | Pure Java, no `git` binary in the image. Core JGit has **no** SSH transport, so `org.eclipse.jgit.ssh.jsch` is bundled too — chosen over `ssh.apache` because the engine already ships `jsch-2.27.7` (mwiede fork, `com.jcraft.jsch` package), making it one extra jar rather than MINA's five. |
| Sync directions | Pull everywhere; commit/push gated by `Mode` | A production engine should follow a branch and never author to it, so a mistake in production cannot become a commit the next environment inherits. |
| Auth | HTTPS token **and** SSH key | Credential encrypted with the engine's own `Encryptor` before it reaches the `configuration` table, never returned by the API, never written into the working tree. |
| Scope | 11 object types, per-instance selectable | Channels, code templates, channel groups and the configuration map are on by default. Alerts, global scripts and the five Settings pages are **not**: they hold this instance's own identity (server name, SMTP host, ports) or environment-specific behaviour, so copying them between environments by default would be wrong. |
| Applying changes | Engine controllers, not the REST API | The controllers reject what they cannot deserialise. A channel pushed as XML that XStream cannot read is stored as an `InvalidChannel` stub and reported as a *successful* save. |
| Deletions on pull | Never applied from git | Removing a channel destroys its message history. On a routine pull that stays a deliberate act, the same choice the CI pipeline makes by not pruning production. |
| Deletions on branch change | **The operator's choice, per switch** | Adopting a branch is adopting a desired state, so an object that existed only on the branch being left is no longer wanted — but "no longer wanted" and "safe to destroy" are not the same sentence. The preview names every stranded object, and the switch offers three answers: keep them, undeploy and disable them, or delete them. Nothing is inferred and nothing carries over from the last switch; the default is always the one that destroys nothing. |
| Pull with local changes | **Refused** | Pull is a hard reset to `origin/<branch>`, so it would discard configuration the engine holds and git does not. The caller commits or explicitly discards first. |
| Switching branch | Allowed, but **destructive and gated** | A different branch is a different desired state, so adopting it replaces channels, code templates and the rest. Requires an explicit confirmation flag, and `previewSwitch()` reports exactly what would change — both the files that differ and the objects the branch has no file for — so the warning can be specific rather than generic. |
| Settings branch ≠ checked-out branch | Reported, and pull is **refused** | Saving a branch in the settings form records an intent; it must not move the checkout, because adopting a branch replaces this engine's configuration and a settings save is not where that decision belongs. While the two disagree, `status` says so, the page says so, and `pull()` refuses — a pull here would hard-reset the checked-out branch onto another branch's commit, which is a branch switch with none of a switch's confirmation, preview or say over what it strands. |
| Creating a branch | Allowed, non-destructive | Branches from the current head, so nothing changes in the engine. Pushing it is optional. |
| Force reset | Allowed, gated on typing the branch name | The recovery path when the engine has drifted too far to reconcile: discard the working tree, discard unpushed commits, take `origin/<branch>` exactly. It is the one operation here that destroys work irrecoverably, so the confirmation is the branch name typed out rather than a second click, which is only a slower way to click. Available on a pull-only instance too — it writes to the engine, never to the remote, and a following instance is the one most likely to need it. Logged at WARN. |
| Change list | Per-file `+added −removed`, click for the diff | A list of filenames cannot distinguish a reworded description from a rebuilt channel, which is the difference between committing blind and reading first. Diffs are HEAD against the working tree, fetched per file and cached by path. |

The file layout is **identical** to what `scripts/oie-config-pull.sh` produces,
so one repository serves both this plugin and the GitLab pipeline and neither
needs to know the other exists:

```
channels/<name>.xml                  Channels
code-templates/libraries.xml         Code template libraries + membership
code-templates/templates/<name>.xml  Code templates
channel-groups.xml                   Channel groups
configuration-map.properties         Settings > Configuration Map
alerts/<name>.xml                    Alerts
global-scripts.xml                   Global scripts
server-settings.xml                  Settings > Server
administrator-settings.xml           Settings > Administrator
channel-tags.xml                     Settings > Tags
resources.xml                        Settings > Resources
data-pruner.properties               Settings > Data Pruner
volume-monitor.properties            Volume Monitor rules and settings
```

The first seven are shared with `scripts/oie-config-pull.sh`. The six settings
files are plugin-only for now — the shell scripts do not write them, so a
repository can carry them without the CI pipeline caring.

Two notes on the settings files. `channel-tags.xml` is exported as a sorted
*list* rather than the engine's `Set`, because a `HashSet` iterates in an
unstable order and an unstable order turns every export into a diff.
`resources.xml` is skipped on apply when the file is empty: an empty file means
"not managed here", not "delete every resource", which would break every channel
that references one.

Files are named from the object's name with the id kept **inside** the file, so
renaming an object renames its file without changing its identity. Ids are what
make the apply step an upsert rather than a duplicate.

Three of those types can be left stranded by a branch change and need the
question asked: **channels, code templates and alerts**, each an upsert keyed on
id with no notion of "and delete the rest". Channel groups are deliberately *not*
among them — `updateChannelGroups` already treats the set it is handed as the
complete list and deletes anything else, so `apply()` reconciles them in full,
and running them through the orphan path as well would delete every group on the
engine. The remaining types are whole-file replacements already.

One rule makes the check safe to act on: **an absent directory means "this branch
does not describe that type", never "delete everything of that type"**. Git does
not track empty directories, so a branch whose channels were all deleted and a
branch that never had a `channels/` folder look identical once checked out, and
reading the second as the first would offer to delete every channel on the
engine. Ids are read out of the XML with a regex rather than by deserialising,
for the same reason in the other direction: a file this engine cannot parse still
counts as present, because the alternative is a parse failure quietly becoming a
deletion.

## Building

```
./plugins/oie-git-sync/build.sh
```

No local JDK or Maven. It compiles in a container and takes the engine jars
straight out of `oie/engine:4.6.0`, so the extension is always compiled against
exactly the engine it will run on — those jars are not on Maven Central, and the
usual alternative is a third-party mirror pinned to some other version.

JGit and its two dependencies come from Maven Central and are verified against
the SHA-1 each artifact publishes beside it. They are loaded into the engine's
JVM, so an artifact changing under us would be arbitrary code execution inside
the engine.

`plugin.xml` is generated from `plugin.xml.in` with the version stamped in.
`mirthVersion` must match the engine exactly — compatibility is an exact string
match and a mismatch means the extension is silently not loaded.

## Verified

- Extension loads: `ok       Git Sync`
- REST API live: `GET /api/gitsync/status` → `{"ok":true,"configured":false}` on
  an unconfigured instance
- Read-only mode enforced server-side: `POST /api/gitsync/_push` → **403**
- Console plugin discovered: `webplugins` → `["gitsync","sentinel","tls-manager","thread-viewer"]`
- Nav registers both items, branch indicator last:
  `Plugins/80:Source Control`, `Plugins/9999:Git: not configured`
- Source Control page renders, including the unconfigured state and settings form
- XStream-JSON decoder: 7/7 unit tests pass against captured real payloads
  (`test/decode.test.mjs`)
- XStream map encoder: 5/5 unit tests (`test/encode.test.mjs`)
- Change-row parser: 6/6 unit tests (`test/changes.test.mjs`), including paths
  containing spaces and truncated rows
- Line counts and diffs against the live engine: `/changes?refresh=true` returns
  `channels/new-channel.xml\tadd\t321\t0\ttext` and six more, and
  `/diff?path=channel-tags.xml` returns a real unified patch. In the browser
  each row renders as `add  channels/new-channel.xml  +321 −0` and expands to
  the 328-line diff on click
- Force reset refuses without acknowledgement: `POST /branches/_forceReset?confirm=false`
  → **400** with the reason. In the browser the button arms a confirmation that
  names the branch, the 7 changes that would be lost and all 12 scopes, and
  stays disabled until the branch name is typed exactly (`mast` → disabled,
  `master` → enabled)
- All 12 scopes export against a real repository: `server-settings.xml`
  (`environmentName`, `serverName`, metadata columns),
  `administrator-settings.xml`, `channel-tags.xml`, `resources.xml` and
  `data-pruner.properties` and `volume-monitor.properties` all written and
  reported as pending changes
- Tolerant save: posting `authType=NONSENSE`, `subdirectory=../escape` returns
  `200` with `saved:true, valid:false` and both problems named, while
  `remoteUrl` and `branch` from the same request are genuinely persisted
- Validation gates the view: with an unreachable remote, `/_validate` returns
  `valid:false` with `cannot open git-upload-pack`, `/status` returns
  `ok:false`, and the page shows the error plus the settings form rather than
  the repository view

## Written and compiling

- **`build.sh`** — jar extraction from the image, checksum-verified dependency
  download, containerised compile, zip packaging.
- **`plugin.xml.in`** — service plugin, servlet interface + server class,
  library declarations.
- **`GitSyncServicePlugin`** — lifecycle and the optional scheduled pull.
  Deliberately does **not** pull on startup: that would mean a container restart
  silently rewrites configuration. Scheduled pulls are opt-in via
  `pullIntervalSeconds`, use fixed *delay* so a slow pull cannot queue another
  behind it, and never let an exception escape the task (which would cancel all
  future runs and turn one network blip into a permanently stopped sync).
- **`GitSyncService`** — orders the two sides and serialises every operation.
  Pull is export → dirty-check → reset → apply → re-export; the final export
  matters because the engine normalises XML on save, and without it the next
  status would report the engine's own normalisation as a local change. A branch
  switch inserts one more step: export → checkout → *find stranded objects* →
  apply → act on them → re-export. The stranded objects are found before `apply`
  so the list matches what the preview showed, and the export runs last so the
  deletions reach the tree too — otherwise the next status would offer to commit
  the objects the switch had just removed.
- **`GitSyncServlet`** / **`GitSyncServletInterface`** — the REST surface.
  Read-only mode returns 403, a dirty-tree refusal returns **409 with the
  blocking file list**, and an unconfigured or broken repository returns
  `ok:false` with a message rather than a stack trace.
- **`webadmin/`** — console UI: branch indicator in the nav, Source Control page
  with pending changes, commit / commit-and-push / discard, pull / push / test,
  branch review-then-switch, create branch, and settings. Buildless plain ES
  module. Includes a decoder for XStream's JSON (`{"linked-hash-map":{"entry":[…]}}`)
  and a matching encoder, because a `Map` parameter must arrive XStream-shaped --
  a plain JSON body is rejected with a 500, so requests go as
  `<map><entry>…</entry></map>` the way this repo's shell scripts do.
- **`GitSyncSettings`** — remote, branch, auth type, credential, mode,
  subdirectory, commit identity, pull interval, scope. Persists to the
  `configuration` table (survives this image's per-boot `conf/` reset) with the
  credential encrypted via the engine's `Encryptor`.
- **`GitRepo`** — JGit: clone/open, fetch, hard-reset pull, status with pending
  changes, branch list, switch, create, stage+commit, push. HTTPS token and SSH
  key credentials. Enforces the rules above in the class itself, not the UI:
  `pull()` throws `DirtyTreeException` carrying the file list, refuses outright
  when the checked-out branch is not the configured one, and `switchBranch()`
  throws unless `confirmed` is passed. `filesAt()` reads a branch's tree straight
  out of the object database, so the preview can say what the branch does not
  contain *before* anything is checked out — asking afterwards would be asking
  after the answer stopped mattering.
- **`EngineConfigStore`** — engine ⇄ files for all six object types, using the
  exact controller APIs (`ChannelController.updateChannel(Channel,
  ServerEventContext, boolean, Calendar)`, `CodeTemplateController.updateLibraries`,
  `ConfigurationController.setConfigurationProperties`,
  `ScriptController.setGlobalScripts`, `AlertController.updateAlert`). Includes
  `findInvalidChannels()`, which reads channels back after applying because
  `updateChannel` can report success while storing an unusable stub. Also
  `findOrphans()` / `applyOrphanAction()`: the objects a branch does not describe,
  and the three things that can be done with them — kept, undeployed and disabled
  (`EngineController.undeployChannels` then `ChannelController.setChannelEnabled`,
  `AlertController.disableAlert`), or removed (`EngineController.removeChannels`,
  which stops, undeploys and deletes in one blocking call, plus
  `removeCodeTemplate` and `removeAlert`). Deletions are counted from what
  actually went rather than what was asked for, and logged at WARN.

Two details worth knowing in review: exports are sorted by name then id so an
unchanged engine produces an empty diff, and the configuration map is rendered
by hand rather than with `Properties.store` because that writes a timestamp
comment which would make every export a spurious diff.

## Operating notes

**Updating the web UI needs a hard reload.** The console imports a plugin's
`web/plugin.js` with no cache-buster, so a browser that has already loaded the
old module keeps using it — a normal navigation is not enough. After installing
a new build, hard-reload the console (Ctrl+Shift+R). This bit during
development: a fixed file was live on the server while the page kept running the
previous copy.

**Errors are mapped to plain language.** `GitErrors` turns the common failures
into a sentence and a next step — a pre-receive hook declining a push, a
non-fast-forward, an expired token, an unresolvable host inside the container,
an untrusted internal CA. The original text is kept and shown behind a
"Technical detail" disclosure rather than discarded. Nothing returns a
serialised Java exception to the page any more: an operation that fails answers
`200` with `ok:false`, because a push the remote refused is the answer, not a
server fault.

**Registration failures are isolated.** Each `platform.register*` call goes
through a `safely(label, fn)` wrapper. Without it one throwing registration
abandoned the rest of `register()`, which is how a broken status helper silently
took the branch indicator out of the navigation menu while the Source Control
item still worked — a confusing symptom for the actual cause.

**Styling comes from the shell.** The view renders with `.panel`,
`.panel-header`, `.panel-body`, `.field`, `.hint`, `.btn`, `.btn-primary`,
`.btn-danger`, `.tag`, `.kv`, `.mono` and `.form-grid`, so it inherits the app's
spacing, borders and both themes. Inline style is used only where those classes
do not reach, and then in terms of the app's CSS variables (`var(--err)`,
`var(--line)`) rather than fixed colours.

One layout trap worth recording: `.view` is a flex column with
`overflow-y: hidden`, so child sections flex-shrink to fit the viewport instead
of overflowing — `scrollHeight` ends up equal to `clientHeight` and the page
cannot scroll at all. The view root therefore sets `display: block` and
`overflow-y: auto` inline, overriding the class.

## Remaining

1. **End-to-end against a real remote.** Everything above is verified except the
   part that needs a git server: pull-and-apply, commit, push, branch switch —
   including disabling or deleting the objects a branch does not describe — and
   force reset. Clone, fetch and the change/diff endpoints do run against the
   real remote. The destructive operations compile and their refusals are
   verified, but no round trip has actually completed one. This is the main
   outstanding risk, and it is deliberate: each of them would write to a real
   GitLab project or replace a real engine's configuration.
2. **A round-trip test** — export, apply to an empty engine, re-export, assert
   the trees match. Cheap to write and it would catch serialiser drift.
3. **Branch indicator staleness.** The nav registry is read once when the shell
   mounts: registering an item later does not appear, and mutating a registered
   item's label does not re-render (both verified against a running console). So
   the indicator shows the branch as of page load, and the Source Control page
   tells you to reload after switching. A live indicator would need a nav-footer
   or status-bar extension point, which the console does not currently have —
   worth raising upstream.
4. **Conflict reporting.** A push rejected because someone else pushed first is
   surfaced as an error message. It could offer to fetch and show the divergence.
