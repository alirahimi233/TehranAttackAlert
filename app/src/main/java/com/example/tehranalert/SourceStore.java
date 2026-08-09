package com.example.tehranalert;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SourceStore {
    private static final String PREFS = "tehran_alert_prefs";
    private static final String KEY_SOURCES = "sources";
    private static final String KEY_MONITORING = "monitoring";
    private static final String KEY_DEFAULTS_REMOVED = "defaults_removed_v1";

    private static final String OLD_SOURCE_1 =
            "https://t.me/s/rodast_omiddana";
    private static final String OLD_SOURCE_2 =
            "https://t.me/s/Mellig";
    private static final String OLD_SOURCE_3 =
            "https://www.tasnimnews.com/fa/search?q=تهران";

    public static final class Source {
        public String name;
        public String url;
        public boolean enabled;

        public Source(String name, String url, boolean enabled) {
            this.name = name;
            this.url = url;
            this.enabled = enabled;
        }
    }

    private SourceStore() {}

    public static List<Source> load(Context context) {
        SharedPreferences prefs =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        Set<String> saved = prefs.getStringSet(KEY_SOURCES, null);
        List<Source> result = new ArrayList<>();

        // در نصب جدید، فهرست منابع باید خالی باشد.
        if (saved == null) {
            save(context, result);
            prefs.edit().putBoolean(KEY_DEFAULTS_REMOVED, true).apply();
            return result;
        }

        for (String row : saved) {
            String[] parts = row.split("\\|", 3);
            if (parts.length != 3) {
                continue;
            }

            try {
                result.add(new Source(
                        decode(parts[0]),
                        decode(parts[1]),
                        "1".equals(parts[2])
                ));
            } catch (Exception ignored) {
                // ورودی خراب نادیده گرفته می‌شود.
            }
        }

        // منابع پیش‌فرض نسخه قبلی فقط یک بار حذف می‌شوند.
        if (!prefs.getBoolean(KEY_DEFAULTS_REMOVED, false)) {
            boolean changed = false;

            for (int i = result.size() - 1; i >= 0; i--) {
                if (isOldDefaultSource(result.get(i).url)) {
                    result.remove(i);
                    changed = true;
                }
            }

            SharedPreferences.Editor editor = prefs.edit()
                    .putBoolean(KEY_DEFAULTS_REMOVED, true);

            if (changed) {
                Set<String> rows = encodeSources(result);
                editor.putStringSet(KEY_SOURCES, rows);
            }

            editor.apply();
        }

        return result;
    }

    public static void save(Context context, List<Source> sources) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_SOURCES, encodeSources(sources))
                .apply();
    }

    private static Set<String> encodeSources(List<Source> sources) {
        Set<String> rows = new LinkedHashSet<>();

        for (Source source : sources) {
            if (source == null || source.url == null) {
                continue;
            }

            String name = source.name == null ? "" : source.name.trim();
            String url = source.url.trim();

            if (url.isEmpty()) {
                continue;
            }

            rows.add(
                    encode(name)
                            + "|"
                            + encode(url)
                            + "|"
                            + (source.enabled ? "1" : "0")
            );
        }

        return rows;
    }

    private static boolean isOldDefaultSource(String url) {
        if (url == null) {
            return false;
        }

        String normalized = url.trim();

        return OLD_SOURCE_1.equalsIgnoreCase(normalized)
                || OLD_SOURCE_2.equalsIgnoreCase(normalized)
                || OLD_SOURCE_3.equalsIgnoreCase(normalized);
    }

    public static boolean isMonitoring(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_MONITORING, false);
    }

    public static void setMonitoring(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MONITORING, enabled)
                .apply();
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String encode(String value) {
        return Base64.encodeToString(
                value.getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP | Base64.URL_SAFE
        );
    }

    private static String decode(String value) {
        return new String(
                Base64.decode(
                        value,
                        Base64.NO_WRAP | Base64.URL_SAFE
                ),
                StandardCharsets.UTF_8
        );
    }
}
