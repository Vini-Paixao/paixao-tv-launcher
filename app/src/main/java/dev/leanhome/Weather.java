package dev.leanhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.Consumer;

/** Current weather from Open-Meteo (free, no key), cached so Home never waits on the network. */
final class Weather {
    private static final String URL_NOW = "https://api.open-meteo.com/v1/forecast?latitude="
            + BuildConfig.WEATHER_LATITUDE + "&longitude=" + BuildConfig.WEATHER_LONGITUDE
            + "&current=temperature_2m,weather_code,is_day&timezone=auto";
    private static final long FRESH_MS = 30 * 60_000, STALE_MS = 6 * 3_600_000;

    final int temperature, code;
    final boolean day;

    private Weather(int temperature, int code, boolean day) {
        this.temperature = temperature;
        this.code = code;
        this.day = day;
    }

    String condition() {
        if (code == 0) return day ? "Ensolarado" : "Céu limpo";
        if (code <= 2) return "Parcialmente nublado";
        if (code == 3) return "Nublado";
        if (code <= 48) return "Neblina";
        if (code <= 57) return "Garoa";
        if (code <= 67 || (code >= 80 && code <= 82)) return "Chuva";
        if (code <= 86) return "Neve";
        return "Tempestade";
    }

    /** Gives the cached value at once when it is usable, and again after a refresh that changed it. */
    static void load(Context context, Consumer<Weather> done) {
        SharedPreferences store = context.getSharedPreferences("weather", Context.MODE_PRIVATE);
        long age = System.currentTimeMillis() - store.getLong("at", 0);
        if (age < STALE_MS) {
            done.accept(new Weather(store.getInt("temp", 0), store.getInt("code", 0), store.getBoolean("day", true)));
        }
        if (age < FRESH_MS) return;
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                HttpURLConnection http = (HttpURLConnection) new URL(URL_NOW).openConnection();
                http.setConnectTimeout(8_000);
                http.setReadTimeout(8_000);
                StringBuilder body = new StringBuilder();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(http.getInputStream(), "UTF-8"))) {
                    for (String line; (line = in.readLine()) != null; ) body.append(line);
                } finally {
                    http.disconnect();
                }
                JSONObject now = new JSONObject(body.toString()).getJSONObject("current");
                Weather fresh = new Weather((int) Math.round(now.getDouble("temperature_2m")),
                        now.getInt("weather_code"), now.getInt("is_day") == 1);
                store.edit().putLong("at", System.currentTimeMillis()).putInt("temp", fresh.temperature)
                        .putInt("code", fresh.code).putBoolean("day", fresh.day).apply();
                main.post(() -> done.accept(fresh));
            } catch (Exception offline) {
                // Keep what is on screen; the widget stays hidden if nothing usable was cached.
            }
        }, "weather").start();
    }

    /** The widget's sun, moon or cloud, drawn so it needs no image. */
    static final class Icon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Weather weather;

        Icon(Context context) {
            super(context);
        }

        void show(Weather weather) {
            this.weather = weather;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (weather == null) return;
            float size = getWidth(), x = size * 0.52f, y = size * 0.42f;
            boolean cloudy = weather.code >= 2;
            paint.setColor(weather.day ? 0x33FFD9A0 : 0x33CFE0FF);
            canvas.drawCircle(x, y, size * 0.42f, paint);
            paint.setColor(weather.day ? 0xFFFFD08A : 0xFFE6ECFF);
            canvas.drawCircle(x, y, size * 0.21f, paint);
            if (cloudy) {
                paint.setColor(Color.WHITE);
                float base = size * 0.72f;
                canvas.drawRoundRect(size * 0.08f, base - size * 0.15f, size * 0.92f, base + size * 0.12f,
                        size * 0.14f, size * 0.14f, paint);
                canvas.drawCircle(size * 0.36f, base - size * 0.14f, size * 0.2f, paint);
                canvas.drawCircle(size * 0.62f, base - size * 0.17f, size * 0.24f, paint);
            }
        }
    }
}
