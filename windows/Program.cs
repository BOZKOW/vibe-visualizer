using System;
using System.Linq;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Threading;
using System.Windows.Forms;

namespace VibeVisualizer
{
    /// <summary>Vibe Visualizer for Windows: your Vibe Visualizer server in its own window, as the Mac app does.</summary>
    static class Program
    {
        [STAThread]
        static void Main(string[] args)
        {
            AppDomain.CurrentDomain.AssemblyResolve += EmbeddedLibraries.Resolve;
            string link = args.FirstOrDefault(a => a.StartsWith("vibevisualizer:", StringComparison.OrdinalIgnoreCase));
            // one window: a second start (the Spotify login coming back from the browser, or the .exe opened again)
            // hands its link to the running one and ends
            bool first;
            using (var one = new Mutex(true, @"Local\VibeVisualizer.App", out first))
            {
                if (!first) { SingleWindow.Send(link); return; }
                LoginLink.Register();
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Run(link);
            }
        }

        // apart from Main, so the WebView2 types are looked up only once the embedded copies can be found
        [MethodImpl(MethodImplOptions.NoInlining)]
        static void Run(string link) => Application.Run(new AppWindow(link));

        public static string Version =>
            typeof(Program).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "";
    }
}
