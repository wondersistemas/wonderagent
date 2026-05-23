package br.com.wonder.agent.model.driver;

import br.com.wonder.agent.model.deploy.Artifact;
import br.com.wonder.agent.model.deploy.DeployResult;
import br.com.wonder.agent.model.deploy.HealthStatus;
import br.com.wonder.agent.model.state.RuntimeState;

/**
 * Contrato que todo driver de runtime deve implementar.
 *
 * Regras:
 * - detectState() nunca assume — sempre verifica o estado real.
 * - Nenhum método de ação (deploy, start, stop) é chamado sem detectState() prévio.
 * - forceKill() é reservado para estados HUNG e PARTIAL.
 *
 * Ver docs/architecture/driver-interface.md para guia de implementação.
 * Ver docs/development/adding-a-driver.md para passo a passo de novo driver.
 */
public interface RuntimeDriver {

    /** Identificador do tipo: "wildfly", "quarkus", "proxy". */
    String getRuntimeType();

    /**
     * Detecta o estado real do runtime sem efeitos colaterais.
     * Nunca lança exceção — encapsula falhas em UNKNOWN ou HUNG.
     */
    RuntimeState detectState();

    /** Retorna a versão do artefato atualmente instalado, ou null se não detectável. */
    String getInstalledVersion();

    /** Realiza o deploy do artefato. Assume que o runtime está STOPPED. */
    DeployResult deploy(Artifact artifact);

    /** Inicia o runtime. Retorna true se confirmado como RUNNING dentro do timeout. */
    boolean start();

    /** Para o runtime graciosamente. Retorna true se confirmado como STOPPED. */
    boolean stop();

    /**
     * Força encerramento do processo (kill -9 equivalente).
     * Usar apenas quando stop() falhar ou estado for HUNG/PARTIAL.
     */
    boolean forceKill();

    /** Verifica saúde da aplicação (endpoint HTTP, porta, etc.). */
    HealthStatus healthCheck();

    /**
     * Aguarda o health check passar com retry, para uso logo após deploy+start
     * enquanto o runtime ainda está inicializando o artefato.
     * Implementação padrão: uma única tentativa (sem retry).
     * Drivers podem sobrescrever para retry configurável.
     */
    default HealthStatus healthCheckWithRetry() {
        return healthCheck();
    }
}
