package br.com.wonder.agent.model.config;

import br.com.wonder.agent.model.state.RuntimeState;

import java.time.Instant;

/**
 * Payload do relatório de estado enviado ao servidor central.
 * Ver docs/api/central-api.md — POST /api/v1/agents/{clientId}/status
 */
public record AgentStatusReport(
    Instant reportedAt,
    RuntimeState runtimeState,
    String installedVersion,
    boolean healthy,
    Instant lastDeployAt,
    String lastDeployResult,    // "SUCCESS", "FAILURE", null se nunca deployou
    String agentVersion
) {}
