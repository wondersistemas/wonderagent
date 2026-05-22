package br.com.wonder.agent.cli.command;

import br.com.wonder.agent.core.poll.AgentOrchestrator;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WonderAgentCommandTest {

    @Nested
    class CheckCommandTest {
        @Mock AgentOrchestrator orchestrator;
        @InjectMocks WonderAgentCommand.CheckCommand command;

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
}
