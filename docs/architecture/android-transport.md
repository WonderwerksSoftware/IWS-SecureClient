# Android transport architecture

The application owns its `VpnService` and embeds the pinned NetBird gomobile
client. Android admits only the IWS package into the VPN and captures that UID's
entire IPv4 space. NetBird management, signal, relay, and WireGuard sockets are
explicitly passed through `VpnService.protect()` to avoid recursive capture.

The TUN adapter derives the peer network from the assigned native IPv4 CIDR and
requires an exact match to `100.83.0.0/16`. The full-IWS-UID default route is an
OS capture boundary, not authority to forward arbitrary traffic: the route
policy admits only `100.83.246.85:443`, while NetBird routes, DNS, and IPv6 are
disabled. No machine is configured as a gateway between overlays.
