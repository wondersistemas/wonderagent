package br.com.wonder.agent.core.provision;

import com.salesforce.zsync.Zsync;
import com.salesforce.zsync.ZsyncException;
import com.salesforce.zsync.http.Credentials;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WildflyProvisionerTest {

    @TempDir Path tempDir;
    @TempDir Path wildflyHome;

    Zsync zsync;
    ReposiliteMetadataClient metadataClient;
    WildflyProvisioner provisioner;

    static final String REPO_URL = "http://192.168.0.86:8082";
    static final String REPO_PATH = "wnfe-releases";
    static final String VERSION = "1.0.0-SNAPSHOT";

    @BeforeEach
    void setUp() throws Exception {
        zsync = mock(Zsync.class);
        metadataClient = mock(ReposiliteMetadataClient.class);
        provisioner = new WildflyProvisioner(zsync, metadataClient);
        setField(provisioner, "repositoryUrl", REPO_URL);
        setField(provisioner, "repoPath", REPO_PATH);
        setField(provisioner, "username", "reader");
        setField(provisioner, "password", Optional.of("secret"));
        setField(provisioner, "tempDir", tempDir.toString());
        setField(provisioner, "wildflyHome", wildflyHome.toString());
        setField(provisioner, "fixedVersion", Optional.empty());
        // Por padrão, resolveSnapshotValue retorna a versão sem alteração (simula release ou SNAPSHOT sem timestamp)
        when(metadataClient.resolveSnapshotValue(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(4)); // retorna o argumento 'version'
    }

    // ── buildZipUrl ──────────────────────────────────────────────────────────

    @Test
    void buildZipUrl_montaUrlCorretamente() {
        String url = provisioner.buildZipUrl(VERSION);
        assertThat(url).isEqualTo(
                REPO_URL + "/" + REPO_PATH
                        + "/br/com/wonder/wildfly-provisioning/"
                        + VERSION + "/wildfly-provisioning-" + VERSION + "-dist.zip");
    }

    // ── ensureVersion — versão já instalada ──────────────────────────────────

    @Test
    void ensureVersion_quandoJaInstalado_retornaFalse() throws Exception {
        Files.writeString(wildflyHome.resolve(".wildfly-version"), VERSION);

        boolean result = provisioner.ensureVersion(VERSION);

        assertThat(result).isFalse();
        verifyNoInteractions(zsync);
    }

    // ── ensureVersion — versão diferente ─────────────────────────────────────

    @Test
    void ensureVersion_quandoVersaoDiferente_baixaEExtrai() throws Exception {
        Files.writeString(wildflyHome.resolve(".wildfly-version"), "0.9.0");

        Path fakeZip = criarZipFake(tempDir, VERSION);
        when(zsync.zsync(any(), any(), any())).thenReturn(fakeZip);

        boolean result = provisioner.ensureVersion(VERSION);

        assertThat(result).isTrue();
        verify(zsync).zsync(any(), any(), any());
        assertThat(wildflyHome.resolve(".wildfly-version")).hasContent(VERSION);
    }

    @Test
    void ensureVersion_quandoSemVersaoInstalada_baixaEExtrai() throws Exception {
        // nenhum .wildfly-version presente
        Path fakeZip = criarZipFake(tempDir, VERSION);
        when(zsync.zsync(any(), any(), any())).thenReturn(fakeZip);

        boolean result = provisioner.ensureVersion(VERSION);

        assertThat(result).isTrue();
        assertThat(wildflyHome.resolve(".wildfly-version")).hasContent(VERSION);
    }

    // ── versão fixada via fixedVersion ────────────────────────────────────────

    @Test
    void ensureVersion_fixedVersionPrevaleceOverDesired() throws Exception {
        setField(provisioner, "fixedVersion", Optional.of("2.0.0"));
        Files.writeString(wildflyHome.resolve(".wildfly-version"), "2.0.0");

        // desired diz 1.0.0, mas fixedVersion diz 2.0.0 (já instalado)
        boolean result = provisioner.ensureVersion("1.0.0");

        assertThat(result).isFalse();
        verifyNoInteractions(zsync);
    }

    // ── zsync URL e credenciais ───────────────────────────────────────────────

    @Test
    void ensureVersion_passaZsyncUrlCorreta() throws Exception {
        Path fakeZip = criarZipFake(tempDir, VERSION);
        when(zsync.zsync(any(), any(), any())).thenReturn(fakeZip);

        provisioner.ensureVersion(VERSION);

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(zsync).zsync(uriCaptor.capture(), any(), any());
        assertThat(uriCaptor.getValue().toString()).endsWith("-dist.zip.zsync");
    }

    @Test
    void ensureVersion_passaCredenciaisParaHost() throws Exception {
        Path fakeZip = criarZipFake(tempDir, VERSION);
        when(zsync.zsync(any(), any(), any())).thenReturn(fakeZip);

        provisioner.ensureVersion(VERSION);

        ArgumentCaptor<Zsync.Options> optCaptor = ArgumentCaptor.forClass(Zsync.Options.class);
        verify(zsync).zsync(any(), optCaptor.capture(), any());
        Credentials creds = optCaptor.getValue().getCredentials().get("192.168.0.86");
        assertThat(creds).isNotNull();
        String decoded = new String(java.util.Base64.getDecoder()
                .decode(creds.basic().replace("Basic ", "")));
        assertThat(decoded).startsWith("reader:");
    }

    @Test
    void ensureVersion_quandoZipExistente_adicionaComoInputFile() throws Exception {
        String filename = "wildfly-provisioning-" + VERSION + "-dist.zip";
        Path existing = tempDir.resolve(filename);
        criarZipFakeEm(existing, VERSION);
        when(zsync.zsync(any(), any(), any())).thenReturn(existing);

        provisioner.ensureVersion(VERSION);

        ArgumentCaptor<Zsync.Options> optCaptor = ArgumentCaptor.forClass(Zsync.Options.class);
        verify(zsync).zsync(any(), optCaptor.capture(), any());
        assertThat(optCaptor.getValue().getInputFiles()).contains(existing);
    }

    // ── falha de download (zsync + HTTP) ─────────────────────────────────────

    @Test
    void ensureVersion_quandoZsyncEHttpFalham_lancaProvisioningException() throws Exception {
        // zsync falha → tenta HTTP → servidor retorna 404
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        int port = httpServer.getAddress().getPort();
        httpServer.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.getResponseBody().close();
        });
        httpServer.start();

        try {
            setField(provisioner, "repositoryUrl", "http://127.0.0.1:" + port);
            when(zsync.zsync(any(), any(), any())).thenThrow(new ZsyncException("servidor inacessível"));

            assertThatThrownBy(() -> provisioner.ensureVersion(VERSION))
                    .isInstanceOf(WildflyProvisioner.ProvisioningException.class);
        } finally {
            httpServer.stop(0);
        }
    }

    // ── fallback HTTP direto quando .zsync não existe ─────────────────────────

    @Test
    void ensureVersion_quandoZsyncAusente_usaFallbackHttp() throws Exception {
        // Sobe servidor HTTP local que serve o ZIP diretamente
        Path fakeZip = criarZipFake(tempDir, VERSION);
        byte[] zipBytes = Files.readAllBytes(fakeZip);

        HttpServer httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        int port = httpServer.getAddress().getPort();
        String zipPath = "/wnfe-releases/br/com/wonder/wildfly-provisioning/"
                + VERSION + "/wildfly-provisioning-" + VERSION + "-dist.zip";
        httpServer.createContext(zipPath, exchange -> {
            exchange.sendResponseHeaders(200, zipBytes.length);
            exchange.getResponseBody().write(zipBytes);
            exchange.getResponseBody().close();
        });
        httpServer.start();

        try {
            setField(provisioner, "repositoryUrl", "http://127.0.0.1:" + port);
            setField(provisioner, "password", Optional.empty());
            when(zsync.zsync(any(), any(), any()))
                    .thenThrow(new ZsyncException("HttpError: Not Found"));

            boolean result = provisioner.ensureVersion(VERSION);

            assertThat(result).isTrue();
            assertThat(wildflyHome.resolve(".wildfly-version")).hasContent(VERSION);
        } finally {
            httpServer.stop(0);
        }
    }

    // ── extração do ZIP ───────────────────────────────────────────────────────

    @Test
    void ensureVersion_extraiConteudoDoZip() throws Exception {
        Path fakeZip = tempDir.resolve("wildfly-provisioning-" + VERSION + "-dist.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(fakeZip))) {
            ZipEntry dir = new ZipEntry("wildfly/bin/");
            zos.putNextEntry(dir);
            zos.closeEntry();
            ZipEntry file = new ZipEntry("wildfly/bin/standalone.sh");
            zos.putNextEntry(file);
            zos.write("#!/bin/sh\n".getBytes());
            zos.closeEntry();
        }
        when(zsync.zsync(any(), any(), any())).thenReturn(fakeZip);

        provisioner.ensureVersion(VERSION);

        assertThat(wildflyHome.resolve("bin/standalone.sh")).exists();
    }

    // ── permissões Unix na extração ───────────────────────────────────────────

    // ZipOutputStream não grava external attributes no Central Directory.
    // O único jeito de ter um ZIP com permissões Unix reais é usar o 'zip' nativo.
    @Test
    @EnabledOnOs(OS.LINUX)
    void extractZip_preservaPermissoesUnixDoZip() throws Exception {
        // Cria estrutura de arquivos e empacota com zip nativo (que grava external attributes)
        Path staging = tempDir.resolve("staging/wildfly/bin");
        Files.createDirectories(staging);
        Path script = staging.resolve("standalone.sh");
        Files.writeString(script, "#!/bin/sh\n");
        Files.setPosixFilePermissions(script, java.util.Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
        Path readme = staging.getParent().resolve("README.txt");
        Files.writeString(readme, "readme");

        Path zipFile = tempDir.resolve("test.zip");
        int rc = new ProcessBuilder("zip", "-r", zipFile.toAbsolutePath().toString(), "wildfly")
                .directory(tempDir.resolve("staging").toFile())
                .start().waitFor();
        org.junit.jupiter.api.Assumptions.assumeTrue(rc == 0, "zip nativo não disponível");

        provisioner.extractZip(zipFile, wildflyHome);

        Path sh = wildflyHome.resolve("bin/standalone.sh");
        assertThat(sh).exists();
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(sh);
        assertThat(perms).contains(
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_EXECUTE);

        assertThat(wildflyHome.resolve("README.txt")).exists();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void extractZip_semExternalAttributes_naoLancaExcecao() throws Exception {
        // ZIP criado com ZipOutputStream não tem external attributes — deve extrair sem erro
        Path zipFile = tempDir.resolve("test-noperms.zip");
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(zipFile))) {
            java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry("wildfly/bin/script.sh");
            zos.putNextEntry(entry);
            zos.write("#!/bin/sh\n".getBytes());
            zos.closeEntry();
        }

        provisioner.extractZip(zipFile, wildflyHome);
        assertThat(wildflyHome.resolve("bin/script.sh")).exists();
    }

    // ── ensureLatestVersion ───────────────────────────────────────────────────

    @Test
    void ensureLatestVersion_resolveVersaoDoReposiliteEInstala() throws Exception {
        when(metadataClient.resolveLatestVersion(any(), any(), any(), any(), any(), any()))
                .thenReturn(VERSION);
        Path fakeZip = criarZipFake(tempDir, VERSION);
        when(zsync.zsync(any(), any(), any())).thenReturn(fakeZip);

        boolean result = provisioner.ensureLatestVersion(msg -> {});

        assertThat(result).isTrue();
        verify(metadataClient).resolveLatestVersion(any(), any(), any(), any(), any(), any());
        assertThat(wildflyHome.resolve(".wildfly-version")).hasContent(VERSION);
    }

    @Test
    void ensureLatestVersion_quandoFixedVersionDefinida_naoConsultaReposilite() throws Exception {
        setField(provisioner, "fixedVersion", Optional.of(VERSION));
        Files.writeString(wildflyHome.resolve(".wildfly-version"), VERSION);

        boolean result = provisioner.ensureLatestVersion(msg -> {});

        assertThat(result).isFalse();
        verifyNoInteractions(metadataClient);
    }

    // ── downloadOnly ──────────────────────────────────────────────────────────

    @Test
    void downloadOnly_comVersao_baixaSemExtrair() throws Exception {
        Path fakeZip = criarZipFake(tempDir, VERSION);
        when(zsync.zsync(any(), any(), any())).thenReturn(fakeZip);

        Path result = provisioner.downloadOnly(VERSION, msg -> {});

        assertThat(result).exists();
        // não deve ter extraído nada para wildflyHome
        assertThat(wildflyHome.resolve(".wildfly-version")).doesNotExist();
    }

    @Test
    void downloadOnly_semVersao_resolveLatestEBaixa() throws Exception {
        when(metadataClient.resolveLatestVersion(any(), any(), any(), any(), any(), any()))
                .thenReturn(VERSION);
        Path fakeZip = criarZipFake(tempDir, VERSION);
        when(zsync.zsync(any(), any(), any())).thenReturn(fakeZip);

        provisioner.downloadOnly(msg -> {});

        verify(metadataClient).resolveLatestVersion(any(), any(), any(), any(), any(), any());
        verify(zsync).zsync(any(), any(), any());
    }

    // ── applyFromCache ────────────────────────────────────────────────────────

    @Test
    void applyFromCache_comZipEmCache_extraiEEscreveVersionMarker() throws Exception {
        Path zipFile = tempDir.resolve("wildfly-provisioning-" + VERSION + "-dist.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            ZipEntry entry = new ZipEntry("wildfly/bin/standalone.sh");
            zos.putNextEntry(entry);
            zos.write("#!/bin/sh\n".getBytes());
            zos.closeEntry();
        }

        boolean result = provisioner.applyFromCache(VERSION, msg -> {});

        assertThat(result).isTrue();
        assertThat(wildflyHome.resolve("bin/standalone.sh")).exists();
        assertThat(wildflyHome.resolve(".wildfly-version")).hasContent(VERSION);
        // não deve ter chamado zsync
        verifyNoInteractions(zsync);
    }

    @Test
    void applyFromCache_semZipEmCache_lancaProvisioningException() {
        // tempDir vazio, sem ZIP
        assertThatThrownBy(() -> provisioner.applyFromCache(VERSION, msg -> {}))
                .isInstanceOf(WildflyProvisioner.ProvisioningException.class)
                .hasMessageContaining("download-server");
    }

    @Test
    void applyFromCache_quandoJaInstalado_retornaFalse() throws Exception {
        Files.writeString(wildflyHome.resolve(".wildfly-version"), VERSION);
        Path zipFile = tempDir.resolve("wildfly-provisioning-" + VERSION + "-dist.zip");
        criarZipFakeEm(zipFile, VERSION);

        boolean result = provisioner.applyFromCache(VERSION, msg -> {});

        assertThat(result).isFalse();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Path criarZipFake(Path dir, String version) throws Exception {
        Path zip = dir.resolve("wildfly-provisioning-" + version + "-dist.zip");
        criarZipFakeEm(zip, version);
        return zip;
    }

    private void criarZipFakeEm(Path zip, String version) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            ZipEntry entry = new ZipEntry("wildfly/README.txt");
            zos.putNextEntry(entry);
            zos.write(("WildFly " + version).getBytes());
            zos.closeEntry();
        }
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

}
