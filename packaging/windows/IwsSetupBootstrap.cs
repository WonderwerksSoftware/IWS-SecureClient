using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Windows.Forms;

internal static class IwsSetupBootstrap {
    private const string Magic = "IWSDEVICEV1";
    [STAThread] private static void Main() {
        string workspace = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
            "IWS", "Provisioning", Guid.NewGuid().ToString("N"));
        try {
            Directory.CreateDirectory(workspace);
            string archive = ExtractVerifiedOverlay(workspace);
            ExtractVerifiedArchive(archive, workspace);
            string keyPath = Path.Combine(workspace, "one-use.key");
            string payloadPath = Path.Combine(workspace, "device.runtime.json");
            File.WriteAllText(payloadPath, File.ReadAllText(Path.Combine(workspace, "device.json"))
                .Replace("__IWS_SETUP_KEY_PATH__", EscapeJson(keyPath)));
            RunPowerShell(workspace, "Install-IwsPrivateTransport.ps1", "-PayloadPath", payloadPath, "-BundleRoot", workspace);
            RunPowerShell(workspace, "Install-IwsWebViewShellDevice.ps1", "-BundleRoot", workspace);
            Process.Start(new ProcessStartInfo(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
                "IWS", "Client", "IwsClient.exe")) {UseShellExecute=true});
        } catch {
            MessageBox.Show("IWS installation could not be completed. Request a replacement installer.",
                "IWS", MessageBoxButtons.OK, MessageBoxIcon.Error);
            Environment.ExitCode = 1;
        } finally {
            try { if (Directory.Exists(workspace)) Directory.Delete(workspace, true); } catch { Environment.ExitCode = 1; }
        }
    }
    private static string ExtractVerifiedOverlay(string workspace) {
        byte[] executable = File.ReadAllBytes(Process.GetCurrentProcess().MainModule.FileName);
        int trailerSize = Magic.Length + 8 + 32;
        if (executable.Length < trailerSize) throw new InvalidDataException();
        int trailer = executable.Length - trailerSize;
        if (Encoding.ASCII.GetString(executable, trailer, Magic.Length) != Magic) throw new InvalidDataException();
        long length = BitConverter.ToInt64(executable, trailer + Magic.Length);
        long start = trailer - length;
        if (length < 1 || start < 2 || length > Int32.MaxValue) throw new InvalidDataException();
        byte[] payload = new byte[(int)length]; Buffer.BlockCopy(executable, (int)start, payload, 0, payload.Length);
        using (SHA256 sha = SHA256.Create()) {
            byte[] actual = sha.ComputeHash(payload);
            for (int i = 0; i < 32; i++) if (actual[i] != executable[trailer + Magic.Length + 8 + i]) throw new InvalidDataException();
        }
        string archive = Path.Combine(workspace, "payload.zip"); File.WriteAllBytes(archive, payload); return archive;
    }
    private static void ExtractVerifiedArchive(string archive, string workspace) {
        using (ZipArchive zip = ZipFile.OpenRead(archive)) foreach (ZipArchiveEntry entry in zip.Entries) {
            string target = Path.GetFullPath(Path.Combine(workspace, entry.FullName));
            if (!target.StartsWith(Path.GetFullPath(workspace) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException();
            if (String.IsNullOrEmpty(entry.Name)) { Directory.CreateDirectory(target); continue; }
            Directory.CreateDirectory(Path.GetDirectoryName(target)); entry.ExtractToFile(target, true);
        }
        VerifyManifest(workspace, Path.Combine(workspace, "BUNDLE-MANIFEST.sha256"));
    }
    private static void VerifyManifest(string root, string manifest) {
        foreach (string line in File.ReadAllLines(manifest)) {
            string[] parts = line.Split(new[] {"  "}, 2, StringSplitOptions.None); if (parts.Length != 2) throw new InvalidDataException();
            string file = Path.GetFullPath(Path.Combine(root, parts[1].Replace('/', Path.DirectorySeparatorChar)));
            if (!file.StartsWith(Path.GetFullPath(root) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException();
            using (SHA256 sha = SHA256.Create()) if (!String.Equals(BitConverter.ToString(sha.ComputeHash(File.ReadAllBytes(file))).Replace("-", ""), parts[0], StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException();
        }
    }
    private static void RunPowerShell(string root, string script, params string[] values) {
        StringBuilder args = new StringBuilder("-NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"").Append(Path.Combine(root, script)).Append("\"");
        foreach (string value in values) args.Append(" \"").Append(value.Replace("\"", "")).Append("\"");
        using (Process process = Process.Start(new ProcessStartInfo("powershell.exe", args.ToString()) {UseShellExecute=false, CreateNoWindow=true})) {
            process.WaitForExit(); if (process.ExitCode != 0) throw new InvalidOperationException();
        }
    }
    private static string EscapeJson(string value) { return value.Replace("\\", "\\\\").Replace("\"", "\\\""); }
}
