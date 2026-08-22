# Troubleshooting

## Diagnóstico rápido

```
> wonderagent.exe status
> wonderagent.exe detect
```

Verifique também `C:\ProgramData\WonderAgent\logs\agent.log`.

---

## Estados problemáticos

### HUNG

**Sintoma**: `detect` retorna `HUNG`

**Causas comuns**:
- Processo Java em deadlock
- WildFly iniciando (transitório — aguardar)
- Deployment travado

**Ação**:
```
> wonderagent.exe detect   # confirmar HUNG
> taskkill /F /IM java.exe /FI "WINDOWTITLE eq WildFly*"
> wonderagent.exe detect   # deve retornar STOPPED
> wonderagent.exe check    # inicia ciclo — sobe o WildFly e deploya se necessário
```

### HUNG persistente — boot do WildFly nunca termina dentro do timeout

**Sintoma**: `start()` sempre estoura `driver.wildfly.start-timeout-seconds`;
no `standalone/log/server.log` do WildFly o boot fica parado minutos (sem
nenhuma linha de log) e só retoma exatamente quando o `wonderagent.exe`
termina — visível comparando o timestamp de `wonderagent stopped` no log do
agente com a linha seguinte que aparece no `server.log` (diferença de
milissegundos).

**Causa raiz**: bug de `ProcessBuilder` no branch Windows de
`WildflyDriver.start()` — o processo `standalone.bat` era criado sem
redirecionar stdout/stderr, e nada no código drena esses streams. O WildFly
tem um CONSOLE handler de logging ativo além do FILE handler; assim que o
volume de log do boot (WAR grande, muitas classes) enche o buffer do pipe
anônimo do Windows, a escrita no console handler bloqueia — e como o logging
é síncrono entre handlers, isso trava também a escrita em `server.log`. O
bloqueio só é liberado quando o processo pai (wonderagent) termina e o
Windows fecha o handle de leitura do pipe. O branch Linux nunca teve esse
problema porque já redirecionava para `/dev/null` (`nohup ... > /dev/null
2>&1 &`).

**Ação**: corrigido em `WildflyDriver.start()` — `pb.redirectOutput/
redirectError(ProcessBuilder.Redirect.DISCARD)` no branch Windows, espelhando
o que o branch Linux já fazia. `driver.wildfly.start-timeout-seconds` também
foi ajustado para 300s (ver [configuration.md](../operations/configuration.md))
como margem para o tempo real de boot de WARs grandes, mas isso sozinho não
resolvia o travamento — a causa era o pipe, não o tempo de boot.

### PARTIAL

**Sintoma**: `detect` retorna `PARTIAL`

**Causa**: deployment corrompido ou falhou no meio.

**Ação**:
```
> wonderagent.exe detect
# O agente fará forceKill automaticamente no próximo ciclo de poll
# Para forçar imediatamente:
> wonderagent.exe check
```

### DEPLOY_FAILED

**Sintoma**: log mostra `Deploy concluído com falha` ou status no servidor central é FAILURE

**Ação**:
1. Verificar log para `failureReason`
2. Checar se o WildFly subiu: `wonderagent.exe detect`
3. Checar health check manualmente: `curl http://localhost:8080/probusweb/health`
4. Se artefato corrompido: reportar ao time Wonder para republicar a versão

---

## Agente não aparece no servidor central

**Causas comuns**:
- `agent.central-url` incorreto
- `agent.jwt-token` expirado ou inválido
- Firewall bloqueando saída HTTPS (porta 443)

**Diagnóstico**:
```
# Testar conectividade manual
curl https://deploy.wonder.com.br/api/v1/agents/<clientId>/desired-state ^
  -H "Authorization: Bearer <token>"
```

---

## Serviço Windows não inicia

```
> sc query WonderAgent
> eventvwr    # Windows Event Viewer → Application log
```

Verificar se `wonderagent.exe` está em `C:\ProgramData\WonderAgent\` e se
`application.yaml` existe e tem `client-id` e `jwt-token` preenchidos.

---

## Logs

Localização: `C:\ProgramData\WonderAgent\logs\agent.log`

Níveis úteis para diagnóstico:
```yaml
quarkus:
  log:
    level: DEBUG   # temporário para diagnóstico
```
