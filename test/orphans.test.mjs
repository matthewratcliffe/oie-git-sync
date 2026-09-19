import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseOrphan } from './orphans.mjs';

test('orphan row parser', () => {
    const c = parseOrphan(
        'channels\t9f2c1b6e-1d40-4c7a-9d21-2b4f6a0c8e11\tLegacy ADT Feed\tdeployed');
    assert.equal(c.scope, 'channels');
    assert.equal(c.id, '9f2c1b6e-1d40-4c7a-9d21-2b4f6a0c8e11');
    assert.equal(c.name, 'Legacy ADT Feed');
    assert.equal(c.deployed, true);
    console.log('ok   a deployed channel');

    // Anything that is not the literal "deployed" means not deployed. The flag
    // drives a warning in the UI, so it must not be true by accident.
    const u = parseOrphan('channels\tabc\tOld Feed\tundeployed');
    assert.equal(u.deployed, false);
    console.log('ok   an undeployed channel');

    // Only channels can be deployed; the other two types always report undeployed,
    // and the parser must not invent a state for them.
    const t = parseOrphan('code-templates\tdef\tformatDate\tundeployed');
    assert.equal(t.scope, 'code-templates');
    assert.equal(t.deployed, false);
    console.log('ok   a code template');

    const a = parseOrphan('alerts\tghi\tQueue depth\tundeployed');
    assert.equal(a.scope, 'alerts');
    assert.equal(a.name, 'Queue depth');
    console.log('ok   an alert');

    // Tabs are the only separator. A name with spaces in it is ordinary, and a
    // split on whitespace would turn one object into four.
    const s = parseOrphan('channels\tjkl\tHL7 v2 Inbound (staging)\tdeployed');
    assert.equal(s.name, 'HL7 v2 Inbound (staging)');
    assert.equal(s.deployed, true);
    console.log('ok   spaces in the name');

    // The server substitutes "(unnamed)" for a blank name, but a truncated row
    // must not render as the word "undefined" next to a delete button.
    const short = parseOrphan('channels\tmno');
    assert.equal(short.name, '(unnamed)');
    assert.equal(short.deployed, false);
    console.log('ok   a truncated row');

    const empty = parseOrphan('');
    assert.equal(empty.scope, '');
    assert.equal(empty.name, '(unnamed)');
    assert.equal(empty.deployed, false);
    console.log('ok   an empty row');
});
