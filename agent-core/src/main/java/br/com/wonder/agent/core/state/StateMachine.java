package br.com.wonder.agent.core.state;

import br.com.wonder.agent.model.driver.RuntimeDriver;
import br.com.wonder.agent.model.state.RuntimeState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * Gerencia transições de estado do runtime.
 * Toda ação de deploy passa por aqui antes de executar.
 *
 * Ver docs/architecture/state-machine.md para diagrama completo.
 */
@Slf4j
@ApplicationScoped
public class StateMachine {

    @Inject
    RuntimeDriver driver;

    /**
     * Detecta o estado atual e, se necessário, executa recuperação
     * para trazer o runtime a um estado conhecido (STOPPED ou RUNNING).
     *
     * @return estado após recuperação — nunca UNKNOWN, HUNG ou PARTIAL
     */
    public RuntimeState detectAndRecover() {
        RuntimeState state = driver.detectState();
        log.info("Estado detectado: {}", state);

        if (!state.needsRecovery()) {
            return state;
        }

        log.warn("Estado requer recuperação: {} — iniciando forceKill", state);
        boolean killed = driver.forceKill();

        if (!killed) {
            log.error("forceKill falhou — runtime em estado indeterminado");
            return RuntimeState.UNKNOWN;
        }

        state = driver.detectState();
        log.info("Estado após recuperação: {}", state);
        return state;
    }

    public boolean canDeploy(RuntimeState state) {
        return state == RuntimeState.STOPPED || state == RuntimeState.RUNNING
                || state == RuntimeState.UP_TO_DATE || state == RuntimeState.DEPLOY_FAILED;
    }
}
