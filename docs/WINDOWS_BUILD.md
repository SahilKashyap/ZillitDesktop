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
- The same flag also picks the **build variant** (override on its own with
  `-PzillitVariant=qa`/`develop` if it should ever differ from `-PzillitEnv`):
  the installer becomes `Zillit-Desktop-Dev-<version>.exe`, installs under its
  own Start Menu group and `upgradeUuid`, and the app it installs uses its own
  data directory (`%USERPROFILE%\.zillit-develop`) and Credential Manager
  entries — so a develop install sits beside a prod one instead of upgrading
  over it. A plain `packageExe` with no `-PzillitEnv`/`-PzillitVariant` is
  unaffected by any of this.
- `-PzillitBundleConfig` bundles the properties file staged in step 4 (or
  `-PzillitBundleConfig=C:\path\to\zillit.properties`). Omit it for an
  installer that expects the user to provide the config.

## Signing

Both installers are **unsigned unless a certificate is named**. SmartScreen
warns once on an unsigned one ("More info → Run anyway") — and, more
importantly, the in-app updater refuses to install an unsigned `.msi` at all
(see *In-app updates* below). One flag signs:

```powershell
.\gradlew.bat :desktopApp:packageMsi -PzillitBundleConfig -PzillitWindowsSigningCert="Zillit Pvt Ltd"
```

It signs the installer once jpackage has written it — SHA-256, RFC 3161
timestamped — then verifies it with `signtool verify /pa`. The certificate can
be named four ways:

| Flag | For |
|---|---|
| `-PzillitWindowsSigningCert="<subject>"` | a certificate in the store — **prefer this** |
| `-PzillitWindowsSigningCert=<40 hex>` | the same certificate, by thumbprint |
| `-PzillitWindowsSigningCert=C:\x.pfx` plus `-PzillitWindowsSigningPassword` | a file |
| `-PzillitWindowsSigningDlib=… -PzillitWindowsSigningDmdf=…` | Azure Trusted Signing |

Prefer the subject. `WindowsInstaller` (in `core/appupdate`) compares the
*subject* on an incoming package against the one on the running build's
launcher, so a build signed under a renewed certificate still reaches installs
that remember the old one — keep `CN=`/`O=` identical across renewals.

**Known gap: the launcher inside the package is not signed.** `packageMsi` does
not consume `createDistributable`; jpackage lays the payload out itself from
the runtime image, so there is no signed `Zillit-Desktop.exe` for it to carry.
The subject comparison above therefore never engages — an install with an
unsigned launcher accepts any validly signed package whose digest matches
`desktop_installer_sha256_windows`, which is the gate that actually holds.
Closing it means packaging from an already-signed app image through jpackage's
`--app-image` instead of the Compose task.

`signtool.exe` is found in the Windows SDK automatically; name it with
`-PzillitSigntool=<path>` if it lives elsewhere. A password belongs in
`%USERPROFILE%\.gradle\gradle.properties` or the environment, never in the
repo's `gradle.properties` — and note that one given this way is written into
Gradle's configuration cache under `build/`, which the store-based forms avoid.

**Getting a certificate.** Since June 2023 the CA/Browser Forum has required
code-signing keys to be generated on FIPS 140-2 Level 2 hardware, so no public
CA issues a downloadable `.pfx` any more. The routes are a USB token (OV,
~$200–400/yr), a cloud HSM (DigiCert KeyLocker, SSL.com eSigner, ~$300–600/yr),
or Azure Trusted Signing (~$10/mo, but the organisation needs three years of
verifiable history). EV earns SmartScreen reputation immediately; OV builds it
over a few hundred installs. A self-signed certificate does satisfy the
updater — its check only asks that the status read `Valid` — but only on
machines that trust it, so it is for exercising the update path, never for
distribution.

## In-app updates

**The updater installs `.msi` only.** `WindowsInstaller.accepts` matches on the
extension, so `desktop_installer_url_windows` has to point at the `.msi`; an
`.exe` is merely downloaded and handed to the person to run. A Windows release
that people receive in-app is therefore `:desktopApp:packageMsi`, signed, with
`desktop_installer_sha256_windows` published alongside it.

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
