# ADR 0001: Embed NetBird behind an IWS-owned Android VpnService

Status: accepted for the Android POC checkpoint.

The IWS application owns VPN permission and lifecycle. It embeds only NetBird's
BSD-licensed gomobile client path and uses an independently written TUN adapter.
The stock NetBird Android application is neither installed nor a runtime
dependency. This preserves a one-app employee experience and permits an
OS-enforced per-UID isolation boundary.

This decision does not approve production enrollment, routing, release signing,
or deployment.
