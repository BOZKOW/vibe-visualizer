using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;

namespace VibeVisualizer
{
    /// <summary>The WebView2 libraries built into the .exe.</summary>
    static class EmbeddedLibraries
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
            string dir = Path.Combine(AppSettings.DataDir, "runtime", arch), file = Path.Combine(dir, "WebView2Loader.dll");
            byte[] bytes = Read(arch + ".WebView2Loader.dll");
            Directory.CreateDirectory(dir);
            if (!File.Exists(file) || !File.ReadAllBytes(file).SequenceEqual(bytes)) File.WriteAllBytes(file, bytes);
            return dir;
        }

        static byte[] Read(string resource)
        {
            using (var s = typeof(EmbeddedLibraries).Assembly.GetManifestResourceStream(resource))
            {
                if (s == null) return null;
                using (var m = new MemoryStream()) { s.CopyTo(m); return m.ToArray(); }
            }
        }
    }
}
