package br.com.wonder.agent.central;

import br.com.wonder.agent.model.config.AgentStatusReport;
import br.com.wonder.agent.model.config.DesiredState;
import br.com.wonder.agent.model.deploy.DeployResult;

/**
 * Cliente REST para o servidor central.
 * Implementado por HttpCentralClient usando java.net.http.HttpClient.
 * Configurado via agent.central-url e agent.jwt-token em application.yaml.
 *
 * Ver docs/api/central-api.md para contrato completo dos endpoints.
 */
public interface CentralClient {

    DesiredState fetchDesiredState(String clientId);

    void reportStatus(String clientId, AgentStatusReport report);

    void reportDeployResult(String clientId, DeployResult result);
}
