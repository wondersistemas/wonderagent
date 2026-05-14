package br.com.wonder.agent.model.deploy;

import br.com.wonder.agent.model.state.RuntimeState;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DeployResultTest {

    @Test
    void success_preencheFieldsCorretamente() {
        DeployResult result = DeployResult.success(
                "2.5.0", Duration.ofSeconds(30),
                RuntimeState.STOPPED, RuntimeState.RUNNING);

        assertThat(result.success()).isTrue();
        assertThat(result.version()).isEqualTo("2.5.0");
        assertThat(result.duration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(result.stateBefore()).isEqualTo(RuntimeState.STOPPED);
        assertThat(result.stateAfter()).isEqualTo(RuntimeState.RUNNING);
        assertThat(result.failureReason()).isNull();
        assertThat(result.deployedAt()).isNotNull();
    }

    @Test
    void failure_preencheFieldsComMotivo() {
        DeployResult result = DeployResult.failure(
                "2.5.0", Duration.ofSeconds(10),
                RuntimeState.RUNNING, RuntimeState.HUNG,
                "Timeout ao iniciar");

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("Timeout ao iniciar");
        assertThat(result.stateAfter()).isEqualTo(RuntimeState.HUNG);
    }
}
