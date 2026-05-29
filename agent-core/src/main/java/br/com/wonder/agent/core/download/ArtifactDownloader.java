package br.com.wonder.agent.core.download;

import br.com.wonder.agent.model.deploy.Artifact;
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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@ApplicationScoped
public class ArtifactDownloader {

    @ConfigProperty(name = "download.temp-dir")
    String tempDir;

    @ConfigProperty(name = "download.repository.username")
    String username;

    @ConfigProperty(name = "download.repository.password")
    Optional<String> password;

    private final Zsync zsync;

    ArtifactDownloader() {
        // zsync4j usa SimpleDateFormat sem Locale para parsear MTime — bug da biblioteca.
        // Forçar Locale.ENGLISH garante que nomes de mês RFC 2822 sejam reconhecidos.
        // Impacto mínimo: o agente é um processo dedicado, não um servidor multi-tenant.
        Locale.setDefault(Locale.ENGLISH);
        OkHttpClient client = new OkHttpClient();
        client.setReadTimeout(30, TimeUnit.SECONDS);
        client.setWriteTimeout(30, TimeUnit.SECONDS);
        // Reposilite não suporta multipart ranges (bytes=a-b,c-d) — responde 200 com
        // o arquivo inteiro em vez de 206 Partial Content. O zsync4j não consegue processar
        // isso corretamente. O interceptor detecta essa situação e re-emite como range simples
        // (bytes=a-b) pegando apenas o primeiro range solicitado, forçando o zsync4j a iterar
        // um range por vez (menos eficiente, mas correto).
        java.util.concurrent.atomic.AtomicBoolean multipartWarned = new java.util.concurrent.atomic.AtomicBoolean(false);
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

    ArtifactDownloader(Zsync zsync) {
        this.zsync = zsync;
    }

    public Artifact download(Artifact artifact) throws DownloadException {
        return download(artifact, msg -> {}, java.util.Optional.empty());
    }

    public Artifact download(Artifact artifact, java.util.function.Consumer<String> progress) throws DownloadException {
        return download(artifact, progress, java.util.Optional.empty());
    }

    public Artifact download(Artifact artifact, java.util.function.Consumer<String> progress,
                             java.util.Optional<Path> installedWarHint) throws DownloadException {
        if (artifact.warUrl() == null || artifact.warUrl().isBlank()) {
            throw new DownloadException(
                "warUrl vazio no desired-state — verifique a configuração do servidor central para clientId=" + artifact.artifactId(), null);
        }

        String zsyncUrl = artifact.warUrl().replaceAll("\\.war$", ".zsync");
        log.info("Baixando artefato: {} via zsync — {}", artifact.coordinates(), zsyncUrl);
        progress.accept("  URL: " + zsyncUrl);

        Path outDir = Path.of(tempDir);
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new DownloadException("Não foi possível criar diretório temporário: " + outDir, e);
        }

        String host = URI.create(artifact.warUrl()).getHost();

        Path outputFile = outDir.resolve(filename(artifact.warUrl()));

        logZsyncHeaders(zsyncUrl, host);

        Path zsOld = outDir.resolve(filename(artifact.warUrl()) + ".zs-old");
        if (Files.exists(outputFile)) {
            if (isBlockAligned(outputFile)) {
                try {
                    log.trace("Cache local: {} bytes (alinhado) — usando como base delta", sizeOf(outputFile));
                    Files.move(outputFile, zsOld, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    log.debug("Arquivo anterior renomeado para delta: {}", zsOld);
                    progress.accept("  Arquivo anterior encontrado — usando delta (zsync)");
                } catch (IOException e) {
                    log.warn("Não foi possível renomear arquivo anterior: {}", e.getMessage());
                }
            } else {
                log.warn("Cache local não está alinhado ao blocksize ({} bytes) — descartando para download completo: {}",
                        sizeOf(outputFile), outputFile);
                try { Files.deleteIfExists(outputFile); } catch (IOException ignored) {}
                progress.accept("  Nenhuma versão anterior em cache — download completo");
            }
        } else if (installedWarHint.isPresent() && Files.exists(installedWarHint.get())) {
            long hintSize = sizeOf(installedWarHint.get());
            if (hintSize % 4096 == 0) {
                try {
                    log.trace("WAR instalado: {} bytes (alinhado) — copiando como base delta", hintSize);
                    Files.copy(installedWarHint.get(), zsOld, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    log.debug("Usando WAR instalado como base para delta: {}", installedWarHint.get());
                    progress.accept("  Usando WAR instalado como base — usando delta (zsync)");
                } catch (IOException e) {
                    log.warn("Não foi possível copiar WAR instalado para base zsync: {}", e.getMessage());
                    progress.accept("  Nenhuma versão anterior em cache — download completo");
                }
            } else {
                log.warn("WAR instalado não está alinhado ao blocksize ({} bytes) — ignorando hint, download completo", hintSize);
                progress.accept("  Nenhuma versão anterior em cache — download completo");
            }
        } else {
            log.trace("Nenhum arquivo base disponível para delta — download completo");
            progress.accept("  Nenhuma versão anterior em cache — download completo");
        }

        if (Files.exists(zsOld)) {
            log.trace("Base delta (.zs-old): {} bytes", sizeOf(zsOld));
        }

        Zsync.Options options = new Zsync.Options()
                .setOutputFile(outputFile)
                .putCredentials(host, new Credentials(username, password.orElse("")));
        if (Files.exists(zsOld)) {
            options = options.addInputFile(zsOld);
        }

        DownloadProgressObserver observer = new DownloadProgressObserver(progress);
        try {
            progress.accept("  Transferindo...");
            Path result = zsync.zsync(URI.create(zsyncUrl), options, observer);
            observer.reportFinal();
            long sizeAfter = sizeOf(result);
            log.info("Download concluído: {} ({} bytes)", result, sizeAfter);
            log.trace("Tamanho após download (pré-pad): {} bytes, alinhado={}", sizeAfter, sizeAfter % 4096 == 0);
            padToBlockBoundary(result);
            long sizePadded = sizeOf(result);
            if (sizePadded != sizeAfter) {
                log.trace("Tamanho após pad: {} bytes (+{})", sizePadded, sizePadded - sizeAfter);
            }
            writeSha1Cache(result);
            String sizeStr = sizePadded >= 0 ? (sizePadded / (1024 * 1024)) + " MB" : "tamanho desconhecido";
            progress.accept("  Arquivo salvo em: " + result + " (" + sizeStr + ")");
            return artifact.withLocalFile(result);
        } catch (ZsyncChecksumValidationFailedException e) {
            logChecksumFailure(e, artifact.coordinates());
            try { Files.deleteIfExists(outputFile); } catch (IOException ignored) {}
            throw new DownloadException("Checksum SHA-1 inválido após download de " + artifact.coordinates(), e);
        } catch (ZsyncException e) {
            throw new DownloadException("Falha no download zsync de " + artifact.coordinates(), e);
        } catch (IOException e) {
            throw new DownloadException("Falha ao finalizar arquivo após download de " + artifact.coordinates(), e);
        } finally {
            try { Files.deleteIfExists(zsOld); } catch (IOException ignored) {}
        }
    }

    private void logChecksumFailure(ZsyncChecksumValidationFailedException e, String coordinates) {
        if (e.getCause() instanceof ChecksumValidationIOException cv) {
            log.error("Checksum SHA-1 inválido em {}: esperado={} obtido={}", coordinates,
                    cv.getExpectedChecksum(), cv.getActualChecksum());
        } else {
            log.error("Checksum SHA-1 inválido em {}: {}", coordinates, e.getMessage());
        }
    }

    /**
     * Retorna o SHA-1 do WAR em cache no temp dir (gravado após o último download),
     * ou empty se o cache não existe ou o WAR foi removido.
     * Usado pelo AgentOrchestrator para evitar re-download quando o hash remoto já confere
     * com o arquivo baixado mas ainda não deployado.
     */
    public java.util.Optional<String> readCachedSha1(String warUrl) {
        Path sha1File = Path.of(tempDir).resolve(filename(warUrl) + ".sha1");
        Path warFile  = Path.of(tempDir).resolve(filename(warUrl));
        if (!Files.exists(sha1File) || !Files.exists(warFile)) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(Files.readString(sha1File).trim());
        } catch (IOException e) {
            log.warn("Não foi possível ler cache de SHA-1 ({}): {}", sha1File, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private static long sizeOf(Path file) {
        try { return Files.size(file); } catch (IOException e) { return -1; }
    }

    private void logZsyncHeaders(String zsyncUrl, String host) {
        if (!log.isTraceEnabled()) return;
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();
            String creds = java.util.Base64.getEncoder()
                    .encodeToString((username + ":" + password.orElse("")).getBytes());
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(zsyncUrl))
                    .header("Authorization", "Basic " + creds)
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();
            java.net.http.HttpResponse<java.io.InputStream> resp = client.send(req,
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                log.trace(".zsync remoto retornou HTTP {}", resp.statusCode());
                return;
            }
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(resp.body()))) {
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.startsWith("Length:") || line.startsWith("Blocksize:")
                            || line.startsWith("SHA-1:") || line.startsWith("MTime:")) {
                        log.trace(".zsync {}", line);
                    }
                }
            }
        } catch (Exception e) {
            log.trace("Não foi possível ler cabeçalho .zsync: {}", e.getMessage());
        }
    }

    private static boolean isBlockAligned(Path file) {
        try {
            return Files.size(file) % 4096 == 0;
        } catch (IOException e) {
            return false;
        }
    }

    // zsync4j 0.1.0 sobrescreve o outputFile com o WAR baixado sem padding.
    // Reaplica o padding após cada download para que o arquivo sirva como base
    // de delta zsync em atualizações futuras.
    private static void padToBlockBoundary(Path file) throws IOException {
        if (!Files.exists(file)) return;
        long size = Files.size(file);
        long remainder = size % 4096;
        if (remainder == 0) return;
        long padding = 4096 - remainder;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(size);
            raf.write(new byte[(int) padding]);
        }
        log.debug("WAR padded após download: {} → {} bytes (+{})", size, size + padding, padding);
    }

    private void writeSha1Cache(Path warFile) {
        try {
            byte[] data = Files.readAllBytes(warFile);
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
            String sha1 = java.util.HexFormat.of().formatHex(digest.digest(data));
            Files.writeString(warFile.resolveSibling(warFile.getFileName() + ".sha1"), sha1);
            log.debug("SHA-1 do WAR em cache: {}", sha1);
        } catch (Exception e) {
            log.warn("Não foi possível gravar cache de SHA-1 para {}: {}", warFile.getFileName(), e.getMessage());
        }
    }

    private static String filename(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    public static class DownloadException extends Exception {
        public DownloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
