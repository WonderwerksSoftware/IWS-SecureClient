import assert from "node:assert/strict";
import {mkdtemp, mkdir, readFile, readdir, rm, writeFile, chmod} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import {spawnSync} from "node:child_process";
import test from "node:test";
import {createHash} from "node:crypto";

const cli = new URL("../package-device.mjs", import.meta.url).pathname;

test("device script verifies with pinned Java and keeps Gradle state in its disposable source", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "iws-device-script-"));
  try {
    const source = path.join(root, "source");
    const cache = path.join(root, "toolchain");
    const output = path.join(root, "output");
    for (const dir of ["source/scripts", "source/third_party/netbird", "toolchain/tools/jdk21/bin", "toolchain/android-sdk/build-tools/fixture", "output"]) {
      await mkdir(path.join(root, dir), {recursive: true});
    }
    const script = path.join(source, "scripts/build-android-device.sh");
    await writeFile(script, await readFile(new URL("../../scripts/build-android-device.sh", import.meta.url)), {mode: 0o700});
    await writeFile(path.join(source, "scripts/secret-scan.sh"), await readFile(new URL("../../scripts/secret-scan.sh", import.meta.url)), {mode: 0o700});
    await writeFile(path.join(source, "third_party/netbird/pins.sh"), "ANDROID_BUILD_TOOLS=fixture\n");
    await writeFile(path.join(source, "scripts/build-android-poc.sh"), `#!/bin/sh
set -eu
case "$GRADLE_USER_HOME" in "$PWD"/android/.gradle/iws-private-*/gradle-home) : ;; *) exit 5 ;; esac
"$PWD/scripts/secret-scan.sh"
mkdir -p dist
cp "$IWS_SETUP_KEY_FILE" dist/classes.dex
(cd dist && zip -q iws-connect-poc-cleanroom.apk classes.dex)
`, {mode: 0o700});
    await writeFile(path.join(cache, "tools/jdk21/bin/java"), "#!/bin/sh\nexit 0\n", {mode: 0o700});
    await writeFile(path.join(cache, "android-sdk/build-tools/fixture/apksigner"), `#!/bin/sh
set -eu
test "$JAVA_HOME" = "$IWS_CLEANROOM_ROOT/tools/jdk21"
test "$(command -v java)" = "$JAVA_HOME/bin/java"
printf 'Signer #1 certificate SHA-256 digest: abcdef\\n'
`, {mode: 0o700});
    for (const [name, value] of [["setup", "00000000-0000-0000-0000-000000000000"], ["signer", "reference"], ["aar", "fixture-aar"],
      ["manifest", JSON.stringify({deviceId: "fixture", generation: 1, clientHostname: "fixture"})]]) {
      await writeFile(path.join(root, name), value, {mode: 0o600});
    }
    const result = spawnSync(script, [], {cwd: source, encoding: "utf8", env: {
      PATH: "/usr/bin:/bin", IWS_CLEANROOM_ROOT: cache, IWS_OUTPUT_DIR: output,
      IWS_DEVICE_MANIFEST_FILE: path.join(root, "manifest"), IWS_SETUP_KEY_FILE: path.join(root, "setup"),
      IWS_SIGNER_PROPERTIES: path.join(root, "signer"), IWS_EXPECTED_SIGNER_SHA256: "abcdef",
      IWS_PROVEN_AAR_FILE: path.join(root, "aar"), IWS_PROVEN_AAR_SHA256: createHash("sha256").update("fixture-aar").digest("hex")
    }});
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(await readdir(output), ["IWS-fixture-g1.apk"]);
    assert.deepEqual(await readdir(path.join(source, "android/.gradle")), []);
  } finally { await rm(root, {recursive: true, force: true}); }
});

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
        IWS_ANDROID_TOOLCHAIN_ROOT: "/shared/toolchain",
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

for (const outcome of ["success", "failure", "cleanup-failure"]) test(`Android disposable source cleanup on ${outcome}`, async () => {
  const fail = outcome === "failure";
  const root = await mkdtemp(path.join(os.tmpdir(), "iws-workspace-"));
  try {
    const checkpointPath = path.join(root, "checkpoint");
    const outputDirectory = path.join(root, "output");
    await mkdir(path.join(checkpointPath, "scripts"), {recursive: true});
    await mkdir(outputDirectory);
    for (const dir of [".git", ".cleanroom", "android/app/build", "windows", "dist"]) {
      await mkdir(path.join(checkpointPath, dir), {recursive: true});
      await writeFile(path.join(checkpointPath, dir, "excluded"), "untouched");
    }
    await writeFile(path.join(checkpointPath, "scripts/build-android-device.sh"), `#!/bin/sh
set -eu
pwd > "$IWS_OUTPUT_DIR/built-at"
test "$(stat -c %a .)" = 700
test "$IWS_CLEANROOM_ROOT" = /shared/toolchain
test -z "\${NETBIRD_PAT+x}\${DATABASE_URL+x}\${JAVA_HOME+x}\${IWS_UNRELATED+x}"
test ! -e .git && test ! -e .cleanroom && test ! -e android/app/build && test ! -e windows && test ! -e dist
mkdir -p android/app/build
cp "$IWS_SETUP_KEY_FILE" android/app/build/BuildConfig.java
${outcome === "cleanup-failure" ? "chmod 500 android/app/build" : ":"}
${fail ? "exit 1" : 'printf artifact > "$IWS_OUTPUT_DIR/result.apk"'}
`, {mode: 0o700});
    const setupKeyPath = path.join(root, "setup");
    await writeFile(setupKeyPath, "harmless-synthetic-bootstrap");
    const requestFile = await writeRequest(root, {deviceId: "d", generation: 1, platform: "ANDROID", clientHostname: "h",
      manifestPath: path.join(root, "manifest"), setupKeyPath, signerReference: "/private/signer",
      checkpointPath, clientCheckpoint: "immutable-fixture", outputDirectory});
    const result = spawnSync(process.execPath, [cli], {encoding: "utf8", env: {...process.env,
      IWS_PACKAGE_REQUEST_FILE: requestFile, IWS_ANDROID_TOOLCHAIN_ROOT: "/shared/toolchain",
      NETBIRD_PAT: "synthetic-admin", DATABASE_URL: "synthetic-db", JAVA_HOME: "/wrong/java", IWS_UNRELATED: "unused"}});
    assert.equal(result.status, outcome === "success" ? 0 : 1, result.stderr);
    assert.doesNotMatch(result.stdout + result.stderr, /harmless-synthetic-bootstrap|synthetic-admin|synthetic-db/);
    const builtAt = (await readFile(path.join(outputDirectory, "built-at"), "utf8")).trim();
    assert.notEqual(builtAt, checkpointPath);
    assert.equal(path.dirname(builtAt), outputDirectory);
    if (outcome === "cleanup-failure") {
      assert.equal(result.stdout, "");
      assert.match(result.stderr, /ANDROID_WORKSPACE_CLEANUP_FAILED/);
      await chmod(path.join(builtAt, "android/app/build"), 0o700);
      return;
    }
    await assert.rejects(readFile(path.join(builtAt, "android/app/build/BuildConfig.java")), {code: "ENOENT"});
    assert.deepEqual(await readdir(path.join(checkpointPath, "android/app/build")), ["excluded"]);
    assert.equal((await readdir(outputDirectory)).some(name => name.startsWith(".android-source-")), false);
  } finally { await rm(root, {recursive: true, force: true}); }
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
