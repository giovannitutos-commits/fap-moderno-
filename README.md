# FAP Moderno + Alertas customizaveis

Patch por engenharia reversa (sem codigo-fonte) do `FAP_Moderno_1_0_2.apk`.

## O que muda no app
- **Icone novo "FAP Alertas"** no launcher: tela de configuracao.
  - Regras por parametro (minimo e/ou maximo), ligar/desligar cada uma.
  - Som: bipe padrao (7 opcoes), arquivo de audio seu, ou toque do sistema; volume, repeticoes,
    intervalo de repeticao enquanto continuar fora da faixa, volume de ALARME opcional, vibracao, botao Testar.
  - Alerta na tela: faixa vermelha piscando no topo do app com o parametro e o valor. Toque nela para silenciar.
- Lista de parametros para criar regras e preenchida automaticamente conforme o app le o carro
  (conecte ao carro e deixe o painel rodar uma vez).
- O resto do app (bipes por canal, faixas de cor, logs) continua igual.

## Como usar no GitHub
1. Crie um repositorio e suba **estes arquivos + o APK original** (`FAP_Moderno_1_0_2.apk`, na raiz).
2. Aba **Actions** > *Build FAP Moderno + Alertas* > **Run workflow**.
3. Baixe o artefato `FAP_Moderno_Alertas` (o APK assinado).

### Assinatura (recomendado, para poder atualizar por cima)
Sem secrets o workflow usa uma chave temporaria diferente a cada build. Para ter chave fixa:
```
keytool -genkeypair -keystore fap.jks -alias fap -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 fap.jks
```
Em *Settings > Secrets and variables > Actions* crie: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`
(a senha da chave deve ser igual a do keystore).

> A assinatura difere da do APK original: desinstale o app original antes (exporte seus logs/layouts antes!).

## Como funciona (resumo)
- `apktool d -s` decodifica manifest/recursos sem tocar nos dex.
- So o dex com o codigo do app e desmontado em smali; dois hooks sao inseridos:
  `FapApplication.onCreate` -> `AlertBridge.init` e, no loop de polling
  (`FapViewModel$startPolling$1`, logo apos `AudioAlerts.indicator`) -> `AlertBridge.onReading(id, valor, nome, unidade)`.
- `src/` (Java puro, views nativas) vira um dex extra (`javac` + `d8`) adicionado ao APK.
- `zipalign` + `apksigner`.

Se o app for atualizado (outra versao), o script falha com mensagem clara caso os pontos de hook mudem.
