package br.com.wonder.agent.model.state;

/**
 * Estados possíveis de um runtime gerenciado pelo agente.
 * Ver docs/architecture/state-machine.md para diagrama de transições.
 */
public enum RuntimeState {

    /** Estado inicial ou após restart do agente — ainda não verificado. */
    UNKNOWN,

    /** Processo não existe e porta fechada. */
    STOPPED,

    /** Processo existe mas não responde (porta fechada ou API muda). */
    HUNG,

    /** Deployment parcial ou corrompido detectado. */
    PARTIAL,

    /** Processo respondendo, deployment OK, qualquer versão. */
    RUNNING,

    /** RUNNING + versão instalada é a versão desejada. */
    UP_TO_DATE,

    /** Deploy em andamento (transitório). */
    DEPLOYING,

    /** Última tentativa de deploy falhou. Runtime pode estar em qualquer estado. */
    DEPLOY_FAILED;

    public boolean isActionable() {
        return this != DEPLOYING;
    }

    public boolean needsRecovery() {
        return this == HUNG || this == PARTIAL || this == UNKNOWN;
    }
}
