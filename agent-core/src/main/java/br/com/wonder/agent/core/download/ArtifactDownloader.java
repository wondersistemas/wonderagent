package br.com.wonder.agent.core.download;

import br.com.wonder.agent.model.deploy.Artifact;
import com.salesforce.zsync.Zsync;
import com.salesforce.zsync.ZsyncChecksumValidationFailedException;
import com.salesforce.zsync.ZsyncException;
import com.salesforce.zsync.http.Credentials;
import com.salesforce.zsync.internal.ChecksumValidationIOException;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

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
        this.zsync = new Zsync();
    }

    ArtifactDownloader(Zsync zsync) {
        this.zsync = zsync;
    }

    public Artifact download(Artifact artifact) throws DownloadException {
        if (artifact.warUrl() == null || artifact.warUrl().isBlank()) {
            throw new DownloadException(
                "warUrl vazio no desired-state — verifique a configuração do servidor central para clientId=" + artifact.artifactId(), null);
        }

        String zsyncUrl = artifact.warUrl().replace(".war", ".zsync");
        log.info("Baixando artefato: {} via zsync — {}", artifact.coordinates(), zsyncUrl);

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
            } catch (IOException e) {
                log.warn("Não foi possível renomear arquivo anterior: {}", e.getMessage());
            }
        }

        Zsync.Options options = new Zsync.Options()
                .setOutputFile(outputFile)
                .putCredentials(host, new Credentials(username, password.orElse("")));
        if (Files.exists(zsOld)) {
            options = options.addInputFile(zsOld);
        }

        try {
            Path result = zsync.zsync(URI.create(zsyncUrl), options);
            log.info("Download concluído: {}", result);
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

    private static String filename(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    public static class DownloadException extends Exception {
        public DownloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
