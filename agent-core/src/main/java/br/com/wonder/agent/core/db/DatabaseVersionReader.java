package br.com.wonder.agent.core.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;

/**
 * Lê id_versaodb da tabela gerenciador do Oracle local.
 * Valor é enviado ao servidor central no AgentStatusReport para que o servidor
 * possa compor a versão correta do WAR no formato 1.<versaodb>.<patch>.
 *
 * Usa DataSource Quarkus/Agroal (quarkus-jdbc-oracle) para suporte completo
 * ao Native Image — sem reflection manual no reflect-config.json.
 * Se quarkus.datasource.jdbc.url estiver vazio, o DataSource fica inativo
 * e a leitura é ignorada silenciosamente.
 */
@Slf4j
@ApplicationScoped
public class DatabaseVersionReader {

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    Optional<String> jdbcUrl;

    @Inject
    DataSource dataSource;

    private static final String QUERY = "SELECT id_versaodb FROM gerenciador WHERE ROWNUM = 1";

    /**
     * Retorna o id_versaodb ou empty se o DataSource não estiver configurado ou falhar.
     * Nunca lança exceção — falha silenciosa com log de aviso.
     */
    public Optional<String> readDbVersion() {
        if (jdbcUrl.filter(s -> !s.isBlank()).isEmpty()) {
            log.debug("quarkus.datasource.jdbc.url não configurado — leitura de versão de banco ignorada");
            return Optional.empty();
        }

        try (Connection conn = dataSource.getConnection();
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
