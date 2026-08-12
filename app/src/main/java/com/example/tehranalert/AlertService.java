```java
package com.example.tehranalert;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Html;
import android.util.Log;

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

    public static final String ACTION_START =
            "com.example.tehranalert.START";

    public static final String ACTION_STOP_SERVICE =
            "com.example.tehranalert.STOP_SERVICE";

    public static final String ACTION_TEST =
            "com.example.tehranalert.TEST";

    public static final String ACTION_STOP_ALARM =
            "com.example.tehranalert.STOP_ALARM";

    private static final String TAG = "TehranAlert";

    private static final String SERVICE_CHANNEL =
            "monitoring_channel";

    private static final String ALERT_CHANNEL =
            "critical_alert_channel_v4";

    private static final int SERVICE_NOTIFICATION_ID = 100;

    private static final int ALERT_NOTIFICATION_ID = 200;

    private static final long CHECK_INTERVAL_MS =
            60_000L;

    private static final int MAX_DOWNLOAD_BYTES =
            1_500_000;

    private volatile boolean running;

    private Thread monitorThread;

    private MediaPlayer player;

    private Vibrator vibrator;

    private PowerManager.WakeLock wakeLock;


    // =========================================================
    // ایجاد سرویس
    // =========================================================

    @Override
    public void onCreate() {
        super.onCreate();

        createChannels();

        vibrator =
                (Vibrator) getSystemService(VIBRATOR_SERVICE);

        Log.d(TAG, "AlertService created");
    }


    // =========================================================
    // دریافت فرمان
    // =========================================================

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {

        String action =
                intent == null
                        ? ACTION_START
                        : intent.getAction();

        Log.d(
                TAG,
                "onStartCommand action = " + action
        );


        // توقف کامل سرویس
        if (ACTION_STOP_SERVICE.equals(action)) {

            stopAlarm();

            running = false;

            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(
                        STOP_FOREGROUND_REMOVE
                );
            } else {
                stopForeground(true);
            }

            stopSelf();

            return START_NOT_STICKY;
        }


        /*
         * سرویس باید قبل از انجام عملیات
         * به Foreground Service تبدیل شود.
         */

        startForeground(
                SERVICE_NOTIFICATION_ID,
                serviceNotification(
                        "در حال آماده‌سازی پایش..."
                )
        );


        // =====================================================
        // آژیر آزمایشی
        // =====================================================

        if (ACTION_TEST.equals(action)) {

            triggerAlarm(
                    "آژیر آزمایشی",
                    "تست صدای هشدار توسط کاربر"
            );

            return START_STICKY;
        }


        // =====================================================
        // توقف آژیر
        // =====================================================

        if (ACTION_STOP_ALARM.equals(action)) {

            stopAlarm();

            return START_STICKY;
        }


        // =====================================================
        // شروع پایش
        // =====================================================

        SourceStore.setMonitoring(
                this,
                true
        );

        startMonitorLoop();

        return START_STICKY;
    }


    // =========================================================
    // حلقه پایش
    // =========================================================

    private synchronized void startMonitorLoop() {

        if (running) {
            return;
        }

        running = true;


        monitorThread =
                new Thread(
                        () -> {

                            Log.d(
                                    TAG,
                                    "Monitor loop started"
                            );

                            while (
                                    running &&
                                    SourceStore.isMonitoring(this)
                            ) {

                                try {

                                    List<SourceStore.Source> sources =
                                            SourceStore.load(this);

                                    int activeCount = 0;


                                    for (
                                            SourceStore.Source source :
                                            sources
                                    ) {

                                        if (
                                                !running ||
                                                !source.enabled
                                        ) {
                                            continue;
                                        }

                                        activeCount++;

                                        checkSource(source);
                                    }


                                    updateServiceNotification(
                                            "پایش فعال؛ " +
                                                    activeCount +
                                                    " منبع در حال بررسی..."
                                    );


                                } catch (Exception e) {

                                    Log.e(
                                            TAG,
                                            "Monitor loop error",
                                            e
                                    );
                                }


                                sleepInterruptibly(
                                        CHECK_INTERVAL_MS
                                );
                            }


                            running = false;

                            Log.d(
                                    TAG,
                                    "Monitor loop stopped"
                            );

                        },
                        "TehranAlertMonitor"
                );

        monitorThread.start();
    }


    // =========================================================
    // بررسی منبع
    // =========================================================

    private void checkSource(
            SourceStore.Source source
    ) {

        PowerManager.WakeLock fetchLock = null;

        try {

            PowerManager pm =
                    (PowerManager)
                            getSystemService(
                                    POWER_SERVICE
                            );

            fetchLock =
                    pm.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK,
                            "TehranAlert:Fetch"
                    );

            fetchLock.acquire(45_000L);


            Log.d(
                    TAG,
                    "Checking: " +
                            source.name +
                            " -> " +
                            source.url
            );


            /*
             * این متد در نسخه قبلی جا افتاده بود.
             * اکنون داخل همین فایل وجود دارد.
             */

            String html =
                    download(source.url);


            if (
                    source.url
                            .toLowerCase(Locale.ROOT)
                            .contains("eitaa.com/")
            ) {

                Log.d(
                        TAG,
                        "Using Eitaa parser"
                );

                checkEitaa(
                        source,
                        html
                );

            } else if (
                    source.url
                            .toLowerCase(Locale.ROOT)
                            .contains("t.me/")
            ) {

                Log.d(
                        TAG,
                        "Using Telegram parser"
                );

                checkTelegram(
                        source,
                        html
                );

            } else {

                Log.d(
                        TAG,
                        "Using website parser"
                );

                checkWebPage(
                        source,
                        html
                );
            }


        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Source check failed: " +
                            source.url,
                    e
            );

        } finally {

            if (
                    fetchLock != null &&
                    fetchLock.isHeld()
            ) {

                fetchLock.release();
            }
        }
    }


    // =========================================================
    // دانلود صفحه اینترنتی
    // =========================================================

    private String download(
            String urlString
    ) throws Exception {

        HttpURLConnection connection = null;

        try {

            URL url =
                    new URL(urlString);

            connection =
                    (HttpURLConnection)
                            url.openConnection();

            connection.setRequestMethod("GET");

            connection.setConnectTimeout(
                    15_000
            );

            connection.setReadTimeout(
                    20_000
            );

            connection.setInstanceFollowRedirects(
                    true
            );

            connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Android) TehranAttackAlert/1.0"
            );

            connection.setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml"
            );


            int responseCode =
                    connection.getResponseCode();


            Log.d(
                    TAG,
                    "HTTP " +
                            responseCode +
                            " -> " +
                            urlString
            );


            if (
                    responseCode < 200 ||
                    responseCode >= 400
            ) {

                throw new Exception(
                        "HTTP error: " +
                                responseCode
                );
            }


            InputStream input =
                    connection.getInputStream();


            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    input,
                                    StandardCharsets.UTF_8
                            )
                    );


            StringBuilder result =
                    new StringBuilder();


            String line;

            int totalBytes = 0;


            while (
                    (line = reader.readLine()) != null
            ) {

                totalBytes +=
                        line.getBytes(
                                StandardCharsets.UTF_8
                        ).length;


                if (
                        totalBytes >
                                MAX_DOWNLOAD_BYTES
                ) {

                    Log.w(
                            TAG,
                            "Download size limit reached"
                    );

                    break;
                }


                result.append(line);

                result.append('\n');
            }


            reader.close();


            String html =
                    result.toString();


            Log.d(
                    TAG,
                    "Downloaded bytes: " +
                            totalBytes
            );


            return html;


        } finally {

            if (connection != null) {

                connection.disconnect();
            }
        }
    }


    // =========================================================
    // EITAA
    // =========================================================

    private void checkEitaa(
            SourceStore.Source source,
            String html
    ) {

        List<Post> posts =
                parseEitaaPosts(html);


        Log.d(
                TAG,
                "Eitaa posts found: " +
                        posts.size()
        );


        if (posts.isEmpty()) {

            Log.w(
                    TAG,
                    "No Eitaa posts detected"
            );

            return;
        }


        Collections.sort(
                posts,
                Comparator.comparingLong(
                        p -> p.id
                )
        );


        String key =
                "last_eitaa_post_" +
                        sha256(source.url);


        long lastSeen =
                SourceStore
                        .prefs(this)
                        .getLong(
                                key,
                                -1L
                        );


        long newest =
                posts.get(
                        posts.size() - 1
                ).id;


        /*
         * اولین بررسی:
         * پست‌های قبلی را هشدار نمی‌دهیم.
         */

        if (lastSeen < 0) {

            SourceStore
                    .prefs(this)
                    .edit()
                    .putLong(
                            key,
                            newest
                    )
                    .apply();


            Log.d(
                    TAG,
                    "Eitaa first scan. " +
                            "Newest post saved: " +
                            newest
            );

            return;
        }


        // بررسی پست‌های جدید

        for (Post post : posts) {

            if (post.id <= lastSeen) {
                continue;
            }


            Log.d(
                    TAG,
                    "New Eitaa post: " +
                            post.id +
                            " TEXT=" +
                            post.text
            );


            if (
                    isAttackReport(
                            post.text
                    )
            ) {

                triggerAlarm(
                        "⚠️ هشدار حمله به تهران ⚠️",
                        source.name +
                                ": " +
                                shorten(post.text)
                );

                break;
            }
        }


        if (newest > lastSeen) {

            SourceStore
                    .prefs(this)
                    .edit()
                    .putLong(
                            key,
                            newest
                    )
                    .apply();
        }
    }


    // =========================================================
    // Parser Eitaa
    // =========================================================

    private List<Post> parseEitaaPosts(
            String html
    ) {

        List<Post> result =
                new ArrayList<>();


        /*
         * الگوهای مختلفی که ممکن است
         * در HTML صفحه Eitaa وجود داشته باشند.
         */

        String[] patterns = {

                "(?is)class=[\"'][^\"']*(?:message|post)[^\"']*[\"'][^>]*>(.*?)</div>",

                "(?is)data-id=[\"'](\\d+)[\"'][^>]*>(.*?)</",

                "(?is)data-message-id=[\"'](\\d+)[\"'][^>]*>(.*?)</",

                "(?is)data-post-id=[\"'](\\d+)[\"'][^>]*>(.*?)</"
        };


        long generatedId = 1;


        for (String regex : patterns) {

            try {

                Pattern pattern =
                        Pattern.compile(regex);

                Matcher matcher =
                        pattern.matcher(html);


                while (matcher.find()) {

                    String block =
                            matcher.group(
                                    matcher.groupCount()
                            );


                    if (block == null) {
                        continue;
                    }


                    String text =
                            normalize(
                                    stripHtml(block)
                            );


                    if (
                            text.length() < 2
                    ) {
                        continue;
                    }


                    long id =
                            Math.abs(
                                    sha256(text)
                                            .hashCode()
                            );


                    if (id == 0) {
                        id = generatedId++;
                    }


                    result.add(
                            new Post(
                                    id,
                                    text
                            )
                    );
                }

            } catch (Exception e) {

                Log.e(
                        TAG,
                        "Eitaa regex error",
                        e
                );
            }
        }


        /*
         * حذف موارد تکراری
         */

        List<Post> unique =
                new ArrayList<>();


        List<String> seen =
                new ArrayList<>();


        for (Post post : result) {

            if (
                    !seen.contains(
                            post.text
                    )
            ) {

                seen.add(post.text);

                unique.add(post);
            }
        }


        /*
         * اگر HTML ساختار متفاوتی داشت،
         * fallback را اجرا می‌کنیم.
         */

        if (unique.isEmpty()) {

            unique =
                    parseEitaaFallback(
                            html
                    );
        }


        return unique;
    }


    // =========================================================
    // استخراج ID Eitaa
    // =========================================================

    private String extractEitaaId(
            String html
    ) {

        String[] patterns = {

                "data-id=[\"'](\\d+)",

                "data-message-id=[\"'](\\d+)",

                "data-post-id=[\"'](\\d+)",

                "/(\\d+)[\"']",

                "message[_-]?(\\d+)"
        };


        for (String regex : patterns) {

            Pattern pattern =
                    Pattern.compile(
                            regex,
                            Pattern.CASE_INSENSITIVE
                    );

            Matcher matcher =
                    pattern.matcher(html);


            if (matcher.find()) {

                return matcher.group(1);
            }
        }


        return null;
    }


    // =========================================================
    // Fallback Eitaa
    // =========================================================

    private List<Post> parseEitaaFallback(
            String html
    ) {

        List<Post> result =
                new ArrayList<>();


        String plain =
                normalize(
                        stripHtml(html)
                );


        String[] lines =
                plain.split("\\n+");


        long id = 1;


        for (String line : lines) {

            String text =
                    line.trim();


            if (
                    text.length() < 8
            ) {
                continue;
            }


            if (
                    text.contains("دنبال‌کننده") ||
                    text.contains("مشاهده در ایتا") ||
                    text.contains("پرسش‌ها") ||
                    text.contains("قوانین")
            ) {

                continue;
            }


            if (
                    text.contains("تهران") ||
                    text.contains("جنگ") ||
                    text.contains("حمله") ||
                    text.contains("موشک") ||
                    text.contains("انفجار") ||
                    text.contains("پهپاد") ||
                    text.contains("ایران")
            ) {

                result.add(
                        new Post(
                                id++,
                                text
                        )
                );
            }
        }


        return result;
    }


    // =========================================================
    // Telegram
    // =========================================================

    private void checkTelegram(
            SourceStore.Source source,
            String html
    ) {

        List<Post> posts =
                parseTelegramPosts(html);


        if (posts.isEmpty()) {
            return;
        }


        Collections.sort(
                posts,
                Comparator.comparingLong(
                        p -> p.id
                )
        );


        String key =
                "last_telegram_post_" +
                        sha256(source.url);


        long lastSeen =
                SourceStore
                        .prefs(this)
                        .getLong(
                                key,
                                -1L
                        );


        long newest =
                posts.get(
                        posts.size() - 1
                ).id;


        if (lastSeen < 0) {

            SourceStore
                    .prefs(this)
                    .edit()
                    .putLong(
                            key,
                            newest
                    )
                    .apply();

            return;
        }


        for (Post post : posts) {

            if (
                    post.id > lastSeen &&
                    isAttackReport(post.text)
            ) {

                triggerAlarm(
                        "⚠️ هشدار حمله به تهران ⚠️",
                        source.name +
                                ": " +
                                shorten(post.text)
                );

                break;
            }
        }


        if (newest > lastSeen) {

            SourceStore
                    .prefs(this)
                    .edit()
                    .putLong(
                            key,
                            newest
                    )
                    .apply();
        }
    }


    // =========================================================
    // Parser Telegram
    // =========================================================

    private List<Post> parseTelegramPosts(
            String html
    ) {

        List<Post> result =
                new ArrayList<>();


        Pattern block =
                Pattern.compile(
                        "data-post=\"[^/]+/(\\d+)\"[\\s\\S]*?(?=<div class=\"[^\"]*message_text)"
                );


        Pattern message =
                Pattern.compile(
                        "class=\"[^\"]*message_text[^\"]*\">([\\s\\S]*?)</div>"
                );


        Matcher blocks =
                block.matcher(html);


        while (blocks.find()) {

            long id;


            try {

                id =
                        Long.parseLong(
                                blocks.group(1)
                        );

            } catch (Exception e) {

                continue;
            }


            String content = "";


            Matcher body =
                    message.matcher(
                            html.substring(
                                    blocks.start()
                            )
                    );


            if (body.find()) {

                content =
                        stripHtml(
                                body.group(1)
                        );
            }


            result.add(
                    new Post(
                            id,
                            normalize(content)
                    )
            );
        }


        return result;
    }


    // =========================================================
    // سایت‌های خبری
    // =========================================================

    private void checkWebPage(
            SourceStore.Source source,
            String html
    ) {

        String text =
                normalize(
                        stripHtml(html)
                );


        String key =
                "page_hash_" +
                        sha256(source.url);


        String oldHash =
                SourceStore
                        .prefs(this)
                        .getString(
                                key,
                                ""
                        );


        String newHash =
                sha256(text);


        if (oldHash.isEmpty()) {

            SourceStore
                    .prefs(this)
                    .edit()
                    .putString(
                            key,
                            newHash
                    )
                    .apply();

            return;
        }


        if (
                !newHash.equals(oldHash)
        ) {

            SourceStore
                    .prefs(this)
                    .edit()
                    .putString(
                            key,
                            newHash
                    )
                    .apply();


            if (
                    isAttackReport(text)
            ) {

                triggerAlarm(
                        "⚠️ هشدار خبر فوری ⚠️",
                        "تغییر مهم در منبع: " +
                                source.name
                );
            }
        }
    }


    // =========================================================
    // تشخیص خبر حمله
    // =========================================================

    private boolean isAttackReport(
            String raw
    ) {

        String text =
                normalize(raw);


        boolean hasTehran =
                containsAny(
                        text,
                        "تهران",
                        "پایتخت",
                        "فرودگاه امام",
                        "فرودگاه امام خمینی",
                        "مرکز تهران"
                );


        boolean hasAttack =
                containsAny(
                        text,
                        "حمله",
                        "حمله موشکی",
                        "حمله هوایی",
                        "موشک",
                        "شلیک موشک",
                        "بمباران",
                        "انفجار",
                        "انفجار شدید",
                        "پهپاد",
                        "پهپاد انتحاری",
                        "آژیر خطر",
                        "وضعیت قرمز",
                        "اصابت",
                        "اصابت موشک",
                        "موشکباران",
                        "آغاز جنگ"
                );


        boolean strong =
                containsAny(
                        text,
                        "حمله موشکی به تهران",
                        "حمله به تهران",
                        "بمباران تهران",
                        "اصابت موشک به تهران",
                        "انفجار شدید در تهران",
                        "آغاز جنگ"
                );


        if (strong) {
            return true;
        }


        return hasTehran && hasAttack;
    }


    // =========================================================
    // فعال کردن آژیر
    // =========================================================

    private synchronized void triggerAlarm(
            String title,
            String details
    ) {

        Log.w(
                TAG,
                "ALARM TRIGGERED: " +
                        title +
                        " / " +
                        details
        );


        if (player != null) {

            try {
                player.stop();
            } catch (Exception ignored) {
            }

            try {
                player.release();
            } catch (Exception ignored) {
            }

            player = null;
        }


        player =
                MediaPlayer.create(
                        this,
                        R.raw.war_siren
                );


        if (player != null) {

            player.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(
                                    AudioAttributes.USAGE_ALARM
                            )
                            .setContentType(
                                    AudioAttributes.CONTENT_TYPE_SONIFICATION
                            )
                            .build()
            );

            player.setLooping(true);

            player.start();

        } else {

            Log.e(
                    TAG,
                    "war_siren.wav could not be loaded"
            );
        }


        if (vibrator != null) {

            long[] pattern = {
                    0,
                    1500,
                    500,
                    1500,
                    500
            };


            if (Build.VERSION.SDK_INT >= 26) {

                vibrator.vibrate(
                        VibrationEffect.createWaveform(
                                pattern,
                                0
                        )
                );

            } else {

                vibrator.vibrate(
                        pattern,
                        0
                );
            }
        }


        // روشن کردن صفحه

        try {

            PowerManager pm =
                    (PowerManager)
                            getSystemService(
                                    POWER_SERVICE
                            );


            if (
                    wakeLock == null ||
                    !wakeLock.isHeld()
            ) {

                wakeLock =
                        pm.newWakeLock(
                                PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                                        PowerManager.ACQUIRE_CAUSES_WAKEUP,
                                "TehranAlert:AlarmScreen"
                        );

                wakeLock.acquire(
                        5 * 60_000L
                );
            }

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "WakeLock error",
                    e
            );
        }


        NotificationManager manager =
                getSystemService(
                        NotificationManager.class
                );


        manager.notify(
                ALERT_NOTIFICATION_ID,
                alertNotification(
                        title,
                        details
                )
        );
    }


    // =========================================================
    // توقف آژیر
    // =========================================================

    private synchronized void stopAlarm() {

        if (player != null) {

            try {
                player.stop();
            } catch (Exception ignored) {
            }

            try {
                player.release();
            } catch (Exception ignored) {
            }

            player = null;
        }


        if (vibrator != null) {
            vibrator.cancel();
        }


        if (
                wakeLock != null &&
                wakeLock.isHeld()
        ) {

            wakeLock.release();
        }


        NotificationManager manager =
                getSystemService(
                        NotificationManager.class
                );


        manager.cancel(
                ALERT_NOTIFICATION_ID
        );
    }


    // =========================================================
    // Notification سرویس
    // =========================================================

    private Notification serviceNotification(
            String text
    ) {

        Intent open =
                new Intent(
                        this,
                        MainActivity.class
                );


        PendingIntent pending =
                PendingIntent.getActivity(
                        this,
                        1,
                        open,
                        PendingIntent.FLAG_IMMUTABLE
                );


        return new Notification.Builder(
                this,
                SERVICE_CHANNEL
        )
                .setSmallIcon(
                        R.drawable.ic_alert
                )
                .setContentTitle(
                        "سامانه هشدار تهران"
                )
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }


    // =========================================================
    // Notification هشدار
    // =========================================================

    private Notification alertNotification(
            String title,
            String details
    ) {

        Intent stop =
                new Intent(
                        this,
                        AlertService.class
                );

        stop.setAction(
                ACTION_STOP_ALARM
        );


        PendingIntent stopPending =
                PendingIntent.getService(
                        this,
                        2,
                        stop,
                        PendingIntent.FLAG_IMMUTABLE
                );


        return new Notification.Builder(
                this,
                ALERT_CHANNEL
        )
                .setSmallIcon(
                        R.drawable.ic_alert
                )
                .setContentTitle(title)
                .setContentText(details)
                .setCategory(
                        Notification.CATEGORY_ALARM
                )
                .setPriority(
                        Notification.PRIORITY_MAX
                )
                .setOngoing(true)
                .addAction(
                        new Notification.Action.Builder(
                                null,
                                "خاموش کردن آژیر",
                                stopPending
                        ).build()
                )
                .build();
    }


    // =========================================================
    // Notification Channel
    // =========================================================

    private void createChannels() {

        NotificationManager manager =
                getSystemService(
                        NotificationManager.class
                );


        NotificationChannel serviceChannel =
                new NotificationChannel(
                        SERVICE_CHANNEL,
                        "سرویس پایش",
                        NotificationManager.IMPORTANCE_LOW
                );


        manager.createNotificationChannel(
                serviceChannel
        );


        NotificationChannel alertChannel =
                new NotificationChannel(
                        ALERT_CHANNEL,
                        "هشدارهای بحرانی",
                        NotificationManager.IMPORTANCE_HIGH
                );


        alertChannel.enableVibration(true);

        alertChannel.setBypassDnd(true);


        manager.createNotificationChannel(
                alertChannel
        );
    }


    // =========================================================
    // بروزرسانی Notification سرویس
    // =========================================================

    private void updateServiceNotification(
            String text
    ) {

        getSystemService(
                NotificationManager.class
        ).notify(
                SERVICE_NOTIFICATION_ID,
                serviceNotification(text)
        );
    }


    // =========================================================
    // Sleep
    // =========================================================

    private void sleepInterruptibly(
            long millis
    ) {

        try {

            Thread.sleep(millis);

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
        }
    }


    // =========================================================
    // بررسی کلمات
    // =========================================================

    private boolean containsAny(
            String value,
            String... terms
    ) {

        for (String term : terms) {

            if (
                    value.contains(
                            normalize(term)
                    )
            ) {

                return true;
            }
        }


        return false;
    }


    // =========================================================
    // حذف HTML
    // =========================================================

    private String stripHtml(
            String html
    ) {

        if (html == null) {
            return "";
        }


        String replaced =
                html.replaceAll(
                        "(?i)<br\\s*/?>",
                        "\n"
                );


        if (Build.VERSION.SDK_INT >= 24) {

            return Html.fromHtml(
                    replaced,
                    Html.FROM_HTML_MODE_LEGACY
            ).toString();

        } else {

            return Html.fromHtml(
                    replaced
            ).toString();
        }
    }


    // =========================================================
    // نرمال سازی فارسی
    // =========================================================

    private String normalize(
            String value
    ) {

        if (value == null) {
            return "";
        }


        return value
                .toLowerCase(
                        new Locale("fa")
                )
                .replace(
                        'ي',
                        'ی'
                )
                .replace(
                        'ى',
                        'ی'
                )
                .replace(
                        'ك',
                        'ک'
                )
                .replace(
                        '\u200c',
                        ' '
                )
                .replaceAll(
                        "[\\u200c\\s]+",
                        " "
                )
                .trim();
    }


    // =========================================================
    // کوتاه کردن متن
    // =========================================================

    private String shorten(
            String value
    ) {

        if (value == null) {
            return "";
        }


        return value.length() <= 150
                ? value
                : value.substring(
                        0,
                        150
                ) + "...";
    }


    // =========================================================
    // SHA256
    // =========================================================

    private String sha256(
            String value
    ) {

        try {

            byte[] hash =
                    MessageDigest
                            .getInstance(
                                    "SHA-256"
                            )
                            .digest(
                                    value.getBytes(
                                            StandardCharsets.UTF_8
                                    )
                            );


            StringBuilder out =
                    new StringBuilder();


            for (byte b : hash) {

                out.append(
                        String.format(
                                "%02x",
                                b
                        )
                );
            }


            return out.toString();

        } catch (Exception e) {

            return String.valueOf(
                    value.hashCode()
            );
        }
    }


    // =========================================================
    // پایان سرویس
    // =========================================================

    @Override
    public void onDestroy() {

        running = false;

        stopAlarm();

        super.onDestroy();
    }


    @Override
    public IBinder onBind(
            Intent intent
    ) {

        return null;
    }


    // =========================================================
    // کلاس Post
    // =========================================================

    private static final class Post {

        final long id;

        final String text;


        Post(
                long id,
                String text
        ) {

            this.id = id;

            this.text = text;
        }
    }
}
```
