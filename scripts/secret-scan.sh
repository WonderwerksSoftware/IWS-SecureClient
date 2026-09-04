#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"

bad_names=$(find . -path './.git' -prune -o -path './.cleanroom' -prune -o \
    -path './dist' -prune -o -path './android/.gradle' -prune -o \
    -path './android/build' -prune -o -path './android/app/build' -prune -o \
    -path './android/app/libs' -prune -o \
    -type f \( -iname '*.keystore' -o -iname '*.jks' -o -iname '*setup-key*' \
    -o -iname '*.p12' -o -iname '*.pfx' -o -iname 'netbird.pat' \
    -o -iname 'one-use.key' -o -iname 'config.json' -o -iname '*.log' \
    -o -iname '*.jsonl' -o -iname '*.apk' -o -iname '*.aar' -o -iname '*.exe' \
    -o -iname '*.nupkg' -o -iname '*.cab' \) \
    -print)
[ -z "$bad_names" ] || {
    echo "prohibited source-tree artifacts:" >&2
    printf '%s\n' "$bad_names" >&2
    exit 1
}

tracked=$(git ls-files 2>/dev/null || find . -type f -not -path './.git/*')
tracked=$(printf '%s\n' "$tracked" | rg -v '^scripts/secret-scan\.sh$' || true)
[ -n "$tracked" ] || exit 0
if printf '%s\n' "$tracked" | xargs rg -n --no-heading \
    '(-----BEGIN (OPENSSH|RSA|EC|PRIVATE) PRIVATE KEY-----|netbird\.json|NETBIRD_PAT\s*[:=])' ; then
    echo "possible credential or peer state found" >&2
    exit 1
fi
if printf '%s\n' "$tracked" | xargs rg -n -P --no-heading \
    "(?i)(NB_SETUP_KEY|setup[_-]?key)\\s*[:=]\\s*(?:[\"'](?!__|\\\$|<)[A-Za-z0-9][A-Za-z0-9_-]{19,}[\"']|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})" ; then
    echo "possible credential or peer state found" >&2
    exit 1
fi
