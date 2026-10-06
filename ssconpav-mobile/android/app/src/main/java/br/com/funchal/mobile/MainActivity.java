package br.com.ssconpav.mobile;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.ServerPath;
import com.getcapacitor.WebViewListener;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Casca do SSCONPAV Campo.
 *
 * REGRA DE OURO: a tela baixada NUNCA é aberta como arquivo (file://) — o
 * Android nega esse acesso e o app ficava preso em "Página da Web não
 * disponível". A tela baixada é servida pelo próprio servidor interno do
 * Capacitor, no mesmo endereço da tela de fábrica (https://localhost/), então
 * os dados do aparelho (banco local, fila de envio, usuário) continuam os
 * mesmos depois de cada atualização.
 *
 * Proteções, nesta ordem, antes de usar uma tela baixada:
 *   1. ela tem que existir e passar na conferência de integridade
 *      (Tela.integra): tamanho mínimo, APP_VERSION dentro, fecha com </html>;
 *   2. a versão dela não pode ser mais velha que a de fábrica (um APK novo
 *      descarta telas antigas);
 *   3. se já tentou abrir 3 vezes sem a tela avisar que carregou
 *      (AndroidTeste.telaOk), ela é descartada.
 * Em qualquer falha o app abre a tela de fábrica, que funciona sem internet;
 * a atualização acontece de novo quando a conexão voltar.
 */
public class MainActivity extends BridgeActivity {
    private static final String TAG = "SSCONPAV";
    static final String PREFS = "ssconpav_app";
    static final int MAX_TENTATIVAS = 3;

    private boolean usandoTelaBaixada = false;

    @Override
    protected void load() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        File pasta = Tela.pasta(this);
        File tela = Tela.ativa(this);

        String versaoFabrica = lerVersaoDoAsset();
        if (versaoFabrica != null && prefs.getString("tela_versao_fabrica", null) == null) {
            prefs.edit().putString("tela_versao_fabrica", versaoFabrica).apply();
        }

        if (tela.exists()) {
            String versaoBaixada = Tela.versaoDe(tela);
            int tentativas = prefs.getInt("tela_tentativas", 0);
            String motivo = null;
            if (!Tela.integra(tela)) motivo = "arquivo incompleto ou estragado";
            else if (versaoFabrica != null && versaoBaixada != null && versaoBaixada.compareTo(versaoFabrica) < 0)
                motivo = "mais velha que a de fábrica (" + versaoBaixada + " < " + versaoFabrica + ")";
            else if (tentativas >= MAX_TENTATIVAS) motivo = "não carregou " + tentativas + " vezes";

            if (motivo != null) {
                Log.w(TAG, "Descartando a tela baixada: " + motivo);
                Tela.descartar(this);
            } else {
                prefs.edit().putInt("tela_tentativas", tentativas + 1).apply();
                usandoTelaBaixada = true;
                bridgeBuilder.setServerPath(new ServerPath(ServerPath.PathType.BASE_PATH, pasta.getAbsolutePath()));
                Log.i(TAG, "Servindo a tela baixada " + versaoBaixada + " por https://localhost/");
            }
        }

        if (!usandoTelaBaixada && prefs.getString("tela_versao", null) == null && versaoFabrica != null) {
            prefs.edit().putString("tela_versao", versaoFabrica).apply();
        }

        /* Se a página principal falhar de verdade enquanto servimos a tela
           baixada, volta para a de fábrica na hora — sem esperar 3 aberturas. */
        bridgeBuilder.addWebViewListener(new WebViewListener() {
            @Override public void onReceivedError(WebView webView) {
                if (usandoTelaBaixada) {
                    Log.w(TAG, "Erro ao carregar a tela baixada — voltando para a de fábrica");
                    usandoTelaBaixada = false;
                    Tela.descartar(MainActivity.this);
                    String vf = lerVersaoDoAsset();
                    if (vf != null) getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("tela_versao", vf).apply();
                    if (getBridge() != null) getBridge().setServerAssetPath("public");
                }
            }
        });

        super.load();

        WebView webView = getBridge().getWebView();
        webView.addJavascriptInterface(new AndroidTeste(this, webView), "AndroidTeste");
    }

    /** Chamado pela tela (via AndroidTeste.telaOk) quando ela terminou de iniciar. */
    void telaCarregouBem() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("tela_tentativas", 0).apply();
    }

    boolean estaUsandoTelaBaixada() { return usandoTelaBaixada; }
    void marcarUsoTelaBaixada(boolean v) { usandoTelaBaixada = v; }

    private String lerVersaoDoAsset() {
        try (InputStream in = getAssets().open("public/index.html")) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String linha;
            while ((linha = r.readLine()) != null) {
                String v = Tela.versaoNaLinha(linha);
                if (v != null) return v;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
