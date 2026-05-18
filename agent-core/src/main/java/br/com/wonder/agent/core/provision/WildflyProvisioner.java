package br.com.wonder.agent.core.provision;

import com.salesforce.zsync.Zsync;
import com.salesforce.zsync.ZsyncException;
import com.salesforce.zsync.http.Credentials;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Obtém e instala o WildFly provisionado (ZIP com classifier=dist) do Reposilite.
 *
 * Usa zsync para delta-transfer. A versão instalada é rastreada em
 * {@code driver.wildfly.home/.wildfly-version}.
 *
 * Versão fixada: variável de ambiente WILDFLY_PROVISIONING_VERSION (via .env).
 * Versão padrão: vem do desired-state do servidor central.
 */
@Slf4j
@ApplicationScoped
public class WildflyProvisioner {

    // groupId como caminho de diretório Maven: br/com/wonder
    private static final String GROUP_PATH = "br/com/wonder";
    private static final String ARTIFACT_ID = "wildfly-provisioning";
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
    // Se ausente, usa a versão passada pelo servidor central.
    @ConfigProperty(name = "wildfly.provisioning.fixed-version", defaultValue = "")
    Optional<String> fixedVersion;

    @ConfigProperty(name = "wildfly.provisioning.repo-path",
                    defaultValue = "wnfe-releases")
    String repoPath;

    private final Zsync zsync;

    WildflyProvisioner() {
        this.zsync = new Zsync();
    }

    // visível para testes
    WildflyProvisioner(Zsync zsync) {
        this.zsync = zsync;
    }

    /**
     * Garante que o WildFly na versão {@code desiredVersion} está instalado em
     * {@code driver.wildfly.home}. Se {@code WILDFLY_PROVISIONING_VERSION} estiver
     * definido no ambiente, usa essa versão em vez da fornecida pelo caller.
     *
     * @return true se houve instalação, false se já estava na versão correta
     */
    public boolean ensureVersion(String desiredVersion) throws ProvisioningException {
        String targetVersion = resolveVersion(desiredVersion);

        String installed = readInstalledVersion();
        if (targetVersion.equals(installed)) {
            log.debug("WildFly já está na versão {} — nenhuma ação necessária", targetVersion);
            return false;
        }

        log.info("WildFly desatualizado: instalado={}, desejado={} — iniciando provisionamento",
                installed, targetVersion);

        String zipUrl = buildZipUrl(targetVersion);
        Path zipFile = downloadZip(zipUrl, targetVersion);
        extractZip(zipFile, Path.of(wildflyHome));
        writeInstalledVersion(targetVersion);

        log.info("WildFly {} instalado com sucesso em {}", targetVersion, wildflyHome);
        return true;
    }

    // ── privados ────────────────────────────────────────────────────────────────

    private String resolveVersion(String desiredVersion) {
        return fixedVersion.filter(v -> !v.isBlank()).orElse(desiredVersion);
    }

    String buildZipUrl(String version) {
        // http://host/repo/br/com/wonder/wildfly-provisioning/VER/wildfly-provisioning-VER-dist.zip
        String filename = ARTIFACT_ID + "-" + version + "-" + CLASSIFIER + ".zip";
        return repositoryUrl.stripTrailing() + "/" + repoPath
                + "/" + GROUP_PATH + "/" + ARTIFACT_ID + "/" + version + "/" + filename;
    }

    private Path downloadZip(String zipUrl, String version) throws ProvisioningException {
        String zsyncUrl = zipUrl + ".zsync";
        String host = URI.create(zipUrl).getHost();
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
        }

        log.info("Baixando WildFly {} via zsync — {}", version, zsyncUrl);
        try {
            zsync.zsync(URI.create(zsyncUrl), options);
            return outFile;
        } catch (ZsyncException e) {
            throw new ProvisioningException(
                    "Falha no download do WildFly " + version + " de " + zsyncUrl, e);
        }
    }

    private void extractZip(Path zipFile, Path targetDir) throws ProvisioningException {
        log.info("Extraindo {} → {}", zipFile.getFileName(), targetDir);
        try {
            Files.createDirectories(targetDir);
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    Path dest = targetDir.resolve(stripFirstComponent(entry.getName())).normalize();
                    if (!dest.startsWith(targetDir)) {
                        throw new ProvisioningException("Entrada ZIP fora do diretório alvo: " + entry.getName(), null);
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(zis, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    zis.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new ProvisioningException("Falha ao extrair ZIP do WildFly: " + zipFile, e);
        }
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

    private void writeInstalledVersion(String version) throws ProvisioningException {
        Path marker = Path.of(wildflyHome, VERSION_MARKER);
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, version);
        } catch (IOException e) {
            throw new ProvisioningException("Não foi possível escrever " + marker, e);
        }
    }

    public static class ProvisioningException extends Exception {
        public ProvisioningException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
