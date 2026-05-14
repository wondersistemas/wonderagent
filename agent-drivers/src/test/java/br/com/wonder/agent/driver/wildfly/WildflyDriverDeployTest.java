package br.com.wonder.agent.driver.wildfly;

import br.com.wonder.agent.model.deploy.Artifact;
import br.com.wonder.agent.model.deploy.DeployResult;
import br.com.wonder.agent.model.state.RuntimeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WildflyDriverDeployTest {

    @TempDir Path tempDir;
    @TempDir Path artifactDir;

    WildflyDriver driver;

    @BeforeEach
    void setUp() throws Exception {
        driver = new WildflyDriver();
        setField(driver, "wildflyHome", tempDir.toString());
        setField(driver, "managementPort", 9990);
        setField(driver, "deployPath", tempDir.toString());
        setField(driver, "artifactName", "wnfe.war");
        setField(driver, "startTimeoutSeconds", 1);
        setField(driver, "stopTimeoutSeconds", 1);
        setField(driver, "healthCheckUrl", "http://localhost:8080/probusweb/health");
    }

    Artifact artifact(Path localFile) {
        return new Artifact("wnfe", "2.5.0",
                "https://wonderpublic.s3.sa-east-1.amazonaws.com/pacotes/5/wnfe-war-2.5.0.war",
                localFile);
    }

    @Test
    void deploy_copiaArtefatoEEscreveMarkerDeVersao() throws IOException {
        Path warSource = artifactDir.resolve("wnfe-war-2.5.0.war");
        Files.writeString(warSource, "war-content");

        DeployResult result = driver.deploy(artifact(warSource));

        assertThat(result.success()).isTrue();
        assertThat(result.version()).isEqualTo("2.5.0");
        assertThat(Files.exists(tempDir.resolve("wnfe.war"))).isTrue();
        assertThat(Files.readString(tempDir.resolve("wnfe.war"))).isEqualTo("war-content");
    }

    @Test
    void deploy_escreveMarkerDeVersaoLegívelPorGetInstalledVersion() throws IOException {
        Path warSource = artifactDir.resolve("wnfe-war-2.5.0.war");
        Files.writeString(warSource, "war-content");

        driver.deploy(artifact(warSource));

        assertThat(driver.getInstalledVersion()).isEqualTo("2.5.0");
    }

    @Test
    void deploy_quandoArquivoFonteNaoExiste_retornaFalha() {
        DeployResult result = driver.deploy(artifact(Path.of("/nonexistent/wnfe-war-2.5.0.war")));

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).contains("Falha ao copiar");
    }

    @Test
    void getInstalledVersion_quandoMarkerNaoExiste_retornaNull() {
        assertThat(driver.getInstalledVersion()).isNull();
    }

    @Test
    void getInstalledVersion_quandoMarkerExiste_retornaVersao() throws IOException {
        Files.writeString(tempDir.resolve(".wonder-version"), "  2.4.1  \n");

        assertThat(driver.getInstalledVersion()).isEqualTo("2.4.1");
    }

    @Test
    void getRuntimeType_retornaWildfly() {
        assertThat(driver.getRuntimeType()).isEqualTo("wildfly");
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
