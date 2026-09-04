# Windows One-App IWS POC Checkpoint — 2026-09-02

## Checkpoint identity

- Repository: `/home/wcfox/dev/iws-client`
- Isolated worktree: `/home/wcfox/dev/iws-client-windows-poc`
- Branch: `codex/windows-poc`
- Last functional/lifecycle commit before this record: `162b371c409a94879ac9969db244506c36e9e0f7`
- Checkpoint tag: `windows-one-app-poc-pass-20260902`
- Nothing was pushed or deployed to production.

## Exact upstream and toolchain pins

- NetBird version: `v0.77.1`
- NetBird source commit: `79a06720b684768b421f0a54f3bb14f22704994f`
- Signed Windows transport archive SHA-256: `c5bd39b27334a09a43165791f8876618c6bee33bb78025ba20462dd7a19a9002`
- Microsoft WebView2 SDK: `Microsoft.Web.WebView2` `1.0.4191.47`
- WebView2 SDK NuGet SHA-256: `f492bbf547d0da329553b6727435b677579b1e9f91cc9e4a1ad029366d5f23d0`
- Fixed Version WebView2 Runtime: `152.0.4191.53` x64
- Fixed Runtime CAB SHA-256: `f8f200b57d6a7a71d380f777f5c0ea0f71f520048add21e15737121de9ba4f68`
- Disposable compiler: .NET Framework 4.8 C# compiler `4.8.9221.0`, C# 5, WinForms
- Tested OS: Windows 11 Pro x64 on disposable VM `DEVMACHINE`

## Exact installed artifact hashes

| Artifact | SHA-256 |
| --- | --- |
| `IwsClient.exe` | `e22680cab787eccd8e59fd947d010fa95e4126177a4a98a7ae7a86a613829081` |
| `IwsBoundaryProbe.exe` | `4d9c3514557708465a58695a3d100c6ff9305a929d7e2d4dc4d9cbffc98b54ee` |
| `Microsoft.Web.WebView2.Core.dll` | `e6f54c8ce208e3797c427d01ad671b47cb25abc85604753d6ec2546d0ffef550` |
| `Microsoft.Web.WebView2.WinForms.dll` | `cc3d2937c350a4f5e20399855bcab4fe695a395308feb2ed67394faa6a1ae849` |
| `WebView2Loader.dll` | `c66e4a92fdc7a216118e43b7a5024ea2200e8c43f9310bf20d96a0084f82c5bc` |
| Fixed Runtime `msedgewebview2.exe` | `b350c38257352c4105462c450cc380cb159120ab6794a343d8a7d557740757ce` |
| `iws-transport.exe` | `4c0f3e80ab9d49df65f039f971d8f989615914667bc28b2093933475382e7880` |
| `wintun.dll` | `e5da8447dc2c320edc0fc52fa01885c103de8c118481f683643cacc3220dafce` |

The Fixed Runtime browser is Authenticode-valid and signed by Microsoft Corporation. The transport is Authenticode-valid and signed by NetBird GmbH. `IwsClient.exe` is a disposable unsigned POC binary.

Fresh builds of `IwsClient.exe` have different hashes because the built-in .NET Framework compiler emits non-deterministic metadata. A fresh final verification build produced `a6f712dcfe7ee3276415a9d99cadbbcb1d95bca46c88ecd6f47a34716090e0b0` from the same committed source. The installed accepted artifact is the `e226...` binary recorded above.

## PROVEN

### One-app employee boundary

- Employee-facing application is named `IWS` and launched by one `IWS.lnk` targeting `C:\Program Files\IWS\Client\IwsClient.exe`.
- No Edge app-mode, NetBird UI, NetBird tray, NetBird account, setup-key entry, IP entry, or tunnel controls are exposed.
- The hidden automatic service is `IWSPrivateTransport` at NetBird `v0.77.1`.
- Setup-key enrollment used a one-use file; the credential was consumed and removed from both OLECLANKY and the VM without being printed or logged.

### Dedicated Chromium boundary

- `IwsClient.exe` uses the pinned Fixed Version WebView2 Runtime under `C:\Program Files\IWS\Client\WebView2Fixed` and a dedicated persistent profile under `%LOCALAPPDATA%\IWS\WebView2`.
- The live WebView2 process tree contained seven Chromium processes. Every process image was the dedicated `WebView2Fixed\msedgewebview2.exe`, never system Edge or Evergreen WebView2.
- The Chromium network-service process was killed during the POC. It restarted with a new PID (`10680` to `10136`), process count returned to seven, and every replacement process remained under the covered fixed-runtime path.

### Firewall and overlay isolation

- The installed `IWS Client Boundary POC` group contains 39 outbound rules: 13 rules for each of `IwsClient.exe`, `IwsBoundaryProbe.exe`, and the dedicated `msedgewebview2.exe`.
- The only allowed tuple is TCP `100.83.246.85:443`.
- IPv4 address complements are `0.0.0.0-100.83.246.84` and `100.83.246.86-255.255.255.255`; same-address TCP ports `0-442` and `444-65535` are blocked separately.
- UDP and ICMP are blocked over the complete IPv4 partition. TCP, UDP, and ICMPv6 are blocked over `0:0:0:0:0:0:0:0-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff`.
- No block overlaps the allowed TCP tuple. No wildcard program, `Any` protocol block, `0.0.0.0/0` block, shared Edge path, Evergreen path, or transport executable appears in the rule set.
- WFP events were used only as evidence. Windows Firewall rules produced the behavioral result.
- With the real IWS shell active, Fixed Version WebView2 reached `100.83.246.85:443` and failed against OLECLANKY Tailscale, ww-devbox Tailscale, iws-prod Tailscale, representative RFC1918 destinations, unrelated NetBird peer `100.83.50.15`, and public `1.1.1.1:443`.
- Ordinary `powershell.exe`/Windows networking retained HTTP `200` Internet access while the IWS rules were active.
- NetBird management, signal, relays, and the approved peer path remained connected because transport processes are outside the shell rule group.

### Actual IWS functional proof

- The actual non-production IWS Portal rendered inside `IwsClient.exe` from `http://100.83.246.85:443/`.
- Same-origin JS, CSS, and locally vendored staging banner assets loaded.
- Actor selection selected `Renny` and persisted across process restart and Windows reboot.
- Build Wizard loaded live batch data without creating a production batch.
- Inventory loaded its dashboard and scanner/search UI.
- Native Back returned from Build to the Portal. Persistent native IWS Portal/Home returned to the web-owned module launcher.
- Passive evidence recorded multiple same-origin API HTTP `200` responses and Socket.IO/WebSocket creation.
- The dedicated profile retained six localStorage entries and a same-origin persistent POC cookie across relaunch and reboot.

### Reboot and lifecycle proof

- After Windows reboot, all 39 firewall rules, the service, and the local peer identity persisted.
- `IWSPrivateTransport` reconnected to management, signal, relays, and OLECLANKY before revocation.
- After normal employee sign-in, the installed shortcut launched the Portal with actor, localStorage, cookie, API, and WebSocket state intact.
- The shell-only uninstaller removed the shell, Fixed Runtime, shortcut, and all 39 rules while leaving the enrolled transport service and identity connected.
- Reinstall verified a 557-entry SHA-256 manifest, restored the flattened Fixed Runtime layout, recreated the exact 39-rule group, and installed one correct IWS shortcut.
- Physical testing caught and fixed orphaned-rule cleanup and nested-runtime-layout defects before checkpoint acceptance.

### Central revocation proof

- Dashboard deletion of `DEVMACHINE-IWS` / `100.83.132.143` removed the peer from OLECLANKY immediately.
- Windows transitioned to `NeedsLogin`; the service remained running but had no usable peer authorization.
- Relaunching the installed shortcut produced only an IWS-branded private-connection error, rendered no Portal, and created no new API/WebSocket evidence.
- The constrained WebView2 probe could no longer reach the formerly approved endpoint; it timed out after five seconds. All prohibited destinations remained blocked.
- No setup key appeared and no automatic reenrollment occurred. Ordinary Windows Internet access remained HTTP `200`.

## NOT PRODUCTION READY

- .NET Framework 4.8 and WinForms are disposable POC scaffolding, not a final Windows-framework decision.
- `IwsClient.exe` and POC scripts do not have production signing, release provenance, updater, rollback, or enterprise installer engineering.
- The 354.5 MiB Fixed Version Runtime needs a security-update and servicing policy.
- The staging endpoint uses a literal HTTP IP. Production trusted DNS/TLS and certificate lifecycle remain unimplemented.
- Passive CDP acceptance evidence and `IwsBoundaryProbe.exe` are test instrumentation, not production client features.
- Firewall rules are proven on this Windows 11 VM but still need supported-version policy, enterprise management interaction, upgrade migration, tamper resistance, and production threat review.
- OLECLANKY has a transient staging-only firewalld `/32` rule for the now-revoked peer; remove it when this staging environment is torn down.
- VM autologin, disabled UAC prompts, SSH, QEMU guest agent, and scheduled tasks were development-control mechanisms only and must never become IWS runtime dependencies.
- The POC does not yet implement admin-generated per-device installers, IWS bootstrap-token exchange, production signing, inventory/audit UI, or provisioning authority. That is the next architecture phase and was not started here.
- The central peer is intentionally revoked at checkpoint time. The preserved installed client is expected to show `IWS unavailable` until a future authorized provisioning flow creates a new identity.

## Verification suites

Final fresh verification passed:

- WebView2 probe build contract
- WebView2 probe source contract
- disjoint Windows Firewall contract
- IWS WebView2 shell source contract
- IWS shell lifecycle contract
- 50 Windows transport/bootstrap assertions
- 6 selective transport-bundle tests
- fresh clean-room WebView2 client and probe compilation

## Preservation exclusions

The repository and checkpoint archive must not contain setup keys, peer private identity, `%LOCALAPPDATA%` WebView profile, `C:\ProgramData\IWS\Transport`, logs, tokens, browser cache, VM credentials, SSH private keys, debug keystores, or production data.
