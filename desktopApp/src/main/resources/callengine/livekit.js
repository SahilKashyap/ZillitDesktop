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
 * not LiveKit's `track.attach`, so tiles, names and speaking rings are the
 * same chrome on every line.
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

    function send(event) {
        try {
            window.cefQuery({ request: JSON.stringify(event) });
        } catch (e) { /* not hosted */ }
    }

    function warn(where, error) {
        var text = error && error.message ? error.message : String(error);
        send({ type: 'warning', where: 'livekit:' + where, message: text });
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
            window.zillitCall.attachRemote(key, user, kindOf(track), stream);
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
        r.on(E.TrackMuted, function (pub, p) { if (p !== r.localParticipant) { reportMuted(p); } });
        r.on(E.TrackUnmuted, function (pub, p) { if (p !== r.localParticipant) { reportMuted(p); } });
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
            send({ type: 'connection', state: word, reason: '' });
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
            if (!msg || (msg.t !== 'chat' && msg.t !== 'del')) { return; }
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
                r = new LK.Room({
                    adaptiveStream: false,
                    dynacast: true,
                    videoCaptureDefaults: { resolution: LK.VideoPresets.h720.resolution },
                });
                room = r;
                wire(r);
                await r.connect(url, token);
                if (gen !== joinGeneration) { await r.disconnect(); return; }
                r.remoteParticipants.forEach(function (p) { peerJoined(p); });
                send({ type: 'joined', channel: identity, uid: uidOf(userIdOf(identity)) });
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
            screenPub = null;
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
            if (!room || screenPub) { return; }
            try {
                var track;
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
                screenPub = await room.localParticipant.publishTrack(track, { source: LK.Track.Source.ScreenShare });
                track.addEventListener('ended', function () { window.zillitLk.stopScreenShare(); });
                send({ type: 'screen-share', sharing: true });
            } catch (e) {
                warn('startScreenShare', e);
                screenPub = null;
                send({ type: 'screen-share', sharing: false });
            }
        },

        async stopScreenShare() {
            var pub = screenPub;
            screenPub = null;
            if (!room || !pub) { return; }
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
            // The web sets a participant attribute; every LiveKit client reads it.
            room.localParticipant.setAttributes({ hand: raised ? String(Date.now()) : '' }).catch(function () { /* no grant */ });
        },

        getLocalAudioTrack() {
            if (!room) { return null; }
            var mic = room.localParticipant.getTrackPublication(LK.Track.Source.Microphone);
            return mic && mic.track ? mic.track.mediaStreamTrack : null;
        },
    };
})();
