#!/usr/bin/env python3
"""Ferramentas de patch do FAP Moderno (alertas customizaveis).

Subcomandos:
  locate <decoded_dir>                      -> imprime o classesN.dex que contem o codigo do app
  stub <decoded_dir> <dexname> <out.apk>    -> APK minimo (manifest + dex como classes.dex) p/ apktool decodificar so esse dex
  smali <smali_root>                        -> aplica os hooks nos .smali
  manifest <decoded_dir>                    -> registra a tela de configuracao + permissao de vibracao
  inject <built.apk> <patched_stub.apk> <alerts_classes.dex> <out.apk> <dexname>
                                            -> troca o dex patchado e adiciona o dex das classes de alerta
"""
import glob, os, re, sys, zipfile

VM_FILE = "br/com/folha/fap/FapViewModel$startPolling$1.smali"
APP_FILE = "br/com/folha/fap/FapApplication.smali"
BRIDGE = "Lbr/com/folha/fap/alerts/AlertBridge;"


def die(msg):
    print("ERRO: " + msg, file=sys.stderr)
    sys.exit(1)


def cmd_locate(decoded):
    hits = []
    for f in sorted(glob.glob(os.path.join(decoded, "classes*.dex"))):
        data = open(f, "rb").read()
        if b"Lbr/com/folha/fap/FapViewModel$startPolling$1;" in data and b"Lbr/com/folha/fap/FapApplication;" in data:
            hits.append(os.path.basename(f))
    if len(hits) != 1:
        die("esperava exatamente 1 dex com FapViewModel$startPolling$1 e FapApplication, achei: %s" % hits)
    print(hits[0])


def cmd_stub(decoded, dexname, out):
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(os.path.join(decoded, "AndroidManifest.xml"), "AndroidManifest.xml")
        z.write(os.path.join(decoded, dexname), "classes.dex")


def param_slots(sig, static):
    inner = sig[sig.index("(") + 1: sig.index(")")]
    n = 0 if static else 1
    i = 0
    while i < len(inner):
        c = inner[i]
        if c in "JD":
            n += 2; i += 1
        elif c == "L":
            n += 1; i = inner.index(";", i) + 1
        elif c == "[":
            while inner[i] == "[":
                i += 1
            if inner[i] == "L":
                i = inner.index(";", i) + 1
            else:
                i += 1
            n += 1
        else:
            n += 1; i += 1
    return n


def find_method(text, name_sig_fragment):
    m = re.search(r"^\.method [^\n]*" + re.escape(name_sig_fragment) + r"[^\n]*$", text, re.M)
    if not m:
        die("metodo nao encontrado: " + name_sig_fragment)
    end = text.index("\n.end method", m.end())
    return m.start(), end, m.group(0)


def bump_registers(method_text, header, extra):
    """Aumenta os registradores do metodo em `extra` e devolve (novo_texto, primeiro_registrador_livre)."""
    static = " static " in header
    sig = header[header.index(re.search(r"[\w$<>]+\(", header).group(0)):]
    m = re.search(r"^\s*\.locals (\d+)$", method_text, re.M)
    if m:
        base = int(m.group(1))
        new = re.sub(r"^(\s*)\.locals \d+$", r"\g<1>.locals %d" % (base + extra), method_text, count=1, flags=re.M)
        return new, base
    m = re.search(r"^\s*\.registers (\d+)$", method_text, re.M)
    if not m:
        die("sem .registers/.locals no metodo")
    regs = int(m.group(1))
    base = regs - param_slots(sig, static)
    new = re.sub(r"^(\s*)\.registers \d+$", r"\g<1>.registers %d" % (regs + extra), method_text, count=1, flags=re.M)
    return new, base


def cmd_smali(root):
    cand = glob.glob(os.path.join(root, "**", VM_FILE), recursive=True)
    if len(cand) != 1:
        die("nao achei %s em %s" % (VM_FILE, root))
    path = cand[0]
    text = open(path, encoding="utf-8").read()
    if "AlertBridge" in text:
        print("FapViewModel ja patchado"); 
    else:
        ms, me, header = find_method(text, "invokeSuspend(Ljava/lang/Object;)Ljava/lang/Object;")
        body = text[ms:me]
        calls = list(re.finditer(
            r"^([ \t]*)invoke-virtual \{([vp]\d+), ([vp]\d+), ([vp]\d+), ([vp]\d+), ([vp]\d+)\}, "
            r"Lbr/com/folha/fap/AudioAlerts;->indicator\(IZZI\)V$", body, re.M))
        if len(calls) != 1:
            die("esperava 1 chamada a AudioAlerts.indicator no polling, achei %d" % len(calls))
        c = calls[0]
        indent, id_reg = c.group(1), c.group(3)
        before = body[:c.start()]
        reg = r"\{([vp]\d+)(?: \.\. [vp]\d+)?\}"
        cd = re.findall(r"invoke-virtual(?:/range)? " + reg + r", Lbr/com/folha/fap/core/ChannelDefinition;->getId\(\)I", before)
        dv = re.findall(r"invoke-virtual(?:/range)? " + reg + r", Lbr/com/folha/fap/core/DecodedValue;->getValue\(\)Ljava/lang/Double;", before)
        if not cd or not dv:
            die("nao achei ChannelDefinition/DecodedValue antes da chamada de indicator")
        cd, dv = cd[-1], dv[-1]
        body2, base = bump_registers(body, header, 4)
        # a posicao mudou so no cabecalho (.registers); recalcula o ponto de insercao
        c2 = re.search(re.escape(c.group(0)), body2)
        b0, b1, b2, b3 = ["v%d" % (base + i) for i in range(4)]
        ins = "\n".join(indent + l for l in [
            "move/from16 %s, %s" % (b0, id_reg),
            "invoke-virtual/range {%s .. %s}, Lbr/com/folha/fap/core/DecodedValue;->getValue()Ljava/lang/Double;" % (dv, dv),
            "move-result-object %s" % b1,
            "invoke-virtual/range {%s .. %s}, Lbr/com/folha/fap/core/ChannelDefinition;->getName()Ljava/lang/String;" % (cd, cd),
            "move-result-object %s" % b2,
            "invoke-virtual/range {%s .. %s}, Lbr/com/folha/fap/core/ChannelDefinition;->getUnit()Ljava/lang/String;" % (cd, cd),
            "move-result-object %s" % b3,
            "invoke-static/range {%s .. %s}, %s->onReading(ILjava/lang/Double;Ljava/lang/String;Ljava/lang/String;)V" % (b0, b3, BRIDGE),
        ])
        body2 = body2[:c2.end()] + "\n\n" + ins + body2[c2.end():]
        text = text[:ms] + body2 + text[me:]
        open(path, "w", encoding="utf-8").write(text)
        print("FapViewModel$startPolling$1 patchado (regs livres a partir de v%d; id=%s dv=%s cd=%s)" % (base, id_reg, dv, cd))

    cand = glob.glob(os.path.join(root, "**", APP_FILE), recursive=True)
    if len(cand) != 1:
        die("nao achei " + APP_FILE)
    path = cand[0]
    text = open(path, encoding="utf-8").read()
    if "AlertBridge" in text:
        print("FapApplication ja patchado"); return
    ms, me, header = find_method(text, "onCreate()V")
    body = text[ms:me]
    m = re.search(r"^([ \t]*)invoke-super \{p0\}, Landroid/app/Application;->onCreate\(\)V$", body, re.M)
    if not m:
        die("nao achei invoke-super Application.onCreate em FapApplication.onCreate")
    add = "\n\n%sinvoke-static {p0}, %s->init(Landroid/app/Application;)V" % (m.group(1), BRIDGE)
    body = body[:m.end()] + add + body[m.end():]
    text = text[:ms] + body + text[me:]
    open(path, "w", encoding="utf-8").write(text)
    print("FapApplication patchado")


def cmd_manifest(decoded):
    path = os.path.join(decoded, "AndroidManifest.xml")
    t = open(path, encoding="utf-8").read()
    if "AlertSettingsActivity" in t:
        print("manifest ja patchado"); return
    act = (
        '        <activity android:name="br.com.folha.fap.alerts.AlertSettingsActivity" '
        'android:exported="true" android:label="FAP Alertas" '
        'android:theme="@android:style/Theme.DeviceDefault.Light">\n'
        '            <intent-filter>\n'
        '                <action android:name="android.intent.action.MAIN"/>\n'
        '                <category android:name="android.intent.category.LAUNCHER"/>\n'
        '            </intent-filter>\n'
        '        </activity>\n')
    if "</application>" not in t:
        die("manifest sem </application>")
    t = t.replace("</application>", act + "    </application>", 1)
    if "android.permission.VIBRATE" not in t:
        m = re.search(r"<application\b", t)
        t = t[:m.start()] + '<uses-permission android:name="android.permission.VIBRATE"/>\n    ' + t[m.start():]
    open(path, "w", encoding="utf-8").write(t)
    print("manifest patchado")


def cmd_inject(built, stub_patched, alerts_dex, out, dexname):
    with zipfile.ZipFile(stub_patched) as z:
        patched = z.read("classes.dex")
    new_dex = open(alerts_dex, "rb").read()
    with zipfile.ZipFile(built) as zin:
        names = zin.namelist()
        nums = [1 if n == "classes.dex" else int(re.match(r"classes(\d+)\.dex$", n).group(1))
                for n in names if re.match(r"classes\d*\.dex$", n)]
        if dexname not in names:
            die("%s nao esta em %s" % (dexname, built))
        extra = "classes%d.dex" % (max(nums) + 1)
        with zipfile.ZipFile(out, "w") as zout:
            for info in zin.infolist():
                data = patched if info.filename == dexname else zin.read(info.filename)
                zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
                zi.compress_type = info.compress_type
                zi.external_attr = info.external_attr
                zout.writestr(zi, data)
            zi = zipfile.ZipInfo(extra, date_time=(2024, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(zi, new_dex)
    print("%s substituido; alertas adicionados como %s" % (dexname, extra))


if __name__ == "__main__":
    a = sys.argv[1:]
    if not a: die(__doc__)
    {"locate": lambda: cmd_locate(*a[1:]), "stub": lambda: cmd_stub(*a[1:]), "smali": lambda: cmd_smali(*a[1:]),
     "manifest": lambda: cmd_manifest(*a[1:]), "inject": lambda: cmd_inject(*a[1:])}[a[0]]()
