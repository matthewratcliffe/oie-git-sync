import { encodeMap } from './encode.mjs';

let fail = 0;
const eq = (name, got, want) => {
    if (got !== want) {
        console.log('FAIL ' + name);
        console.log('  got  ' + got);
        console.log('  want ' + want);
        fail++;
    } else {
        console.log('ok   ' + name);
    }
};

eq('simple', encodeMap({ branch: 'main' }),
   '<map><entry><string>branch</string><string>main</string></entry></map>');

eq('escapes XML', encodeMap({ scope: 'a&b<c>d' }),
   '<map><entry><string>scope</string><string>a&amp;b&lt;c&gt;d</string></entry></map>');

// Absent keys mean "leave this field alone" server-side, so null/undefined must not be
// sent as empty strings -- that would clear the stored value instead of skipping it.
eq('drops null and undefined', encodeMap({ a: '1', b: null, c: undefined }),
   '<map><entry><string>a</string><string>1</string></entry></map>');

eq('empty string is kept', encodeMap({ subdirectory: '' }),
   '<map><entry><string>subdirectory</string><string></string></entry></map>');

eq('numbers stringified', encodeMap({ pullIntervalSeconds: 0 }),
   '<map><entry><string>pullIntervalSeconds</string><string>0</string></entry></map>');

console.log(fail ? '\n' + fail + ' test(s) failed' : '\nall encoder tests passed');
process.exit(fail ? 1 : 0);
