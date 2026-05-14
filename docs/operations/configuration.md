# Configuração — Referência do application.yaml

Localização padrão: `C:\ProgramData\WonderAgent\application.yaml`

---

## Estrutura de diretórios

Tudo deriva de `agent.home`. A estrutura padrão após instalação:

```
C:\ProgramData\WonderAgent\          ← agent.home
  wonderagent.exe
  application.yaml
  logs\
    agent.log                        ← quarkus.log.file.path
  downloads\                         ← download.temp-dir
  wildfly\                           ← driver.wildfly.home
    bin\
    standalone\
      deployments\                   ← driver.wildfly.deploy-path
```

Para instalar o WildFly em outro lugar, basta sobrescrever `driver.wildfly.home`
no `application.yaml` — os demais caminhos que derivam dele se ajustam automaticamente.

---

## agent.*

| Propriedade | Obrigatório | Padrão | Descrição |
|---|---|---|---|
| `agent.home` | Não | `C:/ProgramData/WonderAgent` | Raiz de logs, downloads e WildFly |
| `agent.client-id` | Sim | — | Identificador único desta instalação |
| `agent.central-url` | Sim | — | URL base do servidor central Wonder |
| `agent.jwt-token` | Sim | — | Token JWT desta instalação |
| `agent.poll-interval` | Não | `PT300S` | Intervalo entre ciclos (ISO 8601: PT30S, PT5M) |
| `agent.version` | Não | (do build) | Versão do agente (não alterar manualmente) |

---

## driver.wildfly.*

| Propriedade | Obrigatório | Padrão | Descrição |
|---|---|---|---|
| `driver.wildfly.home` | Não | `{agent.home}/wildfly` | Diretório raiz do WildFly |
| `driver.wildfly.management-port` | Não | `9990` | Porta da management API |
| `driver.wildfly.deploy-path` | Não | `{driver.wildfly.home}/standalone/deployments` | Diretório de deployments |
| `driver.wildfly.artifact-name` | Sim | — | Nome do arquivo WAR (ex: `wnfe.war`) |
| `driver.wildfly.start-timeout-seconds` | Não | `180` | Timeout para confirmar RUNNING após start |
| `driver.wildfly.stop-timeout-seconds` | Não | `60` | Timeout para confirmar STOPPED após stop |
| `driver.wildfly.health-check-url` | Sim | — | URL para health check HTTP |

---

## quarkus.log.*

| Propriedade | Padrão | Descrição |
|---|---|---|
| `quarkus.log.level` | `INFO` | Nível de log global |
| `quarkus.log.file.enable` | `true` | Habilita log em arquivo |
| `quarkus.log.file.path` | `{agent.home}/logs/agent.log` | Caminho do arquivo de log |
| `quarkus.log.file.rotation.max-file-size` | `50M` | Tamanho máximo por arquivo |
| `quarkus.log.file.rotation.max-backup-index` | `5` | Número de backups |

---

## download.*

| Propriedade | Padrão | Descrição |
|---|---|---|
| `download.temp-dir` | `{agent.home}/downloads` | Diretório para WARs baixados |
| `download.max-retries` | `3` | Tentativas em caso de falha |

---

## Exemplo mínimo (instalação padrão)

WildFly em `C:\ProgramData\WonderAgent\wildfly` — só o essencial:

```yaml
agent:
  client-id: "cliente-abc-prod-01"
  central-url: "https://deploy.wonder.com.br"
  jwt-token: "eyJhbGciOiJIUzI1NiJ9..."

driver:
  wildfly:
    artifact-name: "wnfe.war"
    health-check-url: "http://localhost:8080/probusweb/health"
```

## Exemplo com WildFly em caminho customizado

```yaml
agent:
  client-id: "cliente-abc-prod-01"
  central-url: "https://deploy.wonder.com.br"
  jwt-token: "eyJhbGciOiJIUzI1NiJ9..."

driver:
  wildfly:
    home: "D:/servidores/wildfly-36"
    artifact-name: "wnfe.war"
    health-check-url: "http://localhost:8080/probusweb/health"
```
