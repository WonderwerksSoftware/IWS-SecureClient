import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import path from "node:path";
import test from "node:test";

test("Windows diagnostics classify only whitelisted phases and discard unsafe child output", {
  skip: process.platform !== "win32"
}, () => {
  const script = path.join(process.cwd(), "packaging", "windows", "tests", "Test-IwsSetupDiagnostics.ps1");
  const result = spawnSync("powershell.exe", [
    "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script
  ], {encoding: "utf8"});
  assert.equal(result.status, 0, result.stderr || result.stdout);
  assert.match(result.stdout, /IWS_SETUP_DIAGNOSTICS_TESTS=pass/);
});
