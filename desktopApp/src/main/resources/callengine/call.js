/*
 * The Kotlin ⇄ Agora bridge.
 *
 * Kotlin calls the functions on `zillitCall`; everything the SDK reports goes
 * back as one-line JSON through `window.cefQuery`, which KCEF's message
 * router delivers to KcefCallEngine. The event names here are the contract —
 * EngineBridge.kt parses exactly these shapes, and its tests pin them.
 *
 * Mirrors the web client's usage of agora-rtc-sdk-ng 4.x (mode "rtc", vp8),
 * which is itself mirrored on Android's RtcEngine configuration.
 */
(function () {
    'use strict';

    let client = null;
    let micTrack = null;
    let camTrack = null;
    /*
     * The stage model Kotlin pushes down: identity and shape only.
     * Liveness — speaking, video, mute — is applied here, because the SDK
     * tells this page a frame before Kotlin could relay it.
     */
    let stage = { cols: 1, tiles: [] };
    let compact = false;
    const videoTracks = new Map(); // uid -> RemoteVideoTrack
    const remoteAudio = new Map(); // uid -> RemoteAudioTrack, for output routing
    /*
     * The chosen input/output, kept here because they outlive any one track:
     * a microphone chosen mid-call must survive the next join, and an output
     * device has to be re-applied to every remote track that arrives after
     * the choice was made.
     */
    let chosenMic = '';
    let chosenSpeaker = '';
    let screenTrack = null;
    const speaking = new Set();    // uid
    const cells = new Map();       // uid -> { root, mount }

    function send(event) {
        try {
            window.cefQuery({ request: JSON.stringify(event) });
        } catch (e) {
            // No router (page opened in a plain browser) — nothing to do.
        }
    }

    function describe(context, error) {
        return context + ': ' + (error && error.message ? error.message : String(error));
    }

    /** Fatal: the call cannot carry media. Kotlin ends the call on these. */
    function fail(context, error) {
        send({ type: 'error', message: describe(context, error) });
    }

    /**
     * Degraded but alive: a missing camera must not end an audio call.
     *
     * The step is sent as its own field as well as being folded into the
     * message. Kotlin branches on `where` to decide which warnings are worth
     * telling the user about, and reading that out of prose would be fragile —
     * while sending no `where` at all, as this used to, meant every Agora
     * warning was unattributable and a failed screen share said nothing.
     */
    function warn(context, error) {
        send({ type: 'warning', where: context, message: describe(context, error) });
    }

    const MUTE_SVG =
        '<svg viewBox="0 0 24 24"><path d="M3 3l18 18-1.4 1.4L3 4.4 4.4 3 3 3zm9 12a3 3 0 0 0 3-3V6a3 3 0 0 0-6 0v.9l6 6V12a3 3 0 0 1-3 3zm-7-3a7 7 0 0 0 10.6 6l-1.5-1.5A5 5 0 0 1 7 12H5z"/></svg>';

    /**
     * Profile pictures, by user id, as data URIs.
     *
     * Pushed from Kotlin rather than fetched here: the pictures live behind
     * signed storage URLs the app already knows how to fetch, and the page has
     * neither the credentials nor any business holding them. Empty until they
     * arrive, and a tile with no entry keeps its initials — which is what
     * every tile did before, and is still the answer for someone whose
     * picture has not loaded or who has none.
     */
    const avatars = new Map();

    function initials(name) {
        const parts = String(name || '').trim().split(/\s+/).filter(Boolean).slice(0, 2);
        return parts.length ? parts.map(p => p[0].toUpperCase()).join('') : '?';
    }

    /**
     * What fills a tile's disc: their picture, or their initials.
     *
     * The picture is what the rest of the app shows for a person — an audio
     * call's grid draws it — so a video call whose camera is off should not
     * fall back to something plainer than the audio call it just was.
     */
    function face(model) {
        const uri = avatars.get(model.peerId);
        if (!uri) { return initials(model.name); }
        // The alt text is deliberately empty: the name is already on the
        // tile's chip, and a broken picture should leave the disc plain
        // rather than printing the name twice.
        return '<img src="' + uri + '" alt="">';
    }

    /**
     * Lays the stage out from the pushed model.
     *
     * Rebuilt wholesale on each push because the model changes only when
     * somebody joins or leaves — a handful of times per call, never per frame.
     * Video elements are re-mounted into their new cells rather than recreated,
     * so a re-layout does not blink every stream on the stage.
     */
    function render() {
        const root = document.getElementById('stage');
        if (!root) { return; }
        root.innerHTML = '';
        cells.clear();

        const list = stage.tiles || [];
        if (!list.length) { return; }
        const cols = Math.max(1, stage.cols || 1);
        const rows = Math.ceil(list.length / cols);

        // The largest 16:9 box that fits, then centred — the same arithmetic
        // CallGrid.kt does, so the Compose stage and this one match.
        const gap = 8;
        const cellW = (root.clientWidth - gap * (cols - 1)) / cols;
        const cellH = (root.clientHeight - gap * (rows - 1)) / rows;
        const tileW = Math.max(1, Math.min(cellW, cellH * (16 / 9), 560));
        const tileH = tileW / (16 / 9);
        const disc = Math.max(28, Math.min(tileH * 0.42, 96));

        for (let r = 0; r < rows; r++) {
            const row = document.createElement('div');
            row.className = 'row';
            for (let c = 0; c < cols; c++) {
                const model = list[r * cols + c];
                if (!model) { break; }
                row.appendChild(buildCell(model, tileW, tileH, disc));
            }
            root.appendChild(row);
        }
        // The old elements are gone, so nothing is playing where it was.
        playingIn.clear();
        mountTracks();
        paint();
    }

    function buildCell(model, tileW, tileH, disc) {
        const tile = document.createElement('div');
        tile.className = 'tile' + (model.ringing ? ' idle' : '');
        tile.style.width = tileW + 'px';
        tile.style.height = tileH + 'px';

        const mount = document.createElement('div');
        mount.className = 'face';
        const ringSize = disc + 8;
        mount.innerHTML =
            '<div class="ring halo" style="width:' + ringSize + 'px;height:' + ringSize + 'px"></div>' +
            '<div class="ring" style="width:' + ringSize + 'px;height:' + ringSize + 'px"></div>' +
            '<div class="disc" style="width:' + disc + 'px;height:' + disc + 'px;background:' +
            (model.hue || '#5f6368') + ';font-size:' + Math.round(disc / 2.6) + 'px">' +
            face(model) + '</div>';
        tile.appendChild(mount);

        const chip = document.createElement('div');
        chip.className = 'chip';
        chip.textContent = model.ringing ? 'Ringing…' : (model.self ? 'You' : model.name);
        tile.appendChild(chip);

        const mute = document.createElement('div');
        mute.className = 'mute';
        mute.innerHTML = MUTE_SVG;
        tile.appendChild(mute);

        // A raised hand, in the tile's corner. The banner names people; this
        // marks the face, which is what a busy grid is scanned by.
        if (model.hand) {
            const hand = document.createElement('div');
            hand.className = 'hand';
            hand.textContent = '✋';
            tile.appendChild(hand);
        }

        cells.set(model.uid, { root: tile, mount: mount, model: model });
        return tile;
    }

    /** The self tile — our preview belongs in the grid, not in a corner box. */
    function selfCell() {
        for (const cell of cells.values()) {
            if (cell.model && cell.model.self) { return cell; }
        }
        return null;
    }

    /**
     * Shows [track] in the self tile.
     *
     * Keyed on the cell AND the track. The cell alone is not enough: starting
     * a share swaps the camera for the screen inside the same mount, so a
     * cell-only guard decided the slot was already up to date and the sharer
     * went on watching their own camera for the whole share. The guard itself
     * has to stay — see `playingIn` — it just has to notice both things that
     * can change.
     */
    function playLocal(track) {
        const cell = selfCell();
        if (!cell || !track) { return; }
        if (playingIn.get(SELF) === cell.mount && playingTrack.get(SELF) === track) { return; }
        track.play(cell.mount);
        playingIn.set(SELF, cell.mount);
        playingTrack.set(SELF, track);
    }

    /** Whatever this device is publishing right now, or null for neither. */
    function localPreviewTrack() {
        if (screenTrack) { return screenTrack; }
        return camTrack && desiredCamEnabled ? camTrack : null;
    }

    /**
     * Re-derives the self tile from what is actually being published.
     *
     * Called wherever that can change, rather than each of those places
     * deciding for itself: `render()` clears the played-in map wholesale, so
     * any stage push — someone muting, someone joining — used to drop a live
     * screen preview back to the initials disc, because the only thing that
     * re-mounted the local slot knew about the camera and nothing else.
     */
    function syncLocalPreview() {
        const track = localPreviewTrack();
        if (track) { playLocal(track); } else { forgetLocal(); }
    }

    /**
     * Same, for callers OUTSIDE the render pass.
     *
     * The difference is the repaint, and it is the whole reason these are two
     * functions: [syncLocalPreview] is called from `mountTracks`, which
     * `render` calls, so re-rendering from there recurses until the stack
     * gives out — `RangeError: Maximum call stack size exceeded`, on every
     * call with the camera off, which is most of them. Event handlers are not
     * inside a render and do need the disc painted back.
     */
    function refreshLocalPreview() {
        const track = localPreviewTrack();
        if (track) { playLocal(track); return; }
        // Only when something was actually torn down: an unconditional render
        // here would repaint the whole stage on every camera toggle.
        if (forgetLocal()) { render(); }
    }

    /** Drops the local slot. True when something was playing in it. */
    function forgetLocal() {
        const had = playingIn.has(SELF);
        playingIn.delete(SELF);
        playingTrack.delete(SELF);
        return had;
    }

    /** Puts the initials disc back where the stopped preview was. */
    function clearLocal() {
        forgetLocal();
        render();
    }

    /*
     * Where each track is currently playing.
     *
     * play() is NOT idempotent: calling it again on a track that is already
     * playing tears the old <video> down and starts over, and the browser
     * reports the interruption as "AbortError: The play() request was
     * interrupted by a new load request". Doing that on every volume tick —
     * several times a second — means no frame ever survives long enough to be
     * seen, which looks exactly like a call with no video at all.
     */
    const playingIn = new Map(); // uid -> element

    /** What is playing there, so a swap inside one cell is not mistaken for a no-op. */
    const playingTrack = new Map(); // uid -> track

    /** Our own preview shares the mechanism; SELF is its key in `playingIn`. */
    const SELF = 'self';

    /** Puts every arrived track into its cell, and only when it is not already there. */
    function mountTracks() {
        cells.forEach((cell, uid) => {
            const track = videoTracks.get(uid);
            if (!track) { return; }
            if (playingIn.get(uid) === cell.mount) { return; }
            track.play(cell.mount);
            playingIn.set(uid, cell.mount);
        });
        // Re-derived rather than re-mounted from the camera: after a
        // re-layout the local slot has to come back as whatever is being
        // published, which during a share is the screen.
        syncLocalPreview();
        line1Mount();
    }

    /** The live half: speaking and mute. Class toggles only — never play(). */
    function paint() {
        cells.forEach((cell, uid) => {
            cell.root.classList.toggle('speaking', speaking.has(uid) && !cell.model.ringing);
            cell.root.classList.toggle('muted', cell.model.muted === true);
        });
    }

    /*
     * Media-path breadcrumbs.
     *
     * The page's console is captured into the app log, and audio has no visible
     * symptom to reason backwards from — a call with no sound looks identical
     * whether nothing was published, nothing was subscribed, or the track
     * arrived and never played. These three lines say which. Uids and track
     * kinds only; the channel and token never appear.
     */
    function trace(message) {
        try { console.log('[zillit-media] ' + message); } catch (e) { /* no console */ }
    }

    /**
     * Output routing is per remote track in the Web SDK — it is `setSinkId`
     * underneath — so there is no global switch to flip. Every track that
     * arrives gets the current choice, and changing the choice walks the ones
     * already playing. Browsers without setSinkId throw NOT_SUPPORTED, which
     * is a warning rather than a failure: the OS default still plays.
     */
    function applySpeaker(track) {
        if (!chosenSpeaker || !track || !track.setPlaybackDevice) { return; }
        track.setPlaybackDevice(chosenSpeaker).catch(e => warn('setPlaybackDevice', e));
    }

    /** The three lists Kotlin draws its pickers from, plus what is chosen now. */
    async function reportDevices() {
        try {
            const [mics, speakers, cams] = await Promise.all([
                AgoraRTC.getMicrophones().catch(() => []),
                AgoraRTC.getPlaybackDevices().catch(() => []),
                AgoraRTC.getCameras().catch(() => []),
            ]);
            const shape = list => list.map(d => ({ id: d.deviceId, label: d.label || '' }));
            send({
                type: 'devices',
                microphones: shape(mics),
                speakers: shape(speakers),
                cameras: shape(cams),
                microphoneId: chosenMic,
                speakerId: chosenSpeaker,
            });
        } catch (e) {
            warn('devices', e);
        }
    }

    // Hot-plugging a headset mid-call is the normal case on a desktop, not an
    // edge case: the SDK tells us, and the picker refreshes itself.
    AgoraRTC.onMicrophoneChanged = () => { reportDevices(); };
    AgoraRTC.onPlaybackDeviceChanged = () => { reportDevices(); };

    function wireClientEvents() {
        client.on('user-published', async (user, mediaType) => {
            try {
                await client.subscribe(user, mediaType);
                trace('subscribed ' + mediaType + ' uid=' + user.uid);
                if (mediaType === 'audio') {
                    if (user.audioTrack) {
                        remoteAudio.set(user.uid, user.audioTrack);
                        applySpeaker(user.audioTrack);
                        user.audioTrack.play();
                        trace('playing remote audio uid=' + user.uid);
                        // A voice arriving mid-recording joins the mix.
                        if (user.audioTrack.getMediaStreamTrack) {
                            recorderAdd(user.audioTrack.getMediaStreamTrack());
                        }
                    } else {
                        trace('NO audioTrack after subscribe uid=' + user.uid);
                    }
                    send({ type: 'peer-audio', uid: user.uid, muted: false });
                } else if (mediaType === 'video') {
                    if (user.videoTrack) {
                        videoTracks.set(user.uid, user.videoTrack);
                        mountTracks();
                    }
                    send({ type: 'peer-video', uid: user.uid, muted: false });
                }
            } catch (e) {
                warn('subscribe', e);
            }
        });

        client.on('user-unpublished', (user, mediaType) => {
            if (mediaType === 'audio') {
                remoteAudio.delete(user.uid);
                send({ type: 'peer-audio', uid: user.uid, muted: true });
            } else if (mediaType === 'video') {
                videoTracks.delete(user.uid);
                playingIn.delete(user.uid);
                render();
                send({ type: 'peer-video', uid: user.uid, muted: true });
            }
        });

        client.on('user-joined', (user) => {
            send({ type: 'peer-joined', uid: user.uid });
        });

        client.on('user-left', (user, reason) => {
            videoTracks.delete(user.uid);
            playingIn.delete(user.uid);
            speaking.delete(user.uid);
            render();
            send({ type: 'peer-left', uid: user.uid, reason: String(reason || '') });
        });

        client.on('connection-state-change', (cur, prev, reason) => {
            send({ type: 'connection', state: cur, reason: String(reason || '') });
        });

        client.on('network-quality', (q) => {
            // Self only: uid 0 by the engine's convention.
            send({ type: 'network', uid: 0, tx: q.uplinkNetworkQuality, rx: q.downlinkNetworkQuality });
        });

        client.on('volume-indicator', (volumes) => {
            const loud = volumes.filter(v => v.level > SPEAKING_LEVEL).map(v => v.uid);
            speaking.clear();
            loud.forEach(uid => speaking.add(uid));
            // Painted here rather than waiting for the round trip through
            // Kotlin: the ring is the one piece of chrome where a frame's
            // delay is visible as lag against the voice.
            paint();
            send({ type: 'speakers', uids: loud });
        });

        // Nothing can be done about this one: the backend mints an RTC token
        // at call creation and offers no renewal route, so a warning is all
        // there is. Reported so the call can end deliberately rather than go
        // silent when the grace period runs out.
        client.on('token-privilege-did-expire', () => {
            send({ type: 'token-expired' });
        });
        client.on('token-privilege-will-expire', () => {
            send({ type: 'token-expiring' });
        });
    }

    const SPEAKING_LEVEL = 10;

    /*
     * A join is four awaits long, and Kotlin can call leave() at any of them —
     * hanging up while the channel is still being joined is an ordinary thing
     * to do. A cancelled join must undo only what it itself created, so each
     * attempt carries a generation and checks it has not been superseded.
     */
    let joinGeneration = 0;

    /* What the user asked for while the tracks did not yet exist. */
    let desiredMicMuted = false;
    let desiredCamEnabled = true;

    /** Re-applies the user's choices to tracks that were created after them. */
    function applyDesiredState(mic, cam) {
        if (mic) { mic.setEnabled(!desiredMicMuted).catch(e => warn('mic', e)); }
        if (cam) {
            cam.setEnabled(desiredCamEnabled).catch(e => warn('camera', e));
            if (desiredCamEnabled) { playLocal(cam); } else { clearLocal(); }
        }
    }

    /** Undoes a join that was cancelled or failed part-way. Closes only its own tracks. */
    async function abandon(c, mic, cam) {
        try {
            if (mic) { mic.close(); if (micTrack === mic) { micTrack = null; } }
            if (cam) { cam.close(); if (camTrack === cam) { camTrack = null; } }
            if (!camTrack) { clearLocal(); }
            if (client === c) { client = null; }
            if (c) { await c.leave(); }
        } catch (e) {
            warn('abandon', e);
        }
    }

    /*
     * The container the recorder writes.
     *
     * MP4/AAC first, because a finished recording is not only saved here — it
     * is posted into the call's chat, and the players that must open it are
     * the phones'. iOS cannot decode WebM/Opus at all, so a WebM recording
     * arrives there as an audio message nobody can play. Chromium only muxes
     * MP4 where the platform hands it an AAC encoder, which is why the choice
     * is probed rather than assumed; WebM stays as the fallback that at least
     * plays here and on the web.
     */
    const RECORDER_TYPES = [
        { mime: 'audio/mp4;codecs=mp4a.40.2', ext: 'm4a' },
        { mime: 'audio/mp4', ext: 'm4a' },
        { mime: 'audio/webm;codecs=opus', ext: 'webm' },
        { mime: 'audio/webm', ext: 'webm' }
    ];

    /** The first container this build can actually write. */
    function recorderType() {
        for (let i = 0; i < RECORDER_TYPES.length; i += 1) {
            try {
                if (MediaRecorder.isTypeSupported(RECORDER_TYPES[i].mime)) { return RECORDER_TYPES[i]; }
            } catch (e) {
                // Some builds throw on an unknown type instead of answering false.
            }
        }
        return RECORDER_TYPES[RECORDER_TYPES.length - 1];
    }

    /*
     * The call recorder.
     *
     * Audio only, mixed here because the page is the one place every voice on
     * either line actually flows through: the local microphone and each remote
     * track feed one AudioContext destination, and a MediaRecorder writes the
     * mix. Tracks that arrive mid-recording are added through recorderAdd from
     * the same handlers that start them playing. The finished file crosses the
     * bridge in base64 slices; Kotlin reassembles and saves it.
     */
    let recorder = null;
    let recorderCtx = null;
    let recorderDest = null;
    let recorderBlobs = [];
    let recorderChosen = RECORDER_TYPES[RECORDER_TYPES.length - 1];
    let recorderStartedAt = 0;

    function recorderAdd(streamOrTrack) {
        if (!recorderCtx || !recorderDest || !streamOrTrack) { return; }
        try {
            const stream = (typeof MediaStream !== 'undefined' && streamOrTrack instanceof MediaStream)
                ? streamOrTrack
                : new MediaStream([streamOrTrack]);
            if (!stream.getAudioTracks().length) { return; }
            recorderCtx.createMediaStreamSource(stream).connect(recorderDest);
        } catch (e) {
            warn('recorderAdd', e);
        }
    }

    function deliverRecording() {
        const chosen = recorderChosen;
        // Wall clock rather than the blob: the mixed stream has no timeline of
        // its own, and the chat bubble's clock is the only consumer.
        const millis = recorderStartedAt ? Math.max(0, Date.now() - recorderStartedAt) : 0;
        recorderStartedAt = 0;
        const blob = new Blob(recorderBlobs, { type: chosen.mime });
        recorderBlobs = [];
        recorder = null;
        if (recorderCtx) { try { recorderCtx.close(); } catch (e) { /* already closed */ } }
        recorderCtx = null;
        recorderDest = null;
        if (!blob.size) { return; }
        const reader = new FileReader();
        reader.onload = function () {
            const base64 = String(reader.result).split(',')[1] || '';
            const SLICE = 262144;
            for (let i = 0; i < base64.length; i += SLICE) {
                send({ type: 'recording-chunk', data: base64.slice(i, i + SLICE) });
            }
            send({ type: 'recording-done', mime: chosen.mime, ext: chosen.ext, duration: millis });
        };
        reader.readAsDataURL(blob);
    }

    /**
     * A video track for one chosen screen or window.
     *
     * `getDisplayMedia` cannot be told which source to take — that is what the
     * browser's own picker is for, and there isn't one here. Chromium's older
     * constraint form can, and this build still parses it, so the app's picker
     * names the source and the SDK is handed the finished track rather than
     * being asked to find one.
     */
    async function captureChosenSource(sourceId) {
        const stream = await navigator.mediaDevices.getUserMedia({
            video: {
                mandatory: {
                    chromeMediaSource: 'desktop',
                    chromeMediaSourceId: sourceId,
                    maxWidth: 1920,
                    maxHeight: 1080,
                },
            },
        });
        const track = stream.getVideoTracks()[0];
        if (!track) { throw new Error('the chosen source produced no video'); }
        return AgoraRTC.createCustomVideoTrack({ mediaStreamTrack: track });
    }

    /** Enough lanes that simultaneous reactions do not stack on one line. */
    const REACTION_LANES = 7;
    const REACTION_LANE_WIDTH = 26;
    const MAX_REACTIONS = 24;

    /** A stable horizontal offset for an id — same reaction, same path. */
    function hashLane(key) {
        let h = 0;
        for (let i = 0; i < key.length; i++) { h = (h * 31 + key.charCodeAt(i)) | 0; }
        const lane = Math.abs(h) % REACTION_LANES - Math.floor(REACTION_LANES / 2);
        return lane * REACTION_LANE_WIDTH;
    }

    /*
     * Line 1's media sink.
     *
     * Agora hands this page track objects with a `play(element)` of their own;
     * mediasoup hands it a bare MediaStream, which has to be bound to a real
     * media element or Chromium renders and plays nothing at all. There was no
     * such element path here, so every consumed track was being discarded at
     * this boundary and Line 1 calls were silent while reporting themselves
     * healthy.
     *
     * Audio gets a detached <audio autoplay>: it needs no layout, only a sink.
     * Video is bound into the tile the grid already draws for that peer — and
     * re-bound after every render(), because render() rebuilds each cell and a
     * video element left in a discarded node is a picture nobody sees.
     */
    const line1Media = new Map();   // consumerId -> { peerId, kind, stream, element }

    /** The local camera on Line 1, previewed in the self tile. */
    let line1Local = null;          // { stream, element } or null

    /**
     * The tile for one Line 1 peer id.
     *
     * Peer ids are `userId:deviceId` (with a rejoin suffix after a redial);
     * the stage model's `peerId` carries the USER id — the identity half is
     * what survives rejoins, so it is the only stable key.
     */
    function line1Tile(peerId) {
        const asText = String(peerId || '');
        const identity = asText.split(':')[0];
        for (const cell of cells.values()) {
            if (!cell.model) { continue; }
            // By identity first; by numeric uid as text second, so a caller
            // that hands over the hash rather than the identity still lands.
            if (cell.model.peerId === identity || cell.model.peerId === asText ||
                String(cell.model.uid) === asText) {
                return cell;
            }
        }
        return null;
    }

    function line1VideoElement(stream) {
        const video = document.createElement('video');
        video.autoplay = true;
        video.playsInline = true;
        video.muted = true;          // the audio arrives on its own consumer
        video.style.width = '100%';
        video.style.height = '100%';
        video.style.objectFit = 'cover';
        video.srcObject = stream;
        return video;
    }

    /** Puts every Line 1 video into its (possibly rebuilt) cell. */
    function line1Mount() {
        line1Media.forEach((entry) => {
            if (entry.kind !== 'video') { return; }
            const cell = line1Tile(entry.peerId);
            if (!cell) { return; }
            if (entry.element && entry.element.parentNode === cell.mount) { return; }
            if (!entry.element) { entry.element = line1VideoElement(entry.stream); }
            cell.mount.innerHTML = '';
            cell.mount.appendChild(entry.element);
        });
        if (line1Local) {
            const cell = selfCell();
            if (cell && (!line1Local.element || line1Local.element.parentNode !== cell.mount)) {
                if (!line1Local.element) { line1Local.element = line1VideoElement(line1Local.stream); }
                cell.mount.innerHTML = '';
                cell.mount.appendChild(line1Local.element);
            }
        }
    }

    window.zillitCall = {

        /** Binds one consumed remote track so it is actually heard or seen. */
        attachRemote(consumerId, peerId, kind, stream) {
            try {
                this.detachRemote(consumerId);
                if (kind === 'audio') {
                    const sink = document.createElement('audio');
                    sink.autoplay = true;
                    sink.srcObject = stream;
                    // Detached from the document on purpose: an audio element
                    // needs no layout, and appending it to the grid would take
                    // space from the picture.
                    line1Media.set(consumerId, { peerId: peerId, kind: kind, stream: stream, element: sink });
                    const played = sink.play();
                    if (played && played.catch) { played.catch(function () { /* autoplay policy */ }); }
                    recorderAdd(stream);
                    return;
                }
                line1Media.set(consumerId, { peerId: peerId, kind: kind, stream: stream, element: null });
                line1Mount();
            } catch (e) {
                warn('attachRemote', e);
            }
        },

        /** Releases one consumer's element so a closed track stops holding it. */
        detachRemote(consumerId) {
            const entry = line1Media.get(consumerId);
            if (!entry) { return; }
            line1Media.delete(consumerId);
            try {
                if (entry.element) {
                    entry.element.srcObject = null;
                    if (entry.element.parentNode) { entry.element.parentNode.removeChild(entry.element); }
                }
            } catch (e) {
                warn('detachRemote', e);
            }
        },

        /** Starts recording the call's mixed audio. No-op while one runs. */
        startRecording() {
            if (recorder) { return; }
            try {
                recorderCtx = new AudioContext();
                recorderDest = recorderCtx.createMediaStreamDestination();
                // Our own voice, whichever line carries it.
                if (micTrack && micTrack.getMediaStreamTrack) {
                    recorderAdd(micTrack.getMediaStreamTrack());
                }
                if (window.zillitMs && window.zillitMs.getLocalAudioTrack) {
                    recorderAdd(window.zillitMs.getLocalAudioTrack());
                }
                // Everyone already talking; later arrivals join via recorderAdd.
                remoteAudio.forEach(track => {
                    if (track.getMediaStreamTrack) { recorderAdd(track.getMediaStreamTrack()); }
                });
                line1Media.forEach(entry => {
                    if (entry.kind === 'audio') { recorderAdd(entry.stream); }
                });
                recorderBlobs = [];
                recorderChosen = recorderType();
                recorderStartedAt = Date.now();
                recorder = new MediaRecorder(recorderDest.stream, { mimeType: recorderChosen.mime });
                recorder.ondataavailable = e => {
                    if (e.data && e.data.size) { recorderBlobs.push(e.data); }
                };
                recorder.onstop = deliverRecording;
                recorder.start(1000);
            } catch (e) {
                recorder = null;
                if (recorderCtx) { try { recorderCtx.close(); } catch (e2) { /* already closed */ } }
                recorderCtx = null;
                recorderDest = null;
                warn('startRecording', e);
            }
        },

        /** Stops the recorder; the file is delivered from its onstop. */
        stopRecording() {
            if (!recorder) { return; }
            try { recorder.stop(); } catch (e) { warn('stopRecording', e); }
        },

        /** The local Line 1 camera, previewed in the self tile. */
        attachLocalPreview(stream) {
            // Released before it is replaced. Line 1 now re-points this at
            // every share start and stop, and an element left bound to a
            // stream keeps a sink alive on a track nobody is watching.
            const held = line1Local;
            if (held && held.element) {
                try {
                    held.element.srcObject = null;
                    if (held.element.parentNode) { held.element.parentNode.removeChild(held.element); }
                } catch (e) { warn('attachLocalPreview', e); }
            }
            line1Local = { stream: stream, element: null };
            line1Mount();
        },

        clearLocalPreview() {
            const held = line1Local;
            line1Local = null;
            if (held && held.element && held.element.parentNode) {
                held.element.srcObject = null;
                held.element.parentNode.removeChild(held.element);
            }
            render();
        },

        async join(appId, channel, token, uid, withVideo) {
            if (client) { await this.leave(); }
            const gen = ++joinGeneration;
            const stale = () => gen !== joinGeneration;
            let c = null, mic = null, cam = null;
            try {
                c = AgoraRTC.createClient({ mode: 'rtc', codec: 'vp8' });
                client = c;
                wireClientEvents();
                c.enableAudioVolumeIndicator();

                const joined = await c.join(appId, channel, token || null, uid || null);
                trace('joined uid=' + joined + ' (asked for ' + (uid || 'auto') + ')');
                if (stale()) { await abandon(c, mic, cam); return; }

                mic = await AgoraRTC.createMicrophoneAudioTrack(
                    chosenMic ? { microphoneId: chosenMic } : {},
                );
                if (stale()) { await abandon(c, mic, cam); return; }
                micTrack = mic;

                const tracks = [mic];
                if (withVideo) {
                    try {
                        cam = await AgoraRTC.createCameraVideoTrack();
                        camTrack = cam;
                        playLocal(cam);
                        tracks.push(cam);
                    } catch (e) {
                        // No camera is a downgraded call, not a failed one.
                        warn('camera', e);
                    }
                }
                if (stale()) { await abandon(c, mic, cam); return; }

                // Before publishing: a mute pressed during the join would
                // otherwise be overwritten by a freshly created, enabled mic.
                applyDesiredState(mic, cam);
                await c.publish(tracks);
                trace('published ' + tracks.map(t => t.trackMediaType || '?').join('+') +
                    ' as uid=' + joined);
                if (stale()) { await abandon(c, mic, cam); return; }
                send({ type: 'joined', channel: channel, uid: joined });
            } catch (e) {
                await abandon(c, mic, cam);
                fail('join', e);
            }
        },

        async leave() {
            // Retires any join still in flight, so it closes its own tracks
            // rather than finishing into a call that no longer exists.
            joinGeneration++;
            desiredMicMuted = false;
            desiredCamEnabled = true;
            const had = client;
            try {
                if (micTrack) { micTrack.close(); micTrack = null; }
                if (camTrack) { camTrack.close(); camTrack = null; }
                // The share too, and unconditionally — `client.leave()` does
                // not stop a capture, which is why the mic and camera are
                // closed by hand a line above. Leaving this open outlives the
                // call: the page is never rebuilt, so ScreenCaptureKit keeps
                // running with the macOS recording indicator lit, the next
                // `startScreenShare` silently no-ops on `screenTrack` still
                // being set, and the next call's self tile shows the last
                // call's screen. No unpublish — that throws once the client
                // is gone, and the server has forgotten us regardless.
                if (screenTrack) {
                    try { screenTrack.close(); } catch (e2) { /* already closed */ }
                    screenTrack = null;
                }
                clearLocal();
                videoTracks.clear();
                playingIn.clear();
                speaking.clear();
                render();  // playingIn is cleared again inside; harmless and explicit
                if (client) {
                    const c = client;
                    client = null;
                    await c.leave();
                }
            } catch (e) {
                warn('leave', e);
            }
            if (had) { send({ type: 'left' }); }
        },

        setMic(muted) {
            // Recorded even with no track: during a join the bar is already up
            // and a mute pressed here must not be lost, or the user watches a
            // muted button while the room hears them.
            desiredMicMuted = muted;
            if (micTrack) { micTrack.setEnabled(!muted).catch(e => warn('mic', e)); }
        },

        async setCam(enabled) {
            desiredCamEnabled = enabled;
            try {
                if (enabled && !camTrack && client) {
                    camTrack = await AgoraRTC.createCameraVideoTrack();
                    await client.publish(camTrack);
                } else if (camTrack) {
                    await camTrack.setEnabled(enabled);
                }
                // One rule for what the self tile shows, so turning the camera
                // on or off while sharing cannot steal the tile from the share.
                refreshLocalPreview();
            } catch (e) {
                warn('camera', e);
            }
        },

        async switchCamera() {
            try {
                if (!camTrack) { return; }
                const cams = await AgoraRTC.getCameras();
                if (cams.length < 2) { return; }
                const current = camTrack.getTrackLabel();
                const next = cams.find(c => c.label !== current) || cams[0];
                await camTrack.setDevice(next.deviceId);
            } catch (e) {
                warn('switchCamera', e);
            }
        },

        // Kept for interface parity — desktop has no earpiece/speaker duality,
        // and output is chosen by device below rather than toggled.
        setSpeaker(_enabled) {},

        /** Asks the page to publish the current device lists. */
        listDevices() { reportDevices(); },

        /**
         * Switches the live microphone, or records the choice for the next
         * join when no track exists yet. `setDevice` swaps the source under a
         * published track, so the room hears the new microphone without a
         * republish and without anyone dropping out.
         */
        async setMicrophoneDevice(deviceId) {
            chosenMic = deviceId || '';
            try {
                if (micTrack && chosenMic) { await micTrack.setDevice(chosenMic); }
            } catch (e) {
                warn('setMicrophoneDevice', e);
            }
            reportDevices();
        },

        /** Routes every remote voice — playing and future — to one output. */
        async setSpeakerDevice(deviceId) {
            chosenSpeaker = deviceId || '';
            remoteAudio.forEach(track => applySpeaker(track));
            reportDevices();
        },

        /**
         * Publishes the screen in place of the camera.
         *
         * One video track per client is the SDK's rule, so the camera is
         * unpublished first and restored on stop — the same trade the phones
         * make (they suppress camera-flip while sharing for this reason).
         *
         * [sourceId] is a Chromium DesktopMediaID the app's own picker chose —
         * `screen:<display>:0` or `window:<id>:0`. Embedded Chromium draws no
         * picker of its own, so without one the browser hands back the whole
         * desktop; with one, the capture is built here from the legacy
         * constraint form, which this build still honours and which is the
         * only way to name a specific source from inside the page.
         */
        async startScreenShare(sourceId) {
            if (!client || screenTrack) { return; }
            try {
                screenTrack = sourceId
                    ? await captureChosenSource(sourceId)
                    : await AgoraRTC.createScreenVideoTrack({}, 'disable');
                if (camTrack) { await client.unpublish(camTrack); }
                await client.publish(screenTrack);
                refreshLocalPreview();
                // The browser's own "Stop sharing" bar ends the track without
                // telling us; without this the UI would still claim to share.
                screenTrack.on('track-ended', () => { window.zillitCall.stopScreenShare(); });
                send({ type: 'screen-share', sharing: true });
            } catch (e) {
                // A cancelled picker is a decision, not a failure.
                warn('startScreenShare', e);
                // Close before dropping: the track may already own a live
                // capture — publish is the step most likely to have thrown,
                // and by then getUserMedia has succeeded. Letting it go
                // unclosed leaves ScreenCaptureKit running, and the macOS
                // recording indicator lit, on a share we just reported as
                // stopped.
                if (screenTrack) {
                    try { screenTrack.close(); } catch (e2) { /* already closed */ }
                }
                screenTrack = null;
                // The camera was unpublished a line before the publish that
                // failed, and nothing else republishes it: setCam only toggles
                // a track that is already published, and stopScreenShare
                // returns early with no screenTrack. Without this the peers
                // see a black tile for the rest of the call while this end
                // still shows the camera as on.
                try {
                    if (camTrack && desiredCamEnabled) {
                        await client.publish(camTrack);
                    }
                } catch (e3) {
                    warn('restoreCamera', e3);
                }
                refreshLocalPreview();
                send({ type: 'screen-share', sharing: false });
            }
        },

        async stopScreenShare() {
            if (!screenTrack) { return; }
            try {
                await client.unpublish(screenTrack);
                screenTrack.close();
            } catch (e) {
                warn('stopScreenShare', e);
            }
            screenTrack = null;
            try {
                if (camTrack && desiredCamEnabled) {
                    await client.publish(camTrack);
                }
            } catch (e) {
                warn('restoreCamera', e);
            }
            // Null already, so this lands on the camera or the initials disc.
            refreshLocalPreview();
            send({ type: 'screen-share', sharing: false });
        },

        /** Switches the camera by id, unlike [switchCamera]'s blind next-one. */
        async setCameraDevice(deviceId) {
            try {
                if (camTrack && deviceId) { await camTrack.setDevice(deviceId); }
            } catch (e) {
                warn('setCameraDevice', e);
            }
            reportDevices();
        },

        /** Identity and shape for the tiles. Liveness stays this page's job. */
        setStage(json) {
            try {
                stage = JSON.parse(json) || { cols: 1, tiles: [] };
            } catch (e) {
                warn('setStage', e);
                return;
            }
            render();
        },

        /**
         * One person's profile picture, as a data URI.
         *
         * Sent one at a time as the app finishes fetching each: a call can
         * start before any of them have arrived, and waiting for the slowest
         * would leave every tile plain until then.
         */
        setAvatar(peerId, dataUri) {
            if (!peerId) { return; }
            if (dataUri) { avatars.set(peerId, dataUri); } else { avatars.delete(peerId); }
            render();
        },

        /** The app's palette, so this surface is not a foreign slab. */
        setTheme(json) {
            try {
                const theme = JSON.parse(json) || {};
                const root = document.documentElement;
                const map = {
                    bg: '--bg', tile: '--tile', tileIdle: '--tile-idle', border: '--border',
                    text: '--text', muted: '--muted', speaking: '--speaking', danger: '--danger',
                };
                Object.keys(map).forEach(key => {
                    if (theme[key]) { root.style.setProperty(map[key], theme[key]); }
                });
            } catch (e) {
                warn('setTheme', e);
            }
        },

        /**
         * Floats one emoji up over the picture.
         *
         * Drawn here and not by the app, because this page owns every pixel
         * inside its rectangle: the surface is a heavyweight native component
         * and Compose layers over it are never painted. The app still draws
         * reactions itself on calls with no video, where there is no surface
         * to lose them behind.
         *
         * The element removes itself when its animation ends, so nothing
         * accumulates over a long call; the cap is a floor under that in case
         * animationend never fires (a backgrounded window can skip it).
         */
        showReaction(json) {
            try {
                const data = JSON.parse(json) || {};
                if (!data.emoji) { return; }
                const layer = document.getElementById('reactions');
                if (!layer) { return; }

                while (layer.childElementCount >= MAX_REACTIONS) {
                    layer.removeChild(layer.firstElementChild);
                }

                const node = document.createElement('div');
                node.className = 'reaction';
                // Spread across the middle of the picture, derived from the id
                // so the same reaction always takes the same path.
                const lane = hashLane(String(data.id || data.emoji));
                node.style.left = `calc(50% + ${lane}px)`;

                const face = document.createElement('div');
                face.className = 'face';
                face.textContent = data.emoji;
                node.appendChild(face);

                if (data.name) {
                    const who = document.createElement('div');
                    who.className = 'who';
                    who.textContent = data.name;
                    node.appendChild(who);
                }

                node.addEventListener('animationend', () => node.remove());
                layer.appendChild(node);
            } catch (e) {
                warn('showReaction', e);
            }
        },

        /** Shrunk into the minimised pill: one tile, no chrome. */
        setCompact(value) {
            compact = !!value;
            document.body.classList.toggle('compact', compact);
            render();
        },

        /**
         * Fake a call of `n` people, for checking the grid on one machine.
         *
         * Every layout past two participants is otherwise unreachable here —
         * a five-way call needs five devices. Open call.html in any browser
         * and run `zillitCall.demo(12)`.
         */
        demo(n) {
            const names = [
                'You', 'Vivek Mishra', 'Aisha Khan', 'Tom Okafor', 'Mei Lin', 'Raj Patel',
                'Sara Ahmed', 'Luca Rossi', 'Nina Petrova', 'Omar Haddad', 'Ella Novak', 'Ben Carter',
                'Priya Nair', 'Jonas Weber',
            ];
            const hues = ['#1a73e8', '#d93025', '#188038', '#e37400', '#9334e6', '#0b8043'];
            const count = Math.max(1, Math.min(n || 1, 14));
            stage = {
                cols: count <= 1 ? 1 : count <= 4 ? 2 : count <= 9 ? 3 : 4,
                tiles: Array.from({ length: count }, (_, i) => ({
                    uid: i,
                    name: names[i % names.length],
                    self: i === 0,
                    hue: hues[i % hues.length],
                    muted: i % 4 === 2,
                    known: true,
                    ringing: i === count - 1 && count > 2,
                })),
            };
            render();
            // Rotate the speaking ring so the animation is visible standing still.
            let turn = 0;
            clearInterval(window.__zillitDemo);
            window.__zillitDemo = setInterval(() => {
                speaking.clear();
                speaking.add(turn % count);
                turn++;
                paint();
            }, 1600);
        }
    };

    // A resize changes every tile's size, and the cells carry pixel sizes.
    window.addEventListener('resize', render);

    send({ type: 'ready', sdk: (window.AgoraRTC && AgoraRTC.VERSION) || 'missing' });
})();
