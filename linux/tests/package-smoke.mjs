// Builds inert fixture packages only. Never install these or call management.
import {mkdtemp, mkdir, writeFile, readFile, stat} from "node:fs/promises";
import path from "node:path";
import os from "node:os";
import {fileURLToPath} from "node:url";
import {packageLinuxDevice} from "../../packaging/linux/package-device.mjs";
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
  console.log(JSON.stringify({...result, sizeBytes: result.sizeBytes.toString()}));
}
