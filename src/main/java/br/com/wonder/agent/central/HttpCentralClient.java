package br.com.wonder.agent.central;

import br.com.wonder.agent.model.config.AgentStatusReport;
import br.com.wonder.agent.model.config.DesiredState;
import br.com.wonder.agent.model.deploy.DeployResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

@Slf4j
@ApplicationScoped
public class HttpCentralClient implements CentralClient {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new ParameterNamesModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @ConfigProperty(name = "agent.central-url", defaultValue = "https://deploy.wonder.com.br")
    String centralUrl;

    @ConfigProperty(name = "agent.jwt-token")
    Optional<String> jwtToken;

    @ConfigProperty(name = "quarkus.rest-client.central-api.connect-timeout", defaultValue = "10000")
    long connectTimeoutMs;

    @ConfigProperty(name = "quarkus.rest-client.central-api.read-timeout", defaultValue = "30000")
    long readTimeoutMs;

    private volatile HttpClient httpClient;

    private HttpClient client() {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                            .build();
                }
            }
        }
        return httpClient;
    }

    @Override
    public DesiredState fetchDesiredState(String clientId) {
        URI uri = URI.create(centralUrl + "/api/v1/agents/" + clientId + "/desired-state");
        HttpRequest request = newRequestBuilder(uri)
                .GET()
                .timeout(Duration.ofMillis(readTimeoutMs))
                .build();
        try {
            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
            checkStatus(response, uri);
            return MAPPER.readValue(response.body(), DesiredState.class);
        } catch (CentralClientException e) {
            throw e;
        } catch (Exception e) {
            throw new CentralClientException("Falha ao buscar desired-state: " + uri, e);
        }
    }

    @Override
    public void reportStatus(String clientId, AgentStatusReport report) {
        URI uri = URI.create(centralUrl + "/api/v1/agents/" + clientId + "/status");
        try {
            String body = MAPPER.writeValueAsString(report);
            HttpRequest request = newRequestBuilder(uri)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .build();
            HttpResponse<Void> response = client().send(request, HttpResponse.BodyHandlers.discarding());
            checkStatus(response, uri);
        } catch (CentralClientException e) {
            throw e;
        } catch (Exception e) {
            throw new CentralClientException("Falha ao reportar status: " + uri, e);
        }
    }

    @Override
    public void reportDeployResult(String clientId, DeployResult result) {
        URI uri = URI.create(centralUrl + "/api/v1/agents/" + clientId + "/deploy-results");
        try {
            String body = MAPPER.writeValueAsString(result);
            HttpRequest request = newRequestBuilder(uri)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .build();
            HttpResponse<Void> response = client().send(request, HttpResponse.BodyHandlers.discarding());
            checkStatus(response, uri);
        } catch (CentralClientException e) {
            throw e;
        } catch (Exception e) {
            throw new CentralClientException("Falha ao reportar deploy result: " + uri, e);
        }
    }

    private HttpRequest.Builder newRequestBuilder(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .header("Accept", "application/json");
        jwtToken.filter(t -> !t.isBlank())
                .ifPresent(t -> builder.header("Authorization", "Bearer " + t));
        return builder;
    }

    private void checkStatus(HttpResponse<?> response, URI uri) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new CentralClientException(
                    "Servidor central retornou HTTP " + status + " para " + uri, null);
        }
    }

    public static class CentralClientException extends RuntimeException {
        public CentralClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
