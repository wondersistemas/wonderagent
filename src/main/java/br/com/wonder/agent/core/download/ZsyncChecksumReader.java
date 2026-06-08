package br.com.wonder.agent.core.download;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * Lê o campo SHA-1 do cabeçalho de um arquivo .zsync remoto sem baixar o artefato inteiro.
 *
 * O formato .zsync tem linhas "Chave: valor" antes de uma linha em branco, por exemplo:
 *   zsync: 0.6.2
 *   Filename: wnfe-war-2.5.0.war
 *   SHA-1: 4e1243bd22c66e76c2ba9eddc1f91394e57f9f83
 *   ...
 *   (linha em branco)
 *   (dados binários dos blocos)
 */
@Slf4j
@ApplicationScoped
public class ZsyncChecksumReader {

    @ConfigProperty(name = "download.repository.username")
    String username;

    @ConfigProperty(name = "download.repository.password")
    Optional<String> password;

    /**
     * Faz GET no .zsync e retorna o valor do campo SHA-1, em minúsculas.
     * Retorna Optional.empty() se a URL não for acessível ou o campo não existir.
     */
    public Optional<String> readSha1(String zsyncUrl) {
        log.debug("Lendo SHA-1 do .zsync: {}", zsyncUrl);
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(zsyncUrl))
                    .timeout(Duration.ofSeconds(10))
                    .GET();

            boolean comAuth = username != null && !username.isBlank();
            if (comAuth) {
                String creds = Base64.getEncoder()
                        .encodeToString((username + ":" + password.orElse("")).getBytes());
                reqBuilder.header("Authorization", "Basic " + creds);
            }
            log.trace("GET {} (auth={})", zsyncUrl, comAuth);

            HttpResponse<java.io.InputStream> response = client.send(
                    reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

            log.trace("Resposta HTTP {} para {}", response.statusCode(), zsyncUrl);
            if (response.statusCode() != 200) {
                log.warn("Resposta HTTP {} ao buscar .zsync: {}", response.statusCode(), zsyncUrl);
                return Optional.empty();
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.trace("  .zsync header: {}", line);
                    if (line.isEmpty()) break; // fim do cabeçalho .zsync
                    if (line.startsWith("SHA-1:")) {
                        String sha1 = line.substring("SHA-1:".length()).trim().toLowerCase();
                        log.debug("SHA-1 lido do .zsync: {}", sha1);
                        return Optional.of(sha1);
                    }
                }
            }

            log.warn("Campo SHA-1 não encontrado no cabeçalho do .zsync: {}", zsyncUrl);
            return Optional.empty();

        } catch (Exception e) {
            log.warn("Não foi possível ler SHA-1 do .zsync ({}): {}", zsyncUrl, e.getMessage());
            return Optional.empty();
        }
    }
}
