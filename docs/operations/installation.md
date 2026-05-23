# Instalação — Onboarding de Nova Instalação

## Pré-requisitos

- Windows Server 64-bit
- Acesso HTTPS de saída para o servidor central Wonder (porta 443)
- Acesso HTTP ao Reposilite interno (porta 8082) para download de artefatos
- Permissão de Administrador para instalar serviço Windows

> **WildFly**: não precisa ser instalado manualmente. Se o servidor central configurar
> `wildflyVersion` no desired-state, o agente baixa e instala o WildFly provisionado
> automaticamente no primeiro ciclo de poll.

## Passo a passo

### 1. Obter clientId e JWT do servidor central

Solicitar ao time Wonder a criação da instalação no servidor central.
Você receberá:
- `clientId`: string imutável (ex: `cliente-abc-prod-01`)
- `jwtToken`: token JWT de longa duração

### 2. Copiar o executável

```
C:\ProgramData\WonderAgent\
├── wonderagent.exe
└── application.yaml      ← criar conforme passo 3
```

### 3. Configurar application.yaml e .env

**`C:\ProgramData\WonderAgent\application.yaml`** — configuração principal:

```yaml
agent:
  client-id: "cliente-abc-prod-01"    # recebido no passo 1
  central-url: "https://deploy.wonder.com.br"
  jwt-token: "eyJ..."                 # recebido no passo 1
  poll-interval: "PT300S"             # ISO 8601: PT30S=30s, PT5M=5min, PT300S=5min

driver:
  wildfly:
    artifact-name: "wnfe.war"
    health-check-url: "http://localhost:8080/probusweb/health"
```

**`C:\ProgramData\WonderAgent\.env`** — segredos (não versionar):

```env
# Obrigatório
WONDER_REPO_PASSWORD=senha-do-reposilite

# Opcional — conexão Oracle local para leitura de id_versaodb.
# Se omitido, dbVersion é enviado como null ao servidor central.
DB_URL=jdbc:oracle:thin:@//localhost:1521/ORCL
DB_USERNAME=usuario
DB_PASSWORD=senha

# Modo de start/stop do WildFly (padrão: false)
# false → standalone.sh / jboss-cli.sh diretamente (desenvolvimento/teste)
# true  → serviço do SO: systemctl (Linux) ou sc (Windows)
WILDFLY_SERVICE_MODE=true
```

> **Credenciais do WildFly**: `WILDFLY_MGMT_USER` e `WILDFLY_MGMT_PASSWORD` são
> geradas automaticamente pelo agente no primeiro `provision`. Não é necessário
> configurá-las manualmente.

Ver [configuration.md](configuration.md) para referência completa.

### 4. Verificar conectividade

```
> wonderagent.exe detect
RUNNING
```

Se retornar `UNKNOWN`, verificar se o WildFly está rodando.

### 5. Instalar como serviço Windows

```
> wonderagent.exe install
```

Ou manualmente via NSSM:
```
nssm install WonderAgent "C:\ProgramData\WonderAgent\wonderagent.exe"
nssm set WonderAgent AppDirectory "C:\ProgramData\WonderAgent"
nssm start WonderAgent
```

### 6. Verificar serviço

```
> sc query WonderAgent
STATE: 4 RUNNING
```

### 7. Confirmar no servidor central

O agente deve aparecer no dashboard central com status e versão instalada
dentro de um ciclo de poll (padrão: 5 minutos).
