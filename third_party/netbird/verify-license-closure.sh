#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
. "$repo_root/third_party/netbird/pins.sh"
source_root=${1:?NetBird source directory is required}
clean_root=${IWS_CLEANROOM_ROOT:-$repo_root/.cleanroom}
report_root=$repo_root/evidence/licenses
mkdir -p "$report_root" "$clean_root/gopath/bin"
export GOPATH=$clean_root/gopath
export GOBIN=$GOPATH/bin
export GOMODCACHE=$clean_root/gomodcache
export GOCACHE=$clean_root/gocache
export PATH=$GOBIN:$PATH
go install "github.com/google/go-licenses@$GO_LICENSES_VERSION"
(cd "$source_root" && GOOS=android GOARCH=arm64 go-licenses report ./client/android) \
    | LC_ALL=C sort > "$report_root/netbird-android-closure.csv"

if rg -i ',.*(agpl|gpl|lgpl)' "$report_root/netbird-android-closure.csv"; then
    echo "prohibited strong-copyleft license found in Android closure" >&2
    exit 1
fi
if rg '^github.com/netbirdio/netbird/(management|signal|relay|combined)(/|,)' \
        "$report_root/netbird-android-closure.csv"; then
    echo "server/control-plane package unexpectedly entered Android closure" >&2
    exit 1
fi
rg -i 'mozilla public license|mpl-2.0' "$report_root/netbird-android-closure.csv" \
    > "$report_root/netbird-android-mpl.csv" || true
