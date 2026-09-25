// Runs callengine/call.js against a stub DOM and drives its render path.
//
// A syntax check is not enough and this file is the proof: `render()` once
// reached `mountTracks()` -> `syncLocalPreview()` -> `clearLocal()` ->
// `render()`, which parses perfectly and blows the stack on any call whose
// camera is off. Nothing in the Kotlin test suite executes this file, so the
// only thing that catches a fault like that is running it.
//
// Deliberately a stub rather than jsdom: the page needs a handful of DOM
// methods and no layout, and a real DOM would be a dependency the build does
// not otherwise carry.
//
//   node call-page-harness.js <path to call.js>
//
// Exits non-zero, with the failure on stderr, when the page throws.

const fs = require('fs');
const vm = require('vm');

const source = fs.readFileSync(process.argv[2], 'utf8');

function element(tag) {
    const node = {
        tagName: tag,
        style: {},
        className: '',
        children: [],
        parentNode: null,
        dataset: {},
        classList: { add() {}, remove() {}, toggle() {}, contains: () => false },
        // render() empties the stage this way; the pin checks count what is left.
        set innerHTML(value) { if (value === '') { node.children = []; } },
        get innerHTML() { return ''; },
        set textContent(_) {},
        get textContent() { return ''; },
        appendChild(child) { child.parentNode = node; node.children.push(child); return child; },
        removeChild(child) {
            node.children = node.children.filter(c => c !== child);
            child.parentNode = null;
            return child;
        },
        remove() { if (node.parentNode) { node.parentNode.removeChild(node); } },
        // Kept, so a test can press what the page built.
        listeners: {},
        addEventListener(type, fn) { node.listeners[type] = fn; },
        removeEventListener() {},
        setAttribute() {},
        getBoundingClientRect: () => ({ width: 1280, height: 720, top: 0, left: 0 }),
        clientWidth: 1280,
        clientHeight: 720,
        // A <video> is asked to play() the moment it is built.
        play: () => Promise.resolve(),
        querySelector: () => null,
        querySelectorAll: () => [],
    };
    return node;
}

const stage = element('div');
const document = {
    documentElement: { style: { setProperty() {} }, classList: { add() {}, remove() {} } },
    body: element('body'),
    createElement: element,
    getElementById: () => stage,
    querySelector: () => stage,
    querySelectorAll: () => [],
    addEventListener() {},
};

const context = {
    document,
    console,
    setTimeout,
    clearTimeout,
    setInterval: () => 0,
    clearInterval: () => {},
    navigator: { mediaDevices: { enumerateDevices: async () => [] } },
    AgoraRTC: { VERSION: 'stub', setLogLevel() {} },
    // The page reports through this; collected so a test can assert on it.
    sent: [],
};
context.window = context;
context.globalThis = context;
context.window.cefQuery = ({ request }) => { context.sent.push(request); };
context.window.addEventListener = () => {};
context.window.matchMedia = () => ({ matches: false, addEventListener() {} });

vm.createContext(context);
vm.runInContext(source, context, { filename: 'call.js' });

const api = context.window.zillitCall;
if (!api) { throw new Error('call.js did not install window.zillitCall'); }

// The exact shape Kotlin pushes, with the self tile first as CallTiles builds it.
const model = JSON.stringify({
    cols: 2,
    tiles: [
        { uid: 0, name: 'Vivek Mishra', self: true, hue: '#5f6368', muted: false, known: true, ringing: false, hand: false, peerId: 'me', key: 'self' },
        { uid: 7, name: 'Samsung Device', self: false, hue: '#8a5f68', muted: false, known: true, ringing: false, hand: false, peerId: 'them', key: 'them' },
    ],
});

// Each of these once drove, or could drive, a re-render. A recursion or a
// missing symbol surfaces here as a throw rather than as a frozen call.
api.setStage(model);
api.setStage(model);
api.setTheme(JSON.stringify({ bg: '#101114', tile: '#1b1d22' }));
api.setCompact(true);
api.setCompact(false);
api.setAvatar('me', 'data:image/png;base64,AAAA');
api.setAvatar('them', '');
api.setCam(false);
api.setCam(true);
api.setMic(true);
api.showReaction(JSON.stringify({ key: 'r1', emoji: '🎉', name: 'Vivek' }));
api.setStage(model);

// A peer's camera, then their shared screen into the same tile, then both
// leaving: the share re-lays the stage out (the presenter takes the big
// slot) and its end hands the tile back to the camera.
api.attachRemote('lk:PA:camera', 'them', 'video', {}, false);
api.attachRemote('lk:PA:screen_share', 'them', 'video', {}, true);
api.setStage(model);
api.detachRemote('lk:PA:screen_share');
api.detachRemote('lk:PA:camera');

// A pin: the stage laid out around a pinned tile, and the tile's own pin
// asking Kotlin (the page never decides) — for the other person and for us.
const pinnedModel = JSON.stringify(Object.assign(JSON.parse(model), { pins: ['them'] }));
api.setStage(pinnedModel);
function find(node, test) {
    if (test(node)) { return node; }
    for (const child of node.children || []) {
        const hit = find(child, test);
        if (hit) { return hit; }
    }
    return null;
}
const pins = [];
(function collect(node) {
    if (node.className && /\bpin\b/.test(node.className)) { pins.push(node); }
    (node.children || []).forEach(collect);
})(stage);
if (pins.length !== 2) { throw new Error('expected a pin on both tiles, found ' + pins.length); }
if (!find(stage, (n) => n.className === 'pin on')) { throw new Error('the pinned tile does not show its pin'); }
if (!find(stage, (n) => n.className === 'focus')) { throw new Error('a pin did not lay out the focus stage'); }
let stopped = false;
find(stage, (n) => n.className === 'pin on').listeners.click({ stopPropagation() { stopped = true; } });
if (!context.sent.some((m) => m === JSON.stringify({ type: 'pin', key: 'them' }))) {
    throw new Error('pressing the pin did not ask Kotlin: ' + context.sent.slice(-3).join('\n'));
}
if (!stopped) { throw new Error('the pin press would also swap the strip tile'); }
// Compact: no pins drawn in the pill's thumbnail.
api.setCompact(true);
if (find(stage, (n) => /\bpin\b/.test(n.className || ''))) { throw new Error('the thumbnail drew a pin'); }
api.setCompact(false);
api.setStage(model);

// attachRemote and detachRemote swallow their own errors into a warning, so
// a throw inside them would otherwise pass this run silently.
const swallowed = context.sent.filter((m) => /"where":"(attachRemote|detachRemote)"/.test(m));
if (swallowed.length) { throw new Error('the page warned while mounting media: ' + swallowed.join('\n')); }

// The 2026-09-24 Line 3 report: a Line 1 call's remote video outlived the
// call, and in the next call with the same person that dead track filled their
// tile while the live camera was never mounted.
async function staleVideoAcrossCalls() {
    const dead = { getVideoTracks: () => [{ readyState: 'ended' }] };
    const live = { getVideoTracks: () => [{ readyState: 'live' }] };
    const shownIn = () => {
        const video = find(stage, (n) => n.tagName === 'video');
        return video ? video.srcObject : null;
    };
    api.setStage(model);

    // Even while the dead one is still held, the live camera wins the tile.
    api.attachRemote('ms:old', 'them:device_1', 'video', dead, false);
    api.attachRemote('lk:PB:camera', 'them', 'video', live, false);
    if (shownIn() !== live) { throw new Error('a dead track kept the tile over the live camera'); }
    api.detachRemote('lk:PB:camera');

    // And the end of a call lets go of every remote track it held.
    await api.leave();
    api.setStage(model);
    if (shownIn() !== null) { throw new Error('the last call\'s video is still in a tile after leave()'); }
    api.attachRemote('lk:PC:camera', 'them', 'video', live, false);
    if (shownIn() !== live) { throw new Error('the next call\'s camera was not mounted'); }
}

staleVideoAcrossCalls().then(() => {
    process.stdout.write('call.js drove ' + context.sent.length + ' messages without throwing\n');
}).catch((e) => {
    process.stderr.write(String(e && e.stack || e) + '\n');
    process.exit(1);
});
