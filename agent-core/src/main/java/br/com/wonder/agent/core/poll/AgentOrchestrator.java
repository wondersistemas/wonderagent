package br.com.wonder.agent.core.poll;

import br.com.wonder.agent.central.CentralClient;
import br.com.wonder.agent.core.deploy.DeployPipeline;
import br.com.wonder.agent.core.download.ArtifactDownloader;
import br.com.wonder.agent.model.config.AgentStatusReport;
import br.com.wonder.agent.model.config.DesiredState;
import br.com.wonder.agent.model.deploy.Artifact;
import br.com.wonder.agent.model.deploy.DeployResult;
import br.com.wonder.agent.model.driver.RuntimeDriver;
import br.com.wonder.agent.model.state.RuntimeState;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;

/**
 * Loop principal do agente.
 * Executa no intervalo configurado em agent.poll-interval-seconds.
 *
 * Fluxo: fetchDesiredState → compareVersions → [download e deploy se necessário] → reportStatus
 */
@Slf4j
@ApplicationScoped
public class AgentOrchestrator {

    @Inject CentralClient centralClient;
    @Inject DeployPipeline deployPipeline;
    @Inject RuntimeDriver driver;
    @Inject ArtifactDownloader artifactDownloader;

    @ConfigProperty(name = "agent.client-id")
    String clientId;

    @ConfigProperty(name = "agent.version")
    String agentVersion;

    @Scheduled(every = "{agent.poll-interval-seconds}s")
    public void poll() {
        log.debug("Iniciando ciclo de poll — clientId={}", clientId);
        try {
            DesiredState desired = centralClient.fetchDesiredState(clientId);
            String installedVersion = driver.getInstalledVersion();

            if (desired.version().equals(installedVersion)) {
                log.debug("Versão atual {} já é a desejada", installedVersion);
                reportStatus(desired.version(), null);
                return;
            }

            log.info("Atualização disponível: {} → {}", installedVersion, desired.version());
            Artifact artifact = toArtifact(desired);

            // Baixar artefato do Nexus
            try {
                artifact = artifactDownloader.download(artifact);
            } catch (ArtifactDownloader.DownloadException e) {
                log.error("Falha no download do artefato: {}", e.getMessage());
                // Reportar erro sem tentar deploy
                reportStatus(installedVersion, null);
                return;
            }

            DeployResult result = deployPipeline.execute(artifact);
            reportStatus(driver.getInstalledVersion(), result);

        } catch (Exception e) {
            log.error("Erro no ciclo de poll", e);
        }
    }

    private void reportStatus(String installedVersion, DeployResult lastResult) {
        RuntimeState state = driver.detectState();
        boolean healthy = driver.healthCheck().healthy();

        AgentStatusReport report = new AgentStatusReport(
                Instant.now(),
                state,
                installedVersion,
                healthy,
                lastResult != null ? lastResult.deployedAt() : null,
                lastResult != null ? (lastResult.success() ? "SUCCESS" : "FAILURE") : null,
                agentVersion
        );

        centralClient.reportStatus(clientId, report);

        if (lastResult != null && !lastResult.success()) {
            centralClient.reportDeployResult(clientId, lastResult);
        }
    }

    private Artifact toArtifact(DesiredState desired) {
        return new Artifact(
                desired.artifactId(),
                desired.version(),
                desired.warUrl(),
                null  // localFile será preenchido por ArtifactDownloader
        );
    }
}
