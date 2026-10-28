using System;
using System.IO;
using System.Linq;
using Microsoft.Win32;

namespace VibeVisualizer
{
    /// <summary>What the app remembers, under HKEY_CURRENT_USER\Software\Vibe Visualizer.</summary>
    static class AppSettings
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
