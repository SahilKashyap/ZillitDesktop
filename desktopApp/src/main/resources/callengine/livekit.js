/*
 * Line 3's media half — LiveKit, in the same page as Agora and mediasoup.
 *
 * The room is joined here because WebRTC lives in the page; signalling for
 * the call (ring, accept, hang up) is Kotlin's, over the calling backend's
 * presence socket, and never touches this file. What crosses the bridge:
 *
 *   Kotlin -> page   window.zillitLk.*      commands (join, leave, setMic, …)
 *   page -> Kotlin   window.cefQuery(...)   events, in EngineBridge's vocabulary
 *
 * The events are deliberately the ones Agora's half already sends —
 * `joined`, `peer-joined`, `peer-left`, `peer-audio`, `peer-video`,
 * `speakers`, `connection`, `screen-share`, `left` — so the Kotlin side
 * needs no third dialect. Peers are numbered the way Line 1 numbers them:
 * a stable hash of the participant's identity (see `uidOf`, which must
 * agree with `mediasoupUidOf` in Kotlin), so a roster row and a media peer
 * land on the same tile.
 *
 * Remote media is rendered through the page's own sink (`zillitCall.attachRemote`),
 * so tiles, names and speaking rings are the same chrome on every line — but a
 * remote VIDEO is handed over with its LiveKit track, which the page attaches
 * with `track.attach` so adaptiveStream can see the tile it plays in (below).
 */
(function () {
    'use strict';

    var LK = window.LivekitClient;

    var room = null;
    var joinGeneration = 0;
    var identity = '';
    var desiredMic = true;
    var desiredCam = false;
    var chosenMic = '';
    var screenPub = null;
    /**
     * A share on its way up. `screenPub` is only set once the publish
     * resolves, so without this a second press during the capture/publish
     * (a double-click on Share, the picker's confirm) published a second
     * screen next to the first — five `published screen_share` lines for one
     * share in the 2026-09-23 log, and never a matching stop.
     */
    var shareStarting = false;
    /** People muted for me alone (bare user ids) — kept across a hold so resume does not unmute them. */
    var deafened = {};
    var hidden = {};
    var onHold = false;
    var mediaBeforeHold = { mic: true, cam: false };
    /** What the last join was given — replayed by a rejoin after the link died. */
    var creds = null;
    var rejoining = false;
    var netOffline = false;
    var reconnectPoll = null;

    /**
     * THE PRE-WARMED ROOM — the one connected on the ring's locked token while
     * the phone was still ringing (`prewarm`), waiting for the accept to adopt
     * it.
     *
     * The locked token joins hidden: the SERVER enforces no-publish and
     * no-subscribe, so nothing reaches the caller before the user answers. On
     * accept the SAME participant is upgraded in place, which is what turns
     * answering from a full connect into a permission grant. The rule it
     * serves is exactly one connect per call: connect(locked) → disconnect →
     * connect(full) is what collapses a call server-side.
     *
     * `{ callId, room, ready, gen }`. `gen` is bumped on every start and drop,
     * so a connect that lands late disconnects itself instead of lingering as
     * an invisible ghost participant in someone else's call.
     */
    var warm = null;
    var warmGen = 0;
    /**
     * The call the user has ACCEPTED, so `dropPrewarm` refuses to touch its
     * room: terminal events routinely arrive around an accept (the server tells
     * this user's other devices, and the answering one hears it too), and
     * disconnecting a pre-warmed participant mid-adoption reads to the server
     * as the callee LEAVING — ending the call they just answered.
     */
    var warmClaimed = '';
    /**
     * Tracks opened before there was a room to publish them to
     * (`prewarmMedia`). Published as-is at join: a second getUserMedia while
     * the first is still held fails outright on some devices, so these exact
     * tracks are the ones that go on the wire.
     */
    var prewarmed = null;
    /**
     * The `prewarmMedia` open still in flight. [join] WAITS on it rather than
     * opening its own devices beside it: the second getUserMedia above is not
     * only slow, it fails outright on some hardware, and on the accept path
     * the two overlap by design — the devices are opened at the tap and the
     * join follows one round trip later.
     */
    var prewarmPending = null;
    /**
     * Bumped when the tracks `prewarmMedia` opened stop being wanted — a call
     * that ended, a lobby closed, a drop. NOT by [join]: a join is the thing
     * those tracks were opened FOR, and retiring them on the join's own
     * generation is what left an answered call holding the microphone open
     * and publishing nothing (16 Oct, call 1c058f1e).
     */
    var mediaGen = 0;

    /*
     * A Line 1 MEET's lobby: the devices opened before there is any room,
     * the camera shown full-surface while the user decides, and these SAME
     * tracks published by join() through `prewarmed` — acquiring the
     * microphone after joining is what made meets slow to be heard.
     * `lobbyGen` retires an open that lands after the lobby closed.
     */
    var lobbyGen = 0;
    var lobbyVideo = null;

    function lobbyCameraTrack() {
        if (!prewarmed) { return null; }
        for (var i = 0; i < prewarmed.length; i++) {
            if (prewarmed[i].kind === (LK.Track && LK.Track.Kind ? LK.Track.Kind.Video : 'video')) { return prewarmed[i]; }
        }
        return null;
    }

    function showLobbyPreview() {
        var t = lobbyCameraTrack();
        if (!t) { hideLobbyPreview(); return; }
        if (!lobbyVideo) {
            lobbyVideo = document.createElement('video');
            lobbyVideo.autoplay = true;
            lobbyVideo.muted = true;
            lobbyVideo.setAttribute('playsinline', '');
            lobbyVideo.style.cssText = 'position:fixed;left:0;top:0;width:100%;height:100%;object-fit:cover;' +
                'transform:scaleX(-1);background:#3C4043;z-index:2147483000;';
            document.body.appendChild(lobbyVideo);
        }
        lobbyVideo.srcObject = new MediaStream([t.mediaStreamTrack]);
        lobbyVideo.play().catch(function () { /* autoplay of a muted element */ });
    }

    function hideLobbyPreview() {
        if (!lobbyVideo) { return; }
        try {
            lobbyVideo.srcObject = null;
            if (lobbyVideo.parentNode) { lobbyVideo.parentNode.removeChild(lobbyVideo); }
        } catch (e) { warn('hideLobbyPreview', e); }
        lobbyVideo = null;
    }

    function stopAll(tracks) {
        (tracks || []).forEach(function (t) { try { t.stop(); } catch (e) { /* already gone */ } });
    }

    /*
     * The lobby's device operations, one at a time and in order: an open
     * still waiting on getUserMedia must not race a camera toggle into a
     * second camera, or outlive the close that followed it.
     */
    var lobbyChain = Promise.resolve();

    function lobbyOp(work) {
        lobbyChain = lobbyChain.then(work).catch(function (e) { warn('lobby', e); });
        return lobbyChain;
    }

    /**
     * The Room, with the options every connection shares — the web's
     * `buildRoom` (`LivekitEngine.ts:463-469`). adaptiveStream asks the SFU for
     * the simulcast layer that fits the tile each video actually plays in, and
     * dynacast stops the layers nobody watches; together they are what keeps a
     * remote picture coming on a slow link. With adaptiveStream off every
     * viewer asked for the full 720p layer, which a slow downlink cannot carry,
     * and the tile stayed black where the web's showed a smaller picture.
     *
     * `pauseVideoInBackground` is off because this page is drawn off-screen
     * into the app's window: the document can read as hidden while the call
     * is on screen, and LiveKit would pause every video for it.
     */
    function buildRoom() {
        return new LK.Room({
            adaptiveStream: { pauseVideoInBackground: false },
            dynacast: true,
            videoCaptureDefaults: { resolution: LK.VideoPresets.h720.resolution },
        });
    }

    function sendConnection(word) {
        send({ type: 'connection', state: word, reason: '' });
    }

    /*
     * Reconnection — the web's (`LivekitEngine.ts:221-900`). LiveKit's own
     * reconnect misses a device network drop: an outage can leave the room
     * "connected" while media is dead, and a resume that stalls on a slow link
     * never restarts itself. So the device's own online/offline events count
     * too, and a room that stays down while the network is up is dropped and
     * joined again with the same token — LiveKit replaces the participant in
     * place, so the others see a rejoin, not a new party.
     */
    function onOnline() { netOffline = false; ensureReconnectPoll(); }
    function onOffline() {
        netOffline = true;
        if (room) { sendConnection('RECONNECTING'); }
        ensureReconnectPoll();
    }
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);

    function isConnected() {
        return !!room && room.state === LK.ConnectionState.Connected;
    }

    /** Polls while the link is down: clears once the room proves itself, rejoins when it is stuck, to a deadline. */
    function ensureReconnectPoll() {
        if (reconnectPoll || !room) { return; }
        var POLL = 1000, RETRY_AFTER = 4000, DEADLINE = 30000;
        var stuck = 0, spent = 0;
        reconnectPoll = setInterval(function () {
            spent += POLL;
            if (!room) { stopReconnectPoll(); return; }
            var connected = isConnected();
            if (!netOffline && connected && !rejoining) {
                stopReconnectPoll();
                sendConnection('CONNECTED');
                return;
            }
            // A rejoin in flight is the recovery, not a stuck room.
            if (!netOffline && !connected && !rejoining) { stuck += POLL; } else { stuck = 0; }
            if (stuck >= RETRY_AFTER && spent < DEADLINE) { stuck = 0; rejoin(); }
            if (spent >= DEADLINE) { stopReconnectPoll(); }
        }, POLL);
    }

    function stopReconnectPoll() {
        if (reconnectPoll) { clearInterval(reconnectPoll); reconnectPoll = null; }
    }

    /**
     * Drops the dead room without ending the call — its listeners go first,
     * so its disconnect cannot reach Kotlin as `left` — then joins again with
     * the same credentials and puts back what was being sent.
     */
    async function rejoin() {
        if (rejoining || !creds || !room) { return; }
        rejoining = true;
        var gen = joinGeneration;
        connectAt = Date.now();
        try {
            sendConnection('RECONNECTING');
            var dead = room;
            try { dead.removeAllListeners(); } catch (e) { /* not an emitter */ }
            dead.remoteParticipants.forEach(function (p) {
                p.trackPublications.forEach(function (pub) { detachRemote(p, pub); });
            });
            // Capped: a disconnect that hangs on a dead link must not block recovery.
            await Promise.race([
                dead.disconnect().catch(function () { /* already gone */ }),
                new Promise(function (resolve) { setTimeout(resolve, 1500); }),
            ]);
            if (gen !== joinGeneration) { return; }
            var r = buildRoom();
            room = r;
            wire(r);
            await r.connect(creds.url, creds.token);
            if (gen !== joinGeneration) { await r.disconnect(); return; }
            trace('rejoined after the link died');
            r.remoteParticipants.forEach(function (p) { peerJoined(p); });
            if (r.metadata) { reportRecording(r.metadata); }
            if (desiredMic && !onHold) {
                try {
                    await r.localParticipant.setMicrophoneEnabled(true, chosenMic ? { deviceId: chosenMic } : undefined);
                } catch (e) { warn('rejoin:microphone', e); }
            }
            if (desiredCam && !onHold) {
                try { await r.localParticipant.setCameraEnabled(true); } catch (e) { warn('rejoin:camera', e); }
            }
            showLocalPreview();
            sendConnection('CONNECTED');
            // The sampler was watching the room that died — `sample` retires
            // itself the moment `room` is another object — so a recovered call
            // sent no `audio`, no `ice` and had nothing watching its
            // microphone. It watches the new room from here.
            startSampler(r);
        } catch (e) {
            warn('rejoin', e);   // the poll retries to its deadline
        } finally {
            rejoining = false;
        }
    }

    function send(event) {
        try {
            window.cefQuery({ request: JSON.stringify(event) });
        } catch (e) { /* not hosted */ }
    }

    function warn(where, error) {
        var text = error && error.message ? error.message : String(error);
        send({ type: 'warning', where: 'livekit:' + where, message: text });
    }

    /** A breadcrumb in the app log (`livekit:trace`), for media steps that leave no other mark. */
    function trace(message) {
        send({ type: 'warning', where: 'livekit:trace', message: message });
    }

    // ── Per-call diagnostics the page measures ──────────────────────────────
    //
    // Four of `CallDiagnostics`' ten events are WebRTC facts — `publish`,
    // `speak`, `audio`, `ice` — plus `promoted`, `warm_connect` and `screen`
    // where the page is the only thing that can see them. Kotlin owns the
    // budget, the call window and the transport; this only measures and hands
    // over. Nothing here may throw into a call, so every sample is guarded.

    var sampleTimer = null;
    var prevStats = null;
    var prevScreenStats = null;
    var iceSeen = {};
    var publishLogged = false;
    /**
     * When the room this call publishes on started connecting — set by [join]
     * and [rejoin], NOT by the sampler. `publish` and `promoted` are measured
     * from it, and a clock set after the event it times reported `ms` as the
     * epoch itself: 46 lines in the log carry a "latency" of 56 years.
     */
    var connectAt = 0;
    var lastSpoke = false;
    /** Microphone recoveries on the live room: how many, when, one at a time, told once. */
    var micRecoveries = 0;
    var micRecoveryAt = 0;
    var micRecovering = false;
    var micFailureTold = false;

    function telemetry(event, data) {
        send({ type: 'telemetry', event: event, data: data || {} });
    }

    /** Elapsed millis since a clock that may never have been set — never an epoch. */
    function since(at) { return at ? Math.max(0, Date.now() - at) : 0; }

    function num(v) { return typeof v === 'number' && isFinite(v) ? v : 0; }
    function round1(v) { return Math.round(v * 10) / 10; }
    function round3(v) { return Math.round(v * 1000) / 1000; }

    function pcOf(r, which) {
        try {
            var mgr = r.engine && r.engine.pcManager;
            if (!mgr) { return null; }
            return which === 'pub' ? mgr.publisher : mgr.subscriber;
        } catch (e) { return null; }
    }

    function iceStateOf(pc) {
        try {
            return (pc && pc.getICEConnectionState) ? pc.getICEConnectionState() : 'connected';
        } catch (e) { return 'connected'; }
    }

    /**
     * Every remote audio track's own receiver stats.
     *
     * The inbound half is read per RECEIVER, through `getRTCStatsReport()` on
     * the track, rather than off the subscriber transport: the transport is
     * reached by walking `engine.pcManager`, which is the SDK's private shape
     * and in production handed back nothing — 7,881 audio lines in a row with
     * every inbound field at zero, which is why a "I cannot hear anything"
     * report had no evidence at all behind it. A track's receiver is public
     * API and is the thing actually carrying the audio.
     */
    async function inboundReports(r) {
        var reports = [];
        try {
            var people = [];
            r.remoteParticipants.forEach(function (p) { people.push(p); });
            for (var i = 0; i < people.length; i++) {
                var pubs = [];
                people[i].trackPublications.forEach(function (pub) { pubs.push(pub); });
                for (var j = 0; j < pubs.length; j++) {
                    var track = pubs[j].track;
                    if (!track || kindOf(track) !== 'audio' || !track.getRTCStatsReport) { continue; }
                    var report = await track.getRTCStatsReport().catch(function () { return null; });
                    if (report) { reports.push(report); }
                }
            }
        } catch (e) { /* a stat nobody could read is a zero, never a thrown call */ }
        return reports;
    }

    /**
     * `audio` — what this device sent and received in the last five seconds.
     *
     * A group call has several inbound streams: the counters are summed, and
     * the WORST jitter and the loudest level are taken, so one line still
     * answers "what do I receive".
     *
     * `jbDelayMs`, `jbTargetMs` and `concealedPct` are the jitter buffer's own
     * account of itself, as the web sends them — the three fields that settle
     * a "choppy audio" report, because packets can arrive in full while the
     * buffer conceals half of them.
     */
    function emitAudio(r, pub, inbound) {
        var outPkts = 0, outBytes = 0, outLevel = 0;
        if (pub) {
            pub.forEach(function (st) {
                if (st.type === 'outbound-rtp' && st.kind === 'audio') {
                    outPkts += num(st.packetsSent);
                    outBytes += num(st.bytesSent);
                } else if (st.type === 'media-source' && st.kind === 'audio') {
                    outLevel = Math.max(outLevel, num(st.audioLevel));
                }
            });
        }
        var inPkts = 0, inBytes = 0, inLost = 0, inLevel = 0, jitterMs = 0;
        var jbDelay = 0, jbEmitted = 0, jbTargetMs = 0, concealed = 0, samples = 0;
        (inbound || []).forEach(function (report) {
            report.forEach(function (st) {
                if (st.type !== 'inbound-rtp' || (st.kind || st.mediaType) !== 'audio') { return; }
                inPkts += num(st.packetsReceived);
                inBytes += num(st.bytesReceived);
                inLost += num(st.packetsLost);
                inLevel = Math.max(inLevel, num(st.audioLevel));
                jitterMs = Math.max(jitterMs, num(st.jitter) * 1000);
                // Both are running totals over emitted frames; the quotient is
                // the delay a listener is actually hearing.
                jbDelay += num(st.jitterBufferDelay);
                jbEmitted += num(st.jitterBufferEmittedCount);
                jbTargetMs = Math.max(jbTargetMs, num(st.jitterBufferTargetDelay) * 1000);
                concealed += num(st.concealedSamples);
                samples += num(st.totalSamplesReceived);
            });
        });
        var at = Date.now();
        var outKbps = 0, inKbps = 0;
        if (prevStats && at > prevStats.at) {
            var secs = (at - prevStats.at) / 1000;
            outKbps = round1(Math.max(0, outBytes - prevStats.outBytes) * 8 / 1000 / secs);
            inKbps = round1(Math.max(0, inBytes - prevStats.inBytes) * 8 / 1000 / secs);
        }
        prevStats = { at: at, outBytes: outBytes, inBytes: inBytes };
        var micOn = false;
        try { micOn = !!r.localParticipant.isMicrophoneEnabled; } catch (e) { /* ignore */ }
        telemetry('audio', {
            outPkts: outPkts, outKbps: outKbps, outLevel: round3(outLevel), micOn: micOn,
            inPkts: inPkts, inKbps: inKbps, inLevel: round3(inLevel), inLost: inLost,
            jitterMs: Math.round(jitterMs),
            jbDelayMs: jbEmitted > 0 ? Math.round(jbDelay / jbEmitted * 1000) : 0,
            jbTargetMs: Math.round(jbTargetMs),
            concealedPct: samples > 0 ? round1(concealed / samples * 100) : 0,
        });
    }

    /**
     * `ice` — one line per peer connection per distinct path.
     *
     * `local: "relay"` means TURN is in use, which is the whole reason this
     * event exists. Deduped on the path AND the ICE state, so a stable
     * connection sends one line while a state change always reports; the
     * bitrates are deliberately not in the signature — they move constantly
     * and would turn this into a second sampler.
     */
    function emitIce(which, report, state) {
        var byId = {};
        var selectedPairId = '';
        var pairs = [];
        report.forEach(function (st, id) {
            byId[id] = st;
            if (st.type === 'transport' && st.selectedCandidatePairId) { selectedPairId = st.selectedCandidatePairId; }
            if (st.type === 'candidate-pair') { pairs.push(st); }
        });
        var pair = (selectedPairId && byId[selectedPairId])
            || pairs.filter(function (p) { return p.selected === true; })[0]
            || pairs.filter(function (p) { return p.nominated === true && p.state === 'succeeded'; })[0]
            || pairs.filter(function (p) { return p.state === 'succeeded'; })[0];
        if (!pair) { return; }
        var local = byId[pair.localCandidateId];
        var remote = byId[pair.remoteCandidateId];
        if (!local || !remote) { return; }
        var signature = state + '|' + local.candidateType + '|' + remote.candidateType + '|' + local.protocol;
        if (iceSeen[which] === signature) { return; }
        iceSeen[which] = signature;
        var data = {
            pc: which,
            state: state,
            local: String(local.candidateType || ''),
            remote: String(remote.candidateType || ''),
            protocol: String(local.protocol || ''),
            rttMs: Math.round(num(pair.currentRoundTripTime) * 1000),
            availOutKbps: Math.round(num(pair.availableOutgoingBitrate) / 1000),
            availInKbps: Math.round(num(pair.availableIncomingBitrate) / 1000),
        };
        if (local.candidateType === 'relay') {
            if (local.relayProtocol) { data.relayProtocol = String(local.relayProtocol); }
            if (local.url) { data.url = String(local.url).slice(0, 120); }
        }
        telemetry('ice', data);
    }

    /**
     * `screen` — what a live share is actually sending.
     *
     * The only way to settle a "the share looks blurry" report:
     * `qualityLimitationReason` is the whole story, and it only diverges
     * between share modes once the encoder is under pressure. Sent only while
     * something is being shared, on the sampler that already runs.
     */
    function emitScreen(pub) {
        if (!pub) { return; }
        var width = 0, height = 0, fps = 0, bytes = 0, limited = '';
        pub.forEach(function (st) {
            if (st.type !== 'outbound-rtp' || st.kind !== 'video') { return; }
            var w = num(st.frameWidth);
            if (w < width) { return; }
            width = w; height = num(st.frameHeight);
            fps = num(st.framesPerSecond);
            bytes = num(st.bytesSent);
            limited = String(st.qualityLimitationReason || '');
        });
        // Nothing being shared — or a camera-only call, whose outbound video is
        // not a share. `screenPub` is the page's own answer to which it is.
        if (!width || !screenPub) { prevScreenStats = null; return; }
        var at = Date.now();
        var kbps = 0;
        if (prevScreenStats && at > prevScreenStats.at) {
            kbps = round1(Math.max(0, bytes - prevScreenStats.bytes) * 8 / 1000 / ((at - prevScreenStats.at) / 1000));
        }
        prevScreenStats = { at: at, bytes: bytes };
        telemetry('screen', {
            width: width, height: height, fps: Math.round(fps), kbps: kbps,
            limited: limited || 'none',
        });
    }

    /**
     * One transport's stats, or null.
     *
     * Through a try rather than `.catch`, because a `getStats` that returns
     * nothing at all (an SDK whose transport is not holding a peer connection
     * yet) has no `.catch` to call and would take the whole sample down with
     * it — and a sample that throws is a line the server never sees.
     */
    async function statsOf(pc) {
        if (!pc || !pc.getStats) { return null; }
        try { return (await pc.getStats()) || null; } catch (e) { return null; }
    }

    async function sample(r) {
        if (room !== r) { stopSampler(); return; }
        try {
            var pub = pcOf(r, 'pub');
            var sub = pcOf(r, 'sub');
            var pubReport = await statsOf(pub);
            var subReport = await statsOf(sub);
            var inbound = await inboundReports(r);
            if (room !== r) { return; }
            // The subscriber transport, when there were no receivers to read:
            // its report holds the same `inbound-rtp` rows, so whichever of
            // the two the SDK will actually hand over, the inbound half of
            // the line is filled rather than being zero by construction.
            if (!inbound.length && subReport) { inbound = [subReport]; }
            emitAudio(r, pubReport, inbound);
            emitScreen(pubReport);
            if (pubReport) { emitIce('pub', pubReport, iceStateOf(pub)); }
            if (subReport) { emitIce('sub', subReport, iceStateOf(sub)); }
            // The microphone is meant to be on and is not: the five-second
            // sampler is the only thing watching, and a call nobody notices
            // is the one that costs the user all of it rather than a moment.
            if (!micLive(r)) { await ensureMicPublished(r, 'sampler'); }
        } catch (e) { /* a failed sample is skipped; the loop continues */ }
    }

    /** Starts the five-second sampler for a connected room. Replaces any previous one. */
    function startSampler(r) {
        stopSampler();
        publishLogged = false;
        lastSpoke = false;
        micRecoveries = 0;
        micRecoveryAt = 0;
        micFailureTold = false;
        prevStats = null;
        prevScreenStats = null;
        iceSeen = {};
        // Promptly, not in five seconds: `ice` is the line most often wanted,
        // and a call that fails in its first seconds would never send one.
        sample(r);
        sampleTimer = setInterval(function () { sample(r); }, SAMPLE_MS);
    }

    /** Kills the sampler. Safe to call repeatedly. */
    function stopSampler() {
        if (sampleTimer) { clearInterval(sampleTimer); sampleTimer = null; }
        prevStats = null;
        prevScreenStats = null;
        iceSeen = {};
    }

    var SAMPLE_MS = 5000;

    /**
     * Microphone recoveries allowed per room. Three is a device that is busy
     * for a moment (the call we just left releasing it) recovered twice over;
     * past that it is gone, and reopening it every five seconds for the rest
     * of the call would be its own fault.
     */
    var MIC_RETRIES = 3;

    /** The least time between two of them, so the join and its sampler are not one attempt twice. */
    var MIC_RETRY_MS = 2000;

    /** How long a join waits for the devices its accept started opening. */
    var MIC_OPEN_WAIT_MS = 3000;

    /**
     * The person behind a LiveKit identity. The token's subject is
     * `<userId>#<device>_<ts>` on this backend (one identity per device), and
     * every roster row, tile and Kotlin uid is keyed by the user id alone —
     * matched whole, a participant bound to nothing and stood on the stage
     * as a third, nameless tile beside two real people.
     */
    function userIdOf(identity) {
        return String(identity || '').split('#')[0];
    }

    /** Line 1's numbering, so `mediasoupUidOf(identity)` in Kotlin agrees. */
    function uidOf(key) {
        var h = 0;
        for (var i = 0; i < key.length; i++) { h = (Math.imul(h, 31) + key.charCodeAt(i)) | 0; }
        var a = h < 0 ? (-h | 0) : h;
        return (a % 9900000) + 100000;
    }

    function trackKey(participantSid, source) {
        return 'lk:' + participantSid + ':' + source;
    }

    function kindOf(track) {
        return track && track.kind === 'audio' ? 'audio' : 'video';
    }

    function attachRemote(participant, publication) {
        var track = publication.track;
        if (!track || !track.mediaStreamTrack) { return; }
        var stream = new MediaStream([track.mediaStreamTrack]);
        var key = trackKey(participant.sid, publication.source);
        var user = userIdOf(participant.identity);
        var uid = uidOf(user);
        if (window.zillitCall && window.zillitCall.attachRemote) {
            // The page finds a video's tile by the roster's user id, which
            // Line 1 passes here too. The numeric uid never matched (a number
            // against the tile's string), so a remote camera on Line 3 was
            // received and never shown.
            // The last argument says "this is a shared screen": the page shows
            // it in the presenter's tile in place of their camera, uncropped,
            // rather than the two tracks taking turns in one cell.
            // The LiveKit track rides along for videos: the page attaches it to
            // the tile's element, which is what adaptiveStream sizes against.
            var kind = kindOf(track);
            window.zillitCall.attachRemote(
                key, user, kind, stream, publication.source === LK.Track.Source.ScreenShare,
                kind === 'video' ? track : null);
        }
        if (publication.source === LK.Track.Source.ScreenShare) {
            send({ type: 'peer-screen-share', uid: uid, sharing: true });
        }
    }

    function detachRemote(participant, publication) {
        var key = trackKey(participant.sid, publication.source);
        if (window.zillitCall && window.zillitCall.detachRemote) {
            window.zillitCall.detachRemote(key);
        }
        if (publication.source === LK.Track.Source.ScreenShare) {
            send({ type: 'peer-screen-share', uid: uidOf(userIdOf(participant.identity)), sharing: false });
        }
    }

    /**
     * What the camera track actually is, for the log: the page raises no
     * error when capture silently yields nothing (a camera macOS refused
     * without a prompt looks exactly like one that is on), so the state is
     * said out loud — dimensions, readyState and the device — on every change.
     */
    function traceCamera(where) {
        if (!room) { return; }
        var cam = room.localParticipant.getTrackPublication(LK.Track.Source.Camera);
        var t = cam && cam.track ? cam.track.mediaStreamTrack : null;
        var settings = t && t.getSettings ? t.getSettings() : {};
        send({
            type: 'warning',
            where: 'livekit:camera-state',
            message: where + ': published=' + !!cam + ' muted=' + (cam ? cam.isMuted : '-') +
                ' track=' + (t ? t.readyState + '/' + (t.muted ? 'muted' : 'live') : 'none') +
                ' ' + (settings.width || 0) + 'x' + (settings.height || 0) +
                ' device=' + (settings.deviceId ? String(settings.deviceId).slice(0, 8) : '-') +
                ' label=' + (t && t.label ? t.label : '-'),
        });
    }

    function showLocalPreview() {
        if (!room || !window.zillitCall) { return; }
        var cam = room.localParticipant.getTrackPublication(LK.Track.Source.Camera);
        var track = cam && cam.track && !cam.isMuted ? cam.track.mediaStreamTrack : null;
        if (track && window.zillitCall.attachLocalPreview) {
            window.zillitCall.attachLocalPreview(new MediaStream([track]));
        } else if (!track && window.zillitCall.clearLocalPreview) {
            window.zillitCall.clearLocalPreview();
        }
    }

    function peerJoined(p) {
        send({ type: 'peer-joined', uid: uidOf(userIdOf(p.identity)), peerId: userIdOf(p.identity) });
        p.trackPublications.forEach(function (pub) {
            if (pub.isSubscribed && pub.track) { attachRemote(p, pub); }
        });
        reportMuted(p);
        reportHand(p);
        reportQuality(p);
        applyLocalSubscriptions(p);
    }

    /**
     * A hand is a participant attribute — a raise timestamp, or "" for down
     * — which LiveKit replays to late joiners, as the web sets it. The
     * legacy `{t:"hand"}` data message is honoured too, for a peer on an
     * older bundle.
     */
    function reportHand(p) {
        var ts = Number(p.attributes && p.attributes.hand);
        send({ type: 'peer-hand', userId: userIdOf(p.identity), raised: isFinite(ts) && ts > 0 });
    }

    /**
     * The SFU's verdict on how well a participant reaches it, on the Agora
     * 0..6 scale the Kotlin side folds: 1 excellent, 2 good, 3 poor, 6 lost.
     */
    function reportQuality(p) {
        var Q = LK.ConnectionQuality;
        var q = p.connectionQuality;
        var n = q === Q.Excellent ? 1 : q === Q.Good ? 2 : q === Q.Poor ? 3 : q === Q.Lost ? 6 : 0;
        if (!n) { return; }
        var uid = room && p === room.localParticipant ? uidOf(userIdOf(identity)) : uidOf(userIdOf(p.identity));
        send({ type: 'network', uid: uid, tx: n, rx: n });
    }

    /** Who the room's metadata credits with recording; blank is nobody. */
    function reportRecording(md) {
        var by = '';
        try { by = String((JSON.parse(md || '{}') || {}).recordingBy || ''); } catch (e) { by = ''; }
        send({ type: 'lk-recording', by: userIdOf(by) });
    }

    function subscribeSource(p, source, on) {
        var pub = p.getTrackPublication(source);
        if (pub && pub.setSubscribed) {
            try { pub.setSubscribed(on); } catch (e) { warn('subscribe', e); }
        }
    }

    /** Re-asserts my own mutes and hides on one peer — after they (re)join, or a device switch. */
    function applyLocalSubscriptions(p) {
        var user = userIdOf(p.identity);
        if (deafened[user] || onHold) { subscribeSource(p, LK.Track.Source.Microphone, false); }
        if (hidden[user]) { subscribeSource(p, LK.Track.Source.Camera, false); }
    }

    function reportMuted(p) {
        var mic = p.getTrackPublication(LK.Track.Source.Microphone);
        var cam = p.getTrackPublication(LK.Track.Source.Camera);
        var uid = uidOf(userIdOf(p.identity));
        send({ type: 'peer-audio', uid: uid, muted: !mic || mic.isMuted });
        send({ type: 'peer-video', uid: uid, muted: !cam || cam.isMuted });
    }

    function wire(r) {
        var E = LK.RoomEvent;
        r.on(E.ParticipantConnected, function (p) { peerJoined(p); });
        r.on(E.ParticipantDisconnected, function (p) {
            p.trackPublications.forEach(function (pub) { detachRemote(p, pub); });
            send({ type: 'peer-left', uid: uidOf(userIdOf(p.identity)) });
        });
        r.on(E.TrackSubscribed, function (track, pub, p) {
            send({
                type: 'warning',
                where: 'livekit:trace',
                message: 'subscribed ' + pub.source + '/' + (track ? track.kind : '?') + ' from ' + userIdOf(p.identity),
            });
            attachRemote(p, pub);
            reportMuted(p);
        });
        r.on(E.TrackUnsubscribed, function (track, pub, p) { detachRemote(p, pub); reportMuted(p); });
        // Our own microphone muted by the SFU (the host's "mute everyone")
        // is reported so the button follows; a peer's is their tile.
        r.on(E.TrackMuted, function (pub, p) {
            if (p !== r.localParticipant) { reportMuted(p); return; }
            if (pub.source === LK.Track.Source.Microphone) { send({ type: 'self-audio', muted: true }); }
        });
        r.on(E.TrackUnmuted, function (pub, p) {
            if (p !== r.localParticipant) { reportMuted(p); return; }
            if (pub.source === LK.Track.Source.Microphone) { send({ type: 'self-audio', muted: false }); }
        });
        r.on(E.ParticipantAttributesChanged, function (changed, p) {
            if (p !== r.localParticipant) { reportHand(p); }
        });
        r.on(E.ConnectionQualityChanged, function (q, p) { reportQuality(p); });
        r.on(E.RoomMetadataChanged, function (md) { reportRecording(md); });
        r.on(E.ActiveSpeakersChanged, function (speakers) {
            var uids = [];
            var me = false;
            speakers.forEach(function (p) {
                if (p === r.localParticipant) { me = true; return; }
                uids.push(uidOf(userIdOf(p.identity)));
            });
            send({ type: 'speakers', uids: uids });
            // Our own edge, for the `speak` diagnostic. Edge only: Kotlin
            // debounces, and a line per flip would eat the whole budget.
            if (me !== lastSpoke) {
                lastSpoke = me;
                var level = 0;
                try { level = round3(num(r.localParticipant.audioLevel)); } catch (e) { /* ignore */ }
                send({ type: 'self-speaking', speaking: me, level: level });
            }
        });
        r.on(E.ConnectionStateChanged, function (state) {
            var S = LK.ConnectionState;
            var word = state === S.Connected ? 'CONNECTED'
                : state === S.Reconnecting ? 'RECONNECTING'
                : state === S.Disconnected ? 'DISCONNECTED' : 'CONNECTING';
            sendConnection(word);
            if (state === S.Reconnecting) { ensureReconnectPoll(); }
        });
        r.on(E.Disconnected, function () {
            if (room === r) {
                room = null;
                send({ type: 'left', channel: identity });
            }
        });
        // In-call chat rides LiveKit's reliable data channel, as the web's
        // `ChatChannel` sends it: `{t:"chat", id, text, ts}` and `{t:"del", id}`.
        // Attributed to the SFU-set identity, never a userId in the payload.
        r.on(E.DataReceived, function (payload, participant) {
            if (!participant) { return; }
            var msg;
            try { msg = JSON.parse(new TextDecoder().decode(payload)); } catch (e) { return; }
            if (!msg) { return; }
            // The legacy hand-raise packet, from a peer on a pre-attributes bundle.
            if (msg.t === 'hand') {
                send({ type: 'peer-hand', userId: userIdOf(participant.identity), raised: !!msg.raised });
                return;
            }
            // The host muted us: the SFU's mute says nothing about who.
            if (msg.t === 'muted') {
                if (msg.target === userIdOf(identity)) {
                    var by = msg.byName || 'The host';
                    send({
                        type: 'notice',
                        message: msg.source === 'camera' ? by + ' turned off your camera' : 'You were muted by ' + by,
                    });
                }
                return;
            }
            if (msg.t !== 'chat' && msg.t !== 'del') { return; }
            send({
                type: 'lk-chat',
                from: userIdOf(participant.identity),
                name: participant.name || '',
                id: String(msg.id || ''),
                text: String(msg.text || ''),
                ts: Number(msg.ts || Date.now()),
                deleted: msg.t === 'del',
            });
        });
        r.on(E.LocalTrackPublished, function (pub) {
            send({ type: 'warning', where: 'livekit:trace', message: 'published ' + (pub ? pub.source : '?') });
            // `publish` — the first MICROPHONE track on the wire, which is the
            // moment the call became usable. Once per call; Kotlin drops a
            // second one anyway, but the clock here is the room's own.
            var mic = pub && (pub.source === 'microphone' || pub.kind === 'audio');
            if (mic && !publishLogged) {
                publishLogged = true;
                telemetry('publish', { ok: true, ms: since(connectAt) });
            }
            showLocalPreview();
        });
        r.on(E.LocalTrackUnpublished, function () { showLocalPreview(); });
        r.on(E.MediaDevicesChanged, function () {
            if (window.zillitCall && window.zillitCall.listDevices) { window.zillitCall.listDevices(); }
        });
        // `promoted` — the server granted canPublish on a participant that
        // joined hidden on the ring's locked token. On the pre-warmed path this
        // IS the moment answering completes, and its absence after an
        // `accept_sent` is the signature of an accept the backend never acted
        // on. Only meaningful where the room was warm; a normal join is
        // publishing from the start.
        if (E.ParticipantPermissionsChanged) {
            r.on(E.ParticipantPermissionsChanged, function (prev, participant) {
                if (participant !== r.localParticipant) { return; }
                var could = prev && prev.canPublish;
                var can = false;
                try { can = !!r.localParticipant.permissions.canPublish; } catch (e) { /* ignore */ }
                if (can && !could) { telemetry('promoted', { ms: since(connectAt) }); }
            });
        }
    }

    /** Release anything `prewarmMedia` opened that a join never published. */
    function dropPrewarmedTracks() {
        mediaGen++;
        if (!prewarmed) { return; }
        prewarmed.forEach(function (t) { try { t.stop(); } catch (e) { /* already gone */ } });
        prewarmed = null;
    }

    /** The microphone publication this room is holding, if any. */
    function micPublication(r) {
        try {
            return r.localParticipant.getTrackPublication(LK.Track.Source.Microphone) || null;
        } catch (e) { return null; }
    }

    /**
     * Is a LIVE microphone track on the wire?
     *
     * Not `isMicrophoneEnabled`, which answers "is the publication unmuted" —
     * a room with no publication at all answers false to both, and a room
     * holding a publication whose device has ENDED answers true to the SDK
     * while sending silence. `readyState` is the only thing that knows.
     */
    function micLive(r) {
        if (!r || room !== r) { return true; }
        var pub = micPublication(r);
        var track = pub && pub.track;
        var device = track && track.mediaStreamTrack;
        return !!(device && device.readyState === 'live');
    }

    /**
     * THE MICROPHONE IS ON THE WIRE, or the log and the user are told why.
     *
     * Every mic open before this was `try { setMicrophoneEnabled } catch
     * { warn }` — one attempt, a line in the app log nobody reads, and a call
     * that then ran to its end publishing nothing. On 16 Oct a user answered
     * a call from inside another one, the device was still being released by
     * the call he was leaving, the single attempt failed with it, and he was
     * inaudible for five minutes while hearing everyone perfectly (call
     * 1c058f1e: micOn false and outPkts 0 on all 64 reports, no `publish`
     * line at all).
     *
     * So: retry, then fall back to the OS default — the device we were asked
     * for may be gone, which is what `device-missing` tells Kotlin, as Lines 1
     * and 2 already report it — and when it still will not open, say so in the
     * telemetry (`publish {ok:false}`) and on screen, because a user who knows
     * nobody can hear them can at least hang up and dial again.
     */
    async function ensureMicPublished(r, why) {
        if (room !== r || !desiredMic || onHold || rejoining || micRecovering) { return; }
        if (micLive(r)) { return; }
        if (micRecoveries >= MIC_RETRIES) { return; }
        // Spaced out: the join asks, and so does the sampler it starts a
        // moment later. Two attempts in the same instant are one attempt
        // against the same busy device, and would spend the budget on it.
        if (micRecoveryAt && Date.now() - micRecoveryAt < MIC_RETRY_MS) { return; }
        micRecovering = true;
        micRecoveries++;
        micRecoveryAt = Date.now();
        trace('microphone is not on the wire (' + why + '); attempt ' + micRecoveries);
        try {
            // The chosen device first, then the OS default: a mic that is
            // simply busy comes back on the retry, and one that is gone never
            // will.
            var attempts = chosenMic ? [{ deviceId: chosenMic }, undefined] : [undefined];
            for (var i = 0; i < attempts.length; i++) {
                if (room !== r || !desiredMic || onHold) { return; }
                try {
                    // Capped: a device open that hangs would leave this the
                    // only thing watching the microphone, waiting for ever —
                    // the same silence it exists to break. The attempt that
                    // overran may still land; the next look sees it.
                    await Promise.race([
                        r.localParticipant.setMicrophoneEnabled(true, attempts[i]),
                        new Promise(function (resolve, reject) {
                            setTimeout(function () { reject(new Error('mic open timed out')); }, MIC_OPEN_WAIT_MS);
                        }),
                    ]);
                } catch (e) {
                    warn('microphone:recover', e);
                    continue;
                }
                if (!micLive(r)) { continue; }
                if (attempts[i] === undefined && chosenMic) {
                    // It opened on the default, so the remembered one is the
                    // problem: Kotlin forgets the choice rather than failing
                    // the next call the same way.
                    trace('the chosen microphone would not open; the OS default did');
                    send({ type: 'device-missing', kind: 'microphone' });
                    chosenMic = '';
                }
                return;
            }
            // Out of attempts, and still nothing on the wire. The log has
            // carried no sign of this until now — `publish` was sent only on
            // success, so a call with no microphone read exactly like a call
            // whose telemetry was dropped — and the user had no way to know
            // either, which is how one of them talked for five minutes.
            if (room !== r || micFailureTold || micRecoveries < MIC_RETRIES) { return; }
            micFailureTold = true;
            telemetry('publish', { ok: false, ms: since(connectAt), why: why });
            send({
                type: 'notice',
                message: 'Your microphone could not be started — the others cannot hear you.',
            });
        } finally {
            micRecovering = false;
        }
    }

    /** Waits for an in-flight `prewarmMedia` so [join] publishes its tracks instead of racing them. */
    async function settleMicOpen() {
        var pending = prewarmPending;
        if (!pending) { return; }
        // Capped: a device whose open hangs — a driver, or an OS prompt nobody
        // answers — must not hold the publish behind it for the whole call.
        // Past the cap the join opens its own, and the late tracks are
        // released rather than left holding the device.
        await Promise.race([
            pending.catch(function () { /* its own warn covers it */ }),
            new Promise(function (resolve) { setTimeout(resolve, MIC_OPEN_WAIT_MS); }),
        ]);
    }

    /**
     * Publish the tracks `prewarmMedia` already opened, honouring what the user
     * has switched off since — a pre-muted microphone must never reach the wire,
     * so its track is stopped rather than published.
     *
     * Returns false when there was nothing warm, and the caller opens the
     * devices the ordinary way.
     */
    async function publishPrewarmed(r) {
        var tracks = prewarmed;
        if (!tracks || !tracks.length) { return false; }
        prewarmed = null;
        for (var i = 0; i < tracks.length; i++) {
            var t = tracks[i];
            var audio = t.kind === (LK.Track && LK.Track.Kind ? LK.Track.Kind.Audio : 'audio');
            var keep = audio ? desiredMic : desiredCam;
            if (!keep || room !== r) {
                try { t.stop(); } catch (e) { /* already gone */ }
                continue;
            }
            try {
                await r.localParticipant.publishTrack(t);
            } catch (e) { warn(audio ? 'publish:microphone' : 'publish:camera', e); }
        }
        return true;
    }

    /** The lobby's open, run in turn: the microphone, and the camera when [video], then the preview. */
    async function openLobbyNow(video, gen) {
        if (gen !== lobbyGen) { return; }
        if (room) { trace('openLobby while a room is up; ignored'); return; }
        hideLobbyPreview();
        dropPrewarmedTracks();
        var tracks = null;
        try {
            tracks = await LK.createLocalTracks({
                audio: true,
                video: video ? { resolution: LK.VideoPresets.h720.resolution } : false,
            });
        } catch (e) {
            warn('openLobby', e);
            // The camera is the usual refusal: keep the microphone warm regardless.
            if (!video) { return; }
            try { tracks = await LK.createLocalTracks({ audio: true, video: false }); } catch (e2) {
                warn('openLobby:audio', e2);
                return;
            }
        }
        if (gen !== lobbyGen || room) {
            stopAll(tracks);
            trace('lobby media landed after the lobby; released');
            return;
        }
        prewarmed = tracks;
        trace('lobby opened ' + tracks.length + ' track(s)');
        showLobbyPreview();
    }

    /** The lobby's camera toggle, run in turn: a camera track opened and previewed, or stopped. */
    async function setLobbyCameraNow(on) {
        if (room) { return; }
        var current = lobbyCameraTrack();
        if (!on) {
            if (current) {
                stopAll([current]);
                prewarmed = prewarmed.filter(function (t) { return t !== current; });
            }
            hideLobbyPreview();
            return;
        }
        if (current) { showLobbyPreview(); return; }
        var gen = lobbyGen;
        try {
            var cam = await LK.createLocalVideoTrack({ resolution: LK.VideoPresets.h720.resolution });
            if (gen !== lobbyGen || room) { stopAll([cam]); return; }
            prewarmed = (prewarmed || []).concat([cam]);
            showLobbyPreview();
        } catch (e) { warn('setLobbyCamera', e); }
    }

    window.zillitLk = {
        get active() { return room !== null; },

        /**
         * PRE-CONNECT on the ring's locked token. Idempotent by call id: the
         * same ring can be reported twice, and the second report must not tear
         * down the connect the first one started — that is how a warm room dies
         * mid-handshake and the answer ends up slow anyway.
         */
        prewarm(callId, url, preconnectToken) {
            if (!LK || !callId || !url || !preconnectToken) { return; }
            if (warm && warm.callId === callId) { return; }
            this.dropPrewarm('');
            warmClaimed = '';
            var myGen = ++warmGen;
            var r = buildRoom();
            var startedAt = Date.now();
            trace('prewarm connecting during the ring for ' + callId);
            var ready = r.connect(url, preconnectToken).then(function () {
                // Declined or cancelled while connecting: disconnect the late
                // arrival rather than leaving a hidden participant in the room.
                if (myGen !== warmGen) { r.disconnect().catch(function () {}); return false; }
                // This IS the connect for a ring-warmed call, so it owns
                // `warm_connect`; join() only sends it on the paths that connect
                // themselves, which keeps it at one line per call either way.
                //
                // Named, unlike every other line: a ring arriving while a call
                // is up is warmed BESIDE that call, and the diagnostics window
                // belongs to the call we are on. Without the id this line was
                // filed under the wrong call.
                telemetry('warm_connect', { ok: true, ms: since(startedAt), call: callId });
                return true;
            }).catch(function (e) { warn('prewarm', e); return false; });
            warm = { callId: callId, room: r, ready: ready, gen: myGen };
        },

        /** "I am accepting this call" — see `warmClaimed`. */
        claimPrewarm(callId) {
            if (!warm || warm.callId !== callId) { return; }
            warmClaimed = callId;
            trace('prewarm claimed for the accept of ' + callId);
        },

        /**
         * Let the warm room go. A blank [callId] drops whatever is warm; a
         * named one only that call's, so a terminal event for another call can
         * never take this one's room with it.
         */
        dropPrewarm(callId) {
            if (!warm) { return; }
            if (callId && warm.callId !== callId) { return; }
            if (warmClaimed && warm.callId === warmClaimed) {
                trace('prewarm drop ignored — accepted, adoption pending, for ' + warm.callId);
                return;
            }
            var w = warm;
            warm = null;
            warmGen++;
            trace('prewarm dropping the warm room for ' + w.callId);
            w.ready.then(function () { w.room.disconnect().catch(function () {}); }).catch(function () {});
            dropPrewarmedTracks();
        },

        /**
         * Open the microphone and camera NOW, before there is a room.
         *
         * The device warm-up and the OS permission prompt then overlap the
         * call-setup round trips instead of following them. These exact tracks
         * are what join publishes — never re-opened, because a second
         * getUserMedia while the first is still held fails on some hardware.
         */
        prewarmMedia(video, audio) {
            if (!LK || prewarmed || prewarmPending || (!video && !audio)) { return Promise.resolve(); }
            var myGen = mediaGen;
            var open = (async function () {
                try {
                    var tracks = await LK.createLocalTracks({
                        audio: !!audio,
                        video: video ? { resolution: LK.VideoPresets.h720.resolution } : false,
                    });
                    // Too late to be of use: the call ended while the devices
                    // were opening, or a join waited for them as long as it
                    // could and went on to open its own (the sampler running
                    // is what says a join is past its publish step). Holding
                    // them then is both the second getUserMedia this
                    // mechanism exists to avoid and a device the recovery
                    // below would find busy.
                    if (myGen !== mediaGen || (room && sampleTimer)) {
                        tracks.forEach(function (t) { try { t.stop(); } catch (e) { /* gone */ } });
                        trace('prewarmMedia landed too late; devices released');
                        return;
                    }
                    prewarmed = tracks;
                    trace('prewarmMedia opened ' + tracks.length + ' track(s)');
                } catch (e) {
                    // Permission denied, or no device. The join reports it; there is
                    // nothing to release.
                    prewarmed = null;
                    warn('prewarmMedia', e);
                }
            })();
            // Held so the join can WAIT for it: on the accept path the devices
            // are opened at the tap and the join follows a round trip later,
            // and the two running side by side is a getUserMedia against a
            // device the first call still holds.
            prewarmPending = open;
            return open.then(function () {
                if (prewarmPending === open) { prewarmPending = null; }
            });
        },

        /** A meet's lobby opened: the microphone, and the camera when [video], previewed full-surface. */
        openLobby(video) {
            if (!LK) { return Promise.resolve(); }
            var gen = ++lobbyGen;
            return lobbyOp(function () { return openLobbyNow(video, gen); });
        },

        /** The lobby's camera toggle: open a camera track and preview it, or stop it so the light goes out. */
        setLobbyCamera(on) {
            if (!LK) { return Promise.resolve(); }
            return lobbyOp(function () { return setLobbyCameraNow(on); });
        },

        /** Handed off or retired: the picture goes; the tracks stay for join() to publish. */
        endLobbyPreview() {
            hideLobbyPreview();
        },

        /** Closed without joining: every track the lobby opened is stopped. */
        closeLobby() {
            // At once, so an open still in flight discards what it gets.
            lobbyGen++;
            hideLobbyPreview();
            return lobbyOp(function () {
                hideLobbyPreview();
                if (!room) { dropPrewarmedTracks(); }
            });
        },

        async join(url, token, selfIdentity, displayName, withVideo, microphoneId, callId, micMuted) {
            if (!LK) { send({ type: 'error', message: 'LiveKit SDK is not loaded' }); return; }
            if (room) { await this.leave(); }
            var gen = ++joinGeneration;
            identity = selfIdentity || '';
            // A meet lobby's muted microphone is applied before anything is
            // published; every other join passes nothing and joins unmuted.
            desiredMic = !micMuted;
            desiredCam = !!withVideo;
            chosenMic = microphoneId || '';
            // From HERE, not from the sampler: `publish` and `promoted` are
            // measured against it, and a clock set after them turned every
            // desktop latency in the log into a Unix timestamp.
            connectAt = Date.now();
            var r = null;
            try {
                // ADOPT the room the ring pre-warmed, when this is that call:
                // the participant is already in it and the accept has upgraded
                // it in place, so connecting again would be the second connect
                // the whole mechanism exists to avoid.
                var adopted = null;
                if (warm && callId && warm.callId === callId) {
                    adopted = warm;
                    warm = null;
                    warmClaimed = '';
                }
                r = adopted ? adopted.room : buildRoom();
                room = r;
                creds = { url: url, token: token };
                wire(r);
                if (adopted) {
                    trace('adopted the warm room for ' + callId + ' — no second connect');
                    var landed = await adopted.ready;
                    if (!landed) {
                        // The pre-connect never made it. Fall back to a normal
                        // connect on this room rather than sitting in a room
                        // that was never joined.
                        trace('the warm room never connected; connecting now');
                        await r.connect(url, token);
                    }
                } else {
                    await r.connect(url, token);
                }
                if (gen !== joinGeneration) { await r.disconnect(); return; }
                onHold = false;
                r.remoteParticipants.forEach(function (p) { peerJoined(p); });
                send({ type: 'joined', channel: identity, uid: uidOf(userIdOf(identity)) });
                // A warm room connected during the ring, before `wire` was
                // listening, so its CONNECTED edge was never sent — and that
                // edge is what tells Kotlin there is no outage to wait out.
                if (adopted && isConnected()) { sendConnection('CONNECTED'); }
                // Hands and the recorder as they stand: the room replays neither as events.
                if (r.metadata) { reportRecording(r.metadata); }
                // A stale hand from a previous session on this device would
                // otherwise stay up; an explicit "" forces the change through.
                r.localParticipant.setAttributes({ hand: '' }).catch(function () { /* no grant */ });
                // The devices this call's accept already started opening: the
                // join publishes THOSE, so it waits for them rather than
                // opening a second set against a device the first is holding.
                await settleMicOpen();
                if (gen !== joinGeneration) { await r.disconnect(); return; }
                // The tracks `prewarmMedia` opened, if any: publishing those is
                // the point of having opened them early. Nothing warm → open the
                // devices here as before.
                if (!(await publishPrewarmed(r))) {
                    if (desiredMic) {
                        try {
                            await r.localParticipant.setMicrophoneEnabled(
                                true,
                                chosenMic ? { deviceId: chosenMic } : undefined,
                            );
                        } catch (e) { warn('microphone', e); }
                    }
                    if (desiredCam) {
                        try { await r.localParticipant.setCameraEnabled(true); } catch (e) { warn('camera', e); }
                        traceCamera('join');
                    }
                }
                startSampler(r);
                showLocalPreview();
                if (window.zillitCall && window.zillitCall.listDevices) { window.zillitCall.listDevices(); }
                // Whatever the path above did, the question the user cares
                // about is whether the microphone is on the wire. Asked here
                // once, and by the sampler for as long as the call runs.
                //
                // Not awaited: reopening a device takes the device's own time,
                // and the call is joined — nothing above waits on it.
                ensureMicPublished(r, 'join').catch(function () { /* never a thrown call */ });
            } catch (e) {
                if (room === r) { room = null; }
                // Our own leave() during the connect (the call ended while the
                // room was still dialling) is not a failure to report.
                if (gen !== joinGeneration) { return; }
                send({ type: 'error', message: 'LiveKit join failed: ' + (e && e.message ? e.message : e) });
            }
        },

        async leave() {
            var r = room;
            room = null;
            joinGeneration++;
            creds = null;
            stopReconnectPoll();
            stopSampler();
            connectAt = 0;
            dropPrewarmedTracks();
            hideLobbyPreview();
            screenPub = null;
            onHold = false;
            deafened = {};
            hidden = {};
            if (window.zillitCall) {
                if (window.zillitCall.clearLocalPreview) { window.zillitCall.clearLocalPreview(); }
            }
            if (!r) { return; }
            try {
                r.remoteParticipants.forEach(function (p) {
                    p.trackPublications.forEach(function (pub) { detachRemote(p, pub); });
                });
                r.removeAllListeners();
                await r.disconnect();
            } catch (e) { warn('leave', e); }
            send({ type: 'left', channel: identity });
        },

        setMic(muted) {
            desiredMic = !muted;
            if (!room) { return; }
            room.localParticipant.setMicrophoneEnabled(!muted).catch(function (e) { warn('setMic', e); });
        },

        async setCam(enabled) {
            desiredCam = !!enabled;
            if (!room) { return; }
            try {
                await room.localParticipant.setCameraEnabled(!!enabled);
            } catch (e) { warn('setCam', e); }
            showLocalPreview();
            traceCamera('setCam(' + !!enabled + ')');
        },

        async setMicrophoneDevice(deviceId) {
            chosenMic = deviceId || '';
            if (!room || !chosenMic) { return; }
            try { await room.switchActiveDevice('audioinput', chosenMic); } catch (e) { warn('setMicrophoneDevice', e); }
        },

        async setCameraDevice(deviceId) {
            if (!room || !deviceId) { return; }
            try { await room.switchActiveDevice('videoinput', deviceId); } catch (e) { warn('setCameraDevice', e); }
            showLocalPreview();
        },

        async startScreenShare(sourceId) {
            if (!room || screenPub || shareStarting) { return; }
            shareStarting = true;
            var track = null;
            try {
                if (sourceId) {
                    var stream = await navigator.mediaDevices.getUserMedia({
                        video: { mandatory: { chromeMediaSource: 'desktop', chromeMediaSourceId: sourceId, maxWidth: 1920, maxHeight: 1080 } },
                    });
                    track = stream.getVideoTracks()[0];
                } else {
                    var display = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false });
                    track = display.getVideoTracks()[0];
                }
                if (!track) { throw new Error('the chosen source produced no video'); }
                // Listened for before the publish, not after: a window closed
                // while the publish was in flight used to end the capture
                // unheard, leaving a share that said it was live and sent nothing.
                var captured = track;
                captured.addEventListener('ended', function () {
                    trace('screen capture ended by its source');
                    if (screenPub && screenPub.track && screenPub.track.mediaStreamTrack === captured) {
                        window.zillitLk.stopScreenShare();
                    }
                });
                screenPub = await room.localParticipant.publishTrack(track, {
                    source: LK.Track.Source.ScreenShare,
                    name: 'screen',
                });
                if (track.readyState === 'ended') {
                    trace('screen capture ended while publishing');
                    await window.zillitLk.stopScreenShare();
                    return;
                }
                var settings = track.getSettings ? track.getSettings() : {};
                trace('sharing screen ' + (settings.width || 0) + 'x' + (settings.height || 0));
                send({ type: 'screen-share', sharing: true });
            } catch (e) {
                warn('startScreenShare', e);
                // The capture is released too: a publish the SFU refused
                // otherwise leaves macOS's recording indicator lit on a share
                // the UI has just said stopped.
                if (track) { try { track.stop(); } catch (e2) { /* already stopped */ } }
                screenPub = null;
                send({ type: 'screen-share', sharing: false });
            } finally {
                shareStarting = false;
            }
        },

        async stopScreenShare() {
            var pub = screenPub;
            screenPub = null;
            if (!room || !pub) { return; }
            trace('stopping the screen share');
            try {
                await room.localParticipant.unpublishTrack(pub.track, true);
            } catch (e) { warn('stopScreenShare', e); }
            send({ type: 'screen-share', sharing: false });
        },

        /** One line of in-call chat to everyone, in the web's packet shape. */
        sendChat(id, text, ts) {
            if (!room) { return; }
            var packet = JSON.stringify({ t: 'chat', id: id, text: text, ts: ts });
            room.localParticipant.publishData(new TextEncoder().encode(packet), { reliable: true })
                .catch(function (e) { warn('sendChat', e); });
        },

        setHand(raised) {
            if (!room) { return; }
            // The web sets a participant attribute; every LiveKit client reads
            // it. The legacy data message goes too, for a peer on an older bundle.
            room.localParticipant.setAttributes({ hand: raised ? String(Date.now()) : '' }).catch(function () { /* no grant */ });
            var packet = JSON.stringify({ t: 'hand', raised: !!raised });
            room.localParticipant.publishData(new TextEncoder().encode(packet), { reliable: true })
                .catch(function () { /* no grant */ });
        },

        /**
         * Hold: the room stays connected, but nothing is sent (mic and
         * camera off) and nothing heard (every remote microphone
         * unsubscribed). Resume restores what was on before the hold — never
         * a live microphone the user had not asked for — and skips anyone
         * muted for me before it.
         */
        async setHold(on) {
            if (!room || onHold === !!on) { return; }
            onHold = !!on;
            var p = room.localParticipant;
            if (on) {
                var mic = p.getTrackPublication(LK.Track.Source.Microphone);
                var cam = p.getTrackPublication(LK.Track.Source.Camera);
                mediaBeforeHold = { mic: !!mic && !mic.isMuted, cam: !!cam && !cam.isMuted };
                try { await p.setMicrophoneEnabled(false); } catch (e) { warn('hold', e); }
                try { await p.setCameraEnabled(false); } catch (e) { warn('hold', e); }
            } else {
                if (mediaBeforeHold.mic) { try { await p.setMicrophoneEnabled(true); } catch (e) { warn('resume', e); } }
                if (mediaBeforeHold.cam) { try { await p.setCameraEnabled(true); } catch (e) { warn('resume', e); } }
            }
            desiredMic = on ? false : mediaBeforeHold.mic;
            desiredCam = on ? false : mediaBeforeHold.cam;
            room.remoteParticipants.forEach(function (peer) {
                if (!on && deafened[userIdOf(peer.identity)]) { return; }
                subscribeSource(peer, LK.Track.Source.Microphone, !on);
            });
            showLocalPreview();
        },

        /** "Mute for myself" / "don't watch": only this client stops receiving; nobody is told. */
        setPeerSubscribed(userId, video, on) {
            var table = video ? hidden : deafened;
            if (on) { delete table[userId]; } else { table[userId] = true; }
            if (!room) { return; }
            var source = video ? LK.Track.Source.Camera : LK.Track.Source.Microphone;
            room.remoteParticipants.forEach(function (peer) {
                if (userIdOf(peer.identity) === userId) { subscribeSource(peer, source, !!on); }
            });
        },

        /** Who muted whom — the web's `{t:"muted"}` packet, so the target sees a name. */
        announceHostMute(targetUserId, camera, byName) {
            if (!room) { return; }
            var packet = JSON.stringify({
                t: 'muted', target: targetUserId, source: camera ? 'camera' : 'microphone', byName: byName,
            });
            room.localParticipant.publishData(new TextEncoder().encode(packet), { reliable: true })
                .catch(function (e) { warn('announceHostMute', e); });
        },

        getLocalAudioTrack() {
            if (!room) { return null; }
            var mic = room.localParticipant.getTrackPublication(LK.Track.Source.Microphone);
            return mic && mic.track ? mic.track.mediaStreamTrack : null;
        },
    };
})();
