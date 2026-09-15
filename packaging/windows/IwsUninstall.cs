using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Forms;

internal static class IwsUninstall {
    private const int MoveFileDelayUntilReboot = 0x4;

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool MoveFileEx(string existingFile, string newFile, int flags);

    [STAThread] private static void Main() {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        if (MessageBox.Show(
            "Uninstall IWS Secure Client?\r\n\r\nThis removes the local IWS app, private transport, " +
            "IWS-owned network rules and trust, and local IWS identity/data. It does not remove other VPN software.",
            "IWS Secure Client", MessageBoxButtons.YesNo, MessageBoxIcon.Warning,
            MessageBoxDefaultButton.Button2) != DialogResult.Yes) return;

        try {
            string installerRoot = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
                "IWS", "Installer");
            string script = Path.Combine(installerRoot, "Uninstall-IwsClient.ps1");
            if (!File.Exists(script)) throw new InvalidOperationException();
            StringBuilder arguments = new StringBuilder("-NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"")
                .Append(script.Replace("\"", "")).Append("\"");
            using (Process process = new Process()) {
                process.StartInfo = new ProcessStartInfo("powershell.exe", arguments.ToString()) {
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true
                };
                process.OutputDataReceived += delegate { };
                process.ErrorDataReceived += delegate { };
                if (!process.Start()) throw new InvalidOperationException();
                process.BeginOutputReadLine();
                process.BeginErrorReadLine();
                if (!process.WaitForExit(300000)) {
                    try { process.Kill(); } catch { }
                    throw new TimeoutException();
                }
                process.WaitForExit();
                if (process.ExitCode != 0) throw new InvalidOperationException();
            }
            string executable = Process.GetCurrentProcess().MainModule.FileName;
            MoveFileEx(executable, null, MoveFileDelayUntilReboot);
            MoveFileEx(installerRoot, null, MoveFileDelayUntilReboot);
            MessageBox.Show("IWS Secure Client was uninstalled.", "IWS Secure Client",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        catch {
            MessageBox.Show("IWS uninstall could not be completed. Existing non-IWS software was not removed. " +
                "Retry from Windows Installed Apps or contact IWS support.", "IWS Secure Client",
                MessageBoxButtons.OK, MessageBoxIcon.Error);
            Environment.ExitCode = 1;
        }
    }
}
