# Building the Windows installer (`.exe` / `.msi`)

jpackage — what Compose Desktop's `package*` tasks wrap — builds installers for
**the OS it runs on only**. There is no cross-build: the `.dmg` comes from a Mac,
the `.exe`/`.msi` from Windows, the `.deb` from Linux. Everything below runs on
a Windows 10/11 x64 machine (a VM is fine).

## One-time setup on the Windows machine

1. **Git** and a clone of the repo (branch `certificate_integration` or newer).
2. **A JDK 17+ for the Gradle launcher** — any vendor (Temurin/Zulu). Gradle
   then downloads the *JetBrains Runtime* toolchain itself through the foojay
   resolver (`vendor = JETBRAINS`, Java 21) — that runtime is what the app is
   packaged with, and it is the one that carries JCEF for calls. Needs internet
   on the first build.
3. **WiX Toolset 3.x** (3.11–3.14) — jpackage's requirement for `.exe`/`.msi`
   on Windows. Install and make sure `candle.exe`/`light.exe` are on `PATH`:
   ```powershell
   choco install wixtoolset
   ```
   (or the installer from wixtoolset.org; **not** WiX 4/5).
4. **The production configuration**, only if the installer should ship with the
   keys like the tester DMG does: put `zillit.properties` at
   `%USERPROFILE%\.zillit\zillit.properties`. Copy it from the Mac's
   `~/.zillit/zillit.properties` over a secure channel — never through chat,
   never into the repo. Without it the installed app has no header key and
   cannot talk to the API.

## Build

From the repo root, in PowerShell or `cmd`:

```powershell
.\gradlew.bat :desktopApp:packageExe -PzillitEnv=develop -PzillitBundleConfig
```

- The version in the file names is `zillit.version` from `gradle.properties`
  — bump it there (or `-Pzillit.version=1.0.3`) before packaging; it is also
  what Settings ▸ About shows and what the update check compares.
- `.exe` lands at `desktopApp\build\compose\binaries\main\exe\Zillit-Desktop-<version>.exe`
- `:desktopApp:packageMsi` gives the `.msi` at `...\main\msi\Zillit-Desktop-<version>.msi`
- `:desktopApp:packageDistributionForCurrentOS` builds both.
- `-PzillitEnv=develop` bakes the environment in (`-Dzillit.env=develop` in the
  launcher's `Zillit.cfg`); drop it for a **prod** installer. Check with
  `findstr zillit.env desktopApp\build\compose\binaries\main\app\Zillit-Desktop\app\Zillit-Desktop.cfg`
  before handing it out — a prod desktop shows a prod QR that a develop phone
  cannot scan.
- `-PzillitBundleConfig` bundles the properties file staged in step 4 (or
  `-PzillitBundleConfig=C:\path\to\zillit.properties`). Omit it for an
  installer that expects the user to provide the config.

The installer is **unsigned** (SmartScreen will warn once; "More info → Run
anyway"). Authenticode signing is a later milestone.

After uploading it, publish `desktop_download_url_windows` (and, if the
Windows build's number differs from the Mac's, `desktop_latest_version_windows`
/ `desktop_min_version_windows`) in Firebase Remote Config — plain values, no
quotes. See "Telling installs about it" in `RELEASE_MACOS.md`; without the
Windows URL, Windows installs are offered the Mac `.dmg`.

## First-run checks on Windows — please report these back

None of the following has been exercised on Windows yet (only macOS arm64 has
run this app), so the first build is also the first test:

1. **JCEF natives in the runtime.** On macOS jlink left the Chromium frameworks
   out of the trimmed runtime and the build copies them in (`copyCefFrameworks`,
   `ditto`-based, macOS-only). On Windows JCEF ships as DLLs (`jcef.dll`,
   `libcef.dll`, `chrome_elf.dll`, …) and the layout differs, so check after
   installing:
   ```powershell
   dir "C:\Program Files\Zillit\runtime\bin\*cef*"
   ```
   If they are missing, calls will not render (the log shows a `SIGSEGV`/
   `UnsatisfiedLinkError` right after `KcefRuntime: chromium up`); the fix is
   a Windows counterpart of `copyCefFrameworks` that copies `bin\*.dll` and
   the `lib\` Chromium resources from the JBR into the packaged runtime.
2. **Startup line:** `%USERPROFILE%\.zillit\logs\zillit.log` should show
   `Startup: Environment: develop → https://projectapi-dev.zillit.com` and
   `Header key: from zillit.properties`.
3. **SQLCipher** loads (the DB opens without an `UnsatisfiedLinkError`) —
   the Windows x64 native is in the jar, never loaded.
4. **Credential Manager** holds the session across a restart (macOS Keychain is
   verified; the Windows binding is not).
5. Fonts/metrics — Compose text metrics differ per platform; expect small
   layout differences, not broken screens.

## The other route: GitHub Actions

`.github/workflows/ci.yml` already has a `windows-latest` packaging job
(`packageDistributionForCurrentOS`, on `main` or `workflow_dispatch`) that
uploads the installers as artifacts. It builds **without** the bundled config
(the runner has no `zillit.properties`), so those installers are for smoke
tests only until the config is supplied from repository secrets. Pushing and
triggering that is a step for you to take, not something done from here.
