# Windows IWS WebView2 Isolation POC Design

## Status and scope

This design replaces only the rejected Edge app-mode shell. The enrolled Windows peer `DEVMACHINE-IWS` (`100.83.132.143/16`) and the hidden `IWSPrivateTransport` service remain intact. NetBird policy, production, IWS Stack, Android, and the disposable OLECLANKY staging service are out of scope.

The work is a falsification POC. The first deliverable is a minimal real WebView2 process tree plus an OS-enforced network boundary. Full portal-shell work begins only after that boundary passes physically.

## Architecture

`IwsClient.exe` is a dedicated .NET Framework WinForms application embedding Microsoft WebView2. .NET Framework 4.8 and WinForms are disposable POC scaffolding; this experiment selects the isolation and Chromium boundary, not the final Windows application framework. It uses a pinned x64 Fixed Version WebView2 Runtime stored under `C:\Program Files\IWS\Client\WebView2Fixed`. This gives IWS a unique Chromium executable path instead of the shared Evergreen WebView2 or Microsoft Edge path.

The hidden transport remains a separate Windows service:

- service: `IWSPrivateTransport`
- executable: `C:\Program Files\IWS\Transport\iws-transport.exe`
- state: `C:\ProgramData\IWS\Transport`
- daemon pipe: `npipe://iws-private-transport`

No shell firewall rule applies to the transport service. Its management, signal, relay, and WireGuard sockets must remain functional. The shell reaches IWS only at `http://100.83.246.85:443/` over the already-enrolled NetBird interface.

The WebView profile is dedicated and persistent at `C:\Users\<employee>\AppData\Local\IWS\WebView2`. It retains the IWS session, cookies, cache, and localStorage without sharing an Edge or unrelated WebView2 profile.

## OS-level boundary falsification

Before building the full shell, a minimal `IwsBoundaryProbe.exe` will launch the same pinned Fixed Version runtime and create a real WebView2 controller. It will expose the runtime child-process IDs and executable paths and attempt bounded HTTP/WebSocket requests through Chromium. Enumeration repeats across Chromium child-process exits and restarts; every process capable of originating application traffic must remain under a covered fixed-runtime executable path. The probe is a disposable test artifact and is not installed as the employee launcher.

The firewall design uses only mutually exclusive sets. It must never combine an allow for the approved endpoint with a block that also contains that endpoint.

For every network-capable executable in the observed IWS Fixed Version WebView2 process tree, initially expected to be the runtime's `msedgewebview2.exe`, the `IWS Client Boundary POC` rule group contains:

1. Allow TCP `100.83.246.85`, remote port `443`.
2. Block IPv4 remote range `0.0.0.0-100.83.246.84` for TCP, all remote ports.
3. Block IPv4 remote range `100.83.246.86-255.255.255.255` for TCP, all remote ports.
4. Block TCP to `100.83.246.85`, remote ports `0-442` and `444-65535`.
5. Block UDP to `0.0.0.0-100.83.246.84`, `100.83.246.85`, and `100.83.246.86-255.255.255.255`, all remote ports.
6. Block ICMPv4 to the same three non-overlapping address sets.
7. Block the complete IPv6 range `0:0:0:0:0:0:0:0-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff` for TCP, UDP, and ICMPv6. The tested Windows 11 NetSecurity provider rejects the equivalent `::/0` spelling for these program rules.

The TCP allow and TCP deny sets are disjoint by both destination address and port. The UDP and ICMP rules cannot overlap the TCP allow because they select different protocols. No `Any` block is permitted.

Rules also apply to `IwsClient.exe` and `IwsBoundaryProbe.exe` so a host-side socket cannot bypass Chromium's restricted runtime. Installation fails closed if the fixed runtime path, executable hashes, firewall filters, or process-image inventory differ from the pinned manifest.

The physical boundary suite requires all three sides simultaneously. Approved-endpoint failure plus prohibited-endpoint failure is not a pass:

- WebView2 to `100.83.246.85:443`: pass.
- WebView2 to `100.116.25.100`, `100.99.71.15`, and `100.127.228.103`: fail.
- WebView2 to representative `10.0.0.0/8`, `172.16.0.0/12`, and `192.168.0.0/16` targets: fail.
- WebView2 to unrelated NetBird peer `100.83.50.15`: fail.
- WebView2 to public `1.1.1.1:443`: fail.
- A normal non-IWS Windows process to ordinary Internet: pass.
- `IWSPrivateTransport` management, signal, relay, and peer connection: remain connected.

Known-open prohibited controls are tested before and after rules so a timeout is attributable to the IWS program boundary rather than an already-closed service. The installed filters are read back through `Get-NetFirewallRule`, `Get-NetFirewallApplicationFilter`, `Get-NetFirewallAddressFilter`, and `Get-NetFirewallPortFilter`; acceptance compares their normalized union to the exact complement above. WFP audit events corroborate the observed allow/block decisions but are evidence only; the Windows Firewall rules are the enforcement mechanism.

If the actual WebView2 network process does not use the unique fixed-runtime executable path, if any socket-owning child falls outside the rules, if Windows Firewall normalizes the ranges into an overlapping set, or if a prohibited known-open control remains reachable, this design fails. Work stops before the portal shell and moves to a separately approved WFP/AppContainer identity design.

## WebView security boundary

WebView event filtering is defense in depth and is not accepted as the OS boundary. The full shell permits only the exact origin `http://100.83.246.85:443` and uses current WebView2 APIs to:

- cancel off-origin `NavigationStarting` events;
- filter and cancel off-origin `WebResourceRequested` events;
- cancel `NewWindowRequested`, downloads, permission requests, and external URI schemes;
- disable browser chrome, developer tools, context menus, autofill, password storage, and arbitrary host-object exposure;
- keep JavaScript enabled for the tracked IWS frontend;
- allow same-origin HTTP, WebSocket/Socket.IO, cookies, localStorage, and session behavior.

The persistent native controls are Back and IWS Portal/Home. Back calls WebView history only when `CanGoBack`; Home navigates to the exact portal origin. Portal module discovery remains web-owned, so native code does not assume only Build and Inventory exist.

## Packaging and lifecycle

The POC pins and hashes the WebView2 SDK package, Fixed Version Runtime archive, compiled host, transport runtime, Wintun, and every installed script. Large runtime binaries remain ignored by Git and are built into a local disposable bundle.

The installer performs this order:

1. Verify all hashes and publisher signatures.
2. Install the dedicated host and Fixed Version runtime with restricted ACLs.
3. Create and read back the non-overlapping firewall group.
4. Verify the existing `IWSPrivateTransport` identity and connected state without reenrollment.
5. Install one Start Menu entry named `IWS`; install no NetBird UI, tray, account workflow, or Edge app-mode shortcut.

The uninstaller removes the IWS shell, fixed runtime, profile when explicitly requested, shortcut, and only the `IWS Client Boundary POC` firewall group. It does not remove the NetBird peer identity unless explicitly requested. Failed installation removes any partially installed shell rules and files but preserves the already-enrolled transport identity.

## Functional acceptance

After the OS boundary passes, the installed shell must physically demonstrate:

- automatic verification/start of the hidden transport;
- actual IWS Portal rendering with same-origin JS/CSS/assets;
- actor selection and persisted session;
- Portal, Build, and Inventory navigation;
- same-origin `/api` requests;
- Socket.IO/WebSocket connectivity;
- native Back and persistent IWS Portal/Home controls;
- cookies and localStorage across process restart;
- service, identity, firewall, and shell recovery after Windows reboot;
- no employee-visible NetBird process, UI, tray, account, key, IP, or topology workflow.

After all positive and negative acceptance passes, the final test centrally deletes `DEVMACHINE-IWS`. The client must lose the approved endpoint while keeping the credential absent. Re-enrollment is not attempted during revocation proof.

## Failure handling

Every security-sensitive failure is fail closed and shown only as an IWS-branded error. No diagnostic includes enrollment material, peer private identity, tokens, or unrestricted URLs. The POC does not add routes, advertise networks, enable exit-node behavior, enable NetBird SSH, bridge overlays, enable forwarding/NAT, or change central policy.
