package br.com.folha.fap.alerts;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Tela de configuracao dos alertas (icone "FAP Alertas" no launcher). Views puras, sem Compose. */
public class AlertSettingsActivity extends Activity {
    private static final int REQ_FILE = 71;
    private static final int REQ_RINGTONE = 72;

    private LinearLayout root;
    private LinearLayout rulesBox;
    private TextView soundLabel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("FAP Alertas");
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);
        sv.addView(root);
        setContentView(sv);
        build();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView header(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(18), 0, dp(6));
        return tv;
    }

    private CheckBox check(String label, final String key, boolean def) {
        final SharedPreferences sp = AlertStore.prefs(this);
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setChecked(sp.getBoolean(key, def));
        cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean on) {
                sp.edit().putBoolean(key, on).apply();
            }
        });
        return cb;
    }

    private EditText number(String key, int def, final int min, final int max) {
        final SharedPreferences sp = AlertStore.prefs(this);
        final String k = key;
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(sp.getInt(key, def)));
        et.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable e) {
                try {
                    int v = Integer.parseInt(e.toString().trim());
                    sp.edit().putInt(k, Math.max(min, Math.min(max, v))).apply();
                } catch (Throwable ignored) { }
            }
        });
        return et;
    }

    private LinearLayout row(String label, View field) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(label);
        r.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(field, new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private void build() {
        root.removeAllViews();
        final SharedPreferences sp = AlertStore.prefs(this);

        TextView intro = new TextView(this);
        intro.setText("Defina faixas (minimo/maximo) por parametro. Quando a leitura sair da faixa, o app toca o som escolhido e mostra um aviso vermelho na tela. Toque no aviso para silenciar.");
        root.addView(intro);

        root.addView(header("Geral"));
        root.addView(check("Alertas ativados", "master", true));
        root.addView(check("Alerta sonoro", "sound", true));
        root.addView(check("Alerta na tela", "banner", true));
        root.addView(check("Vibrar", "vibrate", true));
        root.addView(check("Tocar no volume de ALARME (ignora volume de midia)", "alarmStream", false));

        root.addView(header("Som"));
        final Spinner tone = new Spinner(this);
        tone.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, AlertStore.TONE_NAMES));
        tone.setSelection(Math.max(0, Math.min(AlertStore.TONE_NAMES.length - 1, AlertStore.tone(this))));
        tone.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> a, View v, int pos, long id) {
                sp.edit().putInt("tone", pos).apply();
            }
            public void onNothingSelected(android.widget.AdapterView<?> a) { }
        });
        root.addView(row("Bipe padrao", tone));
        root.addView(row("Repeticoes (1-10)", number("repeats", 3, 1, 10)));
        root.addView(row("Repetir a cada (s, 0 = so uma vez)", number("interval", 10, 0, 600)));

        TextView vol = new TextView(this);
        vol.setText("Volume: " + AlertStore.volume(this) + "%");
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(AlertStore.volume(this));
        final TextView volF = vol;
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                volF.setText("Volume: " + v + "%");
                if (user) sp.edit().putInt("volume", v).apply();
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        root.addView(vol);
        root.addView(sb);

        soundLabel = new TextView(this);
        updateSoundLabel();
        soundLabel.setPadding(0, dp(8), 0, dp(4));
        root.addView(soundLabel);

        root.addView(button("Escolher arquivo de audio", new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("audio/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(i, REQ_FILE);
            }
        }));
        root.addView(button("Escolher toque do sistema", new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
                i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL);
                i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
                startActivityForResult(i, REQ_RINGTONE);
            }
        }));
        root.addView(button("Voltar ao bipe padrao", new View.OnClickListener() {
            public void onClick(View v) {
                sp.edit().remove("uri").remove("uriName").apply();
                updateSoundLabel();
            }
        }));
        root.addView(button("Testar som", new View.OnClickListener() {
            public void onClick(View v) {
                AlertBridge.play(AlertSettingsActivity.this, AlertStore.tone(AlertSettingsActivity.this),
                        AlertStore.uri(AlertSettingsActivity.this), AlertStore.volume(AlertSettingsActivity.this),
                        AlertStore.repeats(AlertSettingsActivity.this), AlertStore.alarmStream(AlertSettingsActivity.this));
            }
        }));

        root.addView(header("Regras por parametro"));
        rulesBox = new LinearLayout(this);
        rulesBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(rulesBox);
        root.addView(button("+ Adicionar regra", new View.OnClickListener() {
            public void onClick(View v) { pickChannel(); }
        }));
        refreshRules();
    }

    private Button button(String t, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(t);
        bt.setAllCaps(false);
        bt.setOnClickListener(l);
        return bt;
    }

    private void updateSoundLabel() {
        String u = AlertStore.uri(this);
        String n = AlertStore.prefs(this).getString("uriName", "");
        soundLabel.setText(u.isEmpty() ? "Som atual: bipe padrao" : "Som atual: " + (n.isEmpty() ? u : n));
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        SharedPreferences sp = AlertStore.prefs(this);
        try {
            if (req == REQ_FILE && data.getData() != null) {
                Uri u = data.getData();
                try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Throwable ignored) { }
                sp.edit().putString("uri", u.toString()).putString("uriName", u.getLastPathSegment()).apply();
            } else if (req == REQ_RINGTONE) {
                Uri u = (Uri) data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                if (u != null) {
                    String name = RingtoneManager.getRingtone(this, u).getTitle(this);
                    sp.edit().putString("uri", u.toString()).putString("uriName", name).apply();
                }
            }
        } catch (Throwable t) {
            Toast.makeText(this, "Nao foi possivel usar esse arquivo", Toast.LENGTH_SHORT).show();
        }
        updateSoundLabel();
    }

    // ---------------------------------------------------------- regras
    private void refreshRules() {
        rulesBox.removeAllViews();
        final List<AlertStore.Rule> rules = AlertStore.rules(this);
        if (rules.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("Nenhuma regra ainda.");
            rulesBox.addView(t);
        }
        for (final AlertStore.Rule r : rules) {
            Button bt = button(r.describe(), new View.OnClickListener() {
                public void onClick(View v) { editRule(r, false); }
            });
            bt.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            rulesBox.addView(bt);
        }
    }

    private void pickChannel() {
        Map<Integer, AlertStore.Seen> seen = AlertStore.seen(this);
        final List<AlertStore.Seen> list = new ArrayList<AlertStore.Seen>(seen.values());
        if (list.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Nenhum parametro conhecido")
                    .setMessage("Abra o FAP Moderno, conecte ao carro e deixe o painel ler os parametros. Eles aparecerao aqui.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] items = new String[list.size()];
        for (int i = 0; i < items.length; i++) {
            AlertStore.Seen s = list.get(i);
            items[i] = (s.name.isEmpty() ? "Canal " + s.id : s.name) + (s.unit.isEmpty() ? "" : " (" + s.unit + ")");
        }
        new AlertDialog.Builder(this).setTitle("Parametro").setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                AlertStore.Seen s = list.get(which);
                for (AlertStore.Rule r : AlertStore.rules(AlertSettingsActivity.this)) {
                    if (r.id == s.id) { editRule(r, false); return; }
                }
                AlertStore.Rule r = new AlertStore.Rule();
                r.id = s.id; r.name = s.name; r.unit = s.unit;
                editRule(r, true);
            }
        }).show();
    }

    private void editRule(final AlertStore.Rule rule, final boolean isNew) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, p, p, 0);
        final EditText min = new EditText(this);
        min.setHint("Minimo (vazio = sem limite)");
        min.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        if (!Double.isNaN(rule.min)) min.setText(AlertStore.fmt(rule.min));
        final EditText max = new EditText(this);
        max.setHint("Maximo (vazio = sem limite)");
        max.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        if (!Double.isNaN(rule.max)) max.setText(AlertStore.fmt(rule.max));
        final CheckBox en = new CheckBox(this);
        en.setText("Regra ligada");
        en.setChecked(rule.enabled);
        String[] names = new String[AlertStore.TONE_NAMES.length + 1];
        names[0] = "Usar som global";
        System.arraycopy(AlertStore.TONE_NAMES, 0, names, 1, AlertStore.TONE_NAMES.length);
        final Spinner tone = new Spinner(this);
        tone.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names));
        tone.setSelection(rule.tone + 1);
        box.addView(min);
        box.addView(max);
        box.addView(en);
        box.addView(row("Som desta regra", tone));

        AlertDialog.Builder bld = new AlertDialog.Builder(this)
                .setTitle(rule.name.isEmpty() ? "Canal " + rule.id : rule.name)
                .setView(box)
                .setPositiveButton("Salvar", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        rule.min = parse(min.getText().toString());
                        rule.max = parse(max.getText().toString());
                        rule.enabled = en.isChecked();
                        rule.tone = tone.getSelectedItemPosition() - 1;
                        List<AlertStore.Rule> all = AlertStore.rules(AlertSettingsActivity.this);
                        boolean found = false;
                        for (int i = 0; i < all.size(); i++) if (all.get(i).id == rule.id) { all.set(i, rule); found = true; }
                        if (!found) all.add(rule);
                        AlertStore.saveRules(AlertSettingsActivity.this, all);
                        AlertBridge.invalidateRules();
                        refreshRules();
                    }
                })
                .setNegativeButton("Cancelar", null);
        if (!isNew) {
            bld.setNeutralButton("Excluir", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    List<AlertStore.Rule> all = AlertStore.rules(AlertSettingsActivity.this);
                    for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).id == rule.id) all.remove(i);
                    AlertStore.saveRules(AlertSettingsActivity.this, all);
                    AlertBridge.invalidateRules();
                    refreshRules();
                }
            });
        }
        bld.show();
    }

    private static double parse(String s) {
        try { return Double.parseDouble(s.trim().replace(',', '.')); } catch (Throwable t) { return Double.NaN; }
    }
}
