package br.com.wonder.agent.core.download;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ZsyncChecksumReaderTest {

    HttpServer server;
    int port;
    ZsyncChecksumReader reader;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port = server.getAddress().getPort();
        server.start();

        reader = new ZsyncChecksumReader();
        setField(reader, "username", "");
        setField(reader, "password", Optional.empty());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String zsyncUrl() {
        return "http://localhost:" + port + "/wnfe-war-2.5.0.zsync";
    }

    @Test
    void readSha1_extraiCampoSha1DoHeader() throws Exception {
        String zsyncHeader = """
                zsync: 0.6.2
                Filename: wnfe-war-2.5.0.war
                Blocksize: 2048
                Length: 123456
                Hash-Lengths: 1,4,5
                URL: wnfe-war-2.5.0.war
                SHA-1: 4e1243bd22c66e76c2ba9eddc1f91394e57f9f83

                """;
        server.createContext("/wnfe-war-2.5.0.zsync", exchange -> {
            byte[] body = zsyncHeader.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });

        Optional<String> result = reader.readSha1(zsyncUrl());

        assertThat(result).contains("4e1243bd22c66e76c2ba9eddc1f91394e57f9f83");
    }

    @Test
    void readSha1_normalizaParaMinusculas() throws Exception {
        String zsyncHeader = "SHA-1: 4E1243BD22C66E76C2BA9EDDC1F91394E57F9F83\n\n";
        server.createContext("/wnfe-war-2.5.0.zsync", exchange -> {
            byte[] body = zsyncHeader.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });

        Optional<String> result = reader.readSha1(zsyncUrl());

        assertThat(result).contains("4e1243bd22c66e76c2ba9eddc1f91394e57f9f83");
    }

    @Test
    void readSha1_quandoCampoAusenteNoHeader_retornaEmpty() throws Exception {
        String zsyncHeader = "zsync: 0.6.2\nFilename: wnfe-war-2.5.0.war\n\n";
        server.createContext("/wnfe-war-2.5.0.zsync", exchange -> {
            byte[] body = zsyncHeader.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });

        Optional<String> result = reader.readSha1(zsyncUrl());

        assertThat(result).isEmpty();
    }

    @Test
    void readSha1_quandoServidor404_retornaEmpty() throws Exception {
        server.createContext("/wnfe-war-2.5.0.zsync", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.getResponseBody().close();
        });

        Optional<String> result = reader.readSha1(zsyncUrl());

        assertThat(result).isEmpty();
    }

    @Test
    void readSha1_quandoServidorIndisponivel_retornaEmpty() {
        // Porta que ninguém está ouvindo
        Optional<String> result = reader.readSha1("http://localhost:1/wnfe.zsync");

        assertThat(result).isEmpty();
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
