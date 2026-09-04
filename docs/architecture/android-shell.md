# Android IWS portal shell

The Android application has one primary UI surface: a WebView below persistent
native **Back** and **IWS Portal** controls. The portal root is supplied by the
build property `iwsPortalUrl`; no production address is hard-coded in source.

On launch, the activity binds the existing IWS-owned `VpnService`, requests VPN
permission when needed, and starts the embedded transport. The WebView remains
behind an IWS-branded connection state until the service emits the real NetBird
`CONNECTED` callback. Disconnect and load failures restore the state pane and a
Retry control without exposing NetBird terminology.

The WebView enables JavaScript and DOM storage because the IWS portal requires
them. First-party cookies are enabled and flushed on activity stop; third-party
cookies, file access, content access, mixed content, automatic JavaScript
windows, and multiple windows are disabled. TLS errors are never bypassed.

`PortalPolicy` requires an HTTP(S) portal root and matches scheme, normalized
host, and effective port. Main-frame navigation is HTTP(S)-only. Resource loads
must use the same origin; matching `ws://` or `wss://` is accepted only as the
WebSocket equivalent of the configured HTTP(S) origin. All other destinations
return an empty blocked response or an IWS-branded error state. The OS-level
TUN policy independently remains the final network boundary.

Native and system Back navigate WebView history and do nothing when history is
empty, so normal back gestures do not unexpectedly exit. **IWS Portal** always
loads the configured root after transport connection, regardless of the current
module path. The shell has no native knowledge of `/build`, `/inventory`, or
future portal modules.

The programmatic native chrome applies Android status-bar insets before laying
out Back and Portal. This is required on Android 17's enforced edge-to-edge
window layout so the controls remain fully visible and touchable below system
UI.
