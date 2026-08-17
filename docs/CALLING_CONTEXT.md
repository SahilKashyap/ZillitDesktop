# Zillit Desktop — Calling (Agora "Line 2") Development Context

Context handoff for anyone (human or Claude Code) continuing the calling work in
the Kotlin Multiplatform desktop client. Everything below was established
against the **live develop environment** and the three reference clients —
treat it as ground truth until the server says otherwise.

## Reference clients and their authority

| Client | Path | Authority |
|---|---|---|
| Android | `/Users/sahilkashyap/AndroidStudioProjects/ZillitAndroidV20` | **Business logic + wire protocol.** `app/src/main/java/com/zillit/zillitapp/callingv2/` (~40 files) is the canonical implementation |
| Web | `/Users/sahilkashyap/Downloads/zillit_web-dev 2` | UI reference; `src/components/WebCalling/` + `firebaseInit.js`. Uses `agora-rtc-sdk-ng` 4.24.2 |
| iOS | `/Users/sahilkashyap/Downloads/Zillit-IOS` | Most wire-accurate for the calendar family; ring timeouts were matched to iOS (60s both sides) |

Two call providers exist server-side: **Mediasoup "Line 1"** (invite-code +
protoo SFU dial) and **Agora "Line 2"** (channel + token + uid). The desktop
implements **Line 2 signalling only** so far. The server chooses the line and
says so in the call's `line` field — never choose client-side; joining the
wrong stack fails silently.

## What is built (desktop, module `feature:calls`)

```
feature/calls/src/commonMain/kotlin/com/zillit/desktop/feature/calls/
├── domain/
│   ├── CallModels.kt        CallSession, CallPhase, CallStatus, CallProvider,
│   │                        CallMode, CallType, CallParticipant, CallTimeouts,
│   │                        direction-aware displayName/displayUserId
│   └── CallEngine.kt        The MEDIA SEAM: CallEngine interface + CallEngineEvent
│                            + NoopCallEngine (the no-app-id fallback)
├── data/
│   ├── CallWire.kt          Tolerant JSON readers/writers for every call payload
│   ├── CallApi.kt           REST surface (Android ApiUrl paths verbatim)
│   ├── CallCoordinator.kt   THE state machine — one instance app-wide
│   ├── EngineBridge.kt      The Kotlin ⇄ call.js contract, as pure functions
│   ├── CallStatusPlane.kt   Firestore-mirror interface + NoopCallStatusPlane
│   └── FirestoreCallStatusPlane.kt   Firestore REST implementation
└── ui/
    ├── CallViewModel.kt     projection + timer + ended notice
    └── CallOverlay.kt       ring card / outgoing card / in-call bar / notice
```

Host wiring:
- `desktopApp/.../AppGraph.kt` — `buildCallEngine(...)` + `buildCallCoordinator(...)`
  + `buildStatusPlane(...)`; coordinator lives on `appScope`, exposed on
  `AppGraph.Ready.callCoordinator`.
- `desktopApp/.../Calls.kt` — `CallSurface` floats the overlay above the whole
  workspace (a ring must interrupt any tool; the bar must survive tab switches).
- `desktopApp/.../main.kt` — `CallViewModel` in the `AppViewModels` holder;
  chat's `ChatToolProvider` gets an `onCall` hook.
- Chat thread header (`feature/chat/.../ThreadPane.kt`) shows Phone/Camera
  buttons only when the peer is callable (DM: has `deviceId`; group: always).

New design-system icons: `Phone`, `PhoneDown`, `MicOff`, `Camera`, `CameraOff`
in `core/designsystem/.../icon/ZillitIcons.kt`.

`device_id` was threaded end-to-end for callability:
`GET project/users` row → `ProjectUserDto` (`core:session`) → `UserSnapshot` +
SQL cache column (`core:database`, schema auto-reconciles) → `CrewContact`
(`feature:chat`). While there, a latent bug was fixed: the cached-users read
was dropping `keepNamePrivate`.

## The media engine (done — Agora Web SDK in the JBR's Chromium)

**Agora ships no JVM desktop client SDK.** Their desktop targets are native
Windows (C++/C#), macOS (ObjC/Swift) and Electron; the "Java SDK" is a Linux
*server* SDK with no capture or rendering. So the media stack is the same one
the web client runs — `agora-rtc-sdk-ng` 4.24.2 — hosted by embedded Chromium.

Live on develop: audio and video calls join Agora, the microphone and camera
are captured (macOS shows both indicators), remote video renders, mute reaches
the device, and hang-up releases everything.

```
desktopApp/.../KcefRuntime.kt     CefApp bootstrap — once per process
desktopApp/.../KcefCallEngine.kt  the CallEngine: page lifecycle + JS transport
desktopApp/.../KcefPage.kt        CEF handlers (message router, load, console)
desktopApp/src/main/resources/callengine/   call.html + call.js + the Agora SDK
```

### Three facts that cost a day each — do not rediscover them

1. **Use the JetBrains Runtime's own JCEF; do not add one as a dependency.**
   The app runs on JBR, and JBR carries a complete CEF (framework, helper
   apps, and `org.cef` as a *platform module*). A platform module outranks the
   classpath, so a downloaded JCEF cannot win: you compile against one build
   and run against another. The symptom is silent and expensive — the browser
   constructs, reports no error, and never loads a page. `KcefRuntime` now
   calls `JCefAppConfig.getInstance()` → `CefApp.startup(args)` →
   `CefApp.getInstance(args, settings)`. This deleted the KCEF dependency, its
   ~200 MB first-run download, the install directory, and a `libjawt.dylib`
   planting hack. `desktopApp` pins **both** its toolchain and `javaHome` to
   `JvmVendorSpec.JETBRAINS` so the two ends cannot drift, and `jcef` is in the
   `jlink` module list — a trimmed runtime without it packages an app whose
   calls have no audio.

2. **A windowed JCEF browser does not exist until its AWT component is
   realised** — no native browser, so no page load, no `ready`, no media. And
   nothing displays the call page until a call is up, which is the call that
   needs it. `KcefCallEngine.hold()` breaks the cycle: the component lives in
   an undecorated `JWindow` parked at −8000,−8000, which satisfies AWT while
   nobody sees it. Set the window's bounds *after* adding the component —
   creation rides the hierarchy-bounds events, and a window sized before it had
   a child sends none. `createImmediately()` is **not** the fix: it creates a
   parentless native browser that then blocks the real creation path.

3. **Off-screen rendering is not the escape hatch.** It would let the page load
   unseen, but JCEF draws OSR frames through jogamp, whose natives are separate
   classified artifacts that gluegen only finds beside its own jar — which is
   not how Gradle or jpackage lay out a classpath (`Can't load library:
   .../natives/macosx-universal/gluegen_rt`). Windowed rendering also gives
   video the GPU path.

Video is a heavyweight AWT surface (`SwingPanel`) and therefore paints over
anything Compose draws — which is why `CallOverlay` reserves
`VIDEO_BOTTOM_CLEARANCE` and puts the in-call bar *below* the video rather than
on it. When a video call ends, `releaseSurface()` puts the component back in
the holder window; without that it would be left with no parent and the *next*
call would be the one that broke.

`NoopCallEngine` remains the engine when no `AGORA_APP_ID` is configured for
the environment: signalling runs, media does not.

## Wire contract (hard-won facts — do not rediscover these)

### REST (base = `config.apiV2(ZillitService.Calling)`, i.e. `*_CALLING_BASE_URL/api/v2/`)

| Call | Path | Verb |
|---|---|---|
| Place call | `call/new-call` | POST `{call_mode, call_type, has_video, receiver_device_id?, chat_room_id?}` |
| End for everyone | `call/end-call` | PUT `{call_uuid, device_id}` |
| Missed (one) | `call/log-miss-call` | PUT `{call_uuid, device_id}` |
| Missed (many) | `call/log-miss-call-multiple` | PUT `{call_uuid, device_id: [...]}` |
| Add user | `call/add-user` | PUT `{call_uuid, receiver_device_id, call_type}` |
| My status | `mediasoup-call/call-response` | POST `{roomId, response, fromUserId}` — **generic**, used by Line 2 as well despite the prefix |
| Roster snapshot | `mediasoup-call/call-dump/{roomId}` | GET — fetch right after joining; a joiner missed every `call:update` before it subscribed |
| Guest respond | `mediasoup-call/guest-join-request/respond` | PUT |

All under `RequestModule.ProjectUser` headers, with `CallOptions(projectId =
session.projectId)` because **a call can belong to a production other than the
open one** — every self-comparison downstream breaks if you assume otherwise.

Blank-id guard: never POST a call-response with blank roomId/fromUserId (the
backend rejects "must have a valid ObjectId"); final statuses are sent under
`withContext(NonCancellable)` — a status that dies with its scope is the
"caller cancelled but callee still rings" bug class.

### Socket (the CNC socket, `core:socket` `ZillitSocketEvents.Calls`)

| Event | Meaning |
|---|---|
| `cnc:incoming-call` | The ring. Payload is the **flat call object**, but tolerate `{data:{…}}` |
| `call:update` / `call:response` | Participant status changes (both spellings of room key: `roomId` **and** `room_id`) |
| `call:ended` / `call:group-call-ended` | Broadcast to every room member (not just participants) |
| `call:timeout` | Rang out |
| `call:incall-data` | Reactions/ephemeral chat; **sent** via CNC generic `custom:events` `{event, rooms, eventData}` |
| `call:handoff` / `-done` / `-evict` | Device handoff; on evict **leave media but never call end-call** — the call lives on the other device |
| `call:migrate` | P2P→SFU migration (Line 1) |
| `call:guest:join:request` / `:responded` | Guest admission |

Desktop has **no FCM**; the socket is the only ring path (Android dedupes
socket vs FCM by `invite_code` — nothing to dedupe here).

### Payload tolerances (all pinned by tests in `CallWireTest`)

- REST create-call response reaches the reader **already `data`-peeled** by
  `ApiClient`, as `{success, call:{…}}` → unwrap must also descend into `call`
  at top level. (Cost a live debugging round; don't regress it.)
- `agora_uid` arrives as **number or string** depending on payload; carry as
  string, convert at the engine boundary.
- Status `in_call` (Agora/Firebase) vs `incall` (Mediasoup/P2P) — fold both;
  matching only one reproduces Android's ring-that-wouldn't-stop bug.
- Random-call flag: `is_random_call` (stored doc) **and** `isRandom` (live
  payloads). Missing one misroutes decline quick-replies into a hidden room.
- `call_users` arrays mix bare strings among objects — skip non-objects,
  never reject the roster.
- Unknown participant status reads as `Ringing`, not null/crash.
- `receiver_user_id` on the invite is **our id in the call's production** —
  prefer it over ambient identity.
- `agora_uid` is often **absent from the invite**, so the desktop joins with
  uid 0 and Agora issues one. The number on the wire is then not the number in
  the payload: `CallCoordinator.recordMediaUid` takes the uid off
  `CallEngineEvent.Joined` and mirrors it, because every other platform maps
  roster rows to media streams by `agora_uid`.
- Self-echo: the server rings every device in the room including the caller's;
  gate on `sender_device_id == selfDeviceId` or callers ring themselves.

### Status ladder (`CallStatus`)

`caller` → `ringing` → `in_call` | `declined` | `leave` | `not_answered`,
global `End Call`. Timeouts 60 s outgoing **and** incoming (iOS parity;
Android's old 58/50 split made the receiver give up before the caller's UI).

## Firebase status plane (Firestore)

Line 2 mirrors ring state in Firestore alongside the socket; Android/web treat
it as authoritative for **cross-device ring dismissal**.

Schema (field names are the cross-platform contract — do not rename):
```
calls/{call_uuid}                     status ("End Call" = over), guest_invite_link, …
calls/{call_uuid}/call_users/{device_id}
    device_id, user_id, user_name, agora_uid, current_status,
    has_video, isMute, raise_hand, updated_from ("Android"|"web"|"iOS"|"Desktop")
call_history/{call_uuid}              written by web on save
```

Desktop implementation (`FirestoreCallStatusPlane`):
- **Firestore REST API** (`firestore.googleapis.com/v1/projects/{pid}/databases/(default)/documents/…?key=API_KEY`)
  because no Firebase client SDK exists for desktop JVM.
- **Field-masked PATCH merge** (`updateMask.fieldPaths=…`) — REST spelling of
  `set(merge:true)`. Merge, not update: web grew `upsertUserFields` because
  update loses declines on rows the backend hasn't created yet. The mask
  matters: an unmasked patch erases the other platforms' fields on the row.
- **Polling, 2 s, only while a call is active** — REST has no `onSnapshot`.
- Rides the **plain** HTTP client (`storageClient`), never the Zillit API
  client — the moduledata/bodyhash headers must not leak to Google.
- Failure posture: mirror, never primary. Log + swallow; a 401/403 latches
  `disabled` for the session (App Check enforcement would surface this way)
  and calling degrades to socket-only. Startup logs plane on/off.
- Writes (mirroring Android exactly): ring→`ringing`; accept→`in_call` +
  `agora_uid` + `has_video` + `user_name` (doc self-heal); decline→`declined`;
  hang-up→`leave`; **last one out** also writes call doc `status: "End Call"`.
- Reads run through the same reducer as socket events — whichever channel wins
  the race, the second is a no-op. Own row flipping `in_call`/`declined` with
  `updated_from != "Desktop"` while phase == Incoming → quiet
  "answered on another device" dismissal.

### Config

`AppConfig.firebase: FirebaseConfig?` from properties keys (per env prefix
`STG_`/`QA_`/`PROD_`): `*_FIREBASE_PROJECT_ID`, `*_FIREBASE_API_KEY`.
Both-or-neither; absent → `NoopCallStatusPlane` (socket-only).
**Values live in `~/.zillit/zillit.properties`** (mode 600), copied
file-to-file from Android's `app/src/{develop,qa,prod}/google-services.json`.
Never print values into a transcript, log, or commit; the standing rule for
this repo is that key material moves `grep | >>` style between files only.

## Coordinator rules worth knowing before touching it

`CallCoordinator` is the ONE state machine (app-scoped singleton). Key gates:

- `placeCall` refused unless `Idle` — two sessions fighting one microphone.
- Busy invite → decline **scoped to the ringing call's ids**, not the active
  call's (`invite.selfUserId`, `invite.projectId`).
- `joining` one-shot latch — accept path and a racing `call:update` can both
  conclude "join now"; joining an Agora channel twice with one uid kicks the
  first join out.
- Accept sends status and joins media **in parallel** — media must not wait on
  an HTTP round trip (also what makes the tests deterministic).
- 1:1 outgoing ends when the one callee declines/rings out; a **group** call
  only ends on the server's say-so.
- Hang-up: Android's rule — the **last active participant** ends the call
  globally (`End Call` + REST end-call); anyone else merely leaves. Ending a
  room three people are talking in because one hung up is the bug this avoids.
- Handoff-evict: leave media, do NOT end-call.
- Outgoing display identity: the create-call response describes **the caller
  (us)** — the callee's name is stamped from the screen that pressed the
  button (`CallEvent.Place.displayName` → `session.title`;
  `displayName`/`displayUserId` on `CallSession` are direction-aware).

## Verified live (develop) vs pending

Verified on screen + logs:
- Call buttons render only for callable peers; outgoing card shows callee name
  + photo + pulse; cancel/hang-up → `Call ended` notice → Idle.
- Wire sequence observed: `new-call` → (join) → `call-dump` → hang-up →
  `leave` mirrored to Firestore (`mirrored calls/{uuid}/call_users/{device}`,
  HTTP 200 — **dev Firestore rules accept desktop REST writes with API key
  alone**, no App Check block today) → `call-response left`; room with other
  live participants was correctly LEFT, not ended.
- Join-active: the Vivek Mishra DM room on develop carries a standing test
  call — a new dial attaches and arrives already `in_call` with ~6 users.
  That instant in-call bar is real server state, not a bug.

Media verified live on develop (2026-08-05): Chromium reports ready ~1.5 s
after launch; outgoing audio and video calls join Agora; macOS lights the
microphone and camera indicators and clears them on hang-up; remote video
renders in the surface with the in-call bar below it; mute reaches the device
(the OS indicator goes dark) and unmute restores it; consecutive calls —
including one straight after a video call, which is the surface hand-back —
all join.

Pending (needs a second device — ask the user to ring the desktop):
- Incoming ring card, accept/decline, `ringing` mirror, and the
  answered-elsewhere dismissal (double-covered socket + Firestore; unit-tested
  but not yet seen live).

## Tests

`feature/calls/src/commonTest/…` — 32 tests:
- `CallWireTest` — every payload tolerance above.
- `CallCoordinatorTest` — phase machine over a fake socket (`FakeSocket` impl
  of `SocketClient`); REST stubbed by pointing `ZillitService.Calling` at
  `https://127.0.0.1:9` (instant refusal → `ZillitResult.Failure`, which the
  status paths tolerate by design). Includes plane-driven dismissal tests via
  `FakePlane`.
- `CallStatusPlaneTest` — Ktor `MockEngine` (dep `libs.ktor.client.mock`):
  PATCH URL/mask/body shape, poll dedup + `End Call` verdict, 403 latch.
- `EngineBridgeTest` — the `call.js` event shapes and the JS-injection escaping.
  Everything about the media engine that *can* be tested off-device lives in
  `EngineBridge`, because the CEF half cannot run in a unit test.

Test gotchas: `runTest(StandardTestDispatcher())` + `runCurrent()`; real HTTP
escapes virtual time (hence port 9); `backgroundScope` surfaces uncaught
launch exceptions at teardown — config that *throws* on missing endpoints
must be given an endpoint.

## Build/verify workflow (this repo's standing practice)

```bash
./gradlew jvmTest detekt build && ./scripts/security-scan.sh
ZILLIT_ENV=develop ./gradlew :desktopApp:run   # background; `timeout` does not exist on this Mac
```

detekt thresholds that shaped the code: LongMethod 60, CyclomaticComplexity 15,
TooManyFunctions 20/file, LongParameterList 10 (8 top-level), ReturnCount 4,
MagicNumber, MaxLineLength 120. `main.kt` sits at the function cap — new app
wiring goes in its own file (see `Calls.kt`, `Alerts.kt`).

Security constraints (non-negotiable): certificate validation is never
disabled (security-scan is build-blocking); no secrets in code/logs/transcript;
`CallSession.toString()` redacts the Agora token and image URL — keep it so.
Kotlin block comments **nest**: a path glob like `call/*` inside a KDoc opens
an unclosed comment (cost a compile once).

## Remaining work, in rough order

1. Incoming-side live verification (needs a phone; unit-covered).
2. Ringtone playback (web: `calling/utils/ringtonePlayer.js`; loop while
   `Incoming`/`Outgoing`).
3. Add-user mid-call (REST exists in `CallApi.addUser`; needs UI drawer).
4. In-call chat/reactions (`call:incall-data` readers + envelope already in
   `CallWire.kt`; needs UI + send via CNC `custom:events`).
5. Guest join links (`call:guest:join:*` readers exist; `respondToGuest` in
   `CallApi`).
6. Screen share (engine capability exists on the interface; the page's
   `getDisplayMedia` route).
7. Device handoff initiation (evict handling done; *taking over* a call isn't).
8. Call logs/history (`GET call` + Firestore `call_history`).
9. Line 1 (Mediasoup) if ever needed — protoo/WebRTC, a separate project.
