package br.com.wonder.agent.core.download;

import br.com.wonder.agent.model.deploy.Artifact;
import com.salesforce.zsync.Zsync;
import com.salesforce.zsync.ZsyncException;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Baixa artefatos do S3 usando zsync para transferência delta.
 *
 * Se já existe um WAR local (versão anterior), o zsync baixa apenas os
 * blocos diferentes — economizando banda em atualizações incrementais.
 * O arquivo zsync é esperado em {warUrl}.zsync (convenção do S3Uploader).
 */
@Slf4j
@ApplicationScoped
public class ArtifactDownloader {

    @ConfigProperty(name = "download.temp-dir")
    String tempDir;

    private final Zsync zsync;

    ArtifactDownloader() {
        this.zsync = new Zsync();
    }

    ArtifactDownloader(Zsync zsync) {
        this.zsync = zsync;
    }

    public Artifact download(Artifact artifact) throws DownloadException {
        String zsyncUrl = artifact.warUrl() + ".zsync";
        log.info("Baixando artefato: {} via zsync — {}", artifact.coordinates(), zsyncUrl);

        Path outDir = Path.of(tempDir);
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new DownloadException("Não foi possível criar diretório temporário: " + outDir, e);
        }

        String filename = artifact.warUrl().substring(artifact.warUrl().lastIndexOf('/') + 1);
        Path outputFile = outDir.resolve(filename);

        Zsync.Options options = new Zsync.Options()
                .setOutputFile(outputFile);

        // Reusar WAR anterior como input para download delta
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

    public static class DownloadException extends Exception {
        public DownloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
