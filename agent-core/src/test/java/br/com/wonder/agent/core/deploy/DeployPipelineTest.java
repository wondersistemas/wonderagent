package br.com.wonder.agent.core.deploy;

import br.com.wonder.agent.core.state.StateMachine;
import br.com.wonder.agent.model.deploy.Artifact;
import br.com.wonder.agent.model.deploy.DeployResult;
import br.com.wonder.agent.model.deploy.HealthStatus;
import br.com.wonder.agent.model.driver.RuntimeDriver;
import br.com.wonder.agent.model.state.RuntimeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeployPipelineTest {

    @Mock StateMachine stateMachine;
    @Mock RuntimeDriver driver;
    @InjectMocks DeployPipeline pipeline;

    Artifact artifact;

    @BeforeEach
    void setUp() {
        artifact = new Artifact("wnfe", "2.5.0",
                "https://wonderpublic.s3.sa-east-1.amazonaws.com/pacotes/5/wnfe-war-2.5.0.war",
                Path.of("/tmp/wnfe-war-2.5.0.war"));
    }

    @Test
    void execute_quandoStopped_deployaSemParar() {
        when(stateMachine.detectAndRecover()).thenReturn(RuntimeState.STOPPED);
        when(stateMachine.canDeploy(RuntimeState.STOPPED)).thenReturn(true);
        when(driver.deploy(artifact)).thenReturn(
                DeployResult.success("2.5.0", Duration.ofSeconds(1), RuntimeState.STOPPED, RuntimeState.STOPPED));
        when(driver.start()).thenReturn(true);
        when(driver.healthCheck()).thenReturn(HealthStatus.ok());

        DeployResult result = pipeline.execute(artifact);

        assertThat(result.success()).isTrue();
        verify(driver, never()).stop();
        verify(driver).deploy(artifact);
        verify(driver).start();
    }

    @Test
    void execute_quandoRunning_paraAntesDeDeployar() {
        when(stateMachine.detectAndRecover()).thenReturn(RuntimeState.RUNNING);
        when(stateMachine.canDeploy(RuntimeState.RUNNING)).thenReturn(true);
        when(driver.stop()).thenReturn(true);
        when(driver.deploy(artifact)).thenReturn(
                DeployResult.success("2.5.0", Duration.ofSeconds(1), RuntimeState.STOPPED, RuntimeState.STOPPED));
        when(driver.start()).thenReturn(true);
        when(driver.healthCheck()).thenReturn(HealthStatus.ok());

        DeployResult result = pipeline.execute(artifact);

        assertThat(result.success()).isTrue();
        verify(driver).stop();
        verify(driver).deploy(artifact);
    }

    @Test
    void execute_quandoUpToDate_paraAntesDeDeployar() {
        when(stateMachine.detectAndRecover()).thenReturn(RuntimeState.UP_TO_DATE);
        when(stateMachine.canDeploy(RuntimeState.UP_TO_DATE)).thenReturn(true);
        when(driver.stop()).thenReturn(true);
        when(driver.deploy(artifact)).thenReturn(
                DeployResult.success("2.5.0", Duration.ofSeconds(1), RuntimeState.STOPPED, RuntimeState.STOPPED));
        when(driver.start()).thenReturn(true);
        when(driver.healthCheck()).thenReturn(HealthStatus.ok());

        pipeline.execute(artifact);

        verify(driver).stop();
    }

    @Test
    void execute_quandoStopFalha_tentaForceKill() {
        when(stateMachine.detectAndRecover()).thenReturn(RuntimeState.RUNNING);
        when(stateMachine.canDeploy(RuntimeState.RUNNING)).thenReturn(true);
        when(driver.stop()).thenReturn(false);
        when(driver.deploy(artifact)).thenReturn(
                DeployResult.success("2.5.0", Duration.ofSeconds(1), RuntimeState.STOPPED, RuntimeState.STOPPED));
        when(driver.start()).thenReturn(true);
        when(driver.healthCheck()).thenReturn(HealthStatus.ok());

        pipeline.execute(artifact);

        verify(driver).forceKill();
    }

    @Test
    void execute_quandoEstadoNaoPermiteDeploy_retornaFalha() {
        when(stateMachine.detectAndRecover()).thenReturn(RuntimeState.UNKNOWN);
        when(stateMachine.canDeploy(RuntimeState.UNKNOWN)).thenReturn(false);

        DeployResult result = pipeline.execute(artifact);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).contains("UNKNOWN");
        verify(driver, never()).deploy(any());
    }

    @Test
    void execute_quandoDeployFalha_retornaFalhaSemIniciar() {
        when(stateMachine.detectAndRecover()).thenReturn(RuntimeState.STOPPED);
        when(stateMachine.canDeploy(RuntimeState.STOPPED)).thenReturn(true);
        when(driver.deploy(artifact)).thenReturn(
                DeployResult.failure("2.5.0", Duration.ofSeconds(1),
                        RuntimeState.STOPPED, RuntimeState.STOPPED, "Disco cheio"));

        DeployResult result = pipeline.execute(artifact);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("Disco cheio");
        verify(driver, never()).start();
    }

    @Test
    void execute_quandoStartFalha_retornaFalha() {
        when(stateMachine.detectAndRecover()).thenReturn(RuntimeState.STOPPED);
        when(stateMachine.canDeploy(RuntimeState.STOPPED)).thenReturn(true);
        when(driver.deploy(artifact)).thenReturn(
                DeployResult.success("2.5.0", Duration.ofSeconds(1), RuntimeState.STOPPED, RuntimeState.STOPPED));
        when(driver.start()).thenReturn(false);
        when(driver.detectState()).thenReturn(RuntimeState.STOPPED);

        DeployResult result = pipeline.execute(artifact);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).contains("timeout");
        verify(driver, never()).healthCheck();
    }

    @Test
    void execute_quandoHealthCheckFalha_retornaFalha() {
        when(stateMachine.detectAndRecover()).thenReturn(RuntimeState.STOPPED);
        when(stateMachine.canDeploy(RuntimeState.STOPPED)).thenReturn(true);
        when(driver.deploy(artifact)).thenReturn(
                DeployResult.success("2.5.0", Duration.ofSeconds(1), RuntimeState.STOPPED, RuntimeState.STOPPED));
        when(driver.start()).thenReturn(true);
        when(driver.healthCheck()).thenReturn(HealthStatus.unhealthy("HTTP 503"));
        when(driver.detectState()).thenReturn(RuntimeState.RUNNING);

        DeployResult result = pipeline.execute(artifact);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).contains("HTTP 503");
    }
}
