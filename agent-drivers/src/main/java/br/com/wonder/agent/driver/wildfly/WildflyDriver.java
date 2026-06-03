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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RuntimeState.UNKNOWN;
        } catch (Exception e) {
            log.error("Erro ao detectar estado do WildFly — retornando UNKNOWN: {}", e.getMessage(), e);
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
    public java.util.Optional<String> getInstalledChecksum() {
        Path marker = Path.of(deployPath, ".wonder-sha1");
        try {
            if (Files.exists(marker)) return java.util.Optional.of(Files.readString(marker).trim());
        } catch (IOException e) {
            log.warn("Não foi possível ler arquivo de checksum: {}", e.getMessage());
        }
        return java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<java.nio.file.Path> getInstalledArtifactPath() {
        Path war = Path.of(deployPath, artifactName);
        return Files.exists(war) ? java.util.Optional.of(war) : java.util.Optional.empty();
    }

    @Override
    public DeployResult deploy(Artifact artifact) {
        Instant start = Instant.now();
        try {
            Path target = Path.of(deployPath, artifactName);
            backupCurrentVersion(target);

            Files.copy(artifact.localFile(), target, StandardCopyOption.REPLACE_EXISTING);

            Files.writeString(Path.of(deployPath, ".wonder-version"), artifact.version());
            Files.writeString(Path.of(deployPath, ".wonder-sha1"), sha1Hex(target));

            log.info("Artefato copiado para {}", target);
            return DeployResult.success(artifact.version(), Duration.between(start, Instant.now()),
                    RuntimeState.STOPPED, RuntimeState.STOPPED);
        } catch (IOException e) {
            return DeployResult.failure(artifact.version(), Duration.between(start, Instant.now()),
                    RuntimeState.STOPPED, RuntimeState.STOPPED,
                    "Falha ao copiar artefato: " + e.getMessage());
        }
    }

    private static String sha1Hex(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(Files.readAllBytes(file));
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 não disponível", e);
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
        log.info("Iniciando WildFly (timeout={}s, home={})", startTimeoutSeconds, wildflyHome);
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
            boolean started = waitForState(RuntimeState.RUNNING, startTimeoutSeconds);
            if (!started) {
                log.error("WildFly não atingiu estado RUNNING em {}s — estado atual: {}", startTimeoutSeconds, detectState());
            }
            return started;
        } catch (IOException e) {
            log.error("Falha ao iniciar WildFly (home={}): {}", wildflyHome, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean stop() {
        log.info("Parando WildFly (timeout={}s, serviceMode={})", stopTimeoutSeconds, serviceMode);
        try {
            if (serviceMode) {
                ProcessBuilder pb = isLinux()
                        ? new ProcessBuilder("systemctl", "stop", "wildfly")
                        : new ProcessBuilder("sc", "stop", "WildFly");
                pb.start().waitFor();
            } else {
                shutdownViaManagementApi();
            }
            boolean stopped = waitForState(RuntimeState.STOPPED, stopTimeoutSeconds);
            if (!stopped) {
                log.error("WildFly não atingiu estado STOPPED em {}s — estado atual: {}", stopTimeoutSeconds, detectState());
            }
            return stopped;
        } catch (Exception e) {
            log.error("Falha ao parar WildFly: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean forceKill() {
        OptionalLong pid = findWildflyPid();
        if (pid.isEmpty()) {
            // ProcessHandle.allProcesses() pode não enxergar a command line no Windows —
            // fallback via wmic para encontrar o PID pelo path do jboss-modules.jar.
            pid = findWildflyPidViaWmic();
        }
        if (pid.isPresent()) {
            log.warn("forceKill: matando processo WildFly PID={}", pid.getAsLong());
        } else {
            log.warn("forceKill: nenhum processo WildFly encontrado (pode já estar parado)");
        }
        try {
            if (pid.isPresent()) {
                long p = pid.getAsLong();
                ProcessHandle.of(p).ifPresent(ProcessHandle::destroyForcibly);
                // Fallback taskkill caso destroyForcibly não funcione (ex: sem permissão)
                killViaTaskkill(p);
            }
            boolean stopped = waitForState(RuntimeState.STOPPED, 15);
            if (!stopped) {
                log.error("forceKill: processo não terminou em 15s — PID={}", pid.isPresent() ? pid.getAsLong() : "desconhecido");
            }
            return stopped;
        } catch (Exception e) {
            log.error("forceKill falhou: {}", e.getMessage(), e);
            return false;
        }
    }

    // Usa wmic para encontrar o PID do java.exe que carrega jboss-modules.jar deste wildflyHome.
    // Necessário porque ProcessHandle.allProcesses() não consegue ler commandLine() no Windows
    // quando rodando como Native Image ou sem privilégios suficientes.
    private OptionalLong findWildflyPidViaWmic() {
        if (!isLinux()) {
            try {
                String home = Path.of(wildflyHome).toAbsolutePath().toString().replace("\\", "\\\\");
                String filter = "commandline like '%jboss-modules%' and commandline like '%" + home + "%'";
                Process p = new ProcessBuilder("wmic", "process", "where", filter, "get", "ProcessId", "/VALUE")
                        .start();
                String out = new String(p.getInputStream().readAllBytes()).trim();
                p.waitFor();
                for (String line : out.split("[\\r\\n]+")) {
                    if (line.startsWith("ProcessId=")) {
                        String val = line.substring("ProcessId=".length()).trim();
                        if (!val.isBlank()) {
                            log.debug("findWildflyPidViaWmic: PID={}", val);
                            return OptionalLong.of(Long.parseLong(val));
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("findWildflyPidViaWmic falhou: {}", e.getMessage());
            }
        }
        return OptionalLong.empty();
    }

    private void killViaTaskkill(long pid) {
        if (isLinux()) return;
        try {
            new ProcessBuilder("taskkill", "/F", "/PID", String.valueOf(pid)).start().waitFor();
            log.debug("taskkill /F /PID {} executado", pid);
        } catch (Exception e) {
            log.debug("taskkill falhou: {}", e.getMessage());
        }
    }

    @Override
    public HealthStatus healthCheck() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(healthCheckTimeoutSeconds))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(healthCheckUrl))
                    .timeout(Duration.ofSeconds(healthCheckTimeoutSeconds))
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 200) return HealthStatus.ok();
            return HealthStatus.unhealthy("HTTP " + response.statusCode());
        } catch (Exception e) {
            return HealthStatus.unhealthy("Health check falhou: " + e.getMessage());
        }
    }

    /**
     * Aguarda o deployment scanner confirmar que o WAR da tentativa atual foi processado.
     * Usa enabled-time como âncora temporal: só aceita status=OK com enabled-time >= deployedAt,
     * garantindo que o resultado pertence a esta tentativa e não a um deploy anterior.
     *
     * Cenários cobertos:
     *   - 404: scanner ainda não começou a processar → continua aguardando
     *   - status=OK, enabled-time < deployedAt: resultado de deploy anterior → continua aguardando
     *   - status=OK, enabled-time >= deployedAt: deployment desta tentativa concluiu → true
     *   - status=FAILED, enabled-time >= deployedAt: falha desta tentativa → false imediato
     *   - timeout: false
     */
    @Override
    public boolean waitForDeploymentReady(Instant deployedAt, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        long deployedAtMs = deployedAt.toEpochMilli();
        while (System.currentTimeMillis() < deadline) {
            DeploymentScanResult scan = queryDeploymentStatus();
            if (scan.enabledTime() >= deployedAtMs) {
                if ("OK".equals(scan.status())) {
                    log.info("Deployment confirmado pelo scanner: status=OK enabled-time={}", scan.enabledTime());
                    return true;
                }
                if ("FAILED".equals(scan.status())) {
                    log.error("Deployment falhou no scanner: status=FAILED enabled-time={}", scan.enabledTime());
                    return false;
                }
            }
            log.debug("waitForDeploymentReady: status={} enabled-time={} aguardando deployedAt={}",
                    scan.status(), scan.enabledTime(), deployedAtMs);
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        log.warn("waitForDeploymentReady: timeout após {}s sem confirmação do scanner", timeoutSeconds);
        return false;
    }

    /**
     * Aguarda o health check passar com retry, para uso logo após deploy+start.
     * Entre tentativas há uma pausa fixa de 10s.
     */
    public HealthStatus healthCheckWithRetry() {
        HealthStatus last = HealthStatus.unhealthy("nenhuma tentativa realizada");
        for (int attempt = 1; attempt <= healthCheckRetries; attempt++) {
            last = healthCheck();
            if (last.healthy()) {
                log.info("Health check OK na tentativa {}/{}", attempt, healthCheckRetries);
                return last;
            }
            log.warn("Health check tentativa {}/{} falhou: {} — url={}", attempt, healthCheckRetries, last.details(), healthCheckUrl);
            if (attempt < healthCheckRetries) {
                try { Thread.sleep(10_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return last; }
            }
        }
        log.error("Health check falhou em todas as {} tentativas — última falha: {}", healthCheckRetries, last.details());
        return last;
    }

    // ── Métodos privados ──────────────────────────────────────────────────────

    /**
     * Envia o comando de shutdown via management API HTTP (POST /management).
     * Não depende do jboss-cli.sh, que não está presente no WildFly slim provisionado.
     */
    private void shutdownViaManagementApi() throws IOException, InterruptedException {
        String url = "http://localhost:" + managementPort + "/management";
        String body = "{\"operation\":\"shutdown\",\"address\":[]}";

        Optional<String> user = readWildflyEnvVar("WILDFLY_MGMT_USER");
        Optional<String> pass = readWildflyEnvVar("WILDFLY_MGMT_PASSWORD");

        HttpRequest challenge = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<Void> r1 = newManagementClient().send(challenge, HttpResponse.BodyHandlers.discarding());

        if (r1.statusCode() == 200) {
            log.info("Comando de shutdown enviado via management API");
            return;
        }
        if (r1.statusCode() != 401 || user.isEmpty() || pass.isEmpty()) {
            throw new IOException("Shutdown via management API falhou: HTTP " + r1.statusCode()
                    + (user.isEmpty() || pass.isEmpty() ? " (credenciais não configuradas)" : ""));
        }

        String wwwAuth = r1.headers().firstValue("WWW-Authenticate").orElse("");
        String authHeader = buildDigestHeader("POST", url, wwwAuth, user.get(), pass.get());

        HttpRequest authenticated = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Authorization", authHeader)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<Void> r2 = newManagementClient().send(authenticated, HttpResponse.BodyHandlers.discarding());
        if (r2.statusCode() != 200) {
            throw new IOException("Shutdown via management API falhou após autenticação Digest: HTTP " + r2.statusCode()
                    + " — verifique WILDFLY_MGMT_USER e WILDFLY_MGMT_PASSWORD");
        }
        log.info("Comando de shutdown enviado via management API (com autenticação)");
    }

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
        if (findWildflyPid().isPresent()) return true;
        return findWildflyPidViaWmic().isPresent();
    }

    private boolean isManagementPortOpen() {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("localhost", managementPort), 2000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private HttpClient newManagementClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * Executa um GET na management API com Digest Auth.
     *
     * O WildFly associa o nonce à conexão TCP que gerou o 401, portanto o request
     * autenticado deve usar uma conexão nova — por isso criamos um HttpClient por chamada.
     *
     * Retorna null se 404 (recurso não existe).
     * Retorna "running" se 401 sem credenciais configuradas (servidor vivo mas sem auth).
     */
    private String managementGet(String url) throws IOException, InterruptedException {
        Optional<String> user = readWildflyEnvVar("WILDFLY_MGMT_USER");
        Optional<String> pass = readWildflyEnvVar("WILDFLY_MGMT_PASSWORD");

        HttpRequest challenge = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        HttpResponse<String> r1 = newManagementClient().send(challenge, HttpResponse.BodyHandlers.ofString());

        if (r1.statusCode() == 200) return r1.body().replaceAll("\"", "").trim();
        if (r1.statusCode() == 404) return null;
        if (r1.statusCode() != 401) return null;
        if (user.isEmpty() || pass.isEmpty()) return "running"; // servidor vivo, sem credenciais

        String wwwAuth = r1.headers().firstValue("WWW-Authenticate").orElse("");
        String authHeader = buildDigestHeader("GET", url, wwwAuth, user.get(), pass.get());

        HttpRequest authenticated = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", authHeader)
                .GET()
                .build();
        HttpResponse<String> r2 = newManagementClient().send(authenticated, HttpResponse.BodyHandlers.ofString());

        if (r2.statusCode() == 200) return r2.body().replaceAll("\"", "").trim();
        if (r2.statusCode() == 404) return null;
        return null;
    }

    /**
     * Monta o header Authorization: Digest a partir do WWW-Authenticate do challenge.
     * Suporta qop=auth (usado pelo WildFly). Algoritmo: MD5.
     */
    private String buildDigestHeader(String method, String url, String wwwAuth,
                                     String user, String pass) {
        String realm  = extractDigestParam(wwwAuth, "realm");
        String nonce  = extractDigestParam(wwwAuth, "nonce");
        String opaque = extractDigestParam(wwwAuth, "opaque");
        String qop    = extractDigestParam(wwwAuth, "qop");

        String uri = URI.create(url).getRawPath();
        String query = URI.create(url).getRawQuery();
        if (query != null) uri = uri + "?" + query;

        String cnonce = Long.toHexString(new SecureRandom().nextLong());
        String nc = "00000001";

        String ha1 = md5(user + ":" + realm + ":" + pass);
        String ha2 = md5(method + ":" + uri);
        String response = "auth".equals(qop)
                ? md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2)
                : md5(ha1 + ":" + nonce + ":" + ha2);

        StringBuilder sb = new StringBuilder("Digest ")
                .append("username=\"").append(user).append("\", ")
                .append("realm=\"").append(realm).append("\", ")
                .append("nonce=\"").append(nonce).append("\", ")
                .append("uri=\"").append(uri).append("\", ")
                .append("response=\"").append(response).append("\"");
        if ("auth".equals(qop)) {
            sb.append(", qop=auth")
              .append(", nc=").append(nc)
              .append(", cnonce=\"").append(cnonce).append("\"");
        }
        if (opaque != null && !opaque.isBlank()) {
            sb.append(", opaque=\"").append(opaque).append("\"");
        }
        return sb.toString();
    }

    private static String extractDigestParam(String header, String param) {
        String search = param + "=";
        int idx = header.indexOf(search);
        if (idx < 0) return "";
        int start = idx + search.length();
        if (start < header.length() && header.charAt(start) == '"') {
            int end = header.indexOf('"', start + 1);
            return end > start ? header.substring(start + 1, end) : "";
        }
        int end = header.indexOf(',', start);
        return end > start ? header.substring(start, end).trim() : header.substring(start).trim();
    }

    private static String md5(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(input.getBytes());
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("MD5 não disponível", e);
        }
    }

    private String queryManagementApi(String attribute) throws IOException, InterruptedException {
        String url = "http://localhost:" + managementPort
                + "/management?operation=attribute&name=" + attribute;
        String result = managementGet(url);
        // null aqui significa 404 — improvável para server-state, trata como HUNG
        return result != null ? result : "";
    }

    private record DeploymentScanResult(String status, long enabledTime) {
        static DeploymentScanResult notReady() { return new DeploymentScanResult(null, -1); }
    }

    private DeploymentScanResult queryDeploymentStatus() {
        try {
            String base = "http://localhost:" + managementPort
                    + "/management/deployment/" + artifactName;
            String statusVal = managementGet(base + "?operation=attribute&name=status");
            if (statusVal == null) return DeploymentScanResult.notReady();
            String enabledTimeStr = managementGet(base + "?operation=attribute&name=enabled-time");
            long enabledTime = enabledTimeStr != null && !enabledTimeStr.isBlank()
                    ? Long.parseLong(enabledTimeStr) : -1;
            return new DeploymentScanResult(statusVal, enabledTime);
        } catch (Exception e) {
            log.debug("queryDeploymentStatus falhou: {}", e.getMessage());
            return DeploymentScanResult.notReady();
        }
    }

    private boolean hasFailedDeployment() {
        DeploymentScanResult scan = queryDeploymentStatus();
        // STOPPED indica deployment desabilitado — tratamos como falha para ir para PARTIAL
        return "FAILED".equals(scan.status()) || "STOPPED".equals(scan.status());
    }

    private boolean waitForState(RuntimeState expected, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        // Janela de graça: STOPPED logo após disparar o processo é esperado enquanto a JVM carrega.
        // Só tratamos STOPPED/PARTIAL como terminais após esta janela.
        long earlyExitAllowedAt = System.currentTimeMillis() + 6_000;
        RuntimeState last = null;
        while (System.currentTimeMillis() < deadline) {
            RuntimeState current = detectState();
            if (current != last) {
                log.debug("waitForState: estado={} aguardando={}", current, expected);
                last = current;
            }
            if (current == expected) return true;
            // Ao aguardar RUNNING: STOPPED e PARTIAL são terminais — processo morreu ou deploy falhou.
            // Continuar esperando só faz sentido em HUNG/UNKNOWN (transitório).
            if (expected == RuntimeState.RUNNING
                    && System.currentTimeMillis() > earlyExitAllowedAt
                    && (current == RuntimeState.STOPPED || current == RuntimeState.PARTIAL)) {
                log.warn("waitForState: estado terminal {} detectado enquanto aguardava {} — abortando", current, expected);
                return false;
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        log.warn("waitForState: timeout após {}s aguardando {} — estado atual: {}", timeoutSeconds, expected, last);
        return false;
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
}
