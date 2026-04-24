# WildflyDriver

Driver para WildFly 36. Usa a management API HTTP (porta 9990) para todas as
operações de estado e deploy.

## Detecção de estado

Sequência sem efeitos colaterais:

```
1. tasklist | grep java.exe          → NÃO: STOPPED
2. socket.connect(localhost:9990)    → FALHOU: HUNG
3. GET /management?...server-state   → != "running": HUNG
4. GET /management/deployment/...    → status == "FAILED": PARTIAL
5.                                   → RUNNING
```

## Deploy

1. `Files.copy(artifact.localFile, deployPath/artifactName)`
2. `Files.writeString(deployPath/.wonder-version, version)`

O WildFly detecta o novo arquivo via deployment scanner automaticamente.
Não usa o `jboss-cli` para deploy — cópia direta é suficiente e mais simples.

## Start / Stop

- **start**: `standalone.bat` → aguarda `detectState() == RUNNING` com polling de 2s
- **stop**: `jboss-cli.bat --connect --command=:shutdown` → aguarda `STOPPED`
- **forceKill**: `taskkill /F /IM java.exe /FI "WINDOWTITLE eq WildFly*"`

## Health check

`GET {driver.wildfly.health-check-url}` com timeout de 5s.
Padrão: `http://localhost:8080/probusweb/health`

## Versão instalada

Lida de `{deployPath}/.wonder-version` (arquivo de texto plano).
Escrito pelo `deploy()`. Retorna `null` se o arquivo não existir.

## Configuração

Todas as propriedades em `driver.wildfly.*` — ver
[../operations/configuration.md](../operations/configuration.md).

## Limitações conhecidas

- `forceKill` mata todos os processos `java.exe` com título de janela WildFly.
  Em servidores com múltiplas instâncias WildFly, pode matar a instância errada.
  Mitigação futura: identificar PID via porta de management.
- Não suporta WildFly domain mode — apenas standalone.
