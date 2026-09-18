# Android DNS recovery maintenance — 2026-09-18

## Authorization and delivered result

Renny explicitly approved implementing the private Android recovery fix and updating the shop tablet. This supersedes the freeze only for this maintenance scope. No application server was exposed publicly, no router forwarding was added, and production services, generator pins, other enrollments, and deferred acceptance remain unchanged.

Installed IWS 1.0.1/versionCode 4 in place on TB305FU at 14:29 CDT. Package `com.impactwiring.iwsconnectpoc`, existing UID 10255, enrollment, and Portal user Mike were retained. Actual tablet screenshot showed the Portal and IWS Connected. No data clearing or new enrollment occurred.

## Repair

- Native Go transport DNS uses the current physical network's IPv4 DNS servers through protected, network-bound sockets. It no longer depends on Android's app-default private VPN DNS. Empty/invalid physical DNS fails closed; there is no hardcoded/public fallback.
- Android observes non-VPN physical networks and refreshes DNS/availability on changes. WebView remains on private DNS and validated private HTTPS. No process-wide physical-network binding or VPN bypass was added.
- Explicit Retry queues one serialized replacement after native engine completion, with a bounded timeout. Native cancellation latches a Stop received before Run initialization. Healthy ordinary reopen preserves the existing engine.
- The upstream NetBird commit remains pinned. A maintained source patch contains the Go changes and focused tests. Android version changed independently of the frozen Linux release.

## Verification and limits

- Native DNS race tests: five tests passed, covering physical DNS replacement, no-network behavior, invalid inputs, protected-socket failure, and both direct ResolveUDPAddr and normal dialer lookup paths. Two native cancellation race tests passed; the stop-before-start regression was observed before correction.
- Seven new Java selection/lifecycle tests passed. Full Gradle test/lint/release build passed: 122 debug unit tests, zero failures/errors; no release unit tests were executed. APK is non-debuggable with empty bootstrap key and hostname.
- Packaging tests: 33 passed, three expected Windows-host skips. Independent static review found no material defect. Secret scan and whitespace checks passed.
- Signed repair and rollback certificates match the originally installed certificate, SHA256 `e8d7f92291306d9376d17b15620fc4442cfe8e65aaa47cc912ff42ed6edd7b8c`.
- Device logs identify `v0.77.1-iws-dns-recovery`; relay, Signal, and Management connected. A natural physical link-property change was followed by Signal/Management reconnection. No native panic, fatal startup exception, or library-load failure was observed.
- Actual VPN: private DNS 100.83.75.124; default IPv4 capture; IPv6 unreachable; IWS UID and its Android SDK-sandbox UID only; bypassable=false; underlying physical network 112. This is configuration and Portal evidence, not a new exhaustive packet-isolation campaign.
- No forced Wi-Fi loss, reboot, service kill, production-data operation, or deferred acceptance test was performed. Retry serialization/network replacement have focused test evidence, not a forced physical-tablet outage demonstration. Long-term reliability remains to be observed. This artifact supports ARM64 and physical IPv4 DNS; IPv6-only underlays were not validated.

## Provenance and rollback

Source branch: `codex/android-dns-recovery`, based on `b7de260606e99502928e001a2c03287d2de22c50`, in ww-devbox `/home/wcfox/dev/iws-secureclient-android-recovery`. Native base: `79a06720b684768b421f0a54f3bb14f22704994f`, with `third_party/netbird/patches/android-transport-dns.patch` applied.

SHA256:

| Artifact | SHA256 |
| --- | --- |
| Patched ARM64 AAR | `a860a3a763e82dfd91f7ae428b1081e040f9e34ed49673da58d3e2e62d27aff6` |
| Repair unsigned APK | `d387a9080d031daee5e896c46bf835f183fee4cfeab175ca8f5d2335c3a469d4` |
| Repair signed APK | `4d57db3d7858e0eb4870eabab20aea523a81649b7fd14d0f68db413a0207362b` |
| Rollback unsigned APK | `00aed2590d6b518a78c64d768925310964e6fa383799853bda7f7e968b7b3bb7` |
| Rollback signed APK | `cab3ad5729de78c33c816f8948ba8228e0955ba01895560734d41f488fcac431` |

Both signed APKs are preserved under production `/var/lib/iws-provisioning/maintenance/20260918-android-dns` (directory 0700, files 0600). Signing used the existing protected keystore in place; no signing secret left production. Working copies and the exact original installed APK are in Cometforge `/tmp/iws-android-maintenance-20260918` (0700). Original APK also exists on ww-devbox at `/tmp/iws-original-installed-20260918.apk` (0600).

Rollback is accepted base Java/native behavior rebuilt with versionCode 4 and versionName 1.0.1-rollback. This permits an ordinary same-certificate `adb install -r rollback-signed.apk` without downgrade, uninstall, or data clearing. Rollback is prepared and signature-verified, not installed or device-tested.

## Reproduction and publication boundary

Native build used pinned Go 1.25.12, gomobile, JDK11, NDK 28.2.13676358 and `android/arm64`, Android API26. Build tools were downloaded into the isolated cache with pinned hashes checked; no system software was installed. The maintained `build-aar.sh` applies the patch and supports `IWS_GOMOBILE_TARGET=android/arm64`. Its historical AAR comparison pin remains unchanged; a repaired artifact is intentionally different and must not be substituted silently into the frozen generator.

The patched AAR was copied into `android/app/libs/netbird-v0.77.1.aar` and its hash checked. Gradle used the established JDK21/SDK, `IWS_ANDROID_UNSIGNED_MAINTENANCE=1`, and these properties: expected overlay 100.83.0.0/16, endpoint 100.83.75.124:443, Portal https://portal.iws.internal/, Management https://api.netbird.io:443. `test lint assembleRelease` passed. Unsigned maintenance mode rejects bootstrap or signer inputs; existing signed provisioning behavior is retained.

Build cache/logs and baseline rollback source: ww-devbox worktree `.cleanroom/android-recovery`. The native patch and source must accompany the artifact. Source PR publication is separate from generator promotion: no merge, release overwrite, release-pin update, or automatic fleet rollout is implied by this tablet repair.
