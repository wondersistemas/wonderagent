package br.com.wonder.agent.driver.wildfly;

import br.com.wonder.agent.model.deploy.Artifact;
import br.com.wonder.agent.model.deploy.DeployResult;
import br.com.wonder.agent.model.state.RuntimeState;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WildflyDriverDeployTest {

    @TempDir Path tempDir;
    @TempDir Path artifactDir;

    HttpServer managementServer;
    int managementPort;
    WildflyDriver driver;

    static class TestableWildflyDriver extends WildflyDriver {
        @Override
        OptionalLong findWildflyPid() { return OptionalLong.of(99999L); }
    }

    @BeforeEach
    void setUp() throws Exception {
        managementServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        managementPort = managementServer.getAddress().getPort();
        managementServer.start();

        driver = new WildflyDriver();
        setField(driver, "wildflyHome", tempDir.toString());
        setField(driver, "managementPort", managementPort);
        setField(driver, "deployPath", tempDir.toString());
        setField(driver, "artifactName", "wnfe.war");
        setField(driver, "startTimeoutSeconds", 1);
        setField(driver, "stopTimeoutSeconds", 1);
        setField(driver, "healthCheckUrl", "http://localhost:8080/probusweb/health");
        setField(driver, "healthCheckTimeoutSeconds", 5);
        setField(driver, "healthCheckRetries", 1);
    }

    @AfterEach
    void tearDown() {
        managementServer.stop(0);
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

    @Test
    void deploy_escreveMarkerDeChecksumLegívelPorGetInstalledChecksum() throws Exception {
        Path warSource = artifactDir.resolve("wnfe-war-2.5.0.war");
        Files.writeString(warSource, "war-content");

        driver.deploy(artifact(warSource));

        String expectedSha1 = sha1Hex("war-content".getBytes());
        assertThat(driver.getInstalledChecksum()).contains(expectedSha1);
    }

    @Test
    void getInstalledChecksum_quandoMarkerNaoExiste_retornaEmpty() {
        assertThat(driver.getInstalledChecksum()).isEmpty();
    }

    @Test
    void getInstalledChecksum_quandoMarkerExiste_retornaChecksum() throws IOException {
        Files.writeString(tempDir.resolve(".wonder-sha1"), "  abc123  \n");

        assertThat(driver.getInstalledChecksum()).contains("abc123");
    }

    @Test
    void deploy_checksumCobresConteudoRealDoWar() throws Exception {
        Path warA = artifactDir.resolve("war-a.war");
        Path warB = artifactDir.resolve("war-b.war");
        Files.writeString(warA, "conteudo-a");
        Files.writeString(warB, "conteudo-b");

        driver.deploy(new br.com.wonder.agent.model.deploy.Artifact("wnfe", "2.5.0", "http://x/a.war", warA));
        String checksumA = driver.getInstalledChecksum().orElseThrow();

        driver.deploy(new br.com.wonder.agent.model.deploy.Artifact("wnfe", "2.5.0", "http://x/b.war", warB));
        String checksumB = driver.getInstalledChecksum().orElseThrow();

        assertThat(checksumA).isNotEqualTo(checksumB);
        assertThat(checksumA).isEqualTo(sha1Hex("conteudo-a".getBytes()));
        assertThat(checksumB).isEqualTo(sha1Hex("conteudo-b".getBytes()));
    }

    private static String sha1Hex(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
    }

    // ── waitForDeploymentReady ────────────────────────────────────────────────

    /** Registra handler para /management/deployment/wnfe.war respondendo por atributo. */
    private void registerDeploymentHandler(String statusValue, long enabledTime) {
        managementServer.createContext("/management/deployment/wnfe.war", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            byte[] body;
            if (query != null && query.contains("name=status")) {
                body = ("\"" + statusValue + "\"").getBytes();
            } else {
                body = String.valueOf(enabledTime).getBytes();
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
    }

    @Test
    void waitForDeploymentReady_quandoScannerRetornaOkComEnabledTimeDepoisDoDeployedAt_retornaTrue() throws Exception {
        long enabledTime = System.currentTimeMillis() + 500;
        registerDeploymentHandler("OK", enabledTime);

        Instant deployedAt = Instant.ofEpochMilli(enabledTime - 1000);
        boolean ready = driver.waitForDeploymentReady(deployedAt, 5);

        assertThat(ready).isTrue();
    }

    @Test
    void waitForDeploymentReady_quandoEnabledTimeAnteDoDeployedAt_ignoraEAguarda() throws Exception {
        long deployedAtMs = System.currentTimeMillis();
        long oldTime = deployedAtMs - 5000;
        long newTime = deployedAtMs + 100;

        AtomicInteger statusCalls = new AtomicInteger(0);
        managementServer.createContext("/management/deployment/wnfe.war", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            byte[] body;
            if (query != null && query.contains("name=status")) {
                body = "\"OK\"".getBytes();
            } else {
                // enabled-time: primeira chamada retorna oldTime, demais retornam newTime
                long time = statusCalls.incrementAndGet() == 1 ? oldTime : newTime;
                body = String.valueOf(time).getBytes();
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });

        boolean ready = driver.waitForDeploymentReady(Instant.ofEpochMilli(deployedAtMs), 10);

        assertThat(ready).isTrue();
        assertThat(statusCalls.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void waitForDeploymentReady_quandoScannerRetornaFailed_retornaFalseImediato() throws Exception {
        long enabledTime = System.currentTimeMillis() + 500;
        AtomicInteger statusCalls = new AtomicInteger(0);
        managementServer.createContext("/management/deployment/wnfe.war", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            byte[] body;
            if (query != null && query.contains("name=status")) {
                statusCalls.incrementAndGet();
                body = "\"FAILED\"".getBytes();
            } else {
                body = String.valueOf(enabledTime).getBytes();
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });

        Instant deployedAt = Instant.ofEpochMilli(enabledTime - 1000);
        boolean ready = driver.waitForDeploymentReady(deployedAt, 10);

        assertThat(ready).isFalse();
        assertThat(statusCalls.get()).isEqualTo(1);
    }

    @Test
    void waitForDeploymentReady_quandoTimeout_retornaFalse() throws Exception {
        managementServer.createContext("/management/deployment/wnfe.war", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.getResponseBody().close();
        });

        boolean ready = driver.waitForDeploymentReady(Instant.now(), 1);

        assertThat(ready).isFalse();
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
