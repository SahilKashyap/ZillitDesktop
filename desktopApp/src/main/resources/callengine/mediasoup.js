/*
 * Line 1's media half.
 *
 * mediasoup-client lives here because it needs WebRTC — real transports, real
 * tracks — and the page is where Chromium's WebRTC stack is. protoo signalling
 * does NOT live here: the browser WebSocket API cannot send ping frames, and
 * without them a half-open socket is invisible, so signalling runs in Kotlin
 * (see OkHttpProtooSocket) and this module asks it whenever the SFU has to be
 * consulted.
 *
 * Two directions across the bridge:
 *   Kotlin -> page   window.zillitMs.*        commands
 *   page -> Kotlin   window.cefQuery(...)     events, and "asks"
 *
 * An "ask" is a request that needs an answer — mediasoup-client's `connect` and
 * `produce` transport callbacks cannot proceed without one, and only Kotlin can
 * talk to the SFU. The page keeps the promise; Kotlin answers it by id through
 * `settle`. That is the same correlation ProtooPeer does on the other side, for
 * the same reason: exactly one path may complete each request.
 */
(function () {
    'use strict';

    var lib = window.zillitMediasoup;

    var device = null;
    var sendTransport = null;
    var recvTransport = null;
    var micProducer = null;
    var camProducer = null;
    var screenProducer = null;
    var consumers = {};
    var localStream = null;
    var camStream = null;

    var pending = {};
    var nextAskId = 1;

    function post(event) {
        try {
            window.cefQuery({ request: JSON.stringify(event) });
        } catch (e) {
            // Nothing to do: the bridge is how we would have reported this.
        }
    }

    function emit(type, fields) {
        var event = fields || {};
        event.type = type;
        post(event);
    }

    function warn(where, error) {
        emit('warning', { where: where, message: String((error && error.message) || error) });
    }

    function fail(where, error) {
        emit('failed', { where: where, message: String((error && error.message) || error) });
    }

    /**
     * Asks Kotlin to make one protoo request and resolves with its reply.
     *
     * Rejects if Kotlin says the SFU refused. There is no timeout here on
     * purpose — Kotlin's request already carries one, and a second timer on
     * this side could only disagree with it.
     */
    function ask(method, data) {
        return new Promise(function (resolve, reject) {
            var id = nextAskId++;
            pending[id] = { resolve: resolve, reject: reject };
            emit('ms-ask', { askId: id, method: method, data: data || {} });
        });
    }

    function currentTransport(id) {
        if (sendTransport && sendTransport.id === id) { return sendTransport; }
        if (recvTransport && recvTransport.id === id) { return recvTransport; }
        return null;
    }

    /**
     * Wires the two callbacks a mediasoup transport cannot work without.
     *
     * `connect` fires once, on the first media. `produce` fires per outgoing
     * track. Both must reach the SFU, so both become asks. Each re-looks-up the
     * transport by id before acting, because a rejoin can replace it while a
     * round-trip is in flight and answering into the old one is silent failure.
     */
    function wireTransport(transport, isSend) {
        transport.on('connect', function (params, callback, errback) {
            ask('connectWebRtcTransport', {
                transportId: transport.id,
                dtlsParameters: params.dtlsParameters,
            }).then(function () {
                if (!currentTransport(transport.id)) { return; }
                callback();
            }).catch(function (error) {
                errback(error);
                fail('transport-connect', error);
            });
        });

        if (isSend) {
            transport.on('produce', function (params, callback, errback) {
                ask('produce', {
                    transportId: transport.id,
                    kind: params.kind,
                    rtpParameters: params.rtpParameters,
                    // Forwarded rather than dropped: this is what marks a
                    // screen-share producer, and a consumer that never sees it
                    // cannot tell a shared screen from a camera.
                    appData: params.appData || {},
                }).then(function (reply) {
                    if (!currentTransport(transport.id)) { return; }
                    callback({ id: reply.id });
                }).catch(function (error) {
                    errback(error);
                    fail('transport-produce', error);
                });
            });
        }

        transport.on('connectionstatechange', function (state) {
            // The names the desktop's EngineBridge already understands. An
            // unrecognised string maps to Connecting and arms no watchdog, so
            // a dying call would hang open indefinitely.
            var mapped = state === 'connected' ? 'CONNECTED'
                : state === 'disconnected' ? 'RECONNECTING'
                : state === 'failed' ? 'DISCONNECTED'
                : state === 'closed' ? 'DISCONNECTING'
                : null;
            if (mapped) { emit('connection', { state: mapped }); }
        });
    }

    window.zillitMs = {

        /** Kotlin's answer to one ask. Exactly one settle per ask. */
        settle: function (askId, ok, payload) {
            var waiter = pending[askId];
            if (!waiter) { return; }
            delete pending[askId];
            if (ok) {
                waiter.resolve(payload || {});
            } else {
                waiter.reject(new Error((payload && payload.reason) || 'request failed'));
            }
        },

        /**
         * Loads the device from the router's capabilities.
         *
         * Once per device instance — mediasoup-client forbids a second load —
         * so a rejoin builds a new one rather than reusing this.
         */
        load: async function (routerRtpCapabilities) {
            try {
                device = new lib.Device();
                await device.load({ routerRtpCapabilities: routerRtpCapabilities });
                emit('ms-loaded', {
                    rtpCapabilities: device.rtpCapabilities,
                    sctpCapabilities: device.sctpCapabilities,
                    canProduceAudio: device.canProduce('audio'),
                    canProduceVideo: device.canProduce('video'),
                });
            } catch (e) {
                fail('device-load', e);
            }
        },

        /**
         * Builds both transports from parameters Kotlin already fetched.
         *
         * `iceServers` is applied at creation and not after: a transport's ICE
         * policy is fixed when it is built, so credentials arriving later do
         * nothing for the call that needed them.
         */
        createTransports: async function (sendParams, recvParams, iceServers) {
            try {
                var common = { iceServers: iceServers || [] };

                sendTransport = device.createSendTransport(Object.assign({}, sendParams, common));
                wireTransport(sendTransport, true);

                recvTransport = device.createRecvTransport(Object.assign({}, recvParams, common));
                wireTransport(recvTransport, false);

                emit('ms-transports-ready', {
                    sendTransportId: sendTransport.id,
                    recvTransportId: recvTransport.id,
                });
            } catch (e) {
                fail('create-transports', e);
            }
        },

        /** Publishes the microphone. Idempotent. */
        produceMic: async function (deviceId) {
            try {
                if (micProducer && !micProducer.closed) { return; }
                var constraints = deviceId ? { deviceId: { exact: deviceId } } : true;
                var stream = await navigator.mediaDevices.getUserMedia({ audio: constraints });
                localStream = stream;
                micProducer = await sendTransport.produce({ track: stream.getAudioTracks()[0] });
                emit('ms-producer', { kind: 'audio', producerId: micProducer.id });
            } catch (e) {
                fail('produce-mic', e);
            }
        },

        /** Publishes the camera. Idempotent. */
        produceCam: async function (deviceId) {
            try {
                if (camProducer && !camProducer.closed) { return; }
                if (!sendTransport) { warn('produce-cam', 'no send transport yet'); return; }
                var constraints = deviceId ? { deviceId: { exact: deviceId } } : true;
                var stream = await navigator.mediaDevices.getUserMedia({ video: constraints });
                camStream = stream;
                camProducer = await sendTransport.produce({ track: stream.getVideoTracks()[0] });
                emit('ms-producer', { kind: 'video', producerId: camProducer.id });
                if (window.zillitCall && window.zillitCall.attachLocalPreview) {
                    window.zillitCall.attachLocalPreview(stream);
                }
            } catch (e) {
                // A machine with no camera is a downgraded call, not a dead one.
                warn('produce-cam', e);
            }
        },

        /**
         * Publishes the screen as a SECOND video producer, marked in its
         * appData — the produce ask forwards that appData verbatim, which is
         * what lets every consumer tell a shared screen from a camera. The
         * camera producer is paused while the share runs, matching the
         * one-picture-at-a-time behaviour of the other platforms.
         */
        produceScreen: async function () {
            try {
                if (screenProducer && !screenProducer.closed) { return; }
                if (!sendTransport) { warn('produce-screen', 'no send transport yet'); return; }
                var stream = await navigator.mediaDevices.getDisplayMedia({ video: true });
                var track = stream.getVideoTracks()[0];
                screenProducer = await sendTransport.produce({
                    track: track,
                    appData: { share: true, screenShare: true, source: 'screen' },
                });
                if (camProducer && !camProducer.closed) {
                    try { camProducer.pause(); } catch (e2) { warn('pause-cam', e2); }
                    ask('pauseProducer', { producerId: camProducer.id }).catch(function () {});
                }
                // Chromium's own "Stop sharing" bar ends the track without
                // telling us; without this the UI would still claim to share.
                track.addEventListener('ended', function () { window.zillitMs.stopScreen(); });
                emit('ms-producer', { kind: 'screen', producerId: screenProducer.id });
                emit('screen-share', { sharing: true });
            } catch (e) {
                // A cancelled picker is a decision, not a failure.
                warn('produce-screen', e);
                emit('screen-share', { sharing: false });
            }
        },

        stopScreen: function () {
            var producer = screenProducer;
            screenProducer = null;
            if (!producer) { return; }
            try {
                var id = producer.id;
                producer.close();
                // The SFU is told deliberately — a producer closed only
                // locally lingers server-side until the transport dies.
                ask('closeProducer', { producerId: id }).catch(function () {});
            } catch (e) { warn('stop-screen', e); }
            if (camProducer && !camProducer.closed) {
                try { camProducer.resume(); } catch (e2) { warn('resume-cam', e2); }
                ask('resumeProducer', { producerId: camProducer.id }).catch(function () {});
            }
            emit('screen-share', { sharing: false });
        },

        /**
         * Takes on one consumer the SFU has offered.
         *
         * Kotlin has already accepted the protoo request by the time this runs
         * — the SFU abandons a `newConsumer` it gets no answer to, and the
         * answer must not wait on this side's media work.
         */
        consume: async function (params) {
            try {
                var consumer = await recvTransport.consume({
                    id: params.id,
                    producerId: params.producerId,
                    kind: params.kind,
                    rtpParameters: params.rtpParameters,
                    appData: params.appData || {},
                });
                consumers[consumer.id] = consumer;

                var stream = new MediaStream([consumer.track]);
                attach(consumer.id, params.peerId, consumer.kind, stream);

                emit('ms-consumer', {
                    consumerId: consumer.id,
                    peerId: params.peerId,
                    kind: consumer.kind,
                    // The SFU marks a screen share in appData; without it a
                    // shared screen is indistinguishable from a camera.
                    share: !!(params.appData && (params.appData.share || params.appData.screenShare)),
                });
            } catch (e) {
                fail('consume', e);
            }
        },

        closeConsumer: function (consumerId) {
            var consumer = consumers[consumerId];
            if (!consumer) { return; }
            try { consumer.close(); } catch (e) { warn('close-consumer', e); }
            delete consumers[consumerId];
            detach(consumerId);
        },

        setMic: function (muted) {
            if (!micProducer) { return; }
            try {
                if (muted) { micProducer.pause(); } else { micProducer.resume(); }
                // Server-side too: a local pause stops the RTP but the SFU
                // still lists the producer live, and it is the SFU's
                // consumerPaused/consumerResumed that carries the mute badge
                // to everyone else's screen.
                ask(muted ? 'pauseProducer' : 'resumeProducer', { producerId: micProducer.id })
                    .catch(function () {});
            } catch (e) { warn('set-mic', e); }
        },

        setCam: function (enabled) {
            // Enabling with no producer is a first camera-on in an audio call:
            // produce rather than resume nothing, which is what made "turn
            // video on" a silent no-op on Line 1.
            if (!camProducer || camProducer.closed) {
                if (enabled) { window.zillitMs.produceCam(''); }
                return;
            }
            try {
                if (enabled) { camProducer.resume(); } else { camProducer.pause(); }
                ask(enabled ? 'resumeProducer' : 'pauseProducer', { producerId: camProducer.id })
                    .catch(function () {});
                if (window.zillitCall) {
                    if (enabled && camStream && window.zillitCall.attachLocalPreview) {
                        window.zillitCall.attachLocalPreview(camStream);
                    } else if (!enabled && window.zillitCall.clearLocalPreview) {
                        window.zillitCall.clearLocalPreview();
                    }
                }
            } catch (e) { warn('set-cam', e); }
        },

        /** The local microphone, for the call recorder's mix. */
        getLocalAudioTrack: function () {
            return localStream ? (localStream.getAudioTracks()[0] || null) : null;
        },

        /** Tears everything down. Safe to call twice. */
        leave: function () {
            Object.keys(consumers).forEach(function (id) {
                try { consumers[id].close(); } catch (e) { /* already gone */ }
            });
            consumers = {};
            [micProducer, camProducer, screenProducer].forEach(function (producer) {
                if (producer) { try { producer.close(); } catch (e) { /* already gone */ } }
            });
            if (screenProducer) { emit('screen-share', { sharing: false }); }
            micProducer = null;
            camProducer = null;
            screenProducer = null;
            [sendTransport, recvTransport].forEach(function (transport) {
                if (transport) { try { transport.close(); } catch (e) { /* already gone */ } }
            });
            sendTransport = null;
            recvTransport = null;
            [localStream, camStream].forEach(function (stream) {
                if (stream) { stream.getTracks().forEach(function (track) { track.stop(); }); }
            });
            localStream = null;
            camStream = null;
            if (window.zillitCall && window.zillitCall.clearLocalPreview) {
                window.zillitCall.clearLocalPreview();
            }
            device = null;
            pending = {};
            emit('connection', { state: 'DISCONNECTING' });
        },
    };

    /*
     * Media element plumbing lives with the tiles the page already draws.
     *
     * Reported rather than silently skipped when the sink is missing: this
     * guard used to be a bare `if`, and when the functions did not exist every
     * remote track was dropped here with nothing in any log — a call that was
     * silent and looked healthy.
     */
    function attach(consumerId, peerId, kind, stream) {
        if (!(window.zillitCall && window.zillitCall.attachRemote)) {
            fail('attach', 'the page has no attachRemote; remote media cannot be heard');
            return;
        }
        window.zillitCall.attachRemote(consumerId, peerId, kind, stream);
    }

    function detach(consumerId) {
        if (window.zillitCall && window.zillitCall.detachRemote) {
            window.zillitCall.detachRemote(consumerId);
        }
    }

    emit('ms-ready', {});
}());
