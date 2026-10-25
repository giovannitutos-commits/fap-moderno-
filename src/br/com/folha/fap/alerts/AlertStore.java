package br.com.folha.fap.alerts;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.ToneGenerator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistencia das configuracoes de alerta (SharedPreferences). */
public final class AlertStore {
    public static final String[] TONE_NAMES = {
            "Bipe curto", "Bipe duplo", "Alerta", "Alarme", "Erro", "Confirmacao", "Negacao"
    };
    public static final int[] TONE_IDS = {
            ToneGenerator.TONE_PROP_BEEP, ToneGenerator.TONE_PROP_BEEP2,
            ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK,
            ToneGenerator.TONE_SUP_ERROR, ToneGenerator.TONE_PROP_ACK, ToneGenerator.TONE_PROP_NACK
    };

    public static final class Rule {
        public int id;
        public String name = "";
        public String unit = "";
        public boolean enabled = true;
        public double min = Double.NaN;
        public double max = Double.NaN;
        /** -1 = usar o som global. */
        public int tone = -1;

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(name.isEmpty() ? ("Canal " + id) : name);
            if (!unit.isEmpty()) sb.append(" (").append(unit).append(")");
            sb.append("\n");
            sb.append(Double.isNaN(min) ? "sem minimo" : ("min " + fmt(min)));
            sb.append("  |  ");
            sb.append(Double.isNaN(max) ? "sem maximo" : ("max " + fmt(max)));
            if (!enabled) sb.append("  [desligada]");
            return sb.toString();
        }
    }

    public static final class Seen {
        public int id;
        public String name = "";
        public String unit = "";
        public double last = Double.NaN;
    }

    private static SharedPreferences sp;

    public static synchronized SharedPreferences prefs(Context c) {
        if (sp == null) sp = c.getApplicationContext().getSharedPreferences("fap_alerts", Context.MODE_PRIVATE);
        return sp;
    }

    public static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e9) return String.valueOf((long) v);
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    // ---- globais ----
    public static boolean master(Context c) { return prefs(c).getBoolean("master", true); }
    public static boolean soundOn(Context c) { return prefs(c).getBoolean("sound", true); }
    public static boolean bannerOn(Context c) { return prefs(c).getBoolean("banner", true); }
    public static boolean vibrateOn(Context c) { return prefs(c).getBoolean("vibrate", true); }
    public static boolean alarmStream(Context c) { return prefs(c).getBoolean("alarmStream", false); }
    public static int tone(Context c) { return prefs(c).getInt("tone", 0); }
    public static String uri(Context c) { return prefs(c).getString("uri", ""); }
    public static int volume(Context c) { return prefs(c).getInt("volume", 100); }
    public static int repeats(Context c) { return prefs(c).getInt("repeats", 3); }
    /** Segundos entre repeticoes enquanto o valor continuar fora da faixa; 0 = so uma vez. */
    public static int interval(Context c) { return prefs(c).getInt("interval", 10); }

    // ---- regras ----
    public static synchronized List<Rule> rules(Context c) {
        List<Rule> out = new ArrayList<Rule>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("rules", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Rule r = new Rule();
                r.id = o.getInt("id");
                r.name = o.optString("name", "");
                r.unit = o.optString("unit", "");
                r.enabled = o.optBoolean("enabled", true);
                r.min = o.has("min") ? o.getDouble("min") : Double.NaN;
                r.max = o.has("max") ? o.getDouble("max") : Double.NaN;
                r.tone = o.optInt("tone", -1);
                out.add(r);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    public static synchronized void saveRules(Context c, List<Rule> rules) {
        try {
            JSONArray a = new JSONArray();
            for (Rule r : rules) {
                JSONObject o = new JSONObject();
                o.put("id", r.id);
                o.put("name", r.name);
                o.put("unit", r.unit);
                o.put("enabled", r.enabled);
                if (!Double.isNaN(r.min)) o.put("min", r.min);
                if (!Double.isNaN(r.max)) o.put("max", r.max);
                o.put("tone", r.tone);
                a.put(o);
            }
            prefs(c).edit().putString("rules", a.toString()).apply();
        } catch (Throwable ignored) { }
    }

    // ---- canais ja vistos (para montar a lista ao criar regras) ----
    public static synchronized Map<Integer, Seen> seen(Context c) {
        Map<Integer, Seen> out = new LinkedHashMap<Integer, Seen>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("seen", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Seen s = new Seen();
                s.id = o.getInt("id");
                s.name = o.optString("name", "");
                s.unit = o.optString("unit", "");
                out.put(s.id, s);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    public static synchronized void saveSeen(Context c, Map<Integer, Seen> m) {
        try {
            JSONArray a = new JSONArray();
            for (Seen s : m.values()) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("name", s.name);
                o.put("unit", s.unit);
                a.put(o);
            }
            prefs(c).edit().putString("seen", a.toString()).apply();
        } catch (Throwable ignored) { }
    }
}
