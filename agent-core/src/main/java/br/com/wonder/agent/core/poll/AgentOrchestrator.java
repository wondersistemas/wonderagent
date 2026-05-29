package br.com.wonder.agent.core.poll;

import br.com.wonder.agent.central.CentralClient;
import br.com.wonder.agent.core.db.DatabaseVersionReader;
import br.com.wonder.agent.core.deploy.DeployPipeline;
import br.com.wonder.agent.core.download.ArtifactDownloader;
import br.com.wonder.agent.core.download.ZsyncChecksumReader;
import br.com.wonder.agent.core.provision.WildflyProvisioner;
import br.com.wonder.agent.model.config.AgentStatusReport;
import br.com.wonder.agent.model.config.DesiredState;
import br.com.wonder.agent.model.deploy.Artifact;
import br.com.wonder.agent.model.deploy.DeployResult;
import br.com.wonder.agent.model.driver.RuntimeDriver;
import br.com.wonder.agent.model.state.RuntimeState;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loop principal do agente.
 * Executa no intervalo configurado em agent.poll-interval-seconds.
 *
 * Fluxo: fetchDesiredState → compareVersions → [download e deploy se necessário] → reportStatus
 */
@Slf4j
@ApplicationScoped
public class AgentOrchestrator {

    @Inject @RestClient CentralClient centralClient;
    @Inject DeployPipeline deployPipeline;
    @Inject RuntimeDriver driver;
    @Inject ArtifactDownloader artifactDownloader;
    @Inject WildflyProvisioner wildflyProvisioner;
    @Inject DatabaseVersionReader databaseVersionReader;
    @Inject ZsyncChecksumReader zsyncChecksumReader;

    @ConfigProperty(name = "agent.client-id")
    String clientId;

    @ConfigProperty(name = "agent.version")
    String agentVersion;

    private final AtomicBoolean running = new AtomicBoolean(false);

    // Só true quando iniciado como serviço (WonderAgentCommand.run()).
    // Subcomandos single-shot (check, update, deploy) nunca ativam o scheduler.
    private final AtomicBoolean serviceMode = new AtomicBoolean(false);

    /** Ativa o modo serviço — o scheduler passa a executar os ciclos de poll. */
    public void startServiceMode() {
        serviceMode.set(true);
    }

    /** Executa um ciclo imediato bloqueando o scheduler enquanto roda. */
    public void pollNow() {
        pollNow(msg -> {});
    }

    /**
     * Executa um ciclo imediato. {@code progress} recebe mensagens de andamento
     * para exibição no console (ex: CLI check).
     */
    public void pollNow(java.util.function.Consumer<String> progress) {
        running.set(true);
        try {
            doPoll(progress);
        } finally {
            running.set(false);
        }
    }

    @Scheduled(every = "${agent.poll-interval}")
    public void poll() {
        if (!serviceMode.get()) {
            log.debug("Modo serviço inativo — scheduler ignorado");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("Ciclo anterior ainda em execução, pulando — clientId={}", clientId);
            return;
        }
        log.debug("Iniciando ciclo de poll — clientId={}", clientId);
        try {
            doPoll(msg -> {});
        } finally {
            running.set(false);
        }
    }

    /**
     * Consulta o servidor central e baixa o artefato se houver nova versão.
     *
     * @return o artefato baixado, ou vazio se já está na última versão
     */
    public java.util.Optional<Artifact> update(java.util.function.Consumer<String> progress) {
        try {
            progress.accept("Verificando versão disponível no servidor central...");
            DesiredState desired = centralClient.fetchDesiredState(clientId);

            if (desired.wildflyVersion() != null) {
                try {
                    wildflyProvisioner.ensureVersion(desired.wildflyVersion(), progress);
                } catch (WildflyProvisioner.ProvisioningException e) {
                    log.error("Falha ao provisionar WildFly {}: {}", desired.wildflyVersion(), e.getMessage());
                }
            }

            String installedVersion = driver.getInstalledVersion();
            log.trace("update: desiredVersion={} installedVersion={} warUrl={}",
                    desired.version(), installedVersion, desired.warUrl());

            if (desired.version().equals(installedVersion)) {
                String zsyncUrl = desired.warUrl().replaceAll("\\.war$", ".zsync");
                log.trace("Versões iguais — verificando checksum via .zsync: {}", zsyncUrl);

                java.util.Optional<String> remoteChecksum = zsyncChecksumReader.readSha1(zsyncUrl);
                java.util.Optional<String> localChecksum  = driver.getInstalledChecksum()
                        .or(() -> artifactDownloader.readCachedSha1(desired.warUrl()));
                log.trace("Checksum remoto: {} | Checksum local (deploy+cache): {}",
                        remoteChecksum.orElse("ausente"), localChecksum.orElse("ausente"));

                boolean checksumMatch = remoteChecksum.isPresent() && localChecksum.isPresent()
                        && remoteChecksum.get().equals(localChecksum.get());
                boolean remoteUnavailable = remoteChecksum.isEmpty();

                if (checksumMatch) {
                    log.debug("Versão {} e checksum SHA-1 conferem — sem atualização necessária", installedVersion);
                    progress.accept("Já está na última versão: " + installedVersion);
                    reportStatus(desired.version(), null);
                    return java.util.Optional.empty();
                }

                if (remoteUnavailable) {
                    log.debug("Checksum remoto indisponível — confiando apenas na versão {}", installedVersion);
                    progress.accept("Já está na última versão: " + installedVersion);
                    reportStatus(desired.version(), null);
                    return java.util.Optional.empty();
                }

                // checksum remoto disponível mas local ausente (deploy manual ou versão anterior do agente)
                // ou checksums divergem — força re-download
                if (localChecksum.isEmpty()) {
                    log.warn("Checksum local ausente para versão {} — forçando re-download para garantir integridade",
                            installedVersion);
                    progress.accept("Checksum local ausente para versão " + installedVersion + " — forçando re-download");
                } else {
                    log.warn("Versão {} instalada mas checksum diverge (remote={} local={}) — forçando re-deploy",
                            installedVersion, remoteChecksum.get(), localChecksum.get());
                    progress.accept("Checksum diverge para versão " + installedVersion + " — forçando re-download");
                }
            }

            log.info("Atualização disponível: {} → {}", installedVersion, desired.version());
            if (installedVersion == null) {
                progress.accept("Nova versão disponível: " + desired.version() + " (nenhuma versão instalada)");
            } else {
                progress.accept("Nova versão disponível: " + installedVersion + " → " + desired.version());
            }
            progress.accept("Iniciando download...");

            try {
                Artifact artifact = artifactDownloader.download(toArtifact(desired), progress,
                        driver.getInstalledArtifactPath());
                progress.accept("Download concluído.");
                return java.util.Optional.of(artifact);
            } catch (ArtifactDownloader.DownloadException e) {
                log.error("Falha no download do artefato: {}", e.getMessage(), e);
                progress.accept("ERRO no download: " + e.getMessage());
                reportStatus(installedVersion, null);
                return java.util.Optional.empty();
            }

        } catch (Exception e) {
            log.error("Erro ao verificar atualização", e);
            progress.accept("ERRO: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return java.util.Optional.empty();
        }
    }

    /**
     * Executa o deploy de um artefato já baixado.
     */
    public void deployArtifact(Artifact artifact, java.util.function.Consumer<String> progress) {
        try {
            progress.accept("Aplicando deploy...");
            DeployResult result = deployPipeline.execute(artifact);
            String finalVersion = driver.getInstalledVersion();
            if (result.success()) {
                progress.accept("Nova versão instalada com sucesso: " + finalVersion);
            } else {
                String reason = result.failureReason() != null ? ": " + result.failureReason() : "";
                progress.accept("FALHA no deploy da versão " + artifact.version() + reason);
            }
            reportStatus(finalVersion, result);
        } catch (Exception e) {
            log.error("Erro inesperado ao fazer deploy da versão {}", artifact.version(), e);
            progress.accept("ERRO: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private void doPoll(java.util.function.Consumer<String> progress) {
        update(progress).ifPresent(artifact -> deployArtifact(artifact, progress));
    }

    private void reportStatus(String installedVersion, DeployResult lastResult) {
        RuntimeState state = driver.detectState();
        boolean healthy = driver.healthCheck().healthy();
        String dbVersion = databaseVersionReader.readDbVersion().orElse(null);

        AgentStatusReport report = new AgentStatusReport(
                Instant.now(),
                state,
                installedVersion,
                healthy,
                lastResult != null ? lastResult.deployedAt() : null,
                lastResult != null ? (lastResult.success() ? "SUCCESS" : "FAILURE") : null,
                agentVersion,
                dbVersion
        );

        try {
            centralClient.reportStatus(clientId, report);
        } catch (Exception e) {
            log.warn("Falha ao reportar status ao servidor central: {}", e.getMessage());
        }

        if (lastResult != null && !lastResult.success()) {
            try {
                centralClient.reportDeployResult(clientId, lastResult);
            } catch (Exception e) {
                log.warn("Falha ao reportar resultado de deploy ao servidor central: {}", e.getMessage());
            }
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
