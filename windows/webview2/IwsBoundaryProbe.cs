using System;
using System.Drawing;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace ImpactWiring.IwsBoundaryProbe
{
    internal static class Program
    {
        [STAThread]
        private static void Main(string[] args)
        {
            if (args.Length != 3)
            {
                Environment.ExitCode = 2;
                return;
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new ProbeForm(args[0], args[1], args[2]));
        }
    }

    internal sealed class ProbeForm : Form
    {
        private readonly string fixedRuntimePath;
        private readonly string profilePath;
        private readonly string evidencePath;
        private readonly WebView2 webView;

        internal ProbeForm(string fixedRuntimePath, string profilePath, string evidencePath)
        {
            this.fixedRuntimePath = Path.GetFullPath(fixedRuntimePath);
            this.profilePath = Path.GetFullPath(profilePath);
            this.evidencePath = Path.GetFullPath(evidencePath);

            Text = "IWS Boundary Probe";
            ClientSize = new Size(800, 600);
            StartPosition = FormStartPosition.CenterScreen;
            webView = new WebView2 { Dock = DockStyle.Fill };
            Controls.Add(webView);
            Shown += async delegate
            {
                try
                {
                    await InitializeProbeAsync();
                }
                catch (Exception exception)
                {
                    Directory.CreateDirectory(Path.GetDirectoryName(evidencePath));
                    AppendEvidence("{\"event\":\"initialization-error\",\"type\":\"" +
                        exception.GetType().FullName + "\",\"hresult\":\"0x" +
                        exception.HResult.ToString("X8") + "\"}");
                    Close();
                }
            };
        }

        private async Task InitializeProbeAsync()
        {
            Directory.CreateDirectory(profilePath);
            Directory.CreateDirectory(Path.GetDirectoryName(evidencePath));
            File.WriteAllText(evidencePath, string.Empty, new UTF8Encoding(false));

            CoreWebView2Environment environment = await CoreWebView2Environment.CreateAsync(
                fixedRuntimePath,
                profilePath,
                null);
            await webView.EnsureCoreWebView2Async(environment);
            AppendEvidence("{\"event\":\"browser\",\"pid\":" +
                webView.CoreWebView2.BrowserProcessId + "}");

            webView.CoreWebView2.WebMessageReceived += delegate(
                object sender,
                CoreWebView2WebMessageReceivedEventArgs eventArgs)
            {
                AppendEvidence(eventArgs.WebMessageAsJson);
            };

            webView.NavigateToString(BuildProbePage());
        }

        private void AppendEvidence(string line)
        {
            File.AppendAllText(evidencePath, line + Environment.NewLine, new UTF8Encoding(false));
        }

        private static string BuildProbePage()
        {
            return @"<!doctype html>
<html><body><h1>IWS Boundary Probe</h1><pre id=""output""></pre>
<script>
const targets = [
  'http://100.83.246.85:443/',
  'http://100.116.25.100:443/',
  'http://100.99.71.15:443/',
  'http://100.127.228.103:443/',
  'http://10.1.10.1:443/',
  'http://172.16.0.1:443/',
  'http://192.168.50.1:443/',
  'http://100.83.50.15:443/',
  'http://1.1.1.1:443/'
];
async function probe(url) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 5000);
  const started = Date.now();
  window.chrome.webview.postMessage({event:'request-start', url:url, started:started});
  try {
    const response = await fetch(url, {mode:'no-cors', cache:'no-store', signal:controller.signal});
    window.chrome.webview.postMessage({event:'request-end', url:url, ok:true,
      status:response.status, elapsed:Date.now()-started});
  } catch (error) {
    window.chrome.webview.postMessage({event:'request-end', url:url, ok:false,
      error:String(error && error.name || 'Error'), elapsed:Date.now()-started});
  } finally {
    clearTimeout(timer);
  }
}
(async () => {
  for (const target of targets) await probe(target);
  window.chrome.webview.postMessage({event:'complete'});
})();
</script></body></html>";
        }
    }
}
