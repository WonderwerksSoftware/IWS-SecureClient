import assert from "node:assert/strict";
import {mkdtemp, mkdir, readFile, rm, writeFile, chmod} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import {spawnSync} from "node:child_process";
import test from "node:test";

const cli = new URL("../package-device.mjs", import.meta.url).pathname;

async function writeRequest(root, value, mode = 0o600) {
  const file = path.join(root, "request.json");
  await writeFile(file, JSON.stringify(value), {mode});
  await chmod(file, mode);
  return file;
}

test("package CLI returns one Windows artifact as machine-readable metadata", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "iws-package-cli-windows-"));
  try {
    const checkpointPath = path.join(root, "checkpoint");
    const payloadRoot = path.join(root, "prepared");
    const payload = path.join(payloadRoot, "windows-payload");
    const outputDirectory = path.join(root, "output");
    await mkdir(payload, {recursive: true});
    await mkdir(outputDirectory);
    await writeFile(path.join(payload, "Install-IwsPrivateTransport.ps1"), "transport");
    const manifestPath = path.join(root, "device.json");
    const setupKeyPath = path.join(root, "one-use.key");
    const templatePath = path.join(root, "IWS-Setup-Template.exe");
    await writeFile(manifestPath, "{}");
    await writeFile(setupKeyPath, "PRIVATE_CANARY");
    await writeFile(templatePath, "MZ-template");
    const requestFile = await writeRequest(root, {
      deviceId: "d1", generation: 1, platform: "WINDOWS", clientHostname: "iws-d1-g1",
      manifestPath, setupKeyPath, clientCheckpoint: "secure-client-v1",
      checkpointPath, outputDirectory
    });

    const result = spawnSync(process.execPath, [cli], {
      encoding: "utf8",
      env: {...process.env, IWS_PACKAGE_REQUEST_FILE: requestFile,
        IWS_WINDOWS_TEMPLATE: templatePath, IWS_WINDOWS_PAYLOAD_ROOT: payloadRoot}
    });
    assert.equal(result.status, 0, result.stderr);
    assert.doesNotMatch(result.stdout + result.stderr, /PRIVATE_CANARY/);
    const metadata = JSON.parse(result.stdout);
    assert.equal(metadata.filename, "IWS-Setup-d1-g1.exe");
    assert.equal(metadata.sizeBytes, String((await readFile(metadata.artifactPath)).length));
    assert.equal(metadata.clientCheckpoint, "secure-client-v1");
  } finally {
    await rm(root, {recursive: true, force: true});
  }
});

test("package CLI delegates Android packaging without reading or printing the setup key", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "iws-package-cli-android-"));
  try {
    const checkpointPath = path.join(root, "checkpoint");
    const scripts = path.join(checkpointPath, "scripts");
    const outputDirectory = path.join(root, "output");
    await mkdir(scripts, {recursive: true});
    await mkdir(outputDirectory);
    const builder = path.join(scripts, "build-android-device.sh");
    await writeFile(builder, "#!/bin/sh\nset -eu\nprintf artifact > \"$IWS_OUTPUT_DIR/IWS-d2-g3.apk\"\n", {mode: 0o700});
    const manifestPath = path.join(root, "device.json");
    const setupKeyPath = path.join(root, "one-use.key");
    const signerReference = path.join(root, "signer.properties");
    await writeFile(manifestPath, "{}");
    await writeFile(setupKeyPath, "ANDROID_PRIVATE_CANARY");
    await writeFile(signerReference, "private signer reference", {mode: 0o600});
    const requestFile = await writeRequest(root, {
      deviceId: "d2", generation: 3, platform: "ANDROID", clientHostname: "iws-d2-g3",
      manifestPath, setupKeyPath, clientCheckpoint: "secure-client-v1",
      checkpointPath, outputDirectory, signerReference
    });

    const result = spawnSync(process.execPath, [cli], {
      encoding: "utf8",
      env: {
        ...process.env,
        IWS_PACKAGE_REQUEST_FILE: requestFile,
        IWS_ANDROID_PROVEN_AAR: "/private/proven.aar",
        IWS_ANDROID_PROVEN_AAR_SHA256: "a".repeat(64),
        IWS_ANDROID_SIGNER_FINGERPRINT: "SHA256:fixture"
      }
    });
    assert.equal(result.status, 0, result.stderr);
    assert.doesNotMatch(result.stdout + result.stderr, /ANDROID_PRIVATE_CANARY/);
    const metadata = JSON.parse(result.stdout);
    assert.equal(metadata.filename, "IWS-d2-g3.apk");
    assert.equal(metadata.packageIdentity, "com.impactwiring.iwsconnectpoc");
    assert.deepEqual(metadata.signer, {kind: "ANDROID", fingerprint: "SHA256:fixture"});
  } finally {
    await rm(root, {recursive: true, force: true});
  }
});

test("package CLI rejects a group-readable request boundary", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "iws-package-cli-mode-"));
  try {
    const requestFile = await writeRequest(root, {platform: "WINDOWS"}, 0o640);
    const result = spawnSync(process.execPath, [cli], {
      encoding: "utf8",
      env: {...process.env, IWS_PACKAGE_REQUEST_FILE: requestFile}
    });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /PACKAGE_REQUEST_INVALID/);
  } finally {
    await rm(root, {recursive: true, force: true});
  }
});
