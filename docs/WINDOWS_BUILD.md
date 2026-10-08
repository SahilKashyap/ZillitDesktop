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
- `:desktopApp:packageSignedMsi` gives the `.msi` at the same path, built from a
  signed app image — **the one to build for a release people receive in-app**.
  Needs signing configured; see *In-app updates* below.
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
be named five ways:

| Flag | For |
|---|---|
| `-PzillitAzureSigningEndpoint=… -PzillitAzureSigningAccount=… -PzillitAzureSigningProfile=…` | Azure Artifact Signing — **prefer this** |
| `-PzillitWindowsSigningCert="<subject>"` | a certificate in the store |
| `-PzillitWindowsSigningCert=<40 hex>` | the same certificate, by thumbprint |
| `-PzillitWindowsSigningCert=C:\x.pfx` plus `-PzillitWindowsSigningPassword` | a file |
| `-PzillitWindowsSigningDlib=… -PzillitWindowsSigningDmdf=…` | any other dlib, named by hand |

Prefer Azure, and otherwise the subject. `WindowsInstaller` (in
`core/appupdate`) compares the *subject* on an incoming package against the one
on the running build's launcher, so a build signed under a renewed certificate
still reaches installs that remember the old one — keep `CN=`/`O=` identical
across renewals. Never pin a release build to a thumbprint.

`signtool.exe` is found in the Windows SDK automatically; name it with
`-PzillitSigntool=<path>` if it lives elsewhere. A password belongs in
`%USERPROFILE%\.gradle\gradle.properties` or the environment, never in the
repo's `gradle.properties` — and note that one given this way is written into
Gradle's configuration cache under `build/`, which the store-based and Azure
forms avoid.

### Azure Artifact Signing

The service Microsoft used to call *Trusted Signing*, renamed; the client tools
and NuGet packages were renamed with it. Certificates are reissued **every
three days**, and the subject comes from the one-time identity validation
rather than from any one certificate — which is exactly what the updater's
subject comparison wants, and the reason a release should use this route rather
than a thumbprint.

One-time setup on the Windows machine — **both tools are installed on this
repo's dev machine as of 2026-10-01**; this is the recipe for the next one.

1. **The client tools** — the signing happens inside
   `Azure.CodeSigning.Dlib.dll`, which is not part of Windows:
   ```powershell
   winget install -e --id Microsoft.Azure.ArtifactSigningClientTools
   ```
   It installs **per user**, to
   `%LOCALAPPDATA%\Microsoft\MicrosoftArtifactSigningClientTools`, with the dll
   loose in that directory rather than under the `bin\x64\` that Microsoft's own
   documentation describes. The build searches there and finds it; name it with
   `-PzillitWindowsSigningDlib=<path>` if a future version moves. The NuGet
   package `Microsoft.ArtifactSigning.Client` is the other route.
2. **A current Windows SDK.** The dlib will not load into an old `signtool.exe`
   — it needs the Windows 11 SDK (10.0.22621 or newer), and the 10.0.20348 SDK
   specifically does not work. The build checks this and says so before
   packaging rather than after. The client tools from step 1 bundle the SDK's
   own web installer, and signing tools are the only feature needed:
   ```powershell
   & "$env:LOCALAPPDATA\Microsoft\MicrosoftArtifactSigningClientTools\winsdksetup.exe" /features OptionId.SigningTools /q /norestart
   ```
   It needs elevation and takes a few minutes. It lands in the standard
   location the build already looks in — here it installed 10.0.26100.0, and
   `signtool.bat` beside it claims 10.0.22621.0; either satisfies the check.
3. **A .NET runtime** — but almost certainly already present. The dlib targets
   `net8.0` with `"rollForward": "Major"`, so it runs happily on a newer
   runtime; it worked here against .NET 10.0.12 with no .NET 8 installed. Only
   install .NET 8 if the dlib complains it cannot find a runtime.
4. **Sign in.** Authentication is `DefaultAzureCredential`: `az login` on a
   workstation, or `AZURE_CLIENT_ID` / `AZURE_TENANT_ID` /
   `AZURE_CLIENT_SECRET` on a runner. The account needs the *Certificate
   Profile Signer* role on the certificate profile — being the subscription
   owner is not enough.

Then:

```powershell
.\gradlew.bat :desktopApp:packageSignedMsi -PzillitBundleConfig `
  -PzillitAzureSigningEndpoint=https://eus.codesigning.azure.net `
  -PzillitAzureSigningAccount=Zillit -PzillitAzureSigningProfile=zillit-desktop
```

**Zillit's account** (set up 2026-10-08; none of these is a secret):

| | |
|---|---|
| Endpoint (Account URI) | `https://eus.codesigning.azure.net` (East US) |
| Artifact Signing account | `Zillit` |
| Certificate profile | `zillit-desktop` (Public Trust) |
| Directory (tenant) | `infozillit.onmicrosoft.com` — `4f4106d3-5780-454a-bf5c-20b87f638ee6` |

Sign in to *that* directory, not the default one: `az login --tenant
infozillit.onmicrosoft.com`, as an account holding the **Artifact Signing
Certificate Profile Signer** role on `Zillit` (e.g. `sahil@zillit.com`). A
personal Microsoft account lands in Microsoft's consumer tenant (`9188040d-…`)
and is refused.

For a release, prefer the script, which wraps this command — see *Releasing*
below.

The endpoint is **region-specific and must match the region the account and the
certificate profile were created in** — a mismatch shows up as a 403 during
signing, not as a clear message. The regional URIs are listed in [Microsoft's
signing-integrations
doc](https://learn.microsoft.com/en-us/azure/artifact-signing/how-to-signing-integrations).
The build writes the account metadata JSON the dlib reads itself, to
`desktopApp/build/signing/azure-artifact-signing.json`; none of the three
values is a secret. Pass `-PzillitWindowsSigningDmdf=<file>` instead to
hand-write it — for a `CorrelationId`, or an `ExcludeCredentials` list that
stops `DefaultAzureCredential` trying every mechanism in turn on a runner.

**If you hand-write that file, write it without a byte-order mark.** The dlib
parses it with `System.Text.Json`, which rejects a BOM, and the error names
neither the file nor the BOM:

```
System.Text.Json.JsonException: '0xEF' is an invalid start of a value.
Error information: "Error: SignerSign() failed." (-2147467259/0x80004005)
```

Windows PowerShell's `Out-File -Encoding utf8` and `Set-Content` both add one;
`Set-Content -Encoding utf8NoBOM` (PowerShell 6+), `[IO.File]::WriteAllText`,
or any editor set to "UTF-8 without BOM" do not. The file the build generates
is BOM-free, so this only bites a hand-written one.

Timestamping defaults to Microsoft's own authority
(`http://timestamp.acs.microsoft.com`) on this route and is **not optional**:
with a three-day certificate, an untimestamped package is trusted over a
weekend and refused by every install from Monday. It is always passed; override
with `-PzillitWindowsSigningTimestampUrl`.

**Getting a certificate**, if Azure is not an option. Since June 2023 the
CA/Browser Forum has required code-signing keys to be generated on FIPS 140-2
Level 2 hardware, so no public CA issues a downloadable `.pfx` any more. The
routes are a USB token (OV, ~$200–400/yr), a cloud HSM (DigiCert KeyLocker,
SSL.com eSigner, ~$300–600/yr), or Azure Artifact Signing (~$10/mo, but the
organisation needs three years of verifiable history). EV earns SmartScreen
reputation immediately; OV builds it over a few hundred installs. A self-signed
certificate does satisfy the updater — its check only asks that the status read
`Valid` — but only on machines that trust it, so it is for exercising the
update path, never for distribution.

## Releasing — one command

```powershell
.\scripts\release-msi.ps1 1.1.2 -Check      # is this machine ready?
.\scripts\release-msi.ps1 1.1.2             # build, verify, copy to Downloads
.\scripts\release-msi.ps1 1.1.2 -Verify C:\path\Zillit-Desktop-1.1.2.msi
```

The Windows counterpart of `scripts/release-dmg.sh`. It checks the client tools,
the SDK's `signtool`, WiX, the Azure sign-in and the production keys in
`~/.zillit/zillit.properties`; builds `packageSignedMsi` for production with the
`PROD_` lines of that file bundled (never `STG_`/`QA_`) and Zillit's signing
account; then refuses to call the result good unless the installer's signature
is `Valid` and timestamped, the `Zillit-Desktop.exe` inside is signed by the
same subject, and the bundled config is there. It prints the three Remote
Config values to publish once the `.msi` is uploaded.

## In-app updates

**Build a Windows release people receive in-app with
`:desktopApp:packageSignedMsi`**, not `packageMsi`:

```powershell
.\gradlew.bat :desktopApp:packageSignedMsi -PzillitBundleConfig -PzillitAzureSigning…
```

It lands at the same path — `...\main\msi\Zillit-Desktop-<version>.msi` — and
differs in what is inside it. `packageMsi` signs the installer and nothing
else: Compose hands jpackage an `--app-image` on macOS only, and on Windows
builds the payload from the jlink runtime image, so the `Zillit-Desktop.exe` it
installs is unsigned however the installer was signed. `WindowsInstaller` then
has no signature to compare an incoming package against and falls back to the
digest alone. `packageSignedMsi` signs the launcher **in the app image** and
then runs jpackage over that image with `--app-image`, so the signature ships
inside the installer and the subject comparison engages: a package signed by
anybody else is refused before it runs, however well its digest matches a
Remote Config entry someone managed to edit. It also inherits the app image's
`verifyBundledConfig` check, which `packageMsi` cannot have.

It requires signing to be configured — the task does not exist without it,
since an unsigned one would have no reason to.

**Verified end to end on 2026-10-01** with a self-signed certificate (develop
variant, 1.0.6): the launcher inside the extracted `.msi` reads
`Valid|CN=…` and its signer subject is identical to the package's, which is
precisely the comparison `WindowsInstaller.prepare` makes. The bundled
`zillit.properties` inside the payload was byte-identical to the staged source
and the `.cfg` carried the expected `zillit.env`/`zillit.variant`. Two things
that only a real run could surface, both now handled: `createDistributable`
leaves the launcher **read-only** (signtool rewrites in place and failed with a
bare "Access is denied"), and `.jpackage.xml` lives in `app\`, not at the image
root. Still unexercised on Windows: *installing* and *running* the result — see
the first-run checks below.

**The updater installs `.msi` only.** `WindowsInstaller.accepts` matches on the
extension, so `desktop_installer_url_windows` has to point at the `.msi`; an
`.exe` is merely downloaded and handed to the person to run. Publish
`desktop_installer_sha256_windows` alongside it.

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
