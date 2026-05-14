package br.com.wonder.agent.model.deploy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthStatusTest {

    @Test
    void ok_criaStatusSaudavel() {
        HealthStatus status = HealthStatus.ok();

        assertThat(status.healthy()).isTrue();
        assertThat(status.details()).isNull();
        assertThat(status.checkedAt()).isNotNull();
    }

    @Test
    void unhealthy_criaStatusComMotivo() {
        HealthStatus status = HealthStatus.unhealthy("Connection refused");

        assertThat(status.healthy()).isFalse();
        assertThat(status.details()).isEqualTo("Connection refused");
        assertThat(status.checkedAt()).isNotNull();
    }
}
