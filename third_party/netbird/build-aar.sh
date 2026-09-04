#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
. "$repo_root/third_party/netbird/pins.sh"

clean_root=${IWS_CLEANROOM_ROOT:-$repo_root/.cleanroom}
source_root=$clean_root/netbird
downloads=$clean_root/downloads
tools_root=$clean_root/tools
output_root=$repo_root/dist
evidence_root=${IWS_EVIDENCE_ROOT:-$repo_root/evidence/android-poc-2026-09-01}
mkdir -p "$clean_root" "$downloads" "$tools_root" "$output_root" "$evidence_root"

require_hash() {
    expected=$1
    file=$2
    actual=$(sha256sum "$file" | awk '{print $1}')
    [ "$actual" = "$expected" ] || {
        echo "hash mismatch for $file: expected $expected, got $actual" >&2
        exit 1
    }
}

download() {
    url=$1
    file=$2
    expected=$3
    if [ ! -f "$file" ]; then
        curl --fail --location --proto '=https' --tlsv1.2 --output "$file" "$url"
    fi
    require_hash "$expected" "$file"
}

if [ ! -d "$source_root/.git" ]; then
    git clone --filter=blob:none --no-checkout "$NETBIRD_REPOSITORY" "$source_root"
fi
git -C "$source_root" fetch --force --tags origin "$NETBIRD_COMMIT"
git -C "$source_root" checkout --detach --force "$NETBIRD_COMMIT"
[ "$(git -C "$source_root" rev-parse HEAD)" = "$NETBIRD_COMMIT" ]
[ "$(git -C "$source_root" rev-parse "refs/tags/$NETBIRD_VERSION^{commit}")" = "$NETBIRD_COMMIT" ]
[ "$(git -C "$source_root" rev-parse "refs/tags/$NETBIRD_VERSION^{tag}")" = "$NETBIRD_TAG_OBJECT" ]
require_hash "$NETBIRD_GO_MOD_SHA256" "$source_root/go.mod"

jdk_archive=$downloads/jdk11.tar.gz
download "$JDK11_URL" "$jdk_archive" "$JDK11_SHA256"
if [ ! -x "$tools_root/jdk11/bin/java" ]; then
    rm -rf "$tools_root/jdk11.new"
    mkdir -p "$tools_root/jdk11.new"
    tar -xzf "$jdk_archive" -C "$tools_root/jdk11.new" --strip-components=1
    mv "$tools_root/jdk11.new" "$tools_root/jdk11"
fi

go_archive=$downloads/go.tar.gz
download "$GO_URL" "$go_archive" "$GO_SHA256"
if [ ! -x "$tools_root/go/bin/go" ]; then
    rm -rf "$tools_root/go.new"
    mkdir -p "$tools_root/go.new"
    tar -xzf "$go_archive" -C "$tools_root/go.new" --strip-components=1
    mv "$tools_root/go.new" "$tools_root/go"
fi

export JAVA_HOME=$tools_root/jdk11
export PATH=$tools_root/go/bin:$JAVA_HOME/bin:$clean_root/gopath/bin:$PATH
export GOPATH=$clean_root/gopath
export GOMODCACHE=$clean_root/gomodcache
export GOCACHE=$clean_root/gocache
export GOBIN=$GOPATH/bin
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION
[ -d "$ANDROID_NDK_HOME" ] || {
    echo "Android NDK $ANDROID_NDK_VERSION is required at $ANDROID_NDK_HOME" >&2
    exit 1
}
[ "$(go env GOVERSION)" = "go$GO_VERSION" ]

go install "golang.org/x/mobile/cmd/gomobile@$GOMOBILE_MODULE_VERSION"
go install "golang.org/x/mobile/cmd/gobind@$GOMOBILE_MODULE_VERSION"
verify_mobile_tool() {
    tool=$1
    path=$GOBIN/$tool
    metadata=$(go version -m "$path")
    printf '%s\n' "$metadata" | grep -F "go$GO_VERSION" >/dev/null
    printf '%s\n' "$metadata" | grep -F \
        "golang.org/x/mobile${tab:-	}$GOMOBILE_MODULE_VERSION" >/dev/null ||
        printf '%s\n' "$metadata" | grep -F "$GOMOBILE_MODULE_VERSION" >/dev/null
    sha256sum "$path" >> "$evidence_root/cleanroom-tool-hashes.txt"
}
: > "$evidence_root/cleanroom-tool-hashes.txt"
verify_mobile_tool gomobile
verify_mobile_tool gobind

"$repo_root/third_party/netbird/verify-license-closure.sh" "$source_root"

aar=$output_root/$NETBIRD_AAR_NAME
(cd "$source_root" && GOFLAGS=-buildvcs=false CGO_ENABLED=0 gomobile bind \
    -androidapi="$GOMOBILE_ANDROID_API" \
    -o "$aar" \
    -javapkg=io.netbird.gomobile \
    -ldflags="-checklinkname=0 -X golang.zx2c4.com/wireguard/ipc.socketDirectory=/data/data/$ANDROID_APPLICATION_ID/cache/wireguard -X github.com/netbirdio/netbird/version.version=$NETBIRD_VERSION-iws-poc" \
    ./client/android)
actual_aar_hash=$(sha256sum "$aar" | awk '{print $1}')
printf '%s  %s\n' "$actual_aar_hash" "$aar" \
    > "$evidence_root/cleanroom-aar.sha256"
if [ "$actual_aar_hash" != "$NETBIRD_AAR_SHA256" ]; then
    echo "clean-room AAR differs from proven AAR; entry-level comparison is required" >&2
fi
git -C "$source_root" bundle create "$output_root/netbird-$NETBIRD_VERSION.bundle" "$NETBIRD_VERSION"
printf '%s\n' "$aar"
