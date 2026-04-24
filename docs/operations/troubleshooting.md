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
