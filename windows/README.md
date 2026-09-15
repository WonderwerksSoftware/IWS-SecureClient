# Windows IWS client

This directory builds the Windows IWS client around the official signed
NetBird v0.77.1 CLI payload. It deliberately extracts only `netbird.exe`
(renamed to `iws-transport.exe`), `wintun.dll`, and applicable notices.

`netbird-ui.exe`, the NetBird tray, NetBird shortcuts, setup keys, enrolled
identity, runtime logs, and generated bundles are prohibited from Git.

Build the ignored hidden-transport bundle on Linux:

```sh
./windows/build-bundle.sh
```

Build the dedicated IWS WebView2 shell with `windows/webview2/build-probe.ps1`
on Windows using its pinned SDK/runtime inputs. Compile the setup template and
Installed Apps uninstaller on Windows:

```powershell
packaging\windows\build-template.ps1 `
  -OutputPath C:\build\IWS-Setup-Template.exe `
  -UninstallerOutputPath C:\build\IwsUninstall.exe
```

Then combine the verified transport and WebView2 bundles with the compiled
uninstaller. The fourth argument is a new output directory:

```sh
packaging/windows/prepare-payload.sh \
  /path/to/transport-bundle \
  /path/to/webview-bundle \
  /path/to/IwsUninstall.exe \
  /path/to/prepared-output
```

Create the device artifact through `packaging/package-device.mjs`.

`Install-IwsPrivateTransport.ps1` owns only the hidden service and enrollment.
The setup wizard detects fresh, resumable, registered, legacy, mismatched, and
unknown states. Repair preserves identity; explicit clean/reassignment uses
protected local rollback. Windows Installed Apps invokes the bundled IWS-only
uninstaller without the original artifact or enrollment credential.
The rejected Edge app-mode launcher is absent; `IwsClient.exe` is the sole
employee-facing shell. This is not an updater or a public administrative
bootstrap service.
