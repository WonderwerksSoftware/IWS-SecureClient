# Security boundary

The Android client must remain fail closed. Its embedded overlay must not route,
discover, or bridge Tailscale peers, CGNAT addresses, LAN/private networks,
unrelated NetBird peers, or arbitrary public destinations.

Required invariants:

- only `com.impactwiring.iwsconnectpoc` is allowed into the Android VPN;
- the IWS UID's IPv4 traffic is captured with `0.0.0.0/0`;
- `allowBypass()` is never used;
- NetBird transport sockets escape recursion only through
  `VpnService.protect()`;
- NetBird client routes, server routes, DNS, IPv6, SSH, forwarding, NAT, exit
  node behavior, and LAN advertisement remain disabled;
- the assigned native IPv4 network must exactly equal the compiled overlay;
- application traffic is allowed only to the compiled endpoint and port;
- setup keys, peer identity, logs with credentials, and keystores are never
  committed or included in source archives.

The pinned third-party closure includes MPL-2.0 weak-copyleft components. See
`third_party/netbird/NOTICE.md`; this repository does not characterize the
entire dependency closure as permissively licensed.

The Windows client must preserve the accepted dedicated `IwsClient.exe`, Fixed
Version WebView2 process tree, hidden `IWSPrivateTransport` service, and
non-overlapping 39-rule Windows Firewall complement. Only
`100.83.246.85:443` is admitted for the IWS shell; Tailscale, RFC1918, unrelated
overlay peers, and public destinations remain blocked without affecting normal
Windows applications.

Generated artifacts may contain one 24-hour, one-use setup key. PATs,
administrative credentials, reusable enrollment keys, peer identity, runtime
state, logs, WebView profiles, and signing keys are prohibited from this
repository. The platform packager accepts private paths through a mode-`0600`
request file and must never print credential contents.
