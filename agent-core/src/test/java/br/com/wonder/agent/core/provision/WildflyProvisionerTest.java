package br.com.wonder.agent.core.provision;

import com.salesforce.zsync.Zsync;
import com.salesforce.zsync.ZsyncException;
import com.salesforce.zsync.http.Credentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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
    WildflyProvisioner provisioner;

    static final String REPO_URL = "http://192.168.0.86:8082";
    static final String REPO_PATH = "wnfe-releases";
    static final String VERSION = "1.0.0-SNAPSHOT";

    @BeforeEach
    void setUp() throws Exception {
        zsync = mock(Zsync.class);
        provisioner = new WildflyProvisioner(zsync);
        setField(provisioner, "repositoryUrl", REPO_URL);
        setField(provisioner, "repoPath", REPO_PATH);
        setField(provisioner, "username", "reader");
        setField(provisioner, "password", "secret");
        setField(provisioner, "tempDir", tempDir.toString());
        setField(provisioner, "wildflyHome", wildflyHome.toString());
        setField(provisioner, "fixedVersion", Optional.empty());
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
        when(zsync.zsync(any(), any())).thenReturn(fakeZip);

        boolean result = provisioner.ensureVersion(VERSION);

        assertThat(result).isTrue();
        verify(zsync).zsync(any(), any());
        assertThat(wildflyHome.resolve(".wildfly-version")).hasContent(VERSION);
    }

    @Test
    void ensureVersion_quandoSemVersaoInstalada_baixaEExtrai() throws Exception {
        // nenhum .wildfly-version presente

        Path fakeZip = criarZipFake(tempDir, VERSION);
        when(zsync.zsync(any(), any())).thenReturn(fakeZip);

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
        when(zsync.zsync(any(), any())).thenReturn(fakeZip);

        provisioner.ensureVersion(VERSION);

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(zsync).zsync(uriCaptor.capture(), any());
        assertThat(uriCaptor.getValue().toString()).endsWith("-dist.zip.zsync");
    }

    @Test
    void ensureVersion_passaCredenciaisParaHost() throws Exception {
        Path fakeZip = criarZipFake(tempDir, VERSION);
        when(zsync.zsync(any(), any())).thenReturn(fakeZip);

        provisioner.ensureVersion(VERSION);

        ArgumentCaptor<Zsync.Options> optCaptor = ArgumentCaptor.forClass(Zsync.Options.class);
        verify(zsync).zsync(any(), optCaptor.capture());
        Credentials creds = optCaptor.getValue().getCredentials().get("192.168.0.86");
        assertThat(creds).isNotNull();
        // basic() retorna "Basic <base64(user:pass)>" — decodifica para verificar username
        String decoded = new String(java.util.Base64.getDecoder()
                .decode(creds.basic().replace("Basic ", "")));
        assertThat(decoded).startsWith("reader:");
    }

    @Test
    void ensureVersion_quandoZipExistente_adicionaComoInputFile() throws Exception {
        String filename = "wildfly-provisioning-" + VERSION + "-dist.zip";
        Path existing = tempDir.resolve(filename);
        // cria ZIP fake no local do cache
        criarZipFakeEm(existing, VERSION);
        when(zsync.zsync(any(), any())).thenReturn(existing);

        provisioner.ensureVersion(VERSION);

        ArgumentCaptor<Zsync.Options> optCaptor = ArgumentCaptor.forClass(Zsync.Options.class);
        verify(zsync).zsync(any(), optCaptor.capture());
        assertThat(optCaptor.getValue().getInputFiles()).contains(existing);
    }

    // ── falha de download ─────────────────────────────────────────────────────

    @Test
    void ensureVersion_quandoZsyncFalha_lancaProvisioningException() throws Exception {
        when(zsync.zsync(any(), any())).thenThrow(new ZsyncException("servidor inacessível"));

        assertThatThrownBy(() -> provisioner.ensureVersion(VERSION))
                .isInstanceOf(WildflyProvisioner.ProvisioningException.class)
                .hasMessageContaining(VERSION);
    }

    // ── extração do ZIP ───────────────────────────────────────────────────────

    @Test
    void ensureVersion_extraiConteudoDoZip() throws Exception {
        Path fakeZip = tempDir.resolve("wildfly-provisioning-" + VERSION + "-dist.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(fakeZip))) {
            // simula estrutura wildfly/bin/standalone.sh
            ZipEntry dir = new ZipEntry("wildfly/bin/");
            zos.putNextEntry(dir);
            zos.closeEntry();
            ZipEntry file = new ZipEntry("wildfly/bin/standalone.sh");
            zos.putNextEntry(file);
            zos.write("#!/bin/sh\n".getBytes());
            zos.closeEntry();
        }
        when(zsync.zsync(any(), any())).thenReturn(fakeZip);

        provisioner.ensureVersion(VERSION);

        // strip do primeiro componente: wildfly/bin/... → bin/...
        assertThat(wildflyHome.resolve("bin/standalone.sh")).exists();
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
