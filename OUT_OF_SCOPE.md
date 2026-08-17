# Out of scope — what I cannot complete alone

Companion to [DESKTOP_KMP_PLAN.md](DESKTOP_KMP_PLAN.md). Everything here is work
the project needs that I **cannot finish**, with what unblocks it.

Ordered by how soon it blocks progress.

---

## 1. Blocking now — M1 cannot be verified without these

### 1.1 QA credentials and a test account
**Needed:** `STG_BASE_URL` (and the ~30 per-service URLs), `ENCRYPTION_KEY`, `IV_KEY`,
`LIVEKIT_URL`, plus a QA login.

I built `core:network` and `core:security` against mocks. The crypto is verified
byte-identical to the Android implementation across 700 random inputs, but
**that proves the port matches Android, not that the server accepts it.** Header
assembly, the `MODELDATA` module mapping, and the socket auth handshake are all
unverified against a live backend.

> **Faster alternative:** a handful of known plaintext → ciphertext pairs
> captured from the Android app would let me verify without server access at all.

### 1.2 Whether the production key is exactly 32 characters
The Android encrypt path uses the **last 32 chars** of `ENCRYPTION_KEY`; the
decrypt path uses the **whole string** (`EncrytionDecryption.kt:44` vs `:139`).
If the real key is longer than 32 characters, the client can encrypt payloads it
cannot itself decrypt. `CryptoKeyMaterial.requireCanonicalLengths` checks this,
but someone with access to the real key has to run it.

### 1.3 Backend confirmation
- Are desktop clients subject to the same device-registration limits?
- Any per-device socket connection caps?
- CORS / origin allowlists that would reject a desktop origin?

---

## 2. Requires a decision from you

| Decision | Why it blocks | Recommendation |
|---|---|---|
| **ChromeOS delivery** — Crostini `.deb` or WasmJS | Changes M1 and M12 structurally | Crostini `.deb` (zero extra code); flips only if your users can't enable Linux dev mode |
| **Calling in v1** | ~26k LOC of work and a 10–14 week module | Ship v1 without it; deep-link to mobile/web, add LiveKit in M13 |
| **v1 scope** | Determines what "done" means | M0–M6: workspace, auth, tools grid, chat, email |
| **Account Hub strategy** | 112k LOC — 15% of the Android app | Its own sub-plan before M10; consider read-only first |
| **Web calling convergence** | Web is on Agora + mediasoup, Android on LiveKit | Converge web onto LiveKit, or you maintain three stacks |

---

## 3. I cannot verify — no access to the platform

### 3.1 Windows and Linux/ChromeOS builds
I'm on your Mac. The CI matrix is written and the code is platform-neutral, but
**every cross-platform claim in this repo is unverified.** Specifically unknown:

- Whether the `.msi` and `.deb` build and launch
- Whether the SQLCipher native loads on Windows x64/arm64 and Linux (the
  binaries are verified present in the jar; only macOS arm64 has loaded one)
- Whether KCEF (M6/M8) renders on those platforms
- Whether the OS-keychain bindings behave on Windows Credential Manager and
  Freedesktop Secret Service (macOS Keychain is verified end to end)
- Font rendering and layout metrics — Compose text metrics differ per platform

**Unblocked by:** a Windows machine, a Chromebook, and someone to run the builds.
Or a first CI run once the repo has a remote.

### 3.2 Visual verification
macOS screen-recording permission isn't granted to this session, so I cannot
screenshot the running app. I compensated with Compose UI tests that drive the
real frame, which is better for CI — but **nobody has actually looked at this
app**. Spacing, colour balance, and icon weight need human eyes.

---

## 4. I will not do — credentials and signing

Out of bounds by policy, not capability:

- **Code signing and notarization.** Apple Developer ID, Windows Authenticode,
  keystore passwords. All of M12's distribution work needs these.
- **Any handling of production secrets.** I can write the code that consumes
  `ENCRYPTION_KEY`; I will not hold the value.
- **Publishing.** Uploading builds, creating release channels, configuring the
  auto-update feed.
- **Git operations you haven't asked for.** I have not run `git init` or
  committed anything — whether this becomes its own repo is your call.

---

## 5. Needs hardware or a second person

- **LiveKit calling end-to-end.** Camera, microphone, screen share, and two real
  peers on a real network. I can write the client; I can't be both ends of a
  call.
- **The `livekit-ffi` / Panama binding spike.** Native binding work is iterative
  against real media and real hardware. This is the estimate I'd trust least.
- **Performance under load.** 20 open windows, a 5,000-row Account Hub table, a
  200MB drive sync. Needs profiling on real machines with real data.
- **Camera features** — face recognition (dropped for v1), barcode/QR scanning,
  document scanning.

---

## 6. Needs backend work

Two items in the security plan (§8.3) cannot be done client-side:

1. **Request signing** to replace the static-key AES shim. Needs a server-side
   HMAC scheme and per-device key establishment at registration.
2. **Backend-brokered third-party credentials.** Google Maps, S3 and Box keys
   currently ship in the client. They need an authenticated endpoint issuing
   short-lived scoped credentials instead.

Both need a backend engineer. Start the conversation now — they gate M3 and M6.

---

## 7. Needs product, design, or legal input

- **2,604 Android XML layouts → desktop layouts.** Which screens become tables,
  which become split views, what belongs in a side panel. This is design work,
  not translation, and scaling phone layouts up is exactly how a port ends up
  feeling like a blown-up phone app.
- **`design-system/zillit/MASTER.md`.** I seeded tokens from
  `tailwind.config.js`; confirm the MASTER doc is current so M2's token set is
  right.
- **Dark theme sign-off.** I built a full dark palette. The web app ships only
  partial dark support (Account Hub only), so **there is no reference for what
  most screens should look like dark.** Mine is a reasoned extrapolation, not a
  match.
- **Compliance scope** (plan §8.9). Production data includes cast/crew PII,
  financials and confidential scripts. Which regimes apply — GDPR, UK-DPA,
  others — changes retention, logging and export requirements.
- **Copy and terminology.** I used the Android app's labels where I found them
  and invented the rest.

---

## 8. Should be fixed in the Android app, not here

**TLS certificate validation is disabled in the shipping Android client.**
`NetworkClient.kt:202-254` installs a trust-all `X509TrustManager` and a
permissive hostname verifier, wired in unconditionally at line 156 for every
build type. Every HTTPS request the app makes accepts any certificate from any
host.

I have not modified the Android repo. The fix is small — delete `getPreClient()`
and the `engine { preconfigured = ... }` line. Say the word and I'll prepare the
patch.

The desktop client cannot regress this way: `scripts/security-scan.sh` fails the
build on the pattern, and detekt blocks the import.

---

## 9. Too large for one pass

These are in scope for the project but need decomposition before I start:

| Module | Android LOC | Note |
|---|---|---|
| `accounthub` | 111,900 | Needs its own plan. Two engineers for M10's duration |
| `transportation` + v2 | 42,400 | Maps-dependent; blocked on the webview decision |
| `forms_and_signature` | 35,600 | Signature capture, PDF overlay |
| `calendar` + `new_calendar` | 38,600 | Two implementations to reconcile — ask product which wins |

---

## 9a. M3 auth — verified against QA on 2026-08-03

Superseded. The full chain now runs against `projectapi-qa.zillit.com`:

| Call | Result |
|---|---|
| `POST device/qrcode` | 200 |
| `POST device/qrcode/{id}` (poll, 3s) | 200, waits correctly for a scan |
| `POST device/link-scanned` | 200 |
| `GET project` | 200 |
| `GET configuration` | 200 — maps, places, aws, translation all decrypted |

This confirms what could not be checked locally: the `moduledata`, `bodyhash`,
`timezone` and `deviceInfo` headers are accepted as built, and the header key
read from `zillit.properties` both signs requests and decrypts the config
payload.

## 9b. QR sign-in page — matched to the web, still unproven

The landing screen and the three calls behind it are ported from
`src/pages/device/Login.jsx`. What I could verify locally, I did (196 tests).
What I could not:

### 9b.1–9b.2 Resolved

Both were about never having reached a live server. See §9a — the calls return
200, so `bodyhash` is accepted as computed and the kotlinx-serialization output
matches what the backend re-hashes.

### 9b.3 The QR code's real lifetime is unknown

The web stops polling after 30s and offers a reload; nothing indicates the
*server* invalidates the code at that point. The desktop copies the web's 30s
window. If the backend leaves codes live indefinitely, a photographed screen
stays usable long after it looks dead — worth confirming, since the code is a
device-linking credential.

### 9b.4 The email/OTP path is no longer reachable from the UI

The QR page is the landing screen, as on the web, and the web has no email form
on it. `AuthStep.Email` and its OTP flow still exist and are still tested, but
nothing on the sign-in page navigates to them. That is faithful to the reference;
whether desktop should also offer the Android-style email registration is a
product decision, not one I should make by adding a control the screenshot does
not have.

### 9b.5 The logo is a lettermark

No image resources ship with the desktop build yet, so the header draws a "Z" on
a brand-orange tile rather than the real logo. Supply the asset and it is a
one-line swap.

## 9c. `core:remoteconfig` — verified, with two open items

`GET api/v2/configuration` returned 200 against QA and all four credentials
decrypted (`maps=yes, places=yes, aws=yes, translation=yes`), confirming the
field-by-field encryption matches Android's.

Still open:

- **`box_client_id` / `box_client_secret`** exist in Android's DTO and are read
  by nothing there. They are not modelled here. If Box integration is planned,
  someone has to say what consumes them.
- **Refresh cadence.** Fetched once per project selection, as on Android.
  Nothing re-fetches if a credential is rotated mid-session; the user would have
  to switch projects or restart.

## 10. Known gaps in what I just built

Honest list of what is *not* finished in the current code:

- **Window geometry has never persisted in a real run.** The logic is now
  extracted and tested (7 tests covering the round trip, the absent-position
  sentinel and the half-saved case), but the one real app run that closed
  cleanly crashed in the close handler before saving — my fault, see below.
  Still needs one clean launch-resize-quit-relaunch cycle to confirm.
- **Interactive behaviour is unverified by hand.** Cascade drag/resize, tab
  drag-to-reorder, tear-off, and the keyboard shortcuts are all built and wired,
  and their *logic* is unit-tested (the shortcut table has 11 tests, the reducer
  20). But I cannot click or press keys without screen access, so **nobody has
  actually dragged a window or pressed ⌘W**. Gesture thresholds and hit targets
  in particular need a human. This is the highest-value thing for you to try.
- **`core:database` / `core:security`** — encryption and key custody are both
  done and verified end to end ([sqlcipher](docs/spikes/sqlcipher.md),
  [keychain](docs/spikes/keychain.md)). Outstanding: **no real schema**, and
  `desktopApp` does not yet construct `ZillitDatabaseFactory` — there is nothing
  worth persisting until a feature module needs it.
- **Headless Linux has no Secret Service.** The Crostini packaging story
  (plan §1) has to answer what the app does when there is no keyring daemon:
  require `gnome-keyring`, ship a documented downgrade, or refuse to run.
  Undecided — see [keychain spike](docs/spikes/keychain.md) §6.
- **`core:socket` has never talked to a real server.** The reconnect policy and
  event bus are pure and fully tested, but the handshake, the encrypted
  `moduledata` auth header and the actual event payload shapes are unverified —
  that needs QA credentials (§1.1). The payload *shapes* in particular are
  guesses: I transcribed event names from the Android constants, not schemas.
- **Cached server data has nowhere to live yet.** `SharedPref.kt` caches ~30
  API responses (country lists, departments, email folders, crew lists) as JSON
  strings with no TTL. On desktop these belong in `core:database` with typed
  rows and expiry — that lands with the first feature module that needs them.
- **Dirty-close confirmation** emits the effect; no dialog consumes it yet.
- **detekt's `ForbiddenMethodCall` rules are advisory, not enforced.** They need
  type resolution, which is not configured for this KMP build — verified
  empirically: a `runBlocking` passes detekt clean. So the "no `runBlocking`,
  no `println`" rules (plan §11.2, §8.4) rely on review, not tooling. The TLS
  rules are unaffected: `ForbiddenImport` is syntactic and does fire, and
  `scripts/security-scan.sh` backstops it independently. Fixing this means
  giving detekt a per-compilation classpath.
