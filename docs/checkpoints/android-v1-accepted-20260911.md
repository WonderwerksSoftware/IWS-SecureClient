# Android V1 closed — accepted startup readiness

Renny accepted the original V1 startup criteria on 2026-09-11 and explicitly
directed closure without another Android test/review cycle.

## Exact candidate

- Source: `72f019b11ff74a243760e0e572b16ee22d324c0e`.
- Physically installed release APK SHA-256:
  `2e95f2dc31a93a5ecc84ece95078bafe5ec282af42d49add3f590129124c5ee2`.
- Package: `com.impactwiring.iwsconnectpoc`, non-debuggable production RC.
- Signing certificate SHA-256:
  `e8d7f92291306d9376d17b15620fc4442cfe8e65aaa47cc912ff42ed6edd7b8c`.
- Target: Pixel 7 Pro / GrapheneOS, existing identity retained.
- Recorded automated verification: 115 unit tests passing, lint zero errors
  (eight existing resource warnings), signed release assembly successful.

## Accepted physical evidence (not rerun for closure)

Normal cold launch opens Portal automatically without Retry. Delayed private
connectivity remains Connecting and recovers automatically. A long outage
reaches the bounded unavailable state; normal restoration succeeds. API works;
active Build state survives ordinary reconnect/recovery.

The recorded 20-second endpoint interruption showed Connecting while packets
were dropped and automatic Portal recovery afterward. The 75-second case
showed unavailable at approximately 49 seconds, followed by recovery using the
actual Retry control. Temporary fault rules were automatically removed.

Evidence was recorded in `/tmp/iws-readiness-normal-first.png`,
`/tmp/iws-timed-20-during.png`, `/tmp/iws-timed-20-after.png`,
`/tmp/iws-timed-75-during.png`, and `/tmp/iws-timed-75-after.png` on the
development host. These scratch references are not permanent artifact custody.

## Explicitly deferred defect

Deliberately stopping and restarting the entire VPN service underneath an
already-open Build WebView can leave Socket.IO unable to reconnect even after
API access recovers. This is **not a V1 release blocker**, per Renny's ruling.

Reproduction recorded by the diagnostic instrumentation harness: open the real
Build page, establish same-origin secure Socket.IO, invoke the real service
DISCONNECT/CONNECT actions, retain the same WebView/document and draft marker,
then observe API recovery but no Socket.IO reconnection within the observation
window. The v4 diagnostic recorded 40 checks passing and this one failure.
Harness APK SHA-256:
`e9c7ecd5156858e2945bb39a75ad1f59a83535bec3f1d3308a2c19b5ff9e2a85`.

Cause is not established. No claim is made that an ordinary reconnect reproduces
this forced service-replacement case. Further scratch network-gating experiments
were not run and are not part of the accepted application. Do not investigate
or fix this backlog case without explicit authorization.

The worktree was clean at the accepted source commit before this documentation
was added. No post-candidate exploratory application changes require removal.
The generator's previously deployed input revision is distinct from this
physically installed candidate; merging does not itself promote generator inputs
or authorize unrestricted employee distribution. Android signing custody/backup
and Windows release signing remain separately tracked distribution prerequisites.
