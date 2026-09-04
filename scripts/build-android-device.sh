#!/bin/sh
set -eu
umask 077
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$repo_root/third_party/netbird/pins.sh"
: "${IWS_DEVICE_MANIFEST_FILE:?}"
: "${IWS_SETUP_KEY_FILE:?}"
: "${IWS_OUTPUT_DIR:?}"
: "${IWS_SIGNER_PROPERTIES:?}"
: "${IWS_EXPECTED_SIGNER_SHA256:?}"
: "${IWS_PROVEN_AAR_FILE:?}"
: "${IWS_PROVEN_AAR_SHA256:?}"
for input in "$IWS_DEVICE_MANIFEST_FILE" "$IWS_SETUP_KEY_FILE" "$IWS_SIGNER_PROPERTIES"; do
    [ -f "$input" ] || { echo "IWS device build input is missing" >&2; exit 1; }
done
[ "$(stat -c %a "$IWS_PROVEN_AAR_FILE")" = 600 ] || { echo "IWS native artifact permissions are invalid" >&2; exit 1; }
[ "$(sha256sum "$IWS_PROVEN_AAR_FILE" | awk '{print $1}')" = "$IWS_PROVEN_AAR_SHA256" ] || {
    echo "IWS native artifact verification failed" >&2; exit 1;
}
[ "$(stat -c %a "$IWS_SETUP_KEY_FILE")" = 600 ] || { echo "IWS setup material permissions are invalid" >&2; exit 1; }
[ "$(stat -c %a "$IWS_SIGNER_PROPERTIES")" = 600 ] || { echo "IWS signer permissions are invalid" >&2; exit 1; }
device_id=$(jq -er '.deviceId | select(type == "string" and length > 0)' "$IWS_DEVICE_MANIFEST_FILE")
generation=$(jq -er '.generation | select(type == "number" and . >= 1 and floor == .)' "$IWS_DEVICE_MANIFEST_FILE")
hostname=$(jq -er '.clientHostname | select(type == "string" and length > 0)' "$IWS_DEVICE_MANIFEST_FILE")
setup_key=$(tr -d '\r\n' < "$IWS_SETUP_KEY_FILE")
[ -n "$setup_key" ] || { echo "IWS setup material is empty" >&2; exit 1; }
private_home=$(mktemp -d "${IWS_OUTPUT_DIR%/}/.gradle-XXXXXX")
source_apk=
cleanup() {
    setup_key=
    [ -z "$source_apk" ] || rm -f "$source_apk" "$repo_root/dist/SHA256SUMS"
    rm -rf "$private_home"
}
trap cleanup EXIT HUP INT TERM
chmod 700 "$private_home"
properties="$private_home/gradle.properties"
{
    printf 'iwsBootstrapSetupKey=%s\n' "$setup_key"
    printf 'iwsBootstrapHostname=%s\n' "$hostname"
    printf 'iwsSignerPropertiesFile=%s\n' "$IWS_SIGNER_PROPERTIES"
} > "$properties"
chmod 600 "$properties"
IWS_EVIDENCE_ROOT="$private_home/evidence" IWS_DEVICE_BUILD_PROPERTIES="$properties" \
    "$repo_root/scripts/build-android-poc.sh" >/dev/null
source_apk="$repo_root/dist/iws-connect-poc-cleanroom.apk"
[ -f "$source_apk" ] || { echo "IWS Android build did not produce an APK" >&2; exit 1; }
safe_id=$(printf '%s' "$device_id" | tr -c 'A-Za-z0-9_-' '-')
output="$IWS_OUTPUT_DIR/IWS-${safe_id}-g${generation}.apk"
cp "$source_apk" "$output"
chmod 600 "$output"
count=$(unzip -p "$output" 'classes*.dex' | strings | grep -Fo "$setup_key" | wc -l)
[ "$count" -eq 1 ] || { echo "IWS Android bootstrap occurrence check failed" >&2; rm -f "$output"; exit 1; }
if unzip -p "$output" | strings | grep -Fq 'NETBIRD_PAT'; then
    echo "IWS Android artifact contains prohibited admin material" >&2
    rm -f "$output"
    exit 1
fi
apksigner="${IWS_CLEANROOM_ROOT:-$repo_root/.cleanroom}/android-sdk/build-tools/$ANDROID_BUILD_TOOLS/apksigner"
[ -x "$apksigner" ] || { echo "IWS APK verifier is unavailable" >&2; rm -f "$output"; exit 1; }
actual_signer=$("$apksigner" verify --print-certs "$output" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | tr 'A-F' 'a-f')
expected_signer=$(printf '%s' "$IWS_EXPECTED_SIGNER_SHA256" | tr -d ':' | tr 'A-F' 'a-f')
[ -n "$actual_signer" ] && [ "$actual_signer" = "$expected_signer" ] || {
    echo "IWS Android signer verification failed" >&2
    rm -f "$output"
    exit 1
}
printf '%s\n' "$output"
