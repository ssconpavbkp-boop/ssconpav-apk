package br.com.ssconpav.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;

/**
 * Ponte JS <-> casca do SSCONPAV Campo. Duas coisas se atualizam por caminhos
 * diferentes:
 *
 *   - A TELA (index.html): procurarAtualizacao() baixa para um arquivo
 *     temporário, confere se veio inteira (Tela.integra) e só então avisa
 *     "pronta"; aplicarAtualizacao() move para a pasta da tela e manda o
 *     servidor interno do Capacitor passar a servir dali — mesmo endereço
 *     https://localhost/, nunca file://.
 *
 *   - O APLICATIVO (.apk): baixa e abre o instalador do Android, que sempre
 *     pede um toque.
 */
public class AndroidTeste {
    private static final String TAG = "AndroidTeste";
    private static final String CANAL = "https://ssconpavbkp-boop.github.io/ssconpav-apk/";

    private final MainActivity activity;
    private final WebView webView;

    public AndroidTeste(MainActivity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    private android.content.SharedPreferences prefs() {
        return activity.getSharedPreferences(MainActivity.PREFS, Activity.MODE_PRIVATE);
    }

    private void jsChamar(String funcao, String estado, String valor) {
        String js = "typeof " + funcao + "==='function'&&" + funcao + "(" + JSONObject.quote(estado) + "," + JSONObject.quote(valor == null ? "" : valor) + ")";
        activity.runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    /* ============================ TELA (index.html) ===================== */

    /** A tela chama isto quando terminou de iniciar: zera o contador de tentativas. */
    @JavascriptInterface
    public void telaOk() { activity.telaCarregouBem(); }

    @JavascriptInterface
    public String versaoBaixada() {
        return prefs().getString("tela_versao", "");
    }

    @JavascriptInterface
    public void procurarAtualizacao() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String json = baixarTexto(CANAL + "versao.json?t=" + System.currentTimeMillis());
                JSONObject j = new JSONObject(json);
                String nova = j.optString("appVersion", "");
                String atual = versaoBaixada();
                if (nova.isEmpty()) { jsChamar("avisarAtualizacao", "erro", "sem versão publicada"); return; }
                if (nova.equals(atual)) { jsChamar("avisarAtualizacao", "igual", nova); return; }

                File tmp = Tela.baixando(activity);
                baixarArquivo(CANAL + "index.html?t=" + System.currentTimeMillis(), tmp);
                /* Só aceita se veio inteira e é mesmo a versão anunciada. */
                if (!Tela.integra(tmp) || !nova.equals(Tela.versaoDe(tmp))) {
                    tmp.delete();
                    jsChamar("avisarAtualizacao", "erro", "download incompleto — vai tentar de novo mais tarde");
                    return;
                }
                prefs().edit().putString("tela_versao_baixada", nova).apply();
                jsChamar("avisarAtualizacao", "pronta", nova);
            } catch (Exception e) {
                Log.w(TAG, "procurarAtualizacao", e);
                jsChamar("avisarAtualizacao", "erro", String.valueOf(e.getMessage()));
            }
        });
    }

    @JavascriptInterface
    public void aplicarAtualizacao() {
        activity.runOnUiThread(() -> {
            try {
                File tmp = Tela.baixando(activity);
                if (!tmp.exists() || !Tela.integra(tmp)) { if (tmp.exists()) tmp.delete(); return; }
                File pasta = Tela.pasta(activity);
                if (!pasta.exists()) pasta.mkdirs();
                File ativa = Tela.ativa(activity);
                File nova = new File(pasta, "index.nova.html");
                copiar(tmp, nova);
                tmp.delete();
                if (!Tela.integra(nova)) { nova.delete(); return; }
                if (ativa.exists()) ativa.delete();
                if (!nova.renameTo(ativa)) { nova.delete(); return; }

                String versao = prefs().getString("tela_versao_baixada", "");
                prefs().edit().putString("tela_versao", versao).remove("tela_versao_baixada")
                       .putInt("tela_tentativas", 1).apply();
                activity.marcarUsoTelaBaixada(true);
                /* Servir pela pasta — mesmo https://localhost/, nada de file:// */
                activity.getBridge().setServerBasePath(pasta.getAbsolutePath());
            } catch (Exception e) { Log.w(TAG, "aplicarAtualizacao", e); }
        });
    }

    @JavascriptInterface
    public void voltarVersaoDeFabrica() {
        activity.runOnUiThread(() -> {
            Tela.descartar(activity);
            activity.marcarUsoTelaBaixada(false);
            activity.getBridge().setServerAssetPath("public");
        });
    }

    /* ============================ APLICATIVO (.apk) ======================= */

    private File apkBaixado() { return new File(activity.getCacheDir(), "ssconpav-campo-novo.apk"); }

    @JavascriptInterface
    public String versaoDoApp() {
        try {
            PackageInfo pi = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            long codigo = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : pi.versionCode;
            return pi.versionName + "|" + codigo;
        } catch (Exception e) { return "?|0"; }
    }

    @JavascriptInterface
    public void procurarAtualizacaoDoApp() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String json = baixarTexto(CANAL + "versao.json?t=" + System.currentTimeMillis());
                JSONObject j = new JSONObject(json);
                String nomeNovo = j.optString("apkVersionName", "");
                int codigoNovo = j.optInt("apkVersionCode", -1);
                String url = j.optString("apkUrl", "");
                if (nomeNovo.isEmpty() || codigoNovo < 0) { jsChamar("avisarApp", "erro", "sem versão de app publicada"); return; }

                long codigoAtual;
                try {
                    PackageInfo pi = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
                    codigoAtual = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : pi.versionCode;
                } catch (Exception e) { codigoAtual = 0; }

                if (codigoNovo <= codigoAtual) { jsChamar("avisarApp", "igual", nomeNovo); return; }
                if (url.isEmpty()) { jsChamar("avisarApp", "existe", nomeNovo); return; }

                baixarArquivo(url, apkBaixado());
                prefs().edit().putString("app_versao_baixada", nomeNovo).apply();
                jsChamar("avisarApp", "pronta", nomeNovo);
            } catch (Exception e) {
                Log.w(TAG, "procurarAtualizacaoDoApp", e);
                jsChamar("avisarApp", "erro", String.valueOf(e.getMessage()));
            }
        });
    }

    @JavascriptInterface
    public void instalarAtualizacaoDoApp() {
        activity.runOnUiThread(() -> {
            File apk = apkBaixado();
            if (!apk.exists()) return;
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        });
    }

    @JavascriptInterface
    public void abrirAjustes() {
        activity.runOnUiThread(() -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        });
    }

    /* ================================ helpers ============================ */

    private String baixarTexto(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    private void baixarArquivo(String urlStr, File destino) throws Exception {
        if (destino.getParentFile() != null) destino.getParentFile().mkdirs();
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(20000); c.setReadTimeout(30000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(destino)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.getFD().sync();
        } finally { c.disconnect(); }
    }

    private static void copiar(File de, File para) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(de); FileOutputStream out = new FileOutputStream(para)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.getFD().sync();
        }
    }
}
