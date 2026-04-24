# Instalação — Onboarding de Nova Instalação

## Pré-requisitos

- Windows Server 64-bit
- WildFly 36 instalado e configurado
- Acesso HTTPS de saída para o servidor central Wonder
- Permissão de Administrador para instalar serviço Windows

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

### 3. Configurar application.yaml

```yaml
agent:
  client-id: "cliente-abc-prod-01"    # recebido no passo 1
  central-url: "https://deploy.wonder.com.br"
  jwt-token: "eyJ..."                 # recebido no passo 1
  poll-interval-seconds: 300

driver:
  wildfly:
    home: "C:/wildfly"
    deploy-path: "C:/wildfly/standalone/deployments"
    artifact-name: "wnfe.war"
    health-check-url: "http://localhost:8080/probusweb/health"
```

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
