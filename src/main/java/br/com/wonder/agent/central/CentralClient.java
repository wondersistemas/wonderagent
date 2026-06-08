package br.com.wonder.agent.central;

import br.com.wonder.agent.model.config.AgentStatusReport;
import br.com.wonder.agent.model.config.DesiredState;
import br.com.wonder.agent.model.deploy.DeployResult;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Cliente REST para o servidor central.
 * Configurado via agent.central-url em application.yaml.
 *
 * Ver docs/api/central-api.md para contrato completo dos endpoints.
 */
@RegisterRestClient(configKey = "central-api")
@Path("/api/v1/agents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface CentralClient {

    @GET
    @Path("/{clientId}/desired-state")
    DesiredState fetchDesiredState(@PathParam("clientId") String clientId);

    @POST
    @Path("/{clientId}/status")
    void reportStatus(@PathParam("clientId") String clientId, AgentStatusReport report);

    @POST
    @Path("/{clientId}/deploy-results")
    void reportDeployResult(@PathParam("clientId") String clientId, DeployResult result);
}
