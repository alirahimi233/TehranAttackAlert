package com.example.tehranalert;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Html;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AlertService extends Service {
    public static final String ACTION_START = "com.example.tehranalert.START";
    public static final String ACTION_STOP_SERVICE = "com.example.tehranalert.STOP_SERVICE";
    public static final String ACTION_TEST = "com.example.tehranalert.TEST";
    public static final String ACTION_STOP_ALARM = "com.example.tehranalert.STOP_ALARM";

    private static final String SERVICE_CHANNEL = "monitoring_channel";
    private static final String ALERT_CHANNEL = "critical_alert_channel_v3";
    private static final int SERVICE_NOTIFICATION_ID = 100;
    private static final long CHECK_INTERVAL_MS = 60_000L;
    private static final int MAX_DOWNLOAD_BYTES = 1_500_000;

    private volatile boolean running;
    private Thread monitorThread;
    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP_SERVICE.equals(action)) {
            stopAlarm();
            running = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(SERVICE_NOTIFICATION_ID, serviceNotification("در حال آماده‌سازی پایش..."));

        if (ACTION_TEST.equals(action)) {
            triggerAlarm("آژیر آزمایشی", "تست صدای جنگ توسط کاربر");
        } else if (ACTION_STOP_ALARM.equals(action)) {
            stopAlarm();
        } else {
            SourceStore.setMonitoring(this, true);
            startMonitorLoop();
        }
        return START_STICKY;
    }

    private synchronized void startMonitorLoop() {
        if (running) return;
        running = true;
        monitorThread = new Thread(() -> {
            while (running && SourceStore.isMonitoring(this)) {
                List<SourceStore.Source> sources = SourceStore.load(this);
                int activeCount = 0;
                for (SourceStore.Source source : sources) {
                    if (!running || !source.enabled) continue;
                    activeCount++;
                    checkSource(source);
                }
                updateServiceNotification("پایش فعال؛ " + activeCount + " منبع خبری در حال بررسی...");
                sleepInterruptibly(CHECK_INTERVAL_MS);
            }
            running = false;
        }, "TehranAlertMonitor");
        monitorThread.start();
    }

    private void checkSource(SourceStore.Source source) {
        PowerManager.WakeLock fetchLock = null;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            fetchLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TehranAlert:Fetch");
            fetchLock.acquire(45_000L);
            String html = download(source.url);
            
            if (source.url.contains("t.me/") || source.url.contains("eitaa.com/")) {
                checkSocialPosts(source, html);
            } else {
                checkWebPage(source, html);
            }
        } catch (Exception ignored) {
        } finally {
            if (fetchLock != null && fetchLock.isHeld()) fetchLock.release();
        }
    }

    private void checkSocialPosts(SourceStore.Source source, String html) {
        List<Post> posts = parseSocialPosts(html);
        if (posts.isEmpty()) return;
        Collections.sort(posts, Comparator.comparingLong(p -> p.id));
        
        String key = "last_post_" + sha256(source.url);
        long lastSeen = SourceStore.prefs(this).getLong(key, -1L);
        long newest = posts.get(posts.size() - 1).id;

        if (lastSeen < 0) {
            SourceStore.prefs(this).edit().putLong(key, newest).apply();
            return;
        }

        for (Post post : posts) {
            if (post.id > lastSeen && isAttackReport(post.text)) {
                triggerAlarm("⚠️ هشدار جدی حمله ⚠️", source.name + ": " + shorten(post.text));
                break;
            }
        }
        if (newest > lastSeen) SourceStore.prefs(this).edit().putLong(key, newest).apply();
    }

    private void checkWebPage(SourceStore.Source source, String html) {
        String text = normalize(stripHtml(html));
        String key = "page_hash_" + sha256(source.url);
        String oldHash = SourceStore.prefs(this).getString(key, "");
        String newHash = sha256(text);
        
        if (oldHash.isEmpty()) {
            SourceStore.prefs(this).edit().putString(key, newHash).apply();
            return;
        }
        
        if (!newHash.equals(oldHash)) {
            SourceStore.prefs(this).edit().putString(key, newHash).apply();
            if (isAttackReport(text)) {
                triggerAlarm("هشدار خبر فوری", "تغییرات مهم در منبع: " + source.name);
            }
        }
    }

    private boolean isAttackReport(String raw) {
        String text = normalize(raw);

        boolean hasLocation = containsAny(text, "تهران", "پایتخت", "فرودگاه امام", "کرج", "مرکز کشور");
        
        boolean isCritical = containsAny(text, 
          "آژیر خطر", "حمله موشکی", "شلیک موشک", "انفجار شدید", 
            "وضعیت قرمز", "بمباران", "پهپاد انتحاری");

        boolean isWarning = containsAny(text, 
            "فوری", "خبر فوری", "حمله", "موشک", "پهپاد", "پدافند", "صدای انفجار");

        if (isCritical) return true;

        return hasLocation && isWarning;
    }

    private List<Post> parseSocialPosts(String html) {
        List<Post> result = new ArrayList<>();
        Pattern block = Pattern.compile("data-post=\"[^/]+/(\\d+)\"[\\s\\S]*?(?=<div class=\"[^\"]*message_text)");
        Pattern message = Pattern.compile("class=\"[^\"]*message_text[^\"]*\">([\\s\\S]*?)</div>");
        
        Matcher blocks = block.matcher(html);
        while (blocks.find()) {
            long id;
            try { id = Long.parseLong(blocks.group(1)); } catch (Exception e) { continue; }
            String content = "";
            Matcher body = message.matcher(html.substring(blocks.start()));
            if (body.find()) content = stripHtml(body.group(1));
            result.add(new Post(id, content));
        }
        return result;
    }

    private String download(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        int status = connection.getResponseCode();
        if (status != 200) throw new Exception("Error " + status);
        try (InputStream input = connection.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[8192];
            int read, total = 0;
            while ((read = reader.read(buffer)) != -1 && total < MAX_DOWNLOAD_BYTES) {
                out.append(buffer, 0, read);
                total += read;
            }
            return out.toString();
        } finally {
            connection.disconnect();
        }
    }

    private synchronized void triggerAlarm(String title, String details) {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
        }

        player = MediaPlayer.create(this, com.example.tehranalert.R.raw.war_siren);
        if (player != null) {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setLooping(true);
            player.start();
        }

        if (vibrator != null) {
            long[] pattern = {0, 1500, 500, 1500, 500};
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (wakeLock == null) wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "TehranAlert:AlarmScreen");
        if (!wakeLock.isHeld()) wakeLock.acquire(5 * 60_000L);

        getSystemService(NotificationManager.class).notify(200, alertNotification(title, details));
    }

    private synchronized void stopAlarm() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
        if (vibrator != null) vibrator.cancel();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        getSystemService(NotificationManager.class).cancel(200);
    }

    private Notification serviceNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, SERVICE_CHANNEL)
                .setSmallIcon(com.example.tehranalert.R.drawable.ic_alert)
                .setContentTitle("Attack Tehran")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private Notification alertNotification(String title, String details) {
        Intent stop = new Intent(this, AlertService.class).setAction(ACTION_STOP_ALARM);
        PendingIntent stopPending = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(com.example.tehranalert.R.drawable.ic_alert)
                .setContentTitle(title)
                .setContentText(details)
                .setFullScreenIntent(null, true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "خاموش کردن آژیر", stopPending).build())
                .build();
    }

    private void createChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(SERVICE_CHANNEL, "سرویس پایش", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel alert = new NotificationChannel(ALERT_CHANNEL, "هشدارهای بحرانی", NotificationManager.IMPORTANCE_HIGH);
        alert.enableVibration(true);
        alert.setBypassDnd(true);
        manager.createNotificationChannel(alert);
    }

    private void updateServiceNotification(String text) {
        getSystemService(NotificationManager.class).notify(SERVICE_NOTIFICATION_ID, serviceNotification(text));
    }

    private void sleepInterruptibly(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private String stripHtml(String html) {
        return Html.fromHtml(html.replaceAll("(?i)<br\\s*/?>", "\n"), Html.FROM_HTML_MODE_LEGACY).toString();
    }

    private String normalize(String value) {
        return value.toLowerCase(new Locale("fa")).replace('ي', 'ی').replace('ك', 'ک').replaceAll("[\\u200c\\s]+", " ").trim();
    }

    private String shorten(String value) {
        return value.length() <= 150 ? value : value.substring(0, 150) + "...";
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : hash) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) { return String.valueOf(value.hashCode()); }
    }

    @Override public void onDestroy() { running = false; stopAlarm(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
    private static final class Post { final long id; final String text; Post(long id, String text) { this.id = id; this.text = text; } }
}
