# Arquitetura — Visão Geral

## Problema

Clientes da Wonder rodam WildFly + Oracle em servidores Windows on-premises. O acesso
é indireto (RDP, às vezes VPN) — ferramentas push como Ansible não são viáveis. A atualização
manual via RDP não escala com o crescimento da frota.

## Solução

Agente pull instalado em cada servidor. Periodicamente busca o estado desejado de um
servidor central via HTTPS (saída — sem necessidade de porta de entrada aberta) e aplica
atualizações de forma autônoma.

## Restrições fundamentais

- **Sem container**: certificados A3 (PKCS#11) usam keystore nativa do Windows
- **GraalVM Native Image**: `.exe` único, sem JVM instalada no servidor do cliente
- **Windows 64**: plataforma alvo única

## Componentes

```
┌─────────────────────────────────────────────────────────┐
│  Servidor Central (Wonder)                              │
│  ┌──────────────┐  ┌───────────────────────────────┐   │
│  │  REST API    │  │  Nexus (artefatos Maven)       │   │
│  │  /desired-   │  │  wnfe-war-2.x.war              │   │
│  │   state      │  └───────────────────────────────┘   │
│  └──────┬───────┘                                       │
└─────────┼───────────────────────────────────────────────┘
          │ HTTPS (saída do cliente)
┌─────────▼───────────────────────────────────────────────┐
│  Servidor do Cliente (Windows)                          │
│                                                         │
│  wonderagent.exe (serviço Windows)                      │
│  ┌──────────────────────────────────────────────┐       │
│  │  AgentOrchestrator (poll loop)               │       │
│  │  ├── CentralClient (fetchDesiredState)        │       │
│  │  ├── StateMachine (detectAndRecover)          │       │
│  │  ├── DeployPipeline (stop→swap→start→health)  │       │
│  │  └── RuntimeDriver (WildflyDriver)            │       │
│  └──────────────────────────────────────────────┘       │
│                                                         │
│  WildFly 36 + wnfe.war + Oracle                         │
└─────────────────────────────────────────────────────────┘
```

## Fluxo de um ciclo de poll

```
1. AgentOrchestrator.poll() dispara (a cada N segundos)
2. CentralClient.fetchDesiredState(clientId) → versão desejada
3. RuntimeDriver.getInstalledVersion() → versão instalada
4. Se versões iguais: reportStatus() e encerra ciclo
5. Se versão diferente:
   a. StateMachine.detectAndRecover() → estado confiável
   b. Download do artefato do Nexus
   c. DeployPipeline.execute(artifact)
      - stop() → deploy() → start() → healthCheck()
   d. reportStatus() + reportDeployResult()
```

## Extensibilidade

O agente suporta múltiplos runtimes via interface `RuntimeDriver`. Para adicionar um novo
runtime (ex: Quarkus standalone), basta implementar `RuntimeDriver` e registrá-lo como
bean CDI qualificado. Ver [driver-interface.md](driver-interface.md) e
[../development/adding-a-driver.md](../development/adding-a-driver.md).
