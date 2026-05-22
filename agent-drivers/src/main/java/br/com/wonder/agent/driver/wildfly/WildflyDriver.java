package br.com.wonder.agent.driver.wildfly;

import br.com.wonder.agent.model.deploy.Artifact;
import br.com.wonder.agent.model.deploy.DeployResult;
import br.com.wonder.agent.model.deploy.HealthStatus;
import br.com.wonder.agent.model.driver.RuntimeDriver;
import br.com.wonder.agent.model.state.RuntimeState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;

/**
 * Driver para WildFly 36. Funciona em Windows e Linux.
 * Usa a management API HTTP (porta 9990) para detectar estado e gerenciar deployments.
 * Usa ProcessHandle.allProcesses() para localizar o processo WildFly pelo wildflyHome,
 * evitando afetar outras instâncias Java ou WildFly em execução na mesma máquina.
 *
 * Ver docs/drivers/wildfly-driver.md para detalhes de cada operação.
 */
@Slf4j
@Named("wildfly")
@ApplicationScoped
@Typed(WildflyDriver.class)
public class WildflyDriver implements RuntimeDriver {

    @ConfigProperty(name = "driver.wildfly.home")
    String wildflyHome;

    @ConfigProperty(name = "driver.wildfly.management-port", defaultValue = "9990")
    int managementPort;

    @ConfigProperty(name = "driver.wildfly.deploy-path")
    String deployPath;

    @ConfigProperty(name = "driver.wildfly.artifact-name")
    String artifactName;

    @ConfigProperty(name = "driver.wildfly.start-timeout-seconds", defaultValue = "180")
    int startTimeoutSeconds;

    @ConfigProperty(name = "driver.wildfly.stop-timeout-seconds", defaultValue = "60")
    int stopTimeoutSeconds;

    @ConfigProperty(name = "driver.wildfly.health-check-url")
    String healthCheckUrl;

    @Override
    public String getRuntimeType() {
        return "wildfly";
    }

    /**
     * Detecta estado sem efeitos colaterais.
     * Sequência: processo vivo? → porta management? → API responde? → deployment OK?
     */
    @Override
    public RuntimeState detectState() {
        try {
            if (!isProcessAlive()) {
                return RuntimeState.STOPPED;
            }
            if (!isManagementPortOpen()) {
                return RuntimeState.HUNG;
            }
            String serverState = queryManagementApi("server-state");
            if (!"running".equals(serverState)) {
                return RuntimeState.HUNG;
            }
            if (hasFailedDeployment()) {
                return RuntimeState.PARTIAL;
            }
            return RuntimeState.RUNNING;
        } catch (Exception e) {
            log.warn("Erro ao detectar estado do WildFly: {}", e.getMessage());
            return RuntimeState.UNKNOWN;
        }
    }

    @Override
    public String getInstalledVersion() {
        Path marker = Path.of(deployPath, ".wonder-version");
        try {
            return Files.exists(marker) ? Files.readString(marker).trim() : null;
        } catch (IOException e) {
            log.warn("Não foi possível ler arquivo de versão: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public DeployResult deploy(Artifact artifact) {
        Instant start = Instant.now();
        try {
            Path target = Path.of(deployPath, artifactName);
            backupCurrentVersion(target);

            Files.copy(artifact.localFile(), target, StandardCopyOption.REPLACE_EXISTING);

            // Escreve marcador de versão lido por getInstalledVersion()
            Files.writeString(Path.of(deployPath, ".wonder-version"), artifact.version());

            log.info("Artefato copiado para {}", target);
            return DeployResult.success(artifact.version(), Duration.between(start, Instant.now()),
                    RuntimeState.STOPPED, RuntimeState.STOPPED);
        } catch (IOException e) {
            return DeployResult.failure(artifact.version(), Duration.between(start, Instant.now()),
                    RuntimeState.STOPPED, RuntimeState.STOPPED,
                    "Falha ao copiar artefato: " + e.getMessage());
        }
    }

    private void backupCurrentVersion(Path target) {
        if (!Files.exists(target)) return;
        String previousVersion = getInstalledVersion();
        String label = previousVersion != null ? previousVersion : "unknown";
        Path backup = target.resolveSibling(artifactName + ".backup-" + label);
        try {
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            log.info("Backup da versão anterior criado: {}", backup.getFileName());
        } catch (IOException e) {
            log.warn("Não foi possível criar backup do WAR anterior ({}): {}", label, e.getMessage());
        }
    }

    @Override
    public boolean start() {
        try {
            String[] cmd = isLinux()
                    ? new String[]{"bash", "-c",
                        "nohup " + wildflyHome + "/bin/standalone.sh > /dev/null 2>&1 &"}
                    : new String[]{wildflyHome + "/bin/standalone.bat"};
            new ProcessBuilder(cmd)
                    .directory(Path.of(wildflyHome).toFile())
                    .start();
            return waitForState(RuntimeState.RUNNING, startTimeoutSeconds);
        } catch (IOException e) {
            log.error("Falha ao iniciar WildFly: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean stop() {
        try {
            String[] cmd = isLinux()
                    ? new String[]{"bash", wildflyHome + "/bin/jboss-cli.sh",
                        "--connect", "--command=:shutdown"}
                    : new String[]{wildflyHome + "/bin/jboss-cli.bat",
                        "--connect", "--command=:shutdown"};
            Process p = new ProcessBuilder(cmd).start();
            p.waitFor();
            return waitForState(RuntimeState.STOPPED, stopTimeoutSeconds);
        } catch (Exception e) {
            log.error("Falha ao parar WildFly: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean forceKill() {
        try {
            findWildflyPid().ifPresent(pid ->
                ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly)
            );
            return waitForState(RuntimeState.STOPPED, 15);
        } catch (Exception e) {
            log.error("forceKill falhou: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public HealthStatus healthCheck() {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(healthCheckUrl).toURL().openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            if (code == 200) return HealthStatus.ok();
            return HealthStatus.unhealthy("HTTP " + code);
        } catch (Exception e) {
            return HealthStatus.unhealthy("Health check falhou: " + e.getMessage());
        }
    }

    // ── Métodos privados ──────────────────────────────────────────────────────

    private boolean isLinux() {
        return System.getProperty("os.name").toLowerCase().contains("linux");
    }

    /**
     * Localiza o PID do processo WildFly desta instalação específica.
     * Filtra por processos cujo commandLine contém "jboss-modules" E o caminho do wildflyHome,
     * evitando afetar outras instâncias Java ou outros WildFly na mesma máquina.
     */
    OptionalLong findWildflyPid() {
        String home = Path.of(wildflyHome).toAbsolutePath().toString();
        return ProcessHandle.allProcesses()
                .filter(p -> p.info().commandLine()
                        .map(cmd -> cmd.contains("jboss-modules") && cmd.contains(home))
                        .orElse(false))
                .mapToLong(ProcessHandle::pid)
                .findFirst();
    }

    protected boolean isProcessAlive() {
        return findWildflyPid().isPresent();
    }

    private boolean isManagementPortOpen() {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("localhost", managementPort), 2000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String queryManagementApi(String attribute) throws IOException {
        String url = "http://localhost:" + managementPort
                + "/management?operation=attribute&name=" + attribute;
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        String body = new String(conn.getInputStream().readAllBytes())
                .replaceAll("\"", "").trim();
        return body;
    }

    private boolean hasFailedDeployment() {
        try {
            String url = "http://localhost:" + managementPort
                    + "/management/deployment/" + artifactName
                    + "?operation=attribute&name=status";
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            if (conn.getResponseCode() == 404) return false;
            String status = new String(conn.getInputStream().readAllBytes())
                    .replaceAll("\"", "").trim();
            return "FAILED".equals(status);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean waitForState(RuntimeState expected, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < deadline) {
            if (detectState() == expected) return true;
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }
}
