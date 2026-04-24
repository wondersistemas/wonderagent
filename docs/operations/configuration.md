# Configuração — Referência do application.yaml

Localização padrão: `C:\ProgramData\WonderAgent\application.yaml`

---

## agent.*

| Propriedade | Obrigatório | Padrão | Descrição |
|---|---|---|---|
| `agent.client-id` | Sim | — | Identificador único desta instalação |
| `agent.central-url` | Sim | — | URL base do servidor central Wonder |
| `agent.jwt-token` | Sim | — | Token JWT desta instalação |
| `agent.poll-interval-seconds` | Não | `300` | Intervalo entre ciclos de poll |
| `agent.version` | Não | (do build) | Versão do agente (não alterar manualmente) |

---

## driver.wildfly.*

| Propriedade | Obrigatório | Padrão | Descrição |
|---|---|---|---|
| `driver.wildfly.home` | Sim | — | Diretório raiz do WildFly |
| `driver.wildfly.management-port` | Não | `9990` | Porta da management API |
| `driver.wildfly.deploy-path` | Sim | — | Caminho do diretório de deployments |
| `driver.wildfly.artifact-name` | Sim | — | Nome do arquivo WAR (ex: `wnfe.war`) |
| `driver.wildfly.start-timeout-seconds` | Não | `180` | Timeout para confirmar RUNNING após start |
| `driver.wildfly.stop-timeout-seconds` | Não | `60` | Timeout para confirmar STOPPED após stop |
| `driver.wildfly.health-check-url` | Sim | — | URL para health check HTTP |

---

## nexus.*

| Propriedade | Obrigatório | Padrão | Descrição |
|---|---|---|---|
| `nexus.username` | Sim | — | Usuário Nexus para download de artefatos |
| `nexus.password` | Sim | — | Senha Nexus |

---

## quarkus.log.*

| Propriedade | Padrão | Descrição |
|---|---|---|
| `quarkus.log.level` | `INFO` | Nível de log global |
| `quarkus.log.file.enable` | `true` | Habilita log em arquivo |
| `quarkus.log.file.path` | `C:/ProgramData/WonderAgent/logs/agent.log` | Caminho do arquivo |
| `quarkus.log.file.rotation.max-file-size` | `50M` | Tamanho máximo por arquivo |
| `quarkus.log.file.rotation.max-backup-index` | `5` | Número de backups |

---

## Exemplo completo

```yaml
agent:
  client-id: "cliente-abc-prod-01"
  central-url: "https://deploy.wonder.com.br"
  jwt-token: "eyJhbGciOiJIUzI1NiJ9..."
  poll-interval-seconds: 300

driver:
  wildfly:
    home: "C:/wildfly"
    management-port: 9990
    deploy-path: "C:/wildfly/standalone/deployments"
    artifact-name: "wnfe.war"
    start-timeout-seconds: 180
    stop-timeout-seconds: 60
    health-check-url: "http://localhost:8080/probusweb/health"

nexus:
  username: "deploy"
  password: "senha-nexus"

quarkus:
  log:
    level: INFO
```
