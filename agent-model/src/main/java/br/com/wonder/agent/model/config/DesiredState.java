package br.com.wonder.agent.model.config;

/**
 * Estado desejado retornado pelo servidor central para esta instalação.
 * Ver docs/api/central-api.md — GET /api/v1/agents/{clientId}/desired-state
 */
public record DesiredState(
    String artifactId,
    String version,
    String warUrl,          // URL completa do WAR no Reposilite
    String runtimeType,
    String wildflyVersion,  // null = não gerenciar WildFly; não-null = versão desejada
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
