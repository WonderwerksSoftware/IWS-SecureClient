# Linux V1: bounded pre-implementation security gate

## Android closed

PR #2 merged normally as `e315f52d90921d09a9568590fadc1fc286047153`.
Accepted application source remains `72f019b11ff74a243760e0e572b16ee22d324c0e`;
PR head `340fdef3e018aa401a39ffb41cda24728db3dcd4` adds closure documentation only.
No post-candidate exploratory application diff existed. No Android tests or
reviews were rerun. Forced full-service-restart Socket.IO is explicitly deferred:
https://github.com/WonderwerksSoftware/IWS-SecureClient/issues/3

## Read-only observations, 2026-09-11

| Target | Observed OS | Native trust tool | Existing transport |
| --- | --- | --- | --- |
| ww-devbox | Ubuntu 24.04.5 LTS | `/usr/sbin/update-ca-certificates` | Tailscale active; NetBird service inactive |
| OLECLANKY | Nobara 44 | `/usr/bin/update-ca-trust` | Tailscale and NetBird active |

OLECLANKY has `wt0` address `100.83.246.85/16` and a connected
`100.83.0.0/16` route on that interface. Its installed NetBird reports **0.78.1**,
not the client checkpoint's 0.77.1. No downgrade or identity change was made.
Its service file is `/etc/systemd/system/netbird.service`.
`sudo -n true` reports that a password is required; no credentials were requested
or bypassed. Chromium and `rpmbuild` are installed. Ubuntu has native Chrome and
`dpkg-deb`; an additional Snap Chromium installation is not an approved packaging
target. Native CA directories exist on both hosts; no certificate was installed.

## Reusable boundary versus missing Linux mechanism

Inspected the Windows hidden-service installer, dedicated WebView2 installer,
firewall module, canonical package dispatcher, and Stack's existing package
request/response adapter and Prisma platform enum (main `395ff6f`).

The same device/generation/key/expiry/revoke/re-provision lifecycle and the
file-based packager contract can be extended without a new provisioning design.
There is currently no Linux shell, Linux packager, or Linux application isolation
implementation in this checkpoint. Windows relies on an IWS-specific Chromium
executable path and Windows program-scoped firewall rules; copying a launcher
for the ordinary Linux browser does not supply that security boundary.

The pinned NetBird source at
`79a06720b684768b421f0a54f3bb14f22704994f`,
`client/firewall/nftables/manager_linux.go`, does support a distinct table name
through `NB_NFTABLES_TABLE`. This is evidence of a naming mechanism, **not proof**
that a second client with the same overlay prefix safely coexists with the
staging server or that the application is isolated. No coexistence experiment
was performed and no claim of impossibility is made.

## Gate requiring direction

Authority: **SECURITY / PROJECT-INVARIANT** — preserve application isolation,
ordinary unrelated networking, and OLECLANKY's existing staging identity.

The request limits distro adapters to packaging, trust, and service integration
and prohibits networking redesign. A shared Linux-specific application/network
isolation implementation is also necessary; it is not present to reuse. Confirm
that this additional OS enforcement layer is within the Linux authorization
before changing routing/firewall/process boundaries on these dual-purpose hosts.
It must retain the pinned transport and existing CA and must not replace the
staging server identity. This is a scope decision, not a request to reopen
Android, Windows, or the central provisioning lifecycle.

No Linux implementation, installation, enrollment, revocation, reboot, or
acceptance test has occurred. No provisioner schema/UI change was made.
Windows/Android regression reruns are not yet due because no Linux code changed.
No production services, credentials, policy, business data, or host networking
were mutated. V1 is **not** labeled closed across four platforms. The V2
transition remains conditional on Linux acceptance and the V1 freeze.
