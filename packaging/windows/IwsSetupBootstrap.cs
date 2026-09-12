using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Threading;
using System.Windows.Forms;

internal static class IwsSetupBootstrap {
    private const string Magic = "IWSDEVICEV1";

    [STAThread] private static void Main() {
        string workspace = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
            "IWS", "Provisioning", Guid.NewGuid().ToString("N"));
        IwsSetupDiagnostics diagnostics = null;
        IwsSetupProgressForm progress = null;
        Thread worker = null;
        Exception installationFailure = null;
        try {
            string logRoot = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
                "IWS", "Logs");
            SetRestrictedDirectoryAcl(logRoot);
            string logPath = Path.Combine(logRoot, "setup.log");
            using (FileStream log = new FileStream(logPath, FileMode.OpenOrCreate, FileAccess.Write, FileShare.Read)) { }
            SetRestrictedFileAcl(logPath);
            diagnostics = new IwsSetupDiagnostics(delegate(string record) {
                File.AppendAllText(logPath, record + Environment.NewLine, Encoding.UTF8);
            });
            diagnostics.SetPhase(IwsSetupPhase.OverlayArchiveVerification);

            SetRestrictedDirectoryAcl(workspace);
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            progress = new IwsSetupProgressForm(diagnostics.SafePhaseName);
            IwsSetupDiagnostics activeDiagnostics = diagnostics;
            IwsSetupProgressForm activeProgress = progress;
            progress.Shown += delegate {
                worker = new Thread(delegate() {
                    try {
                        Install(workspace, activeDiagnostics, activeProgress);
                    }
                    catch (Exception exception) {
                        installationFailure = exception;
                    }
                    finally {
                        activeProgress.BeginInvoke(new Action(activeProgress.Close));
                    }
                });
                worker.IsBackground = true;
                worker.Start();
            };
            Application.Run(progress);
            if (worker != null) worker.Join();
            if (installationFailure != null) throw installationFailure;
        }
        catch {
            string phase = "OVERLAY_ARCHIVE_VERIFICATION";
            string code = "IWS-WIN-001";
            if (diagnostics != null) {
                phase = diagnostics.SafePhaseName;
                code = diagnostics.SafeCode;
                try { diagnostics.RecordFailure(); } catch { }
            }
            MessageBox.Show("IWS installation could not be completed.\r\n\r\nPhase: " + phase +
                "\r\nCode: " + code + "\r\n\r\nRequest a replacement installer.",
                "IWS", MessageBoxButtons.OK, MessageBoxIcon.Error);
            Environment.ExitCode = 1;
        }
        finally {
            try { if (Directory.Exists(workspace)) Directory.Delete(workspace, true); } catch { Environment.ExitCode = 1; }
        }
    }

    private static void Install(string workspace, IwsSetupDiagnostics diagnostics, IwsSetupProgressForm progress) {
        string archive = ExtractVerifiedOverlay(workspace);
        ExtractVerifiedArchive(archive, workspace);
        string keyPath = Path.Combine(workspace, "one-use.key");
        string payloadPath = Path.Combine(workspace, "device.runtime.json");
        File.WriteAllText(payloadPath, File.ReadAllText(Path.Combine(workspace, "device.json"))
            .Replace("__IWS_SETUP_KEY_PATH__", EscapeJson(keyPath)));

        SetPhase(diagnostics, progress, IwsSetupPhase.TransportInstallation);
        RunPowerShell(workspace, diagnostics, progress, "Install-IwsPrivateTransport.ps1",
            "-PayloadPath", payloadPath, "-BundleRoot", workspace);
        SetPhase(diagnostics, progress, IwsSetupPhase.WebViewShellInstallation);
        RunPowerShell(workspace, diagnostics, progress, "Install-IwsWebViewShellDevice.ps1",
            "-BundleRoot", workspace);
        SetPhase(diagnostics, progress, IwsSetupPhase.ClientLaunch);
        Process.Start(new ProcessStartInfo(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
            "IWS", "Client", "IwsClient.exe")) {UseShellExecute=true});
    }

    private static void SetRestrictedDirectoryAcl(string path) {
        Directory.CreateDirectory(path);
        DirectorySecurity security = new DirectorySecurity();
        security.SetAccessRuleProtection(true, false);
        InheritanceFlags inheritance = InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit;
        security.AddAccessRule(new FileSystemAccessRule(
            new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null), FileSystemRights.FullControl,
            inheritance, PropagationFlags.None, AccessControlType.Allow));
        security.AddAccessRule(new FileSystemAccessRule(
            new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null), FileSystemRights.FullControl,
            inheritance, PropagationFlags.None, AccessControlType.Allow));
        Directory.SetAccessControl(path, security);
    }

    private static void SetRestrictedFileAcl(string path) {
        FileSecurity security = new FileSecurity();
        security.SetAccessRuleProtection(true, false);
        security.AddAccessRule(new FileSystemAccessRule(
            new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null), FileSystemRights.FullControl,
            AccessControlType.Allow));
        security.AddAccessRule(new FileSystemAccessRule(
            new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null), FileSystemRights.FullControl,
            AccessControlType.Allow));
        File.SetAccessControl(path, security);
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

    private static void RunPowerShell(string root, IwsSetupDiagnostics diagnostics, IwsSetupProgressForm progress,
        string script, params string[] values) {
        StringBuilder args = new StringBuilder("-NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"").Append(Path.Combine(root, script)).Append("\"");
        foreach (string value in values) args.Append(" \"").Append(value.Replace("\"", "")).Append("\"");
        var phases = new ConcurrentQueue<IwsSetupPhase>();
        var startInfo = new ProcessStartInfo("powershell.exe", args.ToString()) {
            UseShellExecute=false, CreateNoWindow=true, RedirectStandardOutput=true, RedirectStandardError=true
        };
        using (Process process = new Process()) {
            process.StartInfo = startInfo;
            DataReceivedEventHandler receive = delegate(object sender, DataReceivedEventArgs eventArgs) {
                IwsSetupPhase phase;
                if (IwsSetupDiagnostics.TryParsePhaseMarker(eventArgs.Data, out phase)) phases.Enqueue(phase);
            };
            process.OutputDataReceived += receive;
            process.ErrorDataReceived += receive;
            if (!process.Start()) throw new InvalidOperationException();
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            while (!process.WaitForExit(100)) DrainPhases(phases, diagnostics, progress);
            process.WaitForExit();
            DrainPhases(phases, diagnostics, progress);
            if (process.ExitCode != 0) throw new InvalidOperationException();
        }
    }

    private static void DrainPhases(ConcurrentQueue<IwsSetupPhase> phases,
        IwsSetupDiagnostics diagnostics, IwsSetupProgressForm progress) {
        IwsSetupPhase phase;
        while (phases.TryDequeue(out phase)) SetPhase(diagnostics, progress, phase);
    }

    private static void SetPhase(IwsSetupDiagnostics diagnostics, IwsSetupProgressForm progress,
        IwsSetupPhase phase) {
        diagnostics.SetPhase(phase);
        progress.PostPhase(diagnostics.SafePhaseName);
    }

    private static string EscapeJson(string value) { return value.Replace("\\", "\\\\").Replace("\"", "\\\""); }
}

internal sealed class IwsSetupProgressForm : Form {
    private readonly Label phaseLabel;

    internal IwsSetupProgressForm(string phase) {
        Text = "IWS";
        Width = 520;
        Height = 160;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        StartPosition = FormStartPosition.CenterScreen;
        ControlBox = false;
        ShowInTaskbar = true;

        phaseLabel = new Label {Left = 24, Top = 20, Width = 456, Height = 40};
        Controls.Add(phaseLabel);
        Controls.Add(new ProgressBar {
            Left = 24, Top = 70, Width = 456, Height = 20,
            Style = ProgressBarStyle.Marquee, MarqueeAnimationSpeed = 30
        });
        SetPhase(phase);
    }

    internal void PostPhase(string phase) {
        BeginInvoke(new Action<string>(SetPhase), phase);
    }

    private void SetPhase(string phase) {
        phaseLabel.Text = "Installing IWS...\r\nPhase: " + phase;
    }
}
