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
export function decode(node) {
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
