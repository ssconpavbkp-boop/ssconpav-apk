package br.com.ssconpav.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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
 * A "casca" nativa do SSCONPAV Campo. Duas coisas se atualizam por caminhos
 * diferentes, e cada uma tem seu par de métodos aqui:
 *
 *   - A TELA (o index.html): procurarAtualizacao() baixa um index.html novo
 *     para a área de dados do app; aplicarAtualizacao() troca para ele e
 *     recarrega. Não pede nada ao usuário — é só conteúdo, não código.
 *
 *   - O APLICATIVO (este .apk): procurarAtualizacaoDoApp() baixa o .apk
 *     publicado; instalarAtualizacaoDoApp() abre o instalador do Android,
 *     que sempre pede um toque de confirmação — não tem como pular isso.
 *
 * O JS chama estes métodos e nós avisamos de volta chamando
 * avisarAtualizacao(estado, valor) / avisarApp(estado, valor) dentro da
 * própria WebView.
 */
public class AndroidTeste {
    private static final String TAG = "AndroidTeste";
    private static final String CANAL = "https://ssconpavbkp-boop.github.io/ssconpav-apk/";

    private final Activity activity;
    private final WebView webView;
    private final String prefsName = "ssconpav_app";

    public AndroidTeste(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    private android.content.SharedPreferences prefs() {
        return activity.getSharedPreferences(prefsName, Activity.MODE_PRIVATE);
    }

    private void jsChamar(String funcao, String estado, String valor) {
        String js = funcao + "(" + JSONObject.quote(estado) + "," + JSONObject.quote(valor == null ? "" : valor) + ")";
        activity.runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    /* ============================ TELA (index.html) ===================== */

    private File pastaTela() { return new File(activity.getFilesDir(), "tela"); }
    private File telaAtiva() { return new File(pastaTela(), "index.html"); }
    private File telaBaixando() { return new File(pastaTela(), "index.baixando.html"); }

    /** Versão embutida no index.html que está de fato carregado agora
     *  (o de fábrica, dentro do .apk, ou um já baixado por cima dele). */
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

                baixarArquivo(CANAL + "index.html?t=" + System.currentTimeMillis(), telaBaixando());
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
                if (!pastaTela().exists()) pastaTela().mkdirs();
                File baixando = telaBaixando();
                if (!baixando.exists()) return;
                File ativa = telaAtiva();
                if (ativa.exists()) ativa.delete();
                baixando.renameTo(ativa);
                String nova = prefs().getString("tela_versao_baixada", "");
                prefs().edit().putString("tela_versao", nova).remove("tela_versao_baixada").apply();
                webView.loadUrl(Uri.fromFile(ativa).toString());
            } catch (Exception e) { Log.w(TAG, "aplicarAtualizacao", e); }
        });
    }

    @JavascriptInterface
    public void voltarVersaoDeFabrica() {
        activity.runOnUiThread(() -> {
            File ativa = telaAtiva();
            if (ativa.exists()) ativa.delete();
            prefs().edit().remove("tela_versao").remove("tela_versao_baixada").apply();
            webView.loadUrl("file:///android_asset/public/index.html");
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
        c.setConnectTimeout(20000); c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(destino)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally { c.disconnect(); }
    }
}
