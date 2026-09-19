/*
 * The mirror of parseOrphan() in webadmin/web/plugin.js.
 *
 * An "orphan" is an object this engine holds that the branch being adopted has
 * no file for. The switch preview lists them so the operator can decide what
 * happens to them, and it sends them one tab-separated string per object for
 * the same reason /changes does: XStream renders a list of maps in a shape the
 * console's decoder cannot reliably unpick, and a flat list of strings is a
 * format that already works.
 */
export function parseOrphan(row) {
    const [scope, id, name, state] = String(row).split('\t');
    return {
        scope: scope || '',
        id: id || '',
        name: name || '(unnamed)',
        deployed: state === 'deployed',
    };
}
