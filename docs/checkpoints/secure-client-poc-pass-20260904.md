# IWS SecureClient consolidated POC checkpoint

## Proven

- Android automatic one-use enrollment, protect-before-auth, IWS-owned VPN,
  per-app default capture, private Portal/Build/Inventory, productized shell,
  and positive/negative isolation acceptance.
- Windows hidden service enrollment, dedicated productized Fixed Version
  WebView2 shell, non-overlapping 39-rule boundary, reboot persistence,
  install/uninstall replay, and central revocation.
- IWS Stack Device Provisioning V1 creates single-use device artifacts and
  revokes the actual enrolled peer.
- Shared packaging accepts private file references and produces one artifact
  with recorded identity, version, size, and SHA-256.

## Still POC / not production ready

- Windows installer and release signing are not production formats.
- Android release signing, managed notification permission UX, update delivery,
  fleet operations, support telemetry, disaster recovery, and production admin
  integration remain unresolved.
- No updater, generic WonderWerks extraction, macOS client, or Linux client is
  included.
- Nothing in this checkpoint authorizes production deployment.
