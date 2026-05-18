package br.com.wonder.agent.core.download;

import br.com.wonder.agent.model.deploy.Artifact;
import com.salesforce.zsync.Zsync;
import com.salesforce.zsync.ZsyncException;
import com.salesforce.zsync.http.Credentials;
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
        String zsyncUrl = artifact.warUrl().replace(".war", ".zsync");
        log.info("Baixando artefato: {} via zsync — {}", artifact.coordinates(), zsyncUrl);

        Path outDir = Path.of(tempDir);
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new DownloadException("Não foi possível criar diretório temporário: " + outDir, e);
        }

        String host = URI.create(artifact.warUrl()).getHost();

        Zsync.Options options = new Zsync.Options()
                .setOutputFile(outDir.resolve(filename(artifact.warUrl())))
                .putCredentials(host, new Credentials(username, password.orElse("")));

        Path outputFile = outDir.resolve(filename(artifact.warUrl()));
        if (Files.exists(outputFile)) {
            log.debug("Arquivo existente encontrado, usando como base para delta: {}", outputFile);
            options = options.addInputFile(outputFile);
        }

        try {
            Path result = zsync.zsync(URI.create(zsyncUrl), options);
            log.info("Download concluído: {}", result);
            return artifact.withLocalFile(result);
        } catch (ZsyncException e) {
            throw new DownloadException("Falha no download zsync de " + artifact.coordinates(), e);
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
