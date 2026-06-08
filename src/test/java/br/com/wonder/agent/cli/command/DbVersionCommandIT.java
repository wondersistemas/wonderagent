package br.com.wonder.agent.cli.command;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test do Native Image para leitura de versão de banco.
 * Valida que o charset Oracle (id 178 / WE8ISO8859P1) foi incluído no binário
 * e que a query em gerenciador.id_versaodb retorna um valor não-nulo.
 *
 * Requer build nativo prévio e banco acessível via DB_URL/DB_USERNAME/DB_PASSWORD.
 * Executar com: mvn verify -Pnative
 */
@QuarkusMainIntegrationTest
class DbVersionCommandIT {

    @Test
    @Launch("db-version")
    void dbVersion_retornaVersaoNaoVazia(LaunchResult result) {
        assertThat(result.exitCode())
                .as("db-version deve sair com código 0")
                .isZero();
        assertThat(result.getOutput().strip())
                .as("id_versaodb não pode ser nulo nem vazio")
                .isNotBlank();
    }
}
