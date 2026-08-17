# Zillit Desktop — Kotlin Multiplatform Build Plan (v2)

**Targets:** macOS (Apple Silicon + Intel), Windows 10/11 (x64 + ARM64), ChromeOS
**Business logic reference:** `/Users/sahilkashyap/AndroidStudioProjects/ZillitAndroidV20`
**UI / multi-tab reference:** `/Users/sahilkashyap/Downloads/zillit_web-dev 2`
**Scaffold:** `/Users/sahilkashyap/AndroidStudioProjects/Zillit` (Compose MP 1.11.1, Kotlin 2.4.10, JVM)

> **v2 changes:** web MDI system read and specified in §3 · calling re-planned around **LiveKit** (§7) · database decision finalized with encryption (§5) · maps via embedded webview (§6) · **new §8: security architecture**, including a critical finding in the current Android client (§8.1).

---

## 0. Scope reality check

| Metric | Android | Web |
|---|---|---|
| Source files | 2,563 `.kt` | ~2,900 `.jsx`/`.js` |
| LOC | ~768,700 | ~420,000 |
| XML layouts | 2,604 | — |
| `bottomNav/tools` | ~404,000 LOC (53%) | — |
| Largest module | `accounthub` ~111,900 LOC | `src/accountHub` |
| API endpoints (`ApiUrl.kt`) | 1,604 lines | — |
| Realm DB managers | 25 | Dexie (IndexedDB) |
| LiveKit calling | ~22,100 LOC / 62 files | *not yet ported (still Agora + mediasoup)* |

Full parity is a 12–18 month program for a small team. The plan below is **module-by-module and shippable at every step**: installable app with working sign-in at Module 3 (~week 9), useful daily-driver product at Module 6 (~month 7).

### 0.1 What ports cleanly

The Android app is already half-multiplatform, which is the single biggest cost saver here:

| Layer | Android today | Portable? |
|---|---|---|
| HTTP | **Ktor 2.3.6 + OkHttp engine** | ✅ engine swap + a Ktor 2→3 migration (package/API renames) |
| JSON | **kotlinx.serialization** | ✅ direct |
| Async | **Coroutines + Flow** | ✅ direct |
| API surface | `ApiUrl.kt`, `ApiConstants.kt`, `Repository`/`RepositoryImp` | ✅ near-verbatim |
| DTOs | `@Serializable` data classes | ✅ strip `@Parcelize` |
| Crypto | `javax.crypto` (AES/CBC) | ✅ native on JVM |
| Socket.IO | `io.socket:socket.io-client` (pure Java) | ✅ runs on JVM as-is |
| PDF | `pdfbox-android` | ✅ → upstream PDFBox 3 |
| **LiveKit call UI** | **Jetpack Compose** (~10k LOC) | ✅ **→ Compose Multiplatform** |
| DI | Hilt (`javax.inject` everywhere) | ⚠️ → Koin; constructor injection survives |
| Local DB | Realm Java | ❌ Android-only **and EOL** (§5) |
| Images | Glide | ❌ → Coil 3 |
| Feature UI | 2,604 XML layouts + Fragments | ❌ rewrite in Compose |
| LiveKit **media engine** | `io.livekit:livekit-android` | ❌ no JVM SDK (§7) |
| Maps | Google Maps Android SDK | ❌ → embedded webview (§6) |

**~35–40% of the Android codebase ports mechanically.** UI is ~55% rewrite. The rest needs platform-specific replacement.

---

## 1. Platform strategy

**macOS / Windows:** Compose Multiplatform desktop (JVM), packaged via `jpackage` into a signed `.dmg` and `.msi` with a bundled, `jlink`-trimmed JRE. No JVM install required.

**ChromeOS:** ship the same JVM binary as a `.deb` for the Crostini Linux container. Zero extra code, one codebase, three installers. The cost is that users must enable the Linux dev environment once.

> **Open question — still need your answer.** If your Chromebook users can't be asked to enable Crostini, the alternative is a `wasmJs` Compose target (installs as a PWA, no setup) — but that overlaps heavily with the web app you already have. My recommendation stands at Crostini `.deb`; I've kept the architecture free of JVM-only APIs outside `desktopMain` so adding `wasmJs` later is a build-file change plus platform `actual`s, not a rewrite.

---

## 2. Architecture

### 2.1 Module graph

```
Zillit/
├── desktopApp/                     # entry, window mgmt, tray, menu bar, packaging
│
├── core/
│   ├── common/                     # Result, dispatchers, logging, date/time, expect/actual
│   ├── config/                     # env (dev/qa/prod), feature flags, remote config
│   ├── security/                   # ⭐ crypto, keychain, TLS pinning, secure store, audit (§8)
│   ├── network/                    # Ktor client, header strategy, ApiUrl, Repository, errors
│   ├── database/                   # SQLDelight + SQLCipher, DAOs mirroring Android *DbManager
│   ├── datastore/                  # SharedPref port → DataStore; secrets → OS keychain
│   ├── socket/                     # Socket.IO client, typed event bus, reconnect
│   ├── filestore/                  # up/downloads, cache, S3/Box adapters, quarantine
│   ├── webview/                    # KCEF host + secure JS bridge (maps, rich text, fallbacks)
│   ├── designsystem/               # tokens from design-system/zillit/MASTER.md, components
│   └── workspace/                  # ⭐ the MDI/tab engine (§3)
│
├── feature/
│   ├── auth/  shell/  home/  tools/  chat/  email/  calendar/  drive/  settings/
│   ├── accounthub/  callsheet/  budget/  purchase-order/  deal-memo/
│   ├── forms-signature/  docsign/  production-report/  ad-dashboard/
│   ├── schedule/  boxschedule/  casting/  wardrobe/  location/  continuity/
│   ├── script/  sides/  transportation/  docdistribution/  recce/  weather/
│   └── calling/                    # LiveKit — see §7
│
└── build-logic/                    # convention plugins
```

Many modules is deliberate: at 700k+ LOC a single module destroys build times. Convention plugins keep each feature's build file to ~6 lines.

### 2.2 Technology decisions

| Concern | Choice | Replaces |
|---|---|---|
| UI | Compose Multiplatform + Material 3 | XML/Fragments |
| DI | **Koin** (KMP, no codegen) | Hilt |
| HTTP | Ktor 3.5.2 + `ktor-client-okhttp` | Ktor 2.3.6 |
| JSON | kotlinx.serialization | same |
| DB | **SQLDelight + SQLCipher** (§5) | Realm |
| Key-value | androidx.datastore (KMP) | SharedPreferences |
| Secrets | OS keychain (§8.4) | Android Keystore |
| Realtime | `io.socket:socket.io-client` | same |
| Calling | **LiveKit** — engine per §7 | livekit-android |
| Images | Coil 3 | Glide |
| PDF | Apache PDFBox 3 | pdfbox-android |
| Media | VLCJ (bundled libvlc) | Media3/ExoPlayer |
| Maps | **KCEF webview + Maps JS** (§6) | Maps Android SDK |
| Rich text | Compose rich editor; KCEF+TipTap fallback | richeditor-android |
| Charts | Compose canvas / Vico MP | MPAndroidChart |
| Crash/telemetry | Sentry JVM + Napier | Crashlytics + Timber |
| Packaging | jpackage + **Conveyor** (signing, delta auto-update) | Play Store |

### 2.3 Feature layering

```
feature/<name>/
├── data/     DTOs · ApiService · Dao · RepositoryImpl · Mapper
├── domain/   Models · Repository interface · UseCases
└── ui/       Screens · ViewModel (StateFlow) · UiState/UiEvent · ToolDescriptor
```

Offline-first read path, mirroring Android: DAO emits cached `Flow` immediately → API fetch in background → write-through → Flow re-emits. UI never blocks on network for data it already holds.

---

## 3. ⭐ The multi-window / multi-tab workspace

I read the web implementation in `src/components/toolWindows/` (2,064 LOC across 16 files). **We are porting this design, not inventing one.** It is more sophisticated than a browser tab strip, and the details below are lifted from the actual code — including the bugs it already fixed, which we should not re-introduce.

### 3.1 How the web version works

| Piece | File | Behaviour |
|---|---|---|
| State | `WindowManagerContext.jsx` (281) | `windows[]`, `layoutMode`, `activeId`, `availableTools`, `mdiEnabled` |
| Tab strip | `ToolTabStrip.jsx` (120) | "N open" + chip per tool (icon, label, badge, ×) + launcher + Cascade toggle + Close all |
| Window chrome | `ToolWindow.jsx` (222) | title bar, drag, resize, minimize/maximize/close |
| Desktop layer | `ToolWindowsLayer.jsx` (166) | portals to body, docks inside the measured content rect right of the sidebar |
| Per-window router | `FilmToolWindowHost.jsx` (130) | **each window gets its own `MemoryRouter`** |
| Registry | `toolRegistry.js` (243) | path → `{component, title, icon, openMode, defaultSize, ownRoutes}` |

**Behaviours that matter:**

1. **Two layout modes.** `tabs` — one maximized workspace, switch via the strip. `cascade` — free-floating draggable/resizable MDI windows, new ones docked bottom-right and offset diagonally (Gmail-compose style).
2. **Windows stay mounted while hidden.** *"switching tabs / minimizing never loses in-progress work."*
3. **Each window owns an isolated in-memory history** (`MemoryRouter`) — the browser URL never changes, the app behind stays mounted. Sub-apps (Account Hub, Sides, Settings, Permission Grid) set `ownRoutes: true` and render their own route subtree.
4. **One window per path.** Re-opening focuses the existing window *and* re-navigates its inner router (`reopenSeq` / `reopenPath`) — because a window keyed on its opening path may have navigated elsewhere since (ZL-20516).
5. **Bounded z-index with a short-circuit.** `raise()` returns the *same array* when the target is already frontmost — because re-creating it on every mousedown re-rendered all window content and wiped in-progress form state (ZL-20352).
6. **States:** `normal` / `minimized` / `maximized`, restoring to `prevState`.
7. **Badges on tabs** come from the same sources as the sidebar, so a tab and its rail entry always agree.
8. **Feature-flagged per project** via Firebase Remote Config (`mdi_windows_projectid`). Off → classic full-page navigation, `openWindow` is a no-op.
9. **Auto-open Home once**; deep links open as a window and normalize the URL; leaving the project closes everything and re-arms.
10. **Email compose pops out into a real browser window** (`email_v2/popout/WindowPortal.jsx`).

### 3.2 The Kotlin port — and where desktop does it better

```kotlin
// core:workspace
@Serializable @JvmInline value class WindowId(val value: String)

@Serializable
sealed interface WorkspaceRoute {
    @Serializable data class Tool(val path: String, val unitId: String? = null) : WorkspaceRoute
    @Serializable data class Chat(val roomId: String, val isGroup: Boolean) : WorkspaceRoute
    @Serializable data class EmailCompose(val draftId: String?) : WorkspaceRoute
    @Serializable data object Home : WorkspaceRoute
    // …
}

enum class WindowState { Normal, Minimized, Maximized, Detached }
enum class LayoutMode  { Tabs, Cascade }

data class ToolWindow(
    val id: WindowId,
    val route: WorkspaceRoute,
    val title: StateFlow<String>,
    val icon: ToolIcon,
    val state: WindowState,
    val prevState: WindowState?,
    val rect: WindowRect,
    val moved: Boolean,
    val z: Int,
    val badge: StateFlow<Int>,
    val isDirty: Boolean,
    val history: SnapshotStateList<WorkspaceRoute>,  // ← the MemoryRouter equivalent
)
```

**Direct translations:**

| Web | Compose Desktop |
|---|---|
| `MemoryRouter` per window | Per-window `SnapshotStateList<WorkspaceRoute>` + scoped `ViewModelStoreOwner` |
| Stays mounted while hidden | `SaveableStateHolder` keyed by `WindowId`, ViewModels retained |
| `raise()` identity short-circuit | Compare-and-skip in the reducer; never emit an equal list |
| `toolRegistry.js` | Koin-collected `ToolProvider` set (§3.4) |
| `ownRoutes: true` | `ToolProvider.hostsOwnRoutes` |
| Remote Config `mdiEnabled` | Same flag via `core:config` |
| Portal into measured content rect | Compose `Box` in the frame layout — no measuring hacks needed |

**Where desktop is strictly better — the reason this app is worth building:**

| Upgrade | Detail |
|---|---|
| **Cascade mode = real OS windows** | The web fakes MDI with `z-index` divs inside the viewport. Compose Desktop opens genuine `Window`s — real OS chrome, snap/tile, **spanning multiple monitors**, Mission Control / Alt-Tab. A `Detached` state is in the model from day one |
| **Tear-off** | Drag a tab out of the strip → it becomes its own OS window. Drag it back → re-docks |
| **Session restore** | Web loses everything on refresh, and closes all windows on project switch. We serialize open windows + per-window history + geometry + monitor, and restore the exact workspace on relaunch |
| **Real memory headroom** | Web must keep everything mounted in one browser tab. We keep ViewModels + saved state for all windows, but release *composition* for hidden ones beyond an LRU cap (default 12, configurable). Same "never lose work" guarantee, far better ceiling |
| **Global shortcuts** | `Cmd/Ctrl+T` new · `Cmd/Ctrl+W` close · `Cmd/Ctrl+1..9` jump · `Ctrl+Tab` cycle · `Cmd/Ctrl+Shift+T` reopen closed · `Cmd/Ctrl+\` split |
| **Native window menu** | macOS "Window" menu listing open tools, standard on desktop |
| **Dirty-close guard** | `isDirty` prompts on close; quitting lists every dirty window once |

### 3.3 Frame layout

```
┌──────────────────────────────────────────────────────────────────┐
│ [Zillit]  Project ▾ · Unit ▾          🔍 search      👤 profile   │
├──────┬───────────────────────────────────────────────────────────┤
│  ⌂   │ 3 open  ┌──────┬─────────┬──────────┬───┐   [Cascade] [×] │
│ 💬 3 │         │ Home │Budget ● │Chat: J ③ │ + │                 │
│ ✉ 12 │ ┌───────┴──────┴─────────┴──────────┴───┴───────────────┐ │
│ 📅   │ │                                                       │ │
│ 🛠   │ │                  ACTIVE WINDOW                        │ │
│ 🚚   │ │                                                       │ │
│ ⚙    │ └───────────────────────────────────────────────────────┘ │
├──────┴───────────────────────────────────────────────────────────┤
│ 🔒 connected · syncing 2 uploads · v1.0.0                        │
└──────────────────────────────────────────────────────────────────┘
```

The left rail is the Android bottom-nav rotated for desktop. **Rail items open windows; they are not screens themselves.** That single decision is what makes the app window-native instead of a phone nav with tabs bolted on.

### 3.4 Feature contract

```kotlin
interface ToolProvider {
    val path: String                    // matches web toolRegistry keys — deep links stay compatible
    val title: String
    val icon: ToolIcon
    val openMode: OpenMode              // Window | Maximized
    val defaultSize: DpSize
    val hostsOwnRoutes: Boolean         // Account Hub, Sides, Settings, Permission Grid
    @Composable fun Content(route: WorkspaceRoute, nav: WindowNavigator)
}
```

Koin collects every `ToolProvider`; the workspace dispatches by path. **Adding a tool = new module + one registration.** No central `when` block — which is exactly what lets Modules 8–11 be built in parallel by different engineers.

---

## 4. Delivery roadmap

Every module ends in a runnable, installable build. Estimates assume 2 engineers with tests; add ~30% for design polish.

### Phase 1 — Foundation

**M0 · Skeleton & build system — ✅ COMPLETE**
Scaffold restructured into §2.1 (11 modules) · `build-logic` convention plugins (`zillit.kmp.library`, `zillit.compose.library`) · version catalog with the full M1 dependency set resolved · CI matrix (macOS/Windows/Linux) · detekt + the §8.2 TLS tripwire wired into `check`.
→ *Exit met: `./gradlew clean build` green, app frame launches, `.dmg` packages and runs.*

Verified on macOS only — Windows and Linux are green in CI configuration but
unrun, since this machine cannot execute them (see §12).

Two findings worth carrying forward:
- **Installer is smaller than estimated.** 71 MB `.dmg` / 136 MB installed with the
  `jlink`-trimmed runtime, against the 250–400 MB in risk 11. That estimate assumed
  libvlc and KCEF, which are not in the graph yet — but the JRE floor is comfortable.
- **SQLDelight codegen works** on the current toolchain, which retires half of risk 6.
  The remaining half is SQLCipher's native coverage, still a week-1 M1 spike.

**M1 · Core infrastructure + security foundation — 🟢 MOSTLY DONE** *(4 weeks planned)*

Every `core:*` module in the M1 scope is built and tested. What remains is
*verification against a live backend*, which needs credentials
([OUT_OF_SCOPE.md](OUT_OF_SCOPE.md) §1), and a real database schema, which
arrives with the first feature module that needs one.

Done and tested: `core:common` (Result/error/logging with redaction),
`core:config` (the real ~30-service topology, loaded from an external file, no
secrets in the binary), `core:security` (AES/CBC port **verified byte-identical
to Android across 700 random inputs** via a differential test), `core:network`
(Ktor client with no TLS escape hatch, one `ApiClient` replacing eleven Android
repository methods, 11 tests).

Also done: **`core:database` with working SQLCipher encryption**, and
**OS-keychain secret custody** in `core:security` — verified end to end against
the real macOS Keychain ([sqlcipher](docs/spikes/sqlcipher.md),
[keychain](docs/spikes/keychain.md)).

**`core:datastore`** is done: typed, scoped, reactive preferences on androidx
DataStore. Ported *selectively* — `SharedPref.kt` conflates real preferences,
cached API responses and secrets, so only the first belongs here (see
`PreferenceKey` kdoc). The theme choice and window geometry now survive a
restart.

**`core:socket`** is done: Socket.IO with our own reconnect (exponential backoff
**with jitter** — the Android client retries on a fixed delay, so a production-wide
reconnect is a thundering herd), auth headers rebuilt per attempt, and a typed
event bus. The god objects are gone by construction: a single `onAnyIncoming`
listener routes everything, features subscribe, and `core:socket` imports no
feature module (§11.3).

**Verified against QA on 2026-08-03.** A real sign-in ran end to end:
`device/qrcode` → poll → `link-scanned` → `project` → `configuration`, all 200,
with `moduledata` / `bodyhash` / `timezone` / `deviceInfo` accepted as built and
the config payload decrypting correctly. That closes the biggest unknown in the
plan — the header scheme is right.

**The M1 exit criterion is still only partly met.** It reads: *"integration test
hits QA, decrypts, caches encrypted, receives a socket event, over a pinned TLS
connection."*

| Clause | State |
|---|---|
| hits QA | ✅ |
| decrypts | ✅ — `configuration` payload |
| caches encrypted | 🟡 `core:database` works, but is **not wired into the app** and has no real schema |
| receives a socket event | ❌ `core:socket` is **not wired into the app**; it has never connected to a live server |
| over a pinned TLS connection | ❌ not implemented — needs the pin set from the backend ([OUT_OF_SCOPE.md](OUT_OF_SCOPE.md) §1.3) |

`core:database` and `core:socket` are built and unit-tested but absent from
`AppGraph`. They land with M4/M5, which are the first features that need them.

*Original plan text follows.*
- `core:security` — port `EncrytionDecryption.kt` byte-exact (golden-value tests vs Android), keychain abstraction, **TLS pinning done properly (§8.1)**, secure secret loading.
- `core:config` — env switching replacing 3 flavors + ~800 lines of `buildConfigField`.
- `core:network` — `NetworkClient`, `ApiConstants`, `ApiUrl` (1,604 lines, mechanical), `Repository`/`RepositoryImp`, `MODELDATA` header strategy, error envelope, API log.
- `core:datastore` — `SharedPref.kt` (1,352 lines) → DataStore; secrets → keychain.
- `core:database` — SQLDelight + SQLCipher, key management, migrations (§5).
- `core:socket` — Socket.IO with auth handshake, backoff, **typed event bus** replacing the `ChatSocketHelper` / `BaseSocketListener` god objects.
→ *Exit: integration test hits QA, decrypts, caches encrypted, receives a socket event, over a pinned TLS connection.* **Highest-leverage milestone in the plan.**

**M2 · Design system + workspace engine — ✅ COMPLETE** *(4 weeks planned)*

Done and tested: full semantic token set with **complete light and dark themes**
(41 roles, animated transition), desktop type scale, 4pt spacing, an in-house
icon set (Compose MP dropped Material icons after 1.7.3), reusable components,
`core:mvvm` (base ViewModel, `LoadState`), the workspace reducers (**20 tests,
including both bugs the web version fixed in production**), `ToolProvider`
registry, tab strip, window host with a documented state-retention contract,
navigation rail, and file-backed session persistence. 7 UI tests drive the real
frame.

Also done: **cascade mode** (draggable, resizable floating windows with
double-click-to-maximize), **tear-off into real OS windows** — the thing the web
version fundamentally cannot do, since its cascade is `z-index` divs trapped in
one viewport — **keyboard shortcuts** (platform-correct modifier, 11 tests), and
**tab drag-to-reorder**.

Not verified: nobody has actually dragged a window or pressed ⌘W — interactive
behaviour needs a human ([OUT_OF_SCOPE.md](OUT_OF_SCOPE.md) §10).

*Original plan text follows.*
- Tokens from `design-system/zillit/MASTER.md` + `tailwind.config.js` (primary `#F99300`, accent `#04327d`) → Compose theme, light + dark.
- Core components: buttons, fields, dropdowns, dialogs, **data tables** (heavily used in Account Hub), drawers, empty/error/loading, toasts.
- **`core:workspace` — the full §3 engine**: tabs + cascade + detached windows, per-window history, session restore, dirty guards, shortcuts, `ToolProvider` contract.
- App frame, menu bar, tray, multi-monitor window persistence.
→ *Exit: open/close/reorder/pin/minimize/maximize/tear-off/restore windows of placeholder content.* **Demo this — it proves the product concept.**

**M3 · Auth, project & unit selection — 🟢 VERIFIED, WITH GAPS** *(2 weeks planned)*

`feature:auth` is complete: device-OTP flow, recovery, project list and
selection, session gating, and an `AppGraph` that wires the app together.
12 ViewModel tests cover the state machine.

**Signed in against QA.** The first live calls found three defects that no
amount of local testing would have: the poll was reading `scanned_device_id`
(populated from creation) instead of `scanner_device_id` (the actual scan
signal); `link-scanned` needs the `SCANNER_DEVICE_ID` header shape and returned
406 without it; and `preset/project-types` returns *translation keys*, so the
create dialog was rendering `entertainment_industry_label` verbatim. All three
are fixed and pinned by tests.

Built beyond the original M3 text, matching the web client:
QR sign-in landing page (`/device/login`), redesigned production list
(search, category filter, favourites, unread badges), create-production flow
(form → email verification → project code), production switching, and
`core:remoteconfig` for `GET configuration`.

**Still missing from M3's own scope:**
- **Unit selection** — ✅ built, as a profile preference in Settings, matching
  both references: `GET join/unit` to list, `PUT user/profile { join_unit_id }`
  to choose. Note this is *not* a sign-in step — neither the web nor Android
  asks for a unit when opening a production, so `ProjectRepository.listUnits`
  (a different endpoint) and `selectProject`'s `unit` parameter remain unused.
  Unverified against a live production: no signed-in session on this machine.
- **Join / pending approval** — ✅ built. "Join a production" on the list opens a
  two-step dialog: code lookup, then the details the server wants
  (`first_name`, `last_name`, `department_id`, `designation_id`, `join_unit_id`,
  `keep_name_private`) posted to `user/join-project`, with the production
  carried in the header. Shape taken from iOS's `JoinUserRequestModel` and
  Android's `JoinProjectRequest`; the previous `requestJoin` posted
  `{project_id, project_code}`, which matched nothing on the server. Personal
  productions skip department/role/unit, as both other clients do. **Admin-side
  approve/reject (`PUT user/join-project`) is not built** — that is the
  coordinator's screen, a separate feature. Unverified live: no session.
- **Token refresh** — not buildable client-side: this API has no tokens. Auth
  is a per-request encrypted `moduledata` header keyed on the device id, and
  neither reference client has a refresh or renew endpoint (the web logs out on
  401). §8.5's short-lived-access-token design needs the backend to issue them
  first. What exists instead: a 401 is verified against `GET device` before the
  user is signed out, so a transient rejection no longer costs a QR scan.

*Original plan text follows.*
Device-OTP, device registration, recovery (email + code) · project list/create/join/pending · unit & project switching (wired into the workspace: closes project-scoped windows) · session persistence, token refresh, logout wipe.
Desktop substitutes: face recognition (ML Kit + camera) **dropped for v1**; QR join → code entry + "scan on phone".
→ *Exit:* ✅ **first installable build.** Sign in on Mac, Windows, Chromebook.

### Phase 2 — Communication core

**M4 · Home, tools grid & badges — 🟢 BUILT, PARTLY VERIFIED** *(2 weeks planned)*

`core:permissions` (12 tests) · `core:badges` (13 tests) · `feature:home`
(11 tests). Live against QA: `GET project/tools` returns 200 and drives both the
grid and the rail.

**Permission model**, transcribed from `AssetRegisterRights.from`:
view / post / download are independent; **admin bypasses access checks but not
`enabled`** (a tool the production switched off is off for everyone); an
**unknown tool is denied, not granted**, and does not throw; post and download
imply view.

**Badges recompute rather than accumulate.** Android's `computeBadgesCount` is
~900 lines adjusting running totals per socket message, and any drift is
permanent until restart. Here `badge_data` is a *signal* — the counts are
refetched from `notification/project/level/unread` and replaced wholesale. One
extra request on a cheap endpoint, and the entire class of drift bug is gone.

**`core:socket` is now wired into the app** — its first use in the product. It
connects on project selection (the handshake carries the device id, which does
not exist before linking) with a `{device_id}`-only payload matching
`ReqHeaderForSocket`, distinct from the REST device header which also sends a
timestamp.

Not done: tool **groups and ordering** (`group_identifier` is parsed, the grid is
flat); **unit scoping** (`unit_id` parsed, unused — ties into M3's missing unit
selection); admin tool management (`project/tools/admin`). The socket handshake
and badge endpoint have **not yet been exercised against a live production** —
they fire on project selection, which needs a signed-in session.

*Original plan text follows.*
Dashboard · tool list with groups/ordering/admin management · **permission gating** (view/edit/post — used by every later module, must be right here) · `badgesHandler` (11.6k LOC) → live socket-driven counts on rail *and* window tabs.
→ *Exit: any tool opens a window (stubs for unbuilt ones); badges live.*

**M5 · Chat — 4 weeks**
1:1 + group · all message types · reactions, edit, delete, read receipts, typing, expiry · offline queue + resend (port `docs/superpowers/plans/2026-07-01-offline-chat-message-resend.md`) · chat list, search, pickers, rooms.
Desktop wins: each conversation is its own window; drag-drop attach; clipboard paste; native notifications with inline reply.

**M6 · Email — 3 weeks**
`mailing` (12.7k) + `new_email` · folders, threads, compose, attachments, templates, signatures · **pop-out compose into real OS windows** (the web's `WindowPortal` done natively).
Rich editor: Compose-native first, KCEF+TipTap fallback (web already uses TipTap — same output format).

**M7 · Calendar — 3 weeks**
`calendar` + `new_calendar` (~38.6k) · month/week/day/agenda, events, production packs, invites. Desktop-density layouts genuinely beat mobile here.

### Phase 3 — Tools *(parallelizable after M4)*

**M8 · Documents & files — 4 weeks**
`drive` (25.5k) · `customFileViewer` · `docdistribution` (20.9k) · `docSign` (17.8k) · `forms_and_signature` (35.6k). PDFBox viewing, canvas signature capture, live "email opened" socket events, OS file associations, multi-file drag-drop.

**M9 · Production tools tier 1 — 6 weeks**
`callsheet` (12.6k) · `new_boxschedule` (14.2k) · `location` (12.8k) · `schedule` · `script` · `sides` · `continuity` · `dod`.

**M10 · Finance — 8 weeks (2 engineers)**
**`accounthub` (111.9k — 15% of the whole Android app)** · `deal_memo` (28.1k) · `ad_dashboard` (14.6k) · `productionreport` (12.7k) · `purchase_order` (11k) · `budget` (8.5k).
⚠️ **Account Hub needs its own sub-plan before M10 starts.** Consider read-only first. Heavy tables/reports — exactly where desktop wins.

**M11 · Remaining tools — 5 weeks**
`transportation`+`v2` (42.4k) · `settings` (27.1k) · `casting` (11.3k) · `wardrobe` (9.1k) · `recce` · `weatherPro` · `catering` · `crewlist` · `sa_portal` · `scriptnotes` · `assetregister`.

### Phase 4 — Platform & calling

**M12 · Desktop polish & distribution — 4 weeks**
Global search · native notifications, dock/taskbar badges, jump lists · **Conveyor** auto-update with delta patches · macOS notarization, Windows Authenticode, `.deb` · crash reporting · offline hardening, resumable uploads · accessibility · i18n (`translation` module + label presets).

**M13 · LiveKit calling — 4 or 12 weeks depending on §7 decision**

---

## 5. Local database — decision

**Realm is out.** MongoDB deprecated the Atlas Device SDKs (Sept 2024) and ended support (Sept 2025). It is Android-only regardless.

**Decision: SQLDelight + SQLCipher.**

| Why SQLDelight | |
|---|---|
| True KMP, JVM/desktop first-class, actively maintained | |
| Compile-time-checked SQL, typed Kotlin APIs, `Flow` queries native — matches how `*DbManager` is consumed today | |
| Same engine everywhere: identical behaviour on macOS and Windows, no per-OS DB quirks | |
| We wrap it in DAOs that **mirror the existing `*DbManager` signatures** (`insertApiLog`, `getChatList`, `updateBadge`, …) so ported business logic barely changes | |

**Encryption at rest: ✅ spike complete — adopted.** Full findings in
[docs/spikes/sqlcipher.md](docs/spikes/sqlcipher.md).

> **Correction to this plan.** It previously named `SQLCipherMultiplatform` and
> `sqlcipher-jdbc` as the candidates. **Neither is published to Maven Central** —
> that came from a web search I did not verify against the repository. Relying on
> a JitPack-only artifact for a security-critical dependency would not have been
> acceptable, so this mattered.

The adopted library is **`io.github.willena:sqlite-jdbc` 3.53.4.0** — the Xerial
driver with [SQLite3MultipleCiphers](https://utelle.github.io/SQLite3MultipleCiphers/)
compiled in, configured for SQLCipher v4 defaults (AES-256-CBC, HMAC-SHA512,
256k KDF). It is on Maven Central and ships natives for **all six** targets we
need — macOS arm64/x64, Windows x64/arm64, Linux x64/arm64 (the last covering
both Intel and ARM Chromebooks under Crostini).

Verified by 9 tests, including a control case proving the plaintext probe is
genuinely sensitive. Write overhead ~1.6× on a 2,000-row batch. Two hazards found
and guarded: a duplicate transitive JDBC driver that could have opened the
database **silently unencrypted**, and a key-ownership contract that fails as
`SQLITE_NOTADB`.

Native *loading* is confirmed on macOS arm64 only; the other five are confirmed
present in the jar and gated by a test that fails on any CI runner lacking one.

**Fallback, still available if the library is ever abandoned:** plain
`sqlite-jdbc` + application-layer AES on sensitive columns, DB file in a
user-only-permission directory, relying on FileVault/BitLocker for bulk-at-rest.
The driver sits behind `EncryptedDriverFactory`, so switching is one file.

Alternative considered: **Room KMP** — viable, and worth revisiting if Android ever migrates off Realm so both platforms share one persistence module. For desktop-first velocity SQLDelight wins today.

**Caching policy** (ported from Android semantics): write-through on every API success · TTL per entity type · LRU eviction for attachments and media · full wipe on logout/project-switch/device-revoke (§8.6) · schema versioned with forward-only migrations from day one.

---

## 6. Maps — settled

**Google Maps via `core:webview` (KCEF).** Confirmed as your call, and it's the right one: no Compose Desktop Maps SDK exists, and the web app already runs `@react-google-maps/api` — so we reuse a working, tested integration rather than building a Compose map from tiles.

Applies to `location`, `map`, `new_map`, `transportation`.

**Implementation notes:**
- One reusable `MapWebView` composable over KCEF, driven by a **typed Kotlin ↔ JS bridge** (`setMarkers`, `fitBounds`, `onMarkerClick`, `onDragEnd`), so calling code never touches JS.
- ⚠️ **The map HTML/JS is bundled locally, never loaded from a remote URL** — see §8.7 for the full webview hardening rules (CSP, navigation allowlist, no `nodeIntegration`-equivalent, message validation).
- The Maps API key is **fetched at runtime from your backend and scoped/referrer-restricted**, never baked into the binary (§8.3).
- Offline: cache last-known tiles; degrade to a list view when offline rather than a blank canvas.

The same `core:webview` module also serves the rich-text fallback and — if you take option A — calling.

---

## 7. Calling — LiveKit

Thanks for the correction; this changes the picture substantially and mostly for the better.

**What exists today:** `com.zillit.zillitapp.livekit` — **62 files, ~22,100 LOC**, on `io.livekit:livekit-android:2.11.0`, pointing at `wss://{dev-,qa-,}calls.zillit.com/livekit`. Notably the **call UI is already Jetpack Compose** (`CallActivity` 5,057 · `ParticipantsPanel` 1,088 · `CallControls` 530 · `ParticipantTile` 479 · `CallUi` 464 · `VideoTile` 362 · `ChatPage` 300 …). The web app has *not* migrated — it still runs Agora + mediasoup.

**The constraint:** there is no official LiveKit client SDK for JVM desktop. LiveKit ships JS, Swift, Android/Kotlin, Flutter, Rust, Unity — [KMP support is an open request](https://github.com/livekit/client-sdk-android/issues/520), and [Ktor's WebRTC client](https://ktor.io/docs/client-webrtc.html) lists JVM desktop as planned, not shipped.

**The opportunity:** roughly **10k LOC of that package is Compose UI that ports to Compose Multiplatform directly.** Only the media engine underneath (`CallEngine.kt`, 3,040 LOC) needs replacing. So the question is purely *which media engine*.

### Options

| | Approach | Effort | Verdict |
|---|---|---|---|
| **A** | **KCEF + `livekit-client-sdk-js`** — render the call in an embedded Chromium window driven by a Kotlin bridge | **3–4 weeks** | Fastest. Chromium's WebRTC is battle-tested. **But the call UI lives in a webview** — you cannot composite its video into the Compose window, so no docked participant panel, no call-in-a-tab, no Compose PiP, and the existing 10k LOC of Compose UI is thrown away |
| **B** ⭐ | **`livekit-ffi` (Rust) bound via Java 22+ Panama/FFM** — [rust-sdks](https://github.com/livekit/rust-sdks) ships `livekit-ffi` *specifically* for binding other languages (it's what the Unity and Python SDKs use). Wrap it in Kotlin, keep the ported Compose UI on top | **10–14 weeks** | The right answer. Native video frames → Compose `ImageBitmap`, full workspace integration, screen share via the OS picker, PiP, call-in-a-window. Reuses the Compose UI. LiveKit maintains the FFI layer, so we track upstream rather than owning a protocol implementation |
| **C** | `dev.onvoid.webrtc:webrtc-java` ([jrtc.dev](https://jrtc.dev/)) + hand-written LiveKit signaling on `io.livekit:protocol` | 14–18 weeks | More work than B for no advantage now that `livekit-ffi` exists. Keep only as a fallback if Panama binding hits a wall |

### Recommendation

**Ship v1 without in-app calling** (deep-link to mobile/web for calls), **then build option B as M13.**

Run a **2-week Panama/`livekit-ffi` spike during M1** — connect to a room, publish audio, receive a remote video frame, render it in Compose. That de-risks the whole thing early and costs almost nothing. If the spike fails, fall back to A as an interim while C is evaluated.

Do **not** build A and then B — that's paying twice and throwing away the Compose UI in between.

Also worth flagging to product: since web is still on Agora + mediasoup while Android is on LiveKit, **desktop should target LiveKit** and web should follow. Otherwise you're maintaining three calling stacks.

---

## 8. Security architecture

You asked for complete security. This section is the plan for that — and it opens with something I found in the current client that needs attention regardless of the desktop project.

### 8.1 🔴 Critical finding in the Android client — TLS validation is disabled

`app/src/main/java/com/zillit/zillitapp/network/NetworkClient.kt:202-254`

```kotlin
private fun getPreClient(): OkHttpClient? {
    val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) { }  // empty
        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
    })
    val sslContext = SSLContext.getInstance("SSL")          // ← also: deprecated protocol family
    sslContext.init(null, trustAllCerts, SecureRandom())
    builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
    builder.hostnameVerifier { _, _ -> true }               // ← hostname check disabled too
    builder.build()
}
```

It is wired in unconditionally at line 156:

```kotlin
engine { preconfigured = getPreClient() }
```

**Impact:** every HTTPS request from the app — auth, chat, financials, documents — accepts *any* certificate from *any* host. Anyone on a shared network (set location, hotel, production office Wi-Fi) can transparently MITM the app with a self-signed cert: read and modify all traffic, harvest credentials and device tokens.

Two things make this worse than it looks:
- **`android:networkSecurityConfig` does not save you.** The manifest correctly sets `usesCleartextTraffic="false"` with a clean config, but a custom `SSLSocketFactory` on OkHttp bypasses the platform trust store entirely. The config is silently irrelevant.
- **It applies to release builds.** There is no build-type guard.

The AES header encryption does not compensate — the key and IV ship in the binary (`BuildConfig.ENCRYPTION_KEY` / `IV_KEY`), so an attacker who can read the APK can decrypt anything the transport exposes.

**This is worth fixing in the Android app now, independently of the desktop project.** The fix is small: delete `getPreClient()` and the `engine { preconfigured = ... }` line so OkHttp uses the platform trust store. If it was added to make a QA server with a self-signed cert work, the correct fix is a debug-only `networkSecurityConfig` trusting that one CA — never a trust-all manager in shipping code.

I have not changed anything in the Android repo. Tell me if you want me to prepare that patch.

**For desktop we do the opposite of this by default** — see §8.2.

### 8.2 Transport security

- **Full certificate validation, always.** No trust-all path exists in the codebase — not behind a flag, not in debug. Enforced by a detekt rule + a CI grep that fails the build on `X509TrustManager`, `hostnameVerifier`, or `SSLContext.getInstance("SSL")`.
- **Certificate pinning** on all `*.zillit.com` hosts via OkHttp `CertificatePinner`, pinning the intermediate CA's SPKI (survives leaf rotation). Two backup pins. Pin set is remotely updatable through a signed config so an emergency CA change doesn't brick installed clients.
- **TLS 1.3 minimum**, 1.2 floor; modern cipher suites only.
- **QA/self-signed handling:** a debug build may trust one extra CA loaded from an explicit file passed by launch flag. Never a trust-all manager, never in release.
- WebSocket (Socket.IO + LiveKit) uses the same pinned, validated stack.

### 8.3 Secrets

**No secret ships in the binary.** Today `ENCRYPTION_KEY`, `IV_KEY`, Maps keys and similar are `buildConfigField`s — trivially recovered from an APK, and just as trivially from a JAR (a desktop JAR is *easier* to decompile than a minified APK).

- Client-side secrets fetched at runtime from an authenticated endpoint after device attestation, held in memory only, never persisted.
- Third-party keys (Maps, S3, Box) become **backend-brokered**: the desktop app asks your server for a short-lived scoped credential. Google Maps key additionally referrer/IP-restricted server-side.
- The **static AES header scheme is a v1 compatibility shim, not a security control.** It is transport obfuscation with a shared key. Plan: keep it for backend compatibility, add real request signing (HMAC over method + path + body-hash + timestamp + nonce, with a per-device key established at registration) — server-side change required, so schedule it as a backend workstream alongside M1.
- Build-time secrets live in CI secret storage; `local.properties` is never committed. *(Note: `ZillitAndroidV20/local.properties` is 11 KB and holds build secrets — confirm it's gitignored and has never been committed. Worth a `git log` check.)*

### 8.4 Data at rest

| Data | Protection |
|---|---|
| Auth tokens, device keys, DB encryption key | **OS keychain** — macOS Keychain Services, Windows DPAPI/Credential Manager, Linux Secret Service. Never in a file, never in DataStore |
| Application database | **SQLCipher AES-256** (§5), key from keychain, never on disk |
| Cached attachments, drafts, exports | Encrypted per-file (AES-GCM), key from keychain, in a user-only-permission app dir |
| Preferences (non-sensitive) | DataStore, plaintext — nothing sensitive is allowed here, enforced by review |
| Logs | **Redacted at the sink**: no tokens, PII, financials, message bodies. The Android client currently logs at Ktor `LogLevel.ALL` — desktop uses `HEADERS` in debug, `NONE` in release, with a redacting logger |
| Memory | Tokens and keys in `CharArray`/`ByteArray`, zeroed after use; never in a `String` (JVM string interning makes them linger in heap dumps) |

### 8.5 Authentication & session

- Device-bound sessions using the existing device-registration model; desktop device identity derived from stable hardware IDs + a generated keypair in the keychain.
- Short-lived access tokens + refresh rotation; refresh token single-use with replay detection.
- **Idle lock** — configurable (default 15 min) re-auth via OS biometric (Touch ID / Windows Hello) or password. Locks the UI *and* re-locks the SQLCipher key.
- Remote revoke: a revoked device wipes local DB, cache and keychain entries on next connect.
- Concurrent-session policy and the linked-devices screen ported from `settings/linkedDevice`.

### 8.6 Application hardening

- **Input validation at every boundary** — API responses, socket payloads, webview messages, file imports. `ignoreUnknownKeys` is fine; unvalidated *use* of parsed data is not.
- **File handling:** downloads land in quarantine, MIME sniffed by content not extension, size-capped, never auto-executed. No shelling out to open a file without an explicit user action.
- **PDF/media parsing is the classic RCE surface.** PDFBox and VLC run with parsing limits; consider a separate low-privilege process for untrusted documents in a later hardening pass.
- **Deep links** (`zillit://`) are validated and authenticated — a link can never trigger a state change without an authenticated session and, for destructive actions, explicit confirmation.
- **No dynamic code loading.** The Android app bundles a QuickJS engine (`wang.harlon.quickjs`); if any equivalent is needed on desktop it runs sandboxed with no host bridge.
- **Supply chain:** Gradle dependency verification with checksums + signatures, lockfiles, OWASP dependency-check and Renovate in CI, SBOM per release.
- **Binary:** R8/ProGuard obfuscation, all artifacts signed (Apple Developer ID + notarization, Windows Authenticode). Auto-update packages are **signature-verified before install** — an unsigned update path is a remote code execution channel.

### 8.7 Webview hardening (maps, rich text, any KCEF surface)

The webview is the largest new attack surface the desktop app introduces. Rules, non-negotiable:

1. **Local content only.** Map/editor HTML+JS is bundled in the app. No remote page is ever loaded into a bridged webview.
2. **Navigation allowlist.** Any navigation outside the bundled origin is blocked and, if user-initiated, handed to the system browser instead.
3. **Strict CSP** on bundled pages; no `eval`, no inline script.
4. **Minimal, typed bridge.** The JS bridge exposes a fixed set of named methods with schema-validated arguments. No generic "call any Kotlin function" escape hatch, no filesystem or process access.
5. **Untrusted content never reaches a bridged webview.** HTML email bodies render **sanitized** (the web app already uses DOMPurify — same approach) in a *separate, bridge-less, script-disabled* webview. Remote images blocked by default behind a "load images" affordance, so email can't be used as a tracking or SSRF vector.
6. KCEF is updated on the same cadence as the app; Chromium CVEs are tracked.

### 8.8 Process

- **Threat model written during M1** (STRIDE over: auth, document distribution, financial approvals in Account Hub, calling, file sync) and revisited per phase.
- Security review gate on every module — not a phase-12 activity.
- SAST (detekt security rules + Semgrep) and dependency scanning on every PR.
- **External penetration test before GA**, plus a re-test after M10 (Account Hub handles money).
- Incident response: signed kill-switch config, forced-update floor, per-device remote revoke.

### 8.9 Compliance

Production data includes cast/crew PII, financials and confidential scripts, so scope GDPR/UK-DPA obligations explicitly: data inventory, retention and deletion (including local caches), export on request, breach notification path, and DPA coverage for LiveKit/AWS/Box/Google subprocessors. Confirm which regimes apply — that answer changes retention and logging requirements.

---

## 9. Timeline

```
Month:   1    2    3    4    5    6    7    8    9   10   11   12   13   14   15
M0  ▓
M1  ▓▓▓▓▓▓▓▓                      ← incl. LiveKit-FFI spike + SQLCipher spike
M2       ▓▓▓▓▓▓▓▓
M3               ▓▓▓▓             ← ✅ installable, sign-in works (~week 11)
M4                  ▓▓▓▓
M5                     ▓▓▓▓▓▓▓▓
M6                            ▓▓▓▓▓▓   ← ✅ usable comms product (~month 7)
M7                                 ▓▓▓▓▓▓
M8                     ▓▓▓▓▓▓▓▓ (parallel)
M9                          ▓▓▓▓▓▓▓▓▓▓▓▓ (parallel)
M10                              ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓  ← Account Hub, 2 devs
M11                                    ▓▓▓▓▓▓▓▓▓▓
M12                                            ▓▓▓▓▓▓▓▓
M13                                            ▓▓▓▓▓▓▓▓▓▓▓▓  ← LiveKit (option B)
```

- **2 engineers, sequential:** ~15 months to full parity with calling.
- **4 engineers, parallel from M4:** ~10 months.
- **First installable (M3):** ~11 weeks.
- **v1 recommendation: M0–M6** (~7 months) — workspace + auth + tools + chat + email. Ship it, then roll tools out continuously.

---

## 10. Risk register

| # | Risk | Impact | Mitigation |
|---|---|---|---|
| 1 | **TLS validation disabled in the shipping Android client** (§8.1) | 🔴 Critical | Fix in Android now — delete `getPreClient()`. Desktop never has the code path. CI rule blocks reintroduction |
| 2 | **Crypto must be byte-exact** or every request 401s | 🔴 Critical | First task of M1, golden-value tests captured from Android |
| 3 | **No LiveKit JVM SDK** (§7) | 🔴 Critical | 2-week `livekit-ffi`/Panama spike in M1. Compose call UI ports either way. Interim: deep-link to mobile/web |
| 4 | **Account Hub is 112k LOC** | 🟠 High | Own sub-plan before M10; 2 engineers; consider read-only first |
| 5 | **Client-side secrets in the binary** (§8.3) | 🟠 High | Backend-brokered credentials + request signing. Needs a backend workstream — start conversation now |
| 6 | ~~SQLCipher JDBC native coverage~~ | 🟢 **Closed** | Spike complete — natives verified for all 6 targets, 9 tests, ~1.6× overhead. [docs/spikes/sqlcipher.md](docs/spikes/sqlcipher.md). Loading still unverified off macOS |
| 7 | 2,604 XML layouts → desktop layouts (not phone-scaled) | 🟠 High | Design system in M2; budget real design time per module |
| 8 | Webview becomes the main attack surface (§8.7) | 🟠 High | Rules in §8.7 are build-blocking, not advisory |
| 9 | Realm → SQLDelight across 25 DB managers | 🟡 Medium | Per-feature-module; DAO signatures mirror Realm managers |
| 10 | Hilt → Koin across the codebase | 🟡 Medium | Constructor injection survives; mostly deleting annotations |
| 11 | Installer size (JRE + libvlc + KCEF ≈ 250–400 MB) | 🟡 Medium | `jlink` trim; KCEF as on-demand download |
| 12 | ChromeOS/Crostini friction | 🟡 Medium | §1 — needs your decision |
| 13 | Three calling stacks org-wide (Agora web / LiveKit Android / desktop) | 🟡 Medium | Product decision: converge web onto LiveKit |
| 14 | Camera / ML Kit / biometrics | 🟢 Low | `webcam-capture` + ZXing; face recognition dropped for v1; biometrics via Touch ID / Windows Hello |
| 15 | No FCM on desktop | 🟢 Low | Persistent socket + OS notifications while running; mobile/email covers offline |

---

## 11. Engineering conventions

1. **`expect`/`actual` only at true platform boundaries** — filesystem, keychain, notifications, windows. Business logic never gets an `actual`.
2. **No `runBlocking` in production paths.** Android uses it in `NetworkClient` and `ChatAndGroupDb`; on desktop that freezes the UI thread.
3. **Kill the god objects.** `BaseSocketListener` and `ChatSocketHelper` (~2,000 LOC each) import from 40+ feature packages — that cannot survive a module graph. Features subscribe to a typed event bus; the socket layer knows nothing about features.
4. **DTO ≠ domain model.** Map at the repository boundary.
5. **Every feature owns its SQLDelight schema.** No shared mega-schema.
6. **`ToolProvider` registration is the only way into the workspace.** No central dispatch.
7. **Security rules are CI-enforced**, not review-enforced: no trust-all TLS, no secrets in source, no unredacted logging, no unbridged-webview exceptions.
8. **Testing:** unit tests on use cases + mappers, `MockEngine` for repositories, Compose UI tests for auth / chat send / workspace restore. ~70% on `domain` + `data`.

---

## 12. What I still need

**Decisions:**
1. **ChromeOS** — Crostini `.deb` (recommended) or WasmJS? Affects M1 and M12.
2. **Calling in v1?** — recommend no; M13 with option B. Confirm.
3. **v1 scope** — recommend M0–M6. Confirm or reshuffle.
4. **Team size** — sequencing assumes 2.
5. **Android TLS fix** — want me to prepare that patch?

**Access / inputs:**
6. **QA credentials** — `STG_BASE_URL`, `ENCRYPTION_KEY`, `IV_KEY`, LiveKit URL + a test account, to verify crypto and network against a live server in M1.
7. **Backend contact** — for request signing (§8.3), credential brokering, and confirming per-device socket/connection limits.
8. **`design-system/zillit/MASTER.md`** — I've seen it exists; confirm it's current, since M2 builds the token set from it.
9. **Compliance scope** (§8.9) — which regimes apply.

---

## 13. Immediate next step

**M0** needs no credentials, no decisions, no backend: restructure the scaffold into the module graph, wire `build-logic` convention plugins and the version catalog, and get a green cross-platform build with an empty window on all three targets. About a day's work.

I'd run the two de-risking spikes (**LiveKit FFI**, **SQLCipher JDBC**) immediately after, in parallel with M1 — both are cheap and both can change the plan, so they should happen early rather than at the module that depends on them.

---

**Sources for the platform research in §7 and §5:**
[LiveKit client-sdk-android — KMP support request](https://github.com/livekit/client-sdk-android/issues/520) ·
[LiveKit rust-sdks (`livekit-ffi`)](https://github.com/livekit/rust-sdks) ·
[Ktor WebRTC client (JVM desktop planned)](https://ktor.io/docs/client-webrtc.html) ·
[webrtc-java](https://jrtc.dev/) ·
[SQLCipherMultiplatform](https://klibs.io/project/s0d3s/SQLCipherMultiplatform) ·
[sqlcipher-jdbc](https://github.com/dttrinh/sqlcipher-jdbc) ·
[SQLDelight](https://github.com/sqldelight/sqldelight)
