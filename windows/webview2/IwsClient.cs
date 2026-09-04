using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Win32;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace ImpactWiring.IwsClient
{
    internal static class Program
    {
        [DllImport("shell32.dll", SetLastError = true)]
        private static extern int SetCurrentProcessExplicitAppUserModelID(
            [MarshalAs(UnmanagedType.LPWStr)] string appId);

        [STAThread]
        private static void Main()
        {
            SetCurrentProcessExplicitAppUserModelID("ImpactWiring.IWS.Client");
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new IwsForm());
        }
    }

    internal sealed class IwsForm : Form
    {
        private const string PortalUrl = "http://100.83.246.85:443/";
        private const string ServiceName = "IWSPrivateTransport";
        private readonly WebView2 webView;
        private readonly Button backButton;
        private readonly Button homeButton;
        private readonly Button retryButton;
        private readonly Button themeLightButton;
        private readonly Button themeDarkButton;
        private readonly Label statusTitle;
        private readonly Label statusDetail;
        private readonly Label launchIwsLabel;
        private readonly Label launchCompanyLabel;
        private readonly Panel toolbar;
        private readonly Panel themeToggle;
        private readonly Panel statusPanel;
        private readonly Panel contentHost;
        private readonly TableLayoutPanel shellLayout;
        private readonly Panel launchLockup;
        private readonly PictureBox launchMark;
        private readonly SpinnerControl spinner;
        private readonly ErrorGlyphControl errorGlyph;
        private readonly ConnectedIndicator connectedIndicator;
        private readonly GradientRuleControl railRule;
        private readonly Icon appIcon;
        private CoreWebView2Environment environment;
        private CoreWebView2DevToolsProtocolEventReceiver webSocketReceiver;
        private CoreWebView2DevToolsProtocolEventReceiver responseReceiver;
        private string evidencePath;
        private bool starting;
        private string themeChoice;

        [DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(
            IntPtr window, int attribute, ref int value, int size);

        internal IwsForm()
        {
            Text = "IWS";
            MinimumSize = new Size(900, 650);
            WindowState = FormWindowState.Maximized;
            StartPosition = FormStartPosition.CenterScreen;
            appIcon = LoadEmbeddedIcon("IwsIcon.ico") ??
                Icon.ExtractAssociatedIcon(Application.ExecutablePath);
            if (appIcon != null) Icon = appIcon;

            backButton = new Button {
                Text = "‹  Back", Width = 82, Height = 36,
                FlatStyle = FlatStyle.Flat, TabStop = true
            };
            homeButton = new Button {
                Text = "IWS Portal", Width = 116, Height = 36,
                FlatStyle = FlatStyle.Flat, TabStop = true,
                Image = LoadBrandMark("IwsMarkSmall.png"),
                ImageAlign = ContentAlignment.MiddleLeft,
                TextImageRelation = TextImageRelation.ImageBeforeText
            };
            themeLightButton = new Button {
                Text = "Light", Width = 44, Height = 28, FlatStyle = FlatStyle.Flat, TabStop = true
            };
            themeDarkButton = new Button {
                Text = "Dark", Width = 44, Height = 28, FlatStyle = FlatStyle.Flat, TabStop = true
            };
            connectedIndicator = new ConnectedIndicator { Width = 86, Height = 32 };
            themeToggle = new Panel { Width = 94, Height = 32 };
            themeLightButton.Location = new Point(2, 2);
            themeDarkButton.Location = new Point(48, 2);
            themeToggle.Controls.Add(themeLightButton);
            themeToggle.Controls.Add(themeDarkButton);
            toolbar = new Panel { Dock = DockStyle.Fill, Height = 48 };
            toolbar.Controls.Add(backButton);
            toolbar.Controls.Add(homeButton);
            toolbar.Controls.Add(themeToggle);
            toolbar.Controls.Add(connectedIndicator);
            railRule = new GradientRuleControl { Dock = DockStyle.Fill, Height = 2 };

            webView = new WebView2 { Dock = DockStyle.Fill, Visible = false };
            launchLockup = new Panel { Width = 310, Height = 62 };
            launchMark = new PictureBox {
                Width = 78, Height = 60, SizeMode = PictureBoxSizeMode.Zoom,
                Image = LoadBrandMark("IwsMarkFull.png"), Location = new Point(0, 1)
            };
            launchIwsLabel = new Label {
                Text = "IWS", AutoSize = true, Location = new Point(92, 5),
                Font = new Font("Segoe UI", 20F, FontStyle.Bold)
            };
            launchCompanyLabel = new Label {
                Text = "IMPACT WIRING SOLUTIONS", AutoSize = true, Location = new Point(94, 40),
                Font = new Font("Segoe UI", 7F, FontStyle.Bold)
            };
            launchLockup.Controls.Add(launchMark);
            launchLockup.Controls.Add(launchIwsLabel);
            launchLockup.Controls.Add(launchCompanyLabel);
            spinner = new SpinnerControl { Width = 24, Height = 24 };
            errorGlyph = new ErrorGlyphControl { Width = 40, Height = 40, Visible = false };
            statusTitle = new Label {
                Text = "Connecting to IWS…", AutoSize = true,
                Font = new Font("Segoe UI", 11F, FontStyle.Bold),
                TextAlign = ContentAlignment.MiddleCenter
            };
            statusDetail = new Label {
                Text = "", AutoSize = true,
                Font = new Font("Segoe UI", 9.5F), TextAlign = ContentAlignment.MiddleCenter
            };
            retryButton = new Button {
                Text = "Retry", Width = 118, Height = 42,
                FlatStyle = FlatStyle.Flat, Visible = false, TabStop = true
            };
            statusPanel = new Panel { Dock = DockStyle.Fill };
            statusPanel.Controls.Add(launchLockup);
            statusPanel.Controls.Add(spinner);
            statusPanel.Controls.Add(errorGlyph);
            statusPanel.Controls.Add(statusTitle);
            statusPanel.Controls.Add(statusDetail);
            statusPanel.Controls.Add(retryButton);

            contentHost = new Panel { Dock = DockStyle.Fill };
            contentHost.Controls.Add(webView);
            contentHost.Controls.Add(statusPanel);
            shellLayout = new TableLayoutPanel {
                Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 3,
                Margin = new Padding(0), Padding = new Padding(0)
            };
            shellLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
            shellLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 48F));
            shellLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 2F));
            shellLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            shellLayout.Controls.Add(toolbar, 0, 0);
            shellLayout.Controls.Add(railRule, 0, 1);
            shellLayout.Controls.Add(contentHost, 0, 2);
            Controls.Add(shellLayout);

            backButton.Click += delegate {
                if (webView.CoreWebView2 != null && webView.CoreWebView2.CanGoBack) {
                    webView.CoreWebView2.GoBack();
                }
            };
            homeButton.Click += delegate {
                if (webView.CoreWebView2 != null) webView.CoreWebView2.Navigate(PortalUrl);
            };
            retryButton.Click += async delegate { await StartAsync(); };
            themeLightButton.Click += delegate { SetThemeChoice("light"); };
            themeDarkButton.Click += delegate { SetThemeChoice("dark"); };
            SystemEvents.UserPreferenceChanged += OnUserPreferenceChanged;
            FormClosed += delegate { SystemEvents.UserPreferenceChanged -= OnUserPreferenceChanged; };
            Resize += delegate { LayoutShell(); };
            themeChoice = LoadThemeChoice();
            ApplyTheme();
            LayoutShell();
            Shown += async delegate { await StartAsync(); };
        }

        private async Task StartAsync()
        {
            if (starting) return;
            starting = true;
            ShowConnectionState("Connecting to IWS…", "", false);
            try
            {
                if (!await WaitForPrivateTransportAsync()) {
                    throw new InvalidOperationException("IWS private connectivity is unavailable.");
                }

                if (webView.CoreWebView2 == null) {
                    string fixedRuntime = Path.Combine(Application.StartupPath, "WebView2Fixed");
                    string profile = Path.Combine(
                        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                        "IWS", "WebView2");
                    Directory.CreateDirectory(profile);
                    environment = await CoreWebView2Environment.CreateAsync(fixedRuntime, profile, null);
                    await webView.EnsureCoreWebView2Async(environment);
                    ConfigureWebView();
                    await ConfigureEvidenceAsync();
                }
                connectedIndicator.Connected = true;
                statusPanel.Visible = false;
                webView.Visible = true;
                webView.CoreWebView2.Navigate(PortalUrl);
            }
            catch (Exception)
            {
                connectedIndicator.Connected = false;
                ShowConnectionState(
                    "IWS is unavailable.",
                    "Check your connection and try again.",
                    true);
            }
            finally
            {
                starting = false;
            }
        }

        private void ShowConnectionState(string title, string detail, bool allowRetry)
        {
            webView.Visible = false;
            launchLockup.Visible = !allowRetry;
            spinner.Visible = !allowRetry;
            spinner.Active = !allowRetry;
            errorGlyph.Visible = allowRetry;
            statusTitle.Text = title;
            statusDetail.Text = detail;
            statusDetail.Visible = !String.IsNullOrEmpty(detail);
            retryButton.Visible = allowRetry;
            statusPanel.Visible = true;
            LayoutShell();
            statusPanel.BringToFront();
            toolbar.BringToFront();
        }

        private void OnUserPreferenceChanged(object sender, UserPreferenceChangedEventArgs args)
        {
            if (InvokeRequired) BeginInvoke((Action)ApplyTheme);
            else ApplyTheme();
        }

        private void ApplyTheme()
        {
            bool dark = ResolveDarkTheme();
            ShellPalette palette = ShellPalette.For(dark);
            if (appIcon != null) Icon = appIcon;
            BackColor = palette.Background;
            toolbar.BackColor = palette.Surface;
            statusPanel.BackColor = palette.Background;
            statusTitle.ForeColor = palette.Text;
            statusDetail.ForeColor = palette.Muted;
            launchIwsLabel.ForeColor = palette.Text;
            launchCompanyLabel.ForeColor = palette.Muted;
            backButton.BackColor = palette.Surface;
            homeButton.BackColor = palette.SurfaceAlt;
            retryButton.BackColor = palette.Accent;
            backButton.ForeColor = palette.Text;
            homeButton.ForeColor = palette.Text;
            retryButton.ForeColor = palette.AccentContrast;
            backButton.FlatAppearance.BorderSize = 0;
            backButton.FlatAppearance.MouseOverBackColor = palette.SurfaceAlt;
            homeButton.FlatAppearance.BorderSize = 1;
            homeButton.FlatAppearance.BorderColor = palette.Border;
            homeButton.FlatAppearance.MouseOverBackColor = palette.SurfaceAlt;
            retryButton.FlatAppearance.BorderSize = 0;
            retryButton.FlatAppearance.BorderColor = palette.Accent;
            themeToggle.BackColor = palette.Border;
            StyleThemeButton(themeLightButton, !dark, palette);
            StyleThemeButton(themeDarkButton, dark, palette);
            connectedIndicator.SetPalette(palette);
            spinner.SetPalette(palette);
            errorGlyph.SetPalette(palette);
            railRule.SetPalette(palette);
            if (webView != null) webView.DefaultBackgroundColor = palette.Background;
            int useDark = dark ? 1 : 0;
            try { DwmSetWindowAttribute(Handle, 20, ref useDark, sizeof(int)); } catch { }
            Invalidate(true);
        }

        private static void StyleThemeButton(Button button, bool selected, ShellPalette palette)
        {
            button.UseVisualStyleBackColor = false;
            button.BackColor = selected ? palette.Surface : palette.SurfaceAlt;
            button.ForeColor = selected ? palette.Text : palette.Muted;
            button.FlatAppearance.BorderSize = selected ? 1 : 0;
            button.FlatAppearance.BorderColor = palette.Border;
            button.FlatAppearance.MouseOverBackColor = palette.Surface;
        }

        private void SetThemeChoice(string choice)
        {
            themeChoice = choice;
            using (RegistryKey key = Registry.CurrentUser.CreateSubKey(
                @"Software\Impact Wiring Solutions\IWS")) {
                key.SetValue("Theme", choice, RegistryValueKind.String);
            }
            ApplyTheme();
            if (webView.CoreWebView2 != null) {
                string script = "localStorage.setItem('iws.theme.v1'," +
                    "JSON.stringify({version:1,theme:'" + choice + "'}));location.reload();";
                webView.CoreWebView2.ExecuteScriptAsync(script);
            }
        }

        private static string LoadThemeChoice()
        {
            try {
                object value = Registry.GetValue(
                    @"HKEY_CURRENT_USER\Software\Impact Wiring Solutions\IWS", "Theme", null);
                string choice = value as string;
                return choice == "light" || choice == "dark" ? choice : null;
            }
            catch { return null; }
        }

        private bool ResolveDarkTheme()
        {
            if (themeChoice == "dark") return true;
            if (themeChoice == "light") return false;
            try {
                object value = Registry.GetValue(
                    @"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize",
                    "AppsUseLightTheme", 1);
                return Convert.ToInt32(value) == 0;
            }
            catch { return false; }
        }

        private static Image LoadBrandMark(string resourceName)
        {
            using (Stream stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(resourceName)) {
                if (stream == null) return null;
                using (Image image = Image.FromStream(stream)) return new Bitmap(image);
            }
        }

        private static Icon LoadEmbeddedIcon(string resourceName)
        {
            using (Stream stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(resourceName)) {
                if (stream == null) return null;
                using (Icon icon = new Icon(stream)) return new Icon(icon, new Size(32, 32));
            }
        }

        private void LayoutShell()
        {
            backButton.Location = new Point(10, 6);
            homeButton.Location = new Point(98, 6);
            connectedIndicator.Location = new Point(Math.Max(230, toolbar.ClientSize.Width - 96), 8);
            themeToggle.Location = new Point(Math.Max(226, connectedIndicator.Left - 102), 8);

            int centerX = statusPanel.ClientSize.Width / 2;
            int centerY = statusPanel.ClientSize.Height / 2;
            if (retryButton.Visible) {
                errorGlyph.Location = new Point(centerX - 20, centerY - 92);
                statusTitle.Location = new Point(centerX - statusTitle.PreferredWidth / 2, centerY - 37);
                statusDetail.Location = new Point(centerX - statusDetail.PreferredWidth / 2, centerY - 4);
                retryButton.Location = new Point(centerX - retryButton.Width / 2, centerY + 39);
            }
            else {
                launchLockup.Location = new Point(centerX - launchLockup.Width / 2, centerY - 92);
                spinner.Location = new Point(centerX - spinner.Width / 2, centerY - 9);
                statusTitle.Location = new Point(centerX - statusTitle.PreferredWidth / 2, centerY + 30);
            }
        }

        private sealed class ShellPalette
        {
            internal readonly Color Background;
            internal readonly Color Surface;
            internal readonly Color SurfaceAlt;
            internal readonly Color Border;
            internal readonly Color Text;
            internal readonly Color Muted;
            internal readonly Color Accent;
            internal readonly Color AccentContrast;
            internal readonly Color Ok;
            internal readonly Color Danger;

            private ShellPalette(
                Color background, Color surface, Color surfaceAlt, Color border,
                Color text, Color muted, Color accent, Color accentContrast,
                Color ok, Color danger)
            {
                Background = background;
                Surface = surface;
                SurfaceAlt = surfaceAlt;
                Border = border;
                Text = text;
                Muted = muted;
                Accent = accent;
                AccentContrast = accentContrast;
                Ok = ok;
                Danger = danger;
            }

            internal static ShellPalette For(bool dark)
            {
                if (dark) {
                    return new ShellPalette(
                        Color.FromArgb(22, 26, 31), Color.FromArgb(30, 36, 43),
                        Color.FromArgb(35, 42, 50), Color.FromArgb(61, 73, 84),
                        Color.FromArgb(238, 241, 234), Color.FromArgb(154, 166, 178),
                        Color.FromArgb(120, 184, 224), Color.FromArgb(15, 19, 23),
                        Color.FromArgb(127, 201, 164), Color.FromArgb(228, 115, 111));
                }
                return new ShellPalette(
                    Color.FromArgb(238, 241, 234), Color.White,
                    Color.FromArgb(245, 245, 245), Color.FromArgb(223, 223, 223),
                    Color.FromArgb(17, 20, 24), Color.FromArgb(90, 100, 112),
                    Color.FromArgb(29, 54, 134), Color.White,
                    Color.FromArgb(46, 107, 79), Color.FromArgb(139, 0, 0));
            }
        }

        private sealed class ConnectedIndicator : Control
        {
            private ShellPalette palette = ShellPalette.For(false);
            private bool connected;
            internal bool Connected {
                get { return connected; }
                set { connected = value; Invalidate(); }
            }
            internal void SetPalette(ShellPalette value) { palette = value; Invalidate(); }
            protected override void OnPaint(PaintEventArgs e)
            {
                base.OnPaint(e);
                e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
                using (Brush dot = new SolidBrush(Connected ? palette.Ok : palette.Muted))
                    e.Graphics.FillEllipse(dot, 2, 13, 6, 6);
                TextRenderer.DrawText(e.Graphics, Connected ? "Connected" : "", new Font("Segoe UI", 8.5F),
                    new Rectangle(14, 6, Width - 14, 22), palette.Muted,
                    TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
            }
        }

        private sealed class GradientRuleControl : Control
        {
            private ShellPalette palette = ShellPalette.For(false);
            internal void SetPalette(ShellPalette value) { palette = value; Invalidate(); }
            protected override void OnPaint(PaintEventArgs e)
            {
                using (LinearGradientBrush brush = new LinearGradientBrush(
                    ClientRectangle, Color.FromArgb(80, 88, 160), Color.FromArgb(176, 208, 192), 0F)) {
                    ColorBlend blend = new ColorBlend();
                    blend.Colors = new Color[] {
                        Color.FromArgb(80, 88, 160), Color.FromArgb(104, 144, 200),
                        Color.FromArgb(120, 184, 224), Color.FromArgb(160, 200, 200),
                        Color.FromArgb(176, 208, 192)
                    };
                    blend.Positions = new float[] { 0F, .25F, .5F, .75F, 1F };
                    brush.InterpolationColors = blend;
                    e.Graphics.FillRectangle(brush, ClientRectangle);
                }
            }
        }

        private sealed class SpinnerControl : Control
        {
            private readonly System.Windows.Forms.Timer timer;
            private int angle;
            private ShellPalette palette = ShellPalette.For(false);
            internal SpinnerControl()
            {
                DoubleBuffered = true;
                timer = new System.Windows.Forms.Timer { Interval = 80 };
                timer.Tick += delegate { angle = (angle + 24) % 360; Invalidate(); };
            }
            internal bool Active { set { if (value) timer.Start(); else timer.Stop(); } }
            internal void SetPalette(ShellPalette value) { palette = value; Invalidate(); }
            protected override void OnPaint(PaintEventArgs e)
            {
                e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
                using (Pen basePen = new Pen(palette.Border, 2.5F))
                    e.Graphics.DrawEllipse(basePen, 3, 3, Width - 7, Height - 7);
                using (Pen accentPen = new Pen(palette.Accent, 2.5F)) {
                    accentPen.StartCap = LineCap.Round;
                    accentPen.EndCap = LineCap.Round;
                    e.Graphics.DrawArc(accentPen, 3, 3, Width - 7, Height - 7, angle, 95);
                }
            }
        }

        private sealed class ErrorGlyphControl : Control
        {
            private ShellPalette palette = ShellPalette.For(false);
            internal void SetPalette(ShellPalette value) { palette = value; Invalidate(); }
            protected override void OnPaint(PaintEventArgs e)
            {
                e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
                using (Pen pen = new Pen(palette.Danger, 2F)) {
                    pen.StartCap = LineCap.Round;
                    pen.EndCap = LineCap.Round;
                    e.Graphics.DrawEllipse(pen, 4, 4, Width - 9, Height - 9);
                    e.Graphics.DrawLine(pen, Width / 2, 11, Width / 2, 23);
                    e.Graphics.DrawLine(pen, Width / 2, 29, Width / 2, 30);
                }
            }
        }

        private static async Task<bool> WaitForPrivateTransportAsync()
        {
            string transport = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
                "IWS", "Transport", "iws-transport.exe");
            if (!File.Exists(transport)) return false;

            for (int attempt = 0; attempt < 30; attempt++)
            {
                ProcessStartInfo start = new ProcessStartInfo {
                    FileName = transport,
                    Arguments = "--daemon-addr npipe://iws-private-transport status --check startup",
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true
                };
                using (Process process = Process.Start(start))
                {
                    if (process.WaitForExit(5000) && process.ExitCode == 0) return true;
                    if (!process.HasExited) process.Kill();
                }
                await Task.Delay(1000);
            }
            return false;
        }

        private void ConfigureWebView()
        {
            CoreWebView2 core = webView.CoreWebView2;
            core.Settings.AreDevToolsEnabled = false;
            core.Settings.AreDefaultContextMenusEnabled = false;
            core.Settings.IsPasswordAutosaveEnabled = false;
            core.Settings.IsGeneralAutofillEnabled = false;
            core.Settings.IsStatusBarEnabled = false;

            core.NavigationStarting += delegate(object sender, CoreWebView2NavigationStartingEventArgs args) {
                if (!IsApprovedUri(args.Uri)) args.Cancel = true;
            };
            core.AddWebResourceRequestedFilter("*", CoreWebView2WebResourceContext.All);
            core.WebResourceRequested += delegate(object sender, CoreWebView2WebResourceRequestedEventArgs args) {
                if (!IsApprovedUri(args.Request.Uri)) {
                    args.Response = environment.CreateWebResourceResponse(
                        Stream.Null, 403, "Forbidden", "Content-Type: text/plain");
                }
            };
            core.NewWindowRequested += delegate(object sender, CoreWebView2NewWindowRequestedEventArgs args) {
                args.Handled = true;
            };
            core.DownloadStarting += delegate(object sender, CoreWebView2DownloadStartingEventArgs args) {
                args.Cancel = true;
            };
            core.PermissionRequested += delegate(object sender, CoreWebView2PermissionRequestedEventArgs args) {
                args.State = CoreWebView2PermissionState.Deny;
            };
            core.LaunchingExternalUriScheme += delegate(
                object sender,
                CoreWebView2LaunchingExternalUriSchemeEventArgs args) {
                args.Cancel = true;
            };
            core.HistoryChanged += delegate { backButton.Invalidate(); };
            core.NavigationCompleted += delegate(
                object sender,
                CoreWebView2NavigationCompletedEventArgs args) {
                if (IsApprovedUri(core.Source) &&
                    (!args.IsSuccess || args.HttpStatusCode >= 400)) {
                    ShowConnectionState(
                        "IWS is unavailable.",
                        "Check your connection and try again.",
                        true);
                }
            };
        }

        private async Task ConfigureEvidenceAsync()
        {
            string evidenceDirectory = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "IWS");
            Directory.CreateDirectory(evidenceDirectory);
            evidencePath = Path.Combine(evidenceDirectory, "shell-evidence.jsonl");
            File.WriteAllText(evidencePath, string.Empty);

            CoreWebView2 core = webView.CoreWebView2;
            webSocketReceiver = core.GetDevToolsProtocolEventReceiver("Network.webSocketCreated");
            webSocketReceiver.DevToolsProtocolEventReceived += delegate {
                AppendEvidence("{\"event\":\"websocket-created\"}");
            };
            responseReceiver = core.GetDevToolsProtocolEventReceiver("Network.responseReceived");
            responseReceiver.DevToolsProtocolEventReceived += delegate(
                object sender,
                CoreWebView2DevToolsProtocolEventReceivedEventArgs args) {
                string response = args.ParameterObjectAsJson;
                if (response.Contains("/api/") && response.Contains("\"status\":200")) {
                    AppendEvidence("{\"event\":\"api-response-200\"}");
                }
            };
            await core.CallDevToolsProtocolMethodAsync("Network.enable", "{}");
            core.NavigationCompleted += async delegate(object sender, CoreWebView2NavigationCompletedEventArgs args) {
                if (!args.IsSuccess || !IsApprovedUri(core.Source)) return;
                AppendEvidence("{\"event\":\"navigation-ok\"}");
                string cookieBefore = await core.ExecuteScriptAsync(
                    "document.cookie.indexOf('iws_poc_cookie=1') >= 0");
                if (cookieBefore.Contains("true")) {
                    AppendEvidence("{\"event\":\"cookie-present-before-write\"}");
                }
                string cookieWrite = await core.ExecuteScriptAsync(
                    "document.cookie='iws_poc_cookie=1; Max-Age=86400; path=/; SameSite=Strict';" +
                    "document.cookie.indexOf('iws_poc_cookie=1') >= 0");
                if (cookieWrite.Contains("true")) {
                    AppendEvidence("{\"event\":\"cookie-write-ok\"}");
                }
                string apiResult = await core.ExecuteScriptAsync(
                    "fetch('/api/health',{cache:'no-store'}).then(r=>String(r.status)).catch(()=>\"error\")");
                if (apiResult.Contains("200")) {
                    AppendEvidence("{\"event\":\"api-health-200\"}");
                }
                string stateResult = await core.ExecuteScriptAsync(
                    "JSON.stringify({localStorage:localStorage.length,cookieChars:document.cookie.length})");
                AppendEvidence("{\"event\":\"web-state-counts\",\"result\":" + stateResult + "}");
            };
        }

        private void AppendEvidence(string value)
        {
            try {
                File.AppendAllText(evidencePath, value + Environment.NewLine);
            }
            catch (IOException) {
                // POC evidence must never interrupt the employee application.
            }
        }

        private static bool IsApprovedUri(string value)
        {
            Uri uri;
            return Uri.TryCreate(value, UriKind.Absolute, out uri) &&
                uri.Scheme == Uri.UriSchemeHttp &&
                uri.Host == "100.83.246.85" &&
                uri.Port == 443 &&
                string.IsNullOrEmpty(uri.UserInfo);
        }
    }
}
