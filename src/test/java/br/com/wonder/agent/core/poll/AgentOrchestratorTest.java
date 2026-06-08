package br.com.wonder.agent.core.poll;

import br.com.wonder.agent.central.CentralClient;
import br.com.wonder.agent.core.db.DatabaseVersionReader;
import br.com.wonder.agent.core.deploy.DeployPipeline;
import br.com.wonder.agent.core.download.ArtifactDownloader;
import br.com.wonder.agent.core.download.ZsyncChecksumReader;
import br.com.wonder.agent.core.provision.WildflyProvisioner;
import br.com.wonder.agent.model.config.AgentStatusReport;
import br.com.wonder.agent.model.config.DesiredState;
import br.com.wonder.agent.model.deploy.Artifact;
import br.com.wonder.agent.model.deploy.DeployResult;
import br.com.wonder.agent.model.deploy.HealthStatus;
import br.com.wonder.agent.model.driver.RuntimeDriver;
import br.com.wonder.agent.model.state.RuntimeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentOrchestratorTest {

    @Mock CentralClient centralClient;
    @Mock DeployPipeline deployPipeline;
    @Mock RuntimeDriver driver;
    @Mock ArtifactDownloader artifactDownloader;
    @Mock WildflyProvisioner wildflyProvisioner;
    @Mock DatabaseVersionReader databaseVersionReader;
    @Mock ZsyncChecksumReader zsyncChecksumReader;
    @InjectMocks AgentOrchestrator orchestrator;

    DesiredState desiredState;

    @BeforeEach
    void setUp() throws Exception {
        setField(orchestrator, "clientId", "cliente-abc");
        setField(orchestrator, "agentVersion", "1.0.0-SNAPSHOT");
        lenient().when(databaseVersionReader.readDbVersion()).thenReturn(java.util.Optional.empty());
        orchestrator.startServiceMode();

        desiredState = new DesiredState(
                "wnfe", "2.5.0",
                "https://wonderpublic.s3.sa-east-1.amazonaws.com/pacotes/5/wnfe-war-2.5.0.war",
                "wildfly",
                null,
                new DesiredState.DeployConfig(
                        "C:/wildfly/standalone/deployments",
                        "http://localhost:8080/probusweb/health",
                        30, 180, 60, false));
    }

    @Test
    void poll_quandoVersaoJaInstalada_naoDeployaEReportaStatus() {
        when(centralClient.fetchDesiredState("cliente-abc")).thenReturn(desiredState);
        when(driver.getInstalledVersion()).thenReturn("2.5.0");
        when(driver.detectState()).thenReturn(RuntimeState.RUNNING);
        when(driver.healthCheck()).thenReturn(HealthStatus.ok());
        // checksum indisponível → confia na versão
        when(zsyncChecksumReader.readSha1(any())).thenReturn(java.util.Optional.empty());

        orchestrator.poll();

        verify(deployPipeline, never()).execute(any());
        verify(centralClient).reportStatus(eq("cliente-abc"), any(AgentStatusReport.class));
    }

    @Test
    void poll_quandoVersaoIgualEChecksumConfere_naoFazDeploy() throws Exception {
        String sha1 = "4e1243bd22c66e76c2ba9eddc1f91394e57f9f83";
        when(centralClient.fetchDesiredState("cliente-abc")).thenReturn(desiredState);
        when(driver.getInstalledVersion()).thenReturn("2.5.0");
        when(driver.getInstalledChecksum()).thenReturn(java.util.Optional.of(sha1));
        when(zsyncChecksumReader.readSha1(any())).thenReturn(java.util.Optional.of(sha1));
        when(driver.detectState()).thenReturn(RuntimeState.RUNNING);
        when(driver.healthCheck()).thenReturn(HealthStatus.ok());

        orchestrator.poll();

        verify(deployPipeline, never()).execute(any());
    }

    @Test
    void poll_quandoVersaoIgualMasChecksumDiverge_forcaDownloadEDeploy() throws Exception {
        when(centralClient.fetchDesiredState("cliente-abc")).thenReturn(desiredState);
        when(driver.getInstalledVersion()).thenReturn("2.5.0").thenReturn("2.5.0");
        when(driver.getInstalledChecksum()).thenReturn(java.util.Optional.of("checksum-corrompido"));
        when(zsyncChecksumReader.readSha1(any())).thenReturn(java.util.Optional.of("checksum-remoto-correto"));
        when(artifactDownloader.download(any(), any(), any())).thenAnswer(inv ->
                ((Artifact) inv.getArgument(0)).withLocalFile(java.nio.file.Path.of("/tmp/wnfe-war-2.5.0.war")));
        when(deployPipeline.execute(any())).thenReturn(
                DeployResult.success("2.5.0", java.time.Duration.ofSeconds(10),
                        RuntimeState.STOPPED, RuntimeState.RUNNING));
        when(driver.detectState()).thenReturn(RuntimeState.RUNNING);
        when(driver.healthCheck()).thenReturn(HealthStatus.ok());

        orchestrator.poll();

        verify(artifactDownloader).download(any(), any(), any());
        verify(deployPipeline).execute(any());
    }

    @Test
    void poll_quandoVersaoIgualEChecksumRemotoIndisponivel_naoFazDeploy() throws Exception {
        when(centralClient.fetchDesiredState("cliente-abc")).thenReturn(desiredState);
        when(driver.getInstalledVersion()).thenReturn("2.5.0");
        when(driver.getInstalledChecksum()).thenReturn(java.util.Optional.of("sha1-local"));
        when(zsyncChecksumReader.readSha1(any())).thenReturn(java.util.Optional.empty());
        when(driver.detectState()).thenReturn(RuntimeState.RUNNING);
        when(driver.healthCheck()).thenReturn(HealthStatus.ok());

        orchestrator.poll();

        verify(deployPipeline, never()).execute(any());
    }

    @Test
    void poll_quandoVersaoIgualEChecksumLocalAusenteECacheAusente_forcaDownloadEDeploy() throws Exception {
        when(centralClient.fetchDesiredState("cliente-abc")).thenReturn(desiredState);
        when(driver.getInstalledVersion()).thenReturn("2.5.0").thenReturn("2.5.0");
        when(driver.getInstalledChecksum()).thenReturn(java.util.Optional.empty());
        when(artifactDownloader.readCachedSha1(any())).thenReturn(java.util.Optional.empty());
        when(zsyncChecksumReader.readSha1(any())).thenReturn(java.util.Optional.of("checksum-remoto"));
        when(artifactDownloader.download(any(), any(), any())).thenAnswer(inv ->
                ((Artifact) inv.getArgument(0)).withLocalFile(java.nio.file.Path.of("/tmp/wnfe-war-2.5.0.war")));
        when(deployPipeline.execute(any())).thenReturn(
                DeployResult.success("2.5.0", java.time.Duration.ofSeconds(10),
                        RuntimeState.STOPPED, RuntimeState.RUNNING));
        when(driver.detectState()).thenReturn(RuntimeState.RUNNING);
        when(driver.healthCheck()).thenReturn(HealthStatus.ok());

        orchestrator.poll();

        verify(artifactDownloader).download(any(), any(), any());
        verify(deployPipeline).execute(any());
    }

    @Test
    void poll_quandoVersaoIgualEChecksumLocalAusenteMasCacheConfere_naoFazDownload() throws Exception {
        String sha1 = "abc123";
        when(centralClient.fetchDesiredState("cliente-abc")).thenReturn(desiredState);
        when(driver.getInstalledVersion()).thenReturn("2.5.0");
        when(driver.getInstalledChecksum()).thenReturn(java.util.Optional.empty());
        when(artifactDownloader.readCachedSha1(any())).thenReturn(java.util.Optional.of(sha1));
        when(zsyncChecksumReader.readSha1(any())).thenReturn(java.util.Optional.of(sha1));
        when(driver.detectState()).thenReturn(RuntimeState.RUNNING);
        when(driver.healthCheck()).thenReturn(HealthStatus.ok());

        orchestrator.poll();

        verify(artifactDownloader, never()).download(any(), any(), any());
        verify(deployPipeline, never()).execute(any());
    }

    @Test
    void poll_quandoVersaoDesatualizada_baixaEFazDeploy() throws Exception {
        when(centralClient.fetchDesiredState("cliente-abc")).thenReturn(desiredState);
        when(driver.getInstalledVersion()).thenReturn("2.4.0").thenReturn("2.5.0");
        when(artifactDownloader.download(any(), any(), any())).thenAnswer(inv ->
                ((Artifact) inv.getArgument(0)).withLocalFile(Path.of("/tmp/wnfe-war-2.5.0.war")));
        when(deployPipeline.execute(any())).thenReturn(
                DeployResult.success("2.5.0", Duration.ofSeconds(45),
                        RuntimeState.STOPPED, RuntimeState.RUNNING));
        when(driver.detectState()).thenReturn(RuntimeState.RUNNING);
        when(driver.healthCheck()).thenReturn(HealthStatus.ok());

        orchestrator.poll();

        verify(artifactDownloader).download(any(), any(), any());
        verify(deployPipeline).execute(any());
        verify(centralClient).reportStatus(eq("cliente-abc"), any());
    }

    @Test
    void poll_quandoDownloadFalha_reportaStatusSemDeploy() throws Exception {
        when(centralClient.fetchDesiredState("cliente-abc")).thenReturn(desiredState);
        when(driver.getInstalledVersion()).thenReturn("2.4.0");
        when(artifactDownloader.download(any(), any(), any()))
                .thenThrow(new ArtifactDownloader.DownloadException("S3 indisponível", new Exception()));
        when(driver.detectState()).thenReturn(RuntimeState.RUNNING);
        when(driver.healthCheck()).thenReturn(HealthStatus.ok());

        orchestrator.poll();

        verify(deployPipeline, never()).execute(any());
        verify(centralClient).reportStatus(eq("cliente-abc"), any());
    }

    @Test
    void poll_quandoDeployFalha_reportaDeployResult() throws Exception {
        DeployResult falha = DeployResult.failure("2.5.0", Duration.ofSeconds(10),
                RuntimeState.STOPPED, RuntimeState.STOPPED, "Falha ao copiar artefato");

        when(centralClient.fetchDesiredState("cliente-abc")).thenReturn(desiredState);
        when(driver.getInstalledVersion()).thenReturn("2.4.0").thenReturn("2.4.0");
        when(artifactDownloader.download(any(), any(), any())).thenAnswer(inv ->
                ((Artifact) inv.getArgument(0)).withLocalFile(Path.of("/tmp/wnfe-war-2.5.0.war")));
        when(deployPipeline.execute(any())).thenReturn(falha);
        when(driver.detectState()).thenReturn(RuntimeState.STOPPED);
        when(driver.healthCheck()).thenReturn(HealthStatus.unhealthy("Serviço parado"));

        orchestrator.poll();

        verify(centralClient).reportDeployResult(eq("cliente-abc"), eq(falha));
    }

    @Test
    void poll_quandoStatusReport_inclueVersaoDoAgente() throws Exception {
        when(centralClient.fetchDesiredState("cliente-abc")).thenReturn(desiredState);
        when(driver.getInstalledVersion()).thenReturn("2.5.0");
        when(driver.detectState()).thenReturn(RuntimeState.RUNNING);
        when(driver.healthCheck()).thenReturn(HealthStatus.ok());

        orchestrator.poll();

        ArgumentCaptor<AgentStatusReport> captor = ArgumentCaptor.forClass(AgentStatusReport.class);
        verify(centralClient).reportStatus(eq("cliente-abc"), captor.capture());

        AgentStatusReport report = captor.getValue();
        assertThat(report.agentVersion()).isEqualTo("1.0.0-SNAPSHOT");
        assertThat(report.installedVersion()).isEqualTo("2.5.0");
        assertThat(report.runtimeState()).isEqualTo(RuntimeState.RUNNING);
        assertThat(report.healthy()).isTrue();
    }

    @Test
    void poll_quandoCentralClientLancaExcecao_naoPropaga() {
        when(centralClient.fetchDesiredState("cliente-abc"))
                .thenThrow(new RuntimeException("Servidor central indisponível"));

        orchestrator.poll();

        verify(centralClient, never()).reportStatus(any(), any());
    }

    @Test
    void poll_semModoServico_naoExecutaCiclo() throws Exception {
        // Nova instância sem startServiceMode()
        AgentOrchestrator semServico = new AgentOrchestrator();
        setField(semServico, "centralClient", centralClient);
        setField(semServico, "deployPipeline", deployPipeline);
        setField(semServico, "artifactDownloader", artifactDownloader);
        setField(semServico, "clientId", "cliente-abc");
        setField(semServico, "agentVersion", "1.0.0-SNAPSHOT");

        semServico.poll();

        verifyNoInteractions(centralClient, deployPipeline, artifactDownloader);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
