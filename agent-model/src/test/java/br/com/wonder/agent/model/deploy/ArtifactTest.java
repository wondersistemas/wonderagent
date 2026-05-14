package br.com.wonder.agent.model.deploy;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactTest {

    private static final Artifact BASE = new Artifact(
            "wnfe", "2.5.0",
            "https://wonderpublic.s3.sa-east-1.amazonaws.com/pacotes/5/wnfe-war-2.5.0.war",
            null);

    @Test
    void coordinates_formataArtifactIdVersao() {
        assertThat(BASE.coordinates()).isEqualTo("wnfe:2.5.0");
    }

    @Test
    void withLocalFile_retornaNovoArtifactPreservandoOutrosCampos() {
        Path file = Path.of("/tmp/wnfe-war-2.5.0.war");
        Artifact comArquivo = BASE.withLocalFile(file);

        assertThat(comArquivo.localFile()).isEqualTo(file);
        assertThat(comArquivo.artifactId()).isEqualTo(BASE.artifactId());
        assertThat(comArquivo.version()).isEqualTo(BASE.version());
        assertThat(comArquivo.warUrl()).isEqualTo(BASE.warUrl());
    }

    @Test
    void withLocalFile_naoMutaOriginal() {
        BASE.withLocalFile(Path.of("/tmp/x.war"));
        assertThat(BASE.localFile()).isNull();
    }
}
