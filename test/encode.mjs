/*
 * The mirror of decode(): a Map parameter has to arrive XStream-shaped, so a plain
 * JSON object body is rejected outright (verified: plain JSON gives a 500, the XML
 * map gives a 200). The engine's own scripts in this repo talk XML to the API for
 * the same reason, so this does too rather than guessing at XStream's JSON dialect.
 */
export function encodeMap(obj) {
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
