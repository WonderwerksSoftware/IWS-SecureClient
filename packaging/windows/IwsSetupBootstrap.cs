using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.ServiceProcess;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

internal static class IwsSetupBootstrap {
    private const string Magic = "IWSDEVICEV1";
    private const string ReceiptPath = @"C:\ProgramData\IWS\Install\receipt.json";
    private const string StateRoot = @"C:\ProgramData\IWS\Transport";
    private const string TransportPath = @"C:\Program Files\IWS\Transport\iws-transport.exe";
    private const string ClientPath = @"C:\Program Files\IWS\Client\IwsClient.exe";
    private const string ServiceName = "IWSPrivateTransport";

    [STAThread] private static void Main() {
        string workspace = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
            "IWS", "Provisioning", Guid.NewGuid().ToString("N"));
        IwsSetupDiagnostics diagnostics = null;
        try {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
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
            if (Environment.OSVersion.Platform != PlatformID.Win32NT || Environment.OSVersion.Version.Major < 10)
                throw new InvalidOperationException();
            SetRestrictedDirectoryAcl(workspace);
            string archive = ExtractVerifiedOverlay(workspace);
            ExtractVerifiedArchive(archive, workspace);
            string deviceJson = File.ReadAllText(Path.Combine(workspace, "device.json"));
            IwsSetupManifest manifest = IwsSetupMetadata.ParseManifest(deviceJson);
            IwsSetupEvidence evidence = InspectInstallation(workspace, manifest);
            IwsSetupDecision decision = IwsSetupStateMachine.Evaluate(evidence);

            using (IwsSetupWelcomeForm welcome = new IwsSetupWelcomeForm(decision, manifest)) {
                Application.Run(welcome);
                if (welcome.SelectedAction == IwsSetupAction.Cancel) return;
                if (welcome.SelectedAction == IwsSetupAction.OpenIws) { LaunchClientAsEmployee(); return; }
                bool cleanConfirmed = false;
                bool reassignmentConfirmed = false;
                if (welcome.SelectedAction == IwsSetupAction.CleanReinstall) {
                    cleanConfirmed = MessageBox.Show(
                        "Clean reinstall removes the active local IWS identity and IWS data before enrolling a replacement. " +
                        "The previous IWS state will be placed in protected recovery storage and restored if setup fails. Continue?",
                        "IWS Clean Reinstall", MessageBoxButtons.YesNo, MessageBoxIcon.Warning,
                        MessageBoxDefaultButton.Button2) == DialogResult.Yes;
                    if (cleanConfirmed && decision.RequiresReassignmentConfirmation) {
                        reassignmentConfirmed = MessageBox.Show(
                            "This installer cannot prove that the existing identity belongs to the same device generation. " +
                            "Confirm that this device is intentionally being reassigned with this fresh installer.",
                            "Confirm IWS Device Reassignment", MessageBoxButtons.YesNo, MessageBoxIcon.Warning,
                            MessageBoxDefaultButton.Button2) == DialogResult.Yes;
                    }
                    if (!IwsSetupStateMachine.CanBeginClean(decision, cleanConfirmed, reassignmentConfirmed)) return;
                }
                IwsSetupAction action = welcome.SelectedAction;
                bool retryUsed = false;
                while (true) {
                    try {
                        bool ready = RunInstallation(workspace, deviceJson, manifest, decision, action, diagnostics);
                        using (IwsSetupCompleteForm complete = new IwsSetupCompleteForm(ready)) Application.Run(complete);
                        return;
                    }
                    catch {
                        try { diagnostics.RecordFailure(); } catch { }
                        IwsFailureAction failureAction = IwsSetupStateMachine.GetFailureAction(diagnostics.CurrentFailure);
                        string message = BuildFailureMessage(diagnostics, failureAction, retryUsed);
                        if (failureAction == IwsFailureAction.FreshInstaller || retryUsed ||
                            MessageBox.Show(message, "IWS Setup", MessageBoxButtons.RetryCancel,
                                MessageBoxIcon.Error) != DialogResult.Retry) {
                            if (failureAction == IwsFailureAction.FreshInstaller || retryUsed)
                                MessageBox.Show(message, "IWS Setup", MessageBoxButtons.OK, MessageBoxIcon.Error);
                            Environment.ExitCode = 1;
                            return;
                        }
                        retryUsed = true;
                        evidence = InspectInstallation(workspace, manifest);
                        decision = IwsSetupStateMachine.Evaluate(evidence);
                        action = decision.DefaultAction;
                        if (action != IwsSetupAction.Install && action != IwsSetupAction.Repair) {
                            MessageBox.Show("Retry cannot continue safely with the detected IWS state. " +
                                "No identity was automatically replaced. Request a fresh device installer or contact IWS support.",
                                "IWS Setup", MessageBoxButtons.OK, MessageBoxIcon.Error);
                            Environment.ExitCode = 1;
                            return;
                        }
                    }
                }
            }
        }
        catch {
            string phase = diagnostics == null ? "OVERLAY_ARCHIVE_VERIFICATION" : diagnostics.SafePhaseName;
            string code = diagnostics == null ? "IWS-WIN-001" : diagnostics.SafeCode;
            try { if (diagnostics != null) diagnostics.RecordFailure(); } catch { }
            MessageBox.Show("IWS setup could not start safely.\r\n\r\nPhase: " + phase + "\r\nCode: " + code +
                "\r\n\r\nNo existing identity was intentionally replaced. Use a current installer for this device or contact IWS support.",
                "IWS Setup", MessageBoxButtons.OK, MessageBoxIcon.Error);
            Environment.ExitCode = 1;
        }
        finally {
            try { if (Directory.Exists(workspace)) Directory.Delete(workspace, true); }
            catch { Environment.ExitCode = 1; }
        }
    }

    private static bool RunInstallation(string workspace, string deviceJson, IwsSetupManifest manifest,
        IwsSetupDecision decision, IwsSetupAction action, IwsSetupDiagnostics diagnostics) {
        string keyPath = Path.Combine(workspace, "one-use.key");
        string placeholder = "__IWS_SETUP_KEY_PATH__";
        if (deviceJson.IndexOf(placeholder, StringComparison.Ordinal) < 0) throw new InvalidDataException();
        string payloadPath = Path.Combine(workspace, "device.runtime.json");
        File.WriteAllText(payloadPath, deviceJson.Replace(placeholder, EscapeJson(keyPath)), Encoding.UTF8);
        string recoveryRoot = null;
        bool clean = action == IwsSetupAction.CleanReinstall;
        try {
            if (clean) {
                if (manifest.ExpiresUtc <= DateTime.UtcNow || !File.Exists(keyPath) ||
                    new FileInfo(keyPath).Length < 1) {
                    diagnostics.AcceptChildOutput("IWS_SETUP_ERROR=ENROLLMENT_CREDENTIAL_REJECTED");
                    throw new InvalidDataException();
                }
                recoveryRoot = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
                    "IWS", "Recovery", DateTime.UtcNow.ToString("yyyyMMddTHHmmssZ") + "-" + Guid.NewGuid().ToString("N"));
                RunProgressScript(workspace, diagnostics, "Preparing protected rollback...",
                    "Backup-IwsClientForCleanReinstall.ps1", "-BundleRoot", workspace,
                    "-RecoveryRoot", recoveryRoot);
            }
            bool enroll = clean || action == IwsSetupAction.Install ||
                (action == IwsSetupAction.Repair && decision.UseEnrollmentKey);
            if (!enroll && action == IwsSetupAction.Repair &&
                decision.State == IwsDetectedState.UnknownIdentity) {
                RunProgressScript(workspace, diagnostics, "Starting and inspecting private connectivity...",
                    "Install-IwsPrivateTransport.ps1", "-PayloadPath", payloadPath,
                    "-BundleRoot", workspace, "-Mode", "Repair", "-PreserveSetupKey");
            }
            else {
                RunProgressScript(workspace, diagnostics, enroll ? "Enrolling private connectivity..." :
                    "Repairing private connectivity...", "Install-IwsPrivateTransport.ps1",
                    "-PayloadPath", payloadPath, "-BundleRoot", workspace, "-Mode", enroll ? "Enroll" : "Repair");
            }
            if (!enroll && action == IwsSetupAction.Repair &&
                decision.State == IwsDetectedState.UnknownIdentity) {
                IwsSetupDecision afterStart = IwsSetupStateMachine.Evaluate(InspectInstallation(workspace, manifest));
                if (afterStart.State == IwsDetectedState.PartialNeedsLogin && afterStart.UseEnrollmentKey) {
                    RunProgressScript(workspace, diagnostics, "Resuming private connectivity enrollment...",
                        "Install-IwsPrivateTransport.ps1", "-PayloadPath", payloadPath,
                        "-BundleRoot", workspace, "-Mode", "Enroll");
                    enroll = true;
                }
            }
            RunProgressScript(workspace, diagnostics, "Installing the IWS app...",
                "Install-IwsWebViewShellDevice.ps1", "-BundleRoot", workspace);
            IwsSetupEvidence finalEvidence = InspectInstallation(workspace, manifest);
            if (!enroll && (decision.State == IwsDetectedState.ExistingIdentity ||
                    decision.State == IwsDetectedState.LegacyIdentity) &&
                finalEvidence.NativeIdentityStatus == IwsNativeIdentityStatus.NeedsLogin) {
                diagnostics.AcceptChildOutput("IWS_SETUP_ERROR=IDENTITY_NEEDS_FRESH_INSTALLER");
                throw new InvalidOperationException();
            }
            return enroll || finalEvidence.NativeIdentityStatus == IwsNativeIdentityStatus.Registered;
        }
        catch {
            if (clean && recoveryRoot != null &&
                File.Exists(Path.Combine(recoveryRoot, "rollback.ready"))) {
                try {
                    RunProgressScript(workspace, diagnostics, "Restoring the previous IWS installation...",
                        "Restore-IwsClientAfterFailedClean.ps1", "-BundleRoot", workspace,
                        "-RecoveryRoot", recoveryRoot);
                }
                catch { throw new InvalidOperationException(); }
            }
            else if (clean && recoveryRoot != null && Directory.Exists(recoveryRoot)) {
                try { Directory.Delete(recoveryRoot, true); } catch { }
            }
            throw;
        }
    }

    private static IwsSetupEvidence InspectInstallation(string workspace, IwsSetupManifest manifest) {
        IwsSetupEvidence evidence = new IwsSetupEvidence {
            ArtifactDeviceId = manifest.DeviceId, ArtifactGeneration = manifest.Generation,
            ArtifactExpiresUtc = manifest.ExpiresUtc, NowUtc = DateTime.UtcNow,
            StatePresent = Directory.Exists(StateRoot),
            ShellAvailable = FileHashEquals(ClientPath, Path.Combine(workspace, "IwsClient.exe"))
        };
        ServiceController service = null;
        try { service = new ServiceController(ServiceName); ServiceControllerStatus ignored = service.Status; }
        catch { if (service != null) service.Dispose(); service = null; }
        evidence.ServicePresent = service != null;
        if (service != null) {
            using (service) {
                evidence.ServiceRunning = service.Status == ServiceControllerStatus.Running;
                string commandLine = ReadServiceImagePath(ServiceName);
                evidence.ServiceOwned = CommandPathOwned(commandLine, TransportPath) &&
                    FileHashEquals(TransportPath, Path.Combine(workspace, "iws-transport.exe"));
                if (evidence.ServiceOwned && evidence.ServiceRunning)
                    evidence.NativeIdentityStatus = ReadNativeIdentityStatus(TransportPath);
            }
        }
        if (File.Exists(ReceiptPath)) {
            IwsInstallationReceipt receipt = IwsSetupMetadata.ParseReceipt(File.ReadAllText(ReceiptPath));
            evidence.ReceiptPresent = true; evidence.ReceiptDeviceId = receipt.DeviceId;
            evidence.ReceiptGeneration = receipt.Generation;
        }
        return evidence;
    }

    private static string ReadServiceImagePath(string serviceName) {
        using (RegistryKey key = Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Services\" + serviceName))
            return key == null ? null : key.GetValue("ImagePath") as string;
    }

    private static bool CommandPathOwned(string commandLine, string expectedPath) {
        if (String.IsNullOrWhiteSpace(commandLine)) return false;
        string value = Environment.ExpandEnvironmentVariables(commandLine).Trim();
        string executable;
        if (value.StartsWith("\"", StringComparison.Ordinal)) {
            int end = value.IndexOf('"', 1); if (end < 2) return false;
            executable = value.Substring(1, end - 1);
        }
        else { int end = value.IndexOf(' '); executable = end < 0 ? value : value.Substring(0, end); }
        try { return String.Equals(Path.GetFullPath(executable), Path.GetFullPath(expectedPath),
            StringComparison.OrdinalIgnoreCase); }
        catch { return false; }
    }

    private static IwsNativeIdentityStatus ReadNativeIdentityStatus(string executable) {
        string output; int exitCode;
        if (!RunNativeProbe(executable, "--daemon-addr npipe://iws-private-transport status", 10000,
                out output, out exitCode)) return IwsNativeIdentityStatus.Unknown;
        if (output.IndexOf("Daemon status: NeedsLogin", StringComparison.Ordinal) >= 0)
            return IwsNativeIdentityStatus.NeedsLogin;
        if (exitCode == 0 && output.IndexOf("NetBird IP:", StringComparison.Ordinal) >= 0)
            return IwsNativeIdentityStatus.Registered;
        return IwsNativeIdentityStatus.Unknown;
    }

    private static bool RunNativeProbe(string executable, string arguments, int timeoutMilliseconds,
        out string output, out int exitCode) {
        StringBuilder captured = new StringBuilder();
        using (Process process = new Process()) {
            process.StartInfo = new ProcessStartInfo(executable, arguments) {
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true
            };
            DataReceivedEventHandler receive = delegate(object sender, DataReceivedEventArgs eventArgs) {
                if (eventArgs.Data != null) lock (captured) captured.AppendLine(eventArgs.Data);
            };
            process.OutputDataReceived += receive; process.ErrorDataReceived += receive;
            if (!process.Start()) { output = ""; exitCode = -1; return false; }
            process.BeginOutputReadLine(); process.BeginErrorReadLine();
            if (!process.WaitForExit(timeoutMilliseconds)) {
                try { process.Kill(); } catch { }
                output = ""; exitCode = -1; return false;
            }
            process.WaitForExit(); output = captured.ToString(); exitCode = process.ExitCode; return true;
        }
    }

    private static bool FileHashEquals(string first, string second) {
        if (!File.Exists(first) || !File.Exists(second)) return false;
        using (SHA256 sha = SHA256.Create()) {
            byte[] left = sha.ComputeHash(File.ReadAllBytes(first));
            byte[] right = sha.ComputeHash(File.ReadAllBytes(second));
            if (left.Length != right.Length) return false;
            for (int index = 0; index < left.Length; index++) if (left[index] != right[index]) return false;
            return true;
        }
    }

    private static void RunProgressScript(string root, IwsSetupDiagnostics diagnostics, string initialText,
        string script, params string[] values) {
        Exception failure = null;
        using (IwsSetupProgressForm progress = new IwsSetupProgressForm(initialText)) {
            Thread worker = null;
            progress.Shown += delegate {
                worker = new Thread(delegate() {
                    try { RunPowerShell(root, diagnostics, progress, script, values); }
                    catch (Exception exception) { failure = exception; }
                    finally { progress.BeginInvoke(new Action(progress.Close)); }
                });
                worker.IsBackground = true; worker.Start();
            };
            Application.Run(progress); if (worker != null) worker.Join();
        }
        if (failure != null) throw failure;
    }

    private static void RunPowerShell(string root, IwsSetupDiagnostics diagnostics, IwsSetupProgressForm progress,
        string script, params string[] values) {
        StringBuilder args = new StringBuilder("-NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"")
            .Append(Path.Combine(root, script)).Append("\"");
        foreach (string value in values) args.Append(" \"").Append(value.Replace("\"", "")).Append("\"");
        var lines = new ConcurrentQueue<string>();
        var startInfo = new ProcessStartInfo("powershell.exe", args.ToString()) {
            UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true
        };
        using (Process process = new Process()) {
            process.StartInfo = startInfo;
            DataReceivedEventHandler receive = delegate(object sender, DataReceivedEventArgs eventArgs) {
                if (eventArgs.Data != null) lines.Enqueue(eventArgs.Data);
            };
            process.OutputDataReceived += receive; process.ErrorDataReceived += receive;
            if (!process.Start()) throw new InvalidOperationException();
            process.BeginOutputReadLine(); process.BeginErrorReadLine();
            DateTime deadline = DateTime.UtcNow.AddMinutes(10);
            while (!process.WaitForExit(100)) {
                DrainSignals(lines, diagnostics, progress);
                if (DateTime.UtcNow >= deadline) {
                    try { process.Kill(); } catch { }
                    throw new System.TimeoutException();
                }
            }
            process.WaitForExit(); DrainSignals(lines, diagnostics, progress);
            if (process.ExitCode != 0) throw new InvalidOperationException();
        }
    }

    private static void DrainSignals(ConcurrentQueue<string> lines, IwsSetupDiagnostics diagnostics,
        IwsSetupProgressForm progress) {
        string line;
        while (lines.TryDequeue(out line)) {
            IwsSetupPhase phase; IwsSetupFailure failure;
            if (IwsSetupDiagnostics.TryParsePhaseMarker(line, out phase)) {
                diagnostics.AcceptChildOutput(line); progress.PostPhase(diagnostics.SafePhaseName);
            }
            else if (IwsSetupDiagnostics.TryParseFailureMarker(line, out failure)) diagnostics.AcceptChildOutput(line);
        }
    }

    private static string BuildFailureMessage(IwsSetupDiagnostics diagnostics, IwsFailureAction action,
        bool retryUsed) {
        string message = "IWS setup did not complete.\r\n\r\nPhase: " + diagnostics.SafePhaseName +
            "\r\nCode: " + diagnostics.SafeCode + "\r\n\r\n";
        if (action == IwsFailureAction.FreshInstaller)
            return message + "The enrollment material was rejected or is no longer eligible. " +
                "Request a fresh installer for this device. Existing recoverable IWS state was not silently rebound.";
        if (retryUsed) return message + "The bounded retry also failed. Existing identity/state was preserved or restored. " +
            "Contact IWS support with the phase and code above.";
        return message + "This phase may be retried once. Retry resumes or repairs the detected IWS state; it does not " +
            "silently replace a registered identity.";
    }

    private static void LaunchClientAsEmployee() {
        if (!File.Exists(ClientPath)) throw new FileNotFoundException();
        Process.Start(new ProcessStartInfo("explorer.exe", "\"" + ClientPath + "\"") { UseShellExecute = true });
    }

    private static void SetRestrictedDirectoryAcl(string path) {
        Directory.CreateDirectory(path);
        DirectorySecurity security = new DirectorySecurity(); security.SetAccessRuleProtection(true, false);
        InheritanceFlags inheritance = InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit;
        security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
            FileSystemRights.FullControl, inheritance, PropagationFlags.None, AccessControlType.Allow));
        security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
            FileSystemRights.FullControl, inheritance, PropagationFlags.None, AccessControlType.Allow));
        Directory.SetAccessControl(path, security);
    }

    private static void SetRestrictedFileAcl(string path) {
        FileSecurity security = new FileSecurity(); security.SetAccessRuleProtection(true, false);
        security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
            FileSystemRights.FullControl, AccessControlType.Allow));
        security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
            FileSystemRights.FullControl, AccessControlType.Allow));
        File.SetAccessControl(path, security);
    }

    private static string ExtractVerifiedOverlay(string workspace) {
        byte[] executable = File.ReadAllBytes(Process.GetCurrentProcess().MainModule.FileName);
        int trailerSize = Magic.Length + 8 + 32;
        if (executable.Length < trailerSize) throw new InvalidDataException();
        int trailer = executable.Length - trailerSize;
        if (Encoding.ASCII.GetString(executable, trailer, Magic.Length) != Magic) throw new InvalidDataException();
        long length = BitConverter.ToInt64(executable, trailer + Magic.Length); long start = trailer - length;
        if (length < 1 || start < 2 || length > Int32.MaxValue) throw new InvalidDataException();
        byte[] payload = new byte[(int)length]; Buffer.BlockCopy(executable, (int)start, payload, 0, payload.Length);
        using (SHA256 sha = SHA256.Create()) {
            byte[] actual = sha.ComputeHash(payload);
            for (int i = 0; i < 32; i++)
                if (actual[i] != executable[trailer + Magic.Length + 8 + i]) throw new InvalidDataException();
        }
        string archive = Path.Combine(workspace, "payload.zip"); File.WriteAllBytes(archive, payload); return archive;
    }

    private static void ExtractVerifiedArchive(string archive, string workspace) {
        using (ZipArchive zip = ZipFile.OpenRead(archive)) foreach (ZipArchiveEntry entry in zip.Entries) {
            string target = Path.GetFullPath(Path.Combine(workspace, entry.FullName));
            if (!target.StartsWith(Path.GetFullPath(workspace) + Path.DirectorySeparatorChar,
                    StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException();
            if (String.IsNullOrEmpty(entry.Name)) { Directory.CreateDirectory(target); continue; }
            Directory.CreateDirectory(Path.GetDirectoryName(target)); entry.ExtractToFile(target, true);
        }
        VerifyManifest(workspace, Path.Combine(workspace, "BUNDLE-MANIFEST.sha256"));
    }

    private static void VerifyManifest(string root, string manifest) {
        HashSet<string> listed = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (string line in File.ReadAllLines(manifest)) {
            string[] parts = line.Split(new[] { "  " }, 2, StringSplitOptions.None);
            if (parts.Length != 2 || parts[0].Length != 64) throw new InvalidDataException();
            string member = parts[1].Replace('/', Path.DirectorySeparatorChar).TrimStart('.', Path.DirectorySeparatorChar);
            string file = Path.GetFullPath(Path.Combine(root, member));
            if (!file.StartsWith(Path.GetFullPath(root) + Path.DirectorySeparatorChar,
                    StringComparison.OrdinalIgnoreCase) || !listed.Add(file)) throw new InvalidDataException();
            using (SHA256 sha = SHA256.Create())
                if (!String.Equals(BitConverter.ToString(sha.ComputeHash(File.ReadAllBytes(file))).Replace("-", ""),
                        parts[0], StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException();
        }
        foreach (string file in Directory.GetFiles(root, "*", SearchOption.AllDirectories))
            if (!String.Equals(file, manifest, StringComparison.OrdinalIgnoreCase) &&
                !String.Equals(file, Path.Combine(root, "payload.zip"), StringComparison.OrdinalIgnoreCase) &&
                !listed.Contains(Path.GetFullPath(file))) throw new InvalidDataException();
    }

    private static string EscapeJson(string value) { return value.Replace("\\", "\\\\").Replace("\"", "\\\""); }
}

internal sealed class IwsSetupWelcomeForm : Form {
    internal IwsSetupAction SelectedAction { get; private set; }
    internal IwsSetupWelcomeForm(IwsSetupDecision decision, IwsSetupManifest manifest) {
        SelectedAction = IwsSetupAction.Cancel;
        Text = "IWS Setup"; Width = 620; Height = 330; FormBorderStyle = FormBorderStyle.FixedDialog;
        StartPosition = FormStartPosition.CenterScreen; MaximizeBox = false; MinimizeBox = false;
        Controls.Add(new Label { Left = 24, Top = 20, Width = 555, Height = 90, Text = Describe(decision) });
        int left = 24;
        if (decision.DefaultAction == IwsSetupAction.Install)
            left = AddButton("Install", IwsSetupAction.Install, left, true);
        else if (decision.DefaultAction == IwsSetupAction.Repair)
            left = AddButton("Repair (recommended)", IwsSetupAction.Repair, left, true);
        if (decision.CanOpenIws) left = AddButton("Open IWS", IwsSetupAction.OpenIws, left, false);
        if (decision.CanCleanReinstall) left = AddButton(decision.RequiresReassignmentConfirmation ?
            "Clean / reassign..." : "Clean reinstall...", IwsSetupAction.CleanReinstall, left, false);
        AddButton("Cancel", IwsSetupAction.Cancel, left, decision.DefaultAction == IwsSetupAction.Blocked);
    }
    private int AddButton(string text, IwsSetupAction action, int left, bool defaultButton) {
        Button button = new Button { Left = left, Top = 180, Width = text.Length > 15 ? 155 : 105,
            Height = 34, Text = text };
        button.Click += delegate { SelectedAction = action; Close(); };
        Controls.Add(button); if (defaultButton) AcceptButton = button;
        return left + button.Width + 10;
    }
    private static string Describe(IwsSetupDecision decision) {
        string suffix = decision.ArtifactExpired ?
            "\r\n\r\nThis installer is expired. It may repair a matching installation, but it cannot enroll or clean reinstall." : "";
        switch (decision.State) {
            case IwsDetectedState.Fresh: return "Welcome to IWS Setup. Install IWS for this device." + suffix;
            case IwsDetectedState.PartialNeedsLogin: return "IWS private connectivity is present and explicitly needs login. " +
                "Repair will safely resume enrollment for this device." + suffix;
            case IwsDetectedState.ExistingIdentity: return "IWS is already registered for this device. Repair preserves its " +
                "identity and reinstalls required IWS files, trust, shortcut, and isolation." + suffix;
            case IwsDetectedState.LegacyIdentity: return "A validated enrolled IWS identity was found without a modern receipt. " +
                "Repair preserves it and does not use this installer's key. Clean reassignment requires extra confirmation." + suffix;
            case IwsDetectedState.UnknownIdentity: return "Existing IWS state was found, but enrollment status is currently unknown. " +
                "Repair preserves it and does not silently re-enroll. Clean reassignment requires extra confirmation." + suffix;
            case IwsDetectedState.DifferentDevice: return "This installer is for a different device than the protected IWS receipt. " +
                "Default repair/rebinding is blocked. Use the matching installer, or explicitly clean/reassign with confirmation." + suffix;
            default: return "An existing service with the IWS name is not verifiably owned by IWS. Setup will not change it.";
        }
    }
}

internal sealed class IwsSetupProgressForm : Form {
    private readonly Label phaseLabel;
    internal IwsSetupProgressForm(string text) {
        Text = "IWS Setup"; Width = 520; Height = 160; FormBorderStyle = FormBorderStyle.FixedDialog;
        StartPosition = FormStartPosition.CenterScreen; ControlBox = false; ShowInTaskbar = true;
        phaseLabel = new Label { Left = 24, Top = 20, Width = 456, Height = 40, Text = text };
        Controls.Add(phaseLabel);
        Controls.Add(new ProgressBar { Left = 24, Top = 70, Width = 456, Height = 20,
            Style = ProgressBarStyle.Marquee, MarqueeAnimationSpeed = 30 });
    }
    internal void PostPhase(string phase) { BeginInvoke(new Action<string>(SetPhase), phase); }
    private void SetPhase(string phase) { phaseLabel.Text = "Working on IWS...\r\nPhase: " + phase; }
}

internal sealed class IwsSetupCompleteForm : Form {
    internal IwsSetupCompleteForm(bool ready) {
        Text = "IWS Setup"; Width = 480; Height = 190; FormBorderStyle = FormBorderStyle.FixedDialog;
        StartPosition = FormStartPosition.CenterScreen; MaximizeBox = false; MinimizeBox = false;
        Controls.Add(new Label { Left = 24, Top = 20, Width = 420, Height = 55,
            Text = ready ? "IWS is installed and ready. You can open it now or later from the Start menu." :
                "IWS repair completed and preserved the existing identity. Private connectivity could not be " +
                "confirmed while offline; open IWS to check again when networking is available." });
        Button open = new Button { Left = 190, Top = 85, Width = 115, Height = 32, Text = "Open IWS" };
        open.Click += delegate { Open(); Close(); };
        Button close = new Button { Left = 320, Top = 85, Width = 100, Height = 32, Text = "Close" };
        close.Click += delegate { Close(); };
        Controls.Add(open); Controls.Add(close); AcceptButton = open;
    }
    private static void Open() {
        string client = @"C:\Program Files\IWS\Client\IwsClient.exe";
        if (File.Exists(client)) Process.Start(new ProcessStartInfo("explorer.exe", "\"" + client + "\"") {
            UseShellExecute = true });
    }
}
