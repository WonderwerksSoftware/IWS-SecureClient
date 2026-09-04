# IWS Secure Client

Canonical source for the IWS-owned Android and Windows applications that open
IWS over private connectivity without exposing NetBird, setup keys, addresses,
or tunnel controls to employees.

This repository owns the native clients, platform packaging logic, reproducible
third-party pins, client-local isolation controls, and client release metadata.
The IWS Portal, Build Wizard, Inventory, business backend, privileged Devices
service, NetBird PAT, provisioning database, and peer revocation logic remain in
IWS Stack.

## Repository layout

- `android/` — accepted Android application, IWS-owned `VpnService`, embedded
  NetBird adapter, per-app isolation, native shell, and tests.
- `windows/` — accepted hidden transport controller, dedicated Fixed Version
  WebView2 shell, firewall boundary, build scripts, and tests.
- `branding/` — shared accepted IWS client mark assets.
- `packaging/` — versioned platform packager boundary consumed by IWS Stack.
- `scripts/` — clean-room Android build, secret scan, and artifact verification.
- `third_party/` — exact NetBird pins, license records, and closure checks.
- `docs/` — architecture, provenance, security, and physical checkpoint records.

## Versioned packager contract

IWS Stack selects an exact SecureClient tag/commit and invokes
`packaging/package-device.mjs` with `IWS_PACKAGE_REQUEST_FILE` pointing to a
mode-`0600` JSON request. The request contains private file paths, never the
setup key itself. The packager returns one JSON metadata record for one APK or
EXE. See `docs/architecture/provisioning-boundary.md` and
`client-version.json`.

## Verification

```sh
npm test
node --test windows/webview2/tests/productization-contract.test.mjs
./scripts/secret-scan.sh
./scripts/build-android-poc.sh
```

Windows compilation and PowerShell contracts require the pinned Windows
toolchain documented under `windows/webview2/` and are verified on the
disposable Windows client VM.

## Status

Android and Windows one-app POC architecture, productized shells, provisioning,
reboot persistence, revocation, and isolation have physical acceptance
evidence. This is still a POC checkpoint, not a production-ready release: code
signing, installer productization, updates, fleet operations, support telemetry,
and production deployment remain future work.
