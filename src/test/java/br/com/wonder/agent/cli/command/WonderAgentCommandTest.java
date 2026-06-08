package br.com.wonder.agent.cli.command;

import br.com.wonder.agent.core.poll.AgentOrchestrator;
import br.com.wonder.agent.core.provision.WildflyProvisioner;
import br.com.wonder.agent.model.deploy.HealthStatus;
import br.com.wonder.agent.model.driver.RuntimeDriver;
import br.com.wonder.agent.model.state.RuntimeState;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WonderAgentCommandTest {

    @Nested
    class VerifAtualizacaoCommandTest {
        @Mock AgentOrchestrator orchestrator;
        @InjectMocks WonderAgentCommand.VerifAtualizacaoCommand command;

        @Test
        void run_delegaAoOrchestratorPollNow() {
            command.run();
            verify(orchestrator).pollNow(any());
        }
    }

    @Nested
    class StatusCommandTest {
        @Mock RuntimeDriver driver;
        @InjectMocks WonderAgentCommand.StatusCommand command;

        @Test
        void run_consultaDriverParaExibirStatus() {
            when(driver.detectState()).thenReturn(RuntimeState.RUNNING);
            when(driver.getInstalledVersion()).thenReturn("1.42.0");
            when(driver.healthCheck()).thenReturn(HealthStatus.ok());

            command.run();

            verify(driver).detectState();
            verify(driver).getInstalledVersion();
            verify(driver).healthCheck();
        }

        @Test
        void run_quandoVersaoDesconhecida_naoLancaExcecao() {
            when(driver.detectState()).thenReturn(RuntimeState.STOPPED);
            when(driver.getInstalledVersion()).thenReturn(null);
            when(driver.healthCheck()).thenReturn(HealthStatus.unhealthy("parado"));

            assertThatCode(() -> command.run()).doesNotThrowAnyException();
        }
    }

    @Nested
    class DetectCommandTest {
        @Mock RuntimeDriver driver;
        @InjectMocks WonderAgentCommand.DetectCommand command;

        @Test
        void run_consultaDriverEImprime() {
            when(driver.detectState()).thenReturn(RuntimeState.STOPPED);
            command.run();
            verify(driver).detectState();
        }
    }

    @Nested
    class InstallCommandTest {
        @InjectMocks WonderAgentCommand.InstallCommand command;

        @Test
        void run_comNssmAusente_naoPropagaExcecao() {
            assertThatCode(() -> command.run()).doesNotThrowAnyException();
        }
    }

    @Nested
    class UninstallCommandTest {
        @InjectMocks WonderAgentCommand.UninstallCommand command;

        @Test
        void run_comNssmAusente_naoPropagaExcecao() {
            assertThatCode(() -> command.run()).doesNotThrowAnyException();
        }
    }

    @Nested
    class DownloadServerCommandTest {
        @Mock WildflyProvisioner provisioner;
        @InjectMocks WonderAgentCommand.DownloadServerCommand command;

        @Test
        void run_comVersaoExplicita_delegaAoProvisionerComVersao() throws Exception {
            command.version = "1.0.0-SNAPSHOT";
            command.run();
            verify(provisioner).downloadOnly(eq("1.0.0-SNAPSHOT"), any());
        }

        @Test
        void run_semVersao_delegaAoProvisionerSemVersao() throws Exception {
            command.version = null;
            command.run();
            verify(provisioner).downloadOnly(any(java.util.function.Consumer.class));
        }

        @Test
        void run_quandoProvisioner_lancaExcecao_naoPropaga() throws Exception {
            command.version = "1.0.0-SNAPSHOT";
            doThrow(new WildflyProvisioner.ProvisioningException("erro", null))
                    .when(provisioner).downloadOnly(any(String.class), any());
            assertThatCode(() -> command.run()).doesNotThrowAnyException();
        }
    }

    @Nested
    class ApplyServerCommandTest {
        @Mock WildflyProvisioner provisioner;
        @InjectMocks WonderAgentCommand.ApplyServerCommand command;

        @Test
        void run_comVersaoExplicita_delegaAoProvisioner() throws Exception {
            command.version = "1.0.0-SNAPSHOT";
            command.run();
            verify(provisioner).applyFromCache(eq("1.0.0-SNAPSHOT"), any());
        }

        @Test
        void run_semVersao_passaNullAoProvisioner() throws Exception {
            command.version = null;
            command.run();
            verify(provisioner).applyFromCache(isNull(), any());
        }

        @Test
        void run_quandoProvisioner_lancaExcecao_naoPropaga() throws Exception {
            command.version = null;
            doThrow(new WildflyProvisioner.ProvisioningException("sem cache", null))
                    .when(provisioner).applyFromCache(any(), any());
            assertThatCode(() -> command.run()).doesNotThrowAnyException();
        }
    }

    @Nested
    class ProvisionarCommandTest {
        @Mock WildflyProvisioner provisioner;
        @InjectMocks WonderAgentCommand.ProvisionarCommand command;

        @Test
        void run_comVersaoExplicita_chamaEnsureVersion() throws Exception {
            command.version = "1.0.0-SNAPSHOT";
            command.run();
            verify(provisioner).ensureVersion(eq("1.0.0-SNAPSHOT"), any());
        }

        @Test
        void run_semVersao_chamaEnsureLatestVersion() throws Exception {
            command.version = null;
            command.run();
            verify(provisioner).ensureLatestVersion(any());
        }

        @Test
        void run_quandoProvisioner_lancaExcecao_naoPropaga() throws Exception {
            command.version = null;
            doThrow(new WildflyProvisioner.ProvisioningException("falha", null))
                    .when(provisioner).ensureLatestVersion(any());
            assertThatCode(() -> command.run()).doesNotThrowAnyException();
        }
    }
}
