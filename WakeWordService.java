package com.example.pulkit.sos;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.telecom.TelecomManager;
import android.telephony.SmsManager;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Foreground service (type: microphone) that keeps listening for
 * "Hello Jashpi", "bachao" x3 and "help" x3 within 10 s, even when the app is closed.
 * On trigger: SMS to all saved contacts, auto-call to contact 1, Telegram via the Render relay.
 */
public class WakeWordService extends Service implements RecognitionListener {
    public static final String CHANNEL = "jashpi_listen";
    private static final int NOTIF_ID = 4107;
    private static final Pattern WAKE = Pattern.compile("hello\\s*j[ae]s?h?\\s*p[iy]|hello\\s*jaspi|हैलो\\s*ज[शस]्?पी");
    private static final Pattern BACHAO = Pattern.compile("bacha+o|bachav|बचाओ|बचाव");
    private static final Pattern HELP = Pattern.compile("\\bhelp\\b|हेल्प|madad|madat|मदद");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Long> bachao = new ArrayList<>(), help = new ArrayList<>();
    private SpeechRecognizer sr;
    private AudioManager am;
    private boolean running, muted;
    private long lastSos;
    private int lastB, lastH;
    private final List<Long> cough = new ArrayList<>();
    private long burstStart;

    /** Call once (e.g. after contacts are saved). relayUrl = your Render https URL, code = 16-char code shown in the PWA. */
    public static void saveConfig(Context c, String name, String relayUrl, String code) {
        c.getSharedPreferences("JashpiConfig", MODE_PRIVATE).edit()
                .putString("name", name).putString("relay", relayUrl).putString("code", code).apply();
    }

    public static void start(Context c) {
        Intent i = new Intent(c, WakeWordService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override public void onCreate() {
        super.onCreate();
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "JASHPI listening", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, WelcomeActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setContentTitle("JASHPI Listening...").setContentText("Say \"Hello Jashpi\" or \"Bachao\" 3 times")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).setContentIntent(pi).build();
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTIF_ID, n);
        } catch (RuntimeException e) { stopSelf(); return; }
        running = true;
        main.post(this::listen);
    }

    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }

    private void listen() {
        if (!running || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        if (sr != null) sr.destroy();
        sr = SpeechRecognizer.createSpeechRecognizer(this);
        sr.setRecognitionListener(this);
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        mute(true);
        lastB = 0; lastH = 0;
        sr.startListening(i);
    }

    private void mute(boolean m) { // hides the restart "beep"
        if (m == muted || am == null) return;
        muted = m;
        try { am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, m ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0); } catch (Exception ignored) { }
    }

    private void restart(long delay) { if (running) main.postDelayed(this::listen, delay); }

    private void handle(Bundle b) {
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (r == null || r.isEmpty()) return;
        String t = r.get(0).toLowerCase();
        long now = System.currentTimeMillis();
        if (WAKE.matcher(t).find()) { fire("wake word", false); return; }
        lastB = count(BACHAO, t, bachao, lastB, now);
        lastH = count(HELP, t, help, lastH, now);
        prune(bachao, now); prune(help, now);
        if (bachao.size() >= 3) { bachao.clear(); fire("bachao x3", false); }
        else if (help.size() >= 3) { help.clear(); fire("help / madad x3", false); }
    }

    private int count(Pattern p, String t, List<Long> hits, int prev, long now) {
        Matcher m = p.matcher(t); int n = 0;
        while (m.find()) n++;
        for (int j = prev; j < n; j++) hits.add(now);
        return n;
    }

    private void prune(List<Long> l, long now) { for (int i = l.size() - 1; i >= 0; i--) if (now - l.get(i) > 10000) l.remove(i); }

    @Override public void onPartialResults(Bundle b) { handle(b); }
    @Override public void onResults(Bundle b) { handle(b); restart(150); }
    @Override public void onError(int e) { restart(e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1500 : 250); }
    @Override public void onReadyForSpeech(Bundle b) { main.postDelayed(() -> mute(false), 400); }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float v) { // cough = short (80-500 ms) loud burst, 3 within 10 s -> silent SOS
        long now = System.currentTimeMillis();
        if (v > 7f) { if (burstStart == 0) burstStart = now; }
        else if (burstStart != 0) {
            long d = now - burstStart; burstStart = 0;
            if (d > 80 && d < 500) { cough.add(now); prune(cough, now); if (cough.size() >= 3) { cough.clear(); fire("cough x3", true); } }
        }
    }
    @Override public void onBufferReceived(byte[] b) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onEvent(int t, Bundle b) { }

    /* ---------- SOS: SMS + call + Telegram ---------- */
    private void fire(String why, boolean silent) {
        long now = System.currentTimeMillis();
        if (now - lastSos < 30000) return;
        lastSos = now;
        final Context c = getApplicationContext();
        new Thread(() -> dispatch(c, null, silent)).start();
    }

    /** SMS to ALL saved contacts, Telegram via relay, and (unless silent) automatic call to contact #1. Also used by the WebView SOS button. */
    public static void dispatch(Context c, String textOverride, boolean silent) {
        SharedPreferences cfg = c.getSharedPreferences("JashpiConfig", MODE_PRIVATE);
        String name = cfg.getString("name", "User");
        List<String> numbers = new ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(cfg.getString("contacts", "[]"));
            for (int k = 0; k < a.length(); k++) { String n = a.getJSONObject(k).getString("phone").replaceAll("[^+0-9]", ""); if (!n.isEmpty()) numbers.add(n); }
        } catch (Exception ignored) { }
        Location l = lastLocation(c);
        String link = l == null ? "(unavailable)" : "https://maps.google.com/?q=" + l.getLatitude() + "," + l.getLongitude();
        String text = textOverride != null ? textOverride.replace("(location unavailable)", link) : name + " is not safe, problem, location: " + link;
        if (c.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            SmsManager sm = Build.VERSION.SDK_INT >= 31 ? c.getSystemService(SmsManager.class) : SmsManager.getDefault();
            for (String n : numbers) { try { sm.sendMultipartTextMessage(n, null, sm.divideMessage(text), null, null); } catch (Exception ignored) { } }
        }
        if (textOverride == null) telegram(cfg, text, l);   // the WebView path already posted to the relay
        if (!silent && !numbers.isEmpty()) call(c, numbers.get(0));
    }

    private static Location lastLocation(Context c) {
        try {
            if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
            LocationManager lm = (LocationManager) c.getSystemService(LOCATION_SERVICE);
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            return best;
        } catch (Exception e) { return null; }
    }

    private static void call(Context c, String number) {
        if (c.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return;
        Uri uri = Uri.fromParts("tel", number, null);
        try {
            boolean overlay = Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(c);
            if (overlay) c.startActivity(new Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            else if (Build.VERSION.SDK_INT >= 23) ((TelecomManager) c.getSystemService(TELECOM_SERVICE)).placeCall(uri, new Bundle());
            else c.startActivity(new Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try { c.startActivity(new Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
        }
    }

    private static void telegram(SharedPreferences cfg, String text, Location l) {
        String relay = cfg.getString("relay", ""), code = cfg.getString("code", "");
        if (relay.isEmpty() || code.isEmpty()) return;
        try {
            String json = "{\"code\":\"" + code + "\",\"ids\":" + cfg.getString("tg", "[]") + ",\"text\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                    + (l != null ? ",\"lat\":" + l.getLatitude() + ",\"lng\":" + l.getLongitude() : "") + "}";
            HttpURLConnection c = (HttpURLConnection) new URL(relay.replaceAll("/$", "") + "/sos").openConnection();
            c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream o = c.getOutputStream(); o.write(json.getBytes("UTF-8")); o.close();
            c.getResponseCode(); c.disconnect();
        } catch (Exception ignored) { }
    }

    @Override public void onDestroy() {
        running = false; mute(false);
        main.removeCallbacksAndMessages(null);
        if (sr != null) sr.destroy();
        super.onDestroy();
    }
}
