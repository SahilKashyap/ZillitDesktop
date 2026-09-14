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

Gradle names every DMG `Zillit-Desktop-<zillit.version>.dmg` regardless of
environment. Rename anything that leaves the machine, or a develop build will
eventually reach a real user.

## Telling installs about it

The app asks Firebase Remote Config (the same project as the calling plane's
config) whether it is out of date, once at sign-in and every six hours, and
Settings ▸ About has a *Check for updates* button that asks on demand. After
uploading a build, publish in the console — **plain values, no quotes** (the
console takes a string; typing `"1.0.3"` stores the quotes and, until
2026-09-14, that read as version 0.0.3 and no banner ever showed):

| Key | Value | Effect |
|---|---|---|
| `desktop_latest_version` | `1.0.3` | older installs see a dismissible "Version 1.0.3 is available" strip |
| `desktop_min_version` | `1.0.1` | installs below this see a non-dismissible "must update" strip |
| `desktop_download_url` | `https://…` | the Download button; https only |

Mac and Windows builds rarely ship together, so each key also takes a
platform suffix that wins on that platform: `desktop_download_url_mac`,
`desktop_download_url_windows` (and `_linux`), likewise
`desktop_latest_version_windows` / `desktop_min_version_windows`. The plain
key is the fallback for everyone. Without the Windows URL, a Windows install
is offered whatever the plain key points at — a `.dmg`.

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
