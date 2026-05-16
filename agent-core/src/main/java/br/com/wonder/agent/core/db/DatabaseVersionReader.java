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
 * Valor é enviado ao servidor central no AgentStatusReport para que o servidor
 * possa compor a versão correta do WAR no formato 1.<versaodb>.<patch>.
 */
@Slf4j
@ApplicationScoped
public class DatabaseVersionReader {

    @ConfigProperty(name = "db.url", defaultValue = "")
    String url;

    @ConfigProperty(name = "db.username", defaultValue = "")
    String username;

    @ConfigProperty(name = "db.password", defaultValue = "")
    String password;

    private static final String QUERY = "SELECT id_versaodb FROM gerenciador WHERE ROWNUM = 1";

    /**
     * Retorna o id_versaodb ou empty se a conexão não estiver configurada ou falhar.
     * Nunca lança exceção — falha silenciosa com log de aviso.
     */
    public Optional<String> readDbVersion() {
        if (url.isBlank()) {
            log.debug("db.url não configurado — leitura de versão de banco ignorada");
            return Optional.empty();
        }

        try (Connection conn = DriverManager.getConnection(url, username, password);
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
            log.warn("Falha ao ler id_versaodb do banco: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
