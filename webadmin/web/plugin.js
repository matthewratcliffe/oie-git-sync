/*
 * Git Sync console UI.
 *
 * Two surfaces:
 *   - a branch indicator at the bottom of the nav menu, and
 *   - a "Source Control" page: pending changes, commit, push, pull, branch
 *     switching and creation, plus settings.
 *
 * Plain ES module JavaScript: no JSX, no imports, no build step. The console
 * dynamically import()s this file and passes its own `platform` into
 * register(), which is how the bundled plugins receive it. Importing
 * @oie/web-shell here would depend on the page's import map and risks a second
 * framework instance registering into a dead registry, which fails silently.
 */

const API = '/api/gitsync';

/*
 * The engine serialises a Map through XStream, so "JSON" arrives shaped like
 * {"linked-hash-map":{"entry":[{"string":"ok","boolean":true}, ...]}} rather
 * than a plain object. Same reason the shell scripts in this repo parse XML.
 *
 * Entry shapes seen in practice:
 *   {"string":"ok","boolean":true}          key and value of different types
 *   {"string":["remoteUrl",null]}           both strings, so XStream collapses them
 *   {"string":"pullIntervalSeconds","int":0}
 */
function decode(node) {
    if (node === null || typeof node !== 'object') return node;
    if (Array.isArray(node)) return node.map(decode);

    const keys = Object.keys(node);

    // A map: {"linked-hash-map": {...}} / {"map": {...}}
    const mapKey = keys.find(k => k === 'map' || k.endsWith('-map'));
    if (mapKey && keys.length === 1) {
        const inner = node[mapKey];
        if (inner === null) return {};
        let entries = inner.entry;
        if (entries == null) return {};
        if (!Array.isArray(entries)) entries = [entries];
        const out = {};
        for (const entry of entries) {
            const [k, v] = decodeEntry(entry);
            out[k] = v;
        }
        return out;
    }

    /*
     * A collection: {"list": {"string": [...]}} / {"list": null}.
     *
     * The name test is deliberately loose. An immutable list (List.of(), .toList())
     * serialises as java.util.ImmutableCollections$ListN wrapping a CollSer blob,
     * whose elements cannot be recovered at all -- the server side must never send
     * one. Recognising the shape anyway means the view gets an empty array instead
     * of an object, so a stray one degrades into "nothing to show" rather than
     * "(x || []).join is not a function".
     */
    const listKey = keys.find(k => k === 'list' || k === 'set'
        || /(^|[.$_-])(list|set|collection)/i.test(k));
    if (listKey && keys.length === 1) {
        const inner = node[listKey];
        if (inner === null) return [];
        // The element type is the single property name, whatever it is.
        // Skip XStream's serialization attributes when looking for the element type.
        const elemKey = Object.keys(inner).find(k => !k.startsWith('@'));
        if (elemKey === undefined) return [];
        const items = inner[elemKey];
        // An unrecoverable payload (CollSer) yields an object, not an array of
        // elements. Return [] rather than smuggling it through as a fake list.
        if (items !== null && typeof items === 'object' && !Array.isArray(items)) {
            return [];
        }
        return (Array.isArray(items) ? items : [items]).map(decode);
    }

    // A boxed scalar: {"boolean":true}, {"int":0}, {"string":"x"}. XStream names the
    // element after the Java type, so a single property with a scalar type name is a
    // value, not an object.
    const SCALARS = new Set(['string', 'boolean', 'int', 'long', 'double', 'float',
        'short', 'byte', 'char', 'big-decimal', 'big-int']);
    if (keys.length === 1 && SCALARS.has(keys[0])) {
        const v = node[keys[0]];
        return Array.isArray(v) ? v.map(decode) : decode(v);
    }

    // A plain object (or an already-decoded one): recurse into its values.
    const out = {};
    for (const k of keys) out[k] = decode(node[k]);
    return out;
}

function decodeEntry(entry) {
    if (entry === null || typeof entry !== 'object') return [String(entry), null];
    const props = Object.keys(entry);

    // Collapsed form: one property whose value is [key, value].
    if (props.length === 1 && Array.isArray(entry[props[0]])) {
        const arr = entry[props[0]];
        return [String(arr[0]), arr.length > 1 ? decode(arr[1]) : null];
    }
    // Distinct forms: first property is the key, second is the value.
    //
    // The value's *property name* carries its type ("list", "linked-hash-map"),
    // so it has to be handed to decode() still wrapped -- passing the inner
    // object alone loses the information that it is a collection or a map.
    const keyVal = entry[props[0]];
    const key = Array.isArray(keyVal) ? String(keyVal[0]) : String(keyVal);
    if (props.length < 2) return [key, null];
    const valueName = props[1];
    return [key, decode({ [valueName]: entry[valueName] })];
}

/*
 * The mirror of decode(): a Map parameter has to arrive XStream-shaped, so a plain
 * JSON object body is rejected outright (verified: plain JSON gives a 500, the XML
 * map gives a 200). The engine's own scripts in this repo talk XML to the API for
 * the same reason, so this does too rather than guessing at XStream's JSON dialect.
 */
function encodeMap(obj) {
    const esc = v => String(v)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
    const entries = Object.keys(obj)
        // An undefined value means "not supplied", which is different from empty: the
        // server leaves a field alone when the key is absent.
        .filter(k => obj[k] !== undefined && obj[k] !== null)
        .map(k => `<entry><string>${esc(k)}</string><string>${esc(obj[k])}</string></entry>`)
        .join('');
    return `<map>${entries}</map>`;
}

// 24x24 stroke-only glyphs.
const ICON_BRANCH = 'M6 3v12M6 15a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM18 3a3 3 0 1 0 0 6 3 3 0 0 0 0-6z'
    + ' M18 9c0 4-4 4-6 6';
const ICON_SOURCE = 'M4 6h16M4 12h10M4 18h7M17 15l3 3-3 3';

/*
 * Nav items are read once when the shell mounts: registering one later does not
 * appear, and mutating a registered item's label does not re-render (both
 * verified against a running console). So the branch has to be known *before*
 * register() runs, which is what this top-level await is for -- the console
 * awaits the module's import, so the fetch completes first.
 *
 * Kept on a short timeout, and never allowed to reject: this delays login for
 * every user, and a plugin that cannot load because git is unreachable would be
 * a bad trade for a label.
 */
async function bootstrapStatus() {
    const controller = new AbortController();
    // 3s, not 1.5s: the first call after a server restart also opens the git
    // repository, and the shorter timeout made the sidebar read "Git:
    // unavailable" on the first page load every time the engine was recreated.
    const timer = setTimeout(() => controller.abort(), 3000);
    try {
        const res = await fetch(`${API}/status?refresh=false`, {
            headers: { Accept: 'application/json', 'X-Requested-With': 'oie-webadmin' },
            credentials: 'same-origin',
            signal: controller.signal,
        });
        // 401 means the console loaded this module on the login page, before a
        // session existed -- not that git is broken. Worth distinguishing,
        // because the engine stalls an unauthenticated API call for seconds and
        // the label would otherwise read as a fault on every post-restart load.
        if (res.status === 401 || res.status === 403) return { unauthenticated: true };
        if (!res.ok) return null;
        return decode(await res.json());
    } catch (err) {
        return null;
    } finally {
        clearTimeout(timer);
    }
}

const BOOT = await bootstrapStatus();

/** The nav label for the branch indicator, from whatever we learned at boot. */
function branchLabel(boot) {
    // Loaded before login, so the branch is simply not known yet. The nav
    // registry is read once when the shell mounts, so this label cannot be
    // filled in later; the next reload inside the session shows the branch.
    if (boot && boot.unauthenticated) return 'Source control';
    if (!boot) return 'Git: unavailable';
    if (boot.configured === false) return 'Git: not configured';
    if (boot.ok === false) return 'Git: error';
    let label = boot.branch || 'detached';
    const bits = [];
    if (boot.dirty) bits.push('*');
    if (boot.ahead) bits.push(`↑${boot.ahead}`);
    if (boot.behind) bits.push(`↓${boot.behind}`);
    if (bits.length) label += ` ${bits.join(' ')}`;
    return label;
}

export function register(platform) {
    const React = platform.React;

    // ------------------------------------------------------------------
    // API helper
    // ------------------------------------------------------------------
    async function call(method, path, body) {
        const headers = {
            Accept: 'application/json',
            'X-Requested-With': 'oie-webadmin',
        };
        if (body !== undefined) {
            headers['Content-Type'] = 'application/xml';
        }
        const res = await fetch(API + path, {
            method,
            headers,
            credentials: 'same-origin',
            body: body === undefined ? undefined : encodeMap(body),
        });

        let payload = null;
        const text = await res.text();
        if (text) {
            try {
                payload = decode(JSON.parse(text));
            } catch (err) {
                payload = { error: text };
            }
        }
        if (!res.ok) {
            const error = new Error(
                (payload && (payload.error || payload.message)) || `HTTP ${res.status}`);
            error.status = res.status;
            error.payload = payload;
            error.detail = payload ? payload.detail : null;
            throw error;
        }
        return payload || {};
    }

    // ------------------------------------------------------------------
    // Presentation helpers
    // ------------------------------------------------------------------
    const h = React.createElement;

    /*
     * The shell's .view class is a flex column with overflow-y: hidden, so child
     * sections flex-shrink to fit the viewport instead of overflowing -- scrollHeight
     * ends up equal to clientHeight and there is nothing to scroll, which bites as soon
     * as the settings form is expanded.
     *
     * .view is our own element here, so an inline display/overflow wins over the class.
     * block rather than flex means the sections keep their natural height and the
     * container scrolls.
     */
    const VIEW_STYLE = {
        display: 'block',
        overflowY: 'auto',
        height: '100%',
        boxSizing: 'border-box',
        padding: '1rem',
    };
    const viewStyle = extra => Object.assign({}, VIEW_STYLE, extra || {});

    /*
     * Everything below renders with the shell's own classes -- .panel,
     * .panel-header, .panel-body, .field, .hint, .btn, .tag, .kv, .mono,
     * .form-grid -- rather than inline styling. They carry the app's spacing,
     * borders, radii and both themes, so this view matches the rest of the
     * console and follows it when the theme changes. Inline style is used only
     * for the handful of things those classes do not cover, and then in terms
     * of the app's CSS variables rather than fixed colours.
     */

    function Panel(title, children, opts) {
        const o = opts || {};
        return h('div', { className: 'panel', style: { marginBottom: '11px' } }, [
            title ? h('div', { key: 'h', className: 'panel-header' }, [
                h('span', { key: 't' }, title),
                o.badge || null,
            ]) : null,
            h('div', { key: 'b', className: 'panel-body' }, children),
        ]);
    }

    function Button(label, onClick, opts) {
        const o = opts || {};
        const classes = ['btn'];
        if (o.primary) classes.push('btn-primary');
        if (o.danger) classes.push('btn-danger');
        return h('button', {
            type: 'button',
            className: classes.join(' '),
            disabled: !!o.disabled,
            title: o.title || '',
            onClick,
            style: { marginRight: '6px' },
        }, label);
    }

    // The shell has no alert component, so this borrows .restart-banner's recipe:
    // a wash of the state colour over the surface, with a matching border.
    function Banner(kind, children) {
        const token = { error: '--err', warn: '--warn', ok: '--ok', info: '--accent' }[kind]
            || '--accent';
        return h('div', {
            className: 'hint',
            style: {
                background: `color-mix(in srgb, var(${token}) 14%, var(--bg1))`,
                border: `1px solid color-mix(in srgb, var(${token}) 55%, transparent)`,
                borderRadius: 'var(--radius)',
                color: 'var(--text)',
                fontSize: '11px',
                padding: '7px 11px',
                marginBottom: '9px',
                whiteSpace: 'pre-wrap',
            },
        }, children);
    }

    /*
     * A failure, friendly message first.
     *
     * The technical detail goes in a <details> rather than being dropped: the
     * plain sentence is what an operator acts on, but when it is not enough the
     * exact text the remote returned has to be reachable without the engine log.
     */
    function ErrorBanner(message, detail) {
        return Banner('error', [
            h('div', { key: 'm', style: { fontWeight: 600 } }, message),
            detail ? h('details', { key: 'd', style: { marginTop: '5px' } }, [
                h('summary', {
                    key: 's',
                    style: { cursor: 'pointer', color: 'var(--text-dim)' },
                }, 'Technical detail'),
                h('pre', {
                    key: 'p',
                    className: 'mono',
                    style: {
                        margin: '5px 0 0',
                        fontSize: '10px',
                        whiteSpace: 'pre-wrap',
                        color: 'var(--text-dim)',
                    },
                }, detail),
            ]) : null,
        ]);
    }

    function FileList(paths, emptyText) {
        if (!paths || paths.length === 0) {
            return h('div', { className: 'hint' }, emptyText);
        }
        return h('ul', {
            className: 'mono',
            style: {
                margin: '3px 0 0',
                paddingLeft: '17px',
                fontSize: '11px',
                maxHeight: '15rem',
                overflowY: 'auto',
            },
        }, paths.map((p, i) => h('li', { key: `${p}-${i}` }, p)));
    }

    function Field(label, control, hint) {
        return h('div', { className: 'field' }, [
            h('label', { key: 'l' }, label),
            control,
            hint ? h('div', { key: 'h', className: 'hint' }, hint) : null,
        ]);
    }

    /*
     * Scope options, matching EngineConfigStore.SyncScope. The engine treats a blank
     * scope as its default set, so the checkboxes are seeded from that rather than
     * from nothing -- otherwise a fresh install would show everything unticked while
     * actually syncing four of them.
     */
    const SCOPE_OPTIONS = [
        { token: 'channels', label: 'Channels', byDefault: true },
        { token: 'code-templates', label: 'Code templates', byDefault: true },
        { token: 'channel-groups', label: 'Channel groups', byDefault: true },
        { token: 'configuration-map', label: 'Configuration map', byDefault: true },
        { token: 'alerts', label: 'Alerts', byDefault: false },
        { token: 'global-scripts', label: 'Global scripts', byDefault: false },
        // Settings pages. Off by default because they hold this instance's own
        // identity -- server name, SMTP host, ports -- which should not be
        // copied from one environment onto another.
        { token: 'server-settings', label: 'Settings: Server', byDefault: false },
        { token: 'administrator-settings', label: 'Settings: Administrator', byDefault: false },
        { token: 'channel-tags', label: 'Settings: Tags', byDefault: false },
        { token: 'resources', label: 'Settings: Resources', byDefault: false },
        { token: 'data-pruner', label: 'Settings: Data Pruner', byDefault: false },
        // Volume Monitor rules name channels by id, which this plugin keeps identical
        // across environments, so they do travel -- but the thresholds are per
        // environment (a dev instance will never see production volumes), so syncing
        // them is opt-in like the other settings pages.
        { token: 'volume-monitor', label: 'Volume Monitor rules', byDefault: false },
    ];

    function parseScope(value) {
        if (value == null || String(value).trim() === '') {
            return SCOPE_OPTIONS.filter(o => o.byDefault).map(o => o.token);
        }
        const wanted = String(value).split(',').map(t => t.trim()).filter(Boolean);
        return SCOPE_OPTIONS.filter(o => wanted.includes(o.token)).map(o => o.token);
    }

    function ScopeCheckboxes(form, set) {
        const selected = parseScope(form.scope);
        const toggle = (token, on) => {
            const next = SCOPE_OPTIONS
                .filter(o => (o.token === token ? on : selected.includes(o.token)))
                .map(o => o.token);
            // Written back in the declared order, not click order, so the stored value
            // is stable and does not churn in the settings audit trail.
            set('scope', next.join(','));
        };

        return h('div', null, [
            h('div', {
                key: 'boxes',
                className: 'form-grid',
                style: { maxWidth: '520px' },
            }, SCOPE_OPTIONS.map(o => h('label', {
                key: o.token,
                style: {
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: '5px',
                    fontSize: '11px',
                },
            }, [
                h('input', {
                    key: 'c',
                    type: 'checkbox',
                    checked: selected.includes(o.token),
                    onChange: e => toggle(o.token, e.target.checked),
                }),
                h('span', { key: 's' }, o.label),
            ]))),
            selected.length === 0 ? h('div', {
                key: 'warn',
                className: 'hint',
                style: { marginTop: '4px' },
            }, 'Nothing selected — the engine falls back to its default set '
                + '(channels, code templates, channel groups, configuration map).') : null,
        ]);
    }

    /**
     * Renders a unified diff.
     *
     * <p>Colours by line prefix rather than parsing hunks: a diff is already a line
     * format, and the three prefixes that matter are the first character. Header lines
     * (diff/index/---/+++) are dimmed rather than dropped, because "the file is new"
     * often lives in them.
     */
    function DiffView(text) {
        if (!text) {
            return h('div', { className: 'hint' },
                'No textual difference — the change may be a mode or a binary file.');
        }
        const lines = text.replace(/\n$/, '').split('\n');
        return h('pre', {
            className: 'mono',
            style: {
                margin: '6px 0 0',
                padding: '8px 10px',
                background: 'var(--bg1)',
                border: '1px solid var(--line)',
                borderRadius: 'var(--radius)',
                fontSize: '11px',
                lineHeight: '1.45',
                maxHeight: '26rem',
                overflow: 'auto',
                whiteSpace: 'pre',
            },
        }, lines.map((line, i) => {
            let color = 'var(--text)';
            let background = 'transparent';
            if (line.startsWith('+++') || line.startsWith('---')
                || line.startsWith('diff ') || line.startsWith('index ')
                || line.startsWith('new file') || line.startsWith('deleted file')
                || line.startsWith('similarity ') || line.startsWith('rename ')) {
                color = 'var(--text-faint)';
            } else if (line.startsWith('@@')) {
                color = 'var(--accent)';
            } else if (line.startsWith('+')) {
                color = 'var(--ok)';
                background = 'color-mix(in srgb, var(--ok) 10%, transparent)';
            } else if (line.startsWith('-')) {
                color = 'var(--err)';
                background = 'color-mix(in srgb, var(--err) 10%, transparent)';
            } else {
                color = 'var(--text-dim)';
            }
            return h('div', {
                key: i,
                style: { color, background, padding: '0 3px' },
            }, line === '' ? ' ' : line);
        }));
    }

    /** "+12 −3", or "binary" where counting lines would be meaningless. */
    function ChangeCounts(c) {
        if (c.binary) {
            return h('span', { className: 'tag', title: 'Binary file' }, 'binary');
        }
        return h('span', { className: 'mono', style: { fontSize: '11px' } }, [
            h('span', { key: 'a', style: { color: 'var(--ok)' } }, `+${c.added}`),
            ' ',
            h('span', { key: 'r', style: { color: 'var(--err)' } }, `\u2212${c.removed}`),
        ]);
    }

    /** One tab-separated row from /changes. See the note on the service method. */
    function parseChange(row) {
        const [path, change, added, removed, kind] = String(row).split('\t');
        return {
            path: path || '',
            change: change || 'modify',
            added: Number(added) || 0,
            removed: Number(removed) || 0,
            binary: kind === 'binary',
        };
    }

    /*
     * One tab-separated row from the orphan list: an object this engine holds that
     * the branch being adopted has no file for. Same format, and the same reason for
     * it, as parseChange above.
     */
    function parseOrphan(row) {
        const [scope, id, name, state] = String(row).split('\t');
        return {
            scope: scope || '',
            id: id || '',
            name: name || '(unnamed)',
            deployed: state === 'deployed',
        };
    }

    const ORPHAN_LABELS = {
        channels: 'Channel',
        'code-templates': 'Code template',
        alerts: 'Alert',
    };

    /*
     * The stranded objects by name, never only by count.
     *
     * "3 channels will be deleted" is not a sentence anyone can check. "Legacy ADT
     * Feed, currently deployed" is, and the difference is whether the operator can
     * notice that one of the three is the channel production actually runs on.
     */
    function OrphanList(orphans) {
        if (!orphans || orphans.length === 0) {
            return h('div', { className: 'hint' },
                'This branch describes everything the engine currently holds.');
        }
        return h('ul', {
            style: {
                margin: '3px 0 0',
                paddingLeft: '17px',
                fontSize: '11px',
                maxHeight: '15rem',
                overflowY: 'auto',
            },
        }, orphans.map((o, i) => h('li', { key: `${o.id}-${i}` }, [
            h('span', { key: 't', className: 'tag', style: { marginRight: '6px' } },
                ORPHAN_LABELS[o.scope] || o.scope),
            h('span', { key: 'n' }, o.name),
            o.deployed
                ? h('span', {
                    key: 'd',
                    style: { color: 'var(--warn)', marginLeft: '6px' },
                }, 'deployed')
                : null,
        ])));
    }

    /*
     * What to do with the objects the incoming branch does not describe.
     *
     * Asked rather than assumed. Keeping them leaves the engine running configuration
     * the branch never mentions; deleting them takes each channel's message history
     * with it and cannot be undone. Neither is safe enough to be the silent default,
     * so the switch puts the question in front of whoever is making it, with the
     * objects listed above and no option preselected beyond the harmless one.
     */
    function OrphanChoice(orphans, value, onChange, disabled) {
        const n = orphans.length;
        const deployed = orphans.filter(o => o.deployed).length;
        const option = (token, label, hint) => h('label', {
            key: token,
            style: {
                display: 'flex',
                alignItems: 'flex-start',
                gap: '6px',
                padding: '3px 0',
                cursor: disabled ? 'default' : 'pointer',
            },
        }, [
            h('input', {
                key: 'i',
                type: 'radio',
                name: 'orphan-action',
                value: token,
                checked: value === token,
                disabled: !!disabled,
                onChange: () => onChange(token),
                style: { marginTop: '2px' },
            }),
            h('span', { key: 's' }, [
                h('span', { key: 'l', style: { fontWeight: 600 } }, label),
                h('span', { key: 'h', className: 'hint', style: { display: 'block' } }, hint),
            ]),
        ]);

        return h('div', { style: { marginTop: '0.5rem' } }, [
            h('div', { key: 'q', style: { fontWeight: 600, marginBottom: '0.2rem' } },
                `What should happen to the ${n} object(s) above?`),
            option('keep', 'Keep them',
                'They stay exactly as they are. Channels that are deployed keep running, '
                + 'so the engine ends up holding both branches at once.'
                + (deployed ? ` ${deployed} of them ${deployed === 1 ? 'is' : 'are'} `
                    + 'deployed right now.' : '')),
            option('disable', 'Undeploy and disable them',
                'Channels are undeployed and disabled, alerts are switched off. Nothing '
                + 'is deleted, so this is reversible — but the engine stops behaving like '
                + 'the branch it is leaving.'),
            option('delete', 'Delete them',
                'The engine ends up holding exactly what this branch describes. Deleting '
                + 'a channel destroys its message history and cannot be undone.'),
        ]);
    }

    // ------------------------------------------------------------------
    // The Source Control page
    // ------------------------------------------------------------------
    function SourceControlView() {
        const [status, setStatus] = React.useState(null);
        const [branches, setBranches] = React.useState([]);
        const [settings, setSettings] = React.useState(null);
        const [busy, setBusy] = React.useState('');
        const [error, setError] = React.useState(null);
        const [errorDetail, setErrorDetail] = React.useState(null);
        const [notice, setNotice] = React.useState(null);
        const [blocked, setBlocked] = React.useState(null);
        const [message, setMessage] = React.useState('');
        const [target, setTarget] = React.useState('');
        const [newBranch, setNewBranch] = React.useState('');
        const [pushNew, setPushNew] = React.useState(true);
        const [preview, setPreview] = React.useState(null);
        // Defaults to the only answer that destroys nothing. A destructive choice has to
        // be made, not inherited from the last switch.
        const [orphanAction, setOrphanAction] = React.useState('keep');
        // The force reset's own preview, fetched when its confirmation is armed: it can
        // change branch too, so it strands the same objects and owes the same question.
        const [resetPreview, setResetPreview] = React.useState(null);
        const [showSettings, setShowSettings] = React.useState(false);
        // Two pieces of state rather than one: armed opens the confirmation, and the typed
        // text is what unlocks it. A single flag would make the button a plain two-click
        // action, which is not much of a gate on something this destructive.
        const [resetArmed, setResetArmed] = React.useState(false);
        const [resetText, setResetText] = React.useState('');
        const [changes, setChanges] = React.useState(null);
        // Diffs are fetched per file and cached by path, so reopening one costs nothing
        // and the cache is thrown away whenever the change list is refetched.
        const [openPath, setOpenPath] = React.useState(null);
        const [diffs, setDiffs] = React.useState({});
        const [diffBusy, setDiffBusy] = React.useState(null);

        const refresh = React.useCallback(async (opts) => {
            const o = opts || {};
            setBusy(o.label || 'Refreshing');
            setError(null);
            try {
                // refresh=true re-exports engine state, so "pending changes" means what
                // the engine holds right now rather than at last check.
                const s = await call('GET', '/status?refresh=true');
                setStatus(s);
                // When the settings name a branch the tree is not on, that branch is
                // what the operator most likely wants to switch to -- they already asked
                // for it once, in the settings form.
                setTarget(t => t
                    || (s.branchMismatch ? s.configuredBranch : s.branch) || '');
                const b = await call('GET', '/branches');
                setBranches(b.branches || []);
                // refresh=false: the status call above already re-exported engine state.
                const c = await call('GET', '/changes?refresh=false');
                setChanges((c.changes || []).map(parseChange));
                setDiffs({});
                setOpenPath(null);
            } catch (err) {
                setError(err.message);
                setErrorDetail(err.detail || null);
            } finally {
                setBusy('');
            }
        }, []);

        React.useEffect(() => { refresh({ label: 'Loading' }); }, [refresh]);

        const run = async (label, fn) => {
            setBusy(label);
            setError(null);
            setNotice(null);
            setBlocked(null);
            try {
                const result = await fn();
                // A 200 with ok:false is how a refused push or an unreachable remote comes
                // back -- the request was fine, the operation was not.
                if (result && result.ok === false) {
                    setError(result.error || 'The operation failed.');
                    setErrorDetail(result.detail || null);
                    setBusy('');
                    return null;
                }
                await refresh({ label: 'Refreshing' });
                return result;
            } catch (err) {
                // 409 is the designed refusal: the engine holds changes git does not.
                if (err.status === 409 && err.payload) {
                    setBlocked(err.payload);
                } else {
                    setError(err.message);
                    setErrorDetail(err.detail || null);
                }
                setBusy('');
                return null;
            }
        };

        const summarise = (result, verb) => {
            if (!result) return;
            const parts = [];
            if (result.applied) {
                Object.entries(result.applied)
                    .forEach(([k, v]) => parts.push(`${k}: ${v}`));
            }
            // Reported alongside the applied counts rather than folded into them:
            // "channels: 12, channelsDeleted: 3" is two facts, and merging them would
            // hide the one that cannot be undone.
            if (result.orphanCounts) {
                Object.entries(result.orphanCounts)
                    .forEach(([k, v]) => parts.push(`${k}: ${v}`));
            }
            let text = `${verb}${parts.length ? ' — ' + parts.join(', ') : ''}`;
            if (result.invalidChannels && result.invalidChannels.length) {
                text += `\n\nWARNING: ${result.invalidChannels.length} channel(s) were stored `
                    + 'but are invalid and will not run:\n'
                    + result.invalidChannels.join('\n');
            }
            if (result.problems && result.problems.length) {
                text += `\n\nProblems:\n${result.problems.join('\n')}`;
            }
            setNotice(text);
        };

        if (!status) {
            return h('div', { className: 'view', style: viewStyle() },
                busy ? `${busy}…` : 'No status available.');
        }

        // A configured-but-broken repository: show the message and the settings form
        // rather than an empty page, since the fix is almost always in the settings.
        if (status.ok === false && status.configured !== false) {
            return h('div', { className: 'view', style: viewStyle({ maxWidth: '900px' }) }, [
                h('h2', { key: 't', style: { marginTop: 0 } }, 'Source Control'),
                Banner('error', status.error || 'Git Sync could not read the repository.'),
                h('div', { key: 's' }, [SettingsForm()]),
            ]);
        }

        if (status.configured === false) {
            return h('div', { className: 'view', style: viewStyle({ maxWidth: '900px' }) }, [
                h('h2', { key: 't' }, 'Source Control'),
                Banner('info', 'Git Sync is installed but not configured. Set a remote and '
                    + 'branch below, then pull.'),
                h('div', { key: 's' }, [SettingsForm()]),
            ]);
        }

        const readOnly = !status.canPush;
        const rows = [];

        // ---------------- current state ----------------
        // dl.kv is the shell's own label/value grid, used by the bundled plugins for
        // exactly this kind of summary.
        const kv = (label, value, mono) => [
            h('dt', { key: label + '-k' }, label),
            h('dd', {
                key: label + '-v',
                className: mono ? 'mono' : undefined,
                style: { margin: 0 },
            }, value),
        ];

        rows.push(Panel('Repository', h('dl', { className: 'kv' }, [
            ...kv('Branch', status.branch || 'detached', true),
            ...kv('Commit', (status.headShort || '—') + ' ' + (status.headMessage || '')
                + (status.headAuthor ? '  — ' + status.headAuthor : ''), true),
            ...kv('Tracking', (status.ahead || 0) + ' ahead, ' + (status.behind || 0)
                + ' behind origin/' + status.branch),
            ...kv('Remote', status.remoteUrl || '—', true),
            ...kv('Syncing', (status.scope || []).join(', ') || '—'),
        ]), {
            badge: readOnly ? h('span', {
                className: 'tag',
                title: 'This instance is configured pull-only; commit and push are '
                    + 'rejected by the server, not just hidden here.',
            }, 'pull-only') : null,
        }));

        /*
         * Saving a branch in the settings form records an intent; it does not move the
         * checkout, because adopting a branch replaces this engine's configuration and
         * that is not something a settings save should do without being asked.
         *
         * So the two can disagree, and while they do every other number on this page --
         * ahead, behind, pending changes -- is measured against the branch that is
         * actually checked out, not the one the settings name. Saying so here, with the
         * switch one click away, is the alternative to a page that quietly means
         * something different from what it appears to say. The server refuses a pull in
         * this state for the same reason.
         */
        if (status.branchMismatch) {
            rows.push(Banner('warn', [
                h('div', { key: 'h', style: { fontWeight: 600 } },
                    `The settings ask for "${status.configuredBranch}", but this engine is `
                    + `on "${status.branch}".`),
                h('div', { key: 'd', style: { marginTop: '0.35rem' } },
                    'Everything on this page describes '
                    + `${status.branch}. Pull is refused until the two agree. Review the `
                    + `switch to ${status.configuredBranch} below to adopt it, or set the `
                    + `branch back to ${status.branch} in Settings.`),
            ]));
        }

        // ---------------- messages ----------------
        if (error) rows.push(ErrorBanner(error, errorDetail));
        if (notice) rows.push(Banner('ok', notice));
        if (blocked) {
            rows.push(Banner('warn', [
                h('div', { key: 'm', style: { fontWeight: 600, marginBottom: '0.4rem' } },
                    blocked.error || 'Refused'),
                h('div', { key: 'd' },
                    'The engine holds changes that are not committed. Commit them, or '
                    + 'discard them to take the branch as-is.'),
                FileList(blocked.pendingChanges, ''),
            ]));
        }

        // ---------------- pending changes ----------------
        const toggleDiff = async (path) => {
            if (openPath === path) {
                setOpenPath(null);
                return;
            }
            setOpenPath(path);
            if (diffs[path] !== undefined) return;
            setDiffBusy(path);
            try {
                const r = await call('GET', `/diff?path=${encodeURIComponent(path)}`);
                setDiffs(d => Object.assign({}, d, { [path]: r.diff || '' }));
            } catch (err) {
                // Kept against the path rather than raised to the page banner: one file
                // failing to diff should not read as the whole view being broken.
                setDiffs(d => Object.assign({}, d, {
                    [path]: `Could not read this diff: ${err.message}`,
                }));
            } finally {
                setDiffBusy(null);
            }
        };

        function ChangeRows() {
            // Falls back to the plain names from /status while the counts are in flight,
            // so the list never blinks empty on a slow engine.
            if (changes === null) {
                return FileList(status.pendingChanges,
                    'The engine matches this branch. Nothing to commit.');
            }
            if (changes.length === 0) {
                return h('div', { className: 'hint' },
                    'The engine matches this branch. Nothing to commit.');
            }
            return h('div', { style: { border: '1px solid var(--line)',
                borderRadius: 'var(--radius)', overflow: 'hidden' } },
                changes.map((c, i) => h('div', {
                    key: c.path,
                    style: {
                        borderTop: i === 0 ? 'none' : '1px solid var(--line)',
                        background: openPath === c.path ? 'var(--bg3)' : 'transparent',
                    },
                }, [
                    h('button', {
                        key: 'b',
                        type: 'button',
                        onClick: () => toggleDiff(c.path),
                        title: 'Show the diff for this file',
                        style: {
                            all: 'unset',
                            boxSizing: 'border-box',
                            cursor: 'pointer',
                            display: 'flex',
                            alignItems: 'center',
                            gap: '8px',
                            width: '100%',
                            padding: '5px 9px',
                            font: 'inherit',
                            color: 'var(--text)',
                        },
                    }, [
                        h('span', {
                            key: 'x',
                            className: 'mono',
                            style: { color: 'var(--text-faint)', width: '0.8em' },
                        }, openPath === c.path ? '\u2212' : '+'),
                        h('span', {
                            key: 't',
                            className: 'tag',
                            title: c.change,
                        }, c.change.slice(0, 3)),
                        h('span', {
                            key: 'p',
                            className: 'mono',
                            style: { flex: '1 1 auto', fontSize: '11px',
                                overflowWrap: 'anywhere', textAlign: 'left' },
                        }, c.path),
                        ChangeCounts(c),
                    ]),
                    openPath === c.path
                        ? h('div', { key: 'd', style: { padding: '0 9px 9px' } },
                            diffBusy === c.path
                                ? h('div', { className: 'hint' }, 'Reading diff…')
                                : DiffView(diffs[c.path]))
                        : null,
                ])));
        }

        rows.push(Panel(
            `Changes (${(changes || status.pendingChanges || []).length})`,
            h('div', null, [
                h('div', { key: 'l' }, ChangeRows()),
                h('div', { key: 'c', style: { marginTop: '0.8rem' } }, [
                    h('input', {
                        key: 'm',
                        type: 'text',
                        value: message,
                        placeholder: 'Commit message',
                        disabled: readOnly,
                        onInput: e => setMessage(e.target.value),
                        style: { width: '100%', maxWidth: '520px', marginBottom: '0.5rem' },
                    }),
                    h('div', { key: 'b' }, [
                        Button('Commit', async () => {
                            const r = await run('Committing',
                                () => call('POST', `/_commit?message=${encodeURIComponent(message)}&push=false`));
                            if (r) { setMessage(''); summarise(r, r.committed ? 'Committed.' : 'Nothing to commit.'); }
                        }, {
                            disabled: readOnly || !status.dirty || !!busy,
                            primary: true,
                            title: readOnly ? 'This instance is pull-only' : '',
                        }),
                        Button('Commit & Push', async () => {
                            const r = await run('Committing and pushing',
                                () => call('POST', `/_commit?message=${encodeURIComponent(message)}&push=true`));
                            if (r) { setMessage(''); summarise(r, 'Committed and pushed.'); }
                        }, { disabled: readOnly || !status.dirty || !!busy }),
                        Button('Discard', async () => {
                            if (!window.confirm(
                                'Discard all uncommitted changes?\n\nThe engine will be reset '
                                + 'to match this branch. Configuration that exists only in the '
                                + 'engine will be lost.')) return;
                            const r = await run('Discarding', () => call('POST', '/_discard'));
                            summarise(r, 'Discarded local changes.');
                        }, { disabled: !status.dirty || !!busy }),
                    ]),
                ]),
            ])));

        // ---------------- pull / push ----------------
        rows.push(Panel('Sync', h('div', null, [
            h('div', { key: 'b' }, [
                Button(status.behind ? `Pull (${status.behind})` : 'Pull', async () => {
                    const r = await run('Pulling', () => call('POST', '/_pull?discardLocal=false'));
                    summarise(r, 'Pulled and applied.');
                }, { disabled: !!busy, primary: true }),
                Button(status.ahead ? `Push (${status.ahead})` : 'Push', async () => {
                    const r = await run('Pushing', () => call('POST', '/_push'));
                    if (r) setNotice(`Pushed: ${r.push || 'ok'}`);
                }, {
                    disabled: readOnly || !status.ahead || !!busy,
                    title: readOnly ? 'This instance is pull-only' : '',
                }),
                Button('Test connection', async () => {
                    const r = await run('Testing', () => call('POST', '/_test'));
                    if (r) {
                        setNotice(r.ok
                            ? `Remote reachable. ${(r.branches || []).length} branch(es) visible.`
                            : `Connection failed: ${r.error}`);
                    }
                }, { disabled: !!busy }),
            ]),
            h('div', { key: 'n', style: { fontSize: '0.8rem', opacity: 0.75, marginTop: '0.5rem' } },
                'Pull resets the engine to this branch. It refuses while there are '
                + 'uncommitted changes rather than discarding them silently.'),
        ])));

        // ---------------- branches ----------------
        // A force reset follows the dropdown, so the same selector drives both the
        // reviewed switch and the unreviewed one, and falls back to staying put.
        const resetTarget = target || status.branch || '';
        rows.push(Panel('Branches', h('div', null, [
            Field('Switch to',
                h('div', null, [
                    h('select', {
                        key: 's',
                        value: target,
                        onChange: e => { setTarget(e.target.value); setPreview(null); },
                        style: { minWidth: '240px', marginRight: '0.5rem' },
                    }, branches.map(b => h('option', { key: b, value: b }, b))),
                    Button('Review switch', async () => {
                        setError(null);
                        setNotice(null);
                        setBusy('Comparing');
                        try {
                            const p = await call('GET',
                                `/branches/_previewSwitch?branch=${encodeURIComponent(target)}`);
                            // Reset to the harmless answer on every review, so a delete
                            // chosen for one branch cannot be carried into the next.
                            setOrphanAction('keep');
                            setPreview(p);
                        } catch (err) {
                            setError(err.message);
                        } finally {
                            setBusy('');
                        }
                    }, { disabled: !target || target === status.branch || !!busy }),
                ]),
                target && target === status.branch ? 'Already on this branch.' : null),

            preview ? h('div', {
                key: 'p',
                style: {
                    marginTop: '0.6rem',
                    borderTop: '1px solid rgba(128,128,128,0.3)',
                    paddingTop: '0.6rem',
                },
            }, [
                Banner('warn', [
                    h('div', { key: 'h', style: { fontWeight: 600 } },
                        `Switching to "${preview.toBranch}" replaces this engine's configuration.`),
                    h('div', { key: 'd', style: { marginTop: '0.35rem' } },
                        `Everything in scope (${(preview.replacesScope || []).join(', ')}) `
                        + 'will be overwritten with whatever that branch contains.'),
                ]),
                h('div', { key: 'i' }, [
                    h('strong', null, `Incoming changes (${(preview.incomingChanges || []).length})`),
                    FileList(preview.incomingChanges, 'This branch matches the current one.'),
                ]),
                (() => {
                    // Everything below is about what the branch does NOT contain, which
                    // the incoming-change list cannot show: a file that is absent from
                    // both trees produces no diff, yet the object it would have described
                    // is running on this engine right now.
                    const orphans = (preview.orphans || []).map(parseOrphan);
                    if (preview.orphanError) {
                        return h('div', { key: 'oe', style: { marginTop: '0.6rem' } },
                            Banner('warn', 'Could not work out which objects this branch '
                                + 'does not describe, so the switch will leave them all '
                                + `alone: ${preview.orphanError}`));
                    }
                    if (orphans.length === 0) {
                        return h('div', { key: 'o0', className: 'hint',
                            style: { marginTop: '0.6rem' } },
                            'This engine holds nothing that the branch does not describe, '
                            + 'so the switch adds and updates only.');
                    }
                    return h('div', { key: 'o', style: { marginTop: '0.6rem' } }, [
                        h('strong', { key: 'h' },
                            `Not on this branch (${orphans.length})`),
                        h('div', { key: 'd', className: 'hint' },
                            'These exist on the engine and the branch has no file for them. '
                            + 'They are not in the list above because there is nothing to '
                            + 'compare them against.'),
                        OrphanList(orphans),
                        OrphanChoice(orphans, orphanAction, setOrphanAction, !!busy),
                    ]);
                })(),
                preview.dirty ? h('div', { key: 'w', style: { marginTop: '0.6rem' } },
                    Banner('error', [
                        h('div', { key: 'm', style: { fontWeight: 600 } },
                            'You have uncommitted changes.'),
                        h('div', { key: 'b' },
                            'Commit them first, or tick discard below to lose them.'),
                        FileList(preview.pendingChanges, ''),
                    ])) : null,
                h('div', { key: 'a', style: { marginTop: '0.6rem' } }, [
                    Button(`Switch to ${preview.toBranch}`, async () => {
                        const orphanCount = (preview.orphans || []).length;
                        // The delete case names the count and the word in the prompt.
                        // A confirmation that says the same thing whatever is about to
                        // happen is one people learn to dismiss without reading.
                        const consequence = orphanAction === 'delete' && orphanCount
                            ? `\n\n${orphanCount} object(s) this branch does not describe `
                                + 'will be DELETED. Channels lose their message history '
                                + 'and this cannot be undone.'
                            : orphanAction === 'disable' && orphanCount
                                ? `\n\n${orphanCount} object(s) this branch does not `
                                    + 'describe will be undeployed and disabled.'
                                : '';
                        if (!window.confirm(
                            `Replace this engine's configuration with branch `
                            + `"${preview.toBranch}"?\n\nThis overwrites `
                            + `${(preview.replacesScope || []).join(', ')}.`
                            + consequence)) return;
                        const r = await run('Switching branch', () => call('POST',
                            `/branches/_switch?branch=${encodeURIComponent(preview.toBranch)}`
                            + `&confirm=true&discardLocal=${preview.dirty ? 'true' : 'false'}`
                            + `&orphanAction=${orphanAction}`));
                        if (r) {
                            setPreview(null);
                            setOrphanAction('keep');
                            summarise(r, `Switched to ${r.branch} and applied it.`);
                            // The branch indicator in the nav is fixed at page load, so a
                            // reload is what makes it match reality again.
                            setNotice(n => `${n || ''}\n\nReload the page to update the branch `
                                + 'shown in the navigation menu.');
                        }
                    }, { disabled: !!busy, primary: true }),
                    Button('Cancel', () => setPreview(null), { disabled: !!busy }),
                ]),
            ]) : null,

            h('div', {
                key: 'c',
                style: {
                    marginTop: '0.9rem',
                    borderTop: '1px solid rgba(128,128,128,0.3)',
                    paddingTop: '0.7rem',
                },
            }, [
                Field('New branch',
                    h('div', null, [
                        h('input', {
                            key: 'n',
                            type: 'text',
                            value: newBranch,
                            placeholder: 'feature/my-change',
                            disabled: readOnly,
                            onInput: e => setNewBranch(e.target.value),
                            style: { minWidth: '240px', marginRight: '0.5rem' },
                        }),
                        h('label', { key: 'p', style: { marginRight: '0.6rem' } }, [
                            h('input', {
                                key: 'c',
                                type: 'checkbox',
                                checked: pushNew,
                                disabled: readOnly,
                                onChange: e => setPushNew(e.target.checked),
                            }),
                            ' push to remote',
                        ]),
                        Button('Create', async () => {
                            const r = await run('Creating branch', () => call('POST',
                                `/branches/_create?branch=${encodeURIComponent(newBranch)}`
                                + `&push=${pushNew}`));
                            if (r) {
                                setNewBranch('');
                                setNotice(`Created and checked out ${r.branch}`
                                    + (r.pushed ? ', pushed to origin.' : '.')
                                    + '\n\nReload the page to update the branch shown in the '
                                    + 'navigation menu.');
                            }
                        }, { disabled: readOnly || !newBranch || !!busy }),
                    ]),
                    'Branches from the current commit, so the engine keeps exactly the '
                    + 'configuration it has now. Nothing is overwritten.'),
            ]),

            h('div', {
                key: 'r',
                style: {
                    marginTop: '0.9rem',
                    borderTop: '1px solid color-mix(in srgb, var(--err) 45%, transparent)',
                    paddingTop: '0.7rem',
                },
            }, [
                h('div', {
                    key: 'h',
                    style: { fontWeight: 600, marginBottom: '0.25rem', color: 'var(--err)' },
                }, 'Force reset'),
                h('div', { className: 'hint', key: 'd' },
                    `Discards everything this engine holds that ${resetTarget || 'the branch'} `
                    + 'on the remote does not, then applies that branch. Uncommitted changes '
                    + 'and commits that were never pushed are both destroyed, and neither can '
                    + 'be recovered. This is the way out when the engine has drifted too far '
                    + 'to reconcile — ordinary catching up is Pull.'),

                !resetArmed
                    ? h('div', { key: 'a', style: { marginTop: '0.5rem' } },
                        Button(resetTarget ? `Force reset to ${resetTarget}` : 'Force reset',
                            async () => {
                                setResetText('');
                                setOrphanAction('keep');
                                setResetPreview(null);
                                setResetArmed(true);
                                // Fetched on arming rather than on page load: it costs a
                                // fetch and a full engine export, which no one should pay
                                // for by scrolling past a panel they are not using.
                                try {
                                    setResetPreview(await call('GET',
                                        '/branches/_previewSwitch?branch='
                                        + encodeURIComponent(resetTarget)));
                                } catch (err) {
                                    // The confirmation still works without it; it just
                                    // cannot offer anything but leaving the objects alone.
                                    setResetPreview({ orphanError: err.message });
                                }
                            },
                            { disabled: !resetTarget || !!busy, danger: true }))
                    : h('div', { key: 'c2', style: { marginTop: '0.5rem' } }, [
                        Banner('error', [
                            h('div', { key: 'h', style: { fontWeight: 600 } },
                                `Reset this engine to origin/${resetTarget}?`),
                            h('div', { key: 'l', style: { marginTop: '0.35rem' } }, [
                                resetTarget !== status.branch
                                    ? `The checked-out branch changes from ${status.branch} `
                                        + `to ${resetTarget}. `
                                    : '',
                                status.dirty
                                    ? `${(status.pendingChanges || []).length} uncommitted `
                                        + 'change(s) will be lost. '
                                    : 'There are no uncommitted changes. ',
                                status.ahead
                                    ? `${status.ahead} commit(s) that were never pushed will `
                                        + 'be lost. '
                                    : '',
                                `Everything in scope (${(status.scope || []).join(', ')}) is `
                                + 'replaced with what that branch contains.',
                            ].filter(Boolean)),
                        ]),
                        status.dirty
                            ? h('div', { key: 'f' }, [
                                h('strong', null, 'Changes that will be destroyed'),
                                FileList(status.pendingChanges, ''),
                            ])
                            : null,
                        (() => {
                            if (!resetPreview) {
                                return h('div', { key: 'ol', className: 'hint' },
                                    'Working out what this branch does not describe…');
                            }
                            if (resetPreview.orphanError) {
                                return h('div', { key: 'oe' },
                                    Banner('warn', 'Could not work out which objects this '
                                        + 'branch does not describe, so the reset will '
                                        + `leave them alone: ${resetPreview.orphanError}`));
                            }
                            const orphans = (resetPreview.orphans || []).map(parseOrphan);
                            if (orphans.length === 0) {
                                return h('div', { key: 'o0', className: 'hint' },
                                    'This engine holds nothing that the branch does not '
                                    + 'describe.');
                            }
                            return h('div', { key: 'o', style: { marginTop: '0.5rem' } }, [
                                h('strong', { key: 'h' },
                                    `Not on this branch (${orphans.length})`),
                                OrphanList(orphans),
                                OrphanChoice(orphans, orphanAction, setOrphanAction, !!busy),
                            ]);
                        })(),
                        Field(`Type "${resetTarget}" to confirm`,
                            h('input', {
                                type: 'text',
                                value: resetText,
                                placeholder: resetTarget,
                                autoFocus: true,
                                onInput: e => setResetText(e.target.value),
                                style: { minWidth: '240px' },
                            })),
                        h('div', { key: 'b', style: { marginTop: '0.5rem' } }, [
                            Button('Force reset', async () => {
                                const r = await run('Resetting', () => call('POST',
                                    '/branches/_forceReset?branch='
                                    + `${encodeURIComponent(resetTarget)}&confirm=true`
                                    + `&orphanAction=${orphanAction}`));
                                if (r) {
                                    setResetArmed(false);
                                    setResetText('');
                                    setResetPreview(null);
                                    setOrphanAction('keep');
                                    setPreview(null);
                                    summarise(r, `Reset to origin/${r.branch} at `
                                        + `${(r.headCommit || '').slice(0, 8)}.`
                                        + ((r.discarded || []).length || r.discardedCommits
                                            ? ` Discarded ${(r.discarded || []).length} change(s)`
                                                + ` and ${r.discardedCommits || 0} unpushed `
                                                + 'commit(s).'
                                            : ''));
                                    // The nav branch indicator is read once at page load.
                                    setNotice(n => `${n || ''}\n\nReload the page to update `
                                        + 'the branch shown in the navigation menu.');
                                }
                            }, {
                                // Trimmed, but not case-folded: branch names are
                                // case-sensitive, so accepting the wrong case here would
                                // confirm a different branch than the one typed.
                                disabled: resetText.trim() !== resetTarget || !!busy,
                                danger: true,
                            }),
                            Button('Cancel', () => {
                                setResetArmed(false);
                                setResetText('');
                            }, { disabled: !!busy }),
                        ]),
                    ]),
            ]),
        ])));

        // ---------------- settings ----------------
        rows.push(h('div', { key: 'settings-toggle', style: { marginBottom: '0.6rem' } },
            Button(showSettings ? 'Hide settings' : 'Settings',
                () => setShowSettings(v => !v))));
        if (showSettings) rows.push(SettingsForm());

        return h('div', {
            className: 'view',
            style: viewStyle({ maxWidth: '980px' }),
        }, [
            busy ? Banner('info', `${busy}…`) : null,
            ...rows,
        ]);

        // ---------------- settings form ----------------
        // refresh() re-reads status; once the settings validate, that is what flips this
        // view from the settings form to the repository view.
        function SettingsForm() {
            return h(SettingsPanel, {
                key: 'settings',
                onValidated: () => refresh({ label: 'Loading repository' }),
            });
        }
    }

    /**
     * Saving and validating are two steps on purpose.
     *
     * Save always persists, so a half-finished configuration is never lost -- the
     * operator can come back to it. Validation is what decides whether we leave the
     * form: `onValidated` is only called once the remote answers and the branch is
     * really there, so the repository view can never render against settings that do
     * not work.
     */
    function SettingsPanel(props) {
        const onValidated = (props && props.onValidated) || (() => {});
        const [form, setForm] = React.useState(null);
        const [busy, setBusy] = React.useState(false);
        const [error, setError] = React.useState(null);
        const [saved, setSaved] = React.useState(false);
        const [problems, setProblems] = React.useState([]);
        const [invalid, setInvalid] = React.useState(null);
        const [remoteBranches, setRemoteBranches] = React.useState(null);
        const [branchError, setBranchError] = React.useState(null);
        const [loadingBranches, setLoadingBranches] = React.useState(false);

        React.useEffect(() => {
            let cancelled = false;
            call('GET', '/settings')
                .then(s => {
                    if (cancelled) return;
                    setForm(Object.assign({ secret: '' }, s));
                    // Populate the dropdown straight away when a remote is already
                    // stored; otherwise wait to be asked, since there is nothing to
                    // query yet.
                    if (s.remoteUrl) {
                        call('POST', '/branches/_remote', {})
                            .then(res => {
                                if (cancelled) return;
                                setRemoteBranches(res.branches || []);
                                if (res.error) setBranchError(res.error);
                            })
                            .catch(() => { /* the button is still there */ });
                    }
                })
                .catch(e => setError(e.message));
            return () => { cancelled = true; };
        }, []);

        if (error) return Panel('Settings', ErrorBanner(error, null));
        if (!form) return Panel('Settings', 'Loading…');

        const set = (k, v) => setForm(f => Object.assign({}, f, { [k]: v }));

        // Branches come from ls-remote against whatever is currently typed in, so the
        // dropdown works before anything has been saved.
        const loadBranches = async () => {
            setLoadingBranches(true);
            setBranchError(null);
            try {
                const res = await call('POST', '/branches/_remote', {
                    remoteUrl: form.remoteUrl || '',
                    authType: form.authType || 'NONE',
                    username: form.username || '',
                    knownHosts: form.knownHosts || '',
                    // Blank means "use the stored credential", matching the save path.
                    secret: form.secret || '',
                });
                const list = res.branches || [];
                setRemoteBranches(list);
                if (res.error) setBranchError(res.error);
                // Pre-select something sensible rather than leaving it on a branch the
                // remote does not have.
                if (list.length && !list.includes(form.branch)) {
                    const fallback = list.includes('main') ? 'main'
                        : (list.includes('master') ? 'master' : list[0]);
                    set('branch', fallback);
                }
            } catch (err) {
                setRemoteBranches([]);
                setBranchError(err.message);
            } finally {
                setLoadingBranches(false);
            }
        };

        /*
         * A dropdown once the remote can be listed, a text box until then.
         *
         * The fallback is not laziness: you cannot enumerate the branches of a remote you
         * cannot reach, and refusing to let anyone type a branch name would make the form
         * unusable exactly when it is being set up for the first time.
         */
        function BranchSelector() {
            const list = remoteBranches;
            const controls = [];

            if (list && list.length) {
                const options = list.slice();
                // Never silently drop a configured branch the remote no longer has.
                const missing = form.branch && !options.includes(form.branch);
                controls.push(h('select', {
                    key: 'sel',
                    value: form.branch || '',
                    onChange: e => set('branch', e.target.value),
                    style: { minWidth: '260px', marginRight: '0.5rem' },
                }, [
                    missing ? h('option', { key: '__missing', value: form.branch },
                        form.branch + '  (not on remote)') : null,
                ].concat(options.map(b => h('option', { key: b, value: b }, b)))));
            } else {
                controls.push(h('input', {
                    key: 'txt',
                    type: 'text',
                    value: form.branch == null ? '' : form.branch,
                    placeholder: 'main',
                    onInput: e => set('branch', e.target.value),
                    style: { minWidth: '260px', marginRight: '0.5rem' },
                }));
            }

            controls.push(Button(
                loadingBranches ? 'Loading…' : (list ? 'Refresh branches' : 'Load branches'),
                loadBranches,
                { disabled: loadingBranches || !form.remoteUrl }));

            return h('div', { style: { display: 'flex', alignItems: 'center' } }, controls);
        }
        const text = (k, placeholder) => h('input', {
            type: 'text',
            value: form[k] == null ? '' : form[k],
            placeholder: placeholder || '',
            onInput: e => set(k, e.target.value),
            style: { width: '100%', maxWidth: '460px' },
        });

        return Panel('Settings', h('div', null, [
            saved ? Banner('ok', 'Settings saved.') : null,
            problems.length ? Banner('warn', [
                h('div', { key: 'h', style: { fontWeight: 600 } },
                    'Saved, but some values could not be used:'),
                h('ul', { key: 'l', style: { margin: '0.3rem 0 0', paddingLeft: '1.1rem' } },
                    problems.map((t, i) => h('li', { key: i }, t))),
            ]) : null,
            invalid ? Banner('error', [
                h('div', { key: 'h', style: { fontWeight: 600 } },
                    'Settings saved, but the repository could not be reached'),
                h('div', { key: 'm', style: { marginTop: '4px' } }, invalid),
                h('div', { key: 'f', className: 'hint', style: { marginTop: '5px' } },
                    'Fix the settings and save again. The repository view stays hidden '
                    + 'until a connection succeeds.'),
            ]) : null,
            Field('Remote URL', text('remoteUrl', 'https://gitlab.example.com/org/oie-config.git')),
            Field('Branch', BranchSelector(), branchError),
            Field('Authentication',
                h('select', {
                    value: form.authType,
                    onChange: e => set('authType', e.target.value),
                }, [
                    h('option', { key: 'n', value: 'NONE' }, 'None (public remote)'),
                    h('option', { key: 'h', value: 'HTTPS_TOKEN' }, 'HTTPS token'),
                    h('option', { key: 's', value: 'SSH_KEY' }, 'SSH key'),
                ])),
            form.authType === 'HTTPS_TOKEN'
                ? Field('Username', text('username', 'oauth2'),
                    'GitLab accepts any non-empty username with a token; some forges want '
                    + 'the real one.')
                : null,
            form.authType !== 'NONE'
                ? Field(form.authType === 'SSH_KEY' ? 'Private key' : 'Token',
                    h('textarea', {
                        value: form.secret,
                        placeholder: form.hasSecret
                            ? '••• stored — leave blank to keep it'
                            : (form.authType === 'SSH_KEY'
                                ? '-----BEGIN OPENSSH PRIVATE KEY-----' : 'glpat-…'),
                        rows: form.authType === 'SSH_KEY' ? 6 : 2,
                        onInput: e => set('secret', e.target.value),
                        style: { width: '100%', maxWidth: '460px', fontFamily: 'ui-monospace, monospace' },
                    }),
                    'Encrypted with the engine’s own key before storage, and never sent '
                    + 'back to this page. Leave blank to keep the stored one.')
                : null,
            form.authType === 'SSH_KEY'
                ? Field('Known hosts',
                    h('textarea', {
                        value: form.knownHosts || '',
                        rows: 3,
                        placeholder: 'gitlab.example.com ssh-ed25519 AAAA…',
                        onInput: e => set('knownHosts', e.target.value),
                        style: { width: '100%', maxWidth: '460px', fontFamily: 'ui-monospace, monospace' },
                    }),
                    'Leave blank and the host key is accepted unseen, so the first '
                    + 'connection cannot detect a man in the middle.')
                : null,
            Field('Mode',
                h('select', {
                    value: form.mode,
                    onChange: e => set('mode', e.target.value),
                }, [
                    h('option', { key: 'r', value: 'READ_ONLY' }, 'Pull only'),
                    h('option', { key: 'w', value: 'READ_WRITE' }, 'Pull, commit and push'),
                ]),
                'Pull-only is the right setting for production: the engine follows the '
                + 'branch and cannot author to it.'),
            Field('Subdirectory', text('subdirectory', 'environments/staging'),
                'Optional path within the repository, so one repo can hold several '
                + 'environments.'),
            Field('Scope', ScopeCheckboxes(form, set),
                'Alerts and global scripts are off by default: they are the two that most '
                + 'often differ legitimately between environments.'),
            Field('Commit author', h('div', null, [
                text('authorName', 'Open Integration Engine'),
                h('div', { style: { height: '0.3rem' } }),
                text('authorEmail', 'oie@example.org'),
            ])),
            Field('Scheduled pull',
                h('input', {
                    type: 'number',
                    min: 0,
                    value: form.pullIntervalSeconds == null ? 0 : form.pullIntervalSeconds,
                    onInput: e => set('pullIntervalSeconds', e.target.value),
                    style: { width: '120px' },
                }),
                'Seconds between automatic pulls. 0 disables it. Takes effect on restart.'),
            h('div', { key: 'actions', style: { marginTop: '0.8rem' } }, [
                Button(busy ? 'Saving…' : 'Save settings', async () => {
                    setBusy(true);
                    setError(null);
                    setSaved(false);
                    setProblems([]);
                    setInvalid(null);
                    try {
                        const body = {};
                        ['remoteUrl', 'branch', 'authType', 'username', 'mode', 'subdirectory',
                         'authorName', 'authorEmail', 'scope', 'knownHosts'].forEach(k => {
                            body[k] = form[k] == null ? '' : String(form[k]);
                        });
                        body.pullIntervalSeconds = String(form.pullIntervalSeconds || 0);
                        if (form.secret) body.secret = form.secret;

                        // Step 1: persist. This succeeds even when values are unusable;
                        // whatever could not be applied comes back in `problems`.
                        const updated = await call('POST', '/settings', body);
                        setForm(Object.assign({ secret: '' }, updated));
                        setSaved(true);
                        setProblems(updated.problems || []);

                        // Step 2: check it actually works. Only a pass leaves the form.
                        const check = await call('POST', '/_validate');
                        if (check.valid) {
                            onValidated();
                        } else {
                            setInvalid(check.error || 'The remote could not be reached.');
                        }
                    } catch (err) {
                        setError(err.message);
                    } finally {
                        setBusy(false);
                    }
                }, { disabled: busy, primary: true }),
                form.hasSecret ? Button('Clear credential', async () => {
                    if (!window.confirm('Remove the stored credential?')) return;
                    setBusy(true);
                    try {
                        const updated = await call('POST', '/settings', { clearSecret: 'true' });
                        setForm(Object.assign({ secret: '' }, updated));
                    } catch (err) {
                        setError(err.message);
                    } finally {
                        setBusy(false);
                    }
                }, { disabled: busy }) : null,
            ]),
        ]));
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------
    /*
     * Each registration is attempted independently.
     *
     * The shell calls register() once and lets an exception propagate, so a fault
     * part-way through leaves whatever ran before it registered and silently drops
     * everything after. That happened here: a ReferenceError between the two nav
     * items left "Source Control" in the sidebar and the branch indicator missing,
     * with nothing on the page to say why. Isolating each one turns that into a
     * console warning and a missing single item.
     */
    const safely = (label, fn) => {
        try {
            fn();
        } catch (err) {
            console.warn('[gitsync] could not register ' + label, err);
        }
    };

    safely('icons', () => {
        platform.registerIcon('git-source', ICON_SOURCE);
        platform.registerIcon('git-branch', ICON_BRANCH);
    });

    safely('source control view', () => {
        platform.registerView('/source-control', platform.reactView(SourceControlView), {
            title: 'Source Control',
        });
        platform.registerNavItem({
            id: 'source-control',
            label: 'Source Control',
            icon: 'git-source',
            path: '/source-control',
            section: 'Plugins',
            order: 80,
        });
    });

    // The branch indicator, last in the last section so it sits at the bottom of the
    // menu. Its label is fixed at page load because the nav registry is read once when
    // the shell mounts -- so after switching branches the page needs a reload, which the
    // Source Control view says explicitly rather than leaving it stale and silent.
    safely('branch indicator', () => {
        // The branch indicator gets its own route rather than pointing at
        // /source-control, because the shell marks a nav item active by comparing its
        // path to the current one -- sharing the path lit both items up at once. This
        // route exists only to bounce to the real page.
        platform.registerView('/git-branch', platform.reactView(function BranchRedirect() {
            React.useEffect(() => { platform.router.navigate('/source-control'); }, []);
            return h('div', { className: 'view', style: { padding: '1rem', opacity: 0.7 } },
                'Opening Source Control…');
        }), { title: 'Source Control' });

        platform.registerNavItem({
            id: 'source-control-branch',
            label: branchLabel(BOOT),
            icon: 'git-branch',
            path: '/git-branch',
            section: 'Plugins',
            order: 9999,
        });
    });
}
