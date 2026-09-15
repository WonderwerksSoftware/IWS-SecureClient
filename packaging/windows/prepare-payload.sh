#!/bin/sh
set -eu
umask 077

transport=${1:?usage: prepare-payload.sh TRANSPORT_BUNDLE WEBVIEW_BUNDLE UNINSTALLER_EXE OUTPUT_DIR}
webview=${2:?usage: prepare-payload.sh TRANSPORT_BUNDLE WEBVIEW_BUNDLE UNINSTALLER_EXE OUTPUT_DIR}
uninstaller=${3:?usage: prepare-payload.sh TRANSPORT_BUNDLE WEBVIEW_BUNDLE UNINSTALLER_EXE OUTPUT_DIR}
output=${4:?usage: prepare-payload.sh TRANSPORT_BUNDLE WEBVIEW_BUNDLE UNINSTALLER_EXE OUTPUT_DIR}
script_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

for root in "$transport" "$webview"; do
    [ -d "$root" ] && [ ! -L "$root" ] && [ -f "$root/BUNDLE-MANIFEST.sha256" ] || {
        echo "IWS Windows bundle input is invalid" >&2
        exit 1
    }
    (cd "$root" && sha256sum -c BUNDLE-MANIFEST.sha256 >/dev/null)
done
[ -f "$uninstaller" ] && [ ! -L "$uninstaller" ] || {
    echo "IWS Windows uninstaller input is invalid" >&2
    exit 1
}
[ ! -e "$output" ] || { echo "IWS Windows payload output already exists" >&2; exit 1; }

parent=$(dirname "$output")
mkdir -p "$parent"
work=$(mktemp -d "$parent/.iws-windows-payload-XXXXXX")
cleanup() { rm -rf "$work"; }
trap cleanup EXIT HUP INT TERM
payload=$work/windows-payload
mkdir -p "$payload/LICENSES"

for member in \
    iws-transport.exe \
    wintun.dll \
    LICENSE \
    LICENSES/BSD-3-Clause.txt \
    pins.psd1 \
    IwsPrivateTransport.psm1 \
    Install-IwsPrivateTransport.ps1 \
    Remove-IwsClientPoc.ps1; do
    [ -f "$transport/$member" ] || { echo "IWS transport bundle is incomplete" >&2; exit 1; }
    cp "$transport/$member" "$payload/$member"
done

for member in \
    IwsClient.exe \
    IwsBoundaryProbe.exe \
    Microsoft.Web.WebView2.Core.dll \
    Microsoft.Web.WebView2.WinForms.dll \
    WebView2Loader.dll \
    IwsWebViewFirewall.psm1 \
    Set-IwsWebViewBoundary.ps1 \
    Remove-IwsWebViewBoundary.ps1 \
    Install-IwsProductionTrust.ps1 \
    iws-production-root-ca.crt \
    Remove-IwsWebViewShellPoc.ps1; do
    [ -f "$webview/$member" ] || { echo "IWS WebView2 bundle is incomplete" >&2; exit 1; }
    cp "$webview/$member" "$payload/$member"
done
[ -d "$webview/WebView2Fixed" ] && [ ! -L "$webview/WebView2Fixed" ] || {
    echo "IWS Fixed Version WebView2 runtime is missing" >&2
    exit 1
}
cp -a "$webview/WebView2Fixed" "$payload/WebView2Fixed"
cp "$script_root/Install-IwsWebViewShellDevice.ps1" "$payload/Install-IwsWebViewShellDevice.ps1"
cp "$script_root/../../windows/Backup-IwsClientForCleanReinstall.ps1" "$payload/Backup-IwsClientForCleanReinstall.ps1"
cp "$script_root/../../windows/IwsCleanTransaction.psm1" "$payload/IwsCleanTransaction.psm1"
cp "$script_root/../../windows/Resolve-IwsFailedCleanReinstall.ps1" "$payload/Resolve-IwsFailedCleanReinstall.ps1"
cp "$script_root/../../windows/Restore-IwsClientAfterFailedClean.ps1" "$payload/Restore-IwsClientAfterFailedClean.ps1"
cp "$script_root/../../windows/Uninstall-IwsClient.ps1" "$payload/Uninstall-IwsClient.ps1"
cp "$uninstaller" "$payload/IwsUninstall.exe"

(cd "$payload" && find . -type f ! -name BUNDLE-MANIFEST.sha256 -print0 |
    LC_ALL=C sort -z | xargs -0 sha256sum > BUNDLE-MANIFEST.sha256)
mv "$work" "$output"
chmod 700 "$output" "$output/windows-payload"
trap - EXIT HUP INT TERM
printf '%s\n' "$output"
