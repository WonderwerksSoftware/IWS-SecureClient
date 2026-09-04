# Windows IWS Client Falsification POC

Status: approved for disposable physical testing.

## Goal

Prove that Windows can present one employee-facing IWS application while a
headless NetBird v0.77.1 transport runs only as an IWS implementation detail.
No production deployment, polished installer, or NetBird desktop UI is in
scope.

## Components

- `iws-transport.exe`: the official signed v0.77.1 `netbird.exe` bytes under an
  IWS process filename.
- `wintun.dll`: the pinned transport driver library shipped with that release.
- `IWSPrivateTransport`: automatic LocalSystem Windows service using
  `npipe://iws-private-transport`.
- `C:\ProgramData\IWS\Transport`: ACL-protected peer identity, state, service
  parameters, and logs through `NB_STATE_DIR`.
- `Install-IwsPrivateTransport.ps1`: elevated one-time hidden-service and
  enrollment controller.
- `IwsClient.exe`: dedicated IWS-owned Fixed Version WebView2 employee shell.
- `IwsWebViewFirewall.psm1`: non-overlapping 39-rule application boundary for
  `IwsClient.exe`, its fixed Chromium runtime, and the boundary probe.
- `Remove-IwsClientPoc.ps1`: explicit disposable cleanup tool.

`netbird-ui.exe`, NetBird shortcuts, tray registration, SSO/account flows,
NetBird network selection, profiles, and update controls are prohibited.

## Enrollment and lifecycle

The controller validates hashes and Authenticode, copies only the two approved
runtime files, installs the service, sets an IWS display name, starts it, and
enrolls through `--setup-key-file`. The key is armed for deletion immediately
after safe path resolution and removed in `finally` on every live path.

Enrollment uses:

- `--disable-client-routes`
- `--disable-server-routes`
- `--disable-dns`
- `--disable-ipv6`
- `--block-inbound`
- `--block-lan-access`
- firewall enabled
- interface name `IWSPrivate`

The service must start automatically after reboot and reconnect from persisted
identity without the setup key.

## Revocation

Central revocation deletes the peer from NetBird Management. No administrative
token is stored on the Windows device. Revoking the setup key alone is not peer
revocation. After peer deletion, the service may remain running but the Portal
must become unreachable and the dedicated shell must fail closed with IWS wording.

## Acceptance

The physical VM must prove:

1. only the IWS service/process and launcher are installed;
2. setup-key enrollment is non-interactive and one-use material is deleted;
3. identity survives service restart and Windows reboot;
4. actual staging Portal, Build, Inventory, API, and Socket.IO work;
5. no NetBird tray/window/account workflow appears;
6. `100.83.246.85:443` succeeds while known Tailscale, RFC1918, unrelated
   overlay, and prohibited public targets fail through the NetBird path;
7. ordinary Windows public networking remains functional;
8. central peer deletion removes private IWS reachability.

The VM has no Tailscale client installed. Same-host dual-overlay coexistence is
therefore not proven by this VM; known Tailscale destinations are negative
targets and the limitation must be explicit.
