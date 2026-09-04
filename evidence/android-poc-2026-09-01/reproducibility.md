# Clean-room reproducibility comparison

The clean-room rebuild used only this repository, freshly downloaded/hash-
verified toolchains, and NetBird commit
`79a06720b684768b421f0a54f3bb14f22704994f`.

## Artifact hashes

| Artifact | Proven checkpoint | Clean-room rebuild |
|---|---|---|
| NetBird AAR | `35f57f164006ef02df0d02b388b1d07dc7c7f2e72f7ab8c87b0fc49465ca58d7` | `d7995ee555b5dc85ab90b0cf413b38e22efbcf0306ae0e4f31ebe7e904764951` |
| Android APK | `fc09145b75d5f0d80c076ae6b8c7cb04c4b8ac7a6487eac82e30fc51896d4a74` | `b7680c93ec34272587195da6d8944051c6de170f65f37fc7b72778e0df0ace1a` |

## Explained differences

The AAR archive metadata and generated `classes.jar` are byte-identical;
`classes.jar` SHA-256 is
`c39d89b9e627a1959cb9bc06eaec43bdfef9d5202c471305e572344b0e674732`.
The four `libgojni.so` files differ because Go embeds absolute GOROOT and source
paths plus build IDs. The proven build used Go's auto-downloaded toolchain at
`/home/wcfox/go/pkg/mod/golang.org/toolchain@v0.0.1-go1.25.12.linux-amd64`
and the disposable POC source path. The clean build uses the official
Go 1.25.12 archive under this repository's ignored clean-room directory.

Both native payloads record Go 1.25.12, version `v0.77.1-iws-poc`, the exact
WireGuard socket directory, and the same pinned NetBird source. The clean APK
passes Android's 16 KiB ZIP/native alignment check.

The APK additionally differs because it contains the clean native libraries and
is signed by an isolated ephemeral debug key. Proven signer certificate SHA-256:
`a7d2fd8d7ce70cf9d96da64199af22417d0508739c3240a2bc48c93086fac7d2`.
Clean signer certificate SHA-256:
`0167e70772e790470478e77a73b40beca89d180476838e8729925747ad657100`.
This proves the external mode-0644 debug keystore was not used by the clean build.

The clean APK reports package `com.impactwiring.iwsconnectpoc`, min SDK 26,
target/compile SDK 36. Unit tests, lint, assembly, package inspection, and 16 KiB
alignment all passed.
