package br.com.wonder.agent.cli.command;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes de integração executados contra o binário nativo (ou runner JAR).
 * Requer build prévio: mvn package -Pnative
 * Executar com: mvn verify -Pnative
 */
@QuarkusMainIntegrationTest
class WonderAgentCommandIT {

    @Test
    @Launch("--help")
    void help_exitaComZeroEImprimeProgramName(LaunchResult result) {
        assertThat(result.exitCode()).isZero();
        assertThat(result.getOutput()).contains("wonderagent");
    }

    @Test
    @Launch({"check", "--help"})
    void checkHelp_exitaComZero(LaunchResult result) {
        assertThat(result.exitCode()).isZero();
        assertThat(result.getOutput()).contains("check");
    }

    @Test
    @Launch({"status", "--help"})
    void statusHelp_exitaComZero(LaunchResult result) {
        assertThat(result.exitCode()).isZero();
        assertThat(result.getOutput()).contains("status");
    }

    @Test
    @Launch({"detect", "--help"})
    void detectHelp_exitaComZero(LaunchResult result) {
        assertThat(result.exitCode()).isZero();
        assertThat(result.getOutput()).contains("detect");
    }

    @Test
    @Launch({"install", "--help"})
    void installHelp_exitaComZero(LaunchResult result) {
        assertThat(result.exitCode()).isZero();
        assertThat(result.getOutput()).contains("install");
    }

    @Test
    @Launch({"uninstall", "--help"})
    void uninstallHelp_exitaComZero(LaunchResult result) {
        assertThat(result.exitCode()).isZero();
        assertThat(result.getOutput()).contains("uninstall");
    }

    @Test
    @Launch({"config", "show"})
    void configShow_imprimeCabecalhoDeConfiguracao(LaunchResult result) {
        assertThat(result.exitCode()).isZero();
        assertThat(result.getOutput()).contains("=== Configuração ativa ===");
    }

    @Test
    @Launch({"config", "--help"})
    void configHelp_exitaComZero(LaunchResult result) {
        assertThat(result.exitCode()).isZero();
        assertThat(result.getOutput()).contains("config");
    }
}
