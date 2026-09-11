import assert from "node:assert/strict";
import {mkdtemp, mkdir, writeFile, readFile, rm} from "node:fs/promises";
import {spawnSync} from "node:child_process";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {fileURLToPath} from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
test("Linux package rejects a wrong transport before emitting an installable artifact", async () => {
  const work = await mkdtemp(path.join(os.tmpdir(), "iws-linux-package-test-"));
  try {
    const request = {platform: "LINUX_DEBIAN", deviceId: "d1", generation: 1,
      clientHostname: "iws-d1-g1", manifestPath: path.join(work, "device.json"),
      setupKeyPath: path.join(work, "one-use.key"), clientCheckpoint: "test-checkpoint",
      checkpointPath: root, outputDirectory: path.join(work, "output")};
    await mkdir(request.outputDirectory);
    await writeFile(request.manifestPath, JSON.stringify({...request,
      expiresAt: new Date(Date.now() + 86400000).toISOString()}), {mode: 0o600});
    await writeFile(request.setupKeyPath, "synthetic-not-a-real-key", {mode: 0o600});
    await writeFile(path.join(work, "transport"), "wrong transport");
    await writeFile(path.join(work, "request.json"), JSON.stringify(request), {mode: 0o600});
    const result = spawnSync(process.execPath, [path.join(root, "packaging/package-device.mjs")], {
      env: {PATH: "/usr/bin:/bin", IWS_PACKAGE_REQUEST_FILE: path.join(work, "request.json"),
        IWS_LINUX_TRANSPORT_FILE: path.join(work, "transport")}, encoding: "utf8"});
    assert.equal(result.status, 1);
    assert.match(result.stderr, /LINUX_TRANSPORT_INVALID/);
    assert.doesNotMatch(result.stdout + result.stderr, /synthetic-not-a-real-key/);
  } finally { await rm(work, {recursive: true, force: true}); }
});
