import assert from "node:assert/strict";
import {mkdtemp, mkdir, readFile, readdir, rm, writeFile} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import {spawnSync} from "node:child_process";
import {createHash} from "node:crypto";
import test from "node:test";

test("Android occurrence verification keeps synthetic setup material out of child argv and counts exact matches", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "iws-android-argv-"));
  const synthetic = "synthetic-only-[literal].bootstrap";
  const put = async (name, contents, mode = 0o600) => {
    const file = path.join(root, name);
    await mkdir(path.dirname(file), {recursive: true});
    await writeFile(file, contents, {mode});
    return file;
  };
  try {
    const script = await put("scripts/build-android-device.sh", await readFile(new URL("../../scripts/build-android-device.sh", import.meta.url)), 0o700);
    await put("third_party/netbird/pins.sh", "ANDROID_BUILD_TOOLS=test\n");
    await put("scripts/build-android-poc.sh", '#!/bin/sh\nmkdir -p "$TEST_ROOT/dist"\ncp "$TEST_ROOT/payload" "$TEST_ROOT/dist/iws-connect-production-rc1.apk"\n', 0o700);
    await put("bin/jq", '#!/bin/sh\ncase "$2" in *deviceId*) echo test;; *generation*) echo 1;; *) echo iws-test;; esac\n', 0o700);
    await put("bin/unzip", '#!/bin/sh\ncat "$2"\n', 0o700);
    await put("bin/grep", `#!${process.execPath}\nconst fs = require('node:fs'); const cp = require('node:child_process');
const args = process.argv.slice(2);
fs.appendFileSync(process.env.TEST_ROOT + '/child-argv', JSON.stringify(args) + '\\n');
const i = args.indexOf('-f');
if (i >= 0 && (fs.statSync(args[i + 1]).mode & 0o777) !== 0o600) process.exit(90);
const r = cp.spawnSync('/usr/bin/grep', args, {stdio: 'inherit'}); process.exit(r.status ?? 91);
`, 0o700);
    await put(".cleanroom/tools/jdk21/bin/java", "#!/bin/sh\nexit 0\n", 0o700);
    await put(".cleanroom/android-sdk/build-tools/test/apksigner", "#!/bin/sh\necho 'Signer #1 certificate SHA-256 digest: abcdef'\n", 0o700);
    const manifest = await put("manifest", "{}");
    const keyFile = await put("input-material", synthetic + "\r\n");
    const signer = await put("signer", "synthetic");
    const aar = await put("native", "native-test");
    await mkdir(path.join(root, "output"));
    for (const count of [0, 1, 2]) {
      await put("payload", "nonmatching bootstrap\n" + (synthetic + "\n").repeat(count));
      await put("child-argv", "");
      const result = spawnSync(script, [], {encoding: "utf8", env: {
        ...process.env, TEST_ROOT: root, PATH: `${root}/bin:${process.env.PATH}`,
        IWS_DEVICE_MANIFEST_FILE: manifest, IWS_SETUP_KEY_FILE: keyFile, IWS_SIGNER_PROPERTIES: signer,
        IWS_OUTPUT_DIR: path.join(root, "output"), IWS_EXPECTED_SIGNER_SHA256: "abcdef",
        IWS_PROVEN_AAR_FILE: aar, IWS_PROVEN_AAR_SHA256: createHash("sha256").update("native-test").digest("hex")
      }});
      assert.equal(result.status, count === 1 ? 0 : 1, "only exactly one literal occurrence may pass");
      assert.ok(!(result.stdout + result.stderr).includes(synthetic), "build output must not contain setup material");
      const args = await readFile(path.join(root, "child-argv"), "utf8");
      assert.ok(!args.includes(synthetic), "child argv must not contain setup material");
      assert.deepEqual(await readdir(path.join(root, "android/.gradle")), [], "private verification files must be removed");
    }
  } finally { await rm(root, {recursive: true, force: true}); }
});
