import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import test from "node:test";

test("client-version.json exposes the production candidate with historical acceptance provenance", async () => {
  const version = JSON.parse(await readFile(new URL("../../client-version.json", import.meta.url), "utf8"));
  assert.deepEqual(version, {
    schemaVersion: 1,
    release: "0.1.0-production-rc1",
    releaseTag: "secure-client-private-https-rc1-20260909",
    android: {
      packageIdentity: "com.impactwiring.iwsconnectpoc",
      versionCode: 2,
      versionName: "0.1-production-rc1",
      acceptedCommit: "1de0bad08c0b303999c60f0f3972e71e4f88afa5",
      acceptedTag: "android-productization-poc-pass-20260904"
    },
    windows: {
      packageIdentity: "IWS-Setup",
      clientVersion: "0.1.0-production-rc1",
      acceptedCommit: "06151df888320d6f45200aee42e44da162da6b1b",
      acceptedTag: "windows-productization-poc-pass-20260904"
    },
    transport: {
      netbirdVersion: "0.77.1",
      netbirdCommit: "79a06720b684768b421f0a54f3bb14f22704994f",
      androidAarSha256: "35f57f164006ef02df0d02b388b1d07dc7c7f2e72f7ab8c87b0fc49465ca58d7"
    },
    webView2: {
      sdkVersion: "1.0.4191.47",
      sdkSha256: "f492bbf547d0da329553b6727435b677579b1e9f91cc9e4a1ad029366d5f23d0",
      runtimeVersion: "152.0.4191.53",
      runtimeSha256: "f8f200b57d6a7a71d380f777f5c0ea0f71f520048add21e15737121de9ba4f68"
    }
  });
});

test("Node package metadata matches the production candidate release", async () => {
  const packageJson = JSON.parse(await readFile(new URL("../../package.json", import.meta.url), "utf8"));
  const packageLock = JSON.parse(await readFile(new URL("../../package-lock.json", import.meta.url), "utf8"));
  assert.equal(packageJson.version, "0.1.0-production-rc1");
  assert.equal(packageLock.version, packageJson.version);
  assert.equal(packageLock.packages[""].version, packageJson.version);
});
