import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const root = path.join(process.cwd(), "packaging", "windows");

test("Windows bootstrap installs the private transport and dedicated WebView2 shell", async () => {
  const source = await readFile(path.join(root, "IwsSetupBootstrap.cs"), "utf8");
  const diagnostics = await readFile(path.join(root, "IwsSetupDiagnostics.cs"), "utf8");
  const manifest = await readFile(path.join(root, "IwsSetupBootstrap.manifest"), "utf8");
  for (const text of [
    "IWSDEVICEV1",
    "BUNDLE-MANIFEST.sha256",
    "Install-IwsPrivateTransport.ps1",
    "Install-IwsWebViewShellDevice.ps1",
    "IwsClient.exe",
    "UseShellExecute=true",
    "Directory.Delete"
  ]) assert.match(source, new RegExp(text.replace(/[.]/g, "[.]")));
  for (const text of [
    "RedirectStandardOutput=true",
    "RedirectStandardError=true",
    "BeginOutputReadLine",
    "BeginErrorReadLine",
    "ProgressBarStyle.Marquee",
    "BuiltinAdministratorsSid",
    "LocalSystemSid",
    "File.SetAccessControl"
  ]) assert.match(source, new RegExp(text.replace(/[.]/g, "[.]")));
  assert.match(diagnostics, /TryParsePhaseMarker/);
  assert.doesNotMatch(source, /StandardOutput[.]ReadToEnd|StandardError[.]ReadToEnd|exception[.]Message/);
  assert.doesNotMatch(source, /Install-IwsClientPoc|Launch-IwsPoc|--app=/);
  assert.equal(source.match(/MessageBox[.]Show/g)?.length, 1);
  assert.match(manifest, /requestedExecutionLevel level="requireAdministrator"/);
});

test("device shell installer preserves the official IWS shortcut identity", async () => {
  const source = await readFile(path.join(root, "Install-IwsWebViewShellDevice.ps1"), "utf8");
  assert.match(source, /SHELL-MANIFEST[.]sha256/);
  assert.match(source, /[$]shortcut[.]TargetPath = [$]installedClient/);
  assert.match(source, /[$]shortcut[.]IconLocation = [$]installedClient \+ ",0"/);
  assert.doesNotMatch(source, /one-use[.]key|device[.]json|setup.?key/iu);
  for (const marker of [
    "IWS_SETUP_PHASE=WEBVIEW_SHELL_INSTALLATION",
    "IWS_SETUP_PHASE=TRUST_INSTALLATION",
    "IWS_SETUP_PHASE=FIREWALL_BOUNDARY_INSTALLATION"
  ]) assert.match(source, new RegExp(marker));
});

test("private transport installer emits only fixed safe phase markers", async () => {
  const source = await readFile(path.join(process.cwd(), "windows", "Install-IwsPrivateTransport.ps1"), "utf8");
  for (const marker of [
    "IWS_SETUP_PHASE=TRANSPORT_INSTALLATION",
    "IWS_SETUP_PHASE=ENROLLMENT",
    "IWS_SETUP_PHASE=TRANSPORT_READY"
  ]) assert.match(source, new RegExp(marker));
});
