/*
 * The mirror of parseChange() in webadmin/web/plugin.js.
 *
 * /changes sends one tab-separated string per file rather than a list of maps,
 * because XStream renders a list of maps in a shape the console's decoder cannot
 * reliably unpick -- the same trap that made an immutable list arrive as an
 * undecodable blob. A flat list of strings is a format that already works.
 */
export function parseChange(row) {
    const [path, change, added, removed, kind] = String(row).split('\t');
    return {
        path: path || '',
        change: change || 'modify',
        added: Number(added) || 0,
        removed: Number(removed) || 0,
        binary: kind === 'binary',
    };
}
