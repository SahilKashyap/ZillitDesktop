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

    /** Degraded but alive: a missing camera must not end an audio call. */
    function warn(context, error) {
        send({ type: 'warning', message: describe(context, error) });
    }

    const MUTE_SVG =
        '<svg viewBox="0 0 24 24"><path d="M3 3l18 18-1.4 1.4L3 4.4 4.4 3 3 3zm9 12a3 3 0 0 0 3-3V6a3 3 0 0 0-6 0v.9l6 6V12a3 3 0 0 1-3 3zm-7-3a7 7 0 0 0 10.6 6l-1.5-1.5A5 5 0 0 1 7 12H5z"/></svg>';

    function initials(name) {
        const parts = String(name || '').trim().split(/\s+/).filter(Boolean).slice(0, 2);
        return parts.length ? parts.map(p => p[0].toUpperCase()).join('') : '?';
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
            initials(model.name) + '</div>';
        tile.appendChild(mount);

        const chip = document.createElement('div');
        chip.className = 'chip';
        chip.textContent = model.ringing ? 'Ringing…' : (model.self ? 'You' : model.name);
        tile.appendChild(chip);

        const mute = document.createElement('div');
        mute.className = 'mute';
        mute.innerHTML = MUTE_SVG;
        tile.appendChild(mute);

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

    function playLocal(track) {
        const cell = selfCell();
        if (!cell || !track) { return; }
        if (playingIn.get(SELF) === cell.mount) { return; }
        track.play(cell.mount);
        playingIn.set(SELF, cell.mount);
    }

    /** Puts the initials disc back where the stopped preview was. */
    function clearLocal() {
        playingIn.delete(SELF);
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
        mountLocal();
    }

    /**
     * The local preview, into whichever cell is ours.
     *
     * Re-mounted after a re-layout for the same reason the remote tracks are:
     * render() replaces every element, so whatever was playing is playing into
     * a node that is no longer on the page.
     */
    function mountLocal() {
        const cell = selfCell();
        if (!cell || !camTrack || !desiredCamEnabled) { return; }
        if (playingIn.get(SELF) === cell.mount) { return; }
        camTrack.play(cell.mount);
        playingIn.set(SELF, cell.mount);
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

    window.zillitCall = {
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
                    playLocal(camTrack);
                    await client.publish(camTrack);
                } else if (camTrack) {
                    await camTrack.setEnabled(enabled);
                    if (enabled) { playLocal(camTrack); } else { clearLocal(); }
                }
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
         * Chromium picks the source itself: an embedded browser has nowhere
         * to draw the picker, so the runtime is launched with
         * --auto-select-desktop-capture-source. The whole screen is shared,
         * not a chosen window.
         */
        async startScreenShare() {
            if (!client || screenTrack) { return; }
            try {
                screenTrack = await AgoraRTC.createScreenVideoTrack({}, 'disable');
                if (camTrack) { await client.unpublish(camTrack); }
                await client.publish(screenTrack);
                playLocal(screenTrack);
                // The browser's own "Stop sharing" bar ends the track without
                // telling us; without this the UI would still claim to share.
                screenTrack.on('track-ended', () => { window.zillitCall.stopScreenShare(); });
                send({ type: 'screen-share', sharing: true });
            } catch (e) {
                // A cancelled picker is a decision, not a failure.
                warn('startScreenShare', e);
                screenTrack = null;
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
                    playLocal(camTrack);
                } else {
                    clearLocal();
                }
            } catch (e) {
                warn('restoreCamera', e);
            }
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
