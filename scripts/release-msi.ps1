<#
.SYNOPSIS
  Builds the production Windows installer we hand out: signed through Azure
  Artifact Signing, launcher and installer both, with the production half of
  ~/.zillit/zillit.properties bundled inside. Then it checks the result and
  prints what Remote Config needs in order to offer it as the update.

.EXAMPLE
  .\scripts\release-msi.ps1 1.1.2              # build, verify, copy to Downloads
  .\scripts\release-msi.ps1 1.1.2 -Check       # only check this machine is set up
  .\scripts\release-msi.ps1 1.1.2 -Verify C:\path\Zillit-Desktop-1.1.2.msi
                                               # check an existing .msi, build nothing

.NOTES
  The Windows counterpart of scripts/release-dmg.sh: one recipe, so an .msi is
  the same whoever builds it.

  Signing is `:desktopApp:packageSignedMsi` (see docs/WINDOWS_BUILD.md). That
  task signs the launcher inside the app image, then the installer around it,
  which is what the in-app updater (WindowsInstaller) needs: an installer
  whose launcher is unsigned can only be handed to the person to run.

  The account, endpoint and profile below are not secrets. Signing
  authenticates as whoever ran `az login` (or AZURE_CLIENT_ID / AZURE_TENANT_ID
  / AZURE_CLIENT_SECRET on a runner), and that identity needs the
  "Artifact Signing Certificate Profile Signer" role on the account.

  Only PROD_ lines of the config are bundled: a production app reads nothing
  else, and the developer's file also holds the staging and QA keys. Nothing
  secret is printed: the config is checked by key name, never by value.
#>
param(
    [Parameter(Mandatory = $true, Position = 0)] [string] $Version,
    [switch] $Check,
    [string] $Verify
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Zillit's Azure Artifact Signing account. Public: these appear in every build log.
$SigningEndpoint = 'https://eus.codesigning.azure.net'
$SigningAccount  = 'Zillit'
$SigningProfile  = 'zillit-desktop'
# zillit.onmicrosoft.com — the directory the signing account lives in.
$SigningTenant   = '30aed3b9-096c-4758-9eeb-0115a32f7b83'

$Root   = Resolve-Path (Join-Path $PSScriptRoot '..')
$Config = Join-Path $HOME '.zillit\zillit.properties'
$MinSdkBuild = 22621
$script:Failures = 0

function Ok($msg)   { Write-Host "  ok    $msg" -ForegroundColor Green }
function Bad($msg)  { Write-Host "  FAIL  $msg" -ForegroundColor Red; $script:Failures++ }
function Note($msg) { Write-Host "  note  $msg" -ForegroundColor Yellow }

if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "Version must look like 1.2.3, not '$Version'" }

# -- this machine ---------------------------------------------------------------

function Test-Machine {
    Write-Host "Checking this machine"

    if ($env:OS -ne 'Windows_NT') { Bad 'not Windows: a signed .msi can only be built on Windows' }

    # The signing dll, from the Artifact Signing Client Tools.
    $dlibRoots = @($env:LOCALAPPDATA, "$env:LOCALAPPDATA\Microsoft", $env:ProgramFiles, ${env:ProgramFiles(x86)}) |
        Where-Object { $_ -and (Test-Path $_) }
    $dlib = $dlibRoots |
        ForEach-Object { Get-ChildItem $_ -Directory -ErrorAction SilentlyContinue | Where-Object Name -match 'signing' } |
        ForEach-Object { Get-ChildItem $_.FullName -Recurse -Depth 5 -Filter 'Azure.CodeSigning.Dlib.dll' -ErrorAction SilentlyContinue } |
        Select-Object -First 1
    if ($dlib) { Ok "Artifact Signing client tools ($($dlib.FullName))" }
    else { Bad 'Artifact Signing client tools missing: winget install -e --id Microsoft.Azure.ArtifactSigningClientTools' }

    # signtool from the Windows 11 SDK — the dll will not load into an older one.
    $kits = 'C:\Program Files (x86)\Windows Kits\10\bin'
    $signtool = Get-ChildItem $kits -Directory -Filter '10.*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName 'x64\signtool.exe' } |
        Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $signtool) { Bad 'signtool.exe missing: install the Windows SDK "Signing Tools" feature' }
    else {
        $build = [int](Split-Path (Split-Path (Split-Path $signtool -Parent) -Parent) -Leaf).Split('.')[2]
        if ($build -ge $MinSdkBuild) { Ok "signtool from SDK 10.0.$build" }
        else { Bad "signtool is from SDK 10.0.$build; Artifact Signing needs 10.0.$MinSdkBuild or newer" }
    }

    # WiX 3 — jpackage builds the .msi with it.
    if (Get-Command candle.exe -ErrorAction SilentlyContinue) { Ok 'WiX (candle.exe on PATH)' }
    else { Bad 'WiX 3.x not on PATH: jpackage needs it to build an .msi (see docs/WINDOWS_BUILD.md)' }

    # Who will sign: `az login`, or the runner's service-principal variables.
    if ($env:AZURE_CLIENT_ID -and $env:AZURE_TENANT_ID -and $env:AZURE_CLIENT_SECRET) {
        Ok "signing as service principal $env:AZURE_CLIENT_ID"
    } elseif (Get-Command az -ErrorAction SilentlyContinue) {
        $who = az account show --query '[user.name, tenantId]' -o tsv 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $who) {
            Bad 'not signed in to Azure: run `az login --tenant zillit.onmicrosoft.com`'
        } else {
            # tsv puts both on one line, tab-separated; joined first in case a version prints two lines.
            $user, $tenant = ((@($who) -join "`t") -split "`t")
            if ($tenant -eq $SigningTenant) { Ok "signing as $user in zillit.onmicrosoft.com" }
            else { Bad ("signed in to tenant $tenant, not Zillit's: run " + "'az login --tenant zillit.onmicrosoft.com'") }
        }
    } else {
        Bad 'no Azure sign-in: install the Azure CLI and run `az login`, or set AZURE_CLIENT_ID/TENANT_ID/CLIENT_SECRET'
    }

    # The config that goes inside the installer.
    if (Test-Path $Config) {
        $text = Get-Content $Config -Raw
        $missing = @('PROD_BASE_URL', 'PROD_ENCRYPTION_KEY', 'PROD_IV_ENCRYPTION_KEY',
                     'PROD_FIREBASE_PROJECT_ID', 'PROD_FIREBASE_API_KEY', 'PROD_FIREBASE_APP_ID') |
            Where-Object { $text -notmatch "(?m)^\s*$_\s*[=:]\s*\S" }
        if ($missing) { $missing | ForEach-Object { Bad "$_ missing or blank in ~/.zillit/zillit.properties" } }
        else { Ok '~/.zillit/zillit.properties has the production keys' }
    } else {
        Bad 'no ~/.zillit/zillit.properties to bundle: the installer would not start for anyone'
    }

    $free = [math]::Floor((Get-PSDrive -Name ($Root.Path.Substring(0, 1))).Free / 1GB)
    if ($free -ge 10) { Ok "$free GB free" } else { Bad "only $free GB free; an .msi build needs about 10 GB" }
}

# -- the result -------------------------------------------------------------------

function Test-Installer($msi) {
    Write-Host "Checking $msi"
    if (-not (Test-Path $msi)) { Bad "no installer at $msi"; return }

    $sig = Get-AuthenticodeSignature -FilePath $msi
    if ($sig.Status -eq 'Valid') { Ok "signature Valid — $($sig.SignerCertificate.Subject)" }
    else { Bad "signature is $($sig.Status): $($sig.StatusMessage)" }
    if ($sig.TimeStamperCertificate) { Ok 'timestamped (still valid after the 3-day certificate expires)' }
    else { Bad 'not timestamped: Windows would stop trusting it once the short-lived certificate expires' }

    # The launcher inside must be signed by the same subject, or in-app updates fall back to the digest alone.
    $work = Join-Path ([IO.Path]::GetTempPath()) "zillit-msi-$([guid]::NewGuid())"
    Start-Process msiexec.exe -ArgumentList '/a', "`"$msi`"", '/qn', "TARGETDIR=`"$work`"" -Wait -NoNewWindow
    $launcher = Get-ChildItem $work -Recurse -Filter 'Zillit-Desktop.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $launcher) { Bad 'could not find Zillit-Desktop.exe inside the installer' }
    else {
        $inner = Get-AuthenticodeSignature -FilePath $launcher.FullName
        if ($inner.Status -eq 'Valid' -and $sig.SignerCertificate -and
            $inner.SignerCertificate.Subject -eq $sig.SignerCertificate.Subject) {
            Ok 'launcher inside is signed by the same subject (in-app updates will check it)'
        } else {
            Bad "launcher inside is $($inner.Status) / '$($inner.SignerCertificate.Subject)'"
        }
        $cfg = Get-ChildItem $work -Recurse -Filter 'zillit.properties' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($cfg) {
            if ((Get-Content $cfg.FullName -Raw) -match '(?m)^\s*(STG|QA)_') { Bad 'bundled config carries STG_/QA_ keys' }
            else { Ok 'config bundled (production keys only)' }
        } else { Bad 'no config inside: it would not start for anyone without ~/.zillit/zillit.properties' }
    }
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}

# -- run ----------------------------------------------------------------------------

if ($Verify) {
    Test-Installer (Resolve-Path $Verify).Path
    if ($script:Failures) { Write-Host "`n$script:Failures check(s) failed." -ForegroundColor Red; exit 1 }
    Write-Host "`nAll checks passed." -ForegroundColor Green
    exit 0
}

Test-Machine
if ($script:Failures) { Write-Host "`n$script:Failures check(s) failed; fix them and run again." -ForegroundColor Red; exit 1 }
if ($Check) { Write-Host "`nThis machine is ready to build $Version." -ForegroundColor Green; exit 0 }

# The production lines only, under the one name the launcher reads.
$stageDir = Join-Path ([IO.Path]::GetTempPath()) "zillit-config-$([guid]::NewGuid())"
New-Item -ItemType Directory $stageDir | Out-Null
$staged = Join-Path $stageDir 'zillit.properties'
# WriteAllLines, not Set-Content: Windows PowerShell 5's -Encoding UTF8 adds a
# byte-order mark, and a BOM glued to the first key hides that key from the reader.
$lines = Get-Content $Config | Where-Object { $_ -notmatch '^\s*(STG|QA)_' }
[IO.File]::WriteAllLines($staged, [string[]] $lines, (New-Object Text.UTF8Encoding $false))

$log = Join-Path $stageDir 'build.log'
$flags = @(
    ':desktopApp:packageSignedMsi',
    "-Pzillit.version=$Version",
    '-PzillitEnv=production',
    '-PzillitVariant=',
    "-PzillitBundleConfig=$staged",
    "-PzillitAzureSigningEndpoint=$SigningEndpoint",
    "-PzillitAzureSigningAccount=$SigningAccount",
    "-PzillitAzureSigningProfile=$SigningProfile",
    '--no-configuration-cache'
)
Write-Host "`nBuilding Zillit-Desktop $Version (log: $log)"
Push-Location $Root
try {
    & .\gradlew.bat @flags *> $log
    $code = $LASTEXITCODE
} finally { Pop-Location }
if ($code -ne 0) {
    Get-Content $log -Tail 40
    Write-Host "`nBuild failed (exit $code). Full log: $log" -ForegroundColor Red
    exit 1
}

$msi = Join-Path $Root "desktopApp\build\compose\binaries\main\msi\Zillit-Desktop-$Version.msi"
Test-Installer $msi
Remove-Item $staged -Force -ErrorAction SilentlyContinue
if ($script:Failures) { Write-Host "`n$script:Failures check(s) failed; the installer was NOT copied." -ForegroundColor Red; exit 1 }

$out = Join-Path ([Environment]::GetFolderPath('UserProfile')) "Downloads\Zillit-Desktop-$Version.msi"
Copy-Item $msi $out -Force
$sha = (Get-FileHash $out -Algorithm SHA256).Hash.ToLower()

Write-Host "`nReady: $out" -ForegroundColor Green
Write-Host ''
Write-Host 'To offer it as the update — in Remote Config, once the upload has finished (plain values, no quotes):'
Write-Host "  desktop_latest_version_windows    = $Version"
Write-Host '  desktop_installer_url_windows     = <https link that serves exactly this .msi>'
Write-Host "  desktop_installer_sha256_windows  = $sha"
