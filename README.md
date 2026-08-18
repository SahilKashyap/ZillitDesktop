# Zillit Desktop

Kotlin Multiplatform desktop client for macOS, Windows and ChromeOS.

The full build programme is in **[DESKTOP_KMP_PLAN.md](DESKTOP_KMP_PLAN.md)** — read that first.
What I can't finish alone is in **[OUT_OF_SCOPE.md](OUT_OF_SCOPE.md)**.

Status: **M0 complete** · **M1 complete** · **M2 complete** (design system,
light/dark themes, tabs + cascade workspace, tear-off OS windows, shortcuts) ·
**M3 complete and verified against QA** — QR sign-in runs end to end:
`device/qrcode` → poll → `link-scanned` → project list → `configuration`, all
200, with the `moduledata` / `bodyhash` / `timezone` headers accepted by the
live backend.

## First run

1. **URLs** — see below.
2. **Header encryption key** — put `<PREFIX>_ENCRYPTION_KEY` and
   `<PREFIX>_IV_ENCRYPTION_KEY` in `zillit.properties`, exactly as the Android
   app's `local.properties` has them. Leave them blank and the app asks for them
   once instead and stores them in your OS keychain.

## Configuration — where server URLs go

The app needs a **`zillit.properties`** file before it can sign in; without one
it shows a "not configured" screen. Start from
[zillit.properties.template](zillit.properties.template).

Put the filled-in file in any of these — first match wins:

| Location | Use |
|---|---|
| `-Dzillit.config=<path>` | explicit, e.g. CI |
| `$ZILLIT_CONFIG` | per-shell |
| `./zillit.properties` | next to the executable |
| `~/.zillit/zillit.properties` | per user |

Pick the environment with `-Dzillit.env=develop\|qa\|prod` or `$ZILLIT_ENV`.
Unset resolves to **prod**, so a misconfigured launch fails onto the strictest
settings rather than silently pointing at QA.

That rule is about the *app* — a packaged build with nothing set is a production
build. On a development machine it inverts: `gradle.properties` ships
`zillit.env=develop`, which every `JavaExec` launch picks up, so a bare
`./gradlew :desktopApp:run` and the IDE's Run button reach **develop** rather
than production. The startup line is what settles it either way; read it rather
than reasoning from this paragraph.

### Key shape

`<PREFIX>_<SERVICE>`, where the prefix is `STG` (develop), `QA` or `PROD` — the
**same shape the Android app's `local.properties` already uses**, so the URL
lines copy straight across:

```bash
grep -E "^(PROD|QA|STG)_[A-Z_]*(BASE_)?URL=" \
  ../ZillitAndroidV20/local.properties > ~/.zillit/zillit.properties
```

The backend is ~35 separate services, each with its own host per environment.
Only `<PREFIX>_BASE_URL` is required; the rest are optional and fail loudly when
a feature needs a host that was not configured.

`LIVEKIT_URL` is the one key Android does not keep in `local.properties` — it
hardcodes a per-flavour default in `build.gradle.kts`. The template carries the
same value.

### The header encryption key

```properties
PROD_ENCRYPTION_KEY=<32 characters>
PROD_IV_ENCRYPTION_KEY=<16 characters>
```

The AES pair behind the `moduledata` and `bodyhash` headers — the same values
under the same names as Android's `local.properties`, so the lines copy across
with the URLs. Config wins over the keychain when both are present; a pair of the
wrong length is ignored with a warning rather than used, because encrypting with
mismatched material surfaces as a 401 on every request and points nowhere near
the config file.

**This is the one secret the file carries, and it is not really secret.** The key
is identical across every install and sits beside a decompilable JAR, so anyone
holding it can forge headers for any device. Keep the file out of git (it is
gitignored) and at mode 600. Per-device request signing replaces it — plan §8.3.

No other credentials belong here: Maps, Places, AWS and translation keys arrive
from `GET api/v2/configuration` after project selection — see `core:remoteconfig`.
They are held **in memory only** and dropped on sign-out. Android stores the same
values in `SharedPref`, which is plaintext on disk; since they are refetched on
every sign-in, persisting them would add exposure and buy nothing.

### Rules enforced at startup

- Every service URL must be `https://`; every realtime URL `wss://` or `https://`
- A header key that is not exactly 32/16 characters is dropped, with a warning

`zillit.properties` is gitignored. `ConfigTemplateTest` fails the build if a
service is added without a matching template line, so the template cannot drift.

## Debugging

### Switching environment

```bash
./gradlew :desktopApp:run -Pzillit.env=qa
```

`-P`, **not** `-D`. A `-D` on the Gradle command line sets the property on
*Gradle's* JVM, not the app's, so the flag is dropped and the launch quietly
keeps the `gradle.properties` default instead of the environment asked for —
which is not something to discover by accident. `desktopApp/build.gradle.kts`
forwards `-Pzillit.env` and `-Pzillit.config` into the application JVM.

Values: `develop` (STG URLs), `qa`, `prod`. With nothing set, a Gradle or IDE
launch resolves to `develop` (from `gradle.properties`) and a packaged app
resolves to `prod` — the two defaults differ on purpose, so that the convenient
way to launch on a dev machine is never the one that reaches production.

For a packaged build, pass it to the app directly:

```bash
open -a Zillit --args -Dzillit.env=qa
```

Confirm which one took effect — the first lines of every run say so:

```
I Startup: Environment: qa → https://projectapi-qa.zillit.com
```

### Logs

```bash
tail -f ~/.zillit/logs/zillit.log
```

Written on every run, plus stdout/stderr when launched from a terminal.
Warnings and errors go to stderr. The file caps at 5 MB and keeps one previous
generation as `zillit.log.1` — enough to ask a user to send after something
went wrong, since desktop has no logcat.

Verbosity follows the environment: `DEBUG` and above outside prod, `WARNING` and
above in prod — verbose logs sitting on a user's machine are a standing record
of who did what and when.

**Everything is redacted** before it is written. `ZillitLog` masks tokens, keys,
bearer headers and long hex blobs (plan §8.4). If you need raw output while
debugging, change `ZillitLog.redact` — do not bypass it.

### Watching API calls in real time

```bash
tail -f ~/.zillit/logs/zillit.log | grep -E "REQUEST|RESPONSE|^->"
```

Outside prod every call is logged at `HEADERS` level: URL, method, status and
headers. That answers *which* call fired and what came back.

To see the payloads too:

```bash
./gradlew :desktopApp:run -Pzillit.env=qa -Pzillit.http=body
```

`-Pzillit.http=body` raises the level to full bodies. It is:

- **ignored in production** — gated on the environment as well as the flag, so
  it cannot be switched on against real data by whoever reads this;
- **announced** — a warning line at startup, because a log full of message
  content is not something to leave running by accident;
- **still redacted** — the QR sign-in `code`, `confirm_code`, tokens, passwords
  and the header key are masked even here. `project_code`, `country_code` and
  the like stay readable, or the logs would be useless.

Bodies carry PII, financials and message content, so this is for chasing one
call, not a setting to leave on. Never attach a body-level log to a ticket
without reading it first.

#### Through a proxy instead

For a UI with search, filtering and replay, point the app at Proxyman, Charles
or mitmproxy:

```bash
./gradlew :desktopApp:run -Pzillit.env=qa \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=9090 \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=9090 \
  -Djavax.net.ssl.trustStore=/path/to/proxy-truststore.jks
```

Trusting the proxy's CA in a **separate truststore for that one run** is the
supported way to do this. Do not disable certificate validation to make a proxy
work — `securityScan` fails the build on it, and it is the exact hole the
Android client shipped (plan §8.1).

### Attaching a debugger

```bash
./gradlew :desktopApp:run -Pzillit.env=qa --debug-jvm
```

Then attach to port 5005 (IntelliJ: Run → Attach to Process, or a Remote JVM
Debug configuration). The app waits for the debugger before starting.

### Inspecting stored state

| What | Where |
|---|---|
| Preferences | `~/.zillit/zillit.preferences_pb` (protobuf; `strings` it) |
| Open windows | `~/.zillit/workspace-session.json` |
| Database | `~/.zillit/zillit.db` — encrypted, unreadable without the keychain key |
| Secrets | Keychain Access → search "Zillit Desktop" |
| Config | `~/.zillit/zillit.properties` |

To start clean: `rm -rf ~/.zillit` and delete the "Zillit Desktop" keychain
entries.

## Keyboard shortcuts

`⌘/Ctrl W` close · `⌘/Ctrl ⇧ T` reopen · `⌘/Ctrl 1–9` jump · `Ctrl ⇥` cycle ·
`⌘/Ctrl ⏎` maximize · `⌘/Ctrl M` minimize · `⌘/Ctrl P` pin ·
`⌘/Ctrl \` switch tabs / floating windows

## Architecture at a glance

- **MVVM per feature.** `data/` (DTOs, API, DAO, repository) → `domain/`
  (models, use cases) → `ui/` (screen, `ZillitViewModel`, `UiState`/`UiEvent`).
  `core:mvvm` holds the base class so no screen re-implements the
  load/success/error dance.
- **One design system.** Screens name semantic roles (`textMuted`, `danger`,
  `tabActiveBackground`), never a shade — which is how light and dark stay in
  step for free. Nothing outside `ZillitPalette.kt` may write `Color(0x…)`.
- **Features reach the UI only through `ToolProvider`.** No central dispatch
  switch, which is what lets tool modules be built in parallel.

## Requirements

- JDK 21 (Gradle provisions Azul Zulu 21 automatically via the foojay resolver)
- No Android SDK required

## Common commands

```bash
./gradlew build
```

```bash
./gradlew :desktopApp:run
```

```bash
./gradlew :desktopApp:packageDistributionForCurrentOS
```

`packageDistributionForCurrentOS` builds the host OS's format only — `.dmg` on macOS,
`.msi` on Windows, `.deb` on Linux/ChromeOS. Installers are unsigned until M12.

## Module layout

```
build-logic/          convention plugins — zillit.kmp.library, zillit.compose.library
core/
  common/             platform detection, coroutines, logging, shared primitives
  config/             environment (dev/qa/prod), feature flags          [M1]
  security/           crypto, keychain, TLS pinning                     [M1]
  network/            Ktor client, ApiUrl, repositories                 [M1]
  database/           SQLDelight + SQLCipher                            [M1]
  datastore/          preferences, secure storage                       [M1]
  socket/             Socket.IO, typed event bus                        [M1]
  remoteconfig/       GET api/v2/configuration — Maps/AWS/GPT creds
  permissions/        per-tool view/post/download, admin bypass        [M4]
  badges/             unread counts, recomputed not accumulated        [M4]
  designsystem/       theme (light/dark), tokens, components, icon set
  mvvm/               ZillitViewModel base, LoadState
  workspace/          tabs + cascade windows, shortcuts, session restore
feature/
  auth/               device OTP, QR sign-in, recovery, project select   [M3]
  home/               dashboard, tools grid, permission loading          [M4]
  shell/              app frame: top bar, rail, workspace, status bar
desktopApp/           entry point, window management, packaging
```

Modules arrive as the roadmap reaches them; empty modules cost configuration time,
so `core:filestore` and `core:webview` land in M6/M8.

### Adding a module

1. `include(":feature:foo")` in [settings.gradle.kts](settings.gradle.kts)
2. Create `feature/foo/build.gradle.kts`:

```kotlin
plugins {
    id("zillit.compose.library")   // or zillit.kmp.library for non-UI modules
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
        }
    }
}
```

Non-UI modules use `zillit.kmp.library` so the Compose compiler never runs over pure logic.

## Verification

`./gradlew build` runs, for every module:

- **compile** — JVM target, Kotlin 2.4.10
- **tests** — unit tests plus Compose UI tests (`feature:shell` renders the real frame,
  since screenshot verification needs macOS screen-recording permission that CI lacks)
- **detekt** — [config/detekt/detekt.yml](config/detekt/detekt.yml)
- **securityScan** — [scripts/security-scan.sh](scripts/security-scan.sh)

### The security scan is not optional

`securityScan` fails the build on TLS-bypass patterns — custom `TrustManager`s,
overridden hostname verifiers, `SSLContext.getInstance("SSL")`.

This exists because the Android client ships a trust-all `X509TrustManager` with
hostname verification disabled, wired in unconditionally for every build type
(`NetworkClient.kt:202-254`). Every HTTPS request it makes accepts any certificate
from any host. See plan §8.1.

Certificate validation is never disabled here — not in debug, not behind a flag.
If you need a self-signed QA server, trust that one CA explicitly instead.

The rule is enforced twice on purpose: detekt's `ForbiddenImport` catches the import,
and the grep catches everything else. detekt's `ForbiddenMethodCall` needs type
resolution to fire, which is easy to lose without noticing — the grep is not.

## Reference codebases

Neither is a dependency; both are read for reference.

| | Path | Used for |
|---|---|---|
| Android | `/Users/sahilkashyap/AndroidStudioProjects/ZillitAndroidV20` | business logic, API surface, caching, socket events, LiveKit calling |
| Web | `/Users/sahilkashyap/Downloads/zillit_web-dev 2` | multi-window UX (`src/components/toolWindows/`), design tokens |
