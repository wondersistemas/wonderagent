package br.com.wonder.agent.core.provision;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReposiliteMetadataClientTest {

    HttpServer server;
    int port;
    ReposiliteMetadataClient client;

    static final String USERNAME = "reader";
    static final String PASSWORD = "secret";
    static final String REPO_PATH = "wnfe-releases";
    static final String GROUP_PATH = "br/com/wonder";
    static final String ARTIFACT_ID = "wildfly-provisioning";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port = server.getAddress().getPort();
        server.start();
        client = new ReposiliteMetadataClient();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String repoUrl() {
        return "http://localhost:" + port;
    }

    private String call() throws WildflyProvisioner.ProvisioningException {
        return client.resolveLatestVersion(repoUrl(), REPO_PATH, GROUP_PATH, ARTIFACT_ID, USERNAME, PASSWORD);
    }

    @Test
    void resolveLatestVersion_retornaTagLatest() throws Exception {
        server.createContext("/" + REPO_PATH + "/" + GROUP_PATH + "/" + ARTIFACT_ID + "/maven-metadata.xml",
                exchange -> {
                    String xml = """
                            <?xml version="1.0" encoding="UTF-8"?>
                            <metadata>
                              <versioning>
                                <latest>1.2.0-SNAPSHOT</latest>
                                <release>1.1.0</release>
                                <versions>
                                  <version>1.1.0</version>
                                  <version>1.2.0-SNAPSHOT</version>
                                </versions>
                              </versioning>
                            </metadata>
                            """;
                    byte[] body = xml.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.getResponseBody().close();
                });

        assertThat(call()).isEqualTo("1.2.0-SNAPSHOT");
    }

    @Test
    void resolveLatestVersion_semLatest_retornaTagRelease() throws Exception {
        server.createContext("/" + REPO_PATH + "/" + GROUP_PATH + "/" + ARTIFACT_ID + "/maven-metadata.xml",
                exchange -> {
                    String xml = """
                            <?xml version="1.0" encoding="UTF-8"?>
                            <metadata>
                              <versioning>
                                <release>1.1.0</release>
                                <versions>
                                  <version>1.0.0</version>
                                  <version>1.1.0</version>
                                </versions>
                              </versioning>
                            </metadata>
                            """;
                    byte[] body = xml.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.getResponseBody().close();
                });

        assertThat(call()).isEqualTo("1.1.0");
    }

    @Test
    void resolveLatestVersion_semLatestSemRelease_retornaUltimaVersaoEmVersions() throws Exception {
        server.createContext("/" + REPO_PATH + "/" + GROUP_PATH + "/" + ARTIFACT_ID + "/maven-metadata.xml",
                exchange -> {
                    String xml = """
                            <?xml version="1.0" encoding="UTF-8"?>
                            <metadata>
                              <versioning>
                                <versions>
                                  <version>1.0.0</version>
                                  <version>1.1.0</version>
                                </versions>
                              </versioning>
                            </metadata>
                            """;
                    byte[] body = xml.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.getResponseBody().close();
                });

        assertThat(call()).isEqualTo("1.1.0");
    }

    @Test
    void resolveLatestVersion_quandoHttp401_lancaProvisioningException() throws Exception {
        server.createContext("/" + REPO_PATH + "/" + GROUP_PATH + "/" + ARTIFACT_ID + "/maven-metadata.xml",
                exchange -> {
                    exchange.sendResponseHeaders(401, -1);
                    exchange.getResponseBody().close();
                });

        assertThatThrownBy(this::call)
                .isInstanceOf(WildflyProvisioner.ProvisioningException.class)
                .hasMessageContaining("Acesso negado");
    }

    @Test
    void resolveLatestVersion_quandoHttp404_lancaProvisioningException() throws Exception {
        server.createContext("/" + REPO_PATH + "/" + GROUP_PATH + "/" + ARTIFACT_ID + "/maven-metadata.xml",
                exchange -> {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.getResponseBody().close();
                });

        assertThatThrownBy(this::call)
                .isInstanceOf(WildflyProvisioner.ProvisioningException.class)
                .hasMessageContaining("não encontrado");
    }

    @Test
    void resolveLatestVersion_quandoXmlSemVersoes_lancaProvisioningException() throws Exception {
        server.createContext("/" + REPO_PATH + "/" + GROUP_PATH + "/" + ARTIFACT_ID + "/maven-metadata.xml",
                exchange -> {
                    String xml = "<metadata><versioning></versioning></metadata>";
                    byte[] body = xml.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.getResponseBody().close();
                });

        assertThatThrownBy(this::call)
                .isInstanceOf(WildflyProvisioner.ProvisioningException.class)
                .hasMessageContaining("Nenhuma versão");
    }

    // ── resolveSnapshotValue ──────────────────────────────────────────────────

    private static final String SNAPSHOT_VERSION = "1.0.0-SNAPSHOT";
    private static final String SNAPSHOT_METADATA_PATH =
            "/" + REPO_PATH + "/" + GROUP_PATH + "/" + ARTIFACT_ID + "/" + SNAPSHOT_VERSION + "/maven-metadata.xml";

    private String callSnapshot(String classifier, String extension) throws WildflyProvisioner.ProvisioningException {
        return client.resolveSnapshotValue(repoUrl(), REPO_PATH, GROUP_PATH, ARTIFACT_ID,
                SNAPSHOT_VERSION, classifier, extension, USERNAME, PASSWORD);
    }

    @Test
    void resolveSnapshotValue_retornaValueDaSnapshotVersionCorreta() throws Exception {
        server.createContext(SNAPSHOT_METADATA_PATH, exchange -> {
            String xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <metadata>
                      <groupId>br.com.wonder</groupId>
                      <artifactId>wildfly-provisioning</artifactId>
                      <version>1.0.0-SNAPSHOT</version>
                      <versioning>
                        <snapshot><timestamp>20260522.190222</timestamp><buildNumber>7</buildNumber></snapshot>
                        <snapshotVersions>
                          <snapshotVersion>
                            <extension>pom</extension>
                            <value>1.0.0-20260522.190222-7</value>
                          </snapshotVersion>
                          <snapshotVersion>
                            <classifier>dist</classifier>
                            <extension>zip</extension>
                            <value>1.0.0-20260522.190222-7</value>
                          </snapshotVersion>
                          <snapshotVersion>
                            <classifier>dist</classifier>
                            <extension>zip.zsync</extension>
                            <value>1.0.0-20260522.190222-7</value>
                          </snapshotVersion>
                        </snapshotVersions>
                      </versioning>
                    </metadata>
                    """;
            byte[] body = xml.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });

        assertThat(callSnapshot("dist", "zip")).isEqualTo("1.0.0-20260522.190222-7");
        assertThat(callSnapshot("dist", "zip.zsync")).isEqualTo("1.0.0-20260522.190222-7");
    }

    @Test
    void resolveSnapshotValue_versaoNaoSnapshot_retornaVersaoSemConsulta() throws Exception {
        // Não deve chamar o servidor — versão release é retornada diretamente
        String result = client.resolveSnapshotValue(repoUrl(), REPO_PATH, GROUP_PATH, ARTIFACT_ID,
                "1.2.3", "dist", "zip", USERNAME, PASSWORD);
        assertThat(result).isEqualTo("1.2.3");
    }

    @Test
    void resolveSnapshotValue_semSnapshotVersions_fallbackTimestampBuildNumber() throws Exception {
        server.createContext(SNAPSHOT_METADATA_PATH, exchange -> {
            String xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <metadata>
                      <version>1.0.0-SNAPSHOT</version>
                      <versioning>
                        <snapshot><timestamp>20260522.190222</timestamp><buildNumber>7</buildNumber></snapshot>
                      </versioning>
                    </metadata>
                    """;
            byte[] body = xml.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });

        assertThat(callSnapshot("dist", "zip")).isEqualTo("1.0.0-20260522.190222-7");
    }
}
