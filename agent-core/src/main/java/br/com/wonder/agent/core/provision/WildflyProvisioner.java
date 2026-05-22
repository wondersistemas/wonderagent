package br.com.wonder.agent.core.provision;

import br.com.wonder.agent.core.download.DownloadProgressObserver;
import com.salesforce.zsync.Zsync;
import com.salesforce.zsync.ZsyncException;
import com.salesforce.zsync.http.Credentials;
import com.squareup.okhttp.OkHttpClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Enumeration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
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

    // Versão fixada via .env — WILDFLY_PROVISIONING_VERSION=x.y
    // Se ausente, usa a versão passada pelo servidor central ou resolvida do Reposilite.
    @ConfigProperty(name = "wildfly.provisioning.fixed-version", defaultValue = "")
    Optional<String> fixedVersion;

    @ConfigProperty(name = "wildfly.provisioning.repo-path",
                    defaultValue = "wnfe-releases")
    String repoPath;

    private final Zsync zsync;

    @Inject
    ReposiliteMetadataClient metadataClient;

    WildflyProvisioner() {
        OkHttpClient http = new OkHttpClient();
        http.setReadTimeout(30, TimeUnit.SECONDS);
        http.setWriteTimeout(30, TimeUnit.SECONDS);
        this.zsync = new Zsync(http);
    }

    // visível para testes
    WildflyProvisioner(Zsync zsync) {
        this.zsync = zsync;
    }

    WildflyProvisioner(Zsync zsync, ReposiliteMetadataClient metadataClient) {
        this.zsync = zsync;
        this.metadataClient = metadataClient;
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

        String installed = readInstalledVersion();
        if (targetVersion.equals(installed)) {
            log.debug("WildFly já está na versão {} — nenhuma ação necessária", targetVersion);
            return false;
        }

        log.info("WildFly desatualizado: instalado={}, desejado={} — iniciando provisionamento",
                installed, targetVersion);

        Path zipFile = downloadZip(targetVersion, progress);
        extractZip(zipFile, Path.of(wildflyHome));
        writeInstalledVersion(targetVersion);

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
        String installed = readInstalledVersion();
        if (targetVersion.equals(installed)) {
            progress.accept("WildFly já está na versão " + targetVersion + " — nenhuma ação necessária");
            return false;
        }

        progress.accept("Aplicando WildFly " + targetVersion + " a partir do cache...");
        extractZip(zipFile, Path.of(wildflyHome));
        writeInstalledVersion(targetVersion);
        progress.accept("WildFly " + targetVersion + " aplicado com sucesso em " + wildflyHome);
        return true;
    }

    // ── privados ────────────────────────────────────────────────────────────────

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
        // Para SNAPSHOTs, resolve o valor concreto (timestamp+build) do arquivo no repositório
        String fileValue = metadataClient.resolveSnapshotValue(
                repositoryUrl, repoPath, GROUP_PATH, ARTIFACT_ID,
                version, CLASSIFIER, "zip", username, password.orElse(""));

        String zipUrl = buildZipUrl(version, fileValue);
        String zsyncUrl = zipUrl + ".zsync";
        String host = URI.create(zipUrl).getHost();
        // O arquivo local sempre usa o nome canônico (versão lógica), não o timestamp
        String filename = ARTIFACT_ID + "-" + version + "-" + CLASSIFIER + ".zip";

        Path outDir = Path.of(tempDir);
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new ProvisioningException("Não foi possível criar diretório temporário: " + outDir, e);
        }

        Path outFile = outDir.resolve(filename);

        Zsync.Options options = new Zsync.Options()
                .setOutputFile(outFile)
                .putCredentials(host, new Credentials(username, password.orElse("")));

        if (Files.exists(outFile)) {
            log.debug("ZIP existente encontrado, usando como base para delta: {}", outFile);
            options = options.addInputFile(outFile);
            progress.accept("  Arquivo anterior encontrado — usando delta (zsync)");
        } else {
            progress.accept("  Nenhuma versão anterior em cache — download completo");
        }

        log.info("Baixando WildFly {} via zsync — {}", version, zsyncUrl);
        DownloadProgressObserver observer = new DownloadProgressObserver(progress);
        try {
            progress.accept("  Transferindo...");
            zsync.zsync(URI.create(zsyncUrl), options, observer);
            observer.reportFinal();
            return outFile;
        } catch (ZsyncException e) {
            log.warn("zsync falhou ({}); usando download HTTP direto do ZIP: {}", e.getMessage(), zipUrl);
            progress.accept("  zsync indisponível — download HTTP direto do ZIP");
            return downloadZipDirect(zipUrl, outFile, progress);
        }
    }

    // Fallback: download HTTP direto quando o .zsync não existe no servidor
    private Path downloadZipDirect(String zipUrl, Path outFile, Consumer<String> progress)
            throws ProvisioningException {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(zipUrl).toURL().openConnection();
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(120_000);
            if (username != null && !username.isBlank()) {
                String cred = username + ":" + password.orElse("");
                conn.setRequestProperty("Authorization",
                        "Basic " + Base64.getEncoder().encodeToString(cred.getBytes()));
            }
            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw new ProvisioningException("Acesso negado ao baixar " + zipUrl + " (HTTP 401)", null);
            }
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new ProvisioningException("ZIP não encontrado: " + zipUrl + " (HTTP 404)", null);
            }
            if (status < 200 || status >= 300) {
                throw new ProvisioningException(
                        "Falha HTTP " + status + " ao baixar " + zipUrl, null);
            }
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream()) {
                Files.copy(in, outFile, StandardCopyOption.REPLACE_EXISTING);
            }
            if (total > 0) {
                progress.accept("  Download concluído: " + (total / 1024 / 1024) + " MB");
            } else {
                progress.accept("  Download concluído");
            }
            conn.disconnect();
            return outFile;
        } catch (ProvisioningException e) {
            throw e;
        } catch (IOException e) {
            throw new ProvisioningException("Falha no download HTTP de " + zipUrl, e);
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
