import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import path from "node:path";
import test from "node:test";

test("Windows installer scripts parse and expose non-mutating clean recovery plans", {
  skip: process.platform !== "win32"
}, () => {
  const script = path.join(process.cwd(), "packaging", "windows", "tests", "Test-IwsInstallerScripts.ps1");
  const result = spawnSync("powershell.exe", [
    "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script,
    "-PackagingRoot", path.join(process.cwd(), "packaging", "windows"),
    "-WindowsRoot", path.join(process.cwd(), "windows")
  ], {encoding: "utf8"});
  assert.equal(result.status, 0, result.stderr || result.stdout);
  assert.match(result.stdout, /IWS_INSTALLER_SCRIPT_TESTS=pass/);
});
