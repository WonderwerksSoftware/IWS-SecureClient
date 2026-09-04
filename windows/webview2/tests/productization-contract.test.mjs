import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {existsSync, readFileSync, statSync} from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";
import test from "node:test";

const testRoot = path.dirname(fileURLToPath(import.meta.url));
const webViewRoot = path.dirname(testRoot);
const source = readFileSync(path.join(webViewRoot, "IwsClient.cs"), "utf8");
const build = readFileSync(path.join(webViewRoot, "build-probe.ps1"), "utf8");
const installer = readFileSync(path.join(webViewRoot, "Install-IwsWebViewShellPoc.ps1"), "utf8");
const icon = path.join(webViewRoot, "assets", "iws.ico");
const brandingRoot = path.resolve(webViewRoot, "..", "..", "branding");
const iconSource = path.join(brandingRoot, "iws-icon-source.svg");

test("Windows shell exposes only the approved IWS visual treatment", () => {
  for (const required of [
    'Text = "IWS"',
    'Text = "‹  Back"',
    'Text = "IWS Portal"',
    'Text = "Connecting to IWS…"',
    '"IWS is unavailable."',
    '"Check your connection and try again."',
    'Text = "Retry"',
    'Text = "Light"',
    'Text = "Dark"',
    'AppsUseLightTheme',
    'SystemEvents.UserPreferenceChanged',
    'Icon.ExtractAssociatedIcon(Application.ExecutablePath)',
    'FlatStyle = FlatStyle.Flat',
    'SetThemeChoice("light")',
    'SetThemeChoice("dark")',
    'LoadBrandMark(',
    'SpinnerControl',
    'ErrorGlyphControl',
    'ConnectedIndicator',
    'set { connected = value; Invalidate(); }',
    'SetCurrentProcessExplicitAppUserModelID("ImpactWiring.IWS.Client")'
  ]) assert.ok(source.includes(required), `missing visual contract: ${required}`);

  for (const required of [
    'core.NavigationCompleted += delegate',
    'args.HttpStatusCode >= 400',
    'ShowConnectionState(',
    '"IWS is unavailable."',
    '"Check your connection and try again."'
  ]) assert.ok(source.includes(required), `branded navigation failure contract missing: ${required}`);

  assert.ok(!source.includes('backButton.Enabled = core.CanGoBack'),
    "Back visibility is delegated to low-contrast system disabled painting");
  assert.ok(!source.includes('Text = "‹  Back", Width = 82, Height = 36, Enabled = false'),
    "Back starts in a low-contrast disabled state");
  assert.ok(source.includes('LoadEmbeddedIcon("IwsIcon.ico")'),
    "window/taskbar icon does not use the dual-keyline transparent resource");

  for (const required of [
    'contentHost.Controls.Add(webView)',
    'contentHost.Controls.Add(statusPanel)',
    'shellLayout.Controls.Add(toolbar, 0, 0)',
    'shellLayout.Controls.Add(railRule, 0, 1)',
    'shellLayout.Controls.Add(contentHost, 0, 2)'
  ]) assert.ok(source.includes(required), `native rail overlap contract missing: ${required}`);

  for (const prohibited of [
    'Connecting privately...',
    'Private connection ready',
    'MessageBox.Show('
  ]) assert.ok(!source.includes(prohibited), `employee-visible POC copy remains: ${prohibited}`);
});

test("official IWS icon is embedded and assigned to the shortcut", () => {
  assert.ok(existsSync(iconSource), "canonical transparent IWS icon source is missing");
  const iconSourceBytes = readFileSync(iconSource);
  assert.equal(createHash("sha256").update(iconSourceBytes).digest("hex"),
    "f84308e2e82de3977243daa855aa904e5cc5ed84bc659f906428a6613d8f4909",
    "Windows icon source differs from Claude's exact transparent mark-small.svg");
  assert.ok(!iconSourceBytes.toString("utf8").includes("<rect"),
    "Windows icon source contains a background rectangle");
  assert.ok(existsSync(icon), "official IWS icon is missing");
  assert.ok(statSync(icon).size > 1000, "official IWS icon is unexpectedly small");
  assert.ok(build.includes('/win32icon:$iconPath'), "compiler does not embed the IWS icon");
  assert.ok(build.includes('assets\\iws.ico'), "build does not locate the pinned IWS icon");
  assert.ok(build.includes('/resource:$iconPath,IwsIcon.ico'),
    "compiler does not embed the dual-keyline transparent icon");
  assert.ok(build.includes('/resource:$markFullPath,IwsMarkFull.png'), "compiler does not embed the full IWS mark");
  assert.ok(build.includes('/resource:$markSmallPath,IwsMarkSmall.png'), "compiler does not embed the small IWS mark");
  assert.ok(build.includes('branding\\iws-mark-full.png'), "build does not locate the full IWS mark");
  assert.ok(build.includes('branding\\iws-mark-small.png'), "build does not locate the small IWS mark");
  assert.ok(installer.includes('$shortcut.IconLocation = $installedClient + ",0"'),
    "Start Menu shortcut does not use the IWS executable icon");
});
