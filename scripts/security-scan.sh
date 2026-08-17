#!/usr/bin/env bash
#
# Build-blocking tripwire for TLS-bypass patterns.
#
# DESKTOP_KMP_PLAN.md §8.2. The Android client ships a trust-all
# X509TrustManager with hostname verification disabled, wired in
# unconditionally for every build type (NetworkClient.kt:202-254). Every HTTPS
# request it makes accepts any certificate from any host.
#
# This script exists so that defect cannot reach the desktop codebase. It is
# deliberately a grep rather than a detekt rule: detekt's ForbiddenMethodCall
# needs type resolution to fire, which makes it easy to silently lose. A grep
# either matches or it does not.
#
# Keep the pattern list free of false positives — a tripwire people learn to
# ignore is worse than no tripwire.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# pattern<TAB>explanation
PATTERNS=$(
  cat <<'EOF'
X509TrustManager	Custom TrustManager — disables certificate validation
TrustAllX509TrustManager	Trust-all TrustManager suppression
hostnameVerifier	Overriding hostname verification defeats TLS host binding
setHostnameVerifier	Overriding hostname verification defeats TLS host binding
ALLOW_ALL_HOSTNAME_VERIFIER	Trust-all hostname verifier
SSLContext.getInstance("SSL")	Deprecated protocol family; use platform defaults
trustAllCerts	Trust-all certificate array
NoopHostnameVerifier	Trust-all hostname verifier
EOF
)

status=0
while IFS=$'\t' read -r pattern reason; do
  [ -z "$pattern" ] && continue
  # Search Kotlin/Java sources only; skip build output and this script's own dir.
  #
  # Comment lines are excluded. Documenting *why* the trust-all pattern is
  # banned necessarily names it, and flagging those mentions made the scan cry
  # wolf on its own rationale — which is how a tripwire becomes noise people
  # route around. Only executable code counts.
  if matches=$(grep -rnF --include='*.kt' --include='*.kts' --include='*.java' \
      --exclude-dir=build --exclude-dir=.gradle --exclude-dir=scripts \
      -- "$pattern" . 2>/dev/null | grep -vE ':[0-9]+:[[:space:]]*(//|\*|/\*)'); then
    echo "SECURITY: forbidden pattern '$pattern'"
    echo "  reason: $reason"
    echo "$matches" | sed 's/^/  /'
    echo
    status=1
  fi
done <<<"$PATTERNS"

if [ "$status" -ne 0 ]; then
  echo "Security scan FAILED. See DESKTOP_KMP_PLAN.md §8.2."
  echo "Certificate validation is never disabled — not in debug, not behind a flag."
  echo "For a self-signed QA server, trust that one CA explicitly instead."
  exit 1
fi

echo "Security scan passed: no TLS-bypass patterns found."
