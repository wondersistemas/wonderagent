package br.com.wonder.agent.core.deploy;

import br.com.wonder.agent.core.state.StateMachine;
import br.com.wonder.agent.model.deploy.Artifact;
import br.com.wonder.agent.model.deploy.DeployResult;
import br.com.wonder.agent.model.deploy.HealthStatus;
import br.com.wonder.agent.model.driver.RuntimeDriver;
import br.com.wonder.agent.model.state.RuntimeState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;

/**
 * Executa o pipeline completo de deploy:
 *   detectAndRecover → stop → deploy → start → healthCheck → report
 *
 * Ver docs/architecture/state-machine.md — seção "Deploy Pipeline".
 */
@Slf4j
@ApplicationScoped
public class DeployPipeline {

    @Inject
    StateMachine stateMachine;

    @Inject
    RuntimeDriver driver;

    public DeployResult execute(Artifact artifact) {
        Instant start = Instant.now();
        log.info("Iniciando deploy: versão={} artefato={}", artifact.version(), artifact.artifactId());
        RuntimeState stateBefore = stateMachine.detectAndRecover();

        if (!stateMachine.canDeploy(stateBefore)) {
            String reason = "Estado inicial não permite deploy: " + stateBefore;
            log.error("Deploy abortado — {}", reason);
            return DeployResult.failure(artifact.version(), Duration.between(start, Instant.now()),
                    stateBefore, stateBefore, reason);
        }

        if (stateBefore == RuntimeState.RUNNING || stateBefore == RuntimeState.UP_TO_DATE) {
            log.info("Parando runtime antes do deploy");
            if (!driver.stop()) {
                log.warn("stop() não confirmado — tentando forceKill");
                driver.forceKill();
            }
        }

        DeployResult deployResult = driver.deploy(artifact);
        if (!deployResult.success()) {
            log.error("Falha ao copiar artefato para deploy: {}", deployResult.failureReason());
            return deployResult;
        }

        // Âncora capturada imediatamente antes do start(): o enabled-time aceito pelo
        // scanner deve ser posterior a este instante, não ao Files.copy() que ocorreu
        // antes do processo WildFly existir — evita aceitar resultado de deploy anterior.
        Instant startedAt = Instant.now();
        log.info("Iniciando runtime após deploy");
        if (!driver.start()) {
            String reason = "Runtime não iniciou dentro do timeout após deploy";
            log.error("Deploy falhou — {} (versão={})", reason, artifact.version());
            return DeployResult.failure(artifact.version(), Duration.between(start, Instant.now()),
                    stateBefore, driver.detectState(), reason);
        }

        log.info("Aguardando confirmação do scanner para versão={}", artifact.version());
        if (!driver.waitForDeploymentReady(startedAt, 120)) {
            String reason = "Deployment scanner não confirmou artefato dentro do timeout";
            log.error("Deploy falhou — {} (versão={})", reason, artifact.version());
            return DeployResult.failure(artifact.version(), Duration.between(start, Instant.now()),
                    stateBefore, driver.detectState(), reason);
        }

        HealthStatus health = driver.healthCheckWithRetry();
        if (!health.healthy()) {
            String reason = "Health check falhou após deploy: " + health.details();
            log.error("Deploy falhou — {} (versão={})", reason, artifact.version());
            return DeployResult.failure(artifact.version(), Duration.between(start, Instant.now()),
                    stateBefore, driver.detectState(), reason);
        }

        Duration elapsed = Duration.between(start, Instant.now());
        log.info("Deploy concluído com sucesso: versão={} duração={}s", artifact.version(), elapsed.toSeconds());
        return DeployResult.success(artifact.version(), elapsed, stateBefore, RuntimeState.RUNNING);
    }
}
