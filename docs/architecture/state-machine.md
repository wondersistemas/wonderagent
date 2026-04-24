# State Machine — Estados e Transições

## Regra fundamental

**Nenhuma ação de deploy começa sem passar pelo StateDetector.**
Se o estado for UNKNOWN, HUNG ou PARTIAL, recuperação vem antes do deploy.

## Estados

| Estado | Condição |
|---|---|
| `UNKNOWN` | Não verificado ainda (inicial ou pós-restart do agente) |
| `STOPPED` | Processo não existe, porta fechada |
| `HUNG` | Processo existe mas não responde (porta fechada ou API muda) |
| `PARTIAL` | Deployment em estado FAILED ou corrompido detectado |
| `RUNNING` | Processo respondendo, deployment OK, qualquer versão |
| `UP_TO_DATE` | RUNNING + versão instalada = versão desejada |
| `DEPLOYING` | Deploy em andamento (transitório) |
| `DEPLOY_FAILED` | Última tentativa falhou |

## Diagrama de transições

```
          UNKNOWN
             │
             │ detectAndRecover()
      ┌──────┼──────────┐
      ▼      ▼          ▼
  STOPPED  RUNNING    HUNG/PARTIAL
      │      │             │
      │      │ versão ok?  │ forceKill()
      │      ▼             ▼
      │  UP_TO_DATE     STOPPED
      │
      │ deploy needed
      ▼
  DEPLOYING ──────────── timeout/erro ──► DEPLOY_FAILED
      │
      │ success
      ▼
   RUNNING
```

## Sequência de detecção (WildFly)

```
isProcessAlive()?          NÃO → STOPPED
        │ SIM
isManagementPortOpen()?    NÃO → HUNG
        │ SIM
queryManagementApi(        NÃO → HUNG
  server-state) == running?
        │ SIM
hasFailedDeployment()?     SIM → PARTIAL
        │ NÃO
      RUNNING
```

## Recuperação de estados problemáticos

| Estado | Ação de recuperação | Resultado esperado |
|---|---|---|
| `HUNG` | `forceKill()` | `STOPPED` |
| `PARTIAL` | `forceKill()` | `STOPPED` |
| `UNKNOWN` | `detectState()` → se ainda UNKNOWN, `forceKill()` | `STOPPED` ou `RUNNING` |

## Deploy pipeline (após estado confiável)

```
Estado inicial: STOPPED ou RUNNING/UP_TO_DATE/DEPLOY_FAILED

1. Se RUNNING: stop() → aguarda STOPPED (timeout: stopTimeoutSeconds)
   - Se stop() falhar: forceKill()

2. deploy(artifact): copia arquivo, escreve .wonder-version

3. start() → aguarda RUNNING (timeout: startTimeoutSeconds)
   - Se não atingir RUNNING: → DEPLOY_FAILED

4. healthCheck() → verifica endpoint HTTP
   - Se unhealthy: → DEPLOY_FAILED

5. reportDeployResult() ao servidor central
```

## Timeouts configuráveis

Ver `driver.wildfly.*` em [../operations/configuration.md](../operations/configuration.md):
- `start-timeout-seconds` (padrão: 180)
- `stop-timeout-seconds` (padrão: 60)
- `health-check-url` com timeout de conexão de 5s
