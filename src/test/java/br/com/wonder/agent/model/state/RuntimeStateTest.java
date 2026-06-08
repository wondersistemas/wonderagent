package br.com.wonder.agent.model.state;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeStateTest {

    @Test
    void needsRecovery_retornaTrueParaEstadosProblematicos() {
        assertThat(RuntimeState.HUNG.needsRecovery()).isTrue();
        assertThat(RuntimeState.PARTIAL.needsRecovery()).isTrue();
        assertThat(RuntimeState.UNKNOWN.needsRecovery()).isTrue();
    }

    @Test
    void needsRecovery_retornaFalseParaEstadosNormais() {
        assertThat(RuntimeState.STOPPED.needsRecovery()).isFalse();
        assertThat(RuntimeState.RUNNING.needsRecovery()).isFalse();
        assertThat(RuntimeState.UP_TO_DATE.needsRecovery()).isFalse();
        assertThat(RuntimeState.DEPLOY_FAILED.needsRecovery()).isFalse();
        assertThat(RuntimeState.DEPLOYING.needsRecovery()).isFalse();
    }

    @Test
    void isActionable_retornaFalseApenasParaDeploying() {
        assertThat(RuntimeState.DEPLOYING.isActionable()).isFalse();
        for (RuntimeState state : RuntimeState.values()) {
            if (state != RuntimeState.DEPLOYING) {
                assertThat(state.isActionable()).as("%s deve ser actionable", state).isTrue();
            }
        }
    }
}
