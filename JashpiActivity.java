package com.example.pulkit.sos;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/** Launcher screen. Loads the JASHPI PWA (calculator front door) from your Render URL and bridges SOS to native SMS + auto-call. */
public class JashpiActivity extends Activity {
    // ONE value you must set: your Render app URL (https, no trailing slash).
    static final String APP_URL = "https://YOUR-APP.onrender.com";

    private WebView web;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestAll();
        askOverlay();
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "Jashpi");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onGeolocationPermissionsShowPrompt(String o, GeolocationPermissions.Callback cb) { cb.invoke(o, true, false); }
            @Override public void onPermissionRequest(PermissionRequest r) { r.grant(r.getResources()); }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest r) {
                Uri u = r.getUrl();
                if (u.toString().startsWith(APP_URL)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });
        web.loadUrl(getIntent().getData() != null ? getIntent().getData().toString() : APP_URL);
    }

    /** "Display over other apps" lets the service start the phone call from the background without any tap. */
    private void askOverlay() {
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this))
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
    }

    private void requestAll() {
        if (Build.VERSION.SDK_INT < 23) return;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}, 7);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        super.onRequestPermissionsResult(rc, p, r);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening();
    }

    private void startListening() { try { WakeWordService.start(this); } catch (Exception ignored) { } }

    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else moveTaskToBack(true); }

    /** Methods the page calls as window.Jashpi.* */
    class Bridge {
        @JavascriptInterface public void sync(String json) {
            try {
                JSONObject o = new JSONObject(json);
                SharedPreferences.Editor e = getSharedPreferences("JashpiConfig", MODE_PRIVATE).edit();
                e.putString("name", o.optString("name")).putString("relay", o.optString("relay"))
                 .putString("code", o.optString("code")).putString("tg", o.optJSONArray("tg") == null ? "[]" : o.getJSONArray("tg").toString()).putString("contacts", o.optJSONArray("contacts") == null ? "[]" : o.getJSONArray("contacts").toString()).apply();
                if (!o.optString("name").isEmpty()) runOnUiThread(JashpiActivity.this::startListening);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface public void share(final String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "Share"));
            });
        }

        @JavascriptInterface public void sos(final String text, final boolean silent) {
            final android.content.Context c = getApplicationContext();
            new Thread(() -> WakeWordService.dispatch(c, text, silent)).start();
        }
    }
}
