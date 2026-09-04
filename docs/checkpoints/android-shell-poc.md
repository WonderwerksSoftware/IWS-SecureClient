# Android actual-IWS vertical-slice checkpoint

Status: **PASS** for the non-production Android client proof on 2026-09-01.

## Candidate identity

- Package: `com.impactwiring.iwsconnectpoc`
- Device: Pixel 7 Pro / GrapheneOS / Android 17
- Actual-IWS staging source: IWS Stack commit
  `1fc246edb647bc95f6b78d8ce28a4239e20c4581`
- Enrolled-signer APK SHA-256:
  `d22ed4dd3fd3a7b298efc5484dfb6251e4ae4b7d86132bc593848c654574144c`
- Clean-signer APK SHA-256:
  `762b9afd0f686c117efa0db550e00b0e7fdb1fee65b2e57b84a63c7c809faff0`
- Portal origin: `http://100.83.246.85:443/`

## Physical acceptance

- `adb install -r` preserved both enrollment-state file hashes.
- A cold launch automatically established IWS-owned `tun0` at
  `100.83.50.15/16`; Android emitted CONNECTED before the portal loaded.
- The real Portal loaded with actor selection persisted as `Mike`.
- Build created staging batch `IWS-2609-0001` and rendered its 22-step ECU
  recipe, builder, progress, connector view, and machine setup.
- Inventory rendered seeded spools and the created batch through same-origin
  API calls.
- Socket.IO polling and a real WebSocket upgrade passed. A staging scanner event
  reached the physical WebView and opened spool `SPL-202636-0-0`.
- Native Back returned from Build to Portal without exiting. Android 17
  edge-to-edge initially placed the native chrome under the status bar; the
  accepted fix applies status-bar insets and moved controls from `y=21–147` to
  `y=129–255`.
- The persistent IWS Portal control returned from Inventory to the actor-aware
  portal root.
- Actor/localStorage and a first-party cookie survived the normal Home/onStop
  lifecycle and cold relaunch. Temporary acceptance probes were removed.
- WebView resource timing contained only `http://100.83.246.85:443`; no public
  runtime origin or Chromium network error appeared.
- Stock NetBird Android application remained absent.

## Isolation regression

From IWS UID `10332`, `100.83.246.85:443` passed. These targets failed:

- `100.116.25.100:443`
- `100.99.71.15:22`
- `100.127.228.103:22`
- `10.1.10.1:53`
- `172.16.0.1:443`
- `192.168.50.1:22`
- non-allowed overlay address `100.83.246.86:443`
- `1.1.1.1:443`

No independent unrelated live NetBird peer was visible in the staging peer
status, so `100.83.246.86` remains a non-allowed overlay-address proof rather
than a confirmed live-peer probe.

Vanadium loaded `https://example.com` while IWS VPN remained connected and
scoped only to the IWS UID.

## Staging-only compatibility notes

The tracked Build and Inventory frontends depended on a public banner at
runtime. The disposable snapshot vendors the exact PNG, SHA-256
`374678b9787274033446dc36c314372395f046953246e144be503d20bb6afe8f`,
under the same IWS origin and changes only the two staging references.
Production follow-up: required UI assets should be hosted on the trusted IWS
origin.

The repository migration chain assumes an existing V1 database. The disposable
blank staging database used the existing explicit `db:push:dev` script and
sample seed. Production startup and data were untouched.
