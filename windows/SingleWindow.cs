using System;
using System.IO;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace VibeVisualizer
{
    /// <summary>A second start passes its link to the window that's already open, over a named pipe.</summary>
    static class SingleWindow
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
}
