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
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
    @Inject WildflyProvisioner wildflyProvisioner;
    @Inject DatabaseVersionReader databaseVersionReader;
    @Inject ZsyncChecksumReader zsyncChecksumReader;

    @ConfigProperty(name = "agent.client-id")
    String clientId;

    @ConfigProperty(name = "agent.version")
    String agentVersion;

    @ConfigProperty(name = "agent.poll-interval", defaultValue = "PT300S")
    String pollInterval;

    private final AtomicBoolean running = new AtomicBoolean(false);

    // Só true quando iniciado como serviço (WonderAgentCommand.run()).
    // Subcomandos single-shot (check, update, deploy) nunca ativam o scheduler.
    private final AtomicBoolean serviceMode = new AtomicBoolean(false);

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private volatile ScheduledExecutorService scheduler;

    void onShutdown(@Observes ShutdownEvent event) {
        shuttingDown.set(true);
        log.debug("Shutdown detectado — novos ciclos de poll serão bloqueados");
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
        }
    }

    /**
     * Ativa o modo serviço — apenas seta a flag.
     * O scheduler é iniciado por {@link #startScheduler()}.
     * Separado para facilitar testes: testes chamam startServiceMode() sem disparar threads reais.
     */
    public void startServiceMode() {
        serviceMode.set(true);
    }

    /**
     * Inicia o ScheduledExecutorService do poll loop.
     * Chamado pelo WonderAgentCommand.run() após startServiceMode().
     */
    public void startScheduler() {
        long intervalSeconds = parsePollIntervalSeconds();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wonderagent-poll");
            t.setDaemon(false);
            return t;
        });
        // Primeiro ciclo imediato, depois repete no intervalo configurado
        scheduler.scheduleWithFixedDelay(this::poll, 0, intervalSeconds, TimeUnit.SECONDS);
        log.info("Modo serviço ativo — poll a cada {}s", intervalSeconds);
    }

    /** Executa um ciclo imediato bloqueando enquanto roda. */
    public void pollNow() {
        pollNow(msg -> {});
    }

    /**
     * Executa um ciclo imediato. {@code progress} recebe mensagens de andamento
     * para exibição no console (ex: CLI check).
     */
    public void pollNow(Consumer<String> progress) {
        running.set(true);
        try {
            doPoll(progress);
        } finally {
            running.set(false);
        }
    }

    void poll() {
        if (!serviceMode.get()) {
            log.debug("Modo serviço inativo — poll ignorado");
            return;
        }
        if (shuttingDown.get()) {
            log.debug("Shutdown em curso — ciclo de poll cancelado");
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
    public Optional<Artifact> update(Consumer<String> progress) {
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

                Optional<String> remoteChecksum = zsyncChecksumReader.readSha1(zsyncUrl);
                Optional<String> localChecksum  = driver.getInstalledChecksum()
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
                    return Optional.empty();
                }

                if (remoteUnavailable) {
                    log.debug("Checksum remoto indisponível — confiando apenas na versão {}", installedVersion);
                    progress.accept("Já está na última versão: " + installedVersion);
                    reportStatus(desired.version(), null);
                    return Optional.empty();
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
                return Optional.of(artifact);
            } catch (ArtifactDownloader.DownloadException e) {
                log.error("Falha no download do artefato: {}", e.getMessage(), e);
                progress.accept("ERRO no download: " + e.getMessage());
                reportStatus(installedVersion, null);
                return Optional.empty();
            }

        } catch (Exception e) {
            log.error("Erro ao verificar atualização", e);
            progress.accept("ERRO: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return Optional.empty();
        }
    }

    /**
     * Executa o deploy de um artefato já baixado.
     */
    public void deployArtifact(Artifact artifact, Consumer<String> progress) {
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

    private void doPoll(Consumer<String> progress) {
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

    private long parsePollIntervalSeconds() {
        try {
            return Duration.parse(pollInterval).getSeconds();
        } catch (Exception e) {
            log.warn("Formato inválido para agent.poll-interval '{}', usando 300s", pollInterval);
            return 300L;
        }
    }
}
