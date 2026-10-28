using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace VibeVisualizer
{
    /// <summary>The window: your server's page, or the app's own page asking for the server's address (or saying it
    /// can't be reached). Spotify's login and every link open in the browser, as in the Mac app; the login comes back
    /// through vibevisualizer://callback.</summary>
    sealed class AppWindow : Form
    {
        static readonly Color Background = Color.FromArgb(7, 7, 12);

        readonly WebView2 web = new WebView2 { Dock = DockStyle.Fill };
        string server = AppSettings.Server;   // https://…/, or "" before it's set
        string pending;                    // a login that came back before the page was ready
        bool ready, onOwnPage, full;
        FormWindowState preState;          // before full screen
        Rectangle preBounds;

        public AppWindow(string link)
        {
            pending = link;
            Text = "Vibe Visualizer";
            using (var ico = typeof(AppWindow).Assembly.GetManifestResourceStream("app.ico")) Icon = new Icon(ico);   // every size, sharp at any scaling
            BackColor = Background;
            web.DefaultBackgroundColor = Background;
            Controls.Add(web);
            RestoreWindow();
            Shown += async (s, e) => { SingleWindow.Listen(l => BeginInvoke((Action)(() => Arrived(l)))); await Start(); };
            FormClosing += (s, e) => SaveWindow();
        }

        async Task Start()
        {
            try
            {
                CoreWebView2Environment.SetLoaderDllFolderPath(EmbeddedLibraries.LoaderFolder());
                var env = await CoreWebView2Environment.CreateAsync(null, Path.Combine(AppSettings.DataDir, "WebView2"));
                await web.EnsureCoreWebView2Async(env);
            }
            catch (WebView2RuntimeNotFoundException)
            {
                if (MessageBox.Show(this, "Vibe Visualizer needs the Microsoft Edge WebView2 Runtime, which Windows 10 and 11 " +
                        "usually come with. Download it from Microsoft now?", "Vibe Visualizer", MessageBoxButtons.YesNo,
                        MessageBoxIcon.Warning) == DialogResult.Yes)
                    Outside("https://go.microsoft.com/fwlink/p/?LinkId=2124703");
                Close();
                return;
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, "Vibe Visualizer couldn’t open its window: " + ex.Message, "Vibe Visualizer",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
                Close();
                return;
            }
            var core = web.CoreWebView2;
            core.Settings.IsStatusBarEnabled = false;
            core.Settings.AreDevToolsEnabled = false;
            core.Settings.IsPasswordAutosaveEnabled = false;
            core.Settings.IsGeneralAutofillEnabled = false;
            await core.AddScriptToExecuteOnDocumentCreatedAsync(AppScript());
            core.NavigationStarting += OnNavigationStarting;
            core.NavigationCompleted += OnNavigationCompleted;
            core.NewWindowRequested += (s, e) => { e.Handled = true; Outside(e.Uri); };
            core.ContainsFullScreenElementChanged += (s, e) => FullScreen(core.ContainsFullScreenElement);
            core.WebMessageReceived += OnMessage;
            ready = true;
            string link = pending;
            pending = null;
            if (server.Length == 0) ShowSetup(true);
            else if (!Arrived(link)) Go(server);
        }

        // what the page can ask the app: where the Spotify login comes back to, the app's version (Settings → Help & about
        // compares it with the newest Windows release), and this window's server address page
        static string AppScript() =>
            "window.VVWin={callback:()=>\"vibevisualizer://callback\",version:()=>" + Js(Program.Version) +
            ",server:()=>window.chrome.webview.postMessage(\"vv:server\")};";

        void Go(string url) => web.CoreWebView2.Navigate(url);

        void ShowSetup(bool first) => web.CoreWebView2.NavigateToString(BuiltInPages.Setup(first, server));

        void ShowError(string message) =>
            web.CoreWebView2.NavigateToString(BuiltInPages.Error(Uri.TryCreate(server, UriKind.Absolute, out var u) ? u.Host : server, message));

        /// <summary>A link from a second start, or one the page opened. The Spotify login goes to the page, which
        /// finishes it from its address as after a login in the browser. The window comes to the front either way.</summary>
        bool Arrived(string link)
        {
            if (WindowState == FormWindowState.Minimized) ShowWindow(Handle, SW_RESTORE);
            Activate();
            if (!ready) { pending = link ?? pending; return true; }
            if (string.IsNullOrEmpty(link) || server.Length == 0) return false;
            if (!Uri.TryCreate(link, UriKind.Absolute, out var u) || u.Scheme != "vibevisualizer" || u.Host != "callback") return false;
            Go(server + u.Query);
            return true;
        }

        void OnNavigationStarting(object sender, CoreWebView2NavigationStartingEventArgs e)
        {
            if (e.Uri.StartsWith("data:") || e.Uri.StartsWith("about:")) { onOwnPage = true; return; }   // the app's own pages
            if (Uri.TryCreate(e.Uri, UriKind.Absolute, out var u) && IsServer(u)) { onOwnPage = false; return; }
            e.Cancel = true;   // anything else (Spotify's login, links) opens in the browser
            string link = e.Uri;
            if (u != null && u.Scheme == "vibevisualizer") BeginInvoke((Action)(() => Arrived(link)));   // after this navigation is called off
            else Outside(e.Uri);
        }

        void OnNavigationCompleted(object sender, CoreWebView2NavigationCompletedEventArgs e)
        {
            if (e.IsSuccess || onOwnPage) return;
            switch (e.WebErrorStatus)
            {
                case CoreWebView2WebErrorStatus.OperationCanceled:
                    return;   // a link that went to the browser, or the next page on its way
                case CoreWebView2WebErrorStatus.HostNameNotResolved:
                    ShowError("Windows can’t find this server. If it’s on your tailnet, check that Tailscale is connected on this PC.");
                    return;
                case CoreWebView2WebErrorStatus.CertificateCommonNameIsIncorrect:
                case CoreWebView2WebErrorStatus.CertificateExpired:
                case CoreWebView2WebErrorStatus.CertificateIsInvalid:
                case CoreWebView2WebErrorStatus.CertificateRevoked:
                    ShowError("The server’s certificate isn’t valid for this address.");
                    return;
                default:
                    ShowError("The server didn’t answer. Check that it’s running and that Tailscale is connected on this PC.");
                    return;
            }
        }

        void OnMessage(object sender, CoreWebView2WebMessageReceivedEventArgs e)
        {
            string m;
            try { m = e.TryGetWebMessageAsString(); }
            catch (ArgumentException) { return; }
            if (m == "vv:server") { ShowSetup(false); return; }   // Help & about's button, or the error page's
            if (!onOwnPage) return;   // the rest only from the app's own pages
            if (m == "vv:retry" || m == "vv:cancel") Go(server);
            else if (m.StartsWith("vv:connect:"))
            {
                string address = AppSettings.Normalize(m.Substring("vv:connect:".Length), out string error);
                if (address == null) { _ = web.CoreWebView2.ExecuteScriptAsync("bad(" + Js(error) + ")"); return; }
                server = address;
                AppSettings.Server = address;
                Go(address);
            }
        }

        bool IsServer(Uri u) =>
            Uri.TryCreate(server, UriKind.Absolute, out var s) && u.Scheme == s.Scheme
            && string.Equals(u.Host, s.Host, StringComparison.OrdinalIgnoreCase) && u.Port == s.Port;

        /// <summary>Opens a web or mail link in the default browser (nothing else is opened).</summary>
        static void Outside(string url)
        {
            if (!Uri.TryCreate(url, UriKind.Absolute, out var u) || (u.Scheme != "https" && u.Scheme != "http" && u.Scheme != "mailto")) return;
            try { Process.Start(new ProcessStartInfo(u.AbsoluteUri) { UseShellExecute = true }); }
            catch (Exception) { }
        }

        void Reload()
        {
            if (!ready) return;
            if (!onOwnPage) web.CoreWebView2.Reload();
            else if (server.Length > 0) Go(server);
        }

        // the page's full screen (its F key) fills the monitor; Esc gives the window back as it was
        void FullScreen(bool on)
        {
            if (on == full) return;
            full = on;
            if (on)
            {
                preState = WindowState;
                if (WindowState == FormWindowState.Maximized) WindowState = FormWindowState.Normal;
                preBounds = Bounds;
                FormBorderStyle = FormBorderStyle.None;
                Bounds = Screen.FromControl(this).Bounds;
            }
            else
            {
                FormBorderStyle = FormBorderStyle.Sizable;
                Bounds = preBounds;
                if (preState == FormWindowState.Maximized) WindowState = FormWindowState.Maximized;
            }
        }

        void RestoreWindow()
        {
            float k = DeviceDpi / 96f;
            MinimumSize = new Size((int)(760 * k), (int)(520 * k));
            int[] p = AppSettings.Window.Split(',').Select(x => int.TryParse(x, out int v) ? v : int.MinValue).ToArray();
            if (p.Length == 5 && !p.Contains(int.MinValue))
            {
                var r = new Rectangle(p[0], p[1], p[2], p[3]);
                if (Screen.AllScreens.Any(s => s.WorkingArea.IntersectsWith(r)))   // still on a monitor that's there
                {
                    StartPosition = FormStartPosition.Manual;
                    Bounds = r;
                    if (p[4] == 1) WindowState = FormWindowState.Maximized;
                    return;
                }
            }
            var wa = Screen.PrimaryScreen.WorkingArea;
            StartPosition = FormStartPosition.CenterScreen;
            Size = new Size(Math.Min((int)(1280 * k), wa.Width), Math.Min((int)(800 * k), wa.Height));
        }

        void SaveWindow()
        {
            var b = full ? preBounds : WindowState == FormWindowState.Normal ? Bounds : RestoreBounds;
            bool max = full ? preState == FormWindowState.Maximized : WindowState == FormWindowState.Maximized;
            AppSettings.Window = string.Join(",", b.X, b.Y, b.Width, b.Height, max ? 1 : 0);
        }

        /// <summary>A JavaScript string literal.</summary>
        static string Js(string s)
        {
            var b = new StringBuilder("\"");
            foreach (char c in s ?? "")
                b.Append(c == '"' || c == '\\' ? "\\" + c : c < ' ' || c > '~' || c == '<' ? "\\u" + ((int)c).ToString("x4") : c.ToString());
            return b.Append('"').ToString();
        }

        // ---- the window's frame: a dark title bar, and Reload / Change server address in its menu (right-click the
        // title bar, or Alt+Space) ----

        const int WM_SYSCOMMAND = 0x112, MF_STRING = 0, MF_SEPARATOR = 0x800, SW_RESTORE = 9;
        const int ID_RELOAD = 0x1010, ID_SERVER = 0x1020;   // window menu items: multiples of 16 below 0xF000

        [DllImport("user32.dll")] static extern IntPtr GetSystemMenu(IntPtr hWnd, bool revert);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern bool AppendMenu(IntPtr menu, int flags, int id, string text);
        [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr hWnd, int cmd);
        [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr hWnd, int attribute, ref int value, int size);

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            int dark = 1;   // DWMWA_USE_IMMERSIVE_DARK_MODE: 20 on current Windows, 19 on early Windows 10 builds
            if (DwmSetWindowAttribute(Handle, 20, ref dark, sizeof(int)) != 0) DwmSetWindowAttribute(Handle, 19, ref dark, sizeof(int));
            IntPtr menu = GetSystemMenu(Handle, false);
            AppendMenu(menu, MF_SEPARATOR, 0, null);
            AppendMenu(menu, MF_STRING, ID_RELOAD, "Reload\tF5");
            AppendMenu(menu, MF_STRING, ID_SERVER, "Change server address…");
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WM_SYSCOMMAND)
            {
                int id = (int)((long)m.WParam & 0xFFF0);
                if (id == ID_RELOAD) { Reload(); return; }
                if (id == ID_SERVER) { if (ready) ShowSetup(false); return; }
            }
            base.WndProc(ref m);
        }
    }
}
