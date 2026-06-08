package br.com.wonder.agent.model.deploy;

import br.com.wonder.agent.model.state.RuntimeState;

import java.time.Duration;
import java.time.Instant;

public record DeployResult(
    boolean success,
    String version,
    long durationSeconds,
    Instant deployedAt,
    RuntimeState stateBefore,
    RuntimeState stateAfter,
    String failureReason     // null se sucesso
) {
    public static DeployResult success(String version, Duration duration,
                                       RuntimeState before, RuntimeState after) {
        return new DeployResult(true, version, duration.toSeconds(), Instant.now(), before, after, null);
    }

    public static DeployResult failure(String version, Duration duration,
                                       RuntimeState before, RuntimeState after, String reason) {
        return new DeployResult(false, version, duration.toSeconds(), Instant.now(), before, after, reason);
    }
}
