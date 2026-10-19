package io.github.bozkow.vibevisualizer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.http.SslError;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;

/**
 * Vibe Visualizer for Android: opens your Vibe Visualizer server (reached over Tailscale) full screen. Everything else
 * (lyrics, Canvas, settings, updates) comes from the server, so the app itself rarely needs updating. The phone's own
 * sound reaches the bars through AudioTap (Vibe Bridge built in).
 */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 1, REQ_AUDIO = 2;
    private WebView web;
    private SharedPreferences prefs;
    private final AudioTap tap = new AudioTap();
    private boolean tapWanted, askedAudio, errorShown, gone;
    private ValueCallback<Uri[]> fileCb;
    private PermissionRequest micReq;
    private String insetsJs = "";

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("vv", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   // a visualizer you watch: the screen stays on
        if (Build.VERSION.SDK_INT >= 28) {   // use the whole screen, beside the camera cutout too (Android leaves a black band otherwise)
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(7, 7, 12));
        FrameLayout root = new FrameLayout(this);
        root.addView(web);
        setContentView(root);
        immersive();
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            insets(ins);   // edge to edge, so the keyboard doesn't resize the page by itself: keep Settings' fields above it
            if (Build.VERSION.SDK_INT >= 30) v.setPadding(0, 0, 0, ins.getInsets(WindowInsets.Type.ime()).bottom);
            return ins;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " VibeVisualizerAndroid/" + version());
        web.addJavascriptInterface(new Js(), "VVAndroid");

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (inApp(u)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (ActivityNotFoundException ignored) { }
                return true;   // other sites (GitHub, a lyrics maker's profile) open in the browser
            }
            @Override public void onPageFinished(WebView v, String url) { if (!insetsJs.isEmpty()) v.evaluateJavascript(insetsJs, null); }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) cantReach(String.valueOf(e.getDescription()), e.getErrorCode() == ERROR_HOST_LOOKUP);
            }
            @Override public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
                h.cancel();   // never load past a bad certificate; without this the page just stays black
                if (e.getUrl() != null && inApp(Uri.parse(e.getUrl()))) cantReach("certificate error " + e.getPrimaryError(), false);
            }
            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                // the page crashed or Android took its memory: start over instead of letting Android close the app
                gone = true;
                ((ViewGroup) v.getParent()).removeView(v);
                v.destroy();
                recreate();
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(Intent.createChooser(i, "Choose a file"), REQ_FILE); }
                catch (ActivityNotFoundException e) { fileCb = null; return false; }
                return true;
            }
            @Override public void onPermissionRequest(PermissionRequest r) {   // the page's microphone / input-device capture
                for (String res : r.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                        if (hasAudio()) r.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                        else { micReq = r; requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO); }
                        return;
                    }
                }
                r.deny();
            }
        });

        String server = prefs.getString("server", null);
        if (server == null) askServer(true);
        else if (saved == null || web.restoreState(saved) == null) web.loadUrl(server);
    }

    // ---------- the server ----------
    private boolean inApp(Uri u) {
        String server = prefs.getString("server", ""), host = u.getHost() == null ? "" : u.getHost();
        if (host.equals(Uri.parse(server).getHost())) return true;
        return host.equals("spotify.com") || host.endsWith(".spotify.com");   // the Spotify login comes back to the app
    }

    private static String normalize(String a) {
        a = a.trim();
        if (a.isEmpty()) return null;
        a = a.replaceFirst("(?i)^https?://", "");
        a = "https://" + a;   // the server is reached through tailscale serve's HTTPS; plain http isn't allowed
        Uri u = Uri.parse(a);
        if (u.getHost() == null || u.getHost().isEmpty()) return null;
        return u.getScheme() + "://" + u.getAuthority() + "/";
    }

    private void askServer(boolean first) {
        EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        in.setHint("https://your-server.ts.net");
        in.setSingleLine(true);
        String cur = prefs.getString("server", null);
        if (cur != null) in.setText(cur);
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(in);
        AlertDialog d = new AlertDialog.Builder(this, R.style.VVDialog)
            .setTitle(first ? "Welcome to Vibe Visualizer" : "Server address")
            .setMessage("The address of your Vibe Visualizer server, the same one you open on your computer. "
                + "If it’s reached through Tailscale, keep the Tailscale app connected on this phone.")
            .setView(box)
            .setPositiveButton("Open", null)
            .setNegativeButton(first ? "Quit" : "Cancel", (x, w) -> { if (first) finish(); })
            .setCancelable(!first)
            .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            String a = normalize(in.getText().toString());
            if (a == null) { in.setError("Type an address like https://your-server.ts.net"); return; }
            prefs.edit().putString("server", a).apply();
            d.dismiss();
            web.clearHistory();
            web.loadUrl(a);
        }));
        d.show();
    }

    private void cantReach(String why, boolean lookup) {
        if (errorShown || isFinishing()) return;
        errorShown = true;
        web.loadData("<html><body style='background:#07070c'></body></html>", "text/html", "utf-8");
        String msg = lookup ? lookupHint()
            : why.startsWith("certificate") ? "The server’s HTTPS certificate doesn’t fit this address. Use the "
                + "https://NAME.ts.net address you open on your computer, not an IP address."
            : "Check that the server is on and Tailscale is connected on this phone.";
        new AlertDialog.Builder(this, R.style.VVDialog)
            .setTitle("Your server can’t be reached")
            .setMessage(msg + "\n\n(" + why + ")")
            .setPositiveButton("Try again", (x, w) -> { errorShown = false; web.loadUrl(prefs.getString("server", "")); })
            .setNeutralButton("Change address", (x, w) -> { errorShown = false; askServer(false); })
            .setNegativeButton("Quit", (x, w) -> finish())
            .setCancelable(false)
            .show();
    }

    /** Why the server's name can't be looked up: Tailscale isn't connected, Private DNS bypasses it, or MagicDNS is off. */
    private String lookupHint() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network act = cm == null ? null : cm.getActiveNetwork();
        NetworkCapabilities nc = act == null ? null : cm.getNetworkCapabilities(act);
        if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            return "Tailscale isn’t connected on this phone (or this app is excluded from it). Open the Tailscale app, "
                + "connect, then try again.";
        if (Build.VERSION.SDK_INT >= 28) {
            for (Network n : cm.getAllNetworks()) {
                LinkProperties lp = cm.getLinkProperties(n);
                String dns = lp == null ? null : lp.getPrivateDnsServerName();
                if (dns != null)
                    return "Android’s Private DNS (" + dns + ") is on, so this phone doesn’t ask Tailscale for your "
                        + "server’s name. Set Private DNS to Off or Automatic in Android’s settings (Network & internet, "
                        + "or Connections → More connection settings on Samsung), then try again.";
            }
        }
        return "This phone doesn’t ask Tailscale for your server’s name. In the Tailscale app turn on "
            + "Settings → Use Tailscale DNS, reconnect, then try again.";
    }

    // ---------- Vibe Bridge built in ----------
    private boolean hasAudio() { return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED; }

    private void tapStart() {   // on the main thread: the Visualizer delivers its data on this thread's looper
        tapWanted = true;
        if (hasAudio()) { tap.start(); return; }
        if (askedAudio) return;
        askedAudio = true;
        new AlertDialog.Builder(this, R.style.VVDialog)
            .setTitle("Let the bars follow your music")
            .setMessage("To make the bars move with what this phone plays (the Spotify app too), Android needs the "
                + "audio permission. Vibe Visualizer only reads the sound’s spectrum: nothing is recorded or sent anywhere.")
            .setPositiveButton("Continue", (x, w) -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO))
            .setNegativeButton("Not now", null)
            .show();
    }

    final class Js {
        @JavascriptInterface public String frame() { return tap.take(); }
        @JavascriptInterface public void start() { runOnUiThread(MainActivity.this::tapStart); }
        @JavascriptInterface public void stop() { runOnUiThread(() -> { tapWanted = false; tap.stop(); }); }
        @JavascriptInterface public String status() {
            return tap.running() ? "on" : !hasAudio() ? "permission" : tap.error != null ? "error: " + tap.error : "off";
        }
        @JavascriptInterface public String device() { return "this phone"; }
        @JavascriptInterface public String version() { return MainActivity.this.version(); }
        @JavascriptInterface public String server() { return prefs.getString("server", ""); }
        @JavascriptInterface public void changeServer() { runOnUiThread(() -> askServer(false)); }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ_AUDIO) return;
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        if (micReq != null) {
            if (ok) micReq.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE}); else micReq.deny();
            micReq = null;
        }
        if (ok && tapWanted) tap.start();
    }

    // ---------- the rest ----------
    private String version() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException e) { return "?"; }
    }

    /** Tells the page how much of each edge the camera cutout covers (CSS px), as --sat/--sab/--sal/--sar. */
    private void insets(WindowInsets ins) {
        float d = getResources().getDisplayMetrics().density;
        int t = 0, b = 0, l = 0, r = 0;
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout c = ins.getDisplayCutout();
            if (c != null) { t = c.getSafeInsetTop(); b = c.getSafeInsetBottom(); l = c.getSafeInsetLeft(); r = c.getSafeInsetRight(); }
        }
        String js = "(function(s){s.setProperty('--sat','" + Math.round(t / d) + "px');s.setProperty('--sab','" + Math.round(b / d)
            + "px');s.setProperty('--sal','" + Math.round(l / d) + "px');s.setProperty('--sar','" + Math.round(r / d)
            + "px')})(document.documentElement.style)";
        if (js.equals(insetsJs)) return;
        insetsJs = js;
        web.evaluateJavascript(js, null);
    }

    private void immersive() {   // full screen; a swipe from the edge shows the system bars for a moment
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) immersive(); }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        if (code == REQ_FILE && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCb = null;
            return;
        }
        super.onActivityResult(code, result, data);
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {   // back closes what's open in the app first (Settings, a panel), then leaves
        web.evaluateJavascript("window.vvBack?vvBack():false", v -> {
            if ("true".equals(v)) return;
            if (web.canGoBack()) web.goBack(); else moveTaskToBack(true);
        });
    }

    @Override protected void onResume() { super.onResume(); if (!gone) web.onResume(); if (tapWanted && hasAudio()) tap.start(); }
    @Override protected void onPause() { super.onPause(); if (!gone) web.onPause(); tap.stop(); }   // nothing runs in the background
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); if (!gone) web.saveState(out); }
    @Override protected void onDestroy() { tap.stop(); if (!gone) web.destroy(); super.onDestroy(); }
}
