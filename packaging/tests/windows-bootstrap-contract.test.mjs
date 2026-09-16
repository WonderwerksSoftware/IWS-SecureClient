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
    "Directory.Delete"
  ]) assert.match(source, new RegExp(text.replace(/[.]/g, "[.]")));
  assert.match(source, /UseShellExecute\s*=\s*true/);
  for (const pattern of [
    /RedirectStandardOutput\s*=\s*true/,
    /RedirectStandardError\s*=\s*true/,
    /BeginOutputReadLine/,
    /BeginErrorReadLine/,
    /ProgressBarStyle[.]Marquee/,
    /BuiltinAdministratorsSid/,
    /LocalSystemSid/,
    /File[.]SetAccessControl/
  ]) assert.match(source, pattern);
  for (const text of [
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
  for (const text of [
    "IwsSetupWelcomeForm",
    "IwsSetupCompleteForm",
    "CleanReinstall",
    "Backup-IwsClientForCleanReinstall.ps1",
    "Resolve-IwsFailedCleanReinstall.ps1",
    "IwsSetupMetadata.ParseManifest",
    "IwsSetupStateMachine.Evaluate"
  ]) assert.match(source, new RegExp(text.replace(/[.]/g, "[.]")));
  assert.match(source, /AddMinutes[(]10[)]/);
  assert.match(manifest, /requestedExecutionLevel level="requireAdministrator"/);
  assert.match(manifest, /8e0f7a12-bfb3-4fe8-b9a5-48fd50a15a9a/);
});

test("Windows setup supports normal Installed Apps removal without retaining enrollment material", async () => {
  const shell = await readFile(path.join(root, "Install-IwsWebViewShellDevice.ps1"), "utf8");
  const uninstaller = await readFile(path.join(root, "IwsUninstall.cs"), "utf8");
  const remove = await readFile(path.join(process.cwd(), "windows", "Uninstall-IwsClient.ps1"), "utf8");
  assert.match(shell, /CurrentVersion\\Uninstall\\IWS Secure Client/);
  assert.match(shell, /IwsUninstall[.]exe/);
  assert.match(uninstaller, /ExecutionPolicy Bypass/);
  assert.match(uninstaller, /WaitForExit[(]300000[)]/);
  assert.doesNotMatch(uninstaller + remove, /one-use[.]key|setup.?key|Tailscale/iu);
  assert.match(remove, /IWS Client Boundary POC/);
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
