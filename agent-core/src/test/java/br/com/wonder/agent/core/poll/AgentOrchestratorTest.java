package br.com.wonder.agent.core.poll;

import br.com.wonder.agent.central.CentralClient;
import br.com.wonder.agent.core.deploy.DeployPipeline;
import br.com.wonder.agent.core.download.ArtifactDownloader;
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
    @InjectMocks AgentOrchestrator orchestrator;

    DesiredState desiredState;

    @BeforeEach
    void setUp() throws Exception {
        setField(orchestrator, "clientId", "cliente-abc");
        setField(orchestrator, "agentVersion", "1.0.0-SNAPSHOT");

        desiredState = new DesiredState(
                "wnfe", "2.5.0",
                "https://wonderpublic.s3.sa-east-1.amazonaws.com/pacotes/5/wnfe-war-2.5.0.war",
                "wildfly",
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

        orchestrator.poll();

        verify(deployPipeline, never()).execute(any());
        verify(centralClient).reportStatus(eq("cliente-abc"), any(AgentStatusReport.class));
    }

    @Test
    void poll_quandoVersaoDesatualizada_baixaEFazDeploy() throws Exception {
        when(centralClient.fetchDesiredState("cliente-abc")).thenReturn(desiredState);
        when(driver.getInstalledVersion()).thenReturn("2.4.0").thenReturn("2.5.0");
        when(artifactDownloader.download(any())).thenAnswer(inv ->
                ((Artifact) inv.getArgument(0)).withLocalFile(Path.of("/tmp/wnfe-war-2.5.0.war")));
        when(deployPipeline.execute(any())).thenReturn(
                DeployResult.success("2.5.0", Duration.ofSeconds(45),
                        RuntimeState.STOPPED, RuntimeState.RUNNING));
        when(driver.detectState()).thenReturn(RuntimeState.RUNNING);
        when(driver.healthCheck()).thenReturn(HealthStatus.ok());

        orchestrator.poll();

        verify(artifactDownloader).download(any());
        verify(deployPipeline).execute(any());
        verify(centralClient).reportStatus(eq("cliente-abc"), any());
    }

    @Test
    void poll_quandoDownloadFalha_reportaStatusSemDeploy() throws Exception {
        when(centralClient.fetchDesiredState("cliente-abc")).thenReturn(desiredState);
        when(driver.getInstalledVersion()).thenReturn("2.4.0");
        when(artifactDownloader.download(any()))
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
        when(artifactDownloader.download(any())).thenAnswer(inv ->
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

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
