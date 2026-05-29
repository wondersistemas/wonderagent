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

        Path zsOld = outDir.resolve(filename(artifact.warUrl()) + ".zs-old");
        if (Files.exists(outputFile)) {
            try {
                Files.move(outputFile, zsOld, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.debug("Arquivo anterior renomeado para delta: {}", zsOld);
                progress.accept("  Arquivo anterior encontrado — usando delta (zsync)");
            } catch (IOException e) {
                log.warn("Não foi possível renomear arquivo anterior: {}", e.getMessage());
            }
        } else if (installedWarHint.isPresent() && Files.exists(installedWarHint.get())) {
            try {
                Files.copy(installedWarHint.get(), zsOld, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.debug("Usando WAR instalado como base para delta: {}", installedWarHint.get());
                progress.accept("  Usando WAR instalado como base — usando delta (zsync)");
            } catch (IOException e) {
                log.warn("Não foi possível copiar WAR instalado para base zsync: {}", e.getMessage());
                progress.accept("  Nenhuma versão anterior em cache — download completo");
            }
        } else {
            progress.accept("  Nenhuma versão anterior em cache — download completo");
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
            log.info("Download concluído: {}", result);
            writeSha1Cache(result);
            try {
                long sizeMb = Files.size(result) / (1024 * 1024);
                progress.accept("  Arquivo salvo em: " + result + " (" + sizeMb + " MB)");
            } catch (IOException ignored) {
                progress.accept("  Arquivo salvo em: " + result);
            }
            return artifact.withLocalFile(result);
        } catch (ZsyncChecksumValidationFailedException e) {
            logChecksumFailure(e, artifact.coordinates());
            try { Files.deleteIfExists(outputFile); } catch (IOException ignored) {}
            throw new DownloadException("Checksum SHA-1 inválido após download de " + artifact.coordinates(), e);
        } catch (ZsyncException e) {
            throw new DownloadException("Falha no download zsync de " + artifact.coordinates(), e);
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
