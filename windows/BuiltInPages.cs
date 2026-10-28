using System.Net;

namespace VibeVisualizer
{
    /// <summary>The app's own pages, drawn like the Mac app's: asking for the server's address, and saying it can't be
    /// reached. They talk to the window with postMessage ("vv:connect:…", "vv:cancel", "vv:retry", "vv:server").</summary>
    static class BuiltInPages
    {
        const string Style = @"<style>
:root{color-scheme:dark}*{box-sizing:border-box}html,body{height:100%;margin:0}
body{background:#07070c;color:#fff;font:14px/1.45 'Segoe UI Variable Text','Segoe UI',system-ui,sans-serif;display:grid;place-items:center;overflow:hidden;user-select:none}
.glow{position:fixed;border-radius:50%;pointer-events:none}
.g1{width:70vw;height:70vw;left:-30vw;top:-42vw;background:rgba(139,124,255,.27);filter:blur(100px)}
.g2{width:62vw;height:62vw;right:-26vw;bottom:-36vw;background:rgba(255,95,162,.15);filter:blur(110px)}
.g3{width:50vw;height:50vw;right:-8vw;top:-34vw;background:rgba(54,201,255,.1);filter:blur(105px)}
body::after{content:'';position:fixed;inset:0;pointer-events:none;background:radial-gradient(circle,transparent 35%,rgba(0,0,0,.28))}
main{position:relative;z-index:1;width:min(500px,calc(100vw - 64px))}
.brand{display:flex;align-items:center;gap:12px;font-size:12px;font-weight:700;letter-spacing:2.4px;color:rgba(255,255,255,.72)}
.mark{width:44px;height:44px;flex:none;border-radius:11px;display:flex;align-items:center;justify-content:center;gap:2.4px;
  background:linear-gradient(135deg,#8b7cff,#ff5fa2,#36c9ff);border:1px solid rgba(255,255,255,.18);box-shadow:0 5px 18px rgba(139,124,255,.3)}
.mark i{width:4.6px;border-radius:3px;background:rgba(255,255,255,.82)}.mark i:nth-child(3){background:#fff}
h1{margin:22px 0 8px;font-size:46px;font-weight:600;letter-spacing:-1.6px;line-height:1.05;background:linear-gradient(135deg,#fff,#d1c9ff);-webkit-background-clip:text;background-clip:text;color:transparent}
h2{margin:0 0 22px;font-size:22px;font-weight:700}
.lead{margin:0 0 22px;font-size:15px;color:rgba(255,255,255,.62)}
.card{padding:20px;border-radius:18px;background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.12);backdrop-filter:blur(30px)}
label{display:block;margin-bottom:8px;font-size:10px;font-weight:700;letter-spacing:1.5px;color:rgba(255,255,255,.54)}
input{width:100%;padding:13px 14px;border-radius:12px;border:1px solid rgba(255,255,255,.11);background:rgba(0,0,0,.28);color:#fff;font:500 14px 'Cascadia Mono',Consolas,monospace;outline:none;user-select:text}
input:focus{border-color:rgba(139,124,255,.75)}
.hint{margin:16px 0 0;font-size:12px;color:rgba(255,255,255,.54)}
.err{margin:12px 0 0;font-size:13px;color:#ff7a94}
.row{display:flex;align-items:center;gap:12px;margin-top:16px}.row .sp{flex:1}
button{font:600 13px 'Segoe UI Variable Text','Segoe UI',system-ui,sans-serif;border:0;border-radius:11px;padding:11px 18px;color:#fff;cursor:pointer}
.pri{background:linear-gradient(90deg,#8b7cff,#ff5fa2)}.pri:hover{filter:brightness(1.08)}
.plain{background:none;color:rgba(255,255,255,.75)}.plain:hover{color:#fff}
.foot{display:flex;align-items:center;gap:7px;margin:22px 0 0 2px;font-size:11px;color:rgba(255,255,255,.54)}
.foot svg{width:14px;height:14px;fill:#36c9ff}
.center{text-align:center;width:min(560px,calc(100vw - 64px));padding:32px;border-radius:24px;background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.12);backdrop-filter:blur(30px)}
.center .row{justify-content:center}.center h2{margin:14px 0 8px}.center .lead{margin:0 0 6px}
.addr{margin:0;font:500 12px 'Cascadia Mono',Consolas,monospace;color:rgba(255,255,255,.45)}
.big{width:36px;height:36px;fill:rgba(255,255,255,.6)}
</style>";

        const string Glow = "<div class=\"glow g1\"></div><div class=\"glow g2\"></div><div class=\"glow g3\"></div>";

        const string Mark = "<span class=\"mark\"><i style=\"height:15px\"></i><i style=\"height:21px\"></i><i style=\"height:27px\"></i>" +
                            "<i style=\"height:21px\"></i><i style=\"height:15px\"></i></span>";

        /// <summary>The server address page: the welcome the first time, "Connect to your server" when it's changed.</summary>
        public static string Setup(bool first, string address)
        {
            string head = first
                ? "<div class=\"brand\">" + Mark + "VIBE VISUALIZER</div><h1>Feel every<br>beat.</h1><p class=\"lead\">Lyrics, color, and motion — all in sync.</p>"
                : "<h2>Connect to your server</h2>";
            string cancel = first || address.Length == 0 ? "" : "<button class=\"plain\" type=\"button\" id=\"c\">Cancel</button>";
            string foot = first
                ? "<p class=\"foot\"><svg viewBox=\"0 0 24 24\"><path d=\"M12 2 4 5v6c0 5 3.4 9.7 8 11 4.6-1.3 8-6 8-11V5l-8-3zm-1 14-4-4 1.4-1.4L11 13.2l4.6-4.6L17 10l-6 6z\"/></svg>" +
                  "Your settings stay on your server and this PC.</p>"
                : "";
            return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Vibe Visualizer</title>" + Style + "</head><body>" + Glow +
                "<main>" + head +
                "<form class=\"card\" id=\"f\"><label for=\"a\">YOUR SERVER</label>" +
                "<input id=\"a\" value=\"" + WebUtility.HtmlEncode(address) + "\" placeholder=\"https://your-server.ts.net\" spellcheck=\"false\" autocomplete=\"off\" autofocus>" +
                "<p class=\"hint\">Keep Tailscale connected on this PC if your server is on your tailnet.</p>" +
                "<p class=\"err\" id=\"e\" hidden></p>" +
                "<div class=\"row\">" + cancel + "<span class=\"sp\"></span><button class=\"pri\" type=\"submit\">Connect →</button></div></form>" +
                foot + "</main><script>" +
                "const post=m=>window.chrome.webview.postMessage(m),c=document.getElementById('c');" +
                "document.getElementById('f').onsubmit=e=>{e.preventDefault();post('vv:connect:'+document.getElementById('a').value)};" +
                "function bad(t){const e=document.getElementById('e');e.textContent=t;e.hidden=false}" +
                "if(c)c.onclick=()=>post('vv:cancel');addEventListener('keydown',e=>{if(e.key==='Escape'&&c)post('vv:cancel')});" +
                "const a=document.getElementById('a');a.focus();a.select();" +
                "</script></body></html>";
        }

        /// <summary>The server didn't answer: why, and the two ways on.</summary>
        public static string Error(string host, string message)
        {
            return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Vibe Visualizer</title>" + Style + "</head><body>" + Glow +
                "<main class=\"center\"><svg class=\"big\" viewBox=\"0 0 24 24\"><path d=\"M2.3 3.7 3.7 2.3l18 18-1.4 1.4-3.6-3.6A9 9 0 0 1 12 19.5a9 9 0 0 1-6.4-2.6l1.4-1.4a7 7 0 0 0 8.3 1.2l-1.5-1.5a5 5 0 0 1-5.4-1.1l1.4-1.4a3 3 0 0 0 2.4.9L2.3 3.7zM12 4.5a12 12 0 0 1 10.6 4.2l-1.4 1.4A10 10 0 0 0 9.5 6.7L8 5.2A12 12 0 0 1 12 4.5zm0 4a8 8 0 0 1 6.7 3.1l-1.4 1.4a6 6 0 0 0-3.2-2l-2.4-2.4L12 8.5zM1.4 8.7A12 12 0 0 1 4.6 6.1L6 7.5a10 10 0 0 0-3.2 2.6L1.4 8.7z\"/></svg>" +
                "<h2>Your server can’t be reached</h2><p class=\"lead\">" + WebUtility.HtmlEncode(message) + "</p>" +
                "<p class=\"addr\">" + WebUtility.HtmlEncode(host) + "</p>" +
                "<div class=\"row\"><button class=\"pri\" id=\"r\">Try again</button><button class=\"plain\" id=\"s\">Change server address</button></div></main>" +
                "<script>const post=m=>window.chrome.webview.postMessage(m);" +
                "document.getElementById('r').onclick=()=>post('vv:retry');document.getElementById('s').onclick=()=>post('vv:server');" +
                "</script></body></html>";
        }
    }
}
