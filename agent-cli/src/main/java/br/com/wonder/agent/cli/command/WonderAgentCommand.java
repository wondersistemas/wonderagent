package br.com.wonder.agent.cli.command;

import br.com.wonder.agent.core.deploy.DeployPipeline;
import br.com.wonder.agent.core.poll.AgentOrchestrator;
import br.com.wonder.agent.model.driver.RuntimeDriver;
import br.com.wonder.agent.model.state.RuntimeState;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.*;

/**
 * Comandos CLI do agente. Entry point: wonderagent.exe [comando]
 * Sem comando: inicia em modo serviço (bloqueante).
 *
 * Ver docs/api/agent-cli.md para referência completa.
 */
@Slf4j
@Command(
    name = "wonderagent",
    mixinStandardHelpOptions = true,
    version = "${agent.version}",
    description = "WonderAgent — agente pull de deploy on-premises",
    subcommands = {
        WonderAgentCommand.StatusCommand.class,
        WonderAgentCommand.DetectCommand.class,
        WonderAgentCommand.CheckCommand.class,
        WonderAgentCommand.InstallCommand.class,
        WonderAgentCommand.UninstallCommand.class,
        WonderAgentCommand.ConfigCommand.class,
    }
)
public class WonderAgentCommand implements Runnable {

    @Inject AgentOrchestrator orchestrator;

    @Override
    public void run() {
        log.info("Iniciando WonderAgent em modo serviço");
        // O scheduler do Quarkus mantém o processo vivo
    }

    @Command(name = "status", description = "Mostra estado atual do runtime e versão instalada")
    static class StatusCommand implements Runnable {
        @Inject RuntimeDriver driver;

        @Override
        public void run() {
            RuntimeState state = driver.detectState();
            String version = driver.getInstalledVersion();
            System.out.printf("Estado:  %s%n", state);
            System.out.printf("Versão:  %s%n", version != null ? version : "(desconhecida)");
            System.out.printf("Saúde:   %s%n", driver.healthCheck().healthy() ? "OK" : "FALHOU");
        }
    }

    @Command(name = "detect", description = "Detecta e imprime o estado do runtime sem agir")
    static class DetectCommand implements Runnable {
        @Inject RuntimeDriver driver;

        @Override
        public void run() {
            System.out.println(driver.detectState());
        }
    }

    @Command(name = "check", description = "Executa um ciclo de poll único: verifica e aplica se necessário")
    static class CheckCommand implements Runnable {
        @Inject AgentOrchestrator orchestrator;

        @Override
        public void run() {
            orchestrator.poll();
        }
    }

    @Command(name = "install", description = "Registra wonderagent como serviço Windows via NSSM")
    static class InstallCommand implements Runnable {
        @Override
        public void run() {
            // TODO: invocar nssm install wonderagent <path-to-exe>
            System.out.println("Instalando serviço Windows... (não implementado)");
        }
    }

    @Command(name = "uninstall", description = "Remove o serviço Windows")
    static class UninstallCommand implements Runnable {
        @Override
        public void run() {
            // TODO: invocar nssm remove wonderagent confirm
            System.out.println("Removendo serviço Windows... (não implementado)");
        }
    }

    @Command(name = "config", description = "Operações de configuração",
             subcommands = ConfigCommand.ShowCommand.class)
    static class ConfigCommand implements Runnable {
        @Override public void run() { System.out.println("Use: config show"); }

        @Command(name = "show", description = "Exibe a configuração ativa")
        static class ShowCommand implements Runnable {
            @Override
            public void run() {
                // TODO: imprimir application.yaml resolvido
                System.out.println("Configuração ativa: (não implementado)");
            }
        }
    }
}
