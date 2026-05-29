package br.com.wonder.agent.core.download;

import br.com.wonder.agent.model.deploy.Artifact;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ArtifactDownloaderTest {

    @TempDir Path tempDir;

    Zsync zsync;
    ArtifactDownloader downloader;

    static final String WAR_URL =
            "http://192.168.0.86:8082/wnfe-releases/br/com/wonder/wnfe-war/2.999/wnfe-war-2.999.war";
    static final String ZSYNC_URL =
            "http://192.168.0.86:8082/wnfe-releases/br/com/wonder/wnfe-war/2.999/wnfe-war-2.999.zsync";

    @BeforeEach
    void setUp() throws Exception {
        zsync = mock(Zsync.class);
        downloader = new ArtifactDownloader(zsync);
        setField(downloader, "tempDir", tempDir.toString());
        setField(downloader, "username", "reader");
        setField(downloader, "password", Optional.of("secret"));
    }

    Artifact artifact() {
        return new Artifact("wnfe-war", "2.999", WAR_URL, null);
    }

    /** Cria um arquivo com tamanho múltiplo de 4096 para satisfazer isBlockAligned(). */
    static void writeAligned(Path file, String seed) throws Exception {
        byte[] block = new byte[4096];
        byte[] seedBytes = seed.getBytes();
        System.arraycopy(seedBytes, 0, block, 0, Math.min(seedBytes.length, block.length));
        Files.write(file, block);
    }

    @Test
    void download_usaUrlZsyncSubstituindoExtensao() throws Exception {
        Path downloaded = tempDir.resolve("wnfe-war-2.999.war");
        Files.createFile(downloaded);
        when(zsync.zsync(any(), any(), any())).thenReturn(downloaded);

        downloader.download(artifact());

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(zsync).zsync(uriCaptor.capture(), any(), any());
        assertThat(uriCaptor.getValue().toString()).isEqualTo(ZSYNC_URL);
    }

    @Test
    void download_zsyncUrlNaoSubstituiWarNoCaminhoApenasNaExtensao() throws Exception {
        // Regressão: ".replace(.war, .zsync)" trocava todas as ocorrências,
        // convertendo "wnfe-war" no path em "wnfe-zsync" e gerando 404.
        String warUrl = "http://192.168.0.86:8082/wnfe-releases/br/com/wonder/wnfe-war/2.999/wnfe-war-2.999.war";
        String expectedZsync = "http://192.168.0.86:8082/wnfe-releases/br/com/wonder/wnfe-war/2.999/wnfe-war-2.999.zsync";

        Path downloaded = tempDir.resolve("wnfe-war-2.999.war");
        Files.createFile(downloaded);
        when(zsync.zsync(any(), any(), any())).thenReturn(downloaded);

        Artifact a = new Artifact("wnfe-war", "2.999", warUrl, null);
        downloader.download(a);

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(zsync).zsync(uriCaptor.capture(), any(), any());
        assertThat(uriCaptor.getValue().toString()).isEqualTo(expectedZsync);
        assertThat(uriCaptor.getValue().toString()).doesNotContain("wnfe-zsync");
    }

    @Test
    void download_passaCredenciaisParaOHost() throws Exception {
        Path downloaded = tempDir.resolve("wnfe-war-2.999.war");
        Files.createFile(downloaded);
        when(zsync.zsync(any(), any(), any())).thenReturn(downloaded);

        downloader.download(artifact());

        ArgumentCaptor<Zsync.Options> optCaptor = ArgumentCaptor.forClass(Zsync.Options.class);
        verify(zsync).zsync(any(), optCaptor.capture(), any());
        Credentials creds = optCaptor.getValue().getCredentials().get("192.168.0.86");
        assertThat(creds).isNotNull();
        String decoded = new String(java.util.Base64.getDecoder().decode(
                creds.basic().replace("Basic ", "")));
        assertThat(decoded).isEqualTo("reader:secret");
    }

    @Test
    void download_quandoWarExistente_renomeiaPraZsOldEPassaComoInputFile() throws Exception {
        Path war = tempDir.resolve("wnfe-war-2.999.war");
        Path zsOld = tempDir.resolve("wnfe-war-2.999.war.zs-old");
        writeAligned(war, "previous-war-content");
        when(zsync.zsync(any(), any(), any())).thenReturn(war);

        downloader.download(artifact());

        ArgumentCaptor<Zsync.Options> optCaptor = ArgumentCaptor.forClass(Zsync.Options.class);
        verify(zsync).zsync(any(), optCaptor.capture(), any());
        // O .war original é renomeado para .zs-old e passado como input para delta
        assertThat(optCaptor.getValue().getInputFiles()).contains(zsOld);
        // O .war original não existe mais como input (foi renomeado)
        assertThat(optCaptor.getValue().getInputFiles()).doesNotContain(war);
    }

    @Test
    void download_quandoWarExistente_apagaZsOldAoFinal() throws Exception {
        Path war = tempDir.resolve("wnfe-war-2.999.war");
        Path zsOld = tempDir.resolve("wnfe-war-2.999.war.zs-old");
        writeAligned(war, "previous-war-content");
        when(zsync.zsync(any(), any(), any())).thenReturn(war);

        downloader.download(artifact());

        assertThat(zsOld).doesNotExist();
    }

    @Test
    void download_quandoSemArquivoExistente_naoAdicionaInputFile() throws Exception {
        Path downloaded = tempDir.resolve("wnfe-war-2.999.war");
        when(zsync.zsync(any(), any(), any())).thenReturn(downloaded);

        downloader.download(artifact());

        ArgumentCaptor<Zsync.Options> optCaptor = ArgumentCaptor.forClass(Zsync.Options.class);
        verify(zsync).zsync(any(), optCaptor.capture(), any());
        assertThat(optCaptor.getValue().getInputFiles()).isEmpty();
    }

    @Test
    void download_retornaArtifactComLocalFilePreenchido() throws Exception {
        Path downloaded = tempDir.resolve("wnfe-war-2.999.war");
        Files.createFile(downloaded);
        when(zsync.zsync(any(), any(), any())).thenReturn(downloaded);

        Artifact original = artifact();
        Artifact result = downloader.download(original);

        assertThat(original.localFile()).isNull();
        assertThat(result.localFile()).isEqualTo(downloaded);
        assertThat(result.version()).isEqualTo(original.version());
    }

    @Test
    void download_quandoZsyncFalha_lancaDownloadException() throws Exception {
        when(zsync.zsync(any(), any(), any())).thenThrow(new ZsyncException("repositório inacessível"));

        assertThatThrownBy(() -> downloader.download(artifact()))
                .isInstanceOf(ArtifactDownloader.DownloadException.class)
                .hasMessageContaining("wnfe-war:2.999");
    }

    @Test
    void download_quandoZsyncFalha_apagaZsOldMesmoComErro() throws Exception {
        Path war = tempDir.resolve("wnfe-war-2.999.war");
        Path zsOld = tempDir.resolve("wnfe-war-2.999.war.zs-old");
        writeAligned(war, "previous-war-content");
        when(zsync.zsync(any(), any(), any())).thenThrow(new ZsyncException("falha"));

        assertThatThrownBy(() -> downloader.download(artifact()))
                .isInstanceOf(ArtifactDownloader.DownloadException.class);

        assertThat(zsOld).doesNotExist();
    }

    @Test
    void download_quandoWarUrlVazio_lancaDownloadException() {
        Artifact semUrl = new Artifact("wnfe-war", "2.999", null, null);

        assertThatThrownBy(() -> downloader.download(semUrl))
                .isInstanceOf(ArtifactDownloader.DownloadException.class)
                .hasMessageContaining("warUrl vazio");
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
