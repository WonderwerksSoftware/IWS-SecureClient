# Toolchain evidence

## Embedded AAR

- Go 1.25.12
- Official Linux amd64 archive SHA-256
  `234828b7a89e0e303d2556310ee549fbcf253d28de937bac3da13d6294262ac1`
- `golang.org/x/mobile` and both `gomobile`/`gobind` at
  `v0.0.0-20251113184115-a159579294ab`
- gomobile SHA-256 `745a2a62abd1aaef957bb8d1767f3f272b0ec791a43147508c5f68f6aa2b6c59`
- gobind SHA-256 `c85db3db8f5caf4aa8303d4d6c893b4f306159688d10b48fba4c6f65eabeabc6`
- Android NDK 28.2.13676358 (r28c), API 26, CGO disabled
- Temurin 11.0.32.1+1
- Android platform 36 / build-tools 36.0.0
- Android command-line tools 15859902, SHA-256
  `4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583`

NDK r28c supplies the Android 16 KiB page-size-compatible linker behavior. The
Go linker flags disable the checklinkname rejection needed by this pinned
source, set the IWS package WireGuard socket directory, and record version
`v0.77.1-iws-poc`.

The proven gomobile/gobind executable hashes above were produced by Go's
auto-downloaded 1.25.12 toolchain under a host-specific module-cache path. The
clean-room build uses the official Go 1.25.12 archive, so it verifies embedded
Go version and exact `x/mobile` module identity and records new executable
hashes; those host-path-sensitive executables are not expected to byte-match.

## Android APK

- Gradle 9.3.1, distribution SHA-256
  `17f277867f6914d61b1aa02efab1ba7bb439ad652ca485cd8ca6842fccec6e43`
- Gradle wrapper JAR SHA-256
  `b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13`
- Android Gradle Plugin 9.1.0
- Android Gradle Plugin JAR SHA-256
  `74b47a2a57349371273fb0893f61977a68dc40d5fb5e012a71d9e00c76ad728d`
- Temurin 21.0.11+10
- compile/target SDK 36; min SDK 26
- Java source/target 11
- JUnit 4.13.2
- Gradle dependency-verification metadata SHA-256
  `8ac0c130f3d21c44dc20988f45932a0b731b47a0bad13c10d4472095b7e516ca`
- Platform-tools 37.0.1 was resolved by AGP into the ignored SDK. It is an
  operational SDK component, not linked into the APK; Maven/Gradle build
  inputs are locked by `gradle/verification-metadata.xml`.

The rebuild uses an isolated Android user home and therefore a newly generated
ephemeral debug signer. Its APK signature is expected to differ from the proven
checkpoint while unsigned ZIP payload equality is checked separately.
