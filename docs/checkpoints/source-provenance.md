# Accepted source provenance

| Destination | Source | Accepted authority |
| --- | --- | --- |
| `android/`, Android build scripts, Android NetBird pins/notices | `/home/wcfox/dev/iws-client-android-productization` | commit `1de0bad08c0b303999c60f0f3972e71e4f88afa5`, tag `android-productization-poc-pass-20260904` |
| Android automatic bootstrap and protect-before-auth lifecycle | same Android lineage | commit `819f1933c57de01ecafdb8af88027c106cbb1365`, tag `android-device-provisioning-v1-pass-20260904`; proven ancestor of `1de0bad…` |
| `windows/` and Windows productized WebView2 shell/isolation | OLECLANKY `/home/wcfox/dev/iws-client-windows-productization` | commit `06151df888320d6f45200aee42e44da162da6b1b`, tag `windows-productization-poc-pass-20260904` |
| Windows one-app transport and 39-rule base | same Windows lineage | commit `08a5faa9cbe045df979ce6ff3f2e98c34ccf0531`, tag `windows-one-app-poc-pass-20260902`; proven ancestor of `06151df…` |
| `packaging/windows/IwsSetupBootstrap*`, device shell installer, setup harness | `/home/wcfox/dev/iws-stack-device-provisioning-v1` | commit `20ba6cb7086f909491047b7e17c2725bb06f6a2a`, tag `iws-device-provisioning-v1-pass-20260904` |
| shared `branding/` source and Windows embedded mark rasters | accepted Windows productization tree | commit `06151df888320d6f45200aee42e44da162da6b1b` |

The older Android tree present in the Windows ancestry was not imported over the
accepted Android productization tree. APK, AAR, EXE, WebView runtime, setup-key,
peer-state, log, and keystore artifacts were excluded from Git.

The rejected `Launch-IwsPoc.ps1` Edge app-mode implementation was deliberately
excluded. Its accepted hidden service/enrollment functions were retained as
`Install-IwsPrivateTransport.ps1` and `IwsPrivateTransport.psm1`; the dedicated
WebView2 shell remains the only employee-facing Windows client.
