# Windows IWS client

This directory builds a disposable Windows proof around the official signed
NetBird v0.77.1 CLI payload. It deliberately extracts only `netbird.exe`
(renamed to `iws-transport.exe`), `wintun.dll`, and applicable notices.

`netbird-ui.exe`, the NetBird tray, NetBird shortcuts, setup keys, enrolled
identity, runtime logs, and generated bundles are prohibited from Git.

Build the ignored hidden-transport bundle on Linux:

```sh
./windows/build-bundle.sh
```

Build the dedicated IWS WebView2 shell with `windows/webview2/build-probe.ps1`
on Windows using its pinned SDK/runtime inputs. Then combine both verified
bundles with `packaging/windows/prepare-payload.sh` and create a device artifact
through `packaging/package-device.mjs`.

`Install-IwsPrivateTransport.ps1` owns only the hidden service and enrollment.
The rejected Edge app-mode launcher is absent; `IwsClient.exe` is the sole
employee-facing shell. This remains a POC installer, not a production signing
or update system.
