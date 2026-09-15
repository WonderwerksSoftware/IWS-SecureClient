import assert from "node:assert/strict";
import {mkdtemp, mkdir, readFile, rm, writeFile} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
  packageWindowsDevice,
  parseWindowsOverlay
} from "../windows/package-device.mjs";

test("Windows packager creates one authenticated zero-input IWS installer", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "iws-secure-client-windows-"));
  try {
    const checkpointPath = path.join(root, "checkpoint");
    const payload = path.join(checkpointPath, "windows-payload");
    const outputDirectory = path.join(root, "output");
    await mkdir(payload, {recursive: true});
    await mkdir(outputDirectory);
    await writeFile(path.join(payload, "Install-IwsPrivateTransport.ps1"), "transport installer");
    await writeFile(path.join(payload, "Install-IwsWebViewShellDevice.ps1"), "shell installer");
    await writeFile(path.join(payload, "IwsUninstall.exe"), "MZ-uninstaller");
    await writeFile(path.join(payload, "Uninstall-IwsClient.ps1"), "uninstaller");
    const templatePath = path.join(root, "IWS-Setup-Template.exe");
    await writeFile(templatePath, Buffer.from("MZ-IWS-TEMPLATE"));
    const manifestPath = path.join(root, "device.json");
    await writeFile(manifestPath, JSON.stringify({
      deviceId: "d1",
      generation: 2,
      platform: "WINDOWS",
      clientHostname: "iws-d1-g2",
      clientCheckpoint: "secure-client-v1.0.1",
      expiresAt: new Date(Date.now() + 86400000).toISOString()
    }));
    const setupKeyPath = path.join(root, "one-use.key");
    await writeFile(setupKeyPath, "ONE_USE_CANARY");

    const result = await packageWindowsDevice({
      request: {
        deviceId: "d1",
        generation: 2,
        platform: "WINDOWS",
        clientHostname: "iws-d1-g2",
        manifestPath,
        setupKeyPath,
        clientCheckpoint: "secure-client-v1.0.1",
        checkpointPath,
        outputDirectory
      },
      templatePath
    });

    const bytes = await readFile(result.artifactPath);
    const parsed = parseWindowsOverlay(bytes);
    assert.equal(bytes.subarray(0, 2).toString(), "MZ");
    assert.equal(parsed.magic, "IWSDEVICEV1");
    assert.equal(bytes.toString().split("ONE_USE_CANARY").length - 1, 1);
    assert.match(parsed.payload.toString("latin1"), /Install-IwsPrivateTransport[.]ps1/);
    assert.match(parsed.payload.toString("latin1"), /Install-IwsWebViewShellDevice[.]ps1/);
    assert.match(parsed.payload.toString("latin1"), /IwsUninstall[.]exe/);
    assert.match(parsed.payload.toString("latin1"), /Uninstall-IwsClient[.]ps1/);
    assert.doesNotMatch(parsed.payload.toString("latin1"), /Launch-IwsPoc|--app=/);
    assert.equal(result.filename, "IWS-Setup-d1-g2.exe");
    assert.equal(result.packageIdentity, "IWS-Setup");
    assert.equal(result.clientCheckpoint, "secure-client-v1.0.1");
    assert.match(result.sha256, /^[0-9a-f]{64}$/);
  } finally {
    await rm(root, {recursive: true, force: true});
  }
});

test("Windows packager rejects mismatched or expired enrollment provenance", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "iws-secure-client-windows-invalid-"));
  try {
    const checkpointPath = path.join(root, "checkpoint");
    const payload = path.join(checkpointPath, "windows-payload");
    const outputDirectory = path.join(root, "output");
    await mkdir(payload, {recursive: true});
    await mkdir(outputDirectory);
    const templatePath = path.join(root, "IWS-Setup-Template.exe");
    await writeFile(templatePath, Buffer.from("MZ-IWS-TEMPLATE"));
    const manifestPath = path.join(root, "device.json");
    const setupKeyPath = path.join(root, "one-use.key");
    await writeFile(setupKeyPath, "ONE_USE_CANARY");
    const request = {
      deviceId: "d1", generation: 2, platform: "WINDOWS", clientHostname: "iws-d1-g2",
      manifestPath, setupKeyPath, clientCheckpoint: "secure-client-v1.0.1",
      checkpointPath, outputDirectory
    };

    await writeFile(manifestPath, JSON.stringify({
      ...request, deviceId: "different", expiresAt: new Date(Date.now() + 86400000).toISOString()
    }));
    await assert.rejects(packageWindowsDevice({request, templatePath}), /WINDOWS_MANIFEST_INVALID/);

    await writeFile(manifestPath, JSON.stringify({
      ...request, expiresAt: new Date(Date.now() - 60000).toISOString()
    }));
    await assert.rejects(packageWindowsDevice({request, templatePath}), /WINDOWS_MANIFEST_EXPIRED/);
  } finally {
    await rm(root, {recursive: true, force: true});
  }
});

test("Windows packager rejects malformed overlay trailers", () => {
  for (const bytes of [Buffer.alloc(0), Buffer.from("MZ"), Buffer.concat([Buffer.from("MZbad"), Buffer.alloc(52)])]) {
    assert.throws(() => parseWindowsOverlay(bytes), /WINDOWS_OVERLAY_INVALID/);
  }
});
