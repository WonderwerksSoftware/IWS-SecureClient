import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import test from "node:test";

const testDir = dirname(fileURLToPath(import.meta.url));
const builder = resolve(testDir, "..", "build-bundle.sh");

function fixture(files) {
  const root = mkdtempSync(resolve(tmpdir(), "iws-windows-bundle-test-"));
  for (const name of files) {
    const path = resolve(root, name);
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, "fixture\n");
  }
  return root;
}

function inspect(root) {
  return spawnSync("bash", [builder, "--inspect-structure", root], {
    encoding: "utf8"
  });
}

test("accepts the minimal IWS-owned runtime structure", () => {
  const root = fixture([
    "iws-transport.exe",
    "wintun.dll",
    "LICENSE",
    "LICENSES/BSD-3-Clause.txt"
  ]);
  try {
    const result = inspect(root);
    assert.equal(result.status, 0, result.stderr || result.stdout);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

for (const forbidden of ["netbird-ui.exe", "netbird.exe", "one-use.key", "tray.ico", "unknown.dll"]) {
  test(`rejects forbidden bundle member ${forbidden}`, () => {
    const root = fixture([
      "iws-transport.exe",
      "wintun.dll",
      "LICENSE",
      "LICENSES/BSD-3-Clause.txt",
      forbidden
    ]);
    try {
      const result = inspect(root);
      assert.notEqual(result.status, 0, `unexpectedly accepted ${forbidden}`);
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });
}
