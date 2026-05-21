package br.com.wonder.agent.cli.command;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainIntegrationTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regressão nativa: garante que MD4 esteja acessível via reflection no binário nativo
 * quando o zsync executa delta com input file (.zs-old).
 *
 * Causa raiz: ZsyncUtil registra org.apache.mina.proxy.utils.MD4 como provider via
 * Provider.put("MessageDigest.MD4", MD4.class.getName()) — o JCA instancia a classe
 * por reflection. Sem a entrada em reflect-config.json, a segunda chamada ao check
 * (quando já existe um .war e o zsync passa .zs-old como input) falha com
 * RuntimeException("MD4 unavailable").
 *
 * Na primeira chamada não há .zs-old, processInputFile não é chamado e MD4 nunca é
 * necessário — por isso o erro só aparece na segunda execução.
 *
 * Fix: org.apache.mina.proxy.utils.MD4 em reflect-config.json.
 *
 * Requer build nativo prévio: mvn package -Pnative
 * Executar com: mvn verify -Pnative
 */
@QuarkusMainIntegrationTest
class Md4NativeIT {

    @Test
    @Launch("check")
    void check_naoFalhaComMd4Unavailable(LaunchResult result) throws IOException {
        // Stderr captura o log do processo (Quarkus escreve a stack trace lá)
        assertThat(result.getErrorOutput())
                .as("stderr não deve conter 'MD4 unavailable' — indica BouncyCastle não " +
                    "registrado antes do zsync instanciar DoubleBlockMatcher")
                .doesNotContainIgnoringCase("MD4 unavailable");

        // O Quarkus também grava quarkus.log durante os ITs — verificar por segurança
        Path logFile = Path.of("target/quarkus.log");
        if (Files.exists(logFile)) {
            assertThat(Files.readString(logFile))
                    .as("quarkus.log não deve conter 'MD4 unavailable'")
                    .doesNotContainIgnoringCase("MD4 unavailable");
        }
    }
}
