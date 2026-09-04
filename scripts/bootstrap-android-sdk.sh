#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$repo_root/third_party/netbird/pins.sh"
clean_root=${IWS_CLEANROOM_ROOT:-$repo_root/.cleanroom}
downloads=$clean_root/downloads
android_home=${ANDROID_HOME:-$clean_root/android-sdk}
archive=$downloads/android-commandline-tools.zip
tools_dir=$android_home/cmdline-tools/$ANDROID_CMDLINE_TOOLS_VERSION
mkdir -p "$downloads" "$android_home/cmdline-tools"

if [ ! -f "$archive" ]; then
    curl --fail --location --proto '=https' --tlsv1.2 --output "$archive" \
        "$ANDROID_CMDLINE_TOOLS_URL"
fi
actual=$(sha256sum "$archive" | awk '{print $1}')
[ "$actual" = "$ANDROID_CMDLINE_TOOLS_SHA256" ] || {
    echo "Android command-line tools hash mismatch" >&2
    exit 1
}
if [ ! -x "$tools_dir/bin/sdkmanager" ]; then
    unpack=$clean_root/android-commandline-tools.new
    rm -rf "$unpack"
    mkdir -p "$unpack"
    unzip -q "$archive" -d "$unpack"
    mv "$unpack/cmdline-tools" "$tools_dir"
    rmdir "$unpack"
fi

sdkmanager=$tools_dir/bin/sdkmanager
yes | "$sdkmanager" --sdk_root="$android_home" --licenses >/dev/null
"$sdkmanager" --sdk_root="$android_home" \
    "platforms;$ANDROID_PLATFORM" \
    "build-tools;$ANDROID_BUILD_TOOLS" \
    "ndk;$ANDROID_NDK_VERSION"
test -d "$android_home/ndk/$ANDROID_NDK_VERSION"
test -d "$android_home/platforms/$ANDROID_PLATFORM"
test -d "$android_home/build-tools/$ANDROID_BUILD_TOOLS"
