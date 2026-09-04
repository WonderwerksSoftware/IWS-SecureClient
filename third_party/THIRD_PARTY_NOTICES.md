# Third-party notices

## NetBird

Android and Windows transport components are pinned to NetBird v0.77.1,
commit `79a06720b684768b421f0a54f3bb14f22704994f`. NetBird source is
BSD-3-Clause. The Android closure also includes MPL-2.0 file-level
weak-copyleft dependencies recorded in `netbird/NOTICE.md` and
`evidence/licenses/`.

## Microsoft WebView2

The Windows shell uses Microsoft.Web.WebView2 SDK `1.0.4191.47` and Fixed
Version Runtime `152.0.4191.53`. Exact URLs and SHA-256 values are in
`windows/webview2/pins.env`. The build extracts and ships the SDK `LICENSE.txt`
with the bundle. WebView2 remains subject to Microsoft's applicable runtime and
redistribution terms; it is not characterized here as an IWS-owned or
permissively relicensed component.

## Wintun

The pinned signed NetBird Windows archive supplies `wintun.dll` and its
applicable BSD-3-Clause notice. The payload build retains the corresponding
license material.
