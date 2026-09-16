# Secure Client V1 — accepted implementation freeze

Renny accepted the working provisioning/client behavior and the final Linux
appearance, and requested closure without further changes on September 16, 2026.

## Frozen source and release boundaries

- Windows: `secure-client-v1.0.1`, commit
  `76561fa704309129fb2b001816e427a804fc2f6b`. Ben's actual laptop completed the
  generated Setup using Clean reinstall; Renny confirmed Portal, Build,
  Inventory, and normal reopening.
- Android: existing accepted package/signing/transport implementation unchanged.
  The production global source pin remains the Windows/Android 1.0.1 source;
  Android's own package remains versionCode 3 / versionName 1.0.0.
- Linux: `secure-client-v1.0.3` packages the accepted implementation at
  `2ef63e2e34bff31b0ad9a7d12ccf6c97c33c4e51`. The release-only delta is version
  metadata, matching packaging assertions, and this checkpoint. No accepted
  runtime, visual, enrollment, or transport behavior is changed for the freeze.
- Provisioner: Linux-only independent pin selection at
  `0c3437ae1c389f892dc3728ff314834f71405058` prevents a Linux release from moving
  the proven Windows/Android inputs.

The old 1.0.2 tag/artifacts are immutable. They do not acquire later icon or
visual fixes. 1.0.3 is the explicit packaging boundary for those accepted files.
Annotated tag/export provenance records the final release commit and tag object.
The operational handoff records the actual server promotion outcome separately;
the presence of this document alone is not evidence of deployment.

## Accepted Linux result

Shared Debian/Fedora package preparation is offline and preserves identity.
The ordinary IWS launcher offers explicit setup/repair/replacement with normal
administrator approval. Existing staging NetBird state is separate and preserved.
Both package formats were built and checked; Cometforge's half-configured DEB
was repaired through the real generated package. Fedora installation/enrollment
was physically confirmed and its exact device record reconciled online.

The initial visual approximation was rejected. The subsequent actual-Windows
reference correction, including original artwork, navigation rail, theme
controls and connection presentation, was accepted by Renny with a live Linux
Portal screenshot. That appearance is frozen: do not reinterpret it again.

## Honest limits retained at freeze

- This is an implementation/product-scope freeze, not a claim of every possible
  production-readiness or fault-injection test having passed.
- Linux reboot acceptance was explicitly user-deferred; no reboot is invented.
- Cold power recovery/BIOS configuration on iws-prod is a separate deferred task.
- Existing signing/distribution limitations remain; this freeze does not create
  Windows code-signing credentials or unrestricted employee-release approval.
- Theme synchronization with web modules is not expanded here. Native controls
  and web-owned preferences must not be represented as a new unified theme system.
- Development-host presentation updates preserve identity. Do not re-enroll a
  working device merely to deliver an icon or shell appearance change.

No transport redesign, policy broadening, updater project, or further cosmetic
pass is part of V1 closure. Future development is IWS V2, except for an evidenced
regression or a separately approved maintenance task.
