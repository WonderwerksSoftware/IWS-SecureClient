# Physical Android actual-IWS acceptance evidence

Date: 2026-09-01

Result: PASS for the locked non-production one-app Android contract, subject to
the explicit unrelated-live-peer limitation recorded in the checkpoint.

Evidence summary:

- Installed APK and device `base.apk` SHA-256 both equal
  `d22ed4dd3fd3a7b298efc5484dfb6251e4ae4b7d86132bc593848c654574144c`.
- Enrollment config/state SHA-256 values remained
  `f25576738dfdc6092e0eedb8be1d6741c19d56277348d9eb65941936f27e03ed`
  and `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
  across both update installs.
- Android VPN record: session `IWS`, `bypassable=false`, owner UID `10332`,
  allowed UID ranges `{10332-10332,20332-20332}`, IPv4 default capture,
  `100.83.50.15/16`, no DNS, and IPv6 unreachable.
- Actual IWS Portal, Build, Inventory, API, Socket.IO/WebSocket, actor state,
  first-party cookie state, Back, and Portal/Home passed on the Pixel.
- Staging listener was exactly `100.83.246.85:443`. The separate existing
  Tailscale IPv4/IPv6 listeners were not changed. Host IPv4 and IPv6 forwarding
  remained zero; the rootless staging network was internal.
- IWS positive and negative probes and the Vanadium non-IWS control passed as
  detailed in `docs/checkpoints/android-shell-poc.md`.
- The external POC signer was returned to
  `/home/wcfox/.android/debug.keystore.retired-20260901`, mode 0600, after each
  authorized build and never entered Git or an archive.
- Production, iws-prod, IWS Stack business logic, Tailscale, NetBird policy,
  routing, forwarding, NAT, and Windows implementation were untouched.
