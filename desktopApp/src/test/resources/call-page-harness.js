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
        set innerHTML(_) {},
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
        addEventListener() {},
        removeEventListener() {},
        setAttribute() {},
        getBoundingClientRect: () => ({ width: 1280, height: 720, top: 0, left: 0 }),
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
        { uid: 0, name: 'Vivek Mishra', self: true, hue: '#5f6368', muted: false, known: true, ringing: false, hand: false, peerId: 'me' },
        { uid: 7, name: 'Samsung Device', self: false, hue: '#8a5f68', muted: false, known: true, ringing: false, hand: false, peerId: 'them' },
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

process.stdout.write('call.js drove ' + context.sent.length + ' messages without throwing\n');
