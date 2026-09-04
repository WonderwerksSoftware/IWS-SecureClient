# Windows WebView2 Boundary POC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Physically prove that a dedicated Fixed Version WebView2 process tree can reach only `100.83.246.85:443` while ordinary Windows applications retain normal networking, then conditionally build the actual IWS shell.

**Architecture:** A .NET Framework 4.8 WinForms probe and later `IwsClient.exe` use an IWS-private Fixed Version WebView2 runtime. Mutually exclusive Windows Firewall rules constrain the host executable and every observed fixed-runtime network executable; temporary WFP audit events prove that the real Chromium process is allowed or blocked by the OS rather than by WebView request filtering.

**Tech Stack:** Windows 11, .NET Framework 4.8 C# compiler, WinForms, Microsoft.Web.WebView2 `1.0.4191.47`, Fixed Version WebView2 Runtime `152.0.4191.53` x64, PowerShell NetSecurity, WFP audit events.

**Spec:** `docs/superpowers/specs/2026-09-02-windows-webview2-isolation-design.md`

## Global Constraints

- Preserve the enrolled `DEVMACHINE-IWS` peer and its `IWSPrivateTransport` service.
- Do not change NetBird policy, routes, forwarding, NAT, DNS, Tailscale, production, IWS Stack, Android, or iws-prod.
- Do not use Edge app-mode or the shared Evergreen WebView2 runtime.
- Install no NetBird UI, tray, account, or key workflow.
- Do not create any allow rule that overlaps a block rule.
- WebView filtering is defense in depth and cannot satisfy the physical boundary gate.
- Stop before the full shell unless the real Fixed Version WebView2 process tree passes the positive and negative boundary suite.
- Keep the existing `100.83.246.85:443` timeout as a separate unresolved positive failure until actual content loads.
- .NET Framework 4.8 and WinForms are disposable POC scaffolding, not a final framework selection.
- WFP audit records are evidence only; Windows Firewall rules must enforce the boundary.
- Re-enumerate the actual Fixed Version Chromium process tree across child-process restarts and require every network-capable process image to remain covered.
- Approved endpoint failure plus prohibited endpoint failure is not a boundary pass.
- Defer central revocation until final functional and isolation acceptance.

## Pinned inputs

- WebView2 SDK NuGet: `Microsoft.Web.WebView2` `1.0.4191.47`
- SDK URL: `https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/1.0.4191.47/microsoft.web.webview2.1.0.4191.47.nupkg`
- SDK SHA-256: `f492bbf547d0da329553b6727435b677579b1e9f91cc9e4a1ad029366d5f23d0`
- Fixed Runtime: `Microsoft.WebView2.FixedVersionRuntime.152.0.4191.53.x64.cab`
- Runtime URL: `https://msedge.sf.dl.delivery.mp.microsoft.com/filestreamingservice/files/f8ecb2c5-f486-4df5-994f-1eec63f1de23/Microsoft.WebView2.FixedVersionRuntime.152.0.4191.53.x64.cab`
- Runtime SHA-256: `f8f200b57d6a7a71d380f777f5c0ea0f71f520048add21e15737121de9ba4f68`
- VM compiler: `C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe`, compiler `4.8.9221.0`, C# 5.
- SDK assemblies: `lib/net462/Microsoft.Web.WebView2.Core.dll`, `lib/net462/Microsoft.Web.WebView2.WinForms.dll`, and `runtimes/win-x64/native/WebView2Loader.dll`.

---

### Task 1: Reproducible probe input and compile contract

**Files:**
- Create: `windows/webview2/pins.env`
- Create: `windows/webview2/build-probe.ps1`
- Create: `windows/webview2/tests/ProbeBuildContract.Tests.ps1`
- Create: `windows/webview2/README.md`

**Interfaces:**
- Consumes: the exact pinned URLs and SHA-256 values above.
- Produces: `.cleanroom/webview2/probe/IwsBoundaryProbe.exe`, the two managed WebView2 DLLs, `WebView2Loader.dll`, and extracted `WebView2Fixed`.

- [ ] **Step 1: Write the failing build-contract test**

Assert that the build script rejects a wrong SDK/runtime hash, compiles only with the pinned SDK files, emits no Edge or NetBird UI executable, and records the output hash manifest.

- [ ] **Step 2: Run the test and verify RED**

Run on the VM:

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File C:\Users\sysmi\iws-poc-src\windows\webview2\tests\ProbeBuildContract.Tests.ps1
```

Expected: fail because `build-probe.ps1` is absent.

- [ ] **Step 3: Implement the minimal pinned build script**

The script verifies both downloaded hashes, expands the CAB with `expand.exe -F:*`, extracts only the documented NuGet SDK files, and compiles:

```powershell
& $csc /nologo /target:winexe /platform:x64 /optimize+ `
  /reference:System.dll /reference:System.Core.dll `
  /reference:System.Drawing.dll /reference:System.Windows.Forms.dll `
  /reference:$coreDll /reference:$winFormsDll `
  /out:$probeExe $probeSource
```

It writes `BUNDLE-MANIFEST.sha256` and fails if the output contains `netbird-ui.exe`, `netbird.exe`, `msedge.exe`, an enrollment key, or any unapproved executable outside `WebView2Fixed`.

- [ ] **Step 4: Run the test and verify GREEN**

Expected: all build-contract assertions pass and both input hashes match.

- [ ] **Step 5: Commit**

```bash
git add windows/webview2
git commit -m "build(windows): pin WebView2 boundary probe"
```

### Task 2: Minimal real WebView2 boundary probe

**Files:**
- Create: `windows/webview2/IwsBoundaryProbe.cs`
- Create: `windows/webview2/tests/ProbeSourceContract.Tests.ps1`

**Interfaces:**
- Consumes: fixed runtime path, dedicated profile path, and target list supplied as command-line JSON.
- Produces: newline-delimited JSON events containing browser-process ID, child image paths, request target, WebView navigation result, and timestamps; it never contains credentials.

- [ ] **Step 1: Write the failing source-contract test**

Require `CoreWebView2Environment.CreateAsync(fixedRuntime, profilePath)`, `EnsureCoreWebView2Async`, `BrowserProcessId`, no `WebResourceRequested` cancellation in probe mode, and the exact target matrix:

```text
http://100.83.246.85:443/
http://100.116.25.100:443/
http://100.99.71.15:443/
http://100.127.228.103:443/
http://10.1.10.1:443/
http://172.16.0.1:443/
http://192.168.50.1:443/
http://100.83.50.15:443/
http://1.1.1.1:443/
```

- [ ] **Step 2: Run the test and verify RED**

Expected: fail because `IwsBoundaryProbe.cs` is absent.

- [ ] **Step 3: Implement the minimal probe**

Create one hidden WinForms window and one WebView2 control. Initialize the exact Fixed Version folder and disposable profile, then navigate to a `NavigateToString` page that issues sequential `fetch(url, {mode:'no-cors', cache:'no-store'})` calls. Emit start/completion messages through `window.chrome.webview.postMessage`. Do not register any request-blocking handler in probe mode.

- [ ] **Step 4: Run source tests and compile**

Expected: source contract passes, compilation exits zero, and `IwsBoundaryProbe.exe` has a recorded SHA-256.

- [ ] **Step 5: Commit**

```bash
git add windows/webview2/IwsBoundaryProbe.cs windows/webview2/tests/ProbeSourceContract.Tests.ps1
git commit -m "test(windows): add real WebView2 boundary probe"
```

### Task 3: Non-overlapping Windows Firewall rule generator

**Files:**
- Create: `windows/webview2/Set-IwsWebViewBoundary.ps1`
- Create: `windows/webview2/Remove-IwsWebViewBoundary.ps1`
- Create: `windows/webview2/tests/FirewallContract.Tests.ps1`

**Interfaces:**
- Consumes: absolute, hash-verified paths to `IwsBoundaryProbe.exe`, future `IwsClient.exe`, and the observed fixed-runtime `msedgewebview2.exe`.
- Produces: firewall group `IWS Client Boundary POC` and normalized readback JSON.

- [ ] **Step 1: Write pure range and overlap tests**

Require exactly these IPv4 partitions:

```text
0.0.0.0-100.83.246.84
100.83.246.85
100.83.246.86-255.255.255.255
```

Require the approved TCP partition `100.83.246.85:443` and denied same-address port partitions `0-442` and `444-65535`. Represent all IPv6 as `0:0:0:0:0:0:0:0-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff`; the tested provider rejects `::/0`. Reject `Any`, `0.0.0.0/0`, a wildcard program path, or any intersection with the approved TCP tuple.

- [ ] **Step 2: Run tests and verify RED**

Expected: fail because the rule generator is absent.

- [ ] **Step 3: Implement rule creation and transactional rollback**

Create separate program-scoped rules for TCP address complements, the approved-address TCP port complement, all IPv4 UDP/ICMPv4 partitions, and IPv6 TCP/UDP/ICMPv6. Add the single disjoint allow rule for TCP `100.83.246.85:443`. On any creation/readback mismatch, remove only the `IWS Client Boundary POC` group and fail.

- [ ] **Step 4: Verify normalized readback**

Read `Get-NetFirewallApplicationFilter`, `Get-NetFirewallAddressFilter`, and `Get-NetFirewallPortFilter`. Convert every rule to `(program, action, protocol, remote-address-set, remote-port-set)` and prove pairwise that no block tuple intersects the approved allow tuple.

- [ ] **Step 5: Run tests and verify GREEN**

Expected: all pure partition, forbidden-wildcard, rollback, and readback assertions pass.

- [ ] **Step 6: Commit**

```bash
git add windows/webview2/Set-IwsWebViewBoundary.ps1 windows/webview2/Remove-IwsWebViewBoundary.ps1 windows/webview2/tests/FirewallContract.Tests.ps1
git commit -m "feat(windows): add disjoint WebView2 firewall boundary"
```

### Task 4: Physical boundary gate on the real process tree

**Files:**
- Create: `windows/webview2/Invoke-IwsBoundaryAcceptance.ps1`
- Create locally ignored evidence: `.cleanroom/webview2/evidence/boundary-acceptance.json`

**Interfaces:**
- Consumes: built probe, fixed runtime, firewall scripts, existing `IWSPrivateTransport`, and target matrix.
- Produces: PASS/FAIL per target, WFP event IDs and application paths, firewall normalized readback, process tree, and ordinary-network control.

- [ ] **Step 1: Capture baseline and audit settings**

Record the current `Filtering Platform Connection` audit setting, enable success/failure auditing only for the bounded test, clear neither existing Security logs nor unrelated policy, and restore the original audit setting in `finally`.

- [ ] **Step 2: Prove actual runtime identity**

Launch the probe without firewall rules. Resolve the WebView browser PID and descendants with `Get-CimInstance Win32_Process`; require every WebView executable to resolve under the pinned `WebView2Fixed` folder. Correlate socket-owning PIDs with image paths. An unexpected network-capable image is a hard failure.

- [ ] **Step 3: Establish known-open controls**

Before applying IWS rules, verify ordinary Windows connectivity to public `1.1.1.1:443` and the previously reachable Tailscale/LAN controls. Record closed controls as coverage-by-filter/readback, not false behavioral passes.

- [ ] **Step 4: Apply and read back the disjoint rules**

Run the rule generator elevated, verify every executable hash/path, then compare the normalized rule union and pairwise intersections to the exact spec.

- [ ] **Step 5: Run the real WebView2 target matrix**

For each target, require a WFP `5156` allow or `5157` block event whose application path is the fixed runtime `msedgewebview2.exe` (or another predeclared fixed-runtime child). The approved endpoint additionally requires HTTP status/content from the actual staging origin; a firewall allow event plus timeout is not a positive pass.

- [ ] **Step 6: Verify ordinary networking and transport separation**

From `powershell.exe`, require normal public networking. Require `IWSPrivateTransport` to remain Running with Management, Signal, relays, and OLECLANKY peer connected. Confirm no firewall application filter references `iws-transport.exe`, system Edge, or shared Evergreen WebView2.

- [ ] **Step 7: Apply the hard gate**

If all actual-WebView targets and controls pass, commit the evidence schema/script and proceed to Task 5. If Windows Firewall cannot produce a complete, disjoint, process-specific boundary, remove the POC rule group, preserve enrollment, stop, and propose the smallest WFP/AppContainer implementation. If the approved endpoint still times out, report boundary-negative evidence separately but do not call the boundary or client fully passing and do not start Task 5.

- [ ] **Step 8: Commit only reusable probe code**

```bash
git add windows/webview2/Invoke-IwsBoundaryAcceptance.ps1
git commit -m "test(windows): automate physical WebView2 boundary acceptance"
```

### Task 5: Conditional full IWS WinForms shell

**Gate:** Run only after Task 4 physically passes, including real content from `100.83.246.85:443`.

**Files:**
- Create: `windows/webview2/IwsClient.cs`
- Create: `windows/webview2/tests/IwsClientSourceContract.Tests.ps1`
- Modify: `windows/webview2/build-probe.ps1`

**Interfaces:**
- Consumes: fixed runtime, persistent profile, exact portal origin, connected transport.
- Produces: `IwsClient.exe` with native Back/Home and defense-in-depth origin enforcement.

- [ ] **Step 1: Write failing shell contract tests**

Require the exact portal origin, persistent profile path, Back/Home controls, `NavigationStarting`, `WebResourceRequested`, `NewWindowRequested`, `DownloadStarting`, `PermissionRequested`, external-scheme denial, disabled devtools/context menu/autofill/password storage, and no module-specific native routing.

- [ ] **Step 2: Verify RED**

Expected: fail because `IwsClient.cs` is absent.

- [ ] **Step 3: Implement the minimal shell**

Create an IWS-branded WinForms window, start/verify `IWSPrivateTransport`, initialize the fixed runtime and persistent profile, attach all origin/security handlers before navigation, and navigate only to `http://100.83.246.85:443/`.

- [ ] **Step 4: Compile and verify GREEN**

Expected: source contracts and build pass; WebView2 runtime processes remain under the same OS rule group.

- [ ] **Step 5: Commit**

```bash
git add windows/webview2
git commit -m "feat(windows): add isolated IWS WebView2 shell"
```

### Task 6: Conditional installer, functional acceptance, reboot, and revocation

**Gate:** Run only after Task 5 and repeat boundary acceptance pass.

**Files:**
- Modify: `windows/Install-IwsClientPoc.ps1`
- Modify: `windows/Remove-IwsClientPoc.ps1`
- Modify: `windows/build-bundle.sh`
- Modify: `windows/tests/Invoke-WindowsPocTests.ps1`
- Create: `windows/webview2/Invoke-IwsShellAcceptance.ps1`

**Interfaces:**
- Consumes: shell, fixed runtime, transport identity, firewall group, staging portal.
- Produces: one installed IWS application and final PASS/PARTIAL/FAIL evidence.

- [ ] **Step 1: Write failing installer/uninstaller tests**

Require transactional firewall creation/removal, Fixed Runtime and shell hash verification, one `IWS` shortcut, no Edge app-mode, no NetBird UI/tray, identity preservation by default, and fail-closed rollback.

- [ ] **Step 2: Implement minimal lifecycle changes and verify tests**

Installer reuses the current peer and applies shell rules; uninstaller removes shell/rules while preserving identity unless explicitly told otherwise.

- [ ] **Step 3: Physically test actual IWS**

Verify Portal assets, actor selection and persistence, Build, Inventory, same-origin `/api`, Socket.IO/WebSocket, cookies/localStorage, native Back/Home, and relaunch state. Re-run the complete OS boundary suite while the portal is active.

- [ ] **Step 4: Reboot and re-verify**

Reboot the disposable VM, require automatic hidden transport reconnection, persistent firewall rules, shell launch, session continuity, functional suite, and negative isolation suite.

- [ ] **Step 5: Centrally revoke last**

After user/dashboard deletion of `DEVMACHINE-IWS`, require peer disconnection and approved endpoint failure without any credential or re-enrollment attempt. Ordinary Windows networking must remain functional.

- [ ] **Step 6: Final verification and commit**

Run all PowerShell, build, bundle, secret-scan, physical functional, reboot, isolation, and revocation checks. Commit only source/docs/tests and report artifact hashes; push nothing.
