# Windows IWS Client Falsification POC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and physically falsify the smallest UI-free Windows IWS client using the pinned signed NetBird v0.77.1 CLI transport.

**Architecture:** An elevated PowerShell controller selectively installs a renamed signed transport executable and `wintun.dll`, creates an IWS-named automatic service on a private named pipe, enrolls from a one-use key file, and installs an IWS launcher. Microsoft Edge app mode is the only employee-facing shell.

**Tech Stack:** PowerShell 5.1, Windows OpenSSH/QEMU guest agent, NetBird v0.77.1, Wintun, Bash artifact builder, Node.js contract tests.

**Spec:** `docs/architecture/windows-poc.md`

## Global Constraints

- NetBird version is exactly `v0.77.1`, commit `79a06720b684768b421f0a54f3bb14f22704994f`.
- Install no `netbird-ui.exe`, tray, NetBird shortcut, SSO flow, or account UI.
- Never print, log, archive, or commit setup-key contents or peer identity.
- Keep NetBird firewall enabled; disable client routes, server routes, DNS, IPv6, profiles, update settings, and network selection.
- Add no routes, LAN advertisement, exit node, SSH, forwarding, NAT, or Tailscale bridge.
- Runtime destination remains `100.83.246.85:443` for this non-production proof.
- Do not modify production, iws-prod, IWS Stack, Android, or NetBird policy.
- The disposable VM is `win-iws-dev` / `DEVMACHINE`, Windows 11 Pro x64.

---

### Task 1: Pin and build the selective transport bundle

**Files:**
- Create: `windows/pins.env`
- Create: `windows/build-bundle.sh`
- Create: `windows/tests/bundle-contract.test.mjs`
- Create: `windows/README.md`

**Interfaces:**
- Consumes: official v0.77.1 signed Windows amd64 archive.
- Produces: ignored `dist/windows/iws-windows-poc.zip` containing only approved runtime and IWS files.

- [ ] Write a Node test that runs `build-bundle.sh --inspect <fixture>` and fails if `netbird-ui.exe`, an unapproved binary, a wrong hash, or a setup-key-like filename enters the manifest.
- [ ] Run the test and confirm RED because the builder does not exist.
- [ ] Add exact archive, `netbird.exe`, and `wintun.dll` SHA-256 pins and implement selective extraction with fail-closed manifest validation.
- [ ] Run the test to GREEN and build the real bundle from the official archive.
- [ ] Verify the bundle manifest and confirm no UI/tray/setup-key artifact exists.

### Task 2: Implement pure Windows command and payload contracts

**Files:**
- Create: `windows/IwsClientPoc.psm1`
- Create: `windows/tests/Invoke-WindowsPocTests.ps1`
- Create: `windows/payload.example.json`

**Interfaces:**
- Produces: `Read-IwsPayload`, `Get-IwsServiceInstallArguments`, `Get-IwsEnrollmentArguments`, `Assert-IwsArtifact`, and `Get-IwsPortalLaunchSpec`.

- [ ] Write PowerShell tests for malformed/missing fields, HTTPS management URL, HTTP(S) staging Portal URL, device-name validation, exact service/enrollment flags, artifact mismatch rejection, and absence of UI commands.
- [ ] Run on the VM and confirm RED because the module does not exist.
- [ ] Implement only the pure validation and argument-building functions.
- [ ] Run the PowerShell suite to GREEN.

### Task 3: Implement live install, launch, and cleanup scripts

**Files:**
- Create: `windows/Install-IwsClientPoc.ps1`
- Create: `windows/Launch-IwsPoc.ps1`
- Create: `windows/Remove-IwsClientPoc.ps1`
- Extend: `windows/tests/Invoke-WindowsPocTests.ps1`

**Interfaces:**
- Installer parameters: `-PayloadPath`, `-BundleRoot`, `-PlanOnly`.
- Launcher parameters: `-PortalUrl`, `-TransportPath`, `-DaemonAddress`.
- Cleanup parameters: `-RemoveIdentity` must be explicit to remove peer state.

- [ ] Add tests using temporary directories and a fake transport command to prove PlanOnly does not read/delete the key, live paths arm cleanup before preflight, failures sanitize output, and cleanup targets only IWS-owned paths/service.
- [ ] Confirm RED for missing live scripts.
- [ ] Implement elevated installation, restricted ACLs, service install/start, setup-key enrollment, status wait, Start Menu shortcut, Edge app-mode launch, and `finally` key deletion.
- [ ] Implement fail-closed launcher and explicit cleanup without automatic identity destruction.
- [ ] Run all Windows tests to GREEN.

### Task 4: Physical pre-enrollment installation proof

**Files:**
- Create: `evidence/windows-poc-2026-09-02/pre-enrollment.md`

**Interfaces:**
- Consumes: built bundle and disposable VM management channel.
- Produces: exact hashes, Authenticode identity, service/process/adapter/route baseline.

- [ ] Transfer the bundle to the VM over host-key-pinned SSH and verify SHA-256.
- [ ] Verify `Get-AuthenticodeSignature` is valid before and after renaming.
- [ ] Run PlanOnly and capture sanitized stages.
- [ ] Install the runtime/service without a setup key and prove no UI/tray/shortcuts/account flow exists.
- [ ] Verify service name/display/start type, named pipe, ACLs, process filename, and no peer identity yet.

### Task 5: One-use enrollment and actual-IWS acceptance

**Files:**
- Update: `evidence/windows-poc-2026-09-02/physical-acceptance.md`

**Interfaces:**
- Consumes: user-provided one-use key file assigned only to `iws-poc-client`.
- Produces: enrolled peer identity and acceptance evidence without recording key contents.

- [ ] Record pre-enrollment routes and negative reachability.
- [ ] Run live enrollment and verify the key file is deleted on success or failure.
- [ ] Confirm assigned NetBird IPv4 and dashboard group membership; ask for dashboard confirmation if unavailable programmatically.
- [ ] Verify Portal, Build, Inventory, `/api`, and Socket.IO against `100.83.246.85:443`.
- [ ] Verify no NetBird UI/tray/account surface exists.
- [ ] Run positive and negative route/reachability checks plus ordinary public-network control.

### Task 6: Reboot persistence and central revocation

**Files:**
- Complete: `evidence/windows-poc-2026-09-02/physical-acceptance.md`

**Interfaces:**
- Consumes: enrolled disposable peer and admin-side peer deletion.
- Produces: final PASS/PARTIAL/FAIL result.

- [ ] Reboot the VM through the accepted management channel.
- [ ] Wait for SSH and prove the IWS service auto-starts and reconnects without setup material.
- [ ] Repeat actual-IWS and isolation checks after reboot.
- [ ] Obtain explicit admin-side peer deletion and prove Portal reachability fails while public networking remains functional.
- [ ] Preserve sanitized evidence, remove temporary transfer/setup artifacts, and leave production untouched.
- [ ] Run the complete source, secret, manifest, and physical evidence verification before committing.
