#!/usr/bin/env node
import {createHash} from "node:crypto";
import {createReadStream} from "node:fs";
import {lstat, readFile, readdir, stat} from "node:fs/promises";
import path from "node:path";
import {spawn} from "node:child_process";
import {packageWindowsDevice} from "./windows/package-device.mjs";

async function sha256File(file) {
  const digest = createHash("sha256");
  for await (const chunk of createReadStream(file)) digest.update(chunk);
  return digest.digest("hex");
}

async function loadRequest(file) {
  const info = await lstat(file);
  if (!info.isFile() || info.isSymbolicLink() || (info.mode & 0o777) !== 0o600) {
    throw new Error("PACKAGE_REQUEST_INVALID");
  }
  const request = JSON.parse(await readFile(file, "utf8"));
  for (const name of [
    "deviceId", "generation", "platform", "clientHostname", "manifestPath",
    "setupKeyPath", "clientCheckpoint", "checkpointPath", "outputDirectory"
  ]) {
    if (request[name] === undefined || request[name] === "") throw new Error("PACKAGE_REQUEST_INVALID");
  }
  return request;
}

async function runAndroid(request) {
  if (!request.signerReference) throw new Error("ANDROID_SIGNER_REQUIRED");
  const builder = path.join(request.checkpointPath, "scripts", "build-android-device.sh");
  await new Promise((resolve, reject) => {
    const child = spawn(builder, [], {
      cwd: request.checkpointPath,
      stdio: "ignore",
      env: {
        PATH: "/usr/bin:/bin",
        LANG: "C.UTF-8",
        LC_ALL: "C.UTF-8",
        TMPDIR: request.outputDirectory,
        IWS_DEVICE_MANIFEST_FILE: request.manifestPath,
        IWS_SETUP_KEY_FILE: request.setupKeyPath,
        IWS_OUTPUT_DIR: request.outputDirectory,
        IWS_SIGNER_PROPERTIES: request.signerReference,
        IWS_EXPECTED_SIGNER_SHA256: process.env.IWS_ANDROID_SIGNER_FINGERPRINT ?? "",
        IWS_PROVEN_AAR_FILE: process.env.IWS_ANDROID_PROVEN_AAR ?? "",
        IWS_PROVEN_AAR_SHA256: process.env.IWS_ANDROID_PROVEN_AAR_SHA256 ?? ""
      }
    });
    child.once("error", reject);
    child.once("exit", code => code === 0 ? resolve() : reject(new Error("ANDROID_BUILD_FAILED")));
  });
  const apks = (await readdir(request.outputDirectory)).filter(value => value.endsWith(".apk"));
  if (apks.length !== 1) throw new Error("ANDROID_OUTPUT_INVALID");
  const filename = apks[0];
  const artifactPath = path.join(request.outputDirectory, filename);
  const info = await stat(artifactPath);
  return {
    artifactPath,
    filename,
    sizeBytes: BigInt(info.size),
    sha256: await sha256File(artifactPath),
    packageIdentity: "com.impactwiring.iwsconnectpoc",
    clientCheckpoint: request.clientCheckpoint,
    signer: {kind: "ANDROID", fingerprint: process.env.IWS_ANDROID_SIGNER_FINGERPRINT ?? ""}
  };
}

async function main() {
  const requestFile = process.env.IWS_PACKAGE_REQUEST_FILE;
  if (!requestFile) throw new Error("PACKAGE_REQUEST_INVALID");
  const request = await loadRequest(requestFile);
  const result = request.platform === "WINDOWS"
    ? await packageWindowsDevice({
        request,
        templatePath: process.env.IWS_WINDOWS_TEMPLATE ?? "",
        payloadRoot: process.env.IWS_WINDOWS_PAYLOAD_ROOT ?? request.checkpointPath
      })
    : request.platform === "ANDROID"
      ? await runAndroid(request)
      : (() => { throw new Error("PACKAGE_PLATFORM_INVALID"); })();
  process.stdout.write(JSON.stringify({...result, sizeBytes: result.sizeBytes.toString()}) + "\n");
}

main().catch(error => {
  const code = error instanceof Error && /^[A-Z0-9_]+$/.test(error.message)
    ? error.message
    : "PACKAGE_BUILD_FAILED";
  process.stderr.write(code + "\n");
  process.exitCode = 1;
});
