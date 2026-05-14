package br.com.wonder.agent.driver.wildfly;

import br.com.wonder.agent.model.state.RuntimeState;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testa detectState() sem WildFly real usando um servidor HTTP mínimo
 * que simula a management API.
 *
 * Como isProcessAlive() usa tasklist.exe (Windows only), os testes injetam
 * um WildflyDriver com isProcessAlive() sobrescrito via subclasse de teste.
 */
class WildflyDriverDetectStateTest {

    @TempDir Path tempDir;

    HttpServer managementServer;
    int managementPort;

    /** Subclasse de teste que simula processo vivo sem chamar tasklist.exe. */
    static class TestableWildflyDriver extends WildflyDriver {
        @Override
        protected boolean isProcessAlive() {
            return true;
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
                // Status do deployment: 404 = sem deployment com falha
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
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            byte[] body;
            if (path.equals("/management") && query != null && query.contains("server-state")) {
                body = "\"running\"".getBytes();
            } else {
                body = "\"FAILED\"".getBytes();
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });

        RuntimeState state = driverComPortaAberta().detectState();
        assertThat(state).isEqualTo(RuntimeState.PARTIAL);
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
        // Driver padrão sem override — isProcessAlive() retorna false no Linux
        WildflyDriver driver = new WildflyDriver();
        setField(driver, "wildflyHome", tempDir.toString());
        setField(driver, "managementPort", managementPort);
        setField(driver, "deployPath", tempDir.toString());
        setField(driver, "artifactName", "wnfe.war");
        setField(driver, "startTimeoutSeconds", 1);
        setField(driver, "stopTimeoutSeconds", 1);
        setField(driver, "healthCheckUrl", "http://localhost:8080/probusweb/health");

        RuntimeState state = driver.detectState();
        // Linux: tasklist não existe → isProcessAlive() retorna false → STOPPED
        assertThat(state).isEqualTo(RuntimeState.STOPPED);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        // Busca o campo na hierarquia de classes
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
