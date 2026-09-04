#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$repo_root/third_party/netbird/pins.sh"
clean_root=${IWS_CLEANROOM_ROOT:-$repo_root/.cleanroom}
downloads=$clean_root/downloads
tools_root=$clean_root/tools
mkdir -p "$downloads" "$tools_root" "$repo_root/android/app/libs" "$repo_root/dist"

"$repo_root/scripts/secret-scan.sh"

jdk_archive=$downloads/jdk21.tar.gz
if [ ! -f "$jdk_archive" ]; then
    curl --fail --location --proto '=https' --tlsv1.2 --output "$jdk_archive" "$JDK21_URL"
fi
actual=$(sha256sum "$jdk_archive" | awk '{print $1}')
[ "$actual" = "$JDK21_SHA256" ] || {
    echo "JDK 21 archive hash mismatch" >&2
    exit 1
}
if [ ! -x "$tools_root/jdk21/bin/java" ]; then
    rm -rf "$tools_root/jdk21.new"
    mkdir -p "$tools_root/jdk21.new"
    tar -xzf "$jdk_archive" -C "$tools_root/jdk21.new" --strip-components=1
    mv "$tools_root/jdk21.new" "$tools_root/jdk21"
fi

export JAVA_HOME=$tools_root/jdk21
export PATH=$JAVA_HOME/bin:$PATH
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$clean_root/gradle-home}
export ANDROID_USER_HOME=$clean_root/android-user-home
export ANDROID_HOME=${ANDROID_HOME:-$clean_root/android-sdk}
mkdir -p "$ANDROID_USER_HOME"

"$repo_root/scripts/bootstrap-android-sdk.sh"
if [ -n "${IWS_PROVEN_AAR_FILE:-}" ]; then
    : "${IWS_PROVEN_AAR_SHA256:?}"
    [ "$(sha256sum "$IWS_PROVEN_AAR_FILE" | awk '{print $1}')" = "$IWS_PROVEN_AAR_SHA256" ] || {
        echo "proven Android transport artifact hash mismatch" >&2
        exit 1
    }
    aar=$IWS_PROVEN_AAR_FILE
else
    aar=$("$repo_root/third_party/netbird/build-aar.sh")
fi
cp "$aar" "$repo_root/android/app/libs/netbird-v0.77.1.aar"

set --
while IFS='=' read -r key value; do
    case "$key" in ''|'#'*) continue ;; esac
    set -- "$@" "-P$key=$value"
done < "$repo_root/config/checkpoint/android-poc.properties"

(cd "$repo_root/android" && ./gradlew --no-daemon clean test lint assembleDebug "$@")
apk=$repo_root/android/app/build/outputs/apk/debug/app-debug.apk
cp "$apk" "$repo_root/dist/iws-connect-poc-cleanroom.apk"
sha256sum "$aar" "$repo_root/dist/iws-connect-poc-cleanroom.apk" \
    > "$repo_root/dist/SHA256SUMS"
printf '%s\n' "$repo_root/dist/iws-connect-poc-cleanroom.apk"
