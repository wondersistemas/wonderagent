package br.com.wonder.agent.core.state;

import br.com.wonder.agent.model.driver.RuntimeDriver;
import br.com.wonder.agent.model.state.RuntimeState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StateMachineTest {

    @Mock RuntimeDriver driver;
    @InjectMocks StateMachine stateMachine;

    @Test
    void detectAndRecover_quandoStopped_retornaSemForceKill() {
        when(driver.detectState()).thenReturn(RuntimeState.STOPPED);

        RuntimeState result = stateMachine.detectAndRecover();

        assertThat(result).isEqualTo(RuntimeState.STOPPED);
        verify(driver, never()).forceKill();
    }

    @Test
    void detectAndRecover_quandoRunning_retornaSemForceKill() {
        when(driver.detectState()).thenReturn(RuntimeState.RUNNING);

        RuntimeState result = stateMachine.detectAndRecover();

        assertThat(result).isEqualTo(RuntimeState.RUNNING);
        verify(driver, never()).forceKill();
    }

    @Test
    void detectAndRecover_quandoHung_executaForceKillERetornaNovoEstado() {
        when(driver.detectState())
                .thenReturn(RuntimeState.HUNG)
                .thenReturn(RuntimeState.STOPPED);
        when(driver.forceKill()).thenReturn(true);

        RuntimeState result = stateMachine.detectAndRecover();

        assertThat(result).isEqualTo(RuntimeState.STOPPED);
        verify(driver).forceKill();
    }

    @Test
    void detectAndRecover_quandoPartial_executaForceKill() {
        when(driver.detectState())
                .thenReturn(RuntimeState.PARTIAL)
                .thenReturn(RuntimeState.STOPPED);
        when(driver.forceKill()).thenReturn(true);

        stateMachine.detectAndRecover();

        verify(driver).forceKill();
    }

    @Test
    void detectAndRecover_quandoUnknown_executaForceKill() {
        when(driver.detectState())
                .thenReturn(RuntimeState.UNKNOWN)
                .thenReturn(RuntimeState.STOPPED);
        when(driver.forceKill()).thenReturn(true);

        stateMachine.detectAndRecover();

        verify(driver).forceKill();
    }

    @Test
    void detectAndRecover_quandoForceKillFalha_retornaUnknown() {
        when(driver.detectState()).thenReturn(RuntimeState.HUNG);
        when(driver.forceKill()).thenReturn(false);

        RuntimeState result = stateMachine.detectAndRecover();

        assertThat(result).isEqualTo(RuntimeState.UNKNOWN);
        // Não chama detectState segunda vez — retorna UNKNOWN diretamente
        verify(driver, times(1)).detectState();
    }

    @Test
    void canDeploy_permiteEstadosValidos() {
        assertThat(stateMachine.canDeploy(RuntimeState.STOPPED)).isTrue();
        assertThat(stateMachine.canDeploy(RuntimeState.RUNNING)).isTrue();
        assertThat(stateMachine.canDeploy(RuntimeState.UP_TO_DATE)).isTrue();
        assertThat(stateMachine.canDeploy(RuntimeState.DEPLOY_FAILED)).isTrue();
    }

    @Test
    void canDeploy_bloqueiaEstadosInvalidos() {
        assertThat(stateMachine.canDeploy(RuntimeState.HUNG)).isFalse();
        assertThat(stateMachine.canDeploy(RuntimeState.PARTIAL)).isFalse();
        assertThat(stateMachine.canDeploy(RuntimeState.UNKNOWN)).isFalse();
        assertThat(stateMachine.canDeploy(RuntimeState.DEPLOYING)).isFalse();
    }
}
