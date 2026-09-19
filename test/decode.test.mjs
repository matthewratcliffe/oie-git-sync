import { decode } from './decode.mjs';
let fail = 0;
const eq = (name, got, want) => {
    const g = JSON.stringify(got), w = JSON.stringify(want);
    if (g !== w) { console.log(`FAIL ${name}\n  got  ${g}\n  want ${w}`); fail++; }
    else console.log(`ok   ${name}`);
};

// Real payload: GET /api/gitsync/status on an unconfigured instance.
eq('status (unconfigured)',
  decode({"linked-hash-map":{"entry":[{"string":"ok","boolean":true},{"string":"configured","boolean":false}]}}),
  { ok: true, configured: false });

// Real payload: GET /api/gitsync/settings.
eq('settings',
  decode({"linked-hash-map":{"entry":[{"string":"ok","boolean":true},{"string":["remoteUrl",null]},{"string":["branch","main"]},{"string":["authType","NONE"]},{"string":["username",null]},{"string":["mode","READ_ONLY"]},{"string":["subdirectory",null]},{"string":["authorName","Open Integration Engine"]},{"string":["authorEmail","oie@localhost"]},{"string":"pullIntervalSeconds","int":0},{"string":["scope",null]},{"string":["knownHosts",null]},{"string":"hasSecret","boolean":false},{"string":"configured","boolean":false}]}}),
  { ok: true, remoteUrl: null, branch: 'main', authType: 'NONE', username: null, mode: 'READ_ONLY',
    subdirectory: null, authorName: 'Open Integration Engine', authorEmail: 'oie@localhost',
    pullIntervalSeconds: 0, scope: null, knownHosts: null, hasSecret: false, configured: false });

// Nested list, as pendingChanges arrives.
eq('nested list',
  decode({"linked-hash-map":{"entry":[{"string":"dirty","boolean":true},{"string":"pendingChanges","list":{"string":["channels/a.xml","channels/b.xml"]}}]}}),
  { dirty: true, pendingChanges: ['channels/a.xml', 'channels/b.xml'] });

eq('empty list', decode({"list":null}), []);
eq('single-element list', decode({"list":{"string":"only.xml"}}), ['only.xml']);

// Nested map, as `applied` counts arrive.
eq('nested map',
  decode({"linked-hash-map":{"entry":[{"string":"applied","linked-hash-map":{"entry":[{"string":"channels","int":3}]}}]}}),
  { applied: { channels: 3 } });

// Already-plain JSON must pass through untouched (the error bodies I build by hand).
eq('plain passthrough',
  decode({ ok: false, error: 'boom', pendingChanges: ['x'] }),
  { ok: false, error: 'boom', pendingChanges: ['x'] });

// Captured verbatim from the running engine: what List.of() actually serialises to.
// The elements are not in there at all -- it is a CollSer blob -- so the only correct
// answer is an empty array, never an object that would break (x || []).join(...).
eq('immutable list degrades to []',
  decode({"linked-hash-map":{"entry":[
    {"string":"ok","boolean":true},
    {"string":"branches","java.util.ImmutableCollections_-ListN":{"@resolves-to":"java.util.CollSer","@serialization":"custom","java.util.CollSer":{"default":{"tag":1},"int":0}}},
    {"string":["error","cannot open git-upload-pack"]}]}}),
  { ok: true, branches: [], error: 'cannot open git-upload-pack' });

console.log(fail ? `\n${fail} test(s) failed` : '\nall decoder tests passed');
process.exit(fail ? 1 : 0);
