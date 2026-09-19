import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseChange } from './changes.mjs';

test('change row parser', () => {
    const c = parseChange('channels/new-channel.xml\tmodify\t12\t3\ttext');
    assert.equal(c.path, 'channels/new-channel.xml');
    assert.equal(c.change, 'modify');
    assert.equal(c.added, 12);
    assert.equal(c.removed, 3);
    assert.equal(c.binary, false);
    console.log('ok   a normal row');

    const b = parseChange('certs/keystore.jks\tmodify\t0\t0\tbinary');
    assert.equal(b.binary, true);
    console.log('ok   binary flag');

    // A new file in a repository with no prior commit: all added, nothing removed.
    const a = parseChange('server-settings.xml\tadd\t41\t0\ttext');
    assert.equal(a.change, 'add');
    assert.equal(a.added, 41);
    assert.equal(a.removed, 0);
    console.log('ok   an added file');

    // Spaces are legal in a path and must not be treated as a separator; only the
    // tab is. A path with a space in it is exactly what a split(' ') would ruin.
    const s = parseChange('channels/My Channel v2.xml\tmodify\t1\t1\ttext');
    assert.equal(s.path, 'channels/My Channel v2.xml');
    assert.equal(s.added, 1);
    console.log('ok   spaces in the path');

    // Defensive: a short or malformed row must still render as a row rather than
    // throwing inside the view, which would take the whole page down.
    const short = parseChange('alerts/x.xml');
    assert.equal(short.path, 'alerts/x.xml');
    assert.equal(short.change, 'modify');
    assert.equal(short.added, 0);
    assert.equal(short.removed, 0);
    assert.equal(short.binary, false);
    console.log('ok   a truncated row degrades');

    const junk = parseChange('a\tmodify\tnot-a-number\t\ttext');
    assert.equal(junk.added, 0);
    assert.equal(junk.removed, 0);
    console.log('ok   unparseable counts become zero');

    console.log('\nall change-row tests passed');
});
