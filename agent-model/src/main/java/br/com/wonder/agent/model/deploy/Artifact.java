package br.com.wonder.agent.model.deploy;

import java.nio.file.Path;

/**
 * Artefato Maven a ser deployado. Identifica o artefato no Nexus
 * e, após download, o arquivo local temporário.
 */
public record Artifact(
    String groupId,
    String artifactId,
    String version,
    String extension,       // "war", "jar"
    String nexusBaseUrl,
    String repository,
    Path   localFile        // null antes do download
) {
    public String coordinates() {
        return groupId + ":" + artifactId + ":" + version;
    }

    public Artifact withLocalFile(Path file) {
        return new Artifact(groupId, artifactId, version, extension, nexusBaseUrl, repository, file);
    }
}
