using System;
using System.Windows.Forms;
using Microsoft.Win32;

namespace VibeVisualizer
{
    /// <summary>vibevisualizer://callback?…: the Spotify login, handed back from the browser. Registered for this
    /// user at every start, so it keeps pointing at this .exe wherever it's moved.</summary>
    static class LoginLink
    {
        public static void Register()
        {
            try
            {
                string exe = Application.ExecutablePath;
                using (var k = Registry.CurrentUser.CreateSubKey(@"Software\Classes\vibevisualizer"))
                {
                    k.SetValue("", "URL:Vibe Visualizer");
                    k.SetValue("URL Protocol", "");   // the value Windows looks for: this key is a link type
                    using (var i = k.CreateSubKey("DefaultIcon")) i.SetValue("", "\"" + exe + "\",0");
                    using (var c = k.CreateSubKey(@"shell\open\command")) c.SetValue("", "\"" + exe + "\" \"%1\"");
                }
            }
            catch (Exception) { }   // the login then finishes in the browser instead
        }
    }
}
