package br.com.wonder.agent.model.deploy;

import java.nio.file.Path;

/**
 * Artefato a ser deployado. Carrega a URL pública do S3 e, após download,
 * o arquivo local temporário.
 */
public record Artifact(
    String artifactId,
    String version,
    String warUrl,          // URL completa do WAR no S3
    Path   localFile        // null antes do download
) {
    public String coordinates() {
        return artifactId + ":" + version;
    }

    public Artifact withLocalFile(Path file) {
        return new Artifact(artifactId, version, warUrl, file);
    }
}
