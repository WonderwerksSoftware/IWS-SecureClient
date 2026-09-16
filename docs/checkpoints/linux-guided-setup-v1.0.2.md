# Linux guided setup 1.0.2

Release `secure-client-v1.0.2` changes Linux packaging and setup only. Windows
remains client version 1.0.1 and Android remains version code 3 / version name
1.0.0.

## Package lifecycle

DEB `postinst` and RPM `%post` run only the fixed offline preparation verb:

```text
/usr/bin/python3 -I /usr/lib/iws-client/runtime.py prepare
```

Preparation validates fixed platform and CA inputs, installs local trust, and
moves incoming manifest/key material into mode-0700/0600 root-owned staging. It
does not contact management, enroll, replace identity, show UI, start, stop, or
restart the IWS service. Expired or conflicting incoming metadata therefore
does not make ordinary package configuration replace the registered client.

The `iws` desktop launcher runs a small unprivileged GTK3 controller. Existing
configured launches still enter the accepted root-created network/mount
namespace, drop privileges, and start the same WebKit shell. Setup changes use
the fixed `/usr/lib/iws-client/iws-setup-helper` through ordinary polkit admin
authorization. The helper accepts only `enroll`, `repair`, or `replace`, then
re-reads protected state instead of accepting device IDs, paths, hostnames, or
keys from the UI.

Explicit replacement first creates and verifies a root-private recovery copy.
Failures before an enrollment attempt restore the previous managed state.
After an enrollment attempt, the new transport state and old recovery archive
are retained. A successful new device record is saved and the mutable key is
consumed before the later service settings-lock restart, so that downstream
failure is repairable without another key.

Package removal stops and disables only `iws-client.service`. It does not remove
enrollment/recovery state or touch stock NetBird, Tailscale, routes, DNS, or
firewall state.

## Build and verification

The provisioner supplies the pinned 0.77.1 Linux transport through
`IWS_LINUX_TRANSPORT_FILE`; no live key is needed for repository smoke fixtures.

```sh
npm test
python3 -m unittest linux.tests.test_bootstrap linux.tests.test_setup linux.tests.test_shell_policy
IWS_TEST_LINUX_TRANSPORT_FILE=/protected/path/iws-transport node linux/tests/package-smoke.mjs
```

The last command emits inert DEB and RPM fixtures and inspects their payload,
version, and lifecycle scriptlets. Do not install those fixtures.

## Deployment boundary

Building, tagging, publishing, installing, authenticating through polkit, and
choosing replacement on a physical host remain separate authorized steps. No
package generated during repository verification is a deployment approval.
