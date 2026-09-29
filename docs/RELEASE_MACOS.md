# Releasing the macOS build

Direct distribution: a signed, notarized `.dmg` on a download link. Not the Mac
App Store — that mandates the App Sandbox, and the call engine is embedded
Chromium, which does not run sandboxed. Gatekeeper is the only gate, and
notarization is what clears it.

## One-time setup

**Certificate.** A single **Developer ID Application** certificate, issued to
*Zillit LLC*, covers both QA builds and production. Only the team's Account
Holder can create one (developer.apple.com → Certificates → **+** → *Developer
ID Application*, under Software). Generate the CSR on the machine that will do
the signing — Keychain Access → Certificate Assistant → *Request a Certificate
From a Certificate Authority* → **Saved to disk** — because the private key
stays where the CSR was made. A `.cer` alone cannot sign anything.

Development certificates cannot substitute. Compose prepends
`Developer ID Application: ` to whatever identity it is given, so an Apple
Development, Mac Developer, or Apple Distribution cert fails as
`Could not find certificate ... in keychain []`, which does not sound like a
wrong certificate type but is one.

Confirm with `security find-identity -v -p codesigning`; the line you need names
the **company**, not a person.

**Credentials.** In `~/.gradle/gradle.properties`, never in the repo:

```properties
zillitSigningIdentity=Developer ID Application: Zillit LLC (<TEAMID>)
zillitAppleId=<apple-id-that-generated-the-password>
zillitTeamId=<TEAMID>
zillitAppleIdPassword=<app-specific-password>
```

The Apple ID must be the account that generated the app-specific password at
appleid.apple.com — a password authenticates only against its own account, and
using a different team member's ID returns `401 Invalid credentials`. Check
credentials in seconds, before spending a build on them:

```bash
xcrun notarytool history --apple-id <id> --team-id <TEAMID> --password <pw>
```

## Building

**Version first.** The build number lives in one place — `zillit.version` in
`gradle.properties` — and feeds the DMG name, the launcher's
`jpackage.app-version`, the version Settings ▸ About shows, and the number the
update check compares against Remote Config. Bump it there before packaging
(or pass `-Pzillit.version=1.0.3` for a one-off). Nothing in
`desktopApp/build.gradle.kts` needs editing.

The number must be **above every version already published** in Remote Config
(`desktop_latest_version`) — see *Releasing an update* below for what goes
wrong otherwise.

QA — bundles `~/.zillit/zillit.properties` inside the app and points at develop,
so testers install and sign in with nothing to configure:

```bash
./gradlew :desktopApp:notarizeDmg -PzillitEnv=develop -PzillitBundleConfig --no-configuration-cache
```

Production — no bundled config, no environment override, so it resolves to prod:

```bash
./gradlew :desktopApp:notarizeDmg --no-configuration-cache
```

`--no-configuration-cache` is required for `notarizeDmg`: Compose's
`MacOSNotarizationSettings` carries a deprecated `ascProvider` field whose getter
throws, and Gradle cannot serialise the task. `packageDmg` is unaffected.

A bundled-config build carries the AES header key in plain text inside the app.
That is the accepted trade for a build a tester can install with nothing to copy
— keep those artifacts on internal distribution only.

**Build variants.** `-PzillitEnv` also picks the DMG's **variant** (override on
its own with `-PzillitVariant=qa`/`develop` if it should ever need to differ
from the server it talks to). A `develop` build above is named
`Zillit-Desktop-Dev-<zillit.version>.dmg`, carries its own bundle id
(`com.zillit.desktop.develop`) and installs its own `Zillit-Desktop-Dev.app` —
so it sits in `/Applications` beside a production install rather than
overwriting it, and it keeps its own data directory (`~/.zillit-develop`),
Keychain entries and login item. Only a plain `packageDmg`/`notarizeDmg` with
neither flag builds the exact same production app as before this existed —
still worth renaming before it leaves the machine, since the DMG's own name is
the only thing that tells a tester which is which before they open it.

## Telling installs about it — and updating them

The app asks Firebase Remote Config whether it is out of date, once at sign-in
and every six hours; Settings ▸ About ▸ *Check for updates* asks on demand.
Where it can, it then updates itself with no click:

1. **Download** the installer in the background to `~/.zillit/updates/downloads`,
   hashing it as it arrives. A digest that does not match
   `desktop_installer_sha256` throws the file away.
2. **Verify** it: the `.app` inside must have this install's bundle id, pass
   `codesign --verify --deep --strict`, carry the **same Team ID** as the
   running build, and be built for this Mac's architecture (`lipo -archs`).
   Anything else is refused and nothing is installed.
3. **Restart** after a countdown in the update strip — 30 s, or 10 s for a
   mandatory update — held while a call is in progress. A helper script waits
   for Zillit to quit, swaps the bundle in `/Applications` (asking for an
   administrator's password if the folder is not writable), and reopens it.

The automatic restart runs once per version. If Zillit comes back still needing
it (the password prompt was cancelled), that version waits for *Restart now*
rather than prompting on every launch. `~/.zillit/updates/install-update.log`
records what the helper did.

A mandatory update — below `desktop_min_version`, or `desktop_force_update` on
and behind `desktop_latest_version` — covers the whole window with an *Update
required* screen whose only other exit is *Quit Zillit*. A Gradle run (`run`,
the IDE) keeps the red strip instead, so a raised floor never locks out a
developer.

### What each install needs before any of this happens

- **The update check needs the Firebase app id.** The `zillit.properties` the app
  reads — bundled with `-PzillitBundleConfig`, or `~/.zillit/zillit.properties`
  on an install without one — must carry `<ENV>_FIREBASE_PROJECT_ID`,
  `<ENV>_FIREBASE_API_KEY` **and `<ENV>_FIREBASE_APP_ID`**. Without the app id
  the check is silently off: no banner, no forced update, nothing.
- **Only builds that have the auto-install code update themselves.** Anything
  older shows the banner at most. The first release carrying it still has to
  reach people the old way.

### Releasing an update, in order

1. **Bump `zillit.version`** above every version already published. The check
   compares numbers only: installs already on a higher number never see the
   new build as newer, and the new build, once installed, is offered the old
   higher-numbered one as an "update".
2. **Build signed and notarized** (see *Building*). An unsigned or ad-hoc
   signed DMG fails step 2 above and is never installed in-app. Always sign
   with the same Developer ID certificate: a different Team ID is refused.
3. **One DMG per environment.** A `develop` build has its own bundle id
   (`com.zillit.desktop.develop`), so STG pointed at the production DMG fails
   verification. Upload each variant's DMG to its own URL.
4. **Hash what you uploaded:**

   ```bash
   shasum -a 256 Zillit-Desktop-1.0.9.dmg
   ```

5. **Publish in Remote Config last**, after the upload has fully finished. Keys
   published first send clients to a file that fails its digest, and that
   version is then not retried automatically.

### The keys

**Plain values, no quotes.** The console takes a string, and typing `"1.0.3"`
stores the quotes too (until 2026-09-14 that read as version 0.0.3, and no
banner ever showed; the app now forgives it, but don't rely on that).

| Key | Value | Effect |
|---|---|---|
| `desktop_latest_version` | `1.0.9` | older installs get the update: a banner, then download → install → restart |
| `desktop_min_version` | `1.0.5` | installs below this are blocked by *Update required* until they update |
| `desktop_force_update` | `true` / `false` | `true` makes **every** install behind `desktop_latest_version` mandatory. An install already current is never forced |
| `desktop_download_url` | `https://…` | the browser fallback; https only. If it links straight to a `.dmg`/`.msi`, it doubles as the installer link |
| `desktop_installer_url` | `https://…/Zillit-Desktop.dmg` | the installer file itself, not a page. Set it even when the download URL is the file: builds from before 2026-09-28 read only this key |
| `desktop_installer_sha256` | 64 hex characters | `shasum -a 256` of that file. **Required** for in-app install, and **must change with every release**, because the URL stays the same while the file behind it does not |

Mac and Windows builds rarely ship together, so each key also takes a platform
suffix that wins on that platform: `_mac`, `_windows`, `_linux` (for example
`desktop_installer_sha256_mac` or `desktop_force_update_windows`). The plain key
is the fallback for everyone. Without a `_windows` download URL, a Windows
install is offered whatever the plain key points at — a `.dmg`.

**Windows cannot install in-app yet.** The `.msi` is unsigned (see
`WINDOWS_BUILD.md`), and the Windows installer refuses anything that is not
Authenticode-`Valid` from the running build's publisher. Until the MSI is
signed, Windows downloads the installer and opens it for the person to run.

Probe what an environment actually serves before blaming the app — the raw
characters matter:

```bash
curl -s -X POST "https://firebaseremoteconfig.googleapis.com/v1/projects/<PROJECT_ID>/namespaces/firebase:fetch?key=<API_KEY>" \
  -H 'Content-Type: application/json' \
  -d '{"appId":"<APP_ID>","appInstanceId":"probe","languageCode":"en"}'
```

## Verifying

```bash
codesign --verify --deep --strict --verbose=2 <path>/Zillit-Desktop.app
spctl -a -t exec -vv <path>/Zillit-Desktop.app
xcrun stapler validate <path>/Zillit-Desktop-1.0.0.dmg
```

**`--deep` is not optional.** Without it, `codesign --verify` checks the outer
seal and stops, and reports a valid signature while nested code is broken — the
failure then arrives as a notarization rejection instead. A release-ready app
reports `accepted` and `source=Notarized Developer ID`.

## Why the build signs twice

`createDistributable` signs the bundle, and `copyCefFrameworks` then writes the
Chromium frameworks into it, breaking the seal it just wrote. The copy cannot
move earlier — `createDistributable` depending on it is a dependency cycle — so
`resignWithFrameworks` signs afterwards. See the comment above that task in
`desktopApp/build.gradle.kts` for what each of its steps is fixing; every one of
them corresponds to a rejection Apple actually returned.
