package br.com.wonder.agent.cli.command;

import br.com.wonder.agent.core.poll.AgentOrchestrator;
import br.com.wonder.agent.model.driver.RuntimeDriver;
import br.com.wonder.agent.model.state.RuntimeState;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.ConfigProvider;
import picocli.CommandLine.*;

/**
 * Comandos CLI do agente. Entry point: wonderagent.exe [comando]
 * Sem comando: inicia em modo serviço (bloqueante).
 *
 * Ver docs/api/agent-cli.md para referência completa.
 */
@Slf4j
@TopCommand
@ApplicationScoped
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

    @ApplicationScoped
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

    @ApplicationScoped
    @Command(name = "detect", description = "Detecta e imprime o estado do runtime sem agir")
    static class DetectCommand implements Runnable {
        @Inject RuntimeDriver driver;

        @Override
        public void run() {
            System.out.println(driver.detectState());
        }
    }

    @ApplicationScoped
    @Command(name = "check", description = "Executa um ciclo de poll único: verifica e aplica se necessário")
    static class CheckCommand implements Runnable {
        @Inject AgentOrchestrator orchestrator;

        @Override
        public void run() {
            orchestrator.poll();
        }
    }

    @ApplicationScoped
    @Command(name = "install", description = "Registra wonderagent como serviço Windows via NSSM")
    static class InstallCommand implements Runnable {

        @Parameters(index = "0", description = "Caminho completo para o wonderagent.exe",
                    defaultValue = "C:\\ProgramData\\WonderAgent\\wonderagent.exe")
        String exePath;

        @Option(names = "--nssm", description = "Caminho para nssm.exe", defaultValue = "nssm")
        String nssmPath;

        @Override
        public void run() {
            try {
                log.info("Instalando serviço Windows via NSSM: {}", exePath);
                Process install = new ProcessBuilder(nssmPath, "install", "WonderAgent", exePath)
                        .inheritIO()
                        .start();
                int rc = install.waitFor();
                if (rc != 0) {
                    System.err.println("NSSM retornou código " + rc + " — verifique permissões de administrador.");
                    return;
                }

                // Configura diretório de trabalho e variáveis de ambiente
                new ProcessBuilder(nssmPath, "set", "WonderAgent", "AppDirectory",
                        "C:\\ProgramData\\WonderAgent").inheritIO().start().waitFor();
                new ProcessBuilder(nssmPath, "set", "WonderAgent", "AppStdout",
                        "C:\\ProgramData\\WonderAgent\\logs\\nssm-stdout.log").inheritIO().start().waitFor();
                new ProcessBuilder(nssmPath, "set", "WonderAgent", "AppStderr",
                        "C:\\ProgramData\\WonderAgent\\logs\\nssm-stderr.log").inheritIO().start().waitFor();
                new ProcessBuilder(nssmPath, "set", "WonderAgent", "Start",
                        "SERVICE_AUTO_START").inheritIO().start().waitFor();

                System.out.println("Serviço WonderAgent instalado com sucesso.");
                System.out.println("Para iniciar: sc start WonderAgent");
            } catch (Exception e) {
                System.err.println("Falha ao instalar serviço: " + e.getMessage());
                log.error("Falha ao instalar serviço Windows", e);
            }
        }
    }

    @ApplicationScoped
    @Command(name = "uninstall", description = "Remove o serviço Windows")
    static class UninstallCommand implements Runnable {

        @Option(names = "--nssm", description = "Caminho para nssm.exe", defaultValue = "nssm")
        String nssmPath;

        @Override
        public void run() {
            try {
                log.info("Removendo serviço Windows WonderAgent");

                // Para o serviço antes de remover
                new ProcessBuilder("sc", "stop", "WonderAgent").inheritIO().start().waitFor();

                Process remove = new ProcessBuilder(nssmPath, "remove", "WonderAgent", "confirm")
                        .inheritIO()
                        .start();
                int rc = remove.waitFor();
                if (rc != 0) {
                    System.err.println("NSSM retornou código " + rc + " — verifique permissões de administrador.");
                    return;
                }
                System.out.println("Serviço WonderAgent removido com sucesso.");
            } catch (Exception e) {
                System.err.println("Falha ao remover serviço: " + e.getMessage());
                log.error("Falha ao remover serviço Windows", e);
            }
        }
    }

    @ApplicationScoped
    @Command(name = "config", description = "Operações de configuração",
             subcommands = ConfigCommand.ShowCommand.class)
    static class ConfigCommand implements Runnable {
        @Override public void run() { System.out.println("Use: config show"); }

        @ApplicationScoped
        @Command(name = "show", description = "Exibe a configuração ativa")
        static class ShowCommand implements Runnable {
            @Override
            public void run() {
                String[] keys = {
                    "agent.client-id",
                    "agent.version",
                    "agent.poll-interval",
                    "agent.central-url",
                    "driver.type",
                    "driver.wildfly.home",
                    "driver.wildfly.management-port",
                    "driver.wildfly.deploy-path",
                    "driver.wildfly.artifact-name",
                    "driver.wildfly.health-check-url",
                    "nexus.username",
                    "download.temp-dir",
                    "download.max-retries",
                };
                System.out.println("=== Configuração ativa ===");
                var config = ConfigProvider.getConfig();
                for (String key : keys) {
                    String value = config.getOptionalValue(key, String.class)
                            .map(v -> key.contains("password") || key.contains("token") ? "***" : v)
                            .orElse("(não definido)");
                    System.out.printf("  %-45s %s%n", key, value);
                }
            }
        }
    }
}
