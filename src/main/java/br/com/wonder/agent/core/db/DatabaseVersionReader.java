package br.com.wonder.agent.core.db;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;

/**
 * Lê id_versaodb da tabela gerenciador do Oracle local.
 * Usa DriverManager diretamente — sem Agroal/Narayana, sem pool (leitura pontual por ciclo de poll).
 * Se DB_URL estiver vazio, retorna empty silenciosamente.
 */
@Slf4j
@ApplicationScoped
public class DatabaseVersionReader {

    @ConfigProperty(name = "db.url")
    Optional<String> jdbcUrl;

    @ConfigProperty(name = "db.username")
    Optional<String> username;

    @ConfigProperty(name = "db.password")
    Optional<String> password;

    private static final String QUERY = "SELECT id_versaodb FROM gerenciador WHERE ROWNUM = 1";

    public Optional<String> readDbVersion() {
        if (jdbcUrl.filter(s -> !s.isBlank()).isEmpty()) {
            log.debug("db.url não configurado — leitura de versão de banco ignorada");
            return Optional.empty();
        }

        try (Connection conn = DriverManager.getConnection(
                jdbcUrl.get(),
                username.orElse(""),
                password.orElse(""));
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(QUERY)) {

            if (rs.next()) {
                String version = rs.getString(1);
                log.debug("id_versaodb lido do banco: {}", version);
                return Optional.ofNullable(version);
            }
            log.warn("gerenciador.id_versaodb não encontrou linhas");
            return Optional.empty();

        } catch (Exception e) {
            log.error("Falha ao ler id_versaodb do banco", e);
            return Optional.empty();
        }
    }
}
