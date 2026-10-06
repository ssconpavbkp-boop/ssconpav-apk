package br.com.ssconpav.mobile;

import android.content.Context;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Onde a tela baixada mora e como se confere se ela está inteira. */
final class Tela {
    static final long TAMANHO_MINIMO = 50 * 1024;   // a tela real tem ~240 KB
    private static final Pattern VERSAO = Pattern.compile("APP_VERSION\\s*=\\s*'([^']+)'");

    private Tela() {}

    static File pasta(Context c)     { return new File(c.getFilesDir(), "tela"); }
    static File ativa(Context c)     { return new File(pasta(c), "index.html"); }
    static File baixando(Context c)  { return new File(c.getCacheDir(), "index.baixando.html"); }

    static String versaoNaLinha(String linha) {
        Matcher m = VERSAO.matcher(linha);
        return m.find() ? m.group(1) : null;
    }

    /** Versão embutida no arquivo (lê só o começo, onde a constante fica). */
    static String versaoDe(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            int n = (int) Math.min(raf.length(), 200 * 1024);
            byte[] b = new byte[n]; raf.readFully(b);
            return versaoNaLinha(new String(b, StandardCharsets.UTF_8));
        } catch (Exception e) { return null; }
    }

    /** Inteira = tamanho razoável, tem a versão dentro e termina com </html>. */
    static boolean integra(File f) {
        try {
            if (!f.isFile() || f.length() < TAMANHO_MINIMO) return false;
            if (versaoDe(f) == null) return false;
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                int n = (int) Math.min(raf.length(), 4096);
                raf.seek(raf.length() - n);
                byte[] b = new byte[n]; raf.readFully(b);
                String fim = new String(b, StandardCharsets.UTF_8).trim().toLowerCase();
                return fim.endsWith("</html>");
            }
        } catch (Exception e) { return false; }
    }

    static void descartar(Context c) {
        File a = ativa(c); if (a.exists()) a.delete();
        File b = baixando(c); if (b.exists()) b.delete();
        c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
            .remove("tela_versao").remove("tela_versao_baixada").putInt("tela_tentativas", 0).apply();
    }
}
