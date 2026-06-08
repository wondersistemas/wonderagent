package br.com.wonder.agent.cli.command;

import io.quarkus.picocli.runtime.PicocliRunner;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;

/**
 * Entry point customizado para garantir encoding correto no console do Windows.
 * Lê o codepage ativo via "chcp" para usar exatamente o encoding que o CMD espera.
 * Sem isso, caracteres acentuados saem corrompidos (UTF-8 vs CP850/CP1252).
 */
@QuarkusMain
public class Main implements QuarkusApplication {

    @Inject
    PicocliRunner picocliRunner;

    @Override
    public int run(String... args) throws Exception {
        applyConsoleEncoding();
        WonderAgentCommand.enableTrace(containsTrace(args));
        return picocliRunner.run(args);
    }

    private static boolean containsTrace(String[] args) {
        for (String arg : args) {
            if ("--trace".equals(arg)) return true;
        }
        return false;
    }

    private static void applyConsoleEncoding() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        try {
            Charset consoleCharset = detectWindowsConsoleCharset();
            System.setOut(new PrintStream(System.out, true, consoleCharset));
            System.setErr(new PrintStream(System.err, true, consoleCharset));
        } catch (Exception ignored) {}
    }

    // Executa "chcp" e parseia "Página de código ativa: 850" ou "Active code page: 850"
    private static Charset detectWindowsConsoleCharset() throws Exception {
        Process process = new ProcessBuilder("cmd", "/c", "chcp")
                .redirectErrorStream(true)
                .start();
        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            output = reader.readLine();
        }
        process.waitFor();

        if (output != null) {
            // extrai o número no final da linha: "... : 850"
            String trimmed = output.trim();
            int colon = trimmed.lastIndexOf(':');
            if (colon >= 0) {
                String cpNum = trimmed.substring(colon + 1).trim();
                try {
                    return Charset.forName("cp" + cpNum);
                } catch (Exception ignored) {}
            }
        }
        // fallback: CP850 é o codepage OEM padrão do CMD em instalações pt-BR
        return Charset.forName("cp850");
    }
}
