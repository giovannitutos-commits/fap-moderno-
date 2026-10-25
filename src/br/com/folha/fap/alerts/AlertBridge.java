package br.com.folha.fap.alerts;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ponto de entrada chamado pelo codigo patchado do app:
 *  - init(Application) em FapApplication.onCreate
 *  - onReading(...) a cada leitura de canal no loop de polling
 * Nada aqui pode lancar excecao para o app original.
 */
public final class AlertBridge {
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static Application app;
    private static volatile Activity current;

    private static final Object LOCK = new Object();
    /** id -> texto do alerta ativo (fora da faixa). */
    private static final Map<Integer, String> activeAlerts = new LinkedHashMap<Integer, String>();
    private static final Map<Integer, Long> lastSound = new ConcurrentHashMap<Integer, Long>();
    private static final Map<Integer, Boolean> silenced = new ConcurrentHashMap<Integer, Boolean>();
    private static Map<Integer, AlertStore.Seen> seen;
    private static List<AlertStore.Rule> rulesCache;
    private static long rulesCacheAt;

    private static TextView banner;
    private static Activity bannerHost;

    private AlertBridge() { }

    public static void init(Application a) {
        try {
            if (app != null) return;
            app = a;
            seen = AlertStore.seen(a);
            a.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                public void onActivityResumed(Activity act) {
                    current = act;
                    UI.post(new Runnable() { public void run() { refreshBanner(); } });
                }
                public void onActivityPaused(Activity act) {
                    if (current == act) current = null;
                    UI.post(new Runnable() { public void run() { detachBanner(); } });
                }
                public void onActivityCreated(Activity act, Bundle b) { }
                public void onActivityStarted(Activity act) { }
                public void onActivityStopped(Activity act) { }
                public void onActivitySaveInstanceState(Activity act, Bundle b) { }
                public void onActivityDestroyed(Activity act) { }
            });
        } catch (Throwable ignored) { }
    }

    /** Chamado a cada leitura. Pode vir de thread de background. */
    public static void onReading(int id, Double value, String name, String unit) {
        try {
            if (app == null) return;
            String n = name == null ? "" : name;
            String u = unit == null ? "" : unit;
            trackSeen(id, n, u);
            if (!AlertStore.master(app)) { clearAlert(id); return; }
            AlertStore.Rule rule = findRule(id);
            if (rule == null || !rule.enabled || value == null || value.isNaN()) { clearAlert(id); return; }
            double v = value.doubleValue();
            boolean low = !Double.isNaN(rule.min) && v < rule.min;
            boolean high = !Double.isNaN(rule.max) && v > rule.max;
            if (!low && !high) { clearAlert(id); return; }

            String label = n.isEmpty() ? (rule.name.isEmpty() ? "Canal " + id : rule.name) : n;
            String text = label + ": " + AlertStore.fmt(v) + (u.isEmpty() ? "" : " " + u)
                    + (high ? "  (acima de " + AlertStore.fmt(rule.max) + ")"
                            : "  (abaixo de " + AlertStore.fmt(rule.min) + ")");

            boolean entering;
            synchronized (LOCK) {
                entering = !activeAlerts.containsKey(id);
                activeAlerts.put(id, text);
            }
            long now = System.currentTimeMillis();
            Long last = lastSound.get(id);
            int interval = AlertStore.interval(app);
            boolean due = entering || (interval > 0 && last != null && now - last >= interval * 1000L);
            if (entering) silenced.remove(id);
            if (due && !Boolean.TRUE.equals(silenced.get(id))) {
                lastSound.put(id, now);
                fireSound(rule);
                if (entering && AlertStore.vibrateOn(app)) vibrate();
            }
            UI.post(new Runnable() { public void run() { refreshBanner(); } });
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------ regras
    private static AlertStore.Rule findRule(int id) {
        long now = System.currentTimeMillis();
        if (rulesCache == null || now - rulesCacheAt > 1500) {
            rulesCache = AlertStore.rules(app);
            rulesCacheAt = now;
        }
        for (AlertStore.Rule r : rulesCache) if (r.id == id) return r;
        return null;
    }

    public static void invalidateRules() { rulesCache = null; }

    private static void trackSeen(int id, String n, String u) {
        if (seen == null) return;
        synchronized (LOCK) {
            AlertStore.Seen s = seen.get(id);
            if (s == null || (!n.isEmpty() && !n.equals(s.name))) {
                if (s == null) { s = new AlertStore.Seen(); s.id = id; seen.put(id, s); }
                s.name = n;
                s.unit = u;
                AlertStore.saveSeen(app, seen);
            }
        }
    }

    private static void clearAlert(int id) {
        boolean removed;
        synchronized (LOCK) { removed = activeAlerts.remove(id) != null; }
        if (removed) {
            lastSound.remove(id);
            silenced.remove(id);
            UI.post(new Runnable() { public void run() { refreshBanner(); } });
        }
    }

    // ------------------------------------------------------------ som
    private static void fireSound(AlertStore.Rule rule) {
        if (!AlertStore.soundOn(app)) return;
        int tone = rule.tone >= 0 ? rule.tone : AlertStore.tone(app);
        String uri = rule.tone >= 0 ? "" : AlertStore.uri(app);
        play(app, tone, uri, AlertStore.volume(app), AlertStore.repeats(app), AlertStore.alarmStream(app));
    }

    /** Usado tambem pelo botao "Testar" da tela de configuracao. */
    public static void play(final Context c, final int tone, final String uri, final int volume,
                            final int repeats, final boolean alarm) {
        final int reps = Math.max(1, Math.min(10, repeats));
        UI.post(new Runnable() {
            public void run() {
                try {
                    if (uri != null && !uri.isEmpty()) playUri(c, uri, volume, reps, alarm);
                    else playTone(tone, volume, reps, alarm);
                } catch (Throwable ignored) { }
            }
        });
    }

    private static void playTone(int tone, int volume, int reps, boolean alarm) {
        int idx = Math.max(0, Math.min(AlertStore.TONE_IDS.length - 1, tone));
        final ToneGenerator tg = new ToneGenerator(
                alarm ? AudioManager.STREAM_ALARM : AudioManager.STREAM_MUSIC,
                Math.max(0, Math.min(100, volume)));
        final int toneId = AlertStore.TONE_IDS[idx];
        for (int i = 0; i < reps; i++) {
            UI.postDelayed(new Runnable() { public void run() { try { tg.startTone(toneId, 350); } catch (Throwable ignored) { } } }, i * 600L);
        }
        UI.postDelayed(new Runnable() { public void run() { try { tg.release(); } catch (Throwable ignored) { } } }, reps * 600L + 800L);
    }

    private static void playUri(final Context c, final String uri, final int volume, final int reps, final boolean alarm) throws Exception {
        final MediaPlayer mp = new MediaPlayer();
        final int[] left = {reps};
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(alarm ? AudioAttributes.USAGE_ALARM : AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        mp.setDataSource(c, Uri.parse(uri));
        float vol = Math.max(0, Math.min(100, volume)) / 100f;
        mp.setVolume(vol, vol);
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer p) {
                if (--left[0] > 0) { try { p.seekTo(0); p.start(); return; } catch (Throwable ignored) { } }
                try { p.release(); } catch (Throwable ignored) { }
            }
        });
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer p, int w, int e) { try { p.release(); } catch (Throwable ignored) { } return true; }
        });
        mp.prepare();
        mp.start();
    }

    private static void vibrate() {
        try {
            Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(new long[]{0, 300, 150, 300}, -1);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------ banner na tela
    private static void detachBanner() {
        try {
            if (banner != null && banner.getParent() instanceof ViewGroup) ((ViewGroup) banner.getParent()).removeView(banner);
        } catch (Throwable ignored) { }
        banner = null;
        bannerHost = null;
    }

    private static int dp(Activity a, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }

    private static void refreshBanner() {
        try {
            Activity a = current;
            String text;
            synchronized (LOCK) {
                if (activeAlerts.isEmpty() || app == null || !AlertStore.bannerOn(app)) text = null;
                else {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<Integer, String> e : activeAlerts.entrySet()) {
                        if (Boolean.TRUE.equals(silenced.get(e.getKey()))) continue;
                        if (sb.length() > 0) sb.append("\n");
                        sb.append("\u26A0 ").append(e.getValue());
                    }
                    text = sb.length() == 0 ? null : sb.toString();
                }
            }
            if (a == null || text == null) { detachBanner(); return; }
            if (bannerHost != a) detachBanner();
            if (banner == null) {
                TextView tv = new TextView(a);
                tv.setTextColor(0xFFFFFFFF);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                tv.setGravity(Gravity.CENTER);
                tv.setBackgroundColor(0xF0C62828);
                int p = dp(a, 12);
                tv.setPadding(p, p, p, p);
                tv.setElevation(dp(a, 12));
                AlphaAnimation blink = new AlphaAnimation(1f, 0.55f);
                blink.setDuration(500);
                blink.setRepeatMode(Animation.REVERSE);
                blink.setRepeatCount(Animation.INFINITE);
                tv.startAnimation(blink);
                tv.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        // toque = silenciar (som e repeticoes) ate o valor voltar ao normal
                        synchronized (LOCK) { for (Integer k : activeAlerts.keySet()) silenced.put(k, Boolean.TRUE); }
                        detachBanner();
                    }
                });
                int sb = 0;
                int rid = a.getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (rid > 0) sb = a.getResources().getDimensionPixelSize(rid);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
                lp.topMargin = sb;
                ((ViewGroup) a.getWindow().getDecorView()).addView(tv, lp);
                banner = tv;
                bannerHost = a;
            }
            banner.setText(text);
        } catch (Throwable ignored) { }
    }
}
