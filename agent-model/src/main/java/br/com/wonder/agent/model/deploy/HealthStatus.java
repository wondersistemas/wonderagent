package br.com.wonder.agent.model.deploy;

import java.time.Instant;

public record HealthStatus(
    boolean healthy,
    String details,
    Instant checkedAt
) {
    public static HealthStatus healthy() {
        return new HealthStatus(true, null, Instant.now());
    }

    public static HealthStatus unhealthy(String reason) {
        return new HealthStatus(false, reason, Instant.now());
    }
}
