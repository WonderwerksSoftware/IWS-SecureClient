import assert from "node:assert/strict";
import {mkdtemp, mkdir, readFile, rm, writeFile} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import {spawnSync} from "node:child_process";
import test from "node:test";

test("source scanner permits only the exact marked synthetic PAT fixture", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "iws-secret-scan-"));
  try {
    await mkdir(path.join(root, "scripts"));
    const scanner = path.join(root, "scripts/secret-scan.sh");
    await writeFile(scanner, await readFile(new URL("../../scripts/secret-scan.sh", import.meta.url)), {mode: 0o700});
    const marker = " // iws-synthetic-fixture";
    // Assemble assignments as test input; retain the full credential identifier.
    const assignment = (value, suffix) => ["NETBIRD_PAT", ': "', value, '",', suffix].join("");
    for (const [value, suffix, expected] of [
      ["synthetic-admin", marker, 0],
      ["synthetic-admin", "", 1],
      ["nbp_" + "a".repeat(48), "", 1],
      ["nbp_" + "a".repeat(48), marker, 1],
      ["synthetic-admin", marker + " additional content", 1]
    ]) {
      await writeFile(path.join(root, "fixture.js"), assignment(value, suffix) + "\n");
      const result = spawnSync(scanner, [], {cwd: root, encoding: "utf8"});
      assert.equal(result.status, expected, "scanner must distinguish the exact synthetic annotation from credential assignments");
    }
  } finally { await rm(root, {recursive: true, force: true}); }
});
