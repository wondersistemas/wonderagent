package br.com.wonder.agent.driver.wildfly;

import br.com.wonder.agent.model.state.RuntimeState;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testa detectState() sem WildFly real usando um servidor HTTP mínimo
 * que simula a management API.
 *
 * isProcessAlive() e findWildflyPid() são sobrescritos nas subclasses de teste
 * para evitar varrer todos os processos do sistema em ambiente de CI.
 */
class WildflyDriverDetectStateTest {

    @TempDir Path tempDir;

    HttpServer managementServer;
    int managementPort;

    /** Subclasse de teste que simula processo vivo (PID fictício). */
    static class TestableWildflyDriver extends WildflyDriver {
        @Override
        OptionalLong findWildflyPid() {
            return OptionalLong.of(99999L);
        }
    }

    /** Subclasse de teste que simula processo ausente. */
    static class StoppedWildflyDriver extends WildflyDriver {
        @Override
        OptionalLong findWildflyPid() {
            return OptionalLong.empty();
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

    private WildflyDriver driverComPortaAberta() throws Exception {
        WildflyDriver driver = new TestableWildflyDriver();
        setField(driver, "wildflyHome", tempDir.toString());
        setField(driver, "managementPort", managementPort);
        setField(driver, "deployPath", tempDir.toString());
        setField(driver, "artifactName", "wnfe.war");
        setField(driver, "startTimeoutSeconds", 1);
        setField(driver, "stopTimeoutSeconds", 1);
        setField(driver, "healthCheckUrl", "http://localhost:8080/probusweb/health");
        setField(driver, "healthCheckTimeoutSeconds", 5);
        setField(driver, "healthCheckRetries", 1);
        return driver;
    }

    @Test
    void detectState_quandoManagementPortAbertaERespondendoRunning_retornaRunning() throws Exception {
        managementServer.createContext("/management", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            byte[] body;
            if (query != null && query.contains("name=server-state")) {
                body = "\"running\"".getBytes();
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.getResponseBody().close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });

        RuntimeState state = driverComPortaAberta().detectState();
        assertThat(state).isEqualTo(RuntimeState.RUNNING);
    }

    @Test
    void detectState_quandoManagementPortAbertaMasRespondendoStarting_retornaHung() throws Exception {
        managementServer.createContext("/management", exchange -> {
            byte[] body = "\"starting\"".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });

        RuntimeState state = driverComPortaAberta().detectState();
        assertThat(state).isEqualTo(RuntimeState.HUNG);
    }

    @Test
    void detectState_quandoDeploymentFalhou_retornaPartial() throws Exception {
        managementServer.createContext("/management", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            byte[] body;
            if (query != null && query.contains("server-state")) {
                body = "\"running\"".getBytes();
            } else if (query != null && query.contains("name=status")) {
                body = "\"FAILED\"".getBytes();
            } else {
                body = "1000".getBytes();
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });

        RuntimeState state = driverComPortaAberta().detectState();
        assertThat(state).isEqualTo(RuntimeState.PARTIAL);
    }

    @Test
    void detectState_quandoDeploymentStopped_retornaPartial() throws Exception {
        managementServer.createContext("/management", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            byte[] body;
            if (query != null && query.contains("server-state")) {
                body = "\"running\"".getBytes();
            } else if (query != null && query.contains("name=status")) {
                body = "\"STOPPED\"".getBytes();
            } else {
                body = "1000".getBytes();
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });

        RuntimeState state = driverComPortaAberta().detectState();
        assertThat(state).isEqualTo(RuntimeState.PARTIAL);
    }

    @Test
    void detectState_quandoManagementRetorna401SemCredenciais_retornaRunning() throws Exception {
        managementServer.createContext("/management", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.getResponseBody().close();
        });

        RuntimeState state = driverComPortaAberta().detectState();
        assertThat(state).isEqualTo(RuntimeState.RUNNING);
    }

    @Test
    void detectState_quandoPortaManagementFechada_retornaHung() throws Exception {
        // Fecha o servidor para simular porta fechada com processo vivo
        managementServer.stop(0);

        WildflyDriver driver = new TestableWildflyDriver();
        setField(driver, "wildflyHome", tempDir.toString());
        setField(driver, "managementPort", managementPort);
        setField(driver, "deployPath", tempDir.toString());
        setField(driver, "artifactName", "wnfe.war");
        setField(driver, "startTimeoutSeconds", 1);
        setField(driver, "stopTimeoutSeconds", 1);
        setField(driver, "healthCheckUrl", "http://localhost:8080/probusweb/health");

        RuntimeState state = driver.detectState();
        assertThat(state).isEqualTo(RuntimeState.HUNG);
    }

    @Test
    void detectState_quandoProcessoNaoExiste_retornaStopped() throws Exception {
        WildflyDriver driver = new StoppedWildflyDriver();
        setField(driver, "wildflyHome", tempDir.toString());
        setField(driver, "managementPort", managementPort);
        setField(driver, "deployPath", tempDir.toString());
        setField(driver, "artifactName", "wnfe.war");
        setField(driver, "startTimeoutSeconds", 1);
        setField(driver, "stopTimeoutSeconds", 1);
        setField(driver, "healthCheckUrl", "http://localhost:8080/probusweb/health");

        RuntimeState state = driver.detectState();
        assertThat(state).isEqualTo(RuntimeState.STOPPED);
    }

    // ── findWildflyPid — filtragem por wildflyHome ────────────────────────────

    @Test
    void findWildflyPid_filtraPorWildflyHome_naoRetornaOutrosProcessos() throws Exception {
        // Usa o driver real para verificar que o método filtra corretamente.
        // Em ambiente de teste não há WildFly real, então o resultado deve ser vazio.
        WildflyDriver driver = new WildflyDriver();
        setField(driver, "wildflyHome", "/caminho/que/nao/existe/wildfly-test-" + System.nanoTime());
        setField(driver, "managementPort", 9990);
        setField(driver, "deployPath", tempDir.toString());
        setField(driver, "artifactName", "wnfe.war");
        setField(driver, "startTimeoutSeconds", 1);
        setField(driver, "stopTimeoutSeconds", 1);
        setField(driver, "healthCheckUrl", "http://localhost:8080/probusweb/health");

        // Não deve encontrar nenhum processo com esse caminho fictício
        assertThat(driver.findWildflyPid()).isEmpty();
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
