package br.com.wonder.agent.core.download;

import br.com.wonder.agent.model.deploy.Artifact;
import com.salesforce.zsync.Zsync;
import com.salesforce.zsync.ZsyncException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ArtifactDownloaderTest {

    @TempDir Path tempDir;

    ArtifactDownloader downloader;
    Zsync zsync;

    static final String WAR_URL =
            "https://wonderpublic.s3.sa-east-1.amazonaws.com/pacotes/5/wnfe-war-2.5.0.war";

    @BeforeEach
    void setUp() throws Exception {
        zsync = mock(Zsync.class);
        downloader = new ArtifactDownloader(zsync);
        setField(downloader, "tempDir", tempDir.toString());
    }

    Artifact artifact() {
        return new Artifact("wnfe", "2.5.0", WAR_URL, null);
    }

    @Test
    void download_usaUrlZsyncComSufixo() throws Exception {
        Path downloaded = tempDir.resolve("wnfe-war-2.5.0.war");
        Files.createFile(downloaded);
        when(zsync.zsync(any(), any())).thenReturn(downloaded);

        downloader.download(artifact());

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(zsync).zsync(uriCaptor.capture(), any());
        assertThat(uriCaptor.getValue().toString()).isEqualTo(WAR_URL + ".zsync");
    }

    @Test
    void download_quandoArquivoExistente_adicionaComoInputFile() throws Exception {
        Path existing = tempDir.resolve("wnfe-war-2.5.0.war");
        Files.writeString(existing, "previous-war-content");

        Path downloaded = existing;
        when(zsync.zsync(any(), any())).thenReturn(downloaded);

        downloader.download(artifact());

        ArgumentCaptor<Zsync.Options> optCaptor = ArgumentCaptor.forClass(Zsync.Options.class);
        verify(zsync).zsync(any(), optCaptor.capture());
        assertThat(optCaptor.getValue().getInputFiles()).contains(existing);
    }

    @Test
    void download_quandoSemArquivoExistente_naoAdicionaInputFile() throws Exception {
        Path downloaded = tempDir.resolve("wnfe-war-2.5.0.war");
        // arquivo ainda não existe — zsync vai criá-lo
        when(zsync.zsync(any(), any())).thenReturn(downloaded);

        downloader.download(artifact());

        ArgumentCaptor<Zsync.Options> optCaptor = ArgumentCaptor.forClass(Zsync.Options.class);
        verify(zsync).zsync(any(), optCaptor.capture());
        assertThat(optCaptor.getValue().getInputFiles()).isEmpty();
    }

    @Test
    void download_retornaArtifactComLocalFilePreenchido() throws Exception {
        Path downloaded = tempDir.resolve("wnfe-war-2.5.0.war");
        Files.createFile(downloaded);
        when(zsync.zsync(any(), any())).thenReturn(downloaded);

        Artifact original = artifact();
        Artifact result = downloader.download(original);

        assertThat(original.localFile()).isNull();
        assertThat(result.localFile()).isEqualTo(downloaded);
        assertThat(result.version()).isEqualTo(original.version());
    }

    @Test
    void download_quandoZsyncFalha_lancaDownloadException() throws Exception {
        when(zsync.zsync(any(), any())).thenThrow(new ZsyncException("S3 inacessível"));

        assertThatThrownBy(() -> downloader.download(artifact()))
                .isInstanceOf(ArtifactDownloader.DownloadException.class)
                .hasMessageContaining("wnfe:2.5.0");
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
