// Runs callengine/livekit.js against a stub LiveKit SDK and drives its
// PRE-WARM state machine.
//
// Why this file exists: the pre-warm rules are all "must not", and every one of
// them fails silently. Warm twice for the same ring and the connect in flight
// is torn down, so the answer is slow anyway. Drop the room after the accept
// and the server reads it as the callee LEAVING, which ends the call the user
// just answered. Fail to adopt it and the second connect collapses the call
// server-side. None of that shows in a syntax check, and none of it can be
// reached from the Kotlin side — the logic lives in the page.
//
// Deliberately a stub rather than the real SDK: what is under test is the
// bookkeeping around Room, not Room itself.
//
//   node livekit-page-harness.js <path to livekit.js>
//
// Exits non-zero, with the failure on stderr, when a rule is broken.

const fs = require('fs');
const vm = require('vm');

const source = fs.readFileSync(process.argv[2], 'utf8');

/** Every Room the page built, in order, with what was done to it. */
const rooms = [];

/** See `fakeRoom().connect`. The pre-warm checks need connects they can hold. */
let holdConnects = true;

function fakeRoom() {
    const room = {
        id: rooms.length,
        connects: [],
        disconnected: false,
        metadata: '',
        remoteParticipants: new Map(),
        localParticipant: {
            audioLevel: 0,
            isMicrophoneEnabled: false,
            permissions: { canPublish: true },
            published: [],
            setAttributes: async () => {},
            setMicrophoneEnabled: async function (on) { this.isMicrophoneEnabled = !!on; },
            setCameraEnabled: async () => {},
            publishTrack: async function (t) { this.published.push(t); },
            trackPublications: new Map(),
        },
        /**
         * While [holdConnects] is set, the returned promise is left in flight
         * and the test settles it through `connects[n].settle` — which is the
         * only way to check what happens to a connect that lands late, or after
         * its ring was dropped. Otherwise it resolves at once, as an ordinary
         * join does.
         */
        connect(url, token) {
            if (!holdConnects) {
                this.connects.push({ url, token, settle: { resolve() {}, reject() {} } });
                return Promise.resolve();
            }
            let settle;
            const promise = new Promise((resolve, reject) => { settle = { resolve, reject }; });
            this.connects.push({ url, token, settle });
            return promise;
        },
        on() { return this; },
        off() { return this; },
        removeAllListeners() { return this; },
        async disconnect() { this.disconnected = true; },
    };
    rooms.push(room);
    return room;
}

/** The tracks `prewarmMedia` opened, so the test can see them stopped or published. */
const tracks = [];

function fakeTrack(kind) {
    const track = { kind: kind, stopped: false, stop() { this.stopped = true; } };
    tracks.push(track);
    return track;
}

const LivekitClient = {
    Room: function () { return fakeRoom(); },
    VideoPresets: { h720: { resolution: { width: 1280, height: 720 } } },
    Track: { Kind: { Audio: 'audio', Video: 'video' }, Source: { Microphone: 'microphone' } },
    RoomEvent: {},
    ConnectionState: { Connected: 'connected', Reconnecting: 'reconnecting', Disconnected: 'disconnected' },
    ConnectionQuality: {},
    createLocalTracks: async (opts) => {
        const made = [];
        if (opts.audio) { made.push(fakeTrack('audio')); }
        if (opts.video) { made.push(fakeTrack('video')); }
        return made;
    },
};

const context = {
    console,
    setTimeout,
    clearTimeout,
    setInterval: () => 0,
    clearInterval: () => {},
    navigator: { mediaDevices: { enumerateDevices: async () => [], getUserMedia: async () => ({ getVideoTracks: () => [] }) } },
    TextDecoder,
    TextEncoder,
    Date,
    Promise,
    /** What the page reported to Kotlin. */
    sent: [],
};
context.window = context;
context.globalThis = context;
context.window.LivekitClient = LivekitClient;
context.window.cefQuery = ({ request }) => { context.sent.push(JSON.parse(request)); };
context.window.addEventListener = () => {};
// The page hands remote media to call.js; nothing here draws, so a no-op stands in.
context.window.zillitCall = { listDevices() {}, clearLocalPreview() {}, attachLocalPreview() {} };

vm.createContext(context);
vm.runInContext(source, context, { filename: 'livekit.js' });

const lk = context.window.zillitLk;
if (!lk) { throw new Error('livekit.js did not install window.zillitLk'); }

function fail(message) { throw new Error(message); }
const settled = () => new Promise((r) => setTimeout(r, 0));

async function main() {
    // ── One connect per ring, however many times the ring is reported ──
    lk.prewarm('c1', 'wss://room', 'locked-token');
    lk.prewarm('c1', 'wss://room', 'locked-token');
    if (rooms.length !== 1) { fail('a second report of the same ring started a second connect'); }
    if (rooms[0].connects.length !== 1) { fail('the warm room did not connect once'); }
    if (rooms[0].connects[0].token !== 'locked-token') { fail('the warm room did not use the ring locked token'); }

    // ── A ring for ANOTHER call replaces the warm room ──
    lk.prewarm('c2', 'wss://room', 'locked-2');
    rooms[0].connects[0].settle.resolve();
    await settled();
    if (!rooms[0].disconnected) { fail("the first call's warm room was left connected"); }
    if (rooms.length !== 2) { fail('the second ring did not warm a room'); }

    // ── A connect that lands after its ring was dropped disconnects itself ──
    lk.dropPrewarm('c2');
    rooms[1].connects[0].settle.resolve();
    await settled();
    if (!rooms[1].disconnected) { fail('a late connect was left in the room as a ghost participant'); }

    // ── CLAIMED: a terminal event around the accept must not drop the room ──
    lk.prewarm('c3', 'wss://room', 'locked-3');
    const warm = rooms[2];
    warm.connects[0].settle.resolve();
    await settled();
    lk.claimPrewarm('c3');
    lk.dropPrewarm('c3');
    await settled();
    if (warm.disconnected) { fail('a drop after the accept disconnected the room the accept depends on'); }

    // ── ADOPTED: join takes that room, and never connects a second time ──
    await lk.join('wss://room', 'full-token', 'u1#dev_1', 'Vivek', false, '', 'c3');
    if (rooms.length !== 3) { fail('join built a new room instead of adopting the warm one'); }
    if (warm.connects.length !== 1) { fail('join connected the adopted room a second time'); }

    // ── A drop naming another call leaves this one alone ──
    lk.prewarm('c4', 'wss://room', 'locked-4');
    const other = rooms[rooms.length - 1];
    lk.dropPrewarm('c-something-else');
    other.connects[0].settle.resolve();
    await settled();
    if (other.disconnected) { fail("a terminal event for another call dropped this call's warm room"); }
    lk.dropPrewarm('c4');
    await settled();

    // ── prewarmMedia: opened once, and PUBLISHED by the join, not re-opened ──
    // An ordinary join from here, so its connect resolves like a real one.
    holdConnects = false;
    await lk.leave();
    tracks.length = 0;
    await lk.prewarmMedia(true, true);
    if (tracks.length !== 2) { fail('prewarmMedia did not open the microphone and the camera'); }
    await lk.prewarmMedia(true, true);
    if (tracks.length !== 2) { fail('prewarmMedia opened the devices twice'); }
    await lk.join('wss://room', 'full-token', 'u1#dev_1', 'Vivek', true, '', 'c9');
    const room = rooms[rooms.length - 1];
    if (room.localParticipant.published.length !== 2) {
        fail('the join did not publish the tracks prewarmMedia had already opened');
    }
    if (tracks.some((t) => t.stopped)) { fail('a track the join published was also stopped'); }

    // ── A camera the caller had switched off is STOPPED, never published ──
    await lk.leave();
    tracks.length = 0;
    await lk.prewarmMedia(true, true);
    await lk.join('wss://room', 'full-token', 'u1#dev_1', 'Vivek', false, '', 'c10');
    const audioOnly = rooms[rooms.length - 1];
    if (audioOnly.localParticipant.published.length !== 1) {
        fail('a pre-muted camera reached the wire: published ' + audioOnly.localParticipant.published.length);
    }
    if (!tracks.find((t) => t.kind === 'video').stopped) { fail('the unpublished camera was left running');

    }
    await lk.leave();

    console.log('livekit.js drove ' + context.sent.length + ' messages without throwing');
}

main().catch((e) => {
    console.error(e && e.stack ? e.stack : e);
    process.exit(1);
});
