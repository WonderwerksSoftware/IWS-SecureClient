# Android isolation contract

## Proven

- IWS-only per-application VPN scoping with `0.0.0.0/0` IPv4 capture.
- No `allowBypass()`; non-IWS Android applications retain ordinary networking.
- NetBird sockets function because the adapter explicitly calls
  `VpnService.protect()`.
- Exact native overlay validation: `100.83.50.15/16` derives
  `100.83.0.0/16`.
- Application traffic reaches only `100.83.246.85:443`.
- Tailscale peers, RFC1918 LAN ranges, unrelated NetBird peers, and arbitrary
  public endpoints fail from the IWS application/VPN context.

## Still POC / not production ready

Production policy lifecycle, managed enrollment, revocation, release signing,
updates, support telemetry, disaster recovery, and formal device-fleet controls
are not implemented by this checkpoint.
