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
        RuntimeState stateBefore = stateMachine.detectAndRecover();

        if (!stateMachine.canDeploy(stateBefore)) {
            return DeployResult.failure(artifact.version(), Duration.between(start, Instant.now()),
                    stateBefore, stateBefore,
                    "Estado inicial não permite deploy: " + stateBefore);
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
            return deployResult;
        }

        log.info("Iniciando runtime após deploy");
        if (!driver.start()) {
            return DeployResult.failure(artifact.version(), Duration.between(start, Instant.now()),
                    stateBefore, driver.detectState(),
                    "Runtime não iniciou dentro do timeout após deploy");
        }

        HealthStatus health = driver.healthCheckWithRetry();
        if (!health.healthy()) {
            return DeployResult.failure(artifact.version(), Duration.between(start, Instant.now()),
                    stateBefore, driver.detectState(),
                    "Health check falhou após deploy: " + health.details());
        }

        log.info("Deploy concluído com sucesso: {}", artifact.version());
        return DeployResult.success(artifact.version(), Duration.between(start, Instant.now()),
                stateBefore, RuntimeState.RUNNING);
    }
}
