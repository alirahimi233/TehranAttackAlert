package com.example.tehranalert;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {

    private LinearLayout sourcesContainer;
    private TextView status;
    private EditText sourceName;
    private EditText sourceUrl;
    private List<SourceStore.Source> sources;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        requestNotificationPermission();

        buildUi();
        refreshSources();
        refreshStatus();
    }

    private void buildUi() {

        ScrollView scroll = new ScrollView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(28));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        scroll.addView(root);

        TextView title = text(
                "سامانه هشدار حمله تهران",
                25,
                true
        );

        title.setTextColor(Color.rgb(142, 17, 17));
        root.addView(title);

        TextView creator = text(
                "سازنده: alirahimi",
                14,
                false
        );

        creator.setTextColor(Color.DKGRAY);
        root.addView(creator, marginTop(creator, 4));

        status = text("", 17, true);
        root.addView(status, marginTop(status, 18));

        // =========================
        // دکمه های شروع و توقف پایش
        // =========================

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);

        Button start = button("شروع پایش");
        Button stop = button("توقف پایش");

        actions.addView(start, weighted());
        actions.addView(stop, weighted());

        root.addView(actions, marginTop(actions, 12));

        // =========================
        // دکمه آژیر آزمایشی
        // =========================

        Button test = button("آژیر آزمایشی");
        Button stopAlarm = button("توقف آژیر");

        root.addView(test, marginTop(test, 8));
        root.addView(stopAlarm, marginTop(stopAlarm, 8));

        // =========================
        // مدیریت منابع
        // =========================

        TextView sourceTitle = text(
                "مدیریت منابع خبری",
                20,
                true
        );

        root.addView(sourceTitle, marginTop(sourceTitle, 24));

        sourceName = input("نام منبع، مثلاً خبرگزاری نمونه");

        sourceUrl = input(
                "URL، مثلاً https://t.me/s/channel"
        );

        sourceUrl.setInputType(
                InputType.TYPE_CLASS_TEXT |
                        InputType.TYPE_TEXT_VARIATION_URI
        );

        root.addView(
                sourceName,
                marginTop(sourceName, 10)
        );

        root.addView(
                sourceUrl,
                marginTop(sourceUrl, 8)
        );

        Button add = button("افزودن منبع");

        root.addView(
                add,
                marginTop(add, 8)
        );

        sourcesContainer = new LinearLayout(this);
        sourcesContainer.setOrientation(
                LinearLayout.VERTICAL
        );

        root.addView(
                sourcesContainer,
                marginTop(sourcesContainer, 12)
        );

        // =========================
        // تنظیمات باتری
        // =========================

        Button battery = button(
                "تنظیمات باتری برنامه"
        );

        root.addView(
                battery,
                marginTop(battery, 20)
        );

        TextView note = text(
                "هشدار فقط وقتی فعال می‌شود که در یک خبر جدید، هم نام تهران و هم عبارت مرتبط با حمله دیده شود. پایش منابع عمومی جایگزین هشدار رسمی نیست.",
                14,
                false
        );

        note.setTextColor(Color.DKGRAY);

        root.addView(
                note,
                marginTop(note, 14)
        );

        // =========================
        // عملکرد دکمه ها
        // =========================

        start.setOnClickListener(v ->
                startMonitoring()
        );

        stop.setOnClickListener(v ->
                stopMonitoring()
        );

        /*
         * آژیر آزمایشی
         *
         * این قسمت را اصلاح کردیم تا:
         * 1- سرویس درست اجرا شود
         * 2- در نسخه های جدید اندروید از Foreground Service استفاده شود
         * 3- اگر خطایی رخ داد، برنامه به جای بسته شدن، پیام خطا نشان دهد
         */

        test.setOnClickListener(v -> {

            try {

                Intent intent = new Intent(
                        this,
                        AlertService.class
                );

                intent.setAction(
                        AlertService.ACTION_TEST
                );

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                    startForegroundService(intent);

                } else {

                    startService(intent);
                }

                Toast.makeText(
                        this,
                        "تست آژیر ارسال شد.",
                        Toast.LENGTH_SHORT
                ).show();

            } catch (Exception e) {

                Toast.makeText(
                        this,
                        "خطا در اجرای آژیر: " +
                                e.getMessage(),
                        Toast.LENGTH_LONG
                ).show();
            }
        });

        /*
         * توقف آژیر
         */

        stopAlarm.setOnClickListener(v -> {

            try {

                sendServiceAction(
                        AlertService.ACTION_STOP_ALARM
                );

            } catch (Exception e) {

                Toast.makeText(
                        this,
                        "خطا در توقف آژیر: " +
                                e.getMessage(),
                        Toast.LENGTH_LONG
                ).show();
            }
        });

        add.setOnClickListener(v ->
                addSource()
        );

        battery.setOnClickListener(v ->
                openBatterySettings()
        );

        setContentView(scroll);
    }

    // =========================================================
    // نمایش منابع
    // =========================================================

    private void refreshSources() {

        sources = SourceStore.load(this);

        sourcesContainer.removeAllViews();

        if (sources.isEmpty()) {

            sourcesContainer.addView(
                    text(
                            "هیچ منبعی ثبت نشده است.",
                            15,
                            false
                    )
            );

            return;
        }

        for (int i = 0; i < sources.size(); i++) {

            final int index = i;

            SourceStore.Source source =
                    sources.get(i);

            LinearLayout box =
                    new LinearLayout(this);

            box.setOrientation(
                    LinearLayout.VERTICAL
            );

            box.setPadding(
                    dp(12),
                    dp(10),
                    dp(12),
                    dp(10)
            );

            box.setBackgroundColor(
                    Color.rgb(245, 245, 245)
            );

            Switch enabled =
                    new Switch(this);

            enabled.setText(source.name);
            enabled.setChecked(source.enabled);
            enabled.setTextSize(16);

            box.addView(enabled);

            TextView url =
                    text(
                            source.url,
                            13,
                            false
                    );

            url.setTextDirection(
                    View.TEXT_DIRECTION_LTR
            );

            url.setGravity(Gravity.LEFT);

            box.addView(
                    url,
                    marginTop(url, 5)
            );

            Button delete =
                    button("حذف");

            box.addView(
                    delete,
                    marginTop(delete, 6)
            );

            sourcesContainer.addView(
                    box,
                    marginTop(box, 8)
            );

            enabled.setOnCheckedChangeListener(
                    (buttonView, checked) -> {

                        sources.get(index).enabled =
                                checked;

                        SourceStore.save(
                                this,
                                sources
                        );
                    }
            );

            delete.setOnClickListener(v -> {

                sources.remove(index);

                SourceStore.save(
                        this,
                        sources
                );

                refreshSources();
            });
        }
    }

    // =========================================================
    // اضافه کردن منبع
    // =========================================================

    private void addSource() {

        String name =
                sourceName
                        .getText()
                        .toString()
                        .trim();

        String url =
                sourceUrl
                        .getText()
                        .toString()
                        .trim();

        if (
                name.isEmpty() ||
                !(
                        url.startsWith("https://") ||
                        url.startsWith("http://")
                )
        ) {

            Toast.makeText(
                    this,
                    "نام و URL معتبر وارد کن.",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        sources.add(
                new SourceStore.Source(
                        name,
                        url,
                        true
                )
        );

        SourceStore.save(
                this,
                sources
        );

        sourceName.setText("");
        sourceUrl.setText("");

        refreshSources();

        Toast.makeText(
                this,
                "منبع اضافه شد.",
                Toast.LENGTH_SHORT
        ).show();
    }

    // =========================================================
    // شروع پایش
    // =========================================================

    private void startMonitoring() {

        SourceStore.setMonitoring(
                this,
                true
        );

        Intent intent =
                new Intent(
                        this,
                        AlertService.class
                );

        intent.setAction(
                AlertService.ACTION_START
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            startForegroundService(intent);

        } else {

            startService(intent);
        }

        refreshStatus();

        Toast.makeText(
                this,
                "پایش منابع شروع شد.",
                Toast.LENGTH_SHORT
        ).show();
    }

    // =========================================================
    // توقف پایش
    // =========================================================

    private void stopMonitoring() {

        SourceStore.setMonitoring(
                this,
                false
        );

        sendServiceAction(
                AlertService.ACTION_STOP_SERVICE
        );

        refreshStatus();

        Toast.makeText(
                this,
                "پایش متوقف شد.",
                Toast.LENGTH_SHORT
        ).show();
    }

    // =========================================================
    // ارسال فرمان به سرویس
    // =========================================================

    private void sendServiceAction(String action) {

        Intent intent =
                new Intent(
                        this,
                        AlertService.class
                );

        intent.setAction(action);

        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                startForegroundService(intent);

            } else {

                startService(intent);
            }

        } catch (Exception e) {

            Toast.makeText(
                    this,
                    "خطا در اجرای سرویس: " +
                            e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    // =========================================================
    // وضعیت پایش
    // =========================================================

    private void refreshStatus() {

        boolean enabled =
                SourceStore.isMonitoring(this);

        status.setText(
                enabled
                        ? "وضعیت: پایش فعال است"
                        : "وضعیت: پایش متوقف است"
        );

        status.setTextColor(
                enabled
                        ? Color.rgb(20, 110, 55)
                        : Color.rgb(160, 35, 35)
        );
    }

    // =========================================================
    // تنظیمات باتری
    // =========================================================

    private void openBatterySettings() {

        try {

            Intent intent =
                    new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse(
                                    "package:" +
                                            getPackageName()
                            )
                    );

            startActivity(intent);

        } catch (Exception ignored) {
        }
    }

    // =========================================================
    // اجازه Notification
    // =========================================================

    private void requestNotificationPermission() {

        if (
                Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(
                        Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
        ) {

            requestPermissions(
                    new String[]{
                            Manifest.permission.POST_NOTIFICATIONS
                    },
                    10
            );
        }
    }

    // =========================================================
    // ساخت TextView
    // =========================================================

    private TextView text(
            String value,
            int size,
            boolean bold
    ) {

        TextView view =
                new TextView(this);

        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.BLACK);

        if (bold) {

            view.setTypeface(
                    null,
                    android.graphics.Typeface.BOLD
            );
        }

        return view;
    }

    // =========================================================
    // ساخت EditText
    // =========================================================

    private EditText input(String hint) {

        EditText input =
                new EditText(this);

        input.setHint(hint);
        input.setSingleLine(true);

        return input;
    }

    // =========================================================
    // ساخت Button
    // =========================================================

    private Button button(String label) {

        Button button =
                new Button(this);

        button.setText(label);
        button.setAllCaps(false);

        return button;
    }

    // =========================================================
    // اندازه دکمه های افقی
    // =========================================================

    private LinearLayout.LayoutParams weighted() {

        LinearLayout.LayoutParams p =
                new LinearLayout.LayoutParams(
                        0,
                        dp(52),
                        1
                );

        p.setMargins(
                dp(4),
                0,
                dp(4),
                0
        );

        return p;
    }

    // =========================================================
    // فاصله از بالا
    // =========================================================

    private LinearLayout.LayoutParams marginTop(
            View view,
            int top
    ) {

        LinearLayout.LayoutParams p =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        p.topMargin = dp(top);

        return p;
    }

    // =========================================================
    // تبدیل dp
    // =========================================================

    private int dp(int value) {

        return Math.round(
                value *
                        getResources()
                                .getDisplayMetrics()
                                .density
        );
    }
}
