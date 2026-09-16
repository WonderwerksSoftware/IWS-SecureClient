import {createHash} from "node:crypto";
import {readFile, writeFile, mkdir, mkdtemp, copyFile, chmod, lstat, rm, readdir, stat} from "node:fs/promises";
import {spawn} from "node:child_process";
import path from "node:path";

const HASH = "4eb4d7a2f5fe1a224362c68f6c8129502247635711db1970cc85ed921896780d";
const PACKAGE_VERSION = "1.0.2";
export function linuxPackageScripts() {
  return {
    postInstall: "/usr/bin/python3 -I /usr/lib/iws-client/runtime.py prepare",
    remove: "if [ \"$1\" = remove ] || [ \"$1\" = 0 ]; then systemctl stop iws-client.service; systemctl disable iws-client.service; fi"
  };
}
async function run(command, args) {
  await new Promise((resolve, reject) => {
    const child = spawn(command, args, {env: {PATH: "/usr/sbin:/usr/bin:/sbin:/bin", LANG: "C.UTF-8"}, stdio: process.env.IWS_PACKAGE_DEBUG === "1" ? "inherit" : "ignore"});
    child.once("error", () => reject(new Error("LINUX_PACKAGE_TOOL_FAILED")));
    child.once("exit", code => code === 0 ? resolve() : reject(new Error("LINUX_PACKAGE_TOOL_FAILED")));
  });
}

export async function packageLinuxDevice(request, transportFile, rpmBuilder = "/usr/bin/rpmbuild") {
  if (!["LINUX_DEBIAN", "LINUX_FEDORA"].includes(request.platform)) throw new Error("LINUX_PLATFORM_INVALID");
  const transport = await readFile(transportFile).catch(() => Buffer.alloc(0));
  if (createHash("sha256").update(transport).digest("hex") !== HASH) throw new Error("LINUX_TRANSPORT_INVALID");
  const manifest = JSON.parse(await readFile(request.manifestPath, "utf8"));
  if (!/^[a-z0-9]{1,40}$/.test(request.deviceId) || !Number.isInteger(request.generation) || request.generation < 1 ||
      request.clientHostname !== `iws-${request.deviceId}-g${request.generation}` ||
      ["deviceId", "generation", "platform", "clientHostname", "clientCheckpoint"].some(k => manifest[k] !== request[k]) ||
      !Number.isFinite(Date.parse(manifest.expiresAt)) || Date.parse(manifest.expiresAt) <= Date.now()) {
    throw new Error("LINUX_MANIFEST_INVALID");
  }
  const keyInfo = await lstat(request.setupKeyPath);
  if (!keyInfo.isFile() || keyInfo.isSymbolicLink() || (keyInfo.mode & 0o777) !== 0o600) throw new Error("LINUX_BOOTSTRAP_INVALID");
  const work = await mkdtemp(path.join(request.outputDirectory, ".linux-"));
  try {
    const tree = path.join(work, "root");
    async function file(relative, bytes, mode = 0o644) {
      const target = path.join(tree, relative);
      await mkdir(path.dirname(target), {recursive: true, mode: 0o755});
      // Build jobs run under umask 0077. Native package payload permissions
      // must nevertheless be explicit, especially existing /usr directories.
      for (let directory = path.dirname(target); ; directory = path.dirname(directory)) {
        await chmod(directory, 0o755);
        if (directory === tree) break;
      }
      await writeFile(target, bytes, {mode});
      await chmod(target, mode);
    }
    async function source(from, to, mode = 0o644) {
      const full = path.join(request.checkpointPath, from);
      const info = await lstat(full);
      if (!info.isFile() || info.isSymbolicLink()) throw new Error("LINUX_SOURCE_INVALID");
      await file(to, await readFile(full), mode);
    }
    for (const name of ["namespace.py", "runtime.py", "setup_ui.py", "shell.py", "shell_policy.py"]) await source(`linux/${name}`, `usr/lib/iws-client/${name}`, 0o755);
    await source("linux/iws-setup-helper", "usr/lib/iws-client/iws-setup-helper", 0o755);
    await source("linux/iws", "usr/bin/iws", 0o755);
    await source("linux/iws-client.service", "usr/lib/systemd/system/iws-client.service");
    await source("linux/iws-client.sudoers", "etc/sudoers.d/iws-client", 0o440);
    await source("linux/com.impactwiring.iws-client.policy", "usr/share/polkit-1/actions/com.impactwiring.iws-client.policy");
    await source("linux/iws.desktop", "usr/share/applications/iws.desktop");
    await source("branding/iws-icon-source.svg", "usr/share/icons/hicolor/scalable/apps/iws.svg");
    for (const mark of ["iws-mark-small.png", "iws-mark-full.png"])
      await source(`branding/${mark}`, `usr/lib/iws-client/${mark}`);
    await source("windows/webview2/iws-production-root-ca.crt", "usr/lib/iws-client/iws-root-ca.crt");
    await source("third_party/netbird/LICENSE", "usr/share/doc/iws-secure-client/netbird-LICENSE");
    await source("third_party/netbird/NOTICE.md", "usr/share/doc/iws-secure-client/netbird-NOTICE.md");
    await source("third_party/THIRD_PARTY_NOTICES.md", "usr/share/doc/iws-secure-client/THIRD_PARTY_NOTICES.md");
    await file("usr/lib/iws-client/iws-transport", transport, 0o755);
    await file("usr/share/iws-client/bootstrap/device.json", JSON.stringify(manifest), 0o600);
    await file("usr/share/iws-client/bootstrap/one-use.key", await readFile(request.setupKeyPath), 0o600);
    await chmod(path.join(tree, "usr/share/iws-client/bootstrap"), 0o700);
    const suffix = request.platform === "LINUX_DEBIAN" ? "deb" : "rpm";
    const filename = `IWS-${request.deviceId}-g${request.generation}.${suffix}`;
    const artifactPath = path.join(request.outputDirectory, filename);
    const scripts = linuxPackageScripts();
    if (suffix === "deb") {
      await file("DEBIAN/control", `Package: iws-secure-client\nVersion: ${PACKAGE_VERSION}-${request.generation}\nArchitecture: amd64\nMaintainer: IWS\nDescription: Managed private IWS client\nDepends: python3, python3-gi, gir1.2-gtk-3.0, gir1.2-webkit2-4.1, iproute2, nftables, passt, sudo, policykit-1, util-linux, ca-certificates, openssl, libnss3-tools, systemd\n`);
      await file("DEBIAN/postinst", `#!/bin/sh\nset -eu\n${scripts.postInstall}\n`, 0o755);
      await file("DEBIAN/prerm", `#!/bin/sh\nset -eu\n${scripts.remove}\n`, 0o755);
      await run("/usr/bin/dpkg-deb", ["--root-owner-group", "--build", tree, artifactPath]);
    } else {
      const top = path.join(work, "rpm");
      for (const dir of ["BUILD", "BUILDROOT", "RPMS", "SOURCES", "SPECS", "SRPMS", "TMP"]) await mkdir(path.join(top, dir), {recursive: true});
      // Paths originate from a private mkdtemp, not from device names or keys.
      const quote = value => "'" + value.replaceAll("'", "'\\''") + "'";
      const spec = `%global __os_install_post %{nil}\nName: iws-secure-client\nVersion: ${PACKAGE_VERSION}\nRelease: ${request.generation}\nSummary: Managed private IWS client\nLicense: Proprietary AND BSD-3-Clause AND MPL-2.0\nBuildArch: x86_64\nRequires: python3, iproute, nftables, passt, sudo, polkit, util-linux, ca-certificates, openssl, nss-tools, systemd, python3-gobject, gtk3, webkit2gtk4.1\n%description\nManaged private IWS client\n%install\nmkdir -p %{buildroot}\ncp -a ${quote(tree)}/. %{buildroot}/\n%post\n${scripts.postInstall}\n%preun\n${scripts.remove}\n%files\n%defattr(-,root,root,-)\n/usr/bin/iws\n/usr/lib/iws-client\n/usr/lib/systemd/system/iws-client.service\n/etc/sudoers.d/iws-client\n/usr/share/applications/iws.desktop\n/usr/share/polkit-1/actions/com.impactwiring.iws-client.policy\n/usr/share/iws-client\n/usr/share/doc/iws-secure-client\n`;
      const specPath = path.join(top, "SPECS/iws.spec");
      await writeFile(specPath, spec + "/usr/share/icons/hicolor/scalable/apps/iws.svg\n", {mode: 0o600});
      await run(rpmBuilder, ["-bb", "--define", `_topdir ${top}`, "--define", `_tmppath ${path.join(top, "TMP")}`, specPath]);
      const rpms = await readdir(path.join(top, "RPMS/x86_64"));
      if (rpms.length !== 1 || !rpms[0].endsWith(".rpm")) throw new Error("LINUX_PACKAGE_OUTPUT_INVALID");
      await copyFile(path.join(top, "RPMS/x86_64", rpms[0]), artifactPath);
    }
    await chmod(artifactPath, 0o600);
    return {artifactPath, filename, sizeBytes: BigInt((await stat(artifactPath)).size),
      sha256: createHash("sha256").update(await readFile(artifactPath)).digest("hex"),
      packageIdentity: "iws-secure-client", clientCheckpoint: request.clientCheckpoint, signer: null};
  } finally {
    // A cleanup failure rejects publication, including failure-path bootstrap files.
    await rm(work, {recursive: true, force: true});
  }
}
