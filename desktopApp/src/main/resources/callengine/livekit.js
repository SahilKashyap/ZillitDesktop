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
            speakers.forEach(function (p) { if (p !== r.localParticipant) { uids.push(uidOf(userIdOf(p.identity))); } });
            send({ type: 'speakers', uids: uids });
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
            showLocalPreview();
        });
        r.on(E.LocalTrackUnpublished, function () { showLocalPreview(); });
        r.on(E.MediaDevicesChanged, function () {
            if (window.zillitCall && window.zillitCall.listDevices) { window.zillitCall.listDevices(); }
        });
    }

    window.zillitLk = {
        get active() { return room !== null; },

        async join(url, token, selfIdentity, displayName, withVideo, microphoneId) {
            if (!LK) { send({ type: 'error', message: 'LiveKit SDK is not loaded' }); return; }
            if (room) { await this.leave(); }
            var gen = ++joinGeneration;
            identity = selfIdentity || '';
            desiredMic = true;
            desiredCam = !!withVideo;
            chosenMic = microphoneId || '';
            var r = null;
            try {
                r = buildRoom();
                room = r;
                creds = { url: url, token: token };
                wire(r);
                await r.connect(url, token);
                if (gen !== joinGeneration) { await r.disconnect(); return; }
                onHold = false;
                r.remoteParticipants.forEach(function (p) { peerJoined(p); });
                send({ type: 'joined', channel: identity, uid: uidOf(userIdOf(identity)) });
                // Hands and the recorder as they stand: the room replays neither as events.
                if (r.metadata) { reportRecording(r.metadata); }
                // A stale hand from a previous session on this device would
                // otherwise stay up; an explicit "" forces the change through.
                r.localParticipant.setAttributes({ hand: '' }).catch(function () { /* no grant */ });
                try {
                    await r.localParticipant.setMicrophoneEnabled(true, chosenMic ? { deviceId: chosenMic } : undefined);
                } catch (e) { warn('microphone', e); }
                if (desiredCam) {
                    try { await r.localParticipant.setCameraEnabled(true); } catch (e) { warn('camera', e); }
                    traceCamera('join');
                }
                showLocalPreview();
                if (window.zillitCall && window.zillitCall.listDevices) { window.zillitCall.listDevices(); }
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
