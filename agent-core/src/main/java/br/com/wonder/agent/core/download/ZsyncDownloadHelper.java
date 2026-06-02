package br.com.wonder.agent.core.download;

import com.salesforce.zsync.Zsync;
import com.salesforce.zsync.ZsyncChecksumValidationFailedException;
import com.salesforce.zsync.ZsyncException;
import com.salesforce.zsync.http.Credentials;
import com.salesforce.zsync.internal.ChecksumValidationIOException;
import com.squareup.okhttp.OkHttpClient;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Encapsula a lógica de download via zsync compartilhada entre ArtifactDownloader e
 * WildflyProvisioner.
 *
 * Centraliza:
 * - Criação do OkHttpClient com interceptor para multipart ranges (workaround Reposilite)
 * - Locale.ENGLISH para parsing de MTime no zsync4j
 * - Verificação de alinhamento de bloco antes de usar arquivo como base delta
 * - Renomeação para .zs-old antes de passar como input (evita corrupção input==output)
 * - Padding de 4096 bytes após download para preservar alinhamento para delta futuro
 * - Fallback HTTP direto quando .zsync não existe no servidor
 */
@Slf4j
@ApplicationScoped
public class ZsyncDownloadHelper {

    @ConfigProperty(name = "download.repository.username")
    String username;

    @ConfigProperty(name = "download.repository.password")
    Optional<String> password;

    private final Zsync zsync;

    ZsyncDownloadHelper() {
        // zsync4j usa SimpleDateFormat sem Locale para parsear MTime — bug da biblioteca.
        // Forçar Locale.ENGLISH garante que nomes de mês RFC 2822 sejam reconhecidos.
        Locale.setDefault(Locale.ENGLISH);
        OkHttpClient client = new OkHttpClient();
        client.setReadTimeout(30, TimeUnit.SECONDS);
        client.setWriteTimeout(30, TimeUnit.SECONDS);
        // Reposilite não suporta multipart ranges (bytes=a-b,c-d) — responde 200 com
        // o arquivo inteiro em vez de 206 Partial Content. O zsync4j não consegue processar
        // isso corretamente. O interceptor detecta essa situação e re-emite como range simples
        // (bytes=a-b) pegando apenas o primeiro range solicitado, forçando o zsync4j a iterar
        // um range por vez (menos eficiente, mas correto).
        AtomicBoolean multipartWarned = new AtomicBoolean(false);
        client.interceptors().add(chain -> {
            com.squareup.okhttp.Request req = chain.request();
            String range = req.header("Range");
            log.trace("zsync → Range: {} — {}", range, req.url());
            com.squareup.okhttp.Response resp = chain.proceed(req);
            log.trace("zsync ← HTTP {} Content-Range: {} Content-Length: {}",
                    resp.code(),
                    resp.header("Content-Range", "ausente"),
                    resp.header("Content-Length", "ausente"));
            if (range != null && resp.code() == 200 && resp.header("Content-Range") == null) {
                int comma = range.indexOf(',');
                if (comma > 0) {
                    String firstRange = range.substring(0, comma).trim();
                    if (multipartWarned.compareAndSet(false, true)) {
                        log.warn("Servidor não suporta multipart ranges — cada bloco será requisitado individualmente (sem impacto na integridade do download)");
                    }
                    resp.body().close();
                    com.squareup.okhttp.Request singleReq = req.newBuilder()
                            .header("Range", firstRange)
                            .build();
                    resp = chain.proceed(singleReq);
                    log.trace("zsync ← (retry single) HTTP {} Content-Range: {}",
                            resp.code(), resp.header("Content-Range", "ausente"));
                }
            }
            return resp;
        });
        this.zsync = new Zsync(client);
    }

    ZsyncDownloadHelper(Zsync zsync) {
        this.zsync = zsync;
    }

    public ZsyncDownloadHelper(Zsync zsync, String username, Optional<String> password) {
        this.zsync = zsync;
        this.username = username;
        this.password = password;
    }

    /**
     * Baixa um arquivo via zsync delta com todas as correções aplicadas.
     *
     * Fluxo:
     * 1. Se outFile existir e estiver alinhado a 4096 bytes: renomeia para .zs-old e usa como base delta
     * 2. Executa zsync
     * 3. Aplica padding de 4096 bytes no resultado
     * 4. Remove .zs-old
     *
     * Se o zsync falhar (ex: .zsync não existe no servidor), faz fallback HTTP direto.
     *
     * @param zsyncUrl URL do arquivo .zsync remoto
     * @param zipUrl   URL do arquivo completo (fallback HTTP)
     * @param outFile  caminho de destino do download
     * @param progress callback de progresso para exibição no console
     * @return caminho do arquivo baixado (= outFile)
     */
    public Path download(String zsyncUrl, String zipUrl, Path outFile,
                         Consumer<String> progress) throws DownloadHelperException {
        String host = URI.create(zipUrl).getHost();
        Path zsOld = outFile.resolveSibling(outFile.getFileName() + ".zs-old");

        try {
            Files.createDirectories(outFile.getParent());
        } catch (IOException e) {
            throw new DownloadHelperException("Não foi possível criar diretório: " + outFile.getParent(), e);
        }

        prepareBaseFile(outFile, zsOld, progress);

        Zsync.Options options = new Zsync.Options()
                .setOutputFile(outFile)
                .putCredentials(host, new Credentials(username, password.orElse("")));
        if (Files.exists(zsOld)) {
            options = options.addInputFile(zsOld);
        }

        DownloadProgressObserver observer = new DownloadProgressObserver(progress);
        try {
            progress.accept("  Transferindo...");
            Path result = zsync.zsync(URI.create(zsyncUrl), options, observer);
            observer.reportFinal();
            padToBlockBoundary(result);
            return result;
        } catch (ZsyncChecksumValidationFailedException e) {
            logChecksumFailure(e, zsyncUrl);
            try { Files.deleteIfExists(outFile); } catch (IOException ignored) {}
            throw new DownloadHelperException("Checksum SHA-1 inválido após download de " + zsyncUrl, e);
        } catch (ZsyncException e) {
            log.warn("zsync falhou ({}); usando download HTTP direto: {}", e.getMessage(), zipUrl);
            progress.accept("  zsync indisponível — download HTTP direto");
            return downloadDirect(zipUrl, outFile, progress);
        } finally {
            try { Files.deleteIfExists(zsOld); } catch (IOException ignored) {}
        }
    }

    /**
     * Variante sem padding pós-download.
     * Usar quando o arquivo baixado não será base de delta futuro (ex: ZIPs).
     */
    public Path downloadNoPad(String zsyncUrl, String zipUrl, Path outFile,
                              Consumer<String> progress) throws DownloadHelperException {
        String host = URI.create(zipUrl).getHost();
        Path zsOld = outFile.resolveSibling(outFile.getFileName() + ".zs-old");

        try {
            Files.createDirectories(outFile.getParent());
        } catch (IOException e) {
            throw new DownloadHelperException("Não foi possível criar diretório: " + outFile.getParent(), e);
        }

        prepareBaseFile(outFile, zsOld, progress);

        Zsync.Options options = new Zsync.Options()
                .setOutputFile(outFile)
                .putCredentials(host, new Credentials(username, password.orElse("")));
        if (Files.exists(zsOld)) {
            options = options.addInputFile(zsOld);
        }

        DownloadProgressObserver observer = new DownloadProgressObserver(progress);
        try {
            progress.accept("  Transferindo...");
            Path result = zsync.zsync(URI.create(zsyncUrl), options, observer);
            observer.reportFinal();
            return result;
        } catch (ZsyncChecksumValidationFailedException e) {
            logChecksumFailure(e, zsyncUrl);
            try { Files.deleteIfExists(outFile); } catch (IOException ignored) {}
            throw new DownloadHelperException("Checksum SHA-1 inválido após download de " + zsyncUrl, e);
        } catch (ZsyncException e) {
            log.warn("zsync falhou ({}); usando download HTTP direto: {}", e.getMessage(), zipUrl);
            progress.accept("  zsync indisponível — download HTTP direto");
            return downloadDirect(zipUrl, outFile, progress);
        } finally {
            try { Files.deleteIfExists(zsOld); } catch (IOException ignored) {}
        }
    }

    private void logChecksumFailure(ZsyncChecksumValidationFailedException e, String url) {
        if (e.getCause() instanceof ChecksumValidationIOException cv) {
            log.error("Checksum SHA-1 inválido em {}: esperado={} obtido={}", url,
                    cv.getExpectedChecksum(), cv.getActualChecksum());
        } else {
            log.error("Checksum SHA-1 inválido em {}: {}", url, e.getMessage());
        }
    }

    // Renomeia outFile para zsOld se existir e estiver alinhado a 4096 bytes.
    // Arquivo desalinhado é descartado — seria rejeitado pelo zsync4j de qualquer forma.
    private void prepareBaseFile(Path outFile, Path zsOld, Consumer<String> progress) {
        if (!Files.exists(outFile)) {
            progress.accept("  Nenhuma versão anterior em cache — download completo");
            return;
        }
        long size = sizeOf(outFile);
        if (size % 4096 == 0) {
            try {
                Files.move(outFile, zsOld, StandardCopyOption.REPLACE_EXISTING);
                log.debug("Arquivo anterior ({} bytes, alinhado) renomeado para delta: {}", size, zsOld);
                progress.accept("  Arquivo anterior encontrado — usando delta (zsync)");
            } catch (IOException e) {
                log.warn("Não foi possível renomear arquivo anterior para delta: {}", e.getMessage());
                progress.accept("  Nenhuma versão anterior em cache — download completo");
            }
        } else {
            log.warn("Cache local não está alinhado ao blocksize ({} bytes) — descartando para download completo: {}",
                    size, outFile);
            try { Files.deleteIfExists(outFile); } catch (IOException ignored) {}
            progress.accept("  Nenhuma versão anterior em cache — download completo");
        }
    }

    // Fallback: download HTTP direto quando o .zsync não existe no servidor
    private Path downloadDirect(String url, Path outFile, Consumer<String> progress)
            throws DownloadHelperException {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(120_000);
            if (username != null && !username.isBlank()) {
                String cred = username + ":" + password.orElse("");
                conn.setRequestProperty("Authorization",
                        "Basic " + Base64.getEncoder().encodeToString(cred.getBytes()));
            }
            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw new DownloadHelperException("Acesso negado ao baixar " + url + " (HTTP 401)", null);
            }
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new DownloadHelperException("Arquivo não encontrado: " + url + " (HTTP 404)", null);
            }
            if (status < 200 || status >= 300) {
                throw new DownloadHelperException("Falha HTTP " + status + " ao baixar " + url, null);
            }
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream()) {
                Files.copy(in, outFile, StandardCopyOption.REPLACE_EXISTING);
            }
            if (total > 0) {
                progress.accept("  Download concluído: " + (total / 1024 / 1024) + " MB");
            } else {
                progress.accept("  Download concluído");
            }
            conn.disconnect();
            return outFile;
        } catch (DownloadHelperException e) {
            throw e;
        } catch (IOException e) {
            throw new DownloadHelperException("Falha no download HTTP de " + url, e);
        }
    }

    // zsync4j sobrescreve o outputFile sem padding. Reaplica para que o arquivo
    // sirva como base de delta zsync em atualizações futuras.
    static void padToBlockBoundary(Path file) throws DownloadHelperException {
        if (!Files.exists(file)) return;
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new DownloadHelperException("Não foi possível obter tamanho de " + file, e);
        }
        long remainder = size % 4096;
        if (remainder == 0) return;
        long padding = 4096 - remainder;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(size);
            raf.write(new byte[(int) padding]);
        } catch (IOException e) {
            throw new DownloadHelperException("Não foi possível aplicar padding em " + file, e);
        }
        log.debug("Arquivo padded após download: {} → {} bytes (+{})", size, size + padding, padding);
    }

    static long sizeOf(Path file) {
        try { return Files.size(file); } catch (IOException e) { return -1; }
    }

    public static class DownloadHelperException extends Exception {
        public DownloadHelperException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
