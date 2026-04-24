package br.com.wonder.agent.model.config;

/**
 * Estado desejado retornado pelo servidor central para esta instalação.
 * Ver docs/api/central-api.md — GET /api/v1/agents/{clientId}/desired-state
 */
public record DesiredState(
    String groupId,
    String artifactId,
    String version,
    String extension,
    String nexusBaseUrl,
    String nexusRepository,
    String runtimeType,
    DeployConfig deployConfig
) {
    public record DeployConfig(
        String deployPath,
        String healthCheckUrl,
        int healthCheckTimeoutSeconds,
        int startTimeoutSeconds,
        int stopTimeoutSeconds,
        boolean rollbackOnFailure
    ) {}
}
