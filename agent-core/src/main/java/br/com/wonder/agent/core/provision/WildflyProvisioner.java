package br.com.wonder.agent.core.provision;

import br.com.wonder.agent.core.download.ZsyncChecksumReader;
import br.com.wonder.agent.core.download.ZsyncDownloadHelper;
import br.com.wonder.agent.model.driver.RuntimeDriver;
import br.com.wonder.agent.model.state.RuntimeState;
import br.com.wonder.agent.model.util.FileChecksum;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Obtém e instala o WildFly provisionado (ZIP com classifier=dist) do Reposilite.
 *
 * Usa zsync para delta-transfer. A versão instalada é rastreada em
 * {@code driver.wildfly.home/.wildfly-version}.
 *
 * Versão fixada: variável de ambiente WILDFLY_PROVISIONING_VERSION (via .env).
 * Versão padrão: vem do desired-state do servidor central ou do Reposilite (latest).
 */
@Slf4j
@ApplicationScoped
public class WildflyProvisioner {

    // groupId como caminho de diretório Maven: br/com/wonder
    static final String GROUP_PATH = "br/com/wonder";
    static final String ARTIFACT_ID = "wildfly-provisioning";
    private static final String CLASSIFIER = "dist";
    private static final String VERSION_MARKER = ".wildfly-version";
    private static final String SHA_MARKER = ".wildfly-sha1";

    @ConfigProperty(name = "download.repository.url")
    String repositoryUrl;

    @ConfigProperty(name = "download.repository.username")
    String username;

    @ConfigProperty(name = "download.repository.password")
    Optional<String> password;

    @ConfigProperty(name = "download.temp-dir")
    String tempDir;

    @ConfigProperty(name = "driver.wildfly.home")
    String wildflyHome;

    @ConfigProperty(name = "driver.wildfly.management-port", defaultValue = "9990")
    int managementPort;

    @ConfigProperty(name = "driver.wildfly.stop-timeout-seconds", defaultValue = "60")
    int stopTimeoutSeconds;

    // Versão fixada via .env — WILDFLY_PROVISIONING_VERSION=x.y
    // Se ausente, usa a versão passada pelo servidor central ou resolvida do Reposilite.
    @ConfigProperty(name = "wildfly.provisioning.fixed-version", defaultValue = "")
    Optional<String> fixedVersion;

    @ConfigProperty(name = "wildfly.provisioning.repo-path",
                    defaultValue = "wnfe-releases")
    String repoPath;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url", defaultValue = "")
    Optional<String> dbUrl;

    @ConfigProperty(name = "quarkus.datasource.username", defaultValue = "")
    Optional<String> dbUsername;

    @ConfigProperty(name = "quarkus.datasource.password", defaultValue = "")
    Optional<String> dbPassword;

    @Inject
    ZsyncDownloadHelper zsyncHelper;

    @Inject
    ReposiliteMetadataClient metadataClient;

    @Inject
    ZsyncChecksumReader zsyncChecksumReader;

    @Inject
    RuntimeDriver driver;

    WildflyProvisioner() {}

    // visível para testes
    WildflyProvisioner(ZsyncDownloadHelper zsyncHelper, ReposiliteMetadataClient metadataClient,
                       ZsyncChecksumReader zsyncChecksumReader, RuntimeDriver driver) {
        this.zsyncHelper = zsyncHelper;
        this.metadataClient = metadataClient;
        this.zsyncChecksumReader = zsyncChecksumReader;
        this.driver = driver;
    }

    /**
     * Garante que o WildFly na versão {@code desiredVersion} está instalado em
     * {@code driver.wildfly.home}. Se {@code WILDFLY_PROVISIONING_VERSION} estiver
     * definido no ambiente, usa essa versão em vez da fornecida pelo caller.
     *
     * @return true se houve instalação, false se já estava na versão correta
     */
    public boolean ensureVersion(String desiredVersion) throws ProvisioningException {
        return ensureVersion(desiredVersion, msg -> {});
    }

    /**
     * Variante com callback de progresso para exibição no console durante download.
     */
    public boolean ensureVersion(String desiredVersion, Consumer<String> progress) throws ProvisioningException {
        String targetVersion = resolveVersion(desiredVersion);

        // Para SNAPSHOTs o arquivo real tem timestamp no nome — resolve o fileValue antes de construir a URL do .zsync
        String fileValue = metadataClient.resolveSnapshotValue(
                repositoryUrl, repoPath, GROUP_PATH, ARTIFACT_ID,
                targetVersion, CLASSIFIER, "zip", username, password.orElse(""));

        String zsyncUrl = buildZipUrl(targetVersion, fileValue) + ".zsync";
        Optional<String> remoteSha1 = zsyncChecksumReader.readSha1(zsyncUrl);
        String localSha1 = readInstalledSha1();

        if (remoteSha1.isPresent() && remoteSha1.get().equals(localSha1)) {
            log.debug("WildFly {} já está na build atual (SHA-1 confere) — nenhuma ação necessária", targetVersion);
            return false;
        }

        String installed = readInstalledVersion();
        if (remoteSha1.isEmpty() && targetVersion.equals(installed)) {
            // SHA-1 remoto indisponível e versão lógica bate — assume instalado correto
            log.debug("WildFly {} instalado e SHA-1 remoto indisponível — assumindo atualizado", targetVersion);
            return false;
        }

        log.info("WildFly desatualizado: instalado={} sha1Local={} sha1Remoto={} — iniciando provisionamento",
                installed, localSha1 != null ? localSha1.substring(0, 8) + "..." : "ausente",
                remoteSha1.map(s -> s.substring(0, 8) + "...").orElse("indisponível"));

        Path zipFile = downloadZipByFileValue(targetVersion, fileValue, progress);
        stopRuntimeIfRunning(progress);
        extractZip(zipFile, Path.of(wildflyHome));
        setupManagementUser(progress);
        writeInstalledVersion(targetVersion);
        remoteSha1.ifPresent(sha1 -> {
            try { writeInstalledSha1(sha1); }
            catch (ProvisioningException e) { log.warn("Não foi possível gravar {}: {}", SHA_MARKER, e.getMessage()); }
        });

        log.info("WildFly {} instalado com sucesso em {}", targetVersion, wildflyHome);
        return true;
    }

    /**
     * Resolve a última versão disponível no Reposilite e garante que está instalada.
     * Usa {@code WILDFLY_PROVISIONING_VERSION} se fixado; caso contrário consulta o
     * maven-metadata.xml do Reposilite.
     *
     * @return true se houve instalação, false se já estava na versão correta
     */
    public boolean ensureLatestVersion(Consumer<String> progress) throws ProvisioningException {
        if (fixedVersion.filter(v -> !v.isBlank()).isPresent()) {
            return ensureVersion(fixedVersion.get(), progress);
        }
        progress.accept("Consultando versão mais recente no Reposilite...");
        String latest = metadataClient.resolveLatestVersion(
                repositoryUrl, repoPath, GROUP_PATH, ARTIFACT_ID, username, password.orElse(""));
        progress.accept("Versão mais recente: " + latest);
        return ensureVersion(latest, progress);
    }

    /**
     * Só baixa o ZIP para o cache (tempDir), sem extrair.
     * Usa zsync delta se o ZIP já estiver em cache.
     *
     * @return caminho do ZIP em cache
     */
    public Path downloadOnly(String desiredVersion, Consumer<String> progress) throws ProvisioningException {
        String targetVersion = resolveVersion(desiredVersion);
        return downloadZip(targetVersion, progress);
    }

    /**
     * Versão sem argumento de versão: resolve a última versão disponível e baixa.
     */
    public Path downloadOnly(Consumer<String> progress) throws ProvisioningException {
        if (fixedVersion.filter(v -> !v.isBlank()).isPresent()) {
            return downloadOnly(fixedVersion.get(), progress);
        }
        progress.accept("Consultando versão mais recente no Reposilite...");
        String latest = metadataClient.resolveLatestVersion(
                repositoryUrl, repoPath, GROUP_PATH, ARTIFACT_ID, username, password.orElse(""));
        progress.accept("Versão mais recente: " + latest);
        return downloadOnly(latest, progress);
    }

    /**
     * Extrai e instala a partir do ZIP já presente no cache (tempDir).
     * Se {@code version} for null, usa o ZIP mais recente encontrado no cache.
     */
    public boolean applyFromCache(String version, Consumer<String> progress) throws ProvisioningException {
        Path zipFile = findCachedZip(version);
        if (zipFile == null) {
            String msg = version != null
                    ? "ZIP da versão " + version + " não encontrado em " + tempDir + " — execute download-server primeiro"
                    : "Nenhum ZIP encontrado em " + tempDir + " — execute download-server primeiro";
            throw new ProvisioningException(msg, null);
        }

        String targetVersion = version != null ? version : extractVersionFromFilename(zipFile.getFileName().toString());

        String cachedSha1 = FileChecksum.sha1OrNull(zipFile);
        String installedSha1 = readInstalledSha1();
        log.trace("applyFromCache: zip={} versão={} sha1Cache={} sha1Instalado={}",
                zipFile.getFileName(), targetVersion,
                cachedSha1 != null ? cachedSha1.substring(0, 8) + "..." : "erro ao calcular",
                installedSha1 != null ? installedSha1.substring(0, 8) + "..." : "ausente");

        if (cachedSha1 != null && installedSha1 != null && cachedSha1.equals(installedSha1)) {
            log.debug("applyFromCache: SHA-1 confere ({}) — nada a fazer", cachedSha1.substring(0, 8) + "...");
            progress.accept("WildFly já está na build atual (SHA-1 confere) — nenhuma ação necessária");
            return false;
        }

        if (installedSha1 == null) {
            log.debug("applyFromCache: SHA-1 instalado ausente — aplicando mesmo que versão lógica seja igual");
        } else {
            log.debug("applyFromCache: SHA-1 diverge (cache={} instalado={}) — aplicando",
                    cachedSha1 != null ? cachedSha1.substring(0, 8) + "..." : "incalculável",
                    installedSha1.substring(0, 8) + "...");
        }

        progress.accept("Aplicando WildFly " + targetVersion + " a partir do cache...");
        extractZip(zipFile, Path.of(wildflyHome));
        writeInstalledVersion(targetVersion);
        if (cachedSha1 != null) {
            try { writeInstalledSha1(cachedSha1); }
            catch (ProvisioningException e) { log.warn("Não foi possível gravar {}: {}", SHA_MARKER, e.getMessage()); }
        }
        progress.accept("WildFly " + targetVersion + " aplicado com sucesso em " + wildflyHome);
        return true;
    }

    // ── privados ────────────────────────────────────────────────────────────────

    private void stopRuntimeIfRunning(Consumer<String> progress) {
        // Usa checagem de porta em vez de detectState() para contornar limitação do
        // ProcessHandle.allProcesses() no Native Image Windows (commandLine() retorna empty).
        if (!isManagementPortOpen()) return;
        progress.accept("Parando WildFly antes de extrair nova versão...");
        log.info("Porta de management aberta — parando WildFly antes do provisionamento");
        driver.stop();
        // Aguarda a porta fechar usando o mesmo timeout do driver.
        // Se não fechar no tempo, tenta forceKill e aguarda mais 15s.
        if (!waitForPortClose(stopTimeoutSeconds)) {
            log.warn("WildFly não parou em {}s via stop() — tentando forceKill", stopTimeoutSeconds);
            progress.accept("  Timeout de stop — forçando encerramento...");
            driver.forceKill();
            if (!waitForPortClose(15)) {
                log.error("WildFly ainda na porta de management após forceKill — extração pode falhar");
                progress.accept("  AVISO: WildFly não confirmou encerramento — tentando extração mesmo assim");
            }
        }
    }

    private boolean isManagementPortOpen() {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("localhost", managementPort), 1000);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    // Aguarda a porta fechar por até timeoutSeconds. Retorna true se fechou, false se expirou.
    private boolean waitForPortClose(int timeoutSeconds) {
        for (int i = 0; i < timeoutSeconds; i++) {
            if (!isManagementPortOpen()) return true;
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    /**
     * Cria o usuário admin escrevendo diretamente no mgmt-users.properties,
     * e grava as credenciais no {wildfly.home}/.env (lido pelos scripts de serviço
     * via systemd/init.d/Windows service wrapper para exportar as variáveis de ambiente
     * antes de iniciar o WildFly).
     * Se as credenciais já existirem no .env do WildFly, não recria o usuário.
     */
    void setupManagementUser(Consumer<String> progress) {
        Path wildflyEnvFile = Path.of(wildflyHome, ".env");
        if (envFileHasMgmtCredentials(wildflyEnvFile)) {
            log.debug("Credenciais de management já presentes em {} — não recriando usuário", wildflyEnvFile);
            return;
        }

        String password = generatePassword(12);
        try {
            writeMgmtUserProperties(password);
            appendWildflyEnvVars(wildflyEnvFile, password);
            progress.accept("  Usuário admin criado na management API do WildFly");
            log.info("Usuário admin configurado na management API; credenciais gravadas em {}", wildflyEnvFile);
        } catch (Exception e) {
            // Não impede o provisionamento — o driver lida com 401 sem credenciais
            log.warn("Não foi possível criar usuário admin no WildFly: {}", e.getMessage());
            progress.accept("  AVISO: falha ao criar usuário admin na management API: " + e.getMessage());
        }
    }

    private boolean envFileHasMgmtCredentials(Path envFile) {
        if (!Files.exists(envFile)) return false;
        try {
            String content = Files.readString(envFile);
            return content.contains("WILDFLY_MGMT_USER=") && content.contains("WILDFLY_MGMT_PASSWORD=");
        } catch (IOException e) {
            return false;
        }
    }

    // Escreve o hash MD5(admin:ManagementRealm:password) diretamente no mgmt-users.properties.
    // O add-user.sh não está presente no WildFly provisionado (slim), então usamos o mesmo
    // algoritmo que ele usaria: HEX(MD5("username:realm:password")).
    private void writeMgmtUserProperties(String password) throws ProvisioningException {
        Path propsFile = Path.of(wildflyHome, "standalone", "configuration", "mgmt-users.properties");
        if (!Files.exists(propsFile)) {
            throw new ProvisioningException("mgmt-users.properties não encontrado em " + propsFile, null);
        }
        try {
            String hash = md5Hex("admin:ManagementRealm:" + password);
            String existing = Files.readString(propsFile);
            // Remove entrada anterior de admin se existir
            String cleaned = existing.lines()
                    .filter(line -> !line.startsWith("admin="))
                    .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
            Files.writeString(propsFile, cleaned + "admin=" + hash + "\n");
        } catch (IOException e) {
            throw new ProvisioningException("Falha ao escrever mgmt-users.properties: " + e.getMessage(), e);
        }
    }

    static String md5Hex(String input) throws ProvisioningException {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new ProvisioningException("MD5 não disponível", e);
        }
    }

    private void appendWildflyEnvVars(Path envFile, String mgmtPassword) throws ProvisioningException {
        StringBuilder block = new StringBuilder();

        // Banco de dados — derivado do .env do agente (DB_USERNAME → DB_USER conforme standalone.xml)
        dbUrl.filter(v -> !v.isBlank()).ifPresent(v ->
                block.append("\n# Banco de dados Oracle\n")
                     .append("DB_URL=").append(v).append("\n"));
        dbUsername.filter(v -> !v.isBlank()).ifPresent(v ->
                block.append("DB_USER=").append(v).append("\n"));
        dbPassword.filter(v -> !v.isBlank()).ifPresent(v ->
                block.append("DB_PASSWORD=").append(v).append("\n"));

        // Credenciais da management API
        block.append("\n# Credenciais do usuário admin da management API (porta 9990)\n")
             .append("WILDFLY_MGMT_USER=admin\n")
             .append("WILDFLY_MGMT_PASSWORD=").append(mgmtPassword).append("\n");

        try {
            Files.createDirectories(envFile.getParent());
            if (Files.exists(envFile)) {
                Files.writeString(envFile, Files.readString(envFile) + block,
                        java.nio.file.StandardOpenOption.WRITE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                Files.writeString(envFile, block.toString().stripLeading());
            }
        } catch (IOException e) {
            throw new ProvisioningException("Não foi possível gravar variáveis em " + envFile, e);
        }
    }

    static String generatePassword(int length) {
        // Caracteres seguros para senhas WildFly add-user: sem espaço, aspas ou $
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789@#!%&";
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(rng.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private String resolveVersion(String desiredVersion) {
        return fixedVersion.filter(v -> !v.isBlank()).orElse(desiredVersion);
    }

    String buildZipUrl(String version) {
        return buildZipUrl(version, version);
    }

    // fileValue é o valor concreto do filename (pode ser timestamp para SNAPSHOTs)
    private String buildZipUrl(String version, String fileValue) {
        // http://host/repo/br/com/wonder/wildfly-provisioning/VER/wildfly-provisioning-FILEVAL-dist.zip
        String filename = ARTIFACT_ID + "-" + fileValue + "-" + CLASSIFIER + ".zip";
        return repositoryUrl.stripTrailing() + "/" + repoPath
                + "/" + GROUP_PATH + "/" + ARTIFACT_ID + "/" + version + "/" + filename;
    }

    Path downloadZip(String version, Consumer<String> progress) throws ProvisioningException {
        String fileValue = metadataClient.resolveSnapshotValue(
                repositoryUrl, repoPath, GROUP_PATH, ARTIFACT_ID,
                version, CLASSIFIER, "zip", username, password.orElse(""));
        return downloadZipByFileValue(version, fileValue, progress);
    }

    // Baixa o ZIP com fileValue já resolvido, evitando segunda chamada ao repositório.
    private Path downloadZipByFileValue(String version, String fileValue, Consumer<String> progress)
            throws ProvisioningException {
        String zipUrl = buildZipUrl(version, fileValue);
        String zsyncUrl = zipUrl + ".zsync";
        String filename = ARTIFACT_ID + "-" + version + "-" + CLASSIFIER + ".zip";
        Path outFile = Path.of(tempDir).resolve(filename);

        log.info("Baixando WildFly {} via zsync — {}", version, zsyncUrl);
        try {
            return zsyncHelper.downloadNoPad(zsyncUrl, zipUrl, outFile, progress);
        } catch (ZsyncDownloadHelper.DownloadHelperException e) {
            throw new ProvisioningException(e.getMessage(), e.getCause());
        }
    }

    void extractZip(Path zipFile, Path targetDir) throws ProvisioningException {
        log.info("Extraindo {} → {}", zipFile.getFileName(), targetDir);
        boolean posixSupported = FileSystems.getDefault()
                .supportedFileAttributeViews().contains("posix");
        // ZipFile (em vez de ZipInputStream) lê o Central Directory e popula externalAttributes
        try (ZipFile zf = new ZipFile(zipFile.toFile())) {
            Files.createDirectories(targetDir);
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path dest = targetDir.resolve(stripFirstComponent(entry.getName())).normalize();
                if (!dest.startsWith(targetDir)) {
                    throw new ProvisioningException("Entrada ZIP fora do diretório alvo: " + entry.getName(), null);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    try (InputStream in = zf.getInputStream(entry)) {
                        Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (posixSupported) {
                        applyUnixPermissions(dest, entry);
                    }
                }
            }
        } catch (ProvisioningException e) {
            throw e;
        } catch (IOException e) {
            throw new ProvisioningException("Falha ao extrair ZIP do WildFly: " + zipFile, e);
        }
    }

    private void applyUnixPermissions(Path dest, ZipEntry entry) {
        int unixMode = readExternalAttributes(entry) & 0777;
        if (unixMode == 0) return;
        try {
            Files.setPosixFilePermissions(dest, PosixFilePermissions.fromString(octalModeToString(unixMode)));
        } catch (IOException e) {
            log.warn("Não foi possível aplicar permissões em {}: {}", dest, e.getMessage());
        }
    }

    // Lê o campo interno do ZipEntry que contém o unix mode (via ZipFile/Central Directory).
    // ZipFile armazena o campo como o unix mode word completo (ex: 0o100755 = 0x81ED).
    // As permissões são os bits 0..8 (0777 mask).
    // O nome do campo mudou entre versões do JDK:
    //   Java ≤24: "extraAttributes"  (tipo int)
    //   Java  25+: "externalFileAttributes" (tipo int)
    // Requer --add-opens java.base/java.util.zip=ALL-UNNAMED (configurado no pom e native-image.properties).
    private static int readExternalAttributes(ZipEntry entry) {
        for (String name : new String[]{"externalFileAttributes", "extraAttributes"}) {
            try {
                var field = ZipEntry.class.getDeclaredField(name);
                field.setAccessible(true);
                Object val = field.get(entry);
                if (val == null) continue;
                int raw = (int) val;
                // valor inválido/não populado (ZipInputStream deixa -1)
                if (raw == -1 || raw == 0) return 0;
                return raw & 0xFFFF;
            } catch (NoSuchFieldException ignored) {
                // tenta o próximo nome
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }

    // Converte modo octal (ex: 0755) para string rwx (ex: "rwxr-xr-x")
    private static String octalModeToString(int mode) {
        char[] p = new char[9];
        p[0] = (mode & 0400) != 0 ? 'r' : '-';
        p[1] = (mode & 0200) != 0 ? 'w' : '-';
        p[2] = (mode & 0100) != 0 ? 'x' : '-';
        p[3] = (mode & 0040) != 0 ? 'r' : '-';
        p[4] = (mode & 0020) != 0 ? 'w' : '-';
        p[5] = (mode & 0010) != 0 ? 'x' : '-';
        p[6] = (mode & 0004) != 0 ? 'r' : '-';
        p[7] = (mode & 0002) != 0 ? 'w' : '-';
        p[8] = (mode & 0001) != 0 ? 'x' : '-';
        return new String(p);
    }

    // Remove o primeiro segmento do path (ex: "wildfly/bin/..." → "bin/...")
    private static String stripFirstComponent(String entryName) {
        int slash = entryName.indexOf('/');
        if (slash < 0 || slash == entryName.length() - 1) return entryName;
        return entryName.substring(slash + 1);
    }

    String readInstalledVersion() {
        Path marker = Path.of(wildflyHome, VERSION_MARKER);
        try {
            return Files.exists(marker) ? Files.readString(marker).trim() : null;
        } catch (IOException e) {
            log.warn("Não foi possível ler {}: {}", marker, e.getMessage());
            return null;
        }
    }

    void writeInstalledVersion(String version) throws ProvisioningException {
        Path marker = Path.of(wildflyHome, VERSION_MARKER);
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, version);
        } catch (IOException e) {
            throw new ProvisioningException("Não foi possível escrever " + marker, e);
        }
    }

    String readInstalledSha1() {
        Path marker = Path.of(wildflyHome, SHA_MARKER);
        try {
            return Files.exists(marker) ? Files.readString(marker).trim() : null;
        } catch (IOException e) {
            log.warn("Não foi possível ler {}: {}", marker, e.getMessage());
            return null;
        }
    }

    void writeInstalledSha1(String sha1) throws ProvisioningException {
        Path marker = Path.of(wildflyHome, SHA_MARKER);
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, sha1);
        } catch (IOException e) {
            throw new ProvisioningException("Não foi possível escrever " + marker, e);
        }
    }

    private Path findCachedZip(String version) {
        Path dir = Path.of(tempDir);
        if (!Files.isDirectory(dir)) return null;
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        if (!name.startsWith(ARTIFACT_ID) || !name.endsWith("-" + CLASSIFIER + ".zip")) return false;
                        return version == null || name.contains(version);
                    })
                    .max(java.util.Comparator.comparingLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException e) { return 0L; }
                    }))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static String extractVersionFromFilename(String filename) {
        // wildfly-provisioning-1.0.0-SNAPSHOT-dist.zip → 1.0.0-SNAPSHOT
        String prefix = ARTIFACT_ID + "-";
        String suffix = "-" + CLASSIFIER + ".zip";
        if (filename.startsWith(prefix) && filename.endsWith(suffix)) {
            return filename.substring(prefix.length(), filename.length() - suffix.length());
        }
        return filename;
    }

    public static class ProvisioningException extends Exception {
        public ProvisioningException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
