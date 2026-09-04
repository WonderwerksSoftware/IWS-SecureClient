#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$repo_root/windows/pins.env"

inspect_structure() {
    root=${1:?bundle directory is required}
    test -f "$root/iws-transport.exe"
    test -f "$root/wintun.dll"
    test -f "$root/LICENSE"
    test -f "$root/LICENSES/BSD-3-Clause.txt"

    find "$root" -type f -printf '%P\n' | LC_ALL=C sort | while IFS= read -r member; do
        case "$member" in
            iws-transport.exe|wintun.dll|LICENSE|LICENSES/BSD-3-Clause.txt|BUNDLE-MANIFEST.sha256|README.md|pins.psd1|IwsPrivateTransport.psm1|Install-IwsPrivateTransport.ps1|Remove-IwsClientPoc.ps1|payload.example.json)
                ;;
            *)
                echo "unapproved Windows bundle member: $member" >&2
                exit 1
                ;;
        esac
    done
}

if [ "${1:-}" = "--inspect-structure" ]; then
    inspect_structure "${2:?bundle directory is required}"
    exit 0
fi

if [ "$#" -ne 0 ]; then
    echo "usage: $0 [--inspect-structure DIR]" >&2
    exit 2
fi

clean_root=$repo_root/.cleanroom/windows
download_dir=$clean_root/downloads
bundle_root=$clean_root/bundle
dist_dir=$repo_root/dist/windows
archive=$download_dir/netbird-$NETBIRD_VERSION-windows-amd64-signed.tar.gz

mkdir -p "$download_dir" "$dist_dir"
if [ ! -f "$archive" ]; then
    curl --fail --location --proto '=https' --tlsv1.2 \
        --output "$archive" "$NETBIRD_WINDOWS_ARCHIVE_URL"
fi

actual=$(sha256sum "$archive" | awk '{print $1}')
test "$actual" = "$NETBIRD_WINDOWS_ARCHIVE_SHA256" || {
    echo "NetBird Windows archive hash mismatch" >&2
    exit 1
}

rm -rf "$bundle_root"
mkdir -p "$bundle_root/LICENSES"
tar -xzf "$archive" -C "$bundle_root" \
    netbird.exe wintun.dll LICENSE LICENSES/BSD-3-Clause.txt
mv "$bundle_root/netbird.exe" "$bundle_root/iws-transport.exe"

test "$(sha256sum "$bundle_root/iws-transport.exe" | awk '{print $1}')" \
    = "$NETBIRD_WINDOWS_EXE_SHA256"
test "$(sha256sum "$bundle_root/wintun.dll" | awk '{print $1}')" \
    = "$WINTUN_DLL_SHA256"

for file in pins.psd1 IwsPrivateTransport.psm1 Install-IwsPrivateTransport.ps1 Remove-IwsClientPoc.ps1 payload.example.json README.md; do
    if [ -f "$repo_root/windows/$file" ]; then
        cp "$repo_root/windows/$file" "$bundle_root/$file"
    fi
done

(cd "$bundle_root" && find . -type f ! -name BUNDLE-MANIFEST.sha256 -print0 \
    | LC_ALL=C sort -z | xargs -0 sha256sum > BUNDLE-MANIFEST.sha256)
inspect_structure "$bundle_root"

output=$dist_dir/iws-windows-poc-v$NETBIRD_VERSION.zip
rm -f "$output"
(cd "$bundle_root" && zip -q -r "$output" .)
chmod 0600 "$output"
sha256sum "$output"
