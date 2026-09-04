# Android private-transport POC checkpoint

Accepted: 2026-09-01.

## Exact identity

- Package: `com.impactwiring.iwsconnectpoc`
- Tested device: Pixel 7 Pro, GrapheneOS build `2026081301`, Android 17 / SDK 37
- NetBird: v0.77.1, commit
  `79a06720b684768b421f0a54f3bb14f22704994f`
- Proven APK SHA-256:
  `fc09145b75d5f0d80c076ae6b8c7cb04c4b8ac7a6487eac82e30fc51896d4a74`
- Working NDK 28 AAR SHA-256:
  `35f57f164006ef02df0d02b388b1d07dc7c7f2e72f7ab8c87b0fc49465ca58d7`

## Proven acceptance

- IWS-owned `VpnService` and embedded setup-key enrollment.
- Stock NetBird Android application absent.
- Per-app VPN; only the IWS UID captured through `0.0.0.0/0`.
- NetBird transport sockets protected with `VpnService.protect()`.
- Real NetBird CONNECTED callback was required.
- `http://100.83.246.85:443/` returned exactly
  `IWS PRIVATE TRANSPORT POC OK`.
- Negative tests passed for OLECLANKY Tailscale `100.116.25.100`, ww-devbox
  `100.99.71.15`, iws-prod Tailscale `100.127.228.103`, RFC1918 LANs,
  unrelated NetBird peers, and arbitrary public `1.1.1.1:443`.
- A normal non-IWS app retained ordinary Internet connectivity.

USB ADB was development control only and was not evidence for private endpoint
reachability. Production and IWS Stack were untouched.

## Still POC / not production ready

The checkpoint proves transport architecture and negative reachability. It does
not yet prove the employee-facing IWS portal shell, release identity/signing,
managed provisioning, updates, production operations, or fleet lifecycle.
