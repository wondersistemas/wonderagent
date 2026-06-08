package br.com.wonder.agent.driver.wildfly;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WildflyDriverStopTest {

    @TempDir Path tempDir;

    HttpServer managementServer;
    int managementPort;

    /** Driver com processo sempre vivo, para stop() não retornar antes de tentar o shutdown. */
    static class AlwaysAliveDriver extends WildflyDriver {
        private boolean reportAlive = true;

        @Override
        OptionalLong findWildflyPid() {
            return reportAlive ? OptionalLong.of(99999L) : OptionalLong.empty();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        managementServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        managementPort = managementServer.getAddress().getPort();
        managementServer.start();
    }

    @AfterEach
    void tearDown() {
        managementServer.stop(0);
    }

    private AlwaysAliveDriver driver() throws Exception {
        AlwaysAliveDriver d = new AlwaysAliveDriver();
        setField(d, "wildflyHome", tempDir.toString());
        setField(d, "managementPort", managementPort);
        setField(d, "deployPath", tempDir.toString());
        setField(d, "artifactName", "wnfe.war");
        setField(d, "startTimeoutSeconds", 1);
        setField(d, "stopTimeoutSeconds", 1);
        setField(d, "healthCheckUrl", "http://localhost:8080/probusweb/health");
        setField(d, "healthCheckTimeoutSeconds", 5);
        setField(d, "healthCheckRetries", 1);
        setField(d, "serviceMode", false);
        return d;
    }

    @Test
    void stop_enviaPostShutdownParaManagementApi() throws Exception {
        AtomicBoolean shutdownRecebido = new AtomicBoolean(false);

        managementServer.createContext("/management", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            if ("POST".equals(exchange.getRequestMethod()) && body.contains("shutdown")) {
                shutdownRecebido.set(true);
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });

        AlwaysAliveDriver d = driver();
        // stop() chama shutdown e depois waitForState(STOPPED, 1s).
        // Como reportAlive=true o timeout expira, mas o POST de shutdown já foi enviado.
        d.stop();

        assertThat(shutdownRecebido.get()).isTrue();
    }

    @Test
    void stop_quandoManagementApiRetorna401SemCredenciais_naoLancaExcecao() throws Exception {
        managementServer.createContext("/management", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.getResponseBody().close();
        });

        AlwaysAliveDriver d = driver();
        // Não deve lançar exceção — apenas loga aviso e aguarda timeout
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> d.stop());
    }

    @Test
    void stop_quandoManagementApiRetorna401ComCredenciais_enviasegundoRequestComDigest() throws Exception {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "WILDFLY_MGMT_USER=admin\nWILDFLY_MGMT_PASSWORD=secret\n");

        AtomicBoolean segundoRequestRecebido = new AtomicBoolean(false);
        managementServer.createContext("/management", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null && auth.startsWith("Digest ")) {
                segundoRequestRecebido.set(true);
                exchange.sendResponseHeaders(200, 0);
            } else {
                exchange.getResponseHeaders().add("WWW-Authenticate",
                        "Digest realm=\"ManagementRealm\", nonce=\"abc\", qop=\"auth\"");
                exchange.sendResponseHeaders(401, -1);
            }
            exchange.getResponseBody().close();
        });

        AlwaysAliveDriver d = driver();
        d.stop();

        assertThat(segundoRequestRecebido.get()).isTrue();
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                var field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName + " não encontrado na hierarquia de " + target.getClass());
    }
}
