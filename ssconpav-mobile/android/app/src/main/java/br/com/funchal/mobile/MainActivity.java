package br.com.ssconpav.mobile;

import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView webView = this.bridge.getWebView();
        webView.addJavascriptInterface(new AndroidTeste(this, webView), "AndroidTeste");

        android.content.SharedPreferences prefs = getSharedPreferences("ssconpav_app", MODE_PRIVATE);
        File telaBaixada = new File(getFilesDir(), "tela/index.html");

        if (telaBaixada.exists()) {
            /* Já existe uma tela baixada por cima da de fábrica — usa ela. */
            webView.loadUrl(Uri.fromFile(telaBaixada).toString());
        } else if (prefs.getString("tela_versao", null) == null) {
            /* Primeira abertura: descobre a versão que veio de fábrica, dentro
               do próprio .apk, e grava -- assim a comparação de versão já
               funciona mesmo antes de qualquer atualização. */
            String versao = lerVersaoDoAsset();
            if (versao != null) prefs.edit().putString("tela_versao", versao).apply();
        }
    }

    private String lerVersaoDoAsset() {
        try (InputStream in = getAssets().open("public/index.html")) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String linha;
            Pattern p = Pattern.compile("APP_VERSION\\s*=\\s*'([^']+)'");
            while ((linha = r.readLine()) != null) {
                Matcher m = p.matcher(linha);
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
