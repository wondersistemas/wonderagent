# Configuração — Referência do application.yaml

Localização padrão: `C:\ProgramData\WonderAgent\application.yaml`

---

## Estrutura de diretórios

Tudo deriva de `agent.home`. A estrutura padrão após instalação:

```
C:\ProgramData\WonderAgent\          ← agent.home
  wonderagent.exe
  application.yaml
  .env                               ← variáveis de ambiente do agente (senhas, versão fixada)
  logs\
    agent.log                        ← quarkus.log.file.path
  downloads\                         ← download.temp-dir
    wnfe-war-2.x.war                 ← cache para delta zsync
    wildfly-provisioning-x-dist.zip  ← cache para delta zsync do WildFly
  wildfly\                           ← driver.wildfly.home
    .wildfly-version                 ← versão lógica do WildFly instalado
    .wildfly-sha1                    ← SHA-1 do ZIP instalado (detecção de nova build)
    .env                             ← variáveis do WildFly: DB_URL, DB_USER, DB_PASSWORD, WILDFLY_MGMT_USER, WILDFLY_MGMT_PASSWORD
    bin\
    standalone\
      deployments\                   ← driver.wildfly.deploy-path
        .wonder-version              ← versão do WAR instalado
```

Para instalar o WildFly em outro lugar, basta sobrescrever `driver.wildfly.home`
no `application.yaml` — os demais caminhos que derivam dele se ajustam automaticamente.

---

## .env — segredos e substituições de ambiente

Variáveis lidas pelo agente na inicialização. Localização:
`C:\ProgramData\WonderAgent\.env`

```env
# Senha do usuário read-only do Reposilite (obrigatório)
WONDER_REPO_PASSWORD=senha-aqui

# Conexão Oracle para leitura de gerenciador.id_versaodb (opcional).
# Se ausente, dbVersion é enviado como null ao servidor central.
# DB_URL=jdbc:oracle:thin:@//localhost:1521/ORCL
# DB_USERNAME=usuario
# DB_PASSWORD=senha

# Fixa uma versão específica do WildFly provisionado (opcional).
# Se ausente, usa a versão enviada pelo servidor central.
# WILDFLY_PROVISIONING_VERSION=1.0.0-SNAPSHOT
```

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
| `download.temp-dir` | `{agent.home}/downloads` | Diretório para arquivos baixados (WAR e ZIP do WildFly) |
| `download.max-retries` | `3` | Tentativas em caso de falha |
| `download.connect-timeout-seconds` | `30` | Timeout de conexão |
| `download.read-timeout-seconds` | `120` | Timeout de leitura |
| `download.repository.url` | `http://192.168.0.86:8082` | URL base do Reposilite |
| `download.repository.username` | `reader` | Usuário read-only |
| `download.repository.password` | `${WONDER_REPO_PASSWORD}` | Senha (lida do `.env`) |

---

## wildfly.provisioning.*

Controla o download e instalação automática do WildFly provisionado.
Só é ativado quando o servidor central envia `wildflyVersion != null` no desired-state.

| Propriedade | Padrão | Descrição |
|---|---|---|
| `wildfly.provisioning.repo-path` | `wnfe-releases` | Repositório no Reposilite |
| `wildfly.provisioning.fixed-version` | `` (vazio) | Fixa uma versão específica. Se vazio, usa a versão do servidor central. Configurável via `WILDFLY_PROVISIONING_VERSION` no `.env` |

O ZIP é publicado no Reposilite como:
```
{repo-path}/br/com/wonder/wildfly-provisioning/{version}/wildfly-provisioning-{version}-dist.zip
```

Após extração, o WildFly é instalado diretamente em `driver.wildfly.home`.
A versão instalada é rastreada em `driver.wildfly.home/.wildfly-version`.

A detecção de nova build usa o SHA-1 do arquivo `.zsync` remoto do ZIP, comparado com
`.wildfly-sha1` local — o mesmo mecanismo usado para o WAR. Assim, uma nova build do mesmo
SNAPSHOT é detectada e instalada mesmo que a versão lógica não tenha mudado.
Se o SHA-1 remoto estiver indisponível, o agente confia na versão lógica em `.wildfly-version`.

---

## db.*

Conexão Oracle local para leitura de `gerenciador.id_versaodb`.
O valor é enviado ao servidor central no `AgentStatusReport` para que ele possa
compor a versão correta do WAR no formato `1.<dbVersion>.<patch>`.

Se `db.url` estiver vazio, a leitura é silenciosamente ignorada e `dbVersion` é `null` no report.

| Propriedade | Obrigatório | Padrão | Descrição |
|---|---|---|---|
| `db.url` | Não | `` (vazio) | JDBC URL do Oracle local. Ex: `jdbc:oracle:thin:@//localhost:1521/ORCL`. Lido de `${DB_URL}` no `.env` |
| `db.username` | Não | `` (vazio) | Usuário Oracle. Lido de `${DB_USERNAME}` no `.env` |
| `db.password` | Não | `` (vazio) | Senha Oracle. Lido de `${DB_PASSWORD}` no `.env` |

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
