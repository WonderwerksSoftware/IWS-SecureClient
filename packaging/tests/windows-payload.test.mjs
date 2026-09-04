import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {mkdtemp, mkdir, readFile, readdir, rm, writeFile} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import {spawnSync} from "node:child_process";
import test from "node:test";

const preparer = new URL("../windows/prepare-payload.sh", import.meta.url).pathname;

async function add(root, relative, contents) {
  const file = path.join(root, relative);
  await mkdir(path.dirname(file), {recursive: true});
  await writeFile(file, contents);
  return `${createHash("sha256").update(contents).digest("hex")}  ${relative}\n`;
}

test("Windows payload preparation combines only accepted transport and WebView2 members", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "iws-windows-payload-"));
  try {
    const transport = path.join(root, "transport");
    const webview = path.join(root, "webview");
    const output = path.join(root, "output");
    await mkdir(transport);
    await mkdir(webview);
    let transportManifest = "";
    for (const [name, value] of [
      ["iws-transport.exe", "MZ-transport"],
      ["wintun.dll", "wintun"],
      ["LICENSE", "MPL-2.0"],
      ["LICENSES/BSD-3-Clause.txt", "BSD"],
      ["pins.psd1", "@{}"],
      ["IwsPrivateTransport.psm1", "module"],
      ["Install-IwsPrivateTransport.ps1", "installer"],
      ["Remove-IwsClientPoc.ps1", "remover"]
    ]) transportManifest += await add(transport, name, value);
    await writeFile(path.join(transport, "BUNDLE-MANIFEST.sha256"), transportManifest);

    let shellManifest = "";
    for (const [name, value] of [
      ["IwsClient.exe", "MZ-client"],
      ["IwsBoundaryProbe.exe", "MZ-probe"],
      ["Microsoft.Web.WebView2.Core.dll", "core"],
      ["Microsoft.Web.WebView2.WinForms.dll", "forms"],
      ["WebView2Loader.dll", "loader"],
      ["IwsWebViewFirewall.psm1", "firewall"],
      ["Set-IwsWebViewBoundary.ps1", "set-boundary"],
      ["Remove-IwsWebViewBoundary.ps1", "remove-boundary"],
      ["WebView2Fixed/Microsoft.WebView2.FixedVersionRuntime.152.0.4191.53.x64/msedgewebview2.exe", "MZ-webview"]
    ]) shellManifest += await add(webview, name, value);
    await writeFile(path.join(webview, "BUNDLE-MANIFEST.sha256"), shellManifest);

    const result = spawnSync("sh", [preparer, transport, webview, output], {encoding: "utf8"});
    assert.equal(result.status, 0, result.stderr);
    const members = (await readdir(path.join(output, "windows-payload"), {recursive: true}))
      .map(String).sort();
    for (const required of [
      "Install-IwsPrivateTransport.ps1",
      "Install-IwsWebViewShellDevice.ps1",
      "IwsClient.exe",
      "IwsPrivateTransport.psm1",
      "iws-transport.exe"
    ]) assert.ok(members.includes(required), `missing ${required}`);
    assert.ok(members.some(value => value.endsWith("msedgewebview2.exe")));
    assert.ok(!members.some(value => /Launch-IwsPoc|netbird-ui|one-use[.]key/.test(value)));
    assert.match(await readFile(path.join(output, "windows-payload", "BUNDLE-MANIFEST.sha256"), "utf8"), /^[0-9a-f]{64}  /m);
  } finally {
    await rm(root, {recursive: true, force: true});
  }
});
