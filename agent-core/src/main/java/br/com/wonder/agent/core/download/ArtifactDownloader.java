package br.com.wonder.agent.core.download;

import br.com.wonder.agent.model.deploy.Artifact;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.function.Consumer;

@Slf4j
@ApplicationScoped
public class ArtifactDownloader {

    @ConfigProperty(name = "download.temp-dir")
    String tempDir;

    @ConfigProperty(name = "download.repository.username")
    String username;

    @ConfigProperty(name = "download.repository.password")
    Optional<String> password;

    @Inject
    ZsyncDownloadHelper zsyncHelper;

    ArtifactDownloader() {}

    ArtifactDownloader(ZsyncDownloadHelper zsyncHelper) {
        this.zsyncHelper = zsyncHelper;
    }

    public Artifact download(Artifact artifact) throws DownloadException {
        return download(artifact, msg -> {}, Optional.empty());
    }

    public Artifact download(Artifact artifact, Consumer<String> progress) throws DownloadException {
        return download(artifact, progress, Optional.empty());
    }

    public Artifact download(Artifact artifact, Consumer<String> progress,
                             Optional<Path> installedWarHint) throws DownloadException {
        if (artifact.warUrl() == null || artifact.warUrl().isBlank()) {
            throw new DownloadException(
                "warUrl vazio no desired-state — verifique a configuração do servidor central para clientId=" + artifact.artifactId(), null);
        }

        String zsyncUrl = artifact.warUrl().replaceAll("\\.war$", ".zsync");
        log.info("Baixando artefato: {} via zsync — {}", artifact.coordinates(), zsyncUrl);
        progress.accept("  URL: " + zsyncUrl);

        Path outDir = Path.of(tempDir);
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new DownloadException("Não foi possível criar diretório temporário: " + outDir, e);
        }

        String host = URI.create(artifact.warUrl()).getHost();
        Path outputFile = outDir.resolve(filename(artifact.warUrl()));

        logZsyncHeaders(zsyncUrl, host);

        // Se o WAR não está em cache mas existe instalado, copia como base para delta
        if (!Files.exists(outputFile)) {
            prepareInstalledWarHint(outputFile, installedWarHint, progress);
        }

        try {
            Path result = zsyncHelper.download(zsyncUrl, artifact.warUrl(), outputFile, progress);
            long sizePadded = ZsyncDownloadHelper.sizeOf(result);
            String sizeStr = sizePadded >= 0 ? (sizePadded / (1024 * 1024)) + " MB" : "tamanho desconhecido";
            progress.accept("  Arquivo salvo em: " + result + " (" + sizeStr + ")");
            writeSha1Cache(result);
            return artifact.withLocalFile(result);
        } catch (ZsyncDownloadHelper.DownloadHelperException e) {
            throw new DownloadException("Falha no download de " + artifact.coordinates(), e);
        }
    }

    /**
     * Retorna o SHA-1 do WAR em cache no temp dir (gravado após o último download),
     * ou empty se o cache não existe ou o WAR foi removido.
     * Usado pelo AgentOrchestrator para evitar re-download quando o hash remoto já confere
     * com o arquivo baixado mas ainda não deployado.
     */
    public Optional<String> readCachedSha1(String warUrl) {
        Path sha1File = Path.of(tempDir).resolve(filename(warUrl) + ".sha1");
        Path warFile  = Path.of(tempDir).resolve(filename(warUrl));
        if (!Files.exists(sha1File) || !Files.exists(warFile)) return Optional.empty();
        try {
            return Optional.of(Files.readString(sha1File).trim());
        } catch (IOException e) {
            log.warn("Não foi possível ler cache de SHA-1 ({}): {}", sha1File, e.getMessage());
            return Optional.empty();
        }
    }

    // Copia WAR instalado como base para delta quando não há cache local
    private void prepareInstalledWarHint(Path outputFile, Optional<Path> installedWarHint,
                                         Consumer<String> progress) {
        if (installedWarHint.isEmpty() || !Files.exists(installedWarHint.get())) return;
        long hintSize = ZsyncDownloadHelper.sizeOf(installedWarHint.get());
        if (hintSize % 4096 == 0) {
            try {
                Path zsOld = outputFile.resolveSibling(outputFile.getFileName() + ".zs-old");
                Files.copy(installedWarHint.get(), zsOld, StandardCopyOption.REPLACE_EXISTING);
                // Renomeia para o nome que o helper vai procurar como base
                Files.move(zsOld, outputFile, StandardCopyOption.REPLACE_EXISTING);
                log.debug("WAR instalado copiado como base para delta: {}", installedWarHint.get());
                progress.accept("  Usando WAR instalado como base — usando delta (zsync)");
            } catch (IOException e) {
                log.warn("Não foi possível copiar WAR instalado para base zsync: {}", e.getMessage());
                progress.accept("  Nenhuma versão anterior em cache — download completo");
            }
        } else {
            log.warn("WAR instalado não está alinhado ao blocksize ({} bytes) — ignorando hint, download completo", hintSize);
            progress.accept("  Nenhuma versão anterior em cache — download completo");
        }
    }

    private void logZsyncHeaders(String zsyncUrl, String host) {
        if (!log.isTraceEnabled()) return;
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();
            String creds = java.util.Base64.getEncoder()
                    .encodeToString((username + ":" + password.orElse("")).getBytes());
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(zsyncUrl))
                    .header("Authorization", "Basic " + creds)
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();
            java.net.http.HttpResponse<java.io.InputStream> resp = client.send(req,
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                log.trace(".zsync remoto retornou HTTP {}", resp.statusCode());
                return;
            }
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(resp.body()))) {
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.startsWith("Length:") || line.startsWith("Blocksize:")
                            || line.startsWith("SHA-1:") || line.startsWith("MTime:")) {
                        log.trace(".zsync {}", line);
                    }
                }
            }
        } catch (Exception e) {
            log.trace("Não foi possível ler cabeçalho .zsync: {}", e.getMessage());
        }
    }

    private void writeSha1Cache(Path warFile) {
        try {
            byte[] data = Files.readAllBytes(warFile);
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
            String sha1 = java.util.HexFormat.of().formatHex(digest.digest(data));
            Files.writeString(warFile.resolveSibling(warFile.getFileName() + ".sha1"), sha1);
            log.debug("SHA-1 do WAR em cache: {}", sha1);
        } catch (Exception e) {
            log.warn("Não foi possível gravar cache de SHA-1 para {}: {}", warFile.getFileName(), e.getMessage());
        }
    }

    private static String filename(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    public static class DownloadException extends Exception {
        public DownloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
