# API do Servidor Central

Endpoints consumidos pelo agente. Todos requerem `Authorization: Bearer <jwt-token>`.
Base URL configurada em `agent.central-url`.

---

## GET /api/v1/agents/{clientId}/desired-state

Retorna o estado desejado para esta instalação.

**Resposta 200:**
```json
{
  "groupId": "br.com.wonder",
  "artifactId": "wnfe-war",
  "version": "2.45.1",
  "extension": "war",
  "nexusBaseUrl": "https://nexus.wonder.com.br",
  "nexusRepository": "releases",
  "runtimeType": "wildfly",
  "deployConfig": {
    "deployPath": "C:/wildfly/standalone/deployments",
    "healthCheckUrl": "http://localhost:8080/probusweb/health",
    "healthCheckTimeoutSeconds": 120,
    "startTimeoutSeconds": 180,
    "stopTimeoutSeconds": 60,
    "rollbackOnFailure": false
  }
}
```

**Erros:**
- `404`: clientId não cadastrado
- `401`: token inválido ou expirado

---

## POST /api/v1/agents/{clientId}/status

Relatório de estado enviado pelo agente a cada ciclo de poll.

**Body:**
```json
{
  "reportedAt": "2026-04-24T10:30:00Z",
  "runtimeState": "UP_TO_DATE",
  "installedVersion": "2.45.1",
  "healthy": true,
  "lastDeployAt": "2026-04-23T14:22:00Z",
  "lastDeployResult": "SUCCESS",
  "agentVersion": "1.0.0"
}
```

**Resposta:** `204 No Content`

---

## POST /api/v1/agents/{clientId}/deploy-results

Detalhe de uma tentativa de deploy. Enviado apenas quando o resultado muda
(sucesso ou falha) — não a cada ciclo.

**Body:**
```json
{
  "deployedAt": "2026-04-24T10:31:00Z",
  "version": "2.45.1",
  "success": true,
  "durationSeconds": 47,
  "stateBefore": "RUNNING",
  "stateAfter": "RUNNING",
  "failureReason": null
}
```

**Resposta:** `204 No Content`

---

## Download de artefato

O agente baixa diretamente do Nexus — não passa pelo servidor central.

```
GET https://{nexusBaseUrl}/repository/{nexusRepository}/{groupId}/{artifactId}/{version}/{artifactId}-{version}.{extension}
```

Autenticação: credenciais Nexus configuradas separadamente em `nexus.username` / `nexus.password`.

---

## clientId

Identificador único e imutável de cada instalação. Um cliente com duas instalações
de produção tem dois `clientId` distintos. Gerado no onboarding e gravado em
`application.yaml` como `agent.client-id`.

## JWT

Token de longa duração por instalação. Gerado pelo servidor central no onboarding.
Configurado em `agent.jwt-token`. Revogação granular: comprometer um token não afeta outros.
