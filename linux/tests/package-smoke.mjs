// Builds inert fixture packages only. Never install these or call management.
import {mkdtemp, mkdir, writeFile, readFile, stat} from "node:fs/promises";
import path from "node:path";
import os from "node:os";
import {fileURLToPath} from "node:url";
import {packageLinuxDevice} from "../../packaging/linux/package-device.mjs";
import {spawnSync} from "node:child_process";
import assert from "node:assert/strict";
process.umask(0o077); // Match the protected server generation service.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const work = await mkdtemp(path.join(os.tmpdir(), "iws-linux-inert-packages-"));
for (const platform of ["LINUX_DEBIAN", "LINUX_FEDORA"]) {
  const output = path.join(work, platform);
  await mkdir(output, {mode: 0o700});
  const request = {deviceId: "syntheticfixture", generation: 1, platform,
    clientHostname: "iws-syntheticfixture-g1", clientCheckpoint: "inert-development-fixture",
    checkpointPath: root, manifestPath: path.join(output, "manifest.json"),
    setupKeyPath: path.join(output, "one-use.key"), outputDirectory: output};
  await writeFile(request.manifestPath, JSON.stringify({...request,
    expiresAt: new Date(Date.now() + 86400000).toISOString()}), {mode: 0o600});
  await writeFile(request.setupKeyPath, "synthetic-not-a-real-key", {mode: 0o600});
  const result = await packageLinuxDevice(request, process.env.IWS_TEST_LINUX_TRANSPORT_FILE);
  if (platform === "LINUX_DEBIAN") {
    const extracted = path.join(output, 'unpacked');
    const child = spawnSync('dpkg-deb', ['-x', result.artifactPath, extracted]);
    assert.equal(child.status, 0);
    assert.equal((await stat(path.join(extracted, 'usr'))).mode & 0o777, 0o755);
    assert.equal((await stat(path.join(extracted, 'usr/bin/iws'))).mode & 0o777, 0o755);
    assert.equal((await stat(path.join(extracted, 'usr/lib/iws-client/iws-root-ca.crt'))).mode & 0o777, 0o644);
    assert.equal((await stat(path.join(extracted, 'usr/share/iws-client/bootstrap/one-use.key'))).mode & 0o777, 0o600);
  }
  console.log(JSON.stringify({...result, sizeBytes: result.sizeBytes.toString()}));
}
