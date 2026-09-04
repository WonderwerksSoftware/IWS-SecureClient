# Windows IWS WebView2 shell

This directory contains the disposable, test-first proof for the Windows IWS shell boundary. It uses only the pinned Microsoft WebView2 SDK and Fixed Version Runtime recorded in `pins.env`.

`IwsClient.exe` is the accepted employee-facing shell. `IwsBoundaryProbe.exe`
remains a test-only executable that verifies the actual Chromium process paths
and Windows Firewall behavior.

Downloaded packages, extracted runtimes, compiled binaries, profiles, and evidence remain under ignored `.cleanroom` or VM-local paths and are not committed.
