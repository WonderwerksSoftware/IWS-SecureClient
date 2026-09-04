import {createHash} from "node:crypto";
import {createReadStream, createWriteStream} from "node:fs";
import {
  appendFile,
  cp,
  mkdtemp,
  readFile,
  readdir,
  rm,
  stat,
  writeFile
} from "node:fs/promises";
import path from "node:path";
import {spawn} from "node:child_process";
import {pipeline} from "node:stream/promises";

const MAGIC = Buffer.from("IWSDEVICEV1", "ascii");
const TRAILER_SIZE = MAGIC.length + 8 + 32;

async function sha256File(file) {
  const digest = createHash("sha256");
  for await (const chunk of createReadStream(file)) digest.update(chunk);
  return digest.digest();
}

async function filesUnder(root, current = root) {
  const result = [];
  for (const entry of await readdir(current, {withFileTypes: true})) {
    const full = path.join(current, entry.name);
    if (entry.isDirectory()) result.push(...await filesUnder(root, full));
    else if (entry.isFile()) result.push(path.relative(root, full).split(path.sep).join("/"));
  }
  return result.sort();
}

async function zipDirectory(root, output) {
  await new Promise((resolve, reject) => {
    const child = spawn("/usr/bin/zip", ["-q", "-0", "-r", output, "."], {
      cwd: root,
      env: {PATH: "/usr/bin:/bin"},
      stdio: "ignore"
    });
    child.once("error", reject);
    child.once("exit", code => code === 0 ? resolve() : reject(new Error("WINDOWS_ZIP_FAILED")));
  });
}

export function parseWindowsOverlay(bytes) {
  if (bytes.length < TRAILER_SIZE) throw new Error("WINDOWS_OVERLAY_INVALID");
  const trailer = bytes.subarray(bytes.length - TRAILER_SIZE);
  const magic = trailer.subarray(0, MAGIC.length).toString("ascii");
  if (magic !== MAGIC.toString("ascii")) throw new Error("WINDOWS_OVERLAY_INVALID");
  const length = trailer.readBigUInt64LE(MAGIC.length);
  if (length > BigInt(bytes.length - TRAILER_SIZE)) throw new Error("WINDOWS_OVERLAY_INVALID");
  const start = bytes.length - TRAILER_SIZE - Number(length);
  const payload = bytes.subarray(start, bytes.length - TRAILER_SIZE);
  const digest = createHash("sha256").update(payload).digest();
  if (!digest.equals(trailer.subarray(MAGIC.length + 8))) throw new Error("WINDOWS_OVERLAY_INVALID");
  return {magic, payload, template: bytes.subarray(0, start)};
}

export async function packageWindowsDevice({request, templatePath, payloadRoot = request.checkpointPath}) {
  if (request.platform !== "WINDOWS") throw new Error("WINDOWS_PLATFORM_INVALID");
  const template = await readFile(templatePath);
  if (template.subarray(0, 2).toString("ascii") !== "MZ") throw new Error("WINDOWS_TEMPLATE_INVALID");
  const staging = await mkdtemp(path.join(request.outputDirectory, ".windows-"));
  const payloadZip = path.join(request.outputDirectory, ".payload.zip");
  try {
    await cp(path.join(payloadRoot, "windows-payload"), staging, {recursive: true});
    await cp(request.setupKeyPath, path.join(staging, "one-use.key"));
    const manifest = JSON.parse(await readFile(request.manifestPath, "utf8"));
    await writeFile(path.join(staging, "device.json"), JSON.stringify({
      ...manifest,
      device_name: request.clientHostname,
      management_server: "https://api.netbird.io:443",
      setup_key_file: "__IWS_SETUP_KEY_PATH__",
      iws_entrypoint: "http://100.83.246.85:443/"
    }), {mode: 0o600});

    const shellExcluded = new Set([
      "one-use.key",
      "device.json",
      "iws-transport.exe",
      "wintun.dll",
      "Install-IwsPrivateTransport.ps1",
      "IwsPrivateTransport.psm1",
      "pins.psd1",
      "BUNDLE-MANIFEST.sha256",
      "SHELL-MANIFEST.sha256"
    ]);
    const shellLines = [];
    for (const member of (await filesUnder(staging)).filter(value => !shellExcluded.has(value))) {
      shellLines.push(`${(await sha256File(path.join(staging, member))).toString("hex")}  ${member}`);
    }
    await writeFile(path.join(staging, "SHELL-MANIFEST.sha256"), shellLines.join("\n") + "\n", {mode: 0o600});

    const bundleLines = [];
    for (const member of (await filesUnder(staging)).filter(value => value !== "BUNDLE-MANIFEST.sha256")) {
      bundleLines.push(`${(await sha256File(path.join(staging, member))).toString("hex")}  ${member}`);
    }
    await writeFile(path.join(staging, "BUNDLE-MANIFEST.sha256"), bundleLines.join("\n") + "\n", {mode: 0o600});
    await zipDirectory(staging, payloadZip);

    const payloadInfo = await stat(payloadZip);
    const payloadDigest = await sha256File(payloadZip);
    const trailer = Buffer.alloc(TRAILER_SIZE);
    MAGIC.copy(trailer, 0);
    trailer.writeBigUInt64LE(BigInt(payloadInfo.size), MAGIC.length);
    payloadDigest.copy(trailer, MAGIC.length + 8);
    const filename = `IWS-Setup-${request.deviceId.replace(/[^A-Za-z0-9_-]/g, "-")}-g${request.generation}.exe`;
    const artifactPath = path.join(request.outputDirectory, filename);
    await writeFile(artifactPath, template, {mode: 0o600});
    await pipeline(createReadStream(payloadZip), createWriteStream(artifactPath, {flags: "a"}));
    await appendFile(artifactPath, trailer);
    const info = await stat(artifactPath);
    return {
      artifactPath,
      filename,
      sizeBytes: BigInt(info.size),
      sha256: (await sha256File(artifactPath)).toString("hex"),
      packageIdentity: "IWS-Setup",
      clientCheckpoint: request.clientCheckpoint,
      signer: null
    };
  } finally {
    await rm(staging, {recursive: true, force: true});
    await rm(payloadZip, {force: true});
  }
}
