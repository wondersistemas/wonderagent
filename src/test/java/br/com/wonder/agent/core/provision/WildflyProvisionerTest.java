package br.com.wonder.agent.core.provision;

import br.com.wonder.agent.core.download.ZsyncChecksumReader;
import br.com.wonder.agent.core.download.ZsyncDownloadHelper;
import br.com.wonder.agent.model.driver.RuntimeDriver;
import br.com.wonder.agent.model.state.RuntimeState;
import br.com.wonder.agent.model.util.FileChecksum;
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
    ZsyncDownloadHelper helper;
    ReposiliteMetadataClient metadataClient;
    ZsyncChecksumReader checksumReader;
    RuntimeDriver runtimeDriver;
    WildflyProvisioner provisioner;

    static final String REPO_URL = "http://192.168.0.86:8082";
    static final String REPO_PATH = "wnfe-releases";
    static final String VERSION = "1.0.0-SNAPSHOT";
    static final String REMOTE_SHA1 = "aabbccdd11223344aabbccdd11223344aabbccdd";
    static final String OTHER_SHA1  = "1111111111111111111111111111111111111111";

    @BeforeEach
    void setUp() throws Exception {
        zsync = mock(Zsync.class);
        metadataClient = mock(ReposiliteMetadataClient.class);
        checksumReader = mock(ZsyncChecksumReader.class);
        runtimeDriver = mock(RuntimeDriver.class);
        helper = new ZsyncDownloadHelper(zsync, "reader", Optional.of("secret"));
        setField(helper, "username", "reader");
        setField(helper, "password", Optional.of("secret"));
        provisioner = new WildflyProvisioner(helper, metadataClient, checksumReader, runtimeDriver);
        // Por padrão, runtime está parado — nenhum stop necessário nos testes
        when(runtimeDriver.detectState()).thenReturn(RuntimeState.STOPPED);
        setField(provisioner, "repositoryUrl", REPO_URL);
        setField(provisioner, "repoPath", REPO_PATH);
        setField(provisioner, "username", "reader");
        setField(provisioner, "password", Optional.of("secret"));
        setField(provisioner, "tempDir", tempDir.toString());
        setField(provisioner, "wildflyHome", wildflyHome.toString());
        setField(provisioner, "managementPort", 0);     // porta fechada por padrão — sem stop necessário
        setField(provisioner, "stopTimeoutSeconds", 5); // timeout curto para testes
        setField(provisioner, "fixedVersion", Optional.empty());
        setField(provisioner, "dbUrl", Optional.empty());
        setField(provisioner, "dbUsername", Optional.empty());
        setField(provisioner, "dbPassword", Optional.empty());
        // Por padrão, resolveSnapshotValue retorna a versão sem alteração
        when(metadataClient.resolveSnapshotValue(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(4));
        // Por padrão, SHA-1 remoto difere do local → forçará download nos testes que não sobrescrevem
        when(checksumReader.readSha1(any())).thenReturn(Optional.of(REMOTE_SHA1));
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

    // ── ensureVersion — já na build atual (SHA-1 confere) ────────────────────

    @Test
    void ensureVersion_quandoSha1Confere_retornaFalse() throws Exception {
        Files.writeString(wildflyHome.resolve(".wildfly-sha1"), REMOTE_SHA1);
        // checksumReader já retorna REMOTE_SHA1 por padrão no setUp

        boolean result = provisioner.ensureVersion(VERSION);

        assertThat(result).isFalse();
        verifyNoInteractions(zsync);
    }

    @Test
    void ensureVersion_quandoSha1RemotoIndisponivelEVersaoIgual_retornaFalse() throws Exception {
        when(checksumReader.readSha1(any())).thenReturn(Optional.empty());
        Files.writeString(wildflyHome.resolve(".wildfly-version"), VERSION);

        boolean result = provisioner.ensureVersion(VERSION);

        assertThat(result).isFalse();
        verifyNoInteractions(zsync);
    }

    // ── ensureVersion — nova build disponível ────────────────────────────────

    @Test
    void ensureVersion_quandoSha1Diverge_baixaEExtrai() throws Exception {
        Files.writeString(wildflyHome.resolve(".wildfly-sha1"), OTHER_SHA1);
        // checksumReader retorna REMOTE_SHA1 != OTHER_SHA1
        mockZsyncComZip(VERSION);

        boolean result = provisioner.ensureVersion(VERSION);

        assertThat(result).isTrue();
        verify(zsync).zsync(any(), any(), any());
        assertThat(wildflyHome.resolve(".wildfly-version")).hasContent(VERSION);
        assertThat(wildflyHome.resolve(".wildfly-sha1")).hasContent(REMOTE_SHA1);
    }

    @Test
    void ensureVersion_quandoSemSha1Local_baixaEExtrai() throws Exception {
        // nenhum .wildfly-sha1 presente
        mockZsyncComZip(VERSION);

        boolean result = provisioner.ensureVersion(VERSION);

        assertThat(result).isTrue();
        assertThat(wildflyHome.resolve(".wildfly-version")).hasContent(VERSION);
        assertThat(wildflyHome.resolve(".wildfly-sha1")).hasContent(REMOTE_SHA1);
    }

    @Test
    void ensureVersion_quandoRuntimeRodando_paraAntesDeExtrair() throws Exception {
        // stop() fecha a porta — provisioner detecta porta aberta, chama stop, depois extrai
        try (java.net.ServerSocket srv = new java.net.ServerSocket(0)) {
            srv.setReuseAddress(true);
            int port = srv.getLocalPort();
            setField(provisioner, "managementPort", port);
            when(runtimeDriver.stop()).thenAnswer(inv -> { srv.close(); return true; });
            mockZsyncComZip(VERSION);

            boolean result = provisioner.ensureVersion(VERSION);

            assertThat(result).isTrue();
            verify(runtimeDriver).stop();
            assertThat(wildflyHome.resolve(".wildfly-version")).hasContent(VERSION);
        }
    }

    @Test
    void ensureVersion_quandoStopFalha_tentaForceKill() throws Exception {
        try (java.net.ServerSocket srv = new java.net.ServerSocket(0)) {
            srv.setReuseAddress(true);
            int port = srv.getLocalPort();
            setField(provisioner, "managementPort", port);
            when(runtimeDriver.stop()).thenReturn(false);
            when(runtimeDriver.forceKill()).thenAnswer(inv -> { srv.close(); return true; });
            mockZsyncComZip(VERSION);

            provisioner.ensureVersion(VERSION);

            verify(runtimeDriver).stop();
            verify(runtimeDriver).forceKill();
        }
    }

    // ── versão fixada via fixedVersion ────────────────────────────────────────

    @Test
    void ensureVersion_fixedVersionPrevaleceOverDesired() throws Exception {
        setField(provisioner, "fixedVersion", Optional.of("2.0.0"));
        Files.writeString(wildflyHome.resolve(".wildfly-sha1"), REMOTE_SHA1);
        // checksumReader retorna REMOTE_SHA1 por padrão → já instalado

        // desired diz 1.0.0, mas fixedVersion diz 2.0.0 (já instalado)
        boolean result = provisioner.ensureVersion("1.0.0");

        assertThat(result).isFalse();
        verifyNoInteractions(zsync);
    }

    // ── zsync URL e credenciais ───────────────────────────────────────────────

    @Test
    void ensureVersion_passaZsyncUrlCorreta() throws Exception {
        mockZsyncComZip(VERSION);

        provisioner.ensureVersion(VERSION);

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(zsync).zsync(uriCaptor.capture(), any(), any());
        assertThat(uriCaptor.getValue().toString()).endsWith("-dist.zip.zsync");
    }

    @Test
    void ensureVersion_passaCredenciaisParaHost() throws Exception {
        mockZsyncComZip(VERSION);

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
        // Arquivo precisa ser múltiplo de 4096 para ser aceito como base de delta
        byte[] block = new byte[4096];
        java.util.Arrays.fill(block, (byte) 0x42);
        Files.write(existing, block);
        // O helper renomeia o arquivo para .zs-old e passa como input;
        // o mock deve gravar um ZIP válido no outFile para que extractZip funcione
        when(zsync.zsync(any(), any(), any())).thenAnswer(inv -> {
            Zsync.Options opts = inv.getArgument(1);
            Path outFile = opts.getOutputFile();
            criarZipFakeEm(outFile, VERSION);
            return outFile;
        });

        provisioner.ensureVersion(VERSION);

        ArgumentCaptor<Zsync.Options> optCaptor = ArgumentCaptor.forClass(Zsync.Options.class);
        verify(zsync).zsync(any(), optCaptor.capture(), any());
        // O helper renomeia o ZIP para .zs-old antes de passar como input
        Path zsOld = tempDir.resolve(filename + ".zs-old");
        assertThat(optCaptor.getValue().getInputFiles()).contains(zsOld);
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
        // Cria arquivo base alinhado para que zsync seja usado (sem base → HTTP direto)
        String filename = "wildfly-provisioning-" + VERSION + "-dist.zip";
        Files.write(tempDir.resolve(filename), new byte[4096]);

        when(zsync.zsync(any(), any(), any())).thenAnswer(inv -> {
            Zsync.Options opts = inv.getArgument(1);
            Path outFile = opts.getOutputFile();
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outFile))) {
                ZipEntry dir = new ZipEntry("wildfly/bin/");
                zos.putNextEntry(dir);
                zos.closeEntry();
                ZipEntry file = new ZipEntry("wildfly/bin/standalone.sh");
                zos.putNextEntry(file);
                zos.write("#!/bin/sh\n".getBytes());
                zos.closeEntry();
            }
            return outFile;
        });

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
        mockZsyncComZip(VERSION);

        boolean result = provisioner.ensureLatestVersion(msg -> {});

        assertThat(result).isTrue();
        verify(metadataClient).resolveLatestVersion(any(), any(), any(), any(), any(), any());
        assertThat(wildflyHome.resolve(".wildfly-version")).hasContent(VERSION);
        assertThat(wildflyHome.resolve(".wildfly-sha1")).hasContent(REMOTE_SHA1);
    }

    @Test
    void ensureLatestVersion_quandoFixedVersionDefinida_naoConsultaLatestVersion() throws Exception {
        setField(provisioner, "fixedVersion", Optional.of(VERSION));
        Files.writeString(wildflyHome.resolve(".wildfly-sha1"), REMOTE_SHA1);
        // checksumReader retorna REMOTE_SHA1 → SHA-1 confere, sem download

        boolean result = provisioner.ensureLatestVersion(msg -> {});

        assertThat(result).isFalse();
        verify(metadataClient, never()).resolveLatestVersion(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(zsync);
    }

    @Test
    void ensureLatestVersion_quandoFixedVersionDefinidaENovaSnapshot_reinstala() throws Exception {
        // SHA-1 remoto difere do local → nova build disponível
        setField(provisioner, "fixedVersion", Optional.of(VERSION));
        Files.writeString(wildflyHome.resolve(".wildfly-sha1"), OTHER_SHA1);
        mockZsyncComZip(VERSION);

        boolean result = provisioner.ensureLatestVersion(msg -> {});

        assertThat(result).isTrue();
        verify(metadataClient, never()).resolveLatestVersion(any(), any(), any(), any(), any(), any());
        assertThat(wildflyHome.resolve(".wildfly-version")).hasContent(VERSION);
        assertThat(wildflyHome.resolve(".wildfly-sha1")).hasContent(REMOTE_SHA1);
    }

    // ── downloadOnly ──────────────────────────────────────────────────────────

    @Test
    void downloadOnly_comVersao_baixaSemExtrair() throws Exception {
        mockZsyncComZip(VERSION);

        Path result = provisioner.downloadOnly(VERSION, msg -> {});

        assertThat(result).exists();
        // não deve ter extraído nada para wildflyHome
        assertThat(wildflyHome.resolve(".wildfly-version")).doesNotExist();
    }

    @Test
    void downloadOnly_semVersao_resolveLatestEBaixa() throws Exception {
        when(metadataClient.resolveLatestVersion(any(), any(), any(), any(), any(), any()))
                .thenReturn(VERSION);
        mockZsyncComZip(VERSION);

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
        Path zipFile = tempDir.resolve("wildfly-provisioning-" + VERSION + "-dist.zip");
        criarZipFakeEm(zipFile, VERSION);
        String sha1 = FileChecksum.sha1OrNull(zipFile);
        Files.writeString(wildflyHome.resolve(".wildfly-version"), VERSION);
        Files.writeString(wildflyHome.resolve(".wildfly-sha1"), sha1);

        boolean result = provisioner.applyFromCache(VERSION, msg -> {});

        assertThat(result).isFalse();
    }

    // ── setupManagementUser ───────────────────────────────────────────────────

    @Test
    void setupManagementUser_quandoCredenciaisJaExistem_naoSobrescreve() throws Exception {
        Path envFile = wildflyHome.resolve(".env");
        Files.writeString(envFile, "WILDFLY_MGMT_USER=admin\nWILDFLY_MGMT_PASSWORD=existente\n");

        provisioner.setupManagementUser(msg -> {});

        assertThat(envFile).hasContent("WILDFLY_MGMT_USER=admin\nWILDFLY_MGMT_PASSWORD=existente\n");
    }

    @Test
    void setupManagementUser_quandoMgmtUserPropsExiste_escreveHashEEnv() throws Exception {
        Path configDir = wildflyHome.resolve("standalone/configuration");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("mgmt-users.properties"),
                "#$REALM_NAME=ManagementRealm$\n");

        provisioner.setupManagementUser(msg -> {});

        String props = Files.readString(configDir.resolve("mgmt-users.properties"));
        assertThat(props).contains("admin=");
        String env = Files.readString(wildflyHome.resolve(".env"));
        assertThat(env).contains("WILDFLY_MGMT_USER=admin");
        assertThat(env).contains("WILDFLY_MGMT_PASSWORD=");
    }

    @Test
    void setupManagementUser_quandoDbConfigurado_escreveVariaveisNoBanco() throws Exception {
        Path configDir = wildflyHome.resolve("standalone/configuration");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("mgmt-users.properties"), "#$REALM_NAME=ManagementRealm$\n");
        setField(provisioner, "dbUrl", Optional.of("jdbc:oracle:thin:@//192.168.0.74:1521/DESENV"));
        setField(provisioner, "dbUsername", Optional.of("wonder"));
        setField(provisioner, "dbPassword", Optional.of("wonder"));

        provisioner.setupManagementUser(msg -> {});

        String env = Files.readString(wildflyHome.resolve(".env"));
        assertThat(env).contains("DB_URL=jdbc:oracle:thin:@//192.168.0.74:1521/DESENV");
        assertThat(env).contains("DB_USER=wonder");
        assertThat(env).contains("DB_PASSWORD=wonder");
        // DB_USERNAME não deve aparecer — WildFly usa DB_USER
        assertThat(env).doesNotContain("DB_USERNAME");
    }

    @Test
    void setupManagementUser_quandoMgmtUserPropsAusente_naoLancaExcecao() {
        // mgmt-users.properties não existe — deve absorver silenciosamente
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> provisioner.setupManagementUser(msg -> {}));
    }

    // ── md5Hex ────────────────────────────────────────────────────────────────

    @Test
    void md5Hex_retornaHashCorreto() throws Exception {
        // Valor de referência: MD5("admin:ManagementRealm:admin") = valor conhecido
        assertThat(WildflyProvisioner.md5Hex("admin:ManagementRealm:admin"))
                .isEqualTo("c22052286cd5d72239a90fe193737253");
    }

    // ── generatePassword ──────────────────────────────────────────────────────

    @Test
    void generatePassword_retornaSenhaComComprimentoCorreto() {
        assertThat(WildflyProvisioner.generatePassword(12)).hasSize(12);
    }

    @Test
    void generatePassword_naoContemCaracteresProibidos() {
        for (int i = 0; i < 50; i++) {
            String pwd = WildflyProvisioner.generatePassword(12);
            assertThat(pwd).doesNotContain(" ", "\"", "'", "$", "\\");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Configura o mock do zsync para escrever um ZIP válido no outFile e retorná-lo.
     * Necessário porque o helper renomeia o arquivo pré-existente para .zs-old antes
     * de chamar zsync — se o mock retornasse o path original ele estaria inválido.
     */
    private void mockZsyncComZip(String version) throws Exception {
        // Cria arquivo base alinhado a 4096 no tempDir para que ZsyncDownloadHelper
        // detecte a base delta e use zsync (sem isso, vai para HTTP direto).
        String filename = "wildfly-provisioning-" + version + "-dist.zip";
        Path existing = tempDir.resolve(filename);
        byte[] block = new byte[4096];
        Files.write(existing, block);

        when(zsync.zsync(any(), any(), any())).thenAnswer(inv -> {
            Zsync.Options opts = inv.getArgument(1);
            Path outFile = opts.getOutputFile();
            criarZipFakeEm(outFile, version);
            return outFile;
        });
    }

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
