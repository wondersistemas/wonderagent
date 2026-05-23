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
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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

    @ConfigProperty(name = "driver.wildfly.health-check-timeout-seconds", defaultValue = "10")
    int healthCheckTimeoutSeconds;

    @ConfigProperty(name = "driver.wildfly.health-check-retries", defaultValue = "6")
    int healthCheckRetries;

    @ConfigProperty(name = "driver.wildfly.service-mode", defaultValue = "false")
    boolean serviceMode;

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
        if (!serviceMode && !Files.exists(Path.of(wildflyHome, "jboss-modules.jar"))) {
            log.error("WildFly não encontrado em {} — execute 'provision' antes de fazer deploy", wildflyHome);
            return false;
        }
        try {
            ProcessBuilder pb;
            if (serviceMode) {
                pb = isLinux()
                        ? new ProcessBuilder("systemctl", "start", "wildfly")
                        : new ProcessBuilder("sc", "start", "WildFly");
            } else if (isLinux()) {
                String envExports = buildEnvExports();
                pb = new ProcessBuilder("bash", "-c",
                        envExports + "nohup " + wildflyHome + "/bin/standalone.sh > /dev/null 2>&1 &");
            } else {
                pb = new ProcessBuilder(wildflyHome + "/bin/standalone.bat");
                loadWildflyEnvInto(pb.environment());
            }
            pb.directory(Path.of(wildflyHome).toFile()).start();
            return waitForState(RuntimeState.RUNNING, startTimeoutSeconds);
        } catch (IOException e) {
            log.error("Falha ao iniciar WildFly: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean stop() {
        try {
            ProcessBuilder pb;
            if (serviceMode) {
                pb = isLinux()
                        ? new ProcessBuilder("systemctl", "stop", "wildfly")
                        : new ProcessBuilder("sc", "stop", "WildFly");
            } else {
                pb = isLinux()
                        ? new ProcessBuilder(wildflyHome + "/bin/jboss-cli.sh",
                                "--connect", "--command=:shutdown")
                        : new ProcessBuilder(wildflyHome + "/bin/jboss-cli.bat",
                                "--connect", "--command=:shutdown");
            }
            pb.start().waitFor();
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
        int timeoutMs = healthCheckTimeoutSeconds * 1000;
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(healthCheckUrl).toURL().openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            int code = conn.getResponseCode();
            if (code == 200) return HealthStatus.ok();
            return HealthStatus.unhealthy("HTTP " + code);
        } catch (Exception e) {
            return HealthStatus.unhealthy("Health check falhou: " + e.getMessage());
        }
    }

    /**
     * Aguarda o health check passar com retry, para uso logo após deploy+start.
     * Entre tentativas há uma pausa fixa de 10s.
     */
    public HealthStatus healthCheckWithRetry() {
        HealthStatus last = HealthStatus.unhealthy("nenhuma tentativa realizada");
        for (int attempt = 1; attempt <= healthCheckRetries; attempt++) {
            last = healthCheck();
            if (last.healthy()) return last;
            log.debug("Health check tentativa {}/{} falhou: {}", attempt, healthCheckRetries, last.details());
            if (attempt < healthCheckRetries) {
                try { Thread.sleep(10_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return last; }
            }
        }
        return last;
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
        HttpURLConnection conn = openManagementConnection(url);
        int code = conn.getResponseCode();
        if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            // WildFly com autenticação habilitada mas sem credenciais configuradas —
            // 401 prova que o servidor está vivo e respondendo normalmente.
            return "running";
        }
        return new String(conn.getInputStream().readAllBytes())
                .replaceAll("\"", "").trim();
    }

    private HttpURLConnection openManagementConnection(String url) throws IOException {
        Optional<String> user = readWildflyEnvVar("WILDFLY_MGMT_USER");
        Optional<String> pass = readWildflyEnvVar("WILDFLY_MGMT_PASSWORD");
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        if (user.isPresent() && pass.isPresent()) {
            String u = user.get();
            char[] p = pass.get().toCharArray();
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(u, p);
                }
            });
        }
        return conn;
    }

    // Monta string de exports shell a partir do {wildfly.home}/.env para uso no comando nohup.
    // Cada linha vira: export KEY='VALUE' (aspas simples escapam qualquer caractere especial).
    private String buildEnvExports() {
        Path envFile = Path.of(wildflyHome, ".env");
        if (!Files.exists(envFile)) return "";
        try {
            StringBuilder sb = new StringBuilder();
            for (String line : Files.readAllLines(envFile)) {
                if (line.startsWith("#") || line.isBlank()) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim().replace("'", "'\\''");
                sb.append("export ").append(key).append("='").append(val).append("'; ");
            }
            return sb.toString();
        } catch (IOException e) {
            log.warn("Não foi possível ler {} para exports: {}", envFile, e.getMessage());
            return "";
        }
    }

    // Carrega o {wildfly.home}/.env no mapa de ambiente do ProcessBuilder (Windows).
    private void loadWildflyEnvInto(java.util.Map<String, String> env) {
        Path envFile = Path.of(wildflyHome, ".env");
        if (!Files.exists(envFile)) return;
        try {
            for (String line : Files.readAllLines(envFile)) {
                if (line.startsWith("#") || line.isBlank()) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                env.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            log.warn("Não foi possível carregar {} no ambiente do processo: {}", envFile, e.getMessage());
        }
    }

    // Lê uma variável do {wildfly.home}/.env (formato KEY=VALUE, linhas com # ignoradas).
    private Optional<String> readWildflyEnvVar(String key) {
        Path envFile = Path.of(wildflyHome, ".env");
        if (!Files.exists(envFile)) return Optional.empty();
        try {
            for (String line : Files.readAllLines(envFile)) {
                if (line.startsWith("#") || line.isBlank()) continue;
                int eq = line.indexOf('=');
                if (eq > 0 && line.substring(0, eq).trim().equals(key)) {
                    return Optional.of(line.substring(eq + 1).trim());
                }
            }
        } catch (IOException e) {
            log.warn("Não foi possível ler {}: {}", envFile, e.getMessage());
        }
        return Optional.empty();
    }

    private boolean hasFailedDeployment() {
        try {
            String url = "http://localhost:" + managementPort
                    + "/management/deployment/" + artifactName
                    + "?operation=attribute&name=status";
            HttpURLConnection conn = openManagementConnection(url);
            int code = conn.getResponseCode();
            if (code == 404 || code == HttpURLConnection.HTTP_UNAUTHORIZED) return false;
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
