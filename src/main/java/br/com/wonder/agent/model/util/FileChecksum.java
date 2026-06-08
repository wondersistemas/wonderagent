package br.com.wonder.agent.model.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class FileChecksum {

    private FileChecksum() {}

    /**
     * Calcula o SHA-1 hex (minúsculas) do arquivo sem carregá-lo inteiro em memória.
     * Retorna null se o arquivo não puder ser lido.
     */
    public static String sha1OrNull(Path file) {
        try {
            return sha1(file);
        } catch (IOException e) {
            return null;
        }
    }

    /** Calcula o SHA-1 hex (minúsculas) do arquivo sem carregá-lo inteiro em memória. */
    public static String sha1(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (InputStream in = new DigestInputStream(Files.newInputStream(file), md)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 não disponível", e);
        }
    }
}
