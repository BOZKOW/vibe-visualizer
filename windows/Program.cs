using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Pipes;
using System.Linq;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

namespace VibeVisualizer
{
    /// <summary>Vibe Visualizer for Windows: your Vibe Visualizer server in its own window, as the Mac app does.</summary>
    static class Program
    {
        [STAThread]
        static void Main(string[] args)
        {
            AppDomain.CurrentDomain.AssemblyResolve += Embedded.Resolve;
            string link = args.FirstOrDefault(a => a.StartsWith("vibevisualizer:", StringComparison.OrdinalIgnoreCase));
            // one window: a second start (the Spotify login coming back from the browser, or the .exe opened again)
            // hands its link to the running one and ends
            bool first;
            using (var one = new Mutex(true, @"Local\VibeVisualizer.App", out first))
            {
                if (!first) { Handoff.Send(link); return; }
                Protocol.Register();
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Run(link);
            }
        }

        // apart from Main, so the WebView2 types are looked up only once the embedded copies can be found
        [MethodImpl(MethodImplOptions.NoInlining)]
        static void Run(string link) => Application.Run(new MainForm(link));

        public static string Version =>
            typeof(Program).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "";
    }

    /// <summary>The WebView2 libraries built into the .exe.</summary>
    static class Embedded
    {
        static readonly Dictionary<string, Assembly> loaded = new Dictionary<string, Assembly>();

        public static Assembly Resolve(object sender, ResolveEventArgs e)
        {
            string name = new AssemblyName(e.Name).Name + ".dll";
            lock (loaded)
            {
                if (loaded.TryGetValue(name, out var a)) return a;   // loaded once: two copies would be two different types
                byte[] bytes = Read(name);
                if (bytes == null) return null;
                a = Assembly.Load(bytes);
                loaded[name] = a;
                return a;
            }
        }

        /// <summary>WebView2Loader.dll for this processor, unpacked into the app's folder under AppData (once, and
        /// again when a newer app brings a different one). Returns that folder.</summary>
        public static string LoaderFolder()
        {
            string arch;
            switch ((Environment.GetEnvironmentVariable("PROCESSOR_ARCHITECTURE") ?? "").ToUpperInvariant())
            {
                case "ARM64": arch = "arm64"; break;
                case "X86": arch = "x86"; break;
                default: arch = "x64"; break;
            }
            string dir = Path.Combine(Settings.DataDir, "runtime", arch), file = Path.Combine(dir, "WebView2Loader.dll");
            byte[] bytes = Read(arch + ".WebView2Loader.dll");
            Directory.CreateDirectory(dir);
            if (!File.Exists(file) || !File.ReadAllBytes(file).SequenceEqual(bytes)) File.WriteAllBytes(file, bytes);
            return dir;
        }

        static byte[] Read(string resource)
        {
            using (var s = typeof(Embedded).Assembly.GetManifestResourceStream(resource))
            {
                if (s == null) return null;
                using (var m = new MemoryStream()) { s.CopyTo(m); return m.ToArray(); }
            }
        }
    }

    /// <summary>A second start passes its link to the window that's already open, over a named pipe.</summary>
    static class Handoff
    {
        static string Name => "VibeVisualizer.App." + Environment.UserName;

        [DllImport("user32.dll")] static extern bool AllowSetForegroundWindow(int processId);

        public static void Send(string link)
        {
            try
            {
                AllowSetForegroundWindow(-1);   // this start came from a click, so the open window may come to the front
                using (var pipe = new NamedPipeClientStream(".", Name, PipeDirection.Out))
                {
                    pipe.Connect(3000);
                    byte[] bytes = Encoding.UTF8.GetBytes((link ?? "") + "\n");
                    pipe.Write(bytes, 0, bytes.Length);
                    pipe.Flush();
                }
            }
            catch (Exception) { }   // the open window is closing: nothing to hand over to
        }

        /// <summary>Calls <paramref name="got"/> (on a background thread) with each link a second start sends.</summary>
        public static void Listen(Action<string> got)
        {
            new Thread(() =>
            {
                while (true)
                {
                    try
                    {
                        using (var pipe = new NamedPipeServerStream(Name, PipeDirection.In, 1))
                        {
                            pipe.WaitForConnection();
                            using (var r = new StreamReader(pipe, Encoding.UTF8)) got(r.ReadLine() ?? "");
                        }
                    }
                    catch (Exception) { Thread.Sleep(500); }
                }
            }) { IsBackground = true, Name = "handoff" }.Start();
        }
    }

    /// <summary>vibevisualizer://callback?…: the Spotify login, handed back from the browser. Registered for this
    /// user at every start, so it keeps pointing at this .exe wherever it's moved.</summary>
    static class Protocol
    {
        public static void Register()
        {
            try
            {
                string exe = Application.ExecutablePath;
                using (var k = Registry.CurrentUser.CreateSubKey(@"Software\Classes\vibevisualizer"))
                {
                    k.SetValue("", "URL:Vibe Visualizer");
                    k.SetValue("URL Protocol", "");
                    using (var i = k.CreateSubKey("DefaultIcon")) i.SetValue("", "\"" + exe + "\",0");
                    using (var c = k.CreateSubKey(@"shell\open\command")) c.SetValue("", "\"" + exe + "\" \"%1\"");
                }
            }
            catch (Exception) { }   // the login then finishes in the browser instead
        }
    }

    /// <summary>What the app remembers, under HKEY_CURRENT_USER\Software\Vibe Visualizer.</summary>
    static class Settings
    {
        const string KeyPath = @"Software\Vibe Visualizer";

        public static string DataDir =>
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Vibe Visualizer");

        public static string Server { get => Get("Server"); set => Set("Server", value); }
        public static string Window { get => Get("Window"); set => Set("Window", value); }

        static string Get(string name)
        {
            try { using (var k = Registry.CurrentUser.OpenSubKey(KeyPath)) return k?.GetValue(name) as string ?? ""; }
            catch (Exception) { return ""; }
        }

        static void Set(string name, string value)
        {
            try { using (var k = Registry.CurrentUser.CreateSubKey(KeyPath)) k.SetValue(name, value ?? ""); }
            catch (Exception) { }
        }

        /// <summary>The server's address as the Mac app takes it: https, a host, nothing after the path ("your-server.ts.net"
        /// becomes "https://your-server.ts.net/"). Null, with <paramref name="error"/> set, when it isn't one.</summary>
        public static string Normalize(string input, out string error)
        {
            error = null;
            string text = (input ?? "").Trim();
            if (text.Length > 0 && !text.Contains("://")) text = "https://" + text;
            if (!Uri.TryCreate(text, UriKind.Absolute, out var u) || u.Scheme != Uri.UriSchemeHttps || string.IsNullOrEmpty(u.Host)
                || u.UserInfo.Length > 0 || u.Query.Length > 0 || u.Fragment.Length > 0)
            {
                error = "Enter your server address, for example https://your-server.ts.net.";
                return null;
            }
            return u.GetLeftPart(UriPartial.Path);   // the path is "/" when there was none
        }
    }
}
