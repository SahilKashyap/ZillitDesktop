#!/usr/bin/env bash
#
# Builds the production macOS DMG we hand out: signed, notarized, stapled, with
# the production half of ~/.zillit/zillit.properties bundled inside, then checks
# it and prints what Remote Config needs to offer it as the update.
#
#   scripts/release-dmg.sh 1.1.1            # build, verify, copy to ~/Downloads
#   scripts/release-dmg.sh 1.1.1 --check    # only check this Mac is set up
#   scripts/release-dmg.sh 1.1.1 --plain    # docs/RELEASE_MACOS.md's plain
#                                           # production build: no config inside
#   scripts/release-dmg.sh 1.1.0 --verify ~/Downloads/Zillit-Desktop-1.1.0-....dmg
#                                           # check an existing DMG, build nothing
#
# One recipe, so a DMG is the same whoever builds it. Two Macs that build the
# same commit with different flags ship different apps: a build without the
# config inside starts only where the user already has the file, and its update
# check stays off without <ENV>_FIREBASE_APP_ID.
#
# Only PROD_ lines are bundled: a production app reads nothing else, and the
# file a developer keeps also holds the staging and QA header keys, which have
# no business inside a build that goes to everyone. Nothing secret is printed:
# the config and the Gradle credentials are checked by key name, never value.
#
# Never copies, or calls good, a DMG that failed a check. Apple's side flakes —
# the notary upload times out, the timestamp server does not answer while
# re-signing — so those are retried; anything else would fail the same way
# again, so it stops and says which step failed.

set -uo pipefail

CALLER_PWD="$PWD"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Zillit LLC. Public — it is in every signed binary — and the in-app updater
# refuses a build from any other team (MacInstaller), so a DMG signed by
# another team must never be called good.
TEAM_ID="V3TDYTBKXG"
BUNDLE_ID="com.zillit.desktop"
ATTEMPTS=4
MIN_FREE_GB=4

VERSION="${1:-}"
MODE="bundled"
CHECK_ONLY=0
VERIFY_FILE=""
args=("$@")
for (( i = 1; i < ${#args[@]}; i++ )); do
  case "${args[$i]}" in
    --check) CHECK_ONLY=1 ;;
    --plain) MODE="plain" ;;
    --verify)
      i=$((i + 1))
      VERIFY_FILE="${args[$i]:-}"
      [[ -n "$VERIFY_FILE" && "$VERIFY_FILE" != /* ]] && VERIFY_FILE="$CALLER_PWD/$VERIFY_FILE"
      [[ -f "$VERIFY_FILE" ]] || { echo "--verify needs an existing .dmg" >&2; exit 2; } ;;
    *) echo "unknown option: ${args[$i]}" >&2; exit 2 ;;
  esac
done

if [[ ! "$VERSION" =~ ^[1-9][0-9]*\.[0-9]+\.[0-9]+$ ]]; then
  echo "usage: scripts/release-dmg.sh <major.minor.patch> [--check] [--plain] [--verify file.dmg]" >&2
  echo "       (macOS needs the major version to be 1 or more)" >&2
  exit 2
fi

CONFIG="$HOME/.zillit/zillit.properties"
GRADLE_PROPS="$HOME/.gradle/gradle.properties"
LOGS="$ROOT/desktopApp/build/release-logs"
DMG_DIR="$ROOT/desktopApp/build/compose/binaries/main/dmg"
SRC="$DMG_DIR/Zillit-Desktop-$VERSION.dmg"
if [[ "$MODE" == "bundled" ]]; then SUFFIX="PRODUCTION"; else SUFFIX="PLAIN"; fi
OUT="$HOME/Downloads/Zillit-Desktop-$VERSION-$SUFFIX-$(date +%Y%m%d).dmg"
# Never replace a DMG that may already be uploaded and published.
[[ -e "$OUT" ]] && OUT="${OUT%.dmg}-$(date +%H%M%S).dmg"

problems=0
ok()   { echo "  ok    $*"; }
bad()  { echo "  FAIL  $*"; problems=$((problems + 1)); }
note() { echo "  note  $*"; }

# The value of a ~/.gradle/gradle.properties key (only ever read for keys that
# are not secret: the identity's name and the team id).
gradle_prop() { sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$GRADLE_PROPS" 2>/dev/null | tail -1 | sed 's/[[:space:]]*$//'; }

# The production lines of the developer's config: what gets bundled.
STAGED_CONFIG=""
stage_prod_config() {
  local dir; dir="$(mktemp -d)"
  # Keeps the name: the launcher's -Dzillit.config points at resources/zillit.properties.
  grep -vE '^[[:space:]]*(STG|QA)_' "$CONFIG" > "$dir/zillit.properties"
  STAGED_CONFIG="$dir/zillit.properties"
}

if [[ -n "$VERIFY_FILE" ]]; then
  SRC="$VERIFY_FILE"
else

# --- is this Mac set up? -----------------------------------------------------

echo "Checking this Mac"

[[ "$(uname -s)" == "Darwin" ]] && ok "macOS" || bad "macOS only: notarization and DMGs need a Mac"
if [[ "$(uname -m)" != "arm64" || "$(sysctl -n sysctl.proc_translated 2>/dev/null)" == "1" ]]; then
  bad "not running natively on Apple Silicon: the build would come out Intel-only, which installs refuse"
else
  ok "Apple Silicon"
fi

ident="$(gradle_prop zillitSigningIdentity)"
[[ -z "$ident" || "$ident" == "Developer ID Application: "* ]] || ident="Developer ID Application: $ident"
if [[ -z "$ident" ]]; then
  bad "zillitSigningIdentity missing from ~/.gradle/gradle.properties"
elif security find-identity -v -p codesigning 2>/dev/null | grep -qF "\"$ident\""; then
  ok "signing certificate in the keychain: $ident"
else
  bad "the keychain has no valid '$ident' with its private key" \
      "(Apple Development / Distribution certificates cannot sign this; see docs/RELEASE_MACOS.md)"
fi
[[ "$ident" == *"($TEAM_ID)" ]] || [[ -z "$ident" ]] || bad "signing identity is not Zillit LLC's team ($TEAM_ID): installs would refuse the build"

for key in zillitAppleId zillitTeamId zillitAppleIdPassword; do
  if ! grep -qE "^[[:space:]]*$key[[:space:]]*=[[:space:]]*[^[:space:]]" "$GRADLE_PROPS" 2>/dev/null; then
    bad "$key missing from ~/.gradle/gradle.properties"
  elif grep -qE "^[[:space:]]*$key[[:space:]]*=.*[<>]" "$GRADLE_PROPS"; then
    bad "$key still holds a <placeholder> from the docs"
  else
    ok "$key set"
  fi
done
team="$(gradle_prop zillitTeamId)"
[[ -z "$team" || "$team" == "$TEAM_ID" ]] || bad "zillitTeamId is $team, not Zillit LLC's $TEAM_ID"
if grep -qE '^[[:space:]]*zillitVariant[[:space:]]*=' "$GRADLE_PROPS" 2>/dev/null; then
  note "~/.gradle/gradle.properties sets zillitVariant; this script overrides it to production"
fi
if [[ "$MODE" == "plain" ]] && grep -qE '^[[:space:]]*zillitBundleConfig' "$GRADLE_PROPS" 2>/dev/null; then
  bad "~/.gradle/gradle.properties sets zillitBundleConfig, which would bundle a config into a --plain build"
fi

if [[ "$MODE" == "bundled" ]]; then
  if [[ -f "$CONFIG" ]]; then
    missing=0
    for key in PROD_BASE_URL PROD_ENCRYPTION_KEY PROD_IV_ENCRYPTION_KEY \
               PROD_FIREBASE_PROJECT_ID PROD_FIREBASE_API_KEY PROD_FIREBASE_APP_ID; do
      if ! grep -qE "^[[:space:]]*$key[[:space:]]*[=:][[:space:]]*[^[:space:]]" "$CONFIG"; then
        bad "$key missing or blank in ~/.zillit/zillit.properties"; missing=1
      fi
    done
    (( missing )) || ok "config ~/.zillit/zillit.properties has the production keys (fingerprint $(shasum -a 256 "$CONFIG" | cut -c1-12))"
  else
    bad "no ~/.zillit/zillit.properties to bundle (or pass --plain for a build without it)"
  fi
fi

if xcrun --find notarytool >/dev/null 2>&1; then ok "notarytool"; else bad "Xcode command line tools missing (xcode-select --install)"; fi

free_gb="$(df -g "$HOME" | awk 'NR==2 {print $4}')"
if [[ "${free_gb:-0}" -ge "$MIN_FREE_GB" ]]; then ok "${free_gb} GB free"
else bad "only ${free_gb:-0} GB free; a DMG build needs about $MIN_FREE_GB GB"; fi

if pgrep -f "GradleWrapperMain" >/dev/null 2>&1; then
  note "another Gradle build is running here; two at once corrupt each other's caches"
fi
dirty="$(git status --porcelain | wc -l | tr -d ' ')"
[[ "$dirty" == "0" ]] || note "$dirty uncommitted change(s) will be in this build"
declared="$(sed -n 's/^zillit\.version=//p' gradle.properties | tr -d '[:space:]')"
[[ "$declared" == "$VERSION" ]] || note "gradle.properties says $declared; building $VERSION as a one-off"
echo "  build  $(git log -1 --format='%h %s')"

if (( problems > 0 )); then
  echo; echo "$problems problem(s) above: fix them first. Nothing was built."; exit 1
fi
if (( CHECK_ONLY )); then echo; echo "This Mac is ready to build."; exit 0; fi

# --- build -------------------------------------------------------------------

echo; echo "Building $VERSION ($MODE)"
mkdir -p "$LOGS"
# zillitVariant= on the command line beats a stray one in ~/.gradle: production.
flags=(-Pzillit.version="$VERSION" -PzillitEnv=prod -PzillitVariant= --no-configuration-cache)
if [[ "$MODE" == "bundled" ]]; then
  stage_prod_config
  flags+=(-PzillitBundleConfig="$STAGED_CONFIG")
fi

# What Apple's side fails with when it is Apple's side, not ours.
TRANSIENT='deadlineExceeded|abortedUpload|timed out|timestamp was expected|timestamp service|NSURLErrorDomain|network connection was lost|Connection reset|HTTP status code: 5[0-9][0-9]'

stapled=0
for (( attempt = 1; attempt <= ATTEMPTS; attempt++ )); do
  # createDistributable can report up-to-date over a stale image.
  rm -rf "$ROOT/desktopApp/build/compose"
  log="$LOGS/$VERSION-$attempt.log"
  ./gradlew :desktopApp:packageDmg :desktopApp:notarizeDmg "${flags[@]}" >"$log" 2>&1
  rc=$?
  if [[ $rc == 0 && -f "$SRC" ]] && xcrun stapler validate "$SRC" >/dev/null 2>&1; then
    stapled=1; echo "  attempt $attempt: built, notarized and stapled"; break
  fi
  if [[ $rc == 0 ]]; then
    echo "  the build succeeded but made no $SRC; it made: $(ls "$DMG_DIR" 2>/dev/null | tr '\n' ' ')"
    break
  fi
  failed="$(grep -oE '^> Task :[A-Za-z:]+ FAILED' "$log" | head -1)"
  echo "  attempt $attempt failed: ${failed:-see $log}"
  if cat "$log" "$ROOT"/desktopApp/build/compose/logs/*/*err* 2>/dev/null | grep -qE "$TRANSIENT"; then
    (( attempt < ATTEMPTS )) && { echo "  Apple's side; retrying in 2 minutes"; sleep 120; }
  else
    echo "  not retrying: this would fail the same way again. Log: $log"
    grep -E '^e: |What went wrong' -A3 "$log" | head -8 | sed 's/^/    /'
    break
  fi
done
if (( ! stapled )); then
  echo; echo "No notarized DMG for $VERSION. Logs in $LOGS"; exit 1
fi
fi  # end of: set-up checks and build, skipped by --verify

# --- verify ------------------------------------------------------------------

echo; echo "Verifying $(basename "$SRC")"
mount="$(mktemp -d)"
cleanup() { hdiutil detach "$mount" >/dev/null 2>&1; rmdir "$mount" 2>/dev/null; }
trap cleanup EXIT

hdiutil verify "$SRC" >/dev/null 2>&1 && ok "image intact" || bad "image fails hdiutil verify"
xcrun stapler validate "$SRC" >/dev/null 2>&1 && ok "notarization ticket stapled" || bad "no notarization ticket stapled to the DMG"
if hdiutil attach -nobrowse -readonly -noverify -mountpoint "$mount" "$SRC" >/dev/null 2>&1; then
  app="$mount/Zillit-Desktop.app"
  plist() { /usr/libexec/PlistBuddy -c "Print :$1" "$app/Contents/Info.plist" 2>/dev/null; }

  [[ "$(plist CFBundleIdentifier)" == "$BUNDLE_ID" ]] && ok "bundle id $BUNDLE_ID" \
    || bad "bundle id is '$(plist CFBundleIdentifier)', not $BUNDLE_ID: it would not replace the installed app"
  [[ "$(plist CFBundleShortVersionString)" == "$VERSION" ]] && ok "app version $VERSION" \
    || bad "app says version '$(plist CFBundleShortVersionString)'"
  # The numbers the update check compares; a mismatch offers the app itself as an update.
  launcher="$(sed -n 's/.*jpackage\.app-version=\([0-9.]*\).*/\1/p' "$app"/Contents/app/*.cfg 2>/dev/null | head -1)"
  jar="$(ls "$app"/Contents/app/desktopApp-*.jar 2>/dev/null | head -1)"
  built="$(unzip -p "$jar" com/zillit/desktop/BuildInfo.class 2>/dev/null | strings | grep -xE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
  [[ "$launcher" == "$VERSION" && "$built" == "$VERSION" ]] && ok "update check reads $VERSION" \
    || bad "update check would read launcher '$launcher' / BuildInfo '$built', not $VERSION"

  bundled="$app/Contents/app/resources/zillit.properties"
  if [[ "$MODE" == "plain" ]]; then
    [[ -f "$bundled" ]] && bad "--plain build carries a config" || ok "no config inside (plain)"
  elif [[ ! -f "$bundled" ]]; then
    bad "no config inside the app: it would not start for anyone without ~/.zillit/zillit.properties"
  else
    if [[ -n "$STAGED_CONFIG" ]]; then
      cmp -s "$bundled" "$STAGED_CONFIG" && ok "config bundled (production keys of ~/.zillit/zillit.properties)" \
        || bad "bundled config is not the one this build staged"
    else
      ok "config bundled"
    fi
    if grep -qE '^[[:space:]]*(STG|QA)_' "$bundled"; then
      if [[ -n "$VERIFY_FILE" ]]; then note "carries the staging and QA keys too (built before they were left out)"
      else bad "bundled config carries staging/QA keys"; fi
    fi
    for key in PROD_BASE_URL PROD_ENCRYPTION_KEY PROD_FIREBASE_APP_ID; do
      grep -qE "^[[:space:]]*$key[[:space:]]*[=:][[:space:]]*[^[:space:]]" "$bundled" || bad "bundled config has no $key"
    done
  fi

  archs_launcher="$(lipo -archs "$app/Contents/MacOS/Zillit-Desktop" 2>/dev/null)"
  archs_jvm="$(lipo -archs "$app/Contents/runtime/Contents/Home/lib/server/libjvm.dylib" 2>/dev/null)"
  [[ " $archs_launcher " == *" arm64 "* && " $archs_jvm " == *" arm64 "* ]] && ok "Apple Silicon (launcher $archs_launcher, runtime $archs_jvm)" \
    || bad "not an Apple Silicon build (launcher '${archs_launcher:-unreadable}', runtime '${archs_jvm:-unreadable}'): installs refuse it"

  codesign --verify --deep --strict "$app" >/dev/null 2>&1 && ok "signature (deep)" || bad "codesign --deep rejects the app"
  signed_team="$(codesign -dv --verbose=2 "$app" 2>&1 | sed -n 's/^TeamIdentifier=//p')"
  [[ "$signed_team" == "$TEAM_ID" ]] && ok "signed by Zillit LLC ($TEAM_ID)" \
    || bad "signed by team '${signed_team:-none}', not Zillit LLC ($TEAM_ID): installs refuse it"
  verdict="$(spctl -a -t exec -vv "$app" 2>&1 | tr '\n' ' ')"
  [[ "$verdict" == *"accepted"*"Notarized Developer ID"* ]] && ok "Gatekeeper: Notarized Developer ID" || bad "Gatekeeper: $verdict"
else
  bad "could not mount the DMG"
fi
cleanup; trap - EXIT
[[ -n "$STAGED_CONFIG" ]] && rm -rf "$(dirname "$STAGED_CONFIG")"

sha="$(shasum -a 256 "$SRC" | cut -d' ' -f1)"
if (( problems > 0 )) || [[ ${#sha} != 64 ]]; then
  echo; echo "$problems check(s) failed: do NOT ship $(basename "$SRC")."
  [[ -z "$VERIFY_FILE" ]] && echo "Nothing was copied; the DMG is at $SRC"
  exit 1
fi

if [[ -n "$VERIFY_FILE" ]]; then
  echo; echo "Good to ship: $SRC"; echo "SHA-256: $sha"; exit 0
fi

if ! cp "$SRC" "$OUT" || [[ "$(shasum -a 256 "$OUT" 2>/dev/null | cut -d' ' -f1)" != "$sha" ]]; then
  rm -f "$OUT"
  echo; echo "Could not copy it to $OUT (disk full, or no access to Downloads)."
  echo "The verified DMG is at $SRC — SHA-256 $sha"
  exit 1
fi

echo
echo "Ready: $OUT ($(du -h "$OUT" | cut -f1 | tr -d ' '))"
echo "SHA-256: $sha"
echo
echo "To offer it as the update — in Remote Config, once the upload has finished."
echo "The _mac keys, so Windows installs are not pointed at a Mac DMG:"
echo "  desktop_latest_version_mac    = $VERSION"
echo "  desktop_installer_url_mac     = <https link that serves exactly this file>"
echo "  desktop_installer_sha256_mac  = $sha"
