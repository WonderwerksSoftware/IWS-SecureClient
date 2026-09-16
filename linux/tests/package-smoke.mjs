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
    const control = path.join(output, 'control');
    const child = spawnSync('dpkg-deb', ['-x', result.artifactPath, extracted]);
    assert.equal(child.status, 0);
    assert.equal(spawnSync('dpkg-deb', ['-e', result.artifactPath, control]).status, 0);
    assert.match(await readFile(path.join(control, 'control'), 'utf8'), /Version: 1[.]0[.]2-1/);
    assert.equal((await readFile(path.join(control, 'postinst'), 'utf8')).trim(),
      '#!/bin/sh\nset -eu\n/usr/bin/python3 -I /usr/lib/iws-client/runtime.py prepare');
    assert.equal((await stat(path.join(extracted, 'usr'))).mode & 0o777, 0o755);
    assert.equal((await stat(path.join(extracted, 'usr/bin/iws'))).mode & 0o777, 0o755);
    assert.equal((await stat(path.join(extracted, 'usr/lib/iws-client/iws-root-ca.crt'))).mode & 0o777, 0o644);
    const desktop = await readFile(path.join(extracted, 'usr/share/applications/iws.desktop'), 'utf8');
    const icon = desktop.match(/^Icon=(.+)$/m)?.[1];
    assert.ok(icon, 'installed launcher must declare its icon');
    assert.deepEqual(await readFile(path.join(extracted, `usr/share/icons/hicolor/scalable/apps/${icon}.svg`)),
      await readFile(path.join(root, 'branding/iws-icon-source.svg')));
    assert.equal((await stat(path.join(extracted, 'usr/lib/iws-client/iws-setup-helper'))).mode & 0o777, 0o755);
    assert.equal((await stat(path.join(extracted, 'usr/share/polkit-1/actions/com.impactwiring.iws-client.policy'))).mode & 0o777, 0o644);
    assert.equal((await stat(path.join(extracted, 'usr/share/iws-client/bootstrap/one-use.key'))).mode & 0o777, 0o600);
  } else {
    const query = spawnSync('rpm', ['-qp', '--qf', '%{VERSION}', result.artifactPath], {encoding: 'utf8'});
    assert.equal(query.status, 0, query.stderr);
    assert.equal(query.stdout, '1.0.2');
    const files = spawnSync('rpm', ['-qlp', result.artifactPath], {encoding: 'utf8'});
    assert.equal(files.status, 0);
    assert.ok(files.stdout.split('\n').includes('/usr/share/icons/hicolor/scalable/apps/iws.svg'));
    const scripts = spawnSync('rpm', ['-qp', '--scripts', result.artifactPath], {encoding: 'utf8'});
    assert.equal(scripts.status, 0, scripts.stderr);
    assert.match(scripts.stdout, /runtime[.]py prepare/);
    assert.doesNotMatch(scripts.stdout, /runtime[.]py install|systemctl restart|--now/);
  }
  console.log(JSON.stringify({...result, sizeBytes: result.sizeBytes.toString()}));
}
