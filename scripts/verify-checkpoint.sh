#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$repo_root/third_party/netbird/pins.sh"
cd "$repo_root"
./scripts/secret-scan.sh
test "$(sed -n "s/.*applicationId '\([^']*\)'.*/\1/p" android/app/build.gradle)" \
    = "$ANDROID_APPLICATION_ID"
test "$(sha256sum dist/$NETBIRD_AAR_NAME | awk '{print $1}')" = "$CLEANROOM_AAR_SHA256"
test "$(sha256sum dist/iws-connect-poc-cleanroom.apk | awk '{print $1}')" = "$CLEANROOM_APK_SHA256"
test "$(sha256sum .cleanroom/gopath/bin/gomobile | awk '{print $1}')" = "$CLEANROOM_GOMOBILE_SHA256"
test "$(sha256sum .cleanroom/gopath/bin/gobind | awk '{print $1}')" = "$CLEANROOM_GOBIND_SHA256"
test "$(.cleanroom/android-sdk/build-tools/36.0.0/aapt dump badging \
    dist/iws-connect-poc-cleanroom.apk | sed -n "s/package: name='\([^']*\)'.*/\1/p")" \
    = "$ANDROID_APPLICATION_ID"
.cleanroom/android-sdk/build-tools/36.0.0/zipalign -c -P 16 4 \
    dist/iws-connect-poc-cleanroom.apk
if git ls-files | rg '\.(aar|apk|keystore|jks)$'; then
    echo "binary/keystore tracked by Git" >&2
    exit 1
fi
git status --short --ignored
