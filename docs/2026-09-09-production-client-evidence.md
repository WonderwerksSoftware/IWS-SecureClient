# Production private HTTPS candidate — physical evidence

Candidate: secure-client-private-https-rc1-20260909. Source deployed to the
server-local packager: ba824956e0d84770ccc610f33a554e5bcdb4de95.
This is not an unconditional employee-distribution approval.

## Configuration

- Origin: https://portal.iws.internal; production peer100.83.75.124.
- Exact private DNS endpoint100.83.75.124 TCP/UDP53; HTTPS TCP443.
- Public CA DER SHA2563976d486cf804696206b98fccb56c2315f29a6690a0272891c562fac2b48a781.
- No CA signing key in either generated client. No certificate-error bypass or HTTP fallback.
- Android per-app capture and Windows disjoint destination/port complement retained.
- NetBird0.77.1 /79a06720b684768b421f0a54f3bb14f22704994f.
- AAR SHA25635f57f164006ef02df0d02b388b1d07dc7c7f2e72f7ab8c87b0fc49465ca58d7.

## Windows — physically verified

Generated installer SHA256dcec4bf7fb697661a6a68e375f5c951fe5e8bebe82f3928629ae2f9d63f1d0d6,
877176844bytes. Installed on disposable win-iws-dev without employee technical
input. Hidden service enrolled peer100.83.0.225 into iws-workers.
Actual private Portal, Build, Inventory, API and secure Socket.IO worked.
Native Back/Home and operator persistence were observed. Reboot restored the
service and DNS automatically; launching IWS displayed the actual Portal.

Repeated actual Fixed Version Chromium process-tree probes observed the real
network-service executable and process restarts. Approved HTTPS/API/WSS passed;
Tailscale, RFC1918, unrelated NetBird and arbitrary public destinations failed.
Native prohibited probes returned AccessDenied; browser probes returned
ERR_NETWORK_ACCESS_DENIED. Ordinary Windows HTTPS remained200. The native probe
used the existing protected IwsBoundaryProbe identity, not a claim that its
native sockets came from IwsClient.exe. The actual Fixed Version binary was used.

Central UI revocation deleted the exact peer (independently verified read-only),
left other peers intact, produced NeedsLogin, and removed fresh TCP443 access.
The UI became Revoked; an explicit re-provision produced generation2. The unused
generation2 key/artifact was then retired through the existing service. No new
peer was installed from it. All owned diagnostic tasks were removed and the
original boundary-probe binary hash restored/verified.

## Android — successful path plus retained timing limitation

Generated release APK SHA256cf13c86245d74c3a2299d5e7aca3cc34c0f12e91ec7b2259b9b27bdc43f340e1,
158988287bytes. Non-debuggable packagecom.impactwiring.iwsconnectpoc,
versionCode2. Production signer certificate SHA256
e8d7f92291306d9376d17b15620fc4442cfe8e65aaa47cc912ff42ed6edd7b8c.
Authorized development Pixel reset/install enrolled100.83.41.50 into iws-workers
automatically; artifact consumed. Ordinary VPN approval was completed physically.
Actual Portal/Build/Inventory, native Back, selected operator persistence were
observed. Scanner success was reported by the user, not automated by this test.

Signed test instrumentation ran in actual target UID10335 with the release APK
unchanged. It measured initial TCP timeout immediately after native CONNECTED,
then correct private DNS and successful TCP after that delay. Clicking the real
native Retry cleared the error and displayed actual Portal/API/WSS successfully.
After positive API/WSS, all eight prohibited native destinations failed and a
final approved TCP control still succeeded. The harness reported30pass/1fail:
the initial two-second timeout is retained, not erased or called31/31.
The result supports working private DNS/TLS, recovery and post-connection
isolation; it does not prove immediate data-plane readiness at native CONNECTED
or reliable no-Retry cold startup. No speculative transport redesign was made.

Only the development Pixel's approved Always-on and lockdown settings were
disabled; VPN permission, per-app isolation, Wi-Fi and wireless ADB remained.
Normal Vanadium visibly loaded public HTTPS while the VPN indicator remained.
The separate diagnostic APK was uninstalled. Test APKv5 SHA256
a59ba5055837209518a2e57df29559584e635004d1b34f704e81289fdaa9db84.

## Not production ready / remaining gates

- Android early-readiness/cold-start timing remains explicitly unqualified.
- Strict Android Private DNS is deferred/unsupported; do not claim it passed.
- New Android signer exists protected server-side; off-host custody/backup still
  requires a recorded protected destination/process before employee distribution.
- No authorized Windows production code-signing identity is available. Windows
  results are unsigned development installer acceptance, not SmartScreen approval.
- Existing staging certificate-negative evidence applies to unchanged validation
  code; no invalid certificates were injected into live production.
- Packaging23/23 and source secret scan freshly passed on this source revision.
- No update infrastructure, V2 redesign, or employee fleet cutover is included.
