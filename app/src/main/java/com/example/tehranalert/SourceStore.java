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
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet(KEY_SOURCES, null);
        if (saved == null) {
            List<Source> defaults = new ArrayList<>();
            defaults.add(new Source("کانال rodast_omiddana", "https://t.me/s/rodast_omiddana", true));
            defaults.add(new Source("کانال Mellig", "https://t.me/s/Mellig", true));
            defaults.add(new Source("جستجوی تهران - تسنیم", "https://www.tasnimnews.com/fa/search?q=تهران", true));
            save(context, defaults);
            return defaults;
        }

        List<Source> result = new ArrayList<>();
        for (String row : saved) {
            String[] parts = row.split("\\|", 3);
            if (parts.length != 3) continue;
            try {
                result.add(new Source(decode(parts[0]), decode(parts[1]), "1".equals(parts[2])));
            } catch (Exception ignored) {}
        }
        return result;
    }

    public static void save(Context context, List<Source> sources) {
        Set<String> rows = new LinkedHashSet<>();
        for (Source source : sources) {
            rows.add(encode(source.name) + "|" + encode(source.url) + "|" + (source.enabled ? "1" : "0"));
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_SOURCES, rows).apply();
    }

    public static boolean isMonitoring(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_MONITORING, false);
    }

    public static void setMonitoring(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_MONITORING, enabled).apply();
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String encode(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP | Base64.URL_SAFE);
    }

    private static String decode(String value) {
        return new String(Base64.decode(value, Base64.NO_WRAP | Base64.URL_SAFE), StandardCharsets.UTF_8);
    }
}
