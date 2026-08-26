# Line 1 — mediasoup calling: the protocol, as the phones speak it

The desktop implements Line 2 (Agora) today. This is the map for Line 1, taken from the iOS
`Calling/Engine/Mediasoup` sources and checked line by line against them. It exists because a
wrong field name in a signalling protocol does not fail loudly — it produces a call where two
people sit in different rooms hearing nothing.

**Reference**: the iOS `reply-option-outside-app` branch, not `main`. `main`'s `Calling/` tree
dates from Sept 2025 and predates the engine rewrite; measuring against it produces confident,
wrong answers.


## Signalling — the protoo channel

### VERDICT — overall accuracy of the signalling map

I opened every cited file and checked every quote. The map is largely accurate: URL shape, headers, envelope, join order, request payloads, restartIce dual-shape, backoff ladder, storm guards, close codes, keepalive, probe-then-rejoin and fresh-peerId rationale all check out verbatim, including line numbers. FOUR facts are wrong or unsubstantiated and are corrected below (closeProducer trigger; produce appData; the reconnect-ladder scope; the muteAudio notification's usefulness). Several material omissions are added.

*Evidence:* All Swift line references below were re-read directly; unchanged facts are marked KEPT.

### CORRECTION 1 — `closeProducer` is NOT sent on switchCamera

The map says closeProducer is "sent on switchCamera". Wrong. There is exactly ONE closeProducer call site in the engine, in `disableCamImpl()` — i.e. camera-off / screen-share teardown. `switchCamera()` performs a local `capturer.startCapture(...)` flip and issues NO protoo traffic at all (and is suppressed outright while screen sharing). Corrected fact: `closeProducer` data = `{"producerId": <String>}`, fire-and-forget (`try? syncRequest`), sent only when the local video producer is torn down.

*Evidence:* MediasoupCallEngine.swift:3888 `try? protooPeer?.syncRequest("closeProducer", data: ["producerId": producer.id])` inside `disableCamImpl` (:3879); `grep -n closeProducer` returns line 3888 and nothing else. `func switchCamera()` at :1335-1352 contains no protoo call, and :1340 `guard !isScreenSharingActive else { ... return }`.

### CORRECTION 2 — `produce` appData: the screen marker never reaches the SFU on this path

The map states appData is empty on produce "even for a screen-share producer; the screen marker lives on the mediasoup-client producer's own appData ... which the SFU relays to consumers". The second half is unsubstantiated and contradicted by the code: `handleTransportProduce` RECEIVES the producer's appData as its `appData: String` parameter and then NEVER references it, hardcoding `"appData": [String: Any]()` into the produce request. So the `{"share":true,"screenShare":true,"source":"screen"}` string built at :3858-3860 is handed to mediasoup-client but dropped at the signalling boundary — nothing carries it to the server, and therefore nothing can relay it to consumers. Either the SFU derives share-ness some other way (not determinable from this code) or this is a live iOS bug. DESKTOP IMPLICATION: forward the produce callback's appData verbatim instead of `{}` — that is the correct mediasoup-demo behaviour and is what makes the receiver's `appDataIsShare` scan (which reads `newConsumer.appData`) actually fire.

*Evidence:* MediasoupCallEngine.swift:4301 `func handleTransportProduce(transport:kind:rtpParameters:appData: String, callback:)`; :4308-4313 the request body — `"transportId"`, `"kind"`, `"rtpParameters": rtpDict`, `"appData": [String: Any]()`; grepping the function body for `appData` returns only the signature and the empty literal. Producer-side marker at :3858-3860.

### CORRECTION 3 — the reconnect ladder does NOT run on a clean close frame

The map presents the 600-retry ladder as the response to disconnection generally. It is narrower: `didCloseWith` on the CURRENT task — any clean close frame, whatever the code — sets `closed = true`, resets the strategy, stops keepalive and fires `onClose()` (or `onSessionReplaced` if the code/reason matches), with NO reconnect scheduled. The ladder only ever runs from `handleDisconnection`, i.e. the ABRUPT paths: a failing `receive()`, `didCompleteWithError`, a failed send, or the keepalive declaring the socket half-open. Desktop implication: a server that closes cleanly (e.g. 1000/1001 on a rolling deploy) terminates the call on iOS rather than reconnecting — match that or deliberately diverge, but know which you are doing.

*Evidence:* ProtooWebSocketTransport.swift:619-649 — inside `didCloseWith`: `self.closed = true` (:627), `self.retryStrategy.reset()` (:629), then `onSessionReplaced` or `onClose` (:644-648); no `scheduleReconnect()` call anywhere in that method. `scheduleReconnect()` is called only at :392, inside `handleDisconnection`.

### CORRECTION 4 — the `muteAudio` notification's peerId cannot match an SFU peerId

The map flags the muteAudio/recording peerId asymmetry as undetermined. It is determinable enough to warn on: `muteAudio` sends `peerId: CallConfig.shared.currentDeviceId` on BOTH lines (no isAgora branch), whereas the mediasoup peerId is `"<userId>:<deviceId>"` and the recording notification deliberately switches to `currentUserId` on mediasoup with a comment explaining that the deviceId form silently failed to match. The receiver's `muteAudio` handler forwards `data["peerId"]` verbatim into `peerStateChanged(peerId:)`. So on Line 1 the mute banner from this notification will not resolve. The signal that actually works is `consumerPaused` / `consumerResumed`, which map to `isMuted: true` / `isMuted: false` keyed on the holder's real peerId. Desktop: drive remote-mute UI from consumerPaused/consumerResumed; treat muteAudio as legacy/Agora.

*Evidence:* CallingViewModel.swift:1763-1766 `sendPeerNotification(method: "muteAudio", data: ["peerId": CallConfig.shared.currentDeviceId, "muted": muted])` — no line branch; contrast :3486-3489 `let recordingPeerId = isAgora ? currentDeviceId : currentUserId` with the comment at :3480-3485 ("the recording banner ... silently failed for every Mediasoup-to-Mediasoup case"). MediasoupCallEngine.swift:4558-4564 muteAudio handler; :4499-4504 consumerPaused → `isMuted: true`; :4517-4521 consumerResumed → `isMuted: false`.

### KEPT — WebSocket URL shape

Verified verbatim, including the rejoin dial omitting both optional params. `wss://<host>/?roomId=<roomId>&peerId=<peerId>`, no percent-encoding of the `:` inside peerId, `&prev_session=` appended before `&token=`. prev_session is base64url of a JSON record with `+`→`-`, `/`→`_`, `=` stripped.

*Evidence:* MediasoupCallEngine.swift:736, :745, :756, :4826 — all four lines match the quoted text character-for-character. CallConfig.swift:659-673 `consumePreviousBase64()` with the three `replacingOccurrences` calls.

### KEPT — handshake headers, with an added caveat

`Sec-WebSocket-Protocol: protoo` and `Origin: ios` set manually; `timeoutInterval = 30`; no Authorization header. The map's caution about Origin stands. ADDED: in a browser page the subprotocol is set correctly via `new WebSocket(url, "protoo")`; only Origin is unspoofable. If the SFU does validate Origin, that alone kills the in-page WebSocket approach and forces the Kotlin-side socket.

*Evidence:* ProtooWebSocketTransport.swift:236-239.

### KEPT — SFU host resolution (three tiers)

Compile-time defaults per scheme (QA `calling-sfu-qa.zillit.com`, Dev `mediasoup-dev.zillit.com`, prod `calling-sfu-prod.zillit.com`); per-call override from `mediasoup_server_url` with alias `sfu_url` (canonical preferred); `normalizeSfuHost` strips any `scheme://` by regex, truncates at the first `/`, keeps host AND port. ADDED: the VM has a recovery layer — if `session.sfuHost` is empty it re-reads the elected host from the persisted incoming push and sticks it back on the session before dialling, logging an `.error` if still unrecoverable. Port that recovery, not just the normalizer.

*Evidence:* CallConfig.swift:72-81; MediasoupCallEngine.swift:114-123 (`normalizeSfuHost`), :125-128 (`effectiveSfuHost`); CallModels.swift:488-490, :538-539; CallingViewModel.swift:541-557 `electedSfuHostForDial`.

### KEPT — peerId and roomId construction

peerId = `"<peerUserId>:<currentDeviceId>"` with the five-step fallback chain exactly as described (callProjectUserId → CallKit push currentProjectUserId → Util.getCurrentProjectUserId() → CallConfig.currentUserId → currentDeviceId). `localIdentity = peerUserId` is the filtering key. roomId = `session.inviteCode.isEmpty ? session.callUuid : session.inviteCode`.

*Evidence:* MediasoupCallEngine.swift:711-724; CallingViewModel.swift:456-458.

### KEPT — protoo envelopes (request / response / notification)

Request `{"request":true,"method":…,"id":<Int>,"data":{…}}` with `id = Int.random(in: 1..<10_000_000)`. Success `{"response":true,"id":…,"ok":true,"data":{…}}`; error `{"response":true,"id":…,"ok":false,"errorCode":<Int>,"errorReason":<String>}`. Notification `{"notification":true,"method":…,"data":{…}}`. ADDED to the client-synthesized error list: code -1 "session replaced" (rejects all pendings when the SFU evicts the session) and -1 "Peer is closed" (request attempted after close).

*Evidence:* ProtooMessage.swift:87-96, :99-106, :109-117, :120-126. ProtooPeer.swift:121 ("Peer is closed"), :169 (408), :179 (-2 "send failed: …"), :254 (-1 "peer closed"), :399 (-1 "session replaced"), :222 (-1 "No response received").

### KEPT — the lenient parser fallback is real and is the top interop risk

Confirmed word for word, including the comment. After the three flag branches: id+method → server request; id alone → response with `ok` inferred as `object["data"] != nil`; method alone → notification. Stock protoo-client JS `Message.parse` is strict. If the SFU ever emits unflagged frames, an unmodified protoo-client build drops them silently. Verify against a live capture before committing.

*Evidence:* ProtooMessage.swift:56-79; :68 `let ok = object["ok"] as? Bool ?? (object["data"] != nil)`; :56-57 comment "Fallback: Server may not include \"response\"/\"notification\"/\"request\" type flags."

### KEPT + REFINED — request timeout is 10s, but there are TWO independent 10s timers

The flat-10s fact and the mis-ported-Android-formula rationale check out verbatim. REFINEMENT the map missed: `syncRequest(_:data:timeout:)` takes a `timeout` parameter defaulting to 10 that bounds only its semaphore wait; the inner `request()` ALWAYS uses a hardcoded `let timeout: TimeInterval = 10`. So passing a larger syncRequest timeout does not extend the request — the 408 fires at 10s regardless. Also note `request()`'s doc comment says "Completion called on main queue" and that is FALSE: completions fire on the transport's delivery queue or on the timeout queue. Do not port that comment's promise.

*Evidence:* ProtooPeer.swift:127-139 (comment + `let timeout: TimeInterval = 10`), :199-214 (`timeout: TimeInterval = 10`, semaphore wait, 408 throw), :117 doc comment vs :151-170 completion invocation; ProtooWebSocketTransport.swift:291-293 delivery on `deliveryQueue`.

### KEPT — timeout/completion arbitration via removal

Exactly one path may complete a request: every path removes the pending entry under the lock and only fires if it won the removal (`takePending`). This exists because `DispatchWorkItem.cancel()` cannot stop an already-dequeued item, and a double-fire broke `fetchBothTransportInfos`'s DispatchGroup balance. Port this arbitration — a naive Kotlin map+timer will reproduce the bug.

*Evidence:* ProtooPeer.swift:159-170 (comment + `guard let self = self, self.takePending(requestId) != nil else { return }`), :270-280 `takePending`.

### KEPT — the join sequence, exact order

getRouterRtpCapabilities → `device.load(routerCapsAsString)` → read the DEVICE's own rtpCapabilities + sctpCapabilities → `ensureTurnCredentialsLoaded()` (local, no protoo traffic) → two concurrent createWebRtcTransport (DispatchGroup, single 10s bound) → build send transport, arm watchdog, build recv transport, arm watchdog → `join` → parse `peers` → emit joinSuccess + state=1 + set hasJoinedOnce → `produceAudioVideo()`. Both transports exist before `join` is sent; connectWebRtcTransport is emitted lazily by the transport connect callback.

*Evidence:* MediasoupCallEngine.swift:2866-3056 — :2870 getRouterRtpCapabilities, :2877 device.load, :2881-2884 device caps, :2900 ensureTurnCredentialsLoaded, :2911 fetchBothTransportInfos, :2912/:2918 create send/recv, :2922-2933 join, :2968 peers parse, :3056 produceAudioVideo.

### KEPT — getRouterRtpCapabilities

Sent with no data content; the response IS the router caps object, re-serialized to a JSON string and passed straight to `device.load(with:)`. Not nested under an `rtpCapabilities` key.

*Evidence:* MediasoupCallEngine.swift:2870-2877.

### KEPT — createWebRtcTransport (x2, concurrent)

Send: `{"forceTcp": false, "producing": true, "consuming": false, "sctpCapabilities": <device sctpCaps>}`. Recv: same with `producing:false, consuming:true`. Response fields read: `id`, `iceParameters`, `iceCandidates`, `dtlsParameters`, `sctpParameters`, each re-serialized to a JSON string, missing key → literal `"{}"`. Both issued in parallel with a shared 10s bound; on timeout throws 408 "createWebRtcTransport pair timed out after 10s".

*Evidence:* MediasoupCallEngine.swift:3148-3181; field extraction at :3186-3190 (send) and :3298-3302 (recv); `jsonString` fallback at :4651-4658.

### KEPT — join request and response

data = `{"forceTcp":false, "producing":true, "consuming":true, "displayName":<currentUserName>, "device":{"flag":"ios","name":<UIDevice.current.name>,"version":"1.0.0"}, "rtpCapabilities":<DEVICE caps>, "sctpCapabilities":<DEVICE caps>}`. Response `{"peers":[…]}`; each peer read for `id`, `displayName`, and the multi-spelling flags `raisedHand`/`raise_hand` and `screenShare`/`sharingScreen`/`screen_share`/`sharing_screen`. Own-identity peers are filtered out of the enumeration; identities present locally but absent from the enumeration are retired (rejoin diff).

*Evidence:* MediasoupCallEngine.swift:2922-2930; DeviceInfo.swift:11-25; peers parsing :2968-3052, flag spellings :3027-3034, self-filter :2987-2990, vanished diff :2974-2983.

### KEPT — connectWebRtcTransport with 3-attempt retry

data = `{"transportId": <String>, "dtlsParameters": <object>}`; response ignored (only `.failure` is handled). Retries at `attempt * 0.5s` (0.5s, 1.0s), each guarded on the id still being the current send or recv transport; after 3 failures escalates to `restartTransportICE(trigger: "connectRequestFailed")`. The "single biggest no-audio cause; web had the identical bug" comment is verbatim.

*Evidence:* MediasoupCallEngine.swift:4258-4261 `handleTransportConnect`, :4263-4269 comment, :4271-4299 `sendConnectWebRtcTransport`.

### KEPT — restartIce and its non-standard response

data = `{"transportId": <String>}`. The comment is verbatim: the Zillit protoo server returns the new ICE parameters DIRECTLY as the response payload — `{"usernameFragment":..,"password":..,"iceLite":..}` — NOT nested under `iceParameters`. Client prefers a nested `iceParameters` if present, else uses the response object itself when it carries `usernameFragment` or `password`, else treats as missing and returns. Also: the callback captures only the transport ID and re-looks-up the live transport, bailing if a rejoin replaced it.

*Evidence:* MediasoupCallEngine.swift:1004 (`peer.request("restartIce", data: ["transportId": transportId])`), :1029-1044.

### KEPT — producer and consumer control requests

`pauseProducer`/`resumeProducer` — `{"producerId": <String>}`, with the stale-intent guard (`guard localAudioMuteIntent == mute else { return }` before send, again after ack) and `serverAckedMuteState` recorded BEFORE the supersede check on success. Retries at `attempt * 0.5s`, 3 attempts, then the uplink watchdog re-sends while ack ≠ intent. `pauseConsumer`/`resumeConsumer`/`requestConsumerKeyFrame` — `{"consumerId": <String>}`. `setConsumerPreferredLayers` — `{"consumerId":…, "spatialLayer": 0|2, "temporalLayer": 0|2}`, always paired with a `requestConsumerKeyFrame`. Every new consumer is resumed via `resumeConsumerWithRetry` (3 attempts, `attempt*0.5s`), then parked in `pendingConsumerResumes`.

*Evidence:* MediasoupCallEngine.swift:1215-1218, :1233 (`serverAckedMuteState = mute`), :1234 supersede guard, :1244-1247 backoff; :2019-2022, :2041-2046, :2087, :2102, :2107; :3580-3623 `resumeConsumerWithRetry`.

### ADDED — parked-resume flush has TWO triggers, not one

The map says parked consumer resumes are re-issued "when the recv transport reconnects". True but incomplete: they are also flushed periodically from the stats tick (every 5th tick) whenever the parked set is non-empty. Both matter — the transport-connected trigger never fires if the recv transport was already connected when the resume failed.

*Evidence:* MediasoupCallEngine.swift:1827-1829 (`if direction == "recv", s == "connected" || s == "completed" { self.flushPendingConsumerResumes() }`); :3435-3437 periodic flush.

### KEPT — app-level Zillit request extensions

All sent through `sendPeerRequest`, which is a fire-and-forget `try? syncRequest` on `DispatchQueue.global(qos: .userInitiated)` with the response discarded. `toggleHandRaise` `{"raisedHand": Bool}` (the `raise_hand` spelling mentioned elsewhere in the VM refers to the FIREBASE field, not this payload — I checked). `startScreenShare` `{}` / `stopScreenShare` `{}` — the engine comment states the server returns 500 for startScreenShare, unimplemented server-side, and is explicitly NOT the mechanism that makes a share visible. `startClientRecording`/`stopClientRecording` `{"peerId": <userId>, "userId": <userId>, "deviceId": <deviceId>, "recording": Bool}`.

*Evidence:* MediasoupCallEngine.swift:2510-2519; CallingViewModel.swift:2046-2048, :3083, :3162/:3345/:6266, :3507-3512, :3578-3583; MediasoupCallEngine.swift:3854 comment ("the separate `startScreenShare` protoo request the server returns 500 for is NOT a substitute — it's unimplemented server-side"); CallingViewModel.swift:5144 confirms `raise_hand` is the Firebase key.

### KEPT — client-sent notifications (with Correction 4 applied)

Exactly two: `muteAudio` `{"peerId": <currentDeviceId — see Correction 4>, "muted": Bool}` and `recording` `{"peerId": <currentUserId on mediasoup>, "recording": Bool, "callUuid": …, "deviceId": …, "userId": …}`. `sendPeerNotification` is a plain `peer.notify` — no id, no ack.

*Evidence:* MediasoupCallEngine.swift:2501-2508; CallingViewModel.swift:1763-1766, :3486-3494, :3565-3575.

### KEPT + REFINED — server→client requests

Only `newConsumer` and `newDataConsumer` arrive as protoo REQUESTS; both answered `accept(nil)` → `{"response":true,"id":…,"ok":true,"data":{}}`. Anything else is rejected 403 `"unknown method: <method>"`. newConsumer is accepted FIRST, synchronously on the delivery thread, before the workQueue hop — the comment about resume-on-accept, the SFU abandoning a timed-out consumer, and the 8.6–11.6s measured consume() is verbatim. REFINEMENT: the 403 reject is itself dispatched onto `workQueue`, so a starved queue also delays rejections; and an engine already `closed` drops a server request with NEITHER accept nor reject (logged only).

*Evidence:* MediasoupCallEngine.swift:4976-5017 — :4979-4984 closed-drop, :4988-5006 newConsumer accept-first, :5007-5008 newDataConsumer, :5009-5015 default reject inside `workQueue.async`.

### KEPT + REFINED — newConsumer payload and share detection

Fields read: `id` (consumerId), `producerId`, `peerId`, `kind`, `rtpParameters` (absent → dropped as unfixable-malformed), `appData`. REFINEMENT: the share scan is gated on `kind == .video` — an audio-only screen share payload will never set the flag. Order of guards also matters for a port: self-identity filter → no recv transport (PARKED, retried, max 3 attempts, not rejected) → missing rtpParameters (dropped). Consume is called with `appData: nil`, so the payload's appData is used only for the share scan. Also handled as a plain notification on the same path.

*Evidence:* MediasoupCallEngine.swift:3943-3947 field extraction; :3966-3970 self filter; :3972-3980 park; :3982-3986 rtpParameters drop; :3996-4001 consume with `appData: nil as String?`; :4109-4126 `appDataIsShare` (`guard kind == .video, let appData = data["appData"] as? [String: Any]`); :4328-4330 notification case; :4236-4243 `parkNewConsumer` cap of 3.

### KEPT + REFINED — received-notification list

The handled switch is exactly: newConsumer · newDataConsumer (`break`) · newPeer `{id, displayName}` · peerClosed `{peerId}` · consumerClosed `{consumerId}` · consumerPaused `{consumerId}` · consumerResumed `{consumerId}` · activeSpeaker `{peerId}` · producerScore `{producerId, score}` · peerRaisedHand `{peerId}` · peerLoweredHand `{peerId}` · muteAudio `{peerId, muted}` · recording `{peerId, recording}` · peerRecordingStarted `{peerId}` · peerRecordingStopped `{peerId}` · peerScreenShareStarted `{peerId}` · peerScreenShareStopped `{peerId}` · endCall (no payload; → state 4) · default: log only. The `id` vs `peerId` key asymmetry between newPeer and peerClosed is real. REFINEMENT: the map's 'not handled' bullet is self-contradictory — it lists activeSpeaker as adjacent-and-absent while also listing it as handled. activeSpeaker IS handled (:4525). Genuinely unhandled: `downlinkBwe`, `peerDisplayNameChanged`, `consumerScore`, `dataConsumerClosed`.

*Evidence:* MediasoupCallEngine.swift:4325-4615, case labels at :4328/4332/4335/4360/4401/4491/4508/4525/4534/4540/4549/4558/4566/4574/4581/4588/4599/4607, `default:` at :4613-4614; newPeer reads `data["displayName"]` at :4347; producerScore normalization at :1517-1523 `extractScores`.

### KEPT — backoff ladder and its rationale

`RetryStrategy(retries: 600, factor: 2, minTimeout: 800, maxTimeout: 4000)`; `interval = min(minTimeout * factor^retryCount, maxTimeout)`; retryCount starts at 1, `reset()` sets it to 0, and `retried()` is called AFTER the reconnect fires. So pre-first-open: 1600, 3200, 4000, 4000…; post-successful-open (didOpen calls reset): 800, 1600, 3200, 4000…. The abandoned defaults (10/2/1000/8000) exhausted in ~70s and then called onClose permanently — shorter than the app's own 30s/90s windows. Scope caveat in Correction 3.

*Evidence:* ProtooWebSocketTransport.swift:34, :46-54, :116-138, :552-573 (`retryStrategy.retried()` at :570, after `newWebSocket()`), :594 `self.retryStrategy.reset()` in didOpen.

### KEPT — the three reconnect storm guards

(1) `pendingReconnect` coalescing, set in `scheduleReconnect`, cleared in `newWebSocket` and `didOpen`; (2) install-new-session-BEFORE-retiring-old so the old task's `cancelled` reads as stale, plus `failedTask !== self.task` filtering; (3) `didOpenWithProtocol` and `didCloseWith` both ignore events from rotated-away tasks. All verbatim. A JS port gets (2) for free from the browser but still needs (1) and (3).

*Evidence:* ProtooWebSocketTransport.swift:74-83, :226, :241-263, :333-336, :559, :588-591, :623-626.

### KEPT — no protoo-level resume; probe-then-fresh-peerId-rejoin

On WS reopen with `hasJoinedOnce`: emit state=1 immediately, then one `restartIce` on `recvTransport?.id ?? sendTransport?.id` with a hard 4.0s `workQueue.asyncAfter` deadline and a `settled` latch. ACK → in-place ICE restart of BOTH transports plus `reconcileOverlapAudioGuard`; producers/consumers kept. Error or timeout → `rejoinAfterReconnect()`: bumpSessionGeneration, resetForRejoin (closes producers/consumers/transports, clears parked newConsumers and parked resumes, nils serverAckedMuteState, creates a fresh `Mediasoup.Device()` because `device.load` is once-per-instance, sets hasJoinedOnce=false), fresh peerId `"<identity>:<deviceId>-r-<8-char UUID prefix>"`, close the peer, open a new WS, joinImpl from scratch. `livePeersByIdentity` deliberately survives.

*Evidence:* MediasoupCallEngine.swift:4686-4706 onOpen; :4720-4764 `probeThenRejoinAfterReconnect` (:4763 `workQueue.asyncAfter(deadline: .now() + 4.0) { settle(false, "probe timeout 4s") }`); :4795-4830 rejoin (:4810-4813 fresh peerId); :4837-4964 resetForRejoin (:4957 `device = Mediasoup.Device()`, :4961 `hasJoinedOnce = false`, :4919 livePeersByIdentity survival comment).

### KEPT — why the peerId must change on rejoin

Same-peerId rejoin made the server evict the old peer and broadcast `peerClosed{peerId}`, which tripped the remote's "all others left" path into a symmetric endCall the local side cannot guard (the remote never disconnected, so it has no settle window). A fresh peerId creates a genuinely new server peer; the old one times out on mediasoup's ~30s inactivity timer without firing peerClosed. The user-identity prefix is preserved so remotes rebind the existing tile.

*Evidence:* MediasoupCallEngine.swift:4774-4791 (doc comment), :4803-4814.

### KEPT — terminal close codes 4000 and 4409

4000 ("closed by protoo-server") and 4409 ("replaced-by-other-device") end the session terminally. Detection is belt-and-braces: by code, by case-insensitive substring against `["closed by protoo-server", "session replaced", "duplicate session", "peer reconnected", "replaced-by-other-device"]`, or by reading `closeCode`/`closeReason` back off the task on the abrupt path. The task-fallback check runs BEFORE the pendingReconnect dedup. At the engine, a reason matching "replaced-by-other-device" takes an unconditional silent-teardown branch that must never reach the recoverable-rejoin logic; a plain 4000 falls through to the network-drop disambiguation below it.

*Evidence:* ProtooWebSocketTransport.swift:421-446, :452-455, :468-489, :359-382; MediasoupCallEngine.swift:5071-5090.

### KEPT — 20s raw WebSocket ping keepalive

`keepaliveIntervalSeconds = 20`, DispatchSourceTimer armed in `didOpenWithProtocol`, torn down on any disconnect and in `close()`. If `awaitingPong` is still true at the next tick, or the ping fails to send, the socket is declared half-open and routed through `handleDisconnection`. The pong callback is guarded on `currentTask === self.task`. Rationale comment (CGNAT half-open, media rides UDP independently, "detection is the whole fix") verbatim. There is NO protoo-level ping method anywhere in this codebase.

*Evidence:* ProtooWebSocketTransport.swift:85-99, :493-548 (:531 `currentTask.sendPing { … }`), :597 `self.startKeepalive()`, :340 stopKeepalive on drop, :210 stopKeepalive in close().

### KEPT + STRENGTHENED — the browser-ping blocker, and the already-available fix

The blocker is real: the WHATWG WebSocket API exposes no ping/pong, and Chromium sends no periodic pings of its own, so an in-page socket loses the 20s half-open detector — the exact mechanism that converts a silently-dead socket into a recoverable reconnect. STRENGTHENED on option (b): running the WebSocket in Kotlin needs no new dependency. `io.socket:socket.io-client` is already a jvmMain dependency of `core/socket` (it bundles OkHttp, whose `WebSocket` supports `pingInterval`), and `io.ktor:ktor-client-okhttp` is already in the version catalog. Option (b) costs you re-implementing ProtooPeer's request/response/timeout/arbitration layer in Kotlin (~400 lines, and the arbitration subtlety above is easy to get wrong) but buys back parity on ping-based half-open detection, and it also sidesteps the Origin-header question entirely. Option (a) — an app-level heartbeat — requires a server change: no ping method exists in this vocabulary.

*Evidence:* ProtooWebSocketTransport.swift:85-99, :531; /Users/vivekmishra/Documents/GitHub/ZillitDesktop/core/socket/build.gradle.kts jvmMain `implementation(libs.socket.io.client)`; /Users/vivekmishra/Documents/GitHub/ZillitDesktop/gradle/libs.versions.toml:75 `ktor-client-okhttp`, :100 `socket-io-client`.

### KEPT — bundling mediasoup-client into the existing page

Confirmed. The page loads its SDK as a flat local file beside call.html (`agora-rtc-sdk-ng-4.24.2.js`, 1.37 MB, no CDN), the Kotlin↔JS bridge is one-line JSON over `window.cefQuery` with a `window.zillitCall` command surface, and mediasoup-client/protoo-client would need a bundling step the repo does not currently have. Nothing in the protocol itself is browser-hostile.

*Evidence:* /Users/vivekmishra/Documents/GitHub/ZillitDesktop/desktopApp/src/main/resources/callengine/call.html:4-6; call.js:38-44 `function send(event) { window.cefQuery({ request: JSON.stringify(event) }); }`; call.js:400 `window.zillitCall = {`; directory listing shows the SDK as a flat local file.

### KEPT — delivery must not block and must preserve order

Parsed messages are delivered on a dedicated serial `com.zillit.protoo.delivery` queue, off main and off the receive loop. The bypass set — notifications routed around the serial workQueue because a slow consume starved them — is exactly `["producerScore", "peerRaisedHand", "peerLoweredHand", "peerRecordingStarted", "peerRecordingStopped"]`, with `activeSpeaker` deliberately excluded because it mutates `peerUidMap` and is only cosmetic.

*Evidence:* ProtooWebSocketTransport.swift:108-112, :281-293; MediasoupCallEngine.swift:5035-5039 `workQueueBypassNotifications`, :5029-5034 activeSpeaker-exclusion comment.

### ADDED — TURN credentials are an out-of-band REST call, and gate transport creation

The map omits this and it will bite an implementer. TURN/STUN credentials come from the Zillit backend over HTTP, not protoo: `GET /api/v2/webrtc/turn-credentials` with the standard calling-API headers (`moduledata`, `iosversion`, `bodyhash`), returning `data.iceServers[]` (each with `urls[]`, `username`, `credential`) plus `ttl` (3600). The backend proxies Cloudflare's `/credentials/generate`; the Cloudflare token never reaches the client. `joinImpl` blocks on `ensureTurnCredentialsLoaded()` BEFORE createWebRtcTransport because a transport's `iceTransportPolicy` is locked at creation — credentials that land later do nothing for the current call, which was the production cellular-failure symptom. A desktop port must fetch these before building transports on both the initial join and every rejoin.

*Evidence:* MediasoupTurnCredentialsService.swift:17-42 (the endpoint contract block), :44-54 (Cloudflare note); MediasoupCallEngine.swift:2888-2900 (the locked-policy rationale + `ensureTurnCredentialsLoaded()`).

### Open questions

- Does the Zillit SFU actually validate the WebSocket Origin header? iOS sets `Origin: ios` explicitly (ProtooWebSocketTransport.swift:238) and a KCEF page cannot override its own Origin. This is not determinable from the iOS code and must be answered server-side before committing to an in-page WebSocket — it is a second, independent reason the socket may have to live in Kotlin.
- Does the SFU ever emit protoo frames without the `request`/`response`/`notification` flag? The iOS parser has an explicit lenient fallback for exactly that; stock protoo-client JS would drop those frames silently. Needs a live wire capture, not code reading.
- How does the SFU learn that a video producer is a screen share, given that iOS sends `"appData": {}` on every `produce` (Correction 2)? Either the server infers it elsewhere, or iOS screen-share signalling is currently broken on the mediasoup line and the desktop should forward the produce callback's appData verbatim rather than copying iOS.
- Is `startScreenShare` still 500ing server-side? The engine comment says unimplemented (MediasoupCallEngine.swift:3854) but that is dated; if it has since shipped, the desktop's share path changes materially.
- Does the SFU treat `muteAudio`'s deviceId-shaped peerId as valid, or is that notification dead on Line 1 as the code suggests (Correction 4)? Worth confirming before the desktop implements it at all.


## Media — the mediasoup pipeline

### VERDICT — 20 of 23 facts survive; 3 are wrong; 6 have wrong line cites

Content-level errors found in exactly three places: (a) FACT 15's claim that the protoo `startScreenShare` request is the peer-signalling path — the engine's own comment says the server returns 500 for it and it is unimplemented server-side; (b) FACT 18's claim that `requestConsumerKeyFrame` follows EVERY resume — the initial post-consume resume sends none; (c) FACT 21/22's recording payloads, which are 5-field and 4-field, not the {peerId, recording} pair stated. Everything else checks out on content. Line-number drift (harmless but listed below) affects facts 0, 3, 5, 12, 14, 20. Facts 1, 2, 4, 6, 7, 8, 9, 10, 11, 13, 16, 17, 19, 22 (verb inventory), 23 are confirmed as written.

*Evidence:* Full per-fact verification detailed in the items below.

### FACT 0 — CORRECTED: the TURN gate is BOUNDED at 2.0s and there IS a late-injection path

The analyst is right that `ensureTurnCredentialsLoaded()` blocks before transport creation, and right that iceTransportPolicy is locked at construction. But two things are wrong or missing, and both matter to an implementer. (1) The wait is a semaphore with a 2.0s deadline — on timeout it RETURNS and the transports are built with `iceServers: nil`. (2) iOS then does exactly what the analyst says not to do, as a recovery: the async Task continues and calls `injectLateTurnCredentialsLocked(fetched)`, which applies `updateICEServers` + a protoo `restartIce` to a transport that was built credential-less. So the correct desktop guidance is: resolve ICE servers before `createSendTransport` on the happy path, AND implement the late-injection fallback (mediasoup-client JS has no `updateIceServers`; you would reach the underlying RTCPeerConnection via `transport.handler` or just call `transport.restartIce()` after `setRTCConfiguration`-equivalent — VERIFY this against your mediasoup-client version, I have not).

Also: relay is NOT forced merely because credentials loaded. `shouldForceRelay()` requires creds AND one of (churn escalation | global flag `mediasoupForceRelayEnabled` | `mediasoupRelayOnCellularEnabled` && cellular). Default on Wi-Fi desktop = `.all`.

*Evidence:* MediasoupCallEngine.swift:2900 `ensureTurnCredentialsLoaded()` (analyst cited :2896 — off by 4). Comment quoted correctly at :2887-2895. :544 `private static let turnCredsJoinTimeout: TimeInterval = 2.0`. :3121-3124 `if sem.wait(timeout: .now() + Self.turnCredsJoinTimeout) == .timedOut { ... return }`. :3112-3120 the late-arrival Task → `self?.injectLateTurnCredentialsLocked(fetched)`. :648-653 `shouldForceRelay()`: `guard turnIceServersJSON != nil else { return false }; if churnRelayEscalated { return true }; if CallConfig.shared.mediasoupForceRelayEnabled { return true }; return CallConfig.shared.mediasoupRelayOnCellularEnabled && CellularPathProbe.isCellular`.

### FACT 1 — CONFIRMED verbatim: getRouterRtpCapabilities, no payload, response data used whole

Confirmed exactly as written, including the important negative: iOS does NOT read a `.rtpCapabilities` sub-field off the response — the whole response object is serialised and handed to `device.load(...)`. JS equivalent stands: `const caps = await peer.request('getRouterRtpCapabilities'); await device.load({ routerRtpCapabilities: caps });`

*Evidence:* MediasoupCallEngine.swift:2870 `let routerCaps = try protooPeer?.syncRequest("getRouterRtpCapabilities")`; :2871-2876 serialise `routerCaps ?? [:]` whole; :2877 `try device.load(with: capsString)  // API: load(with routerRTPCapabilities: String)`.

### FACT 2 — CONFIRMED verbatim: DEVICE caps (not router caps) go into join and both transport requests

Confirmed. `device.rtpCapabilities()` and `device.sctpCapabilities()` are read AFTER load; sctp defaults to `"{}"` on throw; these device values are what feed `join.rtpCapabilities`, `join.sctpCapabilities`, and both `createWebRtcTransport.sctpCapabilities`.

*Evidence:* MediasoupCallEngine.swift:2881 `let deviceRtpCapsStr = try device.rtpCapabilities()`; :2882 `let deviceSctpCapsStr = (try? device.sctpCapabilities()) ?? "{}"`; consumed at :2911 `fetchBothTransportInfos(sctpCaps: deviceSctpCaps)` and :2929-2930 in joinData.

### FACT 3 — CONFIRMED content, line cites off by ~3

Both request literals, the parallelism, and the single 10s deadline are exactly as stated, including the absence of `appData` and `numStreams`. Corrected line numbers below.

*Evidence:* MediasoupCallEngine.swift:3152-3155 `let sendData: [String: Any] = ["forceTcp": false, "producing": true, "consuming": false, "sctpCapabilities": sctpCaps]`; :3156-3159 the recv twin with `producing:false, consuming:true`; :3166 and :3170 `peer.request("createWebRtcTransport", data: …)`; :3177 `if group.wait(timeout: .now() + 10) == .timedOut`; :3178 `"createWebRtcTransport pair timed out after 10s"`. (Analyst cited 3155-3162 / 3166-3172 — content right, offsets off.)

### FACT 4 — CONFIRMED, plus one addition: a non-prefetched fallback path still exists

The five consumed keys, the client-side-only `iceServers`/`iceTransportPolicy`/`appData: nil`, and the 1:1 JS mapping are all confirmed. Addition the analyst missed: `createSendTransport`/`createRecvTransport` each still carry their own `data` literal and a fallback `syncRequest("createWebRtcTransport", …)` for when `prefetchedInfo` is nil — so the same request exists in three places in the file. Harmless, but do not be surprised by it when grepping.

*Evidence:* MediasoupCallEngine.swift:3193-3197 (`info["id"]`, `info["iceParameters"]`, `info["iceCandidates"]`, `info["dtlsParameters"]`, `info["sctpParameters"]`); identical block at :3300-3304 for recv. Construction args :3231-3274 (`iceServers: turnIceServersJSON`, :3272 `iceTransportPolicy: forceRelay ? .relay : .all`, :3273 `appData: nil`) and :3347 for recv. Fallback at :3191 `let info = try prefetchedInfo ?? (protooPeer?.syncRequest("createWebRtcTransport", data: data) ?? [:])` and again at :3298.

### FACT 5 — CONFIRMED ordering; one line cite is wrong (produceAudioVideo)

The strict sequence is exactly as stated and both watchdogs are armed per-transport immediately after construction. Correction: `produceAudioVideo()` is CALLED at :3056 (inside joinImpl, after the peer-enumeration loop); :3362 is the function definition. The analyst's cite of :3364 points at neither.

*Evidence:* MediasoupCallEngine.swift:2870 → :2877 → :2900 → :2911 `fetchBothTransportInfos` → :2912 `createSendTransport` → :2916 `armMediaConnectWatchdog(direction: "send", …)` → :2918 `createRecvTransport` → :2919 `armMediaConnectWatchdog(direction: "recv", …)` → :2933 `syncRequest("join", …)` → :2968-3051 peer parse → :3056 `produceAudioVideo()`. Definition at :3362.

### FACT 6 — CONFIRMED verbatim, including the DeviceInfo literal

The join payload's seven keys and the nested device dict are exact. The `flag: "ios"` / `version: "1.0.0"` literals and the Android-port lineage are confirmed. The analyst's caution — that `flag` is a free-form client string and the desktop value must be VERIFIED against the SFU whitelist — is sound and I could not resolve it from the iOS source either.

*Evidence:* MediasoupCallEngine.swift:2923-2931 joinData literal (`forceTcp`, `producing`, `consuming`, `displayName`, `device`, `rtpCapabilities`, `sctpCapabilities`); DeviceInfo.swift:4 `// Ported from Android DeviceInfo.kt`, :13-15 `flag: "ios", name: UIDevice.current.name, version: "1.0.0"`, :19-25 `toDictionary()` → keys `flag`/`name`/`version`. Peers: :2968 `joinResponse["peers"] as? [[String: Any]]`; :2984 `peer["id"]`; :2993 `peer["displayName"]`; :3027-3034 the four screen-share spellings and two hand spellings.

### FACT 7 — CONFIRMED verbatim, retry ladder and ICE-restart escalation included

Exact. One nuance to add for the desktop: the retry is generation-guarded — before retrying it re-checks that the transportId still matches the CURRENT sendTransport/recvTransport, so a rejoin cancels in-flight ladders. Replicate that or a rejoin will fire connects at dead transport ids.

*Evidence:* MediasoupCallEngine.swift:4258-4261 `handleTransportConnect` → `sendConnectWebRtcTransport(transportId:dtlsDict:attempt: 1)`; :4272-4275 `protooPeer?.request("connectWebRtcTransport", data: ["transportId": transportId, "dtlsParameters": dtlsDict])`; :4265-4270 the "single biggest no-audio cause; web had the identical bug" comment; :4278 `asyncAfter(deadline: .now() + Double(attempt) * 0.5)`; :4282-4284 the `sendTransport?.id == transportId` generation guard; :4286-4296 3-strikes → `restartTransportICE`. Delegates: :5213-5216 (send) and :5232-5235 (recv), both into `handleTransportConnect`.

### FACT 8 — CONFIRMED verbatim, including the hardcoded empty appData

Exact, line numbers exact. The `appData: String` parameter is declared on `handleTransportProduce` and never referenced in the body.

*Evidence:* MediasoupCallEngine.swift:4301 `func handleTransportProduce(transport: Transport, kind: MediaKind, rtpParameters: String, appData: String, callback: @escaping (String?) -> Void)`; :4308-4313 `syncRequest("produce", data: ["transportId": transport.id, "kind": kind == .audio ? "audio" : "video", "rtpParameters": rtpDict, "appData": [String: Any]()])`; :4314 `let producerId = response?["id"] as? String`.

### FACT 9 — CONFIRMED verbatim; retry constant is 3, defined at :420

All seven Opus keys and their values are exact, `encodings: nil` / `codec: nil` / `appData: nil` on the mic producer are exact, and the retry ladder exists. Adding the constant and the re-arm trigger the analyst left vague: `micProduceMaxAttempts = 3`, backoff `attempts * 1.0s`, and the ladder also re-arms from `onTransportClose` when the SFU drops the producer mid-call.

*Evidence:* MediasoupCallEngine.swift:3734-3742 opusOptions literal (`opusFec: true, opusDtx: false, opusNack: true, opusStereo: false, opusMaxAverageBitrate: 32000, opusMaxPlaybackRate: 48000, opusPtime: 20`); :3752 `createProducer(for: audioTrack, encodings: nil, codecOptions: codecOptionsJSON, codec: nil, appData: nil)`; :420 `private static let micProduceMaxAttempts = 3`; :3794 `scheduleMicProduceRetry` (analyst said "3800+"), :3803 `let delay = Double(micProduceAttempts) * 1.0`.

### FACT 10 — CONFIRMED verbatim, and the negative grep reproduces

Exact. I re-ran the grep: `encodings|simulcast|scalabilityMode|scaleResolutionDownBy|maxBitrate|videoGoogleStartBitrate` across the 5359-line file returns only four hits — the two `encodings: nil` produce calls, one comment using the word "simulcast", and one `// best simulcast layer` comment on producerScore. There is no simulcast ladder anywhere. The analyst's guidance (do NOT invent one on desktop in the name of parity) stands.

*Evidence:* MediasoupCallEngine.swift:3861 `createProducer(for: videoTrack, encodings: nil, codecOptions: nil, codec: nil, appData: producerAppData)`; :140-141 `RTCDefaultVideoEncoderFactory()` / `RTCDefaultVideoDecoderFactory()`; only other regex hits are :1532 `// best simulcast layer` and :2004 a doc comment.

### FACT 11 — CONFIRMED verbatim, quote is accurate

Exact, including the accept-first-on-the-delivery-thread ordering and the quoted rationale. `newDataConsumer` is accepted and otherwise ignored; every other server request is rejected 403 on the workQueue.

*Evidence:* MediasoupCallEngine.swift:4988-5013 the `onRequest` switch; :4991-4995 quote "The SFU's resume-on-accept is chained on this request resolving, and the SFU abandons the consumer if the request times out."; :5004 `accept(nil)` then :5005-5007 `workQueue.async { self?.onNewConsumer(data: data) }`; :5008-5009 `case "newDataConsumer": accept(nil)`; :5010-5015 default → `reject(403, "unknown method: \(method)")`. Notification fallback at :4328-4330.

### FACT 12 — CONFIRMED content; two line cites off

All field names exact: `id`, `producerId`, `peerId`, `kind`, `rtpParameters`, `producerPaused`, `appData`; `consume(...)` is called with local `appData: nil`. The `producerPaused` rationale (consumerPaused only fires on transitions) is quoted correctly from the code comment. Corrected cites: rtpParameters is read at :3983 (analyst said :3986); producerPaused at :4080 (analyst said :4074, which is inside the explanatory comment).

*Evidence:* MediasoupCallEngine.swift:3943-3947 `data["peerId"]` / `data["producerId"]` / `data["id"]` / `data["kind"]`; :3983 `guard let rtpParams = data["rtpParameters"]`; :3997-4003 `transport.consume(consumerId:producerId:kind:rtpParameters:appData: nil as String?)`; :4070-4079 the ZL-11720 comment; :4080 `let producerPaused = (data["producerPaused"] as? Bool) ?? false`; :4110 `data["appData"] as? [String: Any]`.

### FACT 13 — CONFIRMED, plus a SECOND parking mechanism the analyst missed

Everything stated checks out: `resumeConsumer {consumerId}` via a 3-attempt, session-generation-guarded ladder; unconsumable payloads parked in `pendingNewConsumers` and retried (`guard attempt < 3` = 2 retries) from a ~10s tick. MISSING and important: there is a SEPARATE park set, `pendingConsumerResumes` (main-thread-confined, unlike the workQueue-confined newConsumer park). A resume whose ladder exhausts, or that finds no protoo peer, is inserted there and re-flushed both on recv-transport (re)connect AND every ~10s from the same tick. Without this second mechanism a mid-call exhausted resume stays server-paused forever — "peer connected, unmuted, silent", which is exactly the bug the code comment cites.

*Evidence:* MediasoupCallEngine.swift:4039 `resumeConsumerWithRetry(consumerId)`; :3580 the function, :3588-3591 the `sessionGeneration` guard, :3605 `_ = try peer.syncRequest("resumeConsumer", data: ["consumerId": consumerId])`, :3612-3620 the 3-attempt ladder + `pendingConsumerResumes.insert` on exhaustion; :230 `private var pendingConsumerResumes: Set<String> = []`; :3626-3632 `flushPendingConsumerResumes()`; :3435-3438 the ~10s re-flush (`sfuStatsLogTick % 5 == 0`); :3442-3447 the newConsumer flush on the same tick; :4236-4243 `parkNewConsumer` with `guard attempt < 3`.

### FACT 14 — CONFIRMED content; line cites drift; two additions

Both-sides mute, the 3-attempt stale-guarded audio ladder, the ~10s `serverAckedMuteState` reconcile, the plain `try? syncRequest` video path, intent-recorded-pre-produce, `closeProducer {producerId}`, and the cosmetic `muteAudio` notification are all confirmed. Corrections and additions: (a) the intent re-apply on a fresh produce is at :3765-3771, not :3775-3779; (b) the `muteAudio` NOTIFICATION sends `peerId: CallConfig.shared.currentDeviceId` — note this is the DEVICE id, whereas the recording notification sends the USER id as `peerId` on mediasoup, and the protoo dial peerId is the composite `userId:deviceId`. Three different things called `peerId` on the wire; do not assume one convention. (c) A third pause path exists that the analyst missed: `setLocalVideoBackgroundSuspended(_:)` also issues pause/resumeProducer (guarded by a UIKit background task) so a backgrounded sharer's peers see an avatar rather than a frozen frame — the desktop analogue would be window-minimised/occluded handling.

*Evidence:* MediasoupCallEngine.swift:1195-1198 `producer.pause()` / `producer.resume()`; :1199 `sendProducerPauseState(producerId:mute:attempt: 1)`; :1215 `guard localAudioMuteIntent == mute else { return }`; :1217-1218 `peer.request(mute ? "pauseProducer" : "resumeProducer", data: ["producerId": producerId])`; :1233 `if case .success = result { self.serverAckedMuteState = mute }`; :1245-1250 the 3-attempt ladder; :3455-3474 the ~10s reconcile; :1259-1271 `muteLocalVideo` with `try? syncRequest("pauseProducer"/"resumeProducer")`; :3765 `serverAckedMuteState = false`, :3768-3771 re-apply intent; :3888 `try? protooPeer?.syncRequest("closeProducer", data: ["producerId": producer.id])`; :4558-4564 the `muteAudio` handler; CallingViewModel.swift:1763-1766 `sendPeerNotification(method: "muteAudio", data: ["peerId": CallConfig.shared.currentDeviceId, "muted": muted])`; :1275+ `setLocalVideoBackgroundSuspended`.

### FACT 15 — WRONG in one load-bearing clause: `startScreenShare` is UNIMPLEMENTED SERVER-SIDE

The frame-source-swap description is correct and the desktop recommendation (do a real second producer instead) is correct. But the signalling clause is wrong as written. The request IS sent — CallingViewModel.swift:3083, gated `if !isAgora`, with empty data — but the engine's own in-code comment states the server returns 500 for it and that it is unimplemented server-side, and `sendPeerRequest` swallows the error (`try?` on a global queue, no result handling), so nobody ever notices. Consequence for the implementer: do NOT rely on emitting `startScreenShare` to make peers see your share. On iOS the paths that actually work are (1) Firebase `screenShare: true` on the user document, (2) the join-response per-peer `screenShare|sharingScreen|screen_share|sharing_screen` flags for late joiners, and (3) consumer `appData` on the receiver. The `peerScreenShareStarted`/`peerScreenShareStopped` handlers exist and are correct, but I have NOT verified any server path that emits them — treat them as defensive.

*Evidence:* MediasoupCallEngine.swift:3854-3856 `// (The separate \`startScreenShare\` protoo request the server returns 500 for is NOT a substitute — it's unimplemented server-side.)`; :2510-2519 `sendPeerRequest` = `DispatchQueue.global(qos: .userInitiated).async { try? peer.syncRequest(method, data: data) }` — no result inspection. Send sites: CallingViewModel.swift:3083 `sendPeerRequest(method: "startScreenShare", data: [:])` (preceded at :3072-3079 by `firebaseManager.updateUserFields(... fields: ["screenShare": true])`), :3162 / :3345 / :6266 for `stopScreenShare`. Frame-source swap: MediasoupCallEngine.swift:2287-2290 `// Does NOT create a new producer — just swaps frame source on the existing videoSource.`; :2343-2348 the `if camProducer != nil { videoCapturer?.stopCapture() }` branch; :2384 `self.enableCamImpl(startCapture: false, isScreenShare: true)`; notification handlers :4588 / :4599.

### FACT 16 — CONFIRMED, and the iOS source contradicts ITSELF, which strengthens the reading

Both code facts verified exactly. The producer-side marker string and the hardcoded empty `appData` on the produce request are both as quoted, and the delegate's `appData` argument is genuinely dead. Worth adding: the comment block introducing the marker asserts that the receiver's `appDataIsShare` check is "the path that drives the render guard" — i.e. the iOS author believed the marker reaches peers. Combined with the empty-appData produce request, that is an internal contradiction in the iOS code, which makes the analyst's conclusion (the marker is dead unless the SFU synthesizes appData) more likely to be a real, unnoticed bug than a misreading. I still have not verified server behaviour. Desktop guidance stands: DO forward `{ share: true, screenShare: true, source: 'screen' }` on the produce request.

*Evidence:* MediasoupCallEngine.swift:3858-3860 `let producerAppData: String? = isScreenShare ? "{\"share\":true,\"screenShare\":true,\"source\":\"screen\"}" : nil`; :3850-3853 `// The receiver's \`appDataIsShare\` (onNewConsumer, ZL-18853) is the path that drives the \`(hasVideo || isScreenSharing)\` render guard`; :3857 `// appData is a JSON STRING on this bridge (String?), not a dict.`; contradicted by :4312 `"appData": [String: Any]()` with the parameter declared unused at :4301.

### FACT 17 — CONFIRMED verbatim, all key names exact

The five boolean keys (`share`, `screenShare`, `screen_share`, `isScreen`, `isScreenShare`) and the five string keys scanned for "share"/"screen" (`type`, `kind`, `source`, `mediaTag`, `media_tag`) are exact, as is the `kind == .video` precondition. The two-map split (`remoteVideoTracks` vs `remoteScreenTracks`) and the single-source-at-a-time renderer invariant are confirmed, including the detach-both-then-bind-one sequence.

*Evidence:* MediasoupCallEngine.swift:4109-4125 the `appDataIsShare` closure; :4127-4143 `consumers[consumer.id]?.isScreenShare = true` / `registerScreenConsumerForMemoryGuard` vs `registerRemoteVideoConsumer`; :4181-4195 the detach-old-camera + detach-old-screen then `if isShare { remoteScreenTracks[peerUid] = videoTrack } else { remoteVideoTracks[peerUid] = videoTrack }` block, with the flicker rationale at :4174-4190.

### FACT 18 — WRONG on one clause: keyframe does NOT follow every resume

The four verbs, their exact payload keys (`consumerId`, `spatialLayer`, `temporalLayer`), the 0/0 ↔ 2/2 degrade/restore, the feature flag being OFF by default with the decoder-corruption rationale, and "audio consumers are never paused" are all confirmed. The wrong clause: `requestConsumerKeyFrame` is NOT sent after every resume. The INITIAL post-consume resume (`resumeConsumerWithRetry`) sends no keyframe request at all. Keyframes accompany only three resume/switch sites: the screen-memory resume, the bounded-grid re-show, and both layer switches. Also correcting the cites: :2087 is the screen-share MEMORY pause; the bounded-grid pause is :2180 and its resume+keyframe pair is :2173/:2177.

*Evidence:* MediasoupCallEngine.swift:2019-2020 and :2042-2043 `syncRequest("setConsumerPreferredLayers", data: ["consumerId": id, "spatialLayer": 0|2, "temporalLayer": 0|2])` each followed by :2022 / :2046 `requestConsumerKeyFrame`; :2016 the `mediasoupScreenShareLayerDegradeEnabled` gate with the iPhone-SE corruption note at :2010-2015; :2087 `pauseConsumer` (memory); :2102+:2107 resume+keyframe (memory); :2170-2182 `setVisibleRemoteVideoUids` with resume :2173, keyframe :2177, pause :2180; :2135-2136 `// AUDIO consumers are never touched (you must hear everyone).` Contrast :3605 — the initial resume — which issues `resumeConsumer` alone.

### FACT 19 — CONFIRMED verbatim; one addition

Exact: notification `activeSpeaker`, field `peerId`, mapped through `peerIdToUid`, routed via workQueue (not the bypass list) because it mutates `peerUidMap` and is cosmetic. Addition: it also drives `CallPIPManager.shared.updateActiveSpeaker(uid:)`, so on desktop it feeds whatever the equivalent PIP/tile-highlight surface is, not only the engine event.

*Evidence:* MediasoupCallEngine.swift:4525-4532 `case "activeSpeaker": if let peerId = data["peerId"] as? String { let uid = peerIdToUid(peerId); … CallPIPManager.shared.updateActiveSpeaker(uid: uid); self?.onEvent?(.activeSpeakerChanged(uid: uid)) }`; exclusion rationale :5032-5034; bypass set :5035-5039.

### FACT 20 — CONFIRMED content; add two conditions that prevent false-firing

`producerScore {producerId, score}`, the three-shape normalisation, max-as-best, producerId→mic/cam labelling, and the bypass-list membership are all exact. Two conditions the analyst omitted are precisely the ones that stop this watchdog from misfiring, and a desktop port that omits them will ICE-restart healthy calls: (1) mediasoup emits producerScore ON CHANGE, not periodically — so "never scored" is `lastUplinkScore["audio"] == nil && ≥6s since produce`, NOT "no score in 6s"; the earlier naive version false-fired both repair attempts on every stable call. (2) The watchdog stands down entirely unless the SEND transport state is `connected` or `completed` — no score during a slow connect is expected, not a fault. Corrected cites: extractScores :1516-1522 (analyst said 1517-1523); the watchdog timer starts at :3414, its trigger conditions are :3513-3516, the stand-down gate :3530-3533, repair :3535+ (analyst said :3537 for the whole thing).

*Evidence:* MediasoupCallEngine.swift:1516-1522 `extractScores`; :1528 `guard let producerId = data["producerId"] as? String`; :1529 `let scores = extractScores(data["score"])`; :1530 `guard let best = scores.max()`; :1533-1535 the camProducer/micProducer labelling; :3501-3512 `// mediasoup emits \`producerScore\` on CHANGE, not periodically`; :3513-3515 `let neverScored = self.lastUplinkScore["audio"] == nil && now.timeIntervalSince(self.lastAudioScoreAt ?? now) >= 6.0`; :3516 `let scoreZero = self.audioUplinkZeroSince.map { now.timeIntervalSince($0) >= 4.0 } ?? false`; :3530-3533 `guard sendState == "connected" || sendState == "completed"`; :3535 `audioUplinkRepairAttempts += 1` with the `/2` cap in the log at :3538; bypass at :5036.

### FACT 21 — CONFIRMED on the architecture; WRONG on the payload shapes

"Recording is a local AVAudioRecorder capture, not an SFU concern" is correct and the desktop conclusion holds. But the wire payloads are not what was stated. The `recording` NOTIFICATION carries FIVE fields, not two: `peerId`, `recording`, `callUuid`, `deviceId`, `userId`. The `startClientRecording` / `stopClientRecording` REQUESTS carry four: `peerId`, `userId`, `deviceId`, `recording` — they are not empty. Critically, on the mediasoup path `peerId` here is `CallConfig.shared.currentUserId` (the USER id), deliberately different from the Agora path which uses the device id, and different again from the `muteAudio` notification which uses the device id.

*Evidence:* MediasoupCallEngine.swift:2187 `// MARK: - Audio Recording (AVAudioRecorder — records mic audio in parallel with WebRTC)`; :2242 `let recorder = try AVAudioRecorder(url: url, settings: settings)`. CallingViewModel.swift:3486-3488 `let recordingPeerId = isAgora ? CallConfig.shared.currentDeviceId : CallConfig.shared.currentUserId`; :3489-3497 `sendPeerNotification(method: "recording", data: ["peerId": recordingPeerId, "recording": true, "callUuid": session.callUuid, "deviceId": …, "userId": …])`; :3506-3512 `sendPeerRequest(method: "startClientRecording", data: ["peerId": currentUserId, "userId": …, "deviceId": …, "recording": true])`; stop twins at :3568 / :3578.

### FACT 22 — verb inventory CONFIRMED complete; three field-name corrections

I re-derived the switch statements independently and the analyst's lists are complete and correct — 13 media-pipeline requests, 5 app-level requests, 2 server→client requests, 18 handled notifications, everything else 403. Three corrections/additions to the SHAPES, which is where an implementer loses a day: (1) `newPeer` reads the peer id from field `id`, NOT `peerId` — the only notification in the set that does. (2) `consumerPaused` / `consumerResumed` / `consumerClosed` all key on `consumerId`. (3) `startScreenShare`/`stopScreenShare` do carry empty data but are unimplemented server-side (see FACT 15), and the recording verbs are not empty (see FACT 21). Notification-case line cites drift by 1 for muteAudio (:4558), recording (:4566), peerRecordingStarted (:4574), endCall (:4607).

*Evidence:* Notification switch cases at MediasoupCallEngine.swift:4328, 4332, 4335, 4360, 4401, 4491, 4508, 4525, 4534, 4540, 4549, 4558, 4566, 4574, 4581, 4588, 4599, 4607. :4336 `case "newPeer": guard let peerId = data["id"] as? String else { return }`; :4402 / :4492 / :4509 `guard let consumerId = data["consumerId"] as? String`. Request sites: :1004 restartIce, :1217 pause/resumeProducer, :2019 setConsumerPreferredLayers, :2022 requestConsumerKeyFrame, :2087 pauseConsumer, :2102 resumeConsumer, :2870 getRouterRtpCapabilities, :2933 join, :3166/:3170 createWebRtcTransport, :3605 resumeConsumer, :3888 closeProducer, :4272 connectWebRtcTransport, :4308 produce. Server-request switch :4988-5013.

### FACT 23 — CONFIRMED, with one hard constraint the analyst understated

The verdict holds: nothing in the media pipeline blocks mediasoup-client + protoo-client in the KCEF page, the JS-async-vs-serial-workQueue advantage is real, and the bridge gap is real and correctly diagnosed. One correction and one constraint. Correction: the bridge is one-way in BOTH directions — Kotlin→JS is `target.executeJavaScript(script, url, 0)` with no return value (KcefCallEngine.kt:341), and JS→Kotlin is a CEF message router delivering a single JSON string (KcefCallEngine.kt:431, call.js:39-42 `window.cefQuery({ request: JSON.stringify(event) })`). Neither direction carries an id. Understated constraint: the embedded Chromium is launched with `--auto-select-desktop-capture-source=Entire screen`, so screen capture works but the source is FIXED to the whole screen — there is no window picker in the embedded page. A genuine second video producer is still the right design, but "share this window" is not free; it needs a native picker plus a source id passed into the page. Also: call.js:564 not :565 for the screen-share send; the picker comment is :549-552.

*Evidence:* desktopApp/src/main/kotlin/com/zillit/desktop/KcefCallEngine.kt:341 `target.executeJavaScript(script, target.url, 0)`; :431 `cefClient.addMessageRouter(KcefPage.messageRouter(onMessage))` with `onMessage: (String) -> Unit`; desktopApp/src/main/kotlin/com/zillit/desktop/KcefRuntime.kt:126 `"--auto-select-desktop-capture-source=Entire screen"`; desktopApp/src/main/resources/callengine/call.js:39-42 `function send(event) { window.cefQuery({ request: JSON.stringify(event) }); }`, :549-552 the picker comment, :564 `send({ type: 'screen-share', sharing: true })`, :555 `screenTrack = await AgoraRTC.createScreenVideoTrack({}, 'disable')`. iOS starvation note MediasoupCallEngine.swift:4005-4013.

### MISSING — the protoo dial URL and the composite peerId

The map never states how the socket is opened or what a `peerId` actually is, and every notification in the pipeline is keyed on it. URL: `wss://<dialHost>/?roomId=<roomId>&peerId=<peerId>`, optionally `&token=<urlencoded sfuToken>` and `&prev_session=<base64url>`. The peerId is COMPOSITE: `"<userId>:<deviceId>"`. `peerIdentity(_:)` takes the substring before the first `:` and that identity — not the full peerId — is what self-filtering compares against, so one user on two devices produces two peerIds sharing one identity. Both the join-enumeration self-filter and the newConsumer self-echo filter depend on this; a desktop port that uses a bare uuid for peerId will silently break both, and will render the user's own phone as a remote tile during a handoff.

*Evidence:* MediasoupCallEngine.swift:321 `localPeerId = "\(peerUserId):\(CallConfig.shared.currentDeviceId)"`; :323 `localIdentity = peerUserId`; :374-376 `private func peerIdentity(_ peerId: String) -> String { peerId.contains(":") ? String(peerId.split(separator: ":").first ?? "") : peerId }`; :736 `var protooUrl = "wss://\(dialHost)/?roomId=\(roomId)&peerId=\(localPeerId)"`; :740 `protooUrl += "&prev_session=\(prev)"`; :754 `protooUrl += "&token=\(encodedToken)"`; self-filters at :2986-2989 and :3966-3971.

### MISSING — the protoo wire format is standard, so protoo-client JS is drop-in

The map asserts the desktop needs protoo request/response semantics but never shows the frame format, which is the thing that proves protoo-client JS is compatible. It is stock protoo: request `{request: true, method, id, data}` with a random int id in 1..<10_000_000; response `{response: true, id, ok: true, data}` or `{response: true, id, ok: false, errorCode, errorReason}`; notification `{notification: true, method, data}`. iOS additionally implements a lenient fallback parse for servers that omit the type flags (id+method → request; id alone → response; method alone → notification) — that fallback is an iOS accommodation, not a server requirement, and the desktop can ignore it unless the SFU is observed omitting flags.

*Evidence:* ProtooMessage.swift:6-9 the case list; :87-96 `createRequest` (`"request": true, "method", "id", "data"`, id from `Int.random(in: 1..<10_000_000)`); :99-106 `createSuccessResponse`; :109-117 `createErrorResponse`; :120-126 `createNotification`; :56-79 the flagless fallback parse.

### MISSING — server responses return the payload OBJECT ITSELF, unwrapped

This is a repeated convention of the Zillit SFU and it bit the iOS author at least twice, so it is worth stating as a rule rather than per-verb. `getRouterRtpCapabilities` returns the RtpCapabilities directly as `data` (already noted in FACT 1). `restartIce` likewise returns the new ICE parameters DIRECTLY — `{"usernameFragment": …, "password": …, "iceLite": …}` — NOT nested under an `iceParameters` key; iOS accepts both shapes defensively, preferring a nested `iceParameters` if present. Assume unwrapped, tolerate wrapped.

*Evidence:* MediasoupCallEngine.swift:1030-1041 `// The Zillit protoo server returns the new ICE parameters DIRECTLY as the response payload — \`{"usernameFragment":..,"password":..,"iceLite":..}\` — NOT nested under an \`iceParameters\` key. Accept both shapes…` then `if let nested = resp["iceParameters"] as? [String: Any] { iceParams = nested } else if resp["usernameFragment"] != nil || resp["password"] != nil { iceParams = resp }`; :1051 `try live.restartICE(with: json)`. Compare :2870-2877.

### MISSING — resumeRemoteVideo blanket-resumes every video consumer on PIP/background return

A fifth consumer-control call site the verb inventory covers but the behaviour list does not: `resumeRemoteVideo()` issues `resumeConsumer` for EVERY video consumer and then deliberately clears the bounded-grid off-screen bookkeeping, because it has just contradicted it. On desktop the analogue is window-restore / un-minimise. If you implement the bounded-grid pause guard without also clearing its state on a blanket resume, off-screen tiles will be recorded as paused while actually running, and the next visibility update will skip re-pausing them.

*Evidence:* MediasoupCallEngine.swift:2529-2537 `for (consumerId, holder) in consumers where holder.kind == .video { workQueue.async { try? self?.protooPeer?.syncRequest("resumeConsumer", data: ["consumerId": consumerId]) } }`; :2538-2541 `// ZL-bounded-grid: this resumed everything, so our off-screen-paused bookkeeping is now stale — clear it so the next visible-set update re-pauses the off-screen tiles from a clean slate.`

### Open questions

- Does the Zillit SFU synthesize `appData` on the consumer side, or is iOS's screen-share marker genuinely dead on the wire? The two code facts are confirmed (producer marker set at :3858-3860, produce request hardcodes `"appData": [String: Any]()` at :4312), but only a live server capture settles whether peers ever receive it. This decides whether desktop's tagged second producer is a fix or a divergence.
- Is `startScreenShare` still 500-ing server-side? The engine comment at MediasoupCallEngine.swift:3854-3856 says it is unimplemented, and `sendPeerRequest` swallows the error, so iOS would not notice if it were later implemented. If it now works, the desktop should send it; if not, Firebase `screenShare` remains the only reliable cross-client signal.
- What `device.flag` values does the SFU whitelist? iOS sends `"ios"` (DeviceInfo.swift:13) and the struct is a port of Android's DeviceInfo.kt, so at least "android" exists. Whether an unknown "desktop" is accepted, ignored, or rejected is not determinable from the iOS source.
- Does mediasoup-client JS expose an equivalent of libmediasoupclient's `updateICEServers` for the late-TURN-injection path (FACT 0)? iOS relies on it to repair a transport built with `iceServers: nil`. I did not verify the JS API surface; if it does not exist, the desktop's only recovery is a full transport teardown or a `restartIce` that re-gathers against an unchanged RTCConfiguration, which may not mint relay candidates.
- Do the `peerScreenShareStarted` / `peerScreenShareStopped` notifications have any server emitter at all? iOS handles them (:4588, :4599) but the only client that would trigger them via `startScreenShare` hits the 500. They may exist solely for web/Android clients using a different signalling path.


## TURN, room addressing, and line selection

### VERIFIED — TURN endpoint, headers, and empty-body bodyhash

`GET {CALLING_BASE_URL}/api/v2/webrtc/turn-credentials`, no body. Headers `Content-Type: application/json`, `moduledata`, `iosversion`, `bodyhash`. With an empty body the hash input is literally `{"payload":"","moduledata":"<moduledata>"}`. moduledata plaintext is `{user_id, project_id, device_id, time_stamp}` (ms epoch), then encrypted.

TWO CORRECTIONS/ADDITIONS the analyst missed:
(a) the `device_id` inside moduledata is `Util.deviceId()` — the HARDWARE id — NOT `CallConfig.currentDeviceId` (the server-registered id used in the peerId). Two different device ids in the same call flow; do not unify them.
(b) for POST/PUT bodies the hash is computed over the body encoded with `.sortedKeys` + `.withoutEscapingSlashes`. Any Kotlin serializer that emits a different key order produces a 406.

DESKTOP: the bodiless-GET case is ALREADY handled correctly — `HeaderCrypto.bodyHash` documents "`payload` is the parsed body — an object or array, or `""` for a bodiless request". The analyst's 406 warning is a non-issue here.

*Evidence:* iOS: .../Calling/Engine/Mediasoup/MediasoupTurnCredentialsService.swift:19 (documented `GET /api/v2/webrtc/turn-credentials`), :68 `private static let endpointPath = "webrtc/turn-credentials"`, :253-259 (the NetworkClient call, `method: "GET"`, `baseURL: CallConfig.shared.BASE_URL`). .../Calling/Network/NetworkClient.swift:67-71 (headers), :76 `encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]`, :88-92 (esp. :89 `finalJSONString = "{\"payload\":\"\",\"moduledata\":\"\(moduledata)\"}"`), :93-94 (bodyhash). moduledata contents: .../Calling/Config/CallConfig.swift:305-315, with :309 `let deviceId = Util.deviceId()`.
Desktop: /Users/vivekmishra/Documents/GitHub/ZillitDesktop/core/network/src/commonMain/kotlin/com/zillit/desktop/core/network/ZillitHeaderProvider.kt:186-193.

### VERIFIED — TURN response shape `data.iceServers[] {urls[], username, credential}` + `data.ttl`

Field names confirmed verbatim. `urls` is declared `[String]` with no union handling in the TURN DTO. `ttl` is `Int?`, seconds; absent → 600. Observed urls in the documented live response: `stun:stun.cloudflare.com:3478`, `turn:turn.cloudflare.com:3478?transport=udp`, `turn:turn.cloudflare.com:3478?transport=tcp`, `turns:turn.cloudflare.com:5349?transport=tcp`. These are exactly browser `RTCIceServer` field names, so the array can be handed to the KCEF page verbatim.

CITATION CORRECTED: the tolerant string-or-array sibling DTO is `P2PIceServerDTO` at CallModels.swift:43-69, NOT :189-215 (that range is `MediasoupAddUserRequest` / `MissedMultiCallRequest`). The claim itself is right — `init(from:)` at :57-63 tries `[String]` then falls back to a bare `String`, and its `username`/`credential` are `String?` (optional) where the TURN DTO's are non-optional.

*Evidence:* .../Calling/Engine/Mediasoup/MediasoupTurnCredentialsService.swift:24-43 (documented literal 200 body), :108-112 `TurnIceServerDTO { let urls: [String]; let username: String; let credential: String }`, :114-120 `TurnCredentialsData { iceServers; ttl: Int? }`, :361 `let ttl = data.ttl ?? 600`. .../Calling/Model/CallModels.swift:43-69 (`P2PIceServerDTO`, string-or-array tolerant at :57-63).

### VERIFIED with an important consequence added — three derived iceServers lists; the BUILD list is TCP/TLS-relay-only

All three lists and the filter confirmed exactly as the analyst described:
1. FAST/build list `iceServersJSON()` — `isRelayUsableURL(url, keepUdpTurn: false)`: keep `turns:` always; drop anything not `turn:`; for `turn:` require `transport=tcp`. So STUN and UDP TURN are BOTH dropped, ALWAYS (persist() hardcodes `keepUdpTurn: false` for this list).
2. UPGRADE list `relayUpgradeIceServersJSON()` — `keepUdpTurn: true`: `turns:` + `turn:` UDP and TCP, `stun:` still dropped.
3. FULL raw list `fullIceServersJSON()` — untouched, P2P only.
Each falls back to the raw list if filtering leaves zero.

CONSEQUENCE THE ANALYST MISSED, and it is load-bearing for desktop: because STUN is stripped from the build list AND the desktop will always run `iceTransportPolicy: .all` (no cellular — see the next item), a desktop transport gathers HOST + RELAY candidates only, with NO server-reflexive candidates. Behind NAT that means every desktop call falls to a TCP/TLS TURN relay pair. This is parity with iOS-on-WiFi, not a desktop regression, but the implementer should know it is deliberate and not "fix" it by re-adding STUN without understanding the May-2026 / 2026-08-06 measurements.

*Evidence:* .../Calling/Engine/Mediasoup/MediasoupTurnCredentialsService.swift:76-98 (the three `Cached` fields + rationale), :333-344 (`persist` builds fastServers with `keepUdpTurn: false` and upgradeServers with `true`; :343-344 the raw-list fallbacks), :359-360 (fullJSON), :400-409 `isRelayUsableURL` — `if u.hasPrefix("turns:") { return true }` / `guard u.hasPrefix("turn:") else { return false }` / `if keepUdpTurn { return true }` / `return u.contains("transport=tcp")`.

### VERIFIED — TTL, 60s refresh lead, coalescing, prefetch, one bounded retry

`refreshLeadSeconds: TimeInterval = 60`; a cache entry is reused while `expiresAt.timeIntervalSinceNow > 60`. Concurrent callers coalesce onto one in-flight `Task<String?, Never>`. `prefetch()` is nonisolated fire-and-forget, called from `MediasoupCallEngine.initialize()`. `invalidate()` nils the cache. On throw: `Task.sleep(nanoseconds: 1_200_000_000)` then ONE retry, then nil. nil is never fatal — transports are then built with `iceServers: nil` and `.all`.

Minor addition: the retry calls `Self.resolveAuthOverrides()` twice (once for projectId, once for userId), so the auth pair is re-resolved on the retry — harmless, but a Kotlin port should resolve once.

*Evidence:* .../Calling/Engine/Mediasoup/MediasoupTurnCredentialsService.swift:74, :133-152, :160-177, :185-187 `nonisolated func prefetch()`, :191-193, :267-298 (:278 the 1.2s sleep, :284-285 the double re-resolve), :124-132 (the "callers must pass that nil straight through" contract). Prefetch call site: .../Calling/Engine/Mediasoup/MediasoupCallEngine.swift:632 `MediasoupTurnCredentialsService.shared.prefetch()`.

### VERIFIED — the TURN fetch is auth-scoped to the CALL's project, not the active one

`resolveAuthOverrides()` returns `(nil, nil)` by default (login user + active project). Two incoming-only overrides, both returning `(ringProjectId, ringUserId)`:
(a) `loginUserId.isEmpty` and both ring ids non-empty — push-woken process, default header would be empty → 406 `libs_module_data_invalid`;
(b) `handlingACall` and both ring ids non-empty and `ringProjectId != activeProjectId` — cross-project incoming → 403 `libs_access_denied`.
Either failure returned nil, transports were built with NO ICE SERVERS AT ALL (not even STUN), producing the field report "people call me → cannot connect; I call them → connects". The minted Cloudflare credentials are NOT project-scoped; only the fetch auth is.

DESKTOP MAPPING — with a correction: `CallOptions.userId` is documented "Ignored when [projectId] is not set", so the desktop must pass BOTH `projectId` and `userId` together. That matches iOS, which always returns the pair together.

*Evidence:* .../Calling/Engine/Mediasoup/MediasoupTurnCredentialsService.swift:197-215 (doc), :216-237 (`resolveAuthOverrides`: :225-227 push-woken branch, :230-233 cross-project branch, :236 the default), :247 `let auth = Self.resolveAuthOverrides()`, :257-258 `projectId: auth.projectId, userId: auth.userId`.
Desktop: /Users/vivekmishra/Documents/GitHub/ZillitDesktop/core/network/src/commonMain/kotlin/com/zillit/desktop/core/network/ApiClient.kt:48 (`projectId`), :51-55 (`userId`, with the "Ignored when [projectId] is not set" contract).

### VERIFIED — iceTransportPolicy is `.all` on desktop, always (barring churn escalation)

`shouldForceRelay()` is the single decision point: `guard turnIceServersJSON != nil else { return false }`; then `if churnRelayEscalated { return true }`; then `if CallConfig.shared.mediasoupForceRelayEnabled { return true }` (default FALSE); then `return CallConfig.shared.mediasoupRelayOnCellularEnabled && CellularPathProbe.isCellular` (valve default TRUE, but no cellular path on desktop). Policy is fixed at transport creation; changing it requires the fresh-peerId rejoin rebuild. Churn escalation: >= threshold ICE disturbances in a rolling window, with `remotePeerEverPresent`, `!transportsBuiltRelayForced`, and `turnIceServersJSON != nil` — one-shot, sticky, triggers `rejoinAfterReconnect()`.

All flag defaults confirmed at the cited lines.

*Evidence:* .../Calling/Engine/Mediasoup/MediasoupCallEngine.swift:642-653 (`shouldForceRelay`, func at :648), :663-680 (`noteIceDisturbance`), :3229 `let forceRelay = shouldForceRelay()`, :3241 `iceServers: turnIceServersJSON`, :3272 `iceTransportPolicy: forceRelay ? .relay : .all`, :3273 `appData: nil`, :3282-3283 (`transportsBuiltWithTurn` / `transportsBuiltRelayForced` snapshots). Defaults: .../Calling/Config/CallConfig.swift:162 `mediasoupForceRelayEnabled: Bool = false`, :175 `mediasoupRelayOnCellularEnabled: Bool = true`, :204 `mediasoupKeepUdpTurnEnabled: Bool = true`, :217 `mediasoupChurnRelayEscalationEnabled: Bool = true`.

### CORRECTED — the udpRelayUpgrade will NEVER fire on desktop

The analyst described the post-connect UDP-relay upgrade as gated only on `mediasoupKeepUdpTurnEnabled`. It is gated on TWO things: `self.transportsBuiltRelayForced && CallConfig.shared.mediasoupKeepUdpTurnEnabled`. Since desktop is never cellular and the global force flag defaults false, `transportsBuiltRelayForced` is always false → the upgrade never arms.

This materially changes the port priority: do NOT build the UDP-relay-upgrade machinery for desktop v1. What DOES still apply on desktop is every other `restartTransportICE` trigger, all of which pass `turnIceServersJSON`: `lateTurnCreds`, `interfaceChange`, `connectStall`, `disconnected`, `failed`, `audioUplink`, `wsReconnectProbe`. Those are the ones that need a JS equivalent for the ICE-server swap.

Execution detail confirmed: +2.0s after `connected`, one-shot per direction via `udpUpgradeDoneDirections`, guarded by `sessionGeneration`.

*Evidence:* .../Calling/Engine/Mediasoup/MediasoupCallEngine.swift:1745-1746 `if self.transportsBuiltRelayForced, CallConfig.shared.mediasoupKeepUdpTurnEnabled {`, :1748 `workQueue.asyncAfter(deadline: .now() + 2.0)`, :1749-1757 (generation + one-shot guards), :1761 `restartTransportICE(t, label: direction, peer: peer, iceServers: upgradeJSON, trigger: "udpRelayUpgrade")`. Other triggers: :574-575 (`lateTurnCreds`), :917-918 (`interfaceChange`), :1636 (`connectStall`), :1802 (`disconnected`), :1820 (`failed`), :3570 (`audioUplink`), :4738-4739 (`wsReconnectProbe`).

### VERIFIED with a caveat — `forceTcp` is a protoo request field, always false; the two transport requests are concurrent ONLY on the join path

Request bodies are literally, verbatim:
  send: `{"forceTcp": false, "producing": true,  "consuming": false, "sctpCapabilities": {…}}`
  recv: `{"forceTcp": false, "producing": false, "consuming": true,  "sctpCapabilities": {…}}`
Both issued via `peer.request("createWebRtcTransport", …)` inside a `DispatchGroup`, awaited with one 10s bound. Response fields consumed: `id`, `iceParameters`, `iceCandidates`, `dtlsParameters`, `sctpParameters`.

CAVEAT THE ANALYST MISSED: concurrency only happens through `fetchBothTransportInfos`, called from the join path. `createSendTransport` and `createRecvTransport` each still carry a SERIAL fallback — `try prefetchedInfo ?? (protooPeer?.syncRequest("createWebRtcTransport", data: data) ?? [:])` — with the same body built inline. A rebuild path that calls them without `prefetchedInfo` is two serial round-trips.

*Evidence:* .../Calling/Engine/Mediasoup/MediasoupCallEngine.swift:3148-3181 (`fetchBothTransportInfos`; :3152-3159 the two literal bodies, :3166-3172 the concurrent requests, :3177-3178 the 10s bound), :3183-3191 (`createSendTransport` serial fallback, :3191 the `syncRequest`), :3193-3197 (response field names), :3290-3298 (`createRecvTransport` mirror, :3298 its `syncRequest`). Join call site: :2911 `let (sendInfo, recvInfo) = try fetchBothTransportInfos(sctpCaps: deviceSctpCaps)`.

### VERIFIED — room addressing: wire key `roomId`, value `invite_code || room_id || call_uuid`

`resolvedSFURoomId(session) = session.inviteCode.isEmpty ? session.callUuid : session.inviteCode`. `inviteCode` is populated as the flat `room_id` on an incoming ring, and as `callData.inviteCode ?? callData.roomId ?? ""` on an outgoing initiate response (because `invite_code` is not a schema field and is dropped on persist / empty on a mutual call). Joining the call-doc `call_uuid` when it differs lands the two ends in different SFU rooms — connected, silent. Confirmed identically at both the outgoing and incoming join sites.

Ring-side key split confirmed: for `line == "mediasoup"` the flat push reads `room_id` into inviteCode; for agora it reads `guest_invite_code`. And `channelName = (provider == .mediasoup) ? callUuid : agoraChannelName` — i.e. `agora_channel_name` is meaningless on Line 1.

DESKTOP DIVERGENCE the analyst missed: the desktop already has its own room resolver with the INVERSE preference — `invite.roomId.ifBlank { invite.callUuid }` — which ignores `inviteCode` entirely. On incoming the two agree (iOS puts `room_id` into inviteCode). On outgoing they can diverge when `invite_code != room_id`. Pick one rule and use it in both places.

*Evidence:* iOS: .../Calling/UI/ViewModels/CallingViewModel.swift:449-458 (`resolvedSFURoomId`, func at :456), :815 `inviteCode: callData.inviteCode ?? callData.roomId ?? ""` with the §8 gotcha comment at :811-814, :1120-1123 (outgoing join), :1510-1513 (incoming accept join). Ring parse: .../Calling/VoIP/CallKitManager.swift:1859 `let invitecode = (line == "mediasoup") ? payload["room_id"] as? String ?? "" : payload["guest_invite_code"] as? String ?? ""`, :1871 `let channelName = (provider == .mediasoup) ? callUuid : agoraChannelName`. Initiate-response model: .../Calling/Model/CallModels.swift:456-460, :494-496 `effectiveCallId { callUuid ?? roomId ?? "" }`.
Desktop: /Users/vivekmishra/Documents/GitHub/ZillitDesktop/feature/calls/src/commonMain/kotlin/com/zillit/desktop/feature/calls/data/CallCoordinator.kt:281 `roomId = invite.roomId.ifBlank { invite.callUuid }`.

### VERIFIED, plus the missing WebSocket subprotocol — the protoo dial URL

`var protooUrl = "wss://\(dialHost)/?roomId=\(roomId)&peerId=\(localPeerId)"`, then optionally `&prev_session=<base64url>` (diagnostics, consumed once) and `&token=<percent-encoded, .urlQueryAllowed>` when `sfuToken` is non-empty. `dialHost` is host:port, no scheme. The 4401 warning is a source comment, confirmed. Close code `4000` + reason `"closed by protoo-server"` = same peerId joined elsewhere → `sessionReplacedByOtherDevice`, deliberately no auto-reconnect.

CRITICAL FACT THE ANALYST MISSED: the WebSocket must negotiate the `protoo` subprotocol — iOS sets `Sec-WebSocket-Protocol: protoo` on the upgrade request. In JS that is `new WebSocket(url, 'protoo')`. protoo-client's own `WebSocketTransport` does this for you; a hand-rolled WS will be rejected without it.

Also confirmed: iOS's reconnect backoff is `RetryStrategy(retries: 600, factor: 2, minTimeout: 800, maxTimeout: 4000)` — 1.6s, 3.2s, then a steady 4s ceiling.

*Evidence:* .../Calling/Engine/Mediasoup/MediasoupCallEngine.swift:736 (the dial URL), :743-753 (prev_session block, `protooUrl += "&prev_session=…"` at :746), :754-757 (`protooUrl += "&token=\(encodedToken)"`), :727-729 (the 4401 comment), :730-735 (the `(elected)`/`(default)` CallDiag line). .../Calling/Engine/Mediasoup/ProtooWebSocketTransport.swift:237 `request.setValue("protoo", forHTTPHeaderField: "Sec-WebSocket-Protocol")`, :138 the RetryStrategy, :416-436 + :632-639 (close-4000 detection incl. the reason-text fallback). .../Calling/Model/CallState.swift:290-297 (`sessionReplacedByOtherDevice` semantics).

### VERIFIED — peerId = `"<callProjectUserId>:<serverDeviceId>"`

`localPeerId = "\(peerUserId):\(CallConfig.shared.currentDeviceId)"`. The `peerUserId` fallback chain, in order: (1) `callProjectUserId` (set by the VM from `session.toUserId`), (2) `CallKitManager.shared.currentProjectUserId`, (3) `Util.getCurrentProjectUserId()`, (4) `CallConfig.shared.currentUserId` (login), (5) `CallConfig.shared.currentDeviceId` as last resort. `localIdentity = peerUserId` — self-filtering compares against this. `currentDeviceId` is the SERVER-REGISTERED device id (`Util.getServerDeviceId()`, falling back to `Util.deviceId()`), and on an incoming call it is OVERWRITTEN from the ring's `receiver_device_id`. `peerIdentity()` splits on the first `:`; `peerIdToUid` hashes the identity (`abs(hashValue) % 9_900_000 + 100_000`) — the Agora numeric uid is unrelated to the peerId.

All five chain steps and both VM assignment sites confirmed at the cited lines.

*Evidence:* .../Calling/Engine/Mediasoup/MediasoupCallEngine.swift:711-721 (the chain; :721 `localPeerId = "\(peerUserId):\(CallConfig.shared.currentDeviceId)"`), :723 `localIdentity = peerUserId`, :129-135 (`callProjectUserId` doc, "The SFU peerId is `\"\(callProjectUserId):\(deviceId)\"`"), :374-376 (`peerIdentity`), :2752-2762 (`peerIdToUid`). VM: .../Calling/UI/ViewModels/CallingViewModel.swift:1131 and :1521 `(callEngine as? MediasoupCallEngine)?.callProjectUserId = session.toUserId`; device-id overwrite at :1316-1319 `CallConfig.shared.currentDeviceId = data.receiverDeviceId`. Device id source: .../Calling/Config/CallConfig.swift:329-340.

### VERIFIED — SFU host is elected per call, sticky, and normalized by stripping ANY scheme

`normalizeSfuHost` trims, strips any scheme via `^[A-Za-z][A-Za-z0-9+.-]*://`, truncates at the first `/`, KEEPS host and port, returns nil if empty. Payload keys `mediasoup_server_url` (canonical) with `sfu_url` alias; `electedSfuHostRaw { mediasoupServerUrl ?? sfuUrlAlias }`. `effectiveSfuHost` returns the override if non-empty, else `CallConfig.MEDIASOUP_URL` — QA `calling-sfu-qa.zillit.com`, Dev `mediasoup-dev.zillit.com`, prod `calling-sfu-prod.zillit.com`. "NEVER fall back to the default on a dial failure" is a literal source comment. The ring parser normalizes at parse time and stores the result on the session.

Additional recovery path worth porting: `electedSfuHostForDial` re-reads `CallKitManager.shared.currentSessionSfuHost` when the VM's session lost it, sticks it back onto the session for later rejoins, and logs an `.error` for the unrecoverable case — a deliberate split-room alarm.

Citation nit: `sfuUrlAlias = "sfu_url"` is at CallModels.swift:539, not within :537-538.

*Evidence:* .../Calling/Engine/Mediasoup/MediasoupCallEngine.swift:107-121 (doc + `normalizeSfuHost` at :114-121), :100-105 (`sfuHostOverride` + the NEVER-fall-back comment), :123-128 (`effectiveSfuHost`). Defaults: .../Calling/Config/CallConfig.swift:72-81. Keys: .../Calling/Model/CallModels.swift:488-490 (`mediasoupServerUrl`, `sfuUrlAlias`, `electedSfuHostRaw`), :537-539 (`sfu_token`, `mediasoup_server_url`, `sfu_url`). Ring parse: .../Calling/VoIP/CallKitManager.swift:1890 and :1925 `session.sfuHost = MediasoupCallEngine.normalizeSfuHost(sfuHostRaw)`. Outgoing: .../Calling/UI/ViewModels/CallingViewModel.swift:819 and :822. Split-room guard: same file :541-558.

### MOSTLY VERIFIED, one citation wrong — how a call is routed to Line 1

(a) OUTGOING is a client choice, confirmed. `selectedProvider` defaults to `.agora`; `showAvailableLines` gates on Firebase Remote Config `ForceUpdate.sharedInstance().lineEnabledProjects` containing `"ALL_PROJECTS"` or the project id, otherwise `completion(.agora)` silently; the sheet is titled "Select Line" with "Line 1" → `.native` → `.mediasoup` and "Line 2" → `.agora`. The provider then picks the ENDPOINT (`mediasoup-call/initiate-call` vs `call/new-call`); there is no `line` field in the mediasoup initiate request.

CITATION CORRECTED: `MediasoupStartCallRequest` is at CallModels.swift:111-144, NOT :256-288 (that range is `MediasoupCallResponseRequest`'s doc and `P2PAcceptInfo`). The SHAPE the analyst gave is right — CodingKeys are `callerId`, `receiverIds`, `callType`, `userID`, `chatRoomId = "chat_room_id"`, `p2p`; i.e. camelCase except the one snake_case key. Add: `callerId` and `userID` are both filled with `CallConfig.shared.currentUserId` (the LOGIN id, not project-scoped), and `projectId` rides as a header override, not in the body.

(b) INCOMING is server-declared via `line`, lowercased, `"mediasoup"` → `.mediasoup` else `.agora`; `connection_type`/`p2p_eligible` pick the transport underneath a call whose `line` stays `"mediasoup"`; `p2pEnabled = false` on client and server. Confirmed.

(c) `line` is a field on `UpdateCallRequest`, default `"agora"` — confirmed, but that struct carries FIVE fields, not one: `call_uuid`, `device_id`, `receiver_device_id`, `call_type`, `line`. Missed-call paths are Agora-only now (zillit_calling#136), confirmed.

*Evidence:* (a) .../Calling/Config/CallConfig.swift:285 `var selectedProvider: CallProvider = .agora`, :391-398 (mediasoup paths). .../Calling/Bridge/CallManagerBridge.swift:380-388 (`swtichChannel`), :390-433 (`showAvailableLines`; :393-396 the remote-config gate, :397-401 the silent agora default, :417-423 the sheet). Endpoint split: .../Calling/UI/ViewModels/CallingViewModel.swift:730-760 (:740, :743 both `currentUserId`; :747 `projectId: effectiveProjectId`). Request model: .../Calling/Model/CallModels.swift:111-144 (CodingKeys :136-143).
(b) .../Calling/VoIP/CallKitManager.swift:1856, :1870, :1873-1886. .../Calling/UI/ViewModels/CallingViewModel.swift:1333-1341. .../Calling/Model/CallState.swift:253-259. .../Calling/Config/CallConfig.swift:242 `var p2pEnabled: Bool = false`.
(c) .../Calling/Model/CallModels.swift:146-174 (`UpdateCallRequest`, `line` at :151, default `"agora"` at :158, CodingKeys :167-173). .../Calling/Config/CallConfig.swift:386-387 + :266-270 comment (missed-call Agora-only).

### CORRECTED — end-call is a PUT, and it is the SAME path for both lines

The analyst wrote that `CallApi.endCall` "posts no `line` field". Two refinements:
- The verb is PUT, not POST, on both sides. iOS: `client.request(url: CallConfig.PATH_END_CALL, method: "PUT", body: request, …)`. Desktop already PUTs. So the desktop only needs the `line` field added, not a verb change.
- There is no `mediasoup-call/end-call`. Both lines PUT `call/end-call`; the `line` field is the ONLY discriminator, and its default is `"agora"` — so a Line 1 hang-up that omits it is actively telling the backend the wrong thing.
- iOS computes the value as `self.isAgora ? "agora" : "mediasoup"` at both the main hang-up and the remove-participant site.
- The desktop's `endCall` body is `callRef(callUuid, deviceId)` — it also omits `receiver_device_id` and `call_type`, which iOS's `UpdateCallRequest` always sends (possibly empty).

*Evidence:* iOS: .../Calling/Network/ApiService.swift:39-49 (:42-43 `url: CallConfig.PATH_END_CALL, method: "PUT"`). .../Calling/Repository/CallRepository.swift:37-49 (:37 `line: String = "agora"` default, :41 the UpdateCallRequest build). Call sites: .../Calling/UI/ViewModels/CallingViewModel.swift:4086 `let currentLine = self.isAgora ? "agora" : "mediasoup"` and :4091-4095; :2701 (remove-participant path). Path: .../Calling/Config/CallConfig.swift:265 `PATH_END_CALL = "call/end-call"`.
Desktop: /Users/vivekmishra/Documents/GitHub/ZillitDesktop/feature/calls/src/commonMain/kotlin/com/zillit/desktop/feature/calls/data/CallApi.kt:78-79 `put("call/end-call", callRef(callUuid, deviceId), projectId)`.

### VERIFIED, with two additions — the desktop `readCallSession` gap list

Every gap the analyst listed checks out against the Kotlin. Confirmed missing from `readCallSession`: `sfu_token`; `mediasoup_server_url`/`sfu_url`; `to_user_id` (desktop reads `receiver_user_id`/`receiverUserId` instead); `from_user_id` (desktop reads `sender_user_id`/`senderUserId`/`user_id`); flat `device_id` as the caller's device (desktop reads `sender_device_id`/`senderDeviceId`/`caller_device_id`, and `CallCoordinator.onInvite` gates the own-ring echo on `invite.callerDeviceId`); `receiver_device_id`/`to_device_id` (the `receiverDeviceId` property exists on `CallSession` but the reader never fills it); the `uuid` alias (`obj.str("call_uuid", "callUuid") ?: return null` drops the ring outright); the p2p family; `group_name`. `isJoinable` and `CallProvider.ofWire` both hard-code Agora, exactly as described.

TWO ADDITIONS:
1. The desktop's key set is NOT arbitrary — it matches iOS's INITIATE-RESPONSE model `CallingResponseCallModel`, which genuinely uses `sender_user_id`, `receiver_user_id`, `caller_device_id`, `receiver_device_id`, `chat_room_name`. The keys that differ are the VoIP-push keys. So the desktop reader is correct for one shape and untested for the other — which is precisely why the analyst's socket-vs-push caveat matters.
2. `group_name` on iOS is a preference chain, not a replacement: `payload["group_name"] ?? payload["chat_room_name"] ?? ""` into `ringGroupName`, a field SEPARATE from the caller name. The desktop maps `chat_room_name` into `title`, so adding `group_name` as a first preference on `title` is the minimal change.

*Evidence:* Desktop: /Users/vivekmishra/Documents/GitHub/ZillitDesktop/feature/calls/src/commonMain/kotlin/com/zillit/desktop/feature/calls/data/CallWire.kt:76-124 (:83 the `call_uuid` hard requirement, :100-101 caller keys, :108-111 selfUserId, :113 title). .../domain/CallModels.kt:104-111 (`ofWire` Agora fallback), :204 (`receiverDeviceId` property), :215-227 (`isJoinable`, :227 `provider == CallProvider.Agora && …`). .../data/CallCoordinator.kt:269-273 (the echo gate).
iOS ring keys: .../Calling/VoIP/CallKitManager.swift:1816 (`uuid` ?? `call_uuid`), :1817 (`device_id`), :1847 (`from_user_id`), :1850 (`to_user_id`), :1852-1854 (`receiver_device_id` ?? `to_device_id`), :1887 (`sfu_token`), :1890 (`mediasoup_server_url` ?? `sfu_url`), :1950-1952 (`others_count`, `group_name` ?? `chat_room_name`). iOS initiate-response keys: .../Calling/Model/CallModels.swift:509-541.

### VERIFIED with corrections — the KCEF assumption holds; the awkward spot is real but differently shaped

Nothing in the iOS implementation makes mediasoup-client + protoo-client inside the existing KCEF page impossible. Caveats, re-ranked after checking the Swift:

1. TURN creds cannot be fetched from the page (needs `moduledata`+`bodyhash`+salt, which live in Kotlin). Fetch in Kotlin, push the array down the bridge. UNCHANGED — correct.
2. The ICE-server swap. `transport.updateICEServers(json)` has no mediasoup-client JS equivalent; the browser workaround is `transport.handler._pc.setConfiguration({iceServers, iceTransportPolicy})` (private field) then `transport.restartIce({ iceParameters })`. CORRECTION to the analyst's framing: this is NOT needed for the UDP-relay upgrade (which never fires on desktop — see the udpRelayUpgrade item). It IS needed for `lateTurnCreds` (creds losing the 2s join race) and for the connect-stall / disconnected / failed / ws-reconnect restarts.
3. `file://` Origin is `null`. SUBSTANTIATED — the desktop loads `"file://${page.absolutePath}"`. If protoo-server does origin checking, the dial fails; verify before building.
4. The 2.0s creds gate. `ensureTurnCredentialsLoaded` blocks the workQueue on a semaphore for at most `turnCredsJoinTimeout = 2.0`; on timeout it does NOT clobber whatever it already had, and arms late-arrival recovery. Reproduce the bound, not the blocking. CORRECT.

NEW, and the single most quotable thing for the JS port: the Zillit protoo server's `restartIce` response returns the new ICE parameters FLAT — `{"usernameFragment":…,"password":…,"iceLite":…}` — NOT nested under an `iceParameters` key. iOS accepts both shapes; a JS port that does `resp.iceParameters` will silently no-op every ICE restart. The request itself is `peer.request("restartIce", { transportId })`.

*Evidence:* Desktop: /Users/vivekmishra/Documents/GitHub/ZillitDesktop/desktopApp/src/main/kotlin/com/zillit/desktop/KcefCallEngine.kt:437 `cefClient.createBrowser("file://${page.absolutePath}", …)`, :401 + :417 (local-file rationale, bundled SDK list). .../resources/callengine/call.html:5-6; .../resources/callengine/call.js:39-45 (`window.cefQuery` bridge).
iOS: .../Calling/Engine/Mediasoup/MediasoupCallEngine.swift:956-963 (:958 `try transport.updateICEServers(iceServers)`), :565-577 (`injectLateTurnCredentialsLocked`), :1004 `peer.request("restartIce", data: ["transportId": transportId])`, :1029-1044 (the flat-vs-nested iceParameters handling, with the explicit "returns the new ICE parameters DIRECTLY as the response payload" comment), :1051 `try live.restartICE(with: json)`, :3088-3138 (`ensureTurnCredentialsLoaded`; :3122 the semaphore bound, :3112-3120 the late-arrival hop), :544 `turnCredsJoinTimeout: TimeInterval = 2.0`, :791-805 (12s/30s join watchdog). .../Calling/Engine/Mediasoup/MediasoupTurnCredentialsService.swift:124-132 (the JSON string is handed straight to createSend/ReceiveTransport).

### ADDED — Line 1 REST surface the desktop already has, and the one it lacks

The analyst's gap list stopped at the ring reader. On the REST side the desktop is further along than implied: `CallApi` already calls `mediasoup-call/call-response` (POST, via `sendCallResponse(roomId, status, fromUserId, projectId)`) and `mediasoup-call/call-dump/{roomId}` (GET, with `CallOptions(projectId=…)`), and `mediasoup-call/guest-join-request/respond`. Its own header comment already names the `mediasoup-call/` family as Line 1.

What is genuinely absent is `mediasoup-call/initiate-call` — there is no desktop equivalent of `createMediasoupCall`, so outgoing Line 1 has no entry point at all. `createCall` hard-codes `call/new-call`.

The `call-dump` call is also the closest working template for the TURN fetch: same verb, same module, same `CallOptions` override mechanism.

*Evidence:* /Users/vivekmishra/Documents/GitHub/ZillitDesktop/feature/calls/src/commonMain/kotlin/com/zillit/desktop/feature/calls/data/CallApi.kt:28-31 (the Line1/Line2 path-family comment), :53-75 (`createCall` → `post("call/new-call", …)` at :69), :119-133 (`sendCallResponse` → `mediasoup-call/call-response` at :129), :144-155 (`callDump` → `mediasoup-call/call-dump/$roomId` at :147, with `HttpVerb.Get`, `RequestModule.ProjectUser`, `CallOptions(projectId = projectId)`), :228 (guest-join respond). iOS counterpart paths: .../Calling/Config/CallConfig.swift:391-398.

### SUBSTANTIATED — the analyst's dominating caveat about ring keys is real, and iOS says so in its own comments

The desktop rings on the socket event `cnc:incoming-call` (`ZillitSocketEvents.Calls.Incoming`). iOS has no socket ring at all — the source states this twice, in comments, unprompted: "iOS has no `cnc:incoming-call` socket ring". So every ring-side field name in this map (`from_user_id`, `to_user_id`, flat `device_id`, `uuid`, `receiver_device_id`, `sfu_token`, `mediasoup_server_url`, `group_name`) comes from `parseVoIPFlatPayload` — the PushKit VoIP parser — not from the event the desktop actually receives.

This should be the first thing confirmed against the web client or the backend `calling-events.js` emitter before any of those names is coded against. The initiate-RESPONSE names (from `CallingResponseCallModel`, all snake_case) are safe — the desktop already reads that shape correctly and iOS parses the same document.

*Evidence:* Desktop: /Users/vivekmishra/Documents/GitHub/ZillitDesktop/core/socket/src/commonMain/kotlin/com/zillit/desktop/core/socket/ZillitSocketEvents.kt:136 `val Incoming = SocketEventName("cnc:incoming-call")`, :129 ("The ring arrives on [Incoming] as a flat call object"); /Users/vivekmishra/Documents/GitHub/ZillitDesktop/feature/calls/src/commonMain/kotlin/com/zillit/desktop/feature/calls/data/CallCoordinator.kt:257-266.
iOS: .../Calling/UI/ViewModels/CallingViewModel.swift:1570 ("VoIP push (iOS has no `cnc:incoming-call` socket ring)…") and :7567 ("…there is no `cnc:incoming-call` socket ring on"). Parser: .../Calling/VoIP/CallKitManager.swift:1813-1953 (`parseVoIPFlatPayload`).

### Open questions

- What does the desktop's `cnc:incoming-call` socket payload actually contain? Every ring-side field name in this map is from iOS's PushKit VoIP parser (`parseVoIPFlatPayload`), and iOS explicitly has no socket ring. Before coding `sfu_token` / `mediasoup_server_url` / `to_user_id` / flat `device_id` into `readCallSession`, capture one real `cnc:incoming-call` frame for a Line 1 call, or read the backend `calling-events.js` emitter. This gates the whole ring-reader work item.
- Does the Zillit protoo-server enforce an Origin check on the WebSocket upgrade? A KCEF page loaded from `file://` sends `Origin: null`. If it rejects that, the fallback is serving the page from a custom CEF scheme handler — worth deciding before the page work starts, not after.
- Is the SFU dial JWT enforced yet? The iOS comment says tokenless dials still work today but warns to implement `&token=` BEFORE the backend enforces it, or dials get close code 4401. Confirm the current server state so the desktop is not shipped tokenless into an enforcing fleet.
- What does the `join` protoo request body look like? This audit covered TURN, room addressing, the dial URL, and transport creation. The `getRouterRtpCapabilities` → `device.load` → `join` handshake, the `produce` / `newConsumer` field shapes, and the peer-state notification methods are in `joinImpl` and the ProtooPeer delegate and were outside this area's scope — they need the same field-by-field treatment before an implementer can finish Line 1.
- On desktop, transports will be built with `iceTransportPolicy: .all` but with a STUN-stripped ICE server list, so no srflx candidates are ever gathered. Is that the intended behaviour for a wired/WiFi desktop client, or should desktop pass the FULL raw list (`fullIceServersJSON`) under `.all` — which is what iOS's own P2P engine does for exactly this reason? The Swift's `fullIceServersJSON` doc says feeding a `.all` peer connection the relay-filtered list "strips STUN → no srflx candidates → no direct path". That argues desktop should use the FULL list, not the fast list — but it contradicts a literal port of the SFU path. Needs a decision, ideally with the SFU team.


## Fitting it to the desktop

### CONFIRMED — ICallEngine → CallEngine method map is accurate

Every line number in the analyst's method map checks out exactly. ICallEngine.swift: onEvent :18, onVideoPixelBuffer :22, supportsBackgroundVideoCapture :31, initialize :33, setVideoEnabled :35, joinChannel :36, leaveChannel :37, muteLocalAudio :39, muteLocalVideo :40, enableLocalVideo :41, switchCamera :42, setEnableSpeakerphone :43, setupLocal/Remote/removeRemote :45-47, startAudioRecording/stop :49-50, startScreenShare/stop/pushScreenFrame :53-55, sendPeerNotification :58, sendPeerRequest :61, generateUid :63, destroy :64, leaveChannelOnly :66, uidForPeer :70, resumeRemoteVideo :74, setVisibleRemoteVideoUids :84, forceReconnect :96. Desktop CallEngine.kt: events :112, isReady :115, initialize :118, join :127, leave :130, setMicrophoneMuted :132, setCameraEnabled :134, setSpeakerEnabled :136, listDevices :139, setDevice :148, switchCamera :151, startScreenShare :154, stopScreenShare :156, setStage :165, setTheme :168, setCompact :171, showReaction :180, destroy :183. leaveChannelOnly == destroy for mediasoup confirmed at MediasoupCallEngine.swift:2745-2748. One citation slip: NoopCallEngine.join is CallEngine.kt:209, not CallModels.kt:209.

*Evidence:* /private/tmp/claude-501/-Users-vivekmishra-Documents-GitHub-ZillitDesktop/0f59a02a-383a-4035-9c5f-929f3c94fd3c/scratchpad/iosnew/Zillit-IOS-reply-option-outside-app/Zillit/Zillit/Calling/Engine/ICallEngine.swift:17-97; /Users/vivekmishra/Documents/GitHub/ZillitDesktop/feature/calls/src/commonMain/kotlin/com/zillit/desktop/feature/calls/domain/CallEngine.kt:109-235

### CONFIRMED — the five mediasoup-only members and generateUid/resumeRemoteVideo are absent from the desktop tree

grep across feature/, desktopApp/, core/ (excluding build/) returns literally 0 hits for each of: sendPeerNotification, sendPeerRequest, uidForPeer, setVisibleRemoteVideoUids, forceReconnect, generateUid, resumeRemoteVideo, startAudioRecording. iOS sources verified at exactly the cited lines: sendPeerNotification :2501-2508 (peer.notify), sendPeerRequest :2510-2519 (DispatchQueue.global → peer.syncRequest), generateUid :2521-2523 (Int.random(in: 100000...9999999)), uidForPeer :2525-2527, resumeRemoteVideo :2529-2541, setVisibleRemoteVideoUids :2166, forceReconnect :834.

*Evidence:* grep -r over /Users/vivekmishra/Documents/GitHub/ZillitDesktop/{feature,desktopApp,core}: 0 hits each; MediasoupCallEngine.swift:2501-2541, 2166, 834

### WRONG — the mediasoup room id is a THREE-way election, not two. This is the highest-cost error in the map.

Analyst wrote `Mediasoup -> inviteCode ?: callUuid`. The Swift function is indeed `session.inviteCode.isEmpty ? session.callUuid : session.inviteCode` (CallingViewModel.swift:456-457) — but iOS's `inviteCode` FIELD is itself populated as `invite_code ?? room_id ?? ""` at CallingViewModel.swift:815, and its own doc comment states the derivation is `invite_code || room_id || call_uuid` (:450-455). The desktop keeps these in TWO separate fields: CallWire.kt:99 reads `invite_code`/`inviteCode` into `inviteCode`, and CallWire.kt:88 reads `room_id`/`roomId` into a separate `CallSession.roomId` (CallModels.kt:175) that exists today and is unused for joining. So the correct desktop election is `inviteCode.ifBlank { roomId }.ifBlank { callUuid }`. Following the analyst's two-way version on any payload that carries `room_id` but no `invite_code` lands the desktop in a different SFU room from the peers — the exact failure the Swift comment warns about: "Joining the call-doc `call_uuid` when it differs from `room_id` lands the two ends in DIFFERENT SFU rooms (no audio)". Corollary correction: no NEW CallSession field is needed for the room; `roomId` already parses.

*Evidence:* CallingViewModel.swift:450-457 and :815 (`inviteCode: callData.inviteCode ?? callData.roomId ?? ""`); CallWire.kt:88, :99; CallModels.kt:175

### WRONG — the H.264 fact cites the wrong lines, and the risk framing needs one correction

The encoder/decoder factories are NOT at MediasoupCallEngine.swift:87-93 (that range is the ZL-20234 screen-share comment). They are at :140-141: `let encoderFactory = RTCDefaultVideoEncoderFactory()` / `let decoderFactory = RTCDefaultVideoDecoderFactory()`. Everything else in the fact holds: call.js:407 pins Agora to `codec: 'vp8'` and sidesteps codec negotiation entirely; the router's capability list comes from the server via `getRouterRtpCapabilities` (:2870) and is not in the iOS source. The cheapest check stands — evaluate `RTCRtpSender.getCapabilities('video').codecs` in the existing page. Note that iOS offering VP8+H.264 does not by itself prove the router NEGOTIATES H.264; it proves only that a VP8-only desktop is not guaranteed to interoperate. The check is still worth minutes.

*Evidence:* MediasoupCallEngine.swift:140-141, :2870; /Users/vivekmishra/Documents/GitHub/ZillitDesktop/desktopApp/src/main/resources/callengine/call.js:407

### INCOMPLETE — close-code handling misses 4409, which the Swift calls the terminal one

Analyst listed only `4000 / "closed by protoo-server" / "peer reconnected"`. The real predicate is ProtooWebSocketTransport.swift:421-445 and recognises TWO codes: `if closeCode == 4000 { return true }` (:422) AND `if closeCode == 4409 { return true }` (:429), the latter documented as "the SFU evicts a user's OLD device with terminal close code 4409 'replaced-by-other-device' the moment the NEW device joins. Must be recognized here — BEFORE any reconnect scheduling". The reason-text needle list (:435-441) is five entries, not two: "closed by protoo-server", "session replaced", "duplicate session", "peer reconnected", "replaced-by-other-device". There is also a reason-synthesis path for a bare 4409 with empty reason (:448-453, :482-486) because Apple normalizes custom 4000-range codes away. Two more concrete numbers for the wrapper: retry strategy is `RetryStrategy(retries: 600, factor: 2, minTimeout: 800, maxTimeout: 4000)` (:138), and the dial sends TWO headers — `Sec-WebSocket-Protocol: protoo` (:237) and `Origin: ios` (:238) — with `timeoutInterval = 30` (:239). Keepalive citations were off: the interval constant is `keepaliveIntervalSeconds: TimeInterval = 20` at :97 (not :93) and `startKeepalive()` is at :493 (not :491); the detector declares the socket dead when a ping's pong is still outstanding at the next tick (:521).

*Evidence:* ProtooWebSocketTransport.swift:97, 138, 237-239, 421-453, 482-486, 493-523

### MISSED — protoo-client's stock parser has no fallback for flag-less messages; iOS's does

The analyst called ProtooMessage.swift:6-120 "a hand-rolled port of exactly what protoo-client speaks". The flagged branches (:21-53) match protoo-client, but lines :56-81 are an EXTRA fallback with the comment "Server may not include 'response'/'notification'/'request' type flags. Match the original Zillit-iOS pattern: id present → response; method present → notification." It resolves id+method → request, id-only → response (with `ok` inferred from `data != nil`), method-only → notification. protoo-client v4's own Message.parse has no such fallback — it logs an error and drops the message. If the Zillit SFU ever emits a flag-less frame, stock protoo-client silently drops it where iOS handles it. Budget a parse-layer patch or a wrapper alongside the close-code wrapper. Also note request ids are minted client-side as `Int.random(in: 1..<10_000_000)` (:88).

*Evidence:* ProtooMessage.swift:15-82, :88

### MISSED — there is a THIRD line (P2P), and connection_type is not the authoritative field for it

The analyst treated `connection_type` as the P2P signal. CallModels.swift:504-507 says otherwise: `var isP2P: Bool { guard p2p?.eligible == true else { return false }; return connectionType != "sfu" }`, with the comment "The deployed dev backend ... signals P2P via `p2p.eligible` and does NOT send a top-level `connection_type` in the initiate response, so eligibility is the authoritative field." iOS runs a separate `P2PCallEngine` for these calls (CallingViewModel.swift:2039-2042, 7292-7302, 7387). The payload field is `p2p` (CodingKeys :540) carrying a `P2PEnvelope`. Consequence for desktop: a client that branches on `connection_type` alone will treat every P2P-elected call as SFU. The migration path is `call:migrate` (CallingViewModel.swift:7381-7420) — it carries `room_id`/`roomId`, `migration_id`/`migrationId`, `reason`, and adopts `mediasoup_server_url` or `sfu_url` when the session has no host yet (:7393-7397), then emits `call:migrated` with `{room_id, state:"completed", platform, from_user_id, migration_id}` (:7411-7419). A desktop with no P2P line should at minimum parse `p2p.eligible` so it can decline to join media rather than dial an SFU room the peers are not in.

*Evidence:* CallModels.swift:483-491, 504-507, 536-540; CallingViewModel.swift:7381-7420

### CORRECTED — activeSpeaker carries ONE peerId, and producerScore is self-only

Both event-mapping claims need sharpening. `case "activeSpeaker"` (MediasoupCallEngine.swift:4525-4532) reads a single `data["peerId"] as? String`, converts via peerIdToUid, and fires `.activeSpeakerChanged(uid:)` — it is one speaker, not a list, so it maps to `ActiveSpeakers(listOf(uid))`. `case "producerScore"` (:4534-4538 → handleProducerScore :1525-1545) reads `data["producerId"]` and `data["score"]`, where score is an ARRAY of simulcast-layer scores and iOS takes `.max()`; the producerId is matched against our OWN camProducer/micProducer to label it "video"/"audio"/"other". So it reports only our own uplink — which maps cleanly onto `NetworkQuality(uid = 0, ...)`, exactly the convention call.js:324-325 already uses for Agora ("Self only: uid 0 by the engine's convention"). Scale difference to document: mediasoup producer score is 0..10, Agora's is 0..6. There is no per-remote-peer score notification in the case list.

*Evidence:* MediasoupCallEngine.swift:1525-1545, 4525-4538; call.js:323-326

### MISSED — peer screen-share has no engine wire on the desktop at all

The analyst listed PeerScreenShare among events but did not notice it never comes from the engine. EngineBridge.parse has no `"peer-screen-share"` case (EngineBridge.kt:50-79) and call.js never emits one; the only producer of `CallEngineEvent.PeerScreenShare` in the tree is CallCoordinator.kt:640, fed from `PlaneEvent.UserFlags` off the roster row (`onPlaneFlags`, :631-648), gated on `event.agoraUid != 0`. Two consequences for Line 1: (a) the mediasoup `peerScreenShareStarted`/`peerScreenShareStopped` notifications (MediasoupCallEngine.swift:4588-4605, payload `{peerId}`) have nowhere to land unless a new page→Kotlin message type is added; (b) the existing roster path is doubly broken on mediasoup because it keys on `agoraUid`, which is the trap below. Related: `endCall` (:4607-4611 → connectionStateChanged(state: 4)) is a server-initiated call teardown with no desktop counterpart.

*Evidence:* EngineBridge.kt:50-79; CallCoordinator.kt:631-648, :704, :1008 (FIELD_SHARING_WIRE = "screen_share"); MediasoupCallEngine.swift:4588-4611

### CONFIRMED with one refinement — the uid→roster binding trap is real

All the mechanics check out. `peerIdToUid` (MediasoupCallEngine.swift:2752-2762) takes the substring before the first colon and returns `abs(identity.hashValue) % 9_900_000 + 100_000`; `generateUid()` (:2521-2523) is `Int.random(in: 100000...9999999)`; peerUidMap is mutated ONLY inside peerIdToUid (:2758, :2760) and its removals, so it is keyed by user-id identities. The desktop side: `bindUids` is at CallTiles.kt:76-89 (analyst said 74-87), `known` at :81 is correct, guestTile fallthrough at :62. Refinement the analyst glossed: bindUids has a rescue at :84-88 — when exactly one roster row is unbound (numericUid == 0 AND status == InCall) and exactly one stream is orphaned, they are paired. That rescue does NOT save the mediasoup 1:1 case, because the peer's roster `agora_uid` is its own non-zero random number, so the row lands in `known` and never reaches `unbound`. The analyst's conclusion holds; the mechanism is one step subtler than stated. The seam is confirmed: CallEngineEvent.PeerJoined declares `peerId: String? = null` (CallEngine.kt:23), call.js:308 emits `{type:'peer-joined', uid}` with no peerId, and EngineBridge.kt:56 drops it. The uidForPeer(deviceId:) suspicion is also confirmed as a real anomaly — CallingViewModel.swift:4854-4856 passes a bare Firebase `device_id` into a map whose keys are user ids, which can only miss and mint a fresh polluting entry. Do not port it.

*Evidence:* CallTiles.kt:62, 76-89; CallEngine.kt:23; EngineBridge.kt:56; call.js:308; MediasoupCallEngine.swift:347, 2521-2527, 2752-2762; CallingViewModel.swift:4852-4858

### CONFIRMED — session parsing gaps, with exact wire names

CallWire.readCallSession (CallWire.kt:76-124) parses none of the SFU fields; verified field by field. iOS names them at CallModels.swift:483-491 with CodingKeys at :509-541 (analyst said 530-540): `connection_type` → connectionType, `sfu_token` → sfuToken, `mediasoup_server_url` → mediasoupServerUrl, `sfu_url` → sfuUrlAlias, and `var electedSfuHostRaw: String? { mediasoupServerUrl ?? sfuUrlAlias }` at :490 — canonical preferred, exactly as stated. normalizeSfuHost is at MediasoupCallEngine.swift:114-121 (correct as cited), 6 lines, and the wss://https:// bug comment is at :107-113. sfuToken's "empty = today's tokenless dial (backward-safe)" comment is :96-99 (correct), and the append is :754-757, percent-encoded with .urlQueryAllowed. The dial URL is `wss://<host>/?roomId=<room>&peerId=<peerId>` at :736 with `&token=` appended (correct). localPeerId is `"\(peerUserId):\(CallConfig.shared.currentDeviceId)"` at :721 — the analyst cited 709-716, which is the fallback-chain comment; the chain runs :711-720 and the assignment is :721. displayName in the join payload is :2926, not :2925 (payload block :2923-2931, keys: forceTcp, producing, consuming, displayName, device, rtpCapabilities, sctpCapabilities). CallConfig.MEDIASOUP_URL at CallConfig.swift:72-81 with calling-sfu-qa / mediasoup-dev / calling-sfu-prod — correct; ConfigLoader has only AGORA_APP_ID_SUFFIX (ConfigLoader.kt:123) — correct.

*Evidence:* CallWire.kt:76-124; CallModels.swift:483-491, 509-541; MediasoupCallEngine.swift:96-99, 107-121, 721, 736, 754-757, 2923-2931; CallConfig.swift:72-81; ConfigLoader.kt:123

### CONFIRMED with a citation fix — isJoinable, joinMedia, and the inverting test

`val isJoinable: Boolean get() = provider == CallProvider.Agora && channelName.isNotBlank() && token.isNotBlank()` is at CallModels.kt:226-227, verbatim. It is the single gate in joinMedia (CallCoordinator.kt:804-813, check at :807), and engine.join is called at :824-829. The doc comment to flip is CallModels.kt:103-107 (analyst said 104-111; :108-111 is the ofWire body itself). CallWireTest.kt:118-122 does assert `assertFalse(mediasoup.isJoinable)` on `{"call_uuid":"u","line":"mediasoup","invite_code":"inv-1"}` and will invert — correct. Citation fix elsewhere in the analyst's map: `fail("call token expired")` is CallCoordinator.kt:697, not :692 (:692 is the TokenExpiring log line).

*Evidence:* CallModels.kt:103-111, 226-227; CallCoordinator.kt:697, 804-829; CallWireTest.kt:113-122

### MISSED — ReconnectWatchdog ignores EngineConnection.Failed entirely

The analyst's advice to emit exactly the four Agora literals is right but under-argued. ReconnectWatchdog.onConnectionChanged (ReconnectWatchdog.kt:29-35) is `Connected -> cancel(); Reconnecting, Disconnected -> start(); else -> Unit`. `EngineConnection.Failed` falls into `else` and starts no grace clock. Since EngineBridge.connection() (EngineBridge.kt:101-107) can never produce Failed (no state string maps to it), this is latent today but becomes live the moment a mediasoup module invents its own state names: an unknown string maps to Connecting, which also does nothing. So the mediasoup page module must emit exactly "CONNECTED" / "RECONNECTING" / "DISCONNECTED" / "DISCONNECTING" or the watchdog never arms and a dead call hangs open indefinitely. Also confirmed: the desktop has no reconnect ACTION — the watchdog only calls onGiveUp("lost connection") after graceMillis (:47-53).

*Evidence:* ReconnectWatchdog.kt:29-53; EngineBridge.kt:101-107

### ANSWERED — the Agora SDK does patch RTCPeerConnection globally

The analyst left this as "a five-minute check". It is done. agora-rtc-sdk-ng-4.24.2.js embeds webrtc-adapter and assigns to sixteen RTCPeerConnection.prototype members on the global: addIceCandidate, addStream, addTrack, addTransceiver, createAnswer, createDataChannel, createOffer, getLocalStreams, getReceivers, getRemoteStreams, getSenders, getStats, removeStream, removeTrack, setLocalDescription, setRemoteDescription. Adapter markers present: shimPeerConnection (2), shimGetUserMedia (3), browserDetails (1), "adapter.js" (7). Most patches are feature-guarded (e.g. `if(!e.RTCPeerConnection.prototype.getSenders){...}`) so a current Chromium will skip the legacy ones, but the shims are not all no-ops and they land on the global prototype that mediasoup-client's Chrome111 handler also uses. Verdict: co-residency is probably safe but is not free, and this converts the analyst's lazy-load-per-line recommendation from a tidiness preference into the correct call for a concrete reason — only load the SDK for the line being joined.

*Evidence:* grep over /Users/vivekmishra/Documents/GitHub/ZillitDesktop/desktopApp/src/main/resources/callengine/agora-rtc-sdk-ng-4.24.2.js

### CONFIRMED — the call.js load-time Agora dependency and the page/bundle mechanics

call.html:209-210 loads the SDK then call.js unconditionally; the SDK is 1,371,979 bytes on disk (exact). call.js:265-266 assigns `AgoraRTC.onMicrophoneChanged` / `AgoraRTC.onPlaybackDeviceChanged` at IIFE top level (same indentation level as the function declarations, executed at load), so removing the script tag throws a ReferenceError there, window.zillitCall (:400) is never assigned, the ready message (:734) never fires, and initialize() times out (KcefCallEngine.kt:172-178). Blocker confirmed, two-line fix confirmed. PAGE_FILES is `listOf("call.html", "call.js", "agora-rtc-sdk-ng-4.24.2.js")` at KcefCallEngine.kt:417 with extractPage at :406-415 — one more file, one more string, correct. Surface machinery confirmed single-browser: _surface :100, surface :110, holder :70, surfaceClaim :98, hostSurface :201-210, OffscreenHolder :351. Screen capture arg at KcefRuntime.kt:126, JBR's own JCEF via JCefAppConfig at :60-79 — both correct. Two citation fixes: `setSpeaker(_enabled) {}` is call.js:515 (not :513), and output routing is `applySpeaker`/`track.setPlaybackDevice` at call.js:236-238 (not :71-74). mountTracks :182 and playLocal :152 are correct. demo() is :698-728. Device enumeration via AgoraRTC.getMicrophones/getPlaybackDevices/getCameras is :244-248 with the emitted shape at :250-257 — the field names EngineBridge.kt:69-75 parses.

*Evidence:* call.html:209-210; call.js:236-238, 244-257, 265-266, 400, 515, 698-734; KcefCallEngine.kt:70, 98, 100, 110, 172-178, 201-210, 351, 406-417; KcefRuntime.kt:60-79, 126

### CONFIRMED — the bundle measurement is real and reproducible

I re-verified the artifacts on disk rather than trusting the numbers. /private/tmp/.../scratchpad/msbundle contains bundle.js at exactly 504,464 bytes and bundle.min.js at exactly 236,369 bytes, from an entry.js of `import * as mediasoupClient from 'mediasoup-client'; import protooClient from 'protoo-client'; window.mediasoupClient = ...; window.protooClient = ...`. package-lock resolves mediasoup-client 3.18.7 and protoo-client 4.0.8, matching the claim. Ratio to the shipped Agora SDK (1,371,979 B) is ~1:5.8 unminified, ~1:5.8... precisely 17% minified. The commit-a-prebuilt-bundle option (a) does match the existing precedent — the Agora SDK is itself a committed resource file.

*Evidence:* /private/tmp/claude-501/-Users-vivekmishra-Documents-GitHub-ZillitDesktop/0f59a02a-383a-4035-9c5f-929f3c94fd3c/scratchpad/msbundle/{bundle.js,bundle.min.js,entry.js,package-lock.json}

### CONFIRMED with corrections — peer notification/request payloads

All eleven call sites verified. muteAudio: `sendPeerNotification(method: "muteAudio", data: ["peerId": CallConfig.shared.currentDeviceId, "muted": muted])` at CallingViewModel.swift:1763-1766 — note peerId is the DEVICE id here. toggleHandRaise: `sendPeerRequest(method: "toggleHandRaise", data: ["raisedHand": newRaised])` at :2046-2048. startScreenShare/stopScreenShare with empty data at :3083, :3162, :3345, :6266. recording: `sendPeerNotification(method: "recording", data: ["peerId": recordingPeerId, "recording": true, "callUuid":…, "deviceId":…, "userId":…])` at :3489-3499 where `recordingPeerId = isAgora ? currentDeviceId : currentUserId` (:3486-3488) — the analyst captured this correctly; plus `startClientRecording` (:3507-3512) / `stopClientRecording` (:3578) with `["peerId": currentUserId, "userId":…, "deviceId":…, "recording": true]`. Inbound sides verified: peerRaisedHand :4540, peerLoweredHand :4549, muteAudio :4558 (`{peerId, muted}`), recording :4566 (`{peerId, recording}`), peerRecordingStarted :4574, peerRecordingStopped :4581, peerScreenShareStarted :4588, peerScreenShareStopped :4599, endCall :4607. Desktop mirrors verified: toggleMicrophone → "isMute" (CallCoordinator.kt:401-406), toggleHand → "raise_hand" (:422-427), toggleCamera → "has_video" (:437-442), ScreenShare → "screen_share" (:699-705, constant at :1008), all through mirrorMediaState → plane.announceSelf (:454-458). The iOS mute path DOES write both Firebase and protoo (firebaseManager.updateUserFields at :1755-1760 then the notify at :1763) — so the analyst's UNVERIFIED flag on partial Firestore-only survivability is correctly hedged. Note the hand-raise path is protoo-ONLY on mediasoup: there is no Firebase write beside `sendPeerRequest("toggleHandRaise")` at :2046, only a P2P DataChannel branch above it (:2039-2042).

*Evidence:* CallingViewModel.swift:1755-1766, 2039-2048, 3083, 3162, 3345, 3486-3512, 3578, 6266; MediasoupCallEngine.swift:4540-4611; CallCoordinator.kt:401-458, 699-705, 1008

### CORRECTED — TURN credentials shape, plus a base-URL risk the analyst did not flag

The endpoint contract is documented at MediasoupTurnCredentialsService.swift:17-43 (analyst said 19-45): `GET /api/v2/webrtc/turn-credentials` with headers moduledata / iosversion / bodyhash, returning `{status, message, messageElements, data: {iceServers: [{urls: [...], username, credential}], ttl}}`. The DTOs are at :108-112 (TurnIceServerDTO: urls [String], username, credential) and :114-120 (TurnCredentialsData: iceServers, ttl: Int?) — analyst said 105-115. Two things the analyst omitted: the response carries `ttl` in seconds, with iOS falling back to a conservative 600 s when absent; and `urls` is always the ARRAY form (the comment notes the spec allows a bare string but the backend always emits an array, so no union handling is needed). The risk the analyst missed: CallApi's base is `config.apiV2(ZillitService.Calling)` (CallApi.kt:43) — a Calling-service-scoped v2 base — while the TURN route sits under `webrtc`, not `calling`. Whether that base can reach it needs checking before budgeting the fetch as trivial. Also load-bearing for step 7 ordering: iOS blocks on credentials INSIDE joinImpl before creating either transport, because "A transport's iceTransportPolicy is LOCKED at creation" (MediasoupCallEngine.swift:2887-2900); a fire-and-forget fetch lost that race and produced the cellular-call failure. Port the bounded wait, not the fetch alone.

*Evidence:* MediasoupTurnCredentialsService.swift:17-43, 108-120; MediasoupCallEngine.swift:2887-2900; CallApi.kt:43

### CONFIRMED with citation fixes — join sequence, watchdogs, and the concurrency detail

joinImpl (MediasoupCallEngine.swift:2866+) runs: getRouterRtpCapabilities (:2870) → device.load → read device rtpCapabilities/sctpCapabilities → ensureTurnCredentialsLoaded (:2900) → fetchBothTransportInfos (:2912) → createSendTransport + armMediaConnectWatchdog("send") → createRecvTransport + armMediaConnectWatchdog("recv") → join (:2932). Order as the analyst stated. Correction: the concurrent transport fetch is NOT at :2902-2919 — that range is the explanatory comment (:2903-2910) and the one-line call (:2912). The implementation is `fetchBothTransportInfos` at :3142-3186, which fires two `peer.request("createWebRtcTransport", …)` calls (:3166, :3170) and fails with `ProtooError(code: 408, reason: "createWebRtcTransport pair timed out after 10s")` at :3178. Join watchdog confirmed at :791-815: comment :791-800, the 12 s fresh-peerId rejoin at :801-809, the 30 s `connectionStateChanged(state: 4, reason: 0)` at :810-815, both gated on `!closed && !hasJoinedOnce` (hasJoinedOnce declared :392, set true at :2953).

*Evidence:* MediasoupCallEngine.swift:392, 791-815, 2866-2933, 2953, 3142-3186

### CONFIRMED — engine construction and the join-signature problem

buildCallEngine is AppGraph.kt:1244-1256: `val appId = config.agoraAppId; if (appId == null) { … return NoopCallEngine() }` then `return KcefCallEngine(appId, appScope).also(Shutdown::engine)`. Both consequences the analyst drew are correct — an env without AGORA_APP_ID gets NoopCallEngine and would silently disable mediasoup too, and appId is baked into the join script at EngineBridge.kt:111-112 (`zillitCall.join(${quote(appId)}, ${quote(channel)}, ${quote(token)}, $uid, $withVideo)`). The params-object recommendation is sound. One citation fix: `selfName` is passed at AppGraph.kt:1227, not :1226 (:1226 is `plane = buildStatusPlane(...)`). Note the interface has more implementations than the analyst counted if you include test doubles — verify the blast radius before changing the signature rather than trusting the number 5.

*Evidence:* AppGraph.kt:1220-1256; EngineBridge.kt:111-112; CallEngine.kt:127, 209

### Open questions

- Does the Zillit mediasoup router negotiate H.264 for video, and does JetBrains Runtime's bundled JCEF ship an H.264 encoder? Unanswerable from either source tree — the router's codec list arrives at runtime from getRouterRtpCapabilities (MediasoupCallEngine.swift:2870). The offline half is still the cheapest first move: load the existing call page and evaluate RTCRtpSender.getCapabilities('video').codecs.
- Can CallApi's base — config.apiV2(ZillitService.Calling) at CallApi.kt:43 — actually reach GET /api/v2/webrtc/turn-credentials, which is scoped under `webrtc` rather than `calling`? If not, the TURN fetch needs its own base and step 8 is not 80 lines.
- Is CallingViewModel.swift:4854 (`callEngine.uidForPeer(deviceId: deviceId)`) a latent iOS bug? peerUidMap is only ever keyed by user-id identities (MediasoupCallEngine.swift:2757-2760), so passing a bare Firebase device_id must miss and mint a polluting entry — unless mediasoup call_users rows carry user ids in their device_id field. Someone with a live mediasoup call should dump one call_users row before anyone ports this.
- Does the web client read hand-raise, mute, screen-share and recording state from Firebase as well as from protoo broadcasts? iOS writes both for mute (CallingViewModel.swift:1755-1766) but hand-raise on mediasoup is protoo-only (:2046, with no Firebase write beside it), which suggests protoo parity is required rather than optional. The analyst's hedge stands; the web client is the place to settle it.
- Should the desktop parse the `p2p` envelope at all? It has no P2P line, but without reading `p2p.eligible` it cannot tell a P2P-elected call from an SFU one (connection_type is absent on the dev backend, CallModels.swift:499-503), and would dial an SFU room the peers are not in. Declining media on an eligible-P2P call may be the honest Line-1 behaviour — worth a product decision before step 4.
