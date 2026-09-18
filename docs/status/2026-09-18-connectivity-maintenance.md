# Connectivity maintenance — 2026-09-18

Renny authorized enabling the NUC rekey workaround and patching the existing Linux clients, then reported recurring tablet trouble. This authority supersedes the prior read-only restriction only for this maintenance. No release pins, published artifacts, working enrollments, Ben's settings, production data, or deferred acceptance were changed.

## NUC: mitigation enabled, long-term result pending

- Installed wpa_supplicant supports per-network `wpa_deny_ptk0_rekey`. Changed live network 0 from 0 to 2 with wpa_cli and verified readback 2.
- It replaces problematic PTK0 renewals with a fresh association/handshake; a brief interruption at those renewals remains possible. Correlation with the earlier outage makes this a mitigation trial, not a proven permanent hardware/software diagnosis.
- Netplan's networkd backend does not expose this option. Added root-owned `/usr/local/sbin/iws-wifi-rekey-config` and `/etc/systemd/system/netplan-wpa-wlp2s0.service.d/50-iws-rekey.conf`. The helper updates the generated supplicant file before service starts or reloads; it expects exactly one configured network and never prints credentials. It is idempotent.
- Protected original generated config: `/root/iws-wifi-rekey-backup-20260918/wpa-wlp2s0.conf`.
- Applied helper to current generated file, reloaded systemd unit definitions, set live option without Wi-Fi restart/reconfigure, and verified the loaded start/reload hooks. Wi-Fi service remained active; a subsequent GTK renewal was logged successfully. A PTK renewal after this change has not yet been observed.
- Power management remains unchanged to keep this a single-variable trial. No production application service restart, driver change, reboot, router change, or forced disconnect.
- Production availability was verified through both patched clients' trusted private HTTPS probes. Direct host probes on the NUC lacked private DNS/CA setup and are not evidence of client failure; no host DNS/trust changes were made.

Rollback: set network 0's option back to 0 with wpa_cli; restore the backed-up generated config; remove only the new helper/drop-in and run systemctl daemon-reload. No Wi-Fi restart is required for the live setting rollback. The unchanged Netplan YAML remains the source of SSID/authentication.

## Cometforge and OLECLANKY: Linux resolver repair installed

Canonical source worktree on ww-devbox: `/home/wcfox/dev/iws-secureclient-dns-repair`, branch `codex/linux-resolver-recovery`, based on accepted upstream `b7de260606e99502928e001a2c03287d2de22c50`.

Changed `linux/runtime.py`; added `linux/tests/test_resolver_replacement.py`.

A bind mount on a host-owned resolver entry can disappear when the host atomically replaces that entry. The runtime now mounts a private tmpfs /etc in its existing private mount namespace, retains unrelated configuration via bind mounts/symlinks, and creates its own fixed resolver/nsswitch entries (plus browser hosts). Source directory descriptors and --no-canonicalize keep bind sources on the original /etc after it is covered. Browser and transport DNS remain separate; no host routes, DNS, firewall, or service-socket access were relaxed.

Verification:
- Four actual-runtime disposable mount-namespace cases failed on the original code and pass on the repair: transport/browser, regular resolver/symlink, with external replacement of resolver, symlink target and nsswitch.
- Existing focused Linux tests: 23 passed. Packaging: 33 passed, 3 expected Windows-host skips. Secret scan and diff check passed.
- Both installed runtimes matched the accepted baseline before replacement: SHA256 `317d1566a1771e293d3ad2114cde51198a138d97499b597e433d91546f573129`.
- Both repaired runtime files: SHA256 `51aeb4e84ffda8c490690b58f7c2682212708cdb674492c894d423b2d5789b9d`.
- On each host, protected backup: `/var/lib/iws-client-maintenance/20260918-dns-repair/runtime.py`. Replaced only `/usr/lib/iws-client/runtime.py` and restarted only `iws-client.service`.
- Enrollment record bytes and host resolver bytes unchanged across deployment.
- Both actual running transports have `iws-private-etc` mounted and successfully resolve relay.netbird.io.
- Fresh browser-equivalent UID1000 sessions on both hosts resolve portal.iws.internal to 100.83.75.124 and obtain validated HTTPS 200 from /api/health and Portal. Public HTTPS egress remains blocked; host D-Bus/resolved sockets remain masked.
- These are focused regression/connectivity checks, not repeated employee-flow acceptance or deferred reboot testing.

Rollback per host: restore the backed-up runtime and restart only iws-client.service. Browser windows already open in an older namespace need an ordinary close/reopen; no claim is made that existing process namespaces changed.

This is an installed maintenance hotfix, not a newly versioned package. Published releases and production installer-generation pins remain unchanged. Normal source PR publication is tracked below.

## Shop tablet: separate DNS recovery defect observed; session restored

- Connected through user-supplied wireless-debug address 10.1.10.199:35621. TB305FU; installed IWS 1.0.0/code3.
- While IWS native logs repeatedly reported relay.netbird.io lookup failures and signal not ready, ordinary ADB-shell Wi-Fi DNS resolved the same relay and a ping succeeded.
- Active app VPN used private resolver 100.83.75.124 and only IWS UIDs; physical Wi-Fi had public upstream DNS and was validated. The VPN predated the current Wi-Fi network.
- Exact pinned NetBird commit 79a06720b684768b421f0a54f3bb14f22704994f: client/net/dialer_init_android.go attaches socket protection, but client/net/dialer.go leaves the standard resolver unchanged. IWS starts RunWithoutLogin with an empty DNSList and has no underlay-specific lookup implementation. This supports a separate transport-DNS recovery defect; packet-level attribution and a permanent Android correction remain outstanding.
- Reopened only IWS, preserving app data/enrollment. At about 13:43 CDT relay and signal connected. Physical screenshot showed Portal, IWS Connected, and retained Mike identity. No production workflow/data action was performed.
- Android source, APK, signing configuration and release pins were not changed. Reopening restored this session; it does not remove the recurrence risk.

## Source publication

Independent read-only review found no material issues. Unrelated top-level /etc entries are captured at namespace creation; later additions or atomic replacements may require an ordinary affected-process restart.

Source is committed on codex/linux-resolver-recovery for normal PR review. No merge, new package release, or generator promotion is claimed.
