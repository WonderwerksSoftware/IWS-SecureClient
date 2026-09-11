# Production candidate 0.1.0-production-rc1

This candidate is configured for `https://portal.iws.internal/` and pins the
public CA certificate SHA-256 fingerprint
`3976d486cf804696206b98fccb56c2315f29a6690a0272891c562fac2b48a781`.

The `acceptedCommit` and `acceptedTag` values in `client-version.json` preserve
the prior POC acceptance provenance only. This candidate is not a PASS and no
PASS tag has been created.

Remaining gates are the production-signer-backed signed APK build, signer
fingerprint verification, installation on the approved Android device, and live
production endpoint validation. Those gates are intentionally left to the
parent rollout.
