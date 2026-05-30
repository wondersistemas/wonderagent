package br.com.wonder.agent.cli.command;

import br.com.wonder.agent.core.db.DatabaseVersionReader;
import br.com.wonder.agent.core.poll.AgentOrchestrator;
import br.com.wonder.agent.core.provision.WildflyProvisioner;
import br.com.wonder.agent.model.deploy.Artifact;
import br.com.wonder.agent.model.driver.RuntimeDriver;
import br.com.wonder.agent.model.state.RuntimeState;
import io.quarkus.arc.Unremovable;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
        WonderAgentCommand.VerifAtualizacaoCommand.class,
        WonderAgentCommand.DownloadCommand.class,
        WonderAgentCommand.DeployCommand.class,
        WonderAgentCommand.InstallCommand.class,
        WonderAgentCommand.UninstallCommand.class,
        WonderAgentCommand.ConfigCommand.class,
        WonderAgentCommand.DbVersionCommand.class,
        WonderAgentCommand.DownloadServerCommand.class,
        WonderAgentCommand.ApplyServerCommand.class,
        WonderAgentCommand.ProvisionarCommand.class,
    }
)
public class WonderAgentCommand implements Runnable {

    @Inject AgentOrchestrator orchestrator;

    @Option(names = "--trace", description = "Ativa log no nível TRACE para diagnóstico detalhado",
            hidden = true) // lido em Main.run() antes do CDI — mantido aqui só para o parser aceitar o arg
    boolean trace;

    @Override
    public void run() {
        log.info("Iniciando WonderAgent em modo serviço");
        orchestrator.startServiceMode();
        // O scheduler do Quarkus mantém o processo vivo
    }

    static void enableTrace(boolean trace) {
        if (!trace) return;
        org.jboss.logmanager.LogContext ctx = org.jboss.logmanager.LogContext.getLogContext();
        org.jboss.logmanager.Logger root = ctx.getLogger("");
        root.setLevel(org.jboss.logmanager.Level.TRACE);
        for (java.util.logging.Handler h : root.getHandlers()) {
            if (h instanceof org.jboss.logmanager.ExtHandler eh) {
                eh.setLevel(org.jboss.logmanager.Level.TRACE);
            } else {
                h.setLevel(java.util.logging.Level.FINEST);
            }
        }
        System.out.println("[trace] Nível de log alterado para TRACE");
    }

    @Unremovable
    @ApplicationScoped
    @Command(name = "status", mixinStandardHelpOptions = true,
             description = "Mostra estado atual do runtime e versão instalada")
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

    @Unremovable
    @ApplicationScoped
    @Command(name = "detect", mixinStandardHelpOptions = true,
             description = "Detecta e imprime o estado do runtime sem agir")
    static class DetectCommand implements Runnable {
        @Inject RuntimeDriver driver;

        @Override
        public void run() {
            System.out.println(driver.detectState());
        }
    }

    @Unremovable
    @ApplicationScoped
    @Command(name = "verif_atualizacao", mixinStandardHelpOptions = true,
             description = "Executa um ciclo de poll único: verifica e aplica se necessário")
    static class VerifAtualizacaoCommand implements Runnable {
        @Inject AgentOrchestrator orchestrator;
        @Option(names = "--trace", description = "Ativa log no nível TRACE para diagnóstico detalhado",
                hidden = true) boolean trace;

        @Override
        public void run() {
            orchestrator.pollNow(System.out::println);
        }
    }

    @Unremovable
    @ApplicationScoped
    @Command(name = "download", mixinStandardHelpOptions = true,
             description = "Consulta o servidor central e baixa nova versão se disponível (sem fazer deploy)")
    static class DownloadCommand implements Runnable {
        @Inject AgentOrchestrator orchestrator;
        @Option(names = "--trace", description = "Ativa log no nível TRACE para diagnóstico detalhado",
                hidden = true) boolean trace;

        @Override
        public void run() {
            orchestrator.update(System.out::println);
        }
    }

    @Unremovable
    @ApplicationScoped
    @Command(name = "deploy", mixinStandardHelpOptions = true,
             description = "Faz deploy do artefato já baixado pelo comando download")
    static class DeployCommand implements Runnable {
        @Inject AgentOrchestrator orchestrator;
        @Option(names = "--trace", description = "Ativa log no nível TRACE para diagnóstico detalhado",
                hidden = true) boolean trace;

        @ConfigProperty(name = "download.temp-dir")
        String tempDir;

        @ConfigProperty(name = "driver.wildfly.artifact-name")
        String artifactName;

        @Override
        public void run() {
            Path warFile;
            try {
                warFile = findWarInTempDir();
            } catch (IOException e) {
                System.err.println("ERRO ao acessar diretório de downloads: " + e.getMessage());
                return;
            }

            if (warFile == null) {
                System.err.println("Nenhum artefato encontrado em " + tempDir + " — execute 'wonderagent download' primeiro.");
                return;
            }

            // Extrai a versão do nome do arquivo: wnfe-war-2.5.0.war → 2.5.0
            String version = extractVersion(warFile.getFileName().toString());
            Artifact artifact = new Artifact(
                    artifactName.replace(".war", ""),
                    version,
                    null,
                    warFile
            );
            orchestrator.deployArtifact(artifact, System.out::println);
        }

        private Path findWarInTempDir() throws IOException {
            Path dir = Path.of(tempDir);
            if (!Files.isDirectory(dir)) return null;
            try (var stream = Files.list(dir)) {
                return stream
                        .filter(p -> p.getFileName().toString().endsWith(".war"))
                        .max(java.util.Comparator.comparingLong(p -> {
                            try { return Files.getLastModifiedTime(p).toMillis(); }
                            catch (IOException e) { return 0L; }
                        }))
                        .orElse(null);
            }
        }

        private static String extractVersion(String filename) {
            // wnfe-war-2.5.0.war → 2.5.0
            String withoutExt = filename.endsWith(".war") ? filename.substring(0, filename.length() - 4) : filename;
            int lastDash = withoutExt.lastIndexOf('-');
            return lastDash >= 0 ? withoutExt.substring(lastDash + 1) : withoutExt;
        }
    }

    @Unremovable
    @ApplicationScoped
    @Command(name = "install", mixinStandardHelpOptions = true,
             description = "Registra wonderagent como serviço Windows via NSSM")
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

    @Unremovable
    @ApplicationScoped
    @Command(name = "uninstall", mixinStandardHelpOptions = true,
             description = "Remove o serviço Windows")
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

    @Unremovable
    @ApplicationScoped
    @Command(name = "db-version", mixinStandardHelpOptions = true,
             description = "Lê id_versaodb do banco Oracle e imprime na saída padrão")
    static class DbVersionCommand implements Runnable {
        @Inject DatabaseVersionReader dbVersionReader;

        @Override
        public void run() {
            String version = dbVersionReader.readDbVersion()
                    .orElseThrow(() -> new RuntimeException("Falha ao ler id_versaodb do banco — verifique DB_URL, DB_USERNAME e DB_PASSWORD"));
            System.out.println(version);
        }
    }

    @Unremovable
    @ApplicationScoped
    @Command(name = "download-server", mixinStandardHelpOptions = true,
             description = "Baixa o ZIP do WildFly para o cache local (sem instalar). Usa zsync delta se já houver versão em cache.")
    static class DownloadServerCommand implements Runnable {
        @Inject WildflyProvisioner provisioner;

        @Option(names = "--version",
                description = "Versão do wildfly-provisioning a baixar. Se omitido, resolve a última versão publicada no Reposilite.")
        String version;

        @Override
        public void run() {
            try {
                if (version != null) {
                    provisioner.downloadOnly(version, System.out::println);
                } else {
                    provisioner.downloadOnly(System.out::println);
                }
            } catch (WildflyProvisioner.ProvisioningException e) {
                System.err.println("ERRO: " + e.getMessage());
                log.error("Falha no download-server", e);
            }
        }
    }

    @Unremovable
    @ApplicationScoped
    @Command(name = "apply-server", mixinStandardHelpOptions = true,
             description = "Extrai e instala o ZIP do WildFly já presente no cache local. Execute download-server antes.")
    static class ApplyServerCommand implements Runnable {
        @Inject WildflyProvisioner provisioner;

        @Option(names = "--version",
                description = "Versão a aplicar. Se omitido, usa o ZIP mais recente no cache.")
        String version;

        @Override
        public void run() {
            try {
                provisioner.applyFromCache(version, System.out::println);
            } catch (WildflyProvisioner.ProvisioningException e) {
                System.err.println("ERRO: " + e.getMessage());
                log.error("Falha no apply-server", e);
            }
        }
    }

    @Unremovable
    @ApplicationScoped
    @Command(name = "provisionar", mixinStandardHelpOptions = true,
             description = "Baixa e instala o WildFly (download-server + apply-server). Se já estiver na versão correta, não faz nada.")
    static class ProvisionarCommand implements Runnable {
        @Inject WildflyProvisioner provisioner;

        @Option(names = "--version",
                description = "Versão do wildfly-provisioning. Se omitido, resolve a última versão publicada no Reposilite.")
        String version;

        @Override
        public void run() {
            try {
                if (version != null) {
                    provisioner.ensureVersion(version, System.out::println);
                } else {
                    provisioner.ensureLatestVersion(System.out::println);
                }
            } catch (WildflyProvisioner.ProvisioningException e) {
                System.err.println("ERRO: " + e.getMessage());
                log.error("Falha no provision", e);
            }
        }
    }

    @Unremovable
    @ApplicationScoped
    @Command(name = "config", mixinStandardHelpOptions = true,
             description = "Operações de configuração",
             subcommands = ConfigCommand.ShowCommand.class)
    static class ConfigCommand implements Runnable {
        @Spec Model.CommandSpec spec;

        @Override public void run() { spec.commandLine().usage(System.out); }

        @ApplicationScoped
        @Command(name = "show", mixinStandardHelpOptions = true,
                 description = "Exibe a configuração ativa")
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
