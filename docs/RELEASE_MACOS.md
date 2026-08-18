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

Gradle names every DMG `Zillit-1.0.0.dmg` regardless of environment. Rename
anything that leaves the machine, or a develop build will eventually reach a
real user.

## Verifying

```bash
codesign --verify --deep --strict --verbose=2 <path>/Zillit.app
spctl -a -t exec -vv <path>/Zillit.app
xcrun stapler validate <path>/Zillit-1.0.0.dmg
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
