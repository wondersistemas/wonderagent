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
│  ┌──────────────┐  ┌───────────────────────────────┐    │
│  │  REST API    │  │  Reposilite                   │    │
│  │  /desired-   │  │  wnfe-war-2.x.war             │    │
│  │   state      │  │  wildfly-provisioning-x-dist  │    │
│  └──────┬───────┘  └───────────────────────────────┘    │
└─────────┼───────────────────────────────────────────────┘
          │ HTTPS (saída do cliente)
┌─────────▼───────────────────────────────────────────────┐
│  Servidor do Cliente (Windows)                          │
│                                                         │
│  wonderagent.exe (serviço Windows)                      │
│  ┌──────────────────────────────────────────────┐       │
│  │  AgentOrchestrator (poll loop)               │       │
│  │  ├── CentralClient (fetchDesiredState)       │       │
│  │  ├── DatabaseVersionReader (id_versaodb)     │       │
│  │  ├── WildflyProvisioner (ensureVersion)      │       │
│  │  ├── StateMachine (detectAndRecover)         │       │
│  │  ├── DeployPipeline (stop→swap→start→health) │       │
│  │  └── RuntimeDriver (WildflyDriver)           │       │
│  └──────────────────────────────────────────────┘       │
│                                                         │
│  WildFly 36 + wnfe.war + Oracle                         │
└─────────────────────────────────────────────────────────┘
```

## Fluxo de um ciclo de poll

```
1. AgentOrchestrator.poll() dispara (a cada N segundos)
2. CentralClient.fetchDesiredState(clientId) → versão desejada
3. Se desired.wildflyVersion != null:
   WildflyProvisioner.ensureVersion() → baixa e extrai WildFly se versão mudou
4. RuntimeDriver.getInstalledVersion() → versão do WAR instalado
5. Se versões iguais: reportStatus() e encerra ciclo
6. Se versão diferente:
   a. StateMachine.detectAndRecover() → estado confiável
   b. ArtifactDownloader.download() → WAR via zsync do Reposilite
   c. DeployPipeline.execute(artifact)
      - stop() → deploy() → start() → healthCheck()
   d. reportStatus() + reportDeployResult()

Em todo ciclo, reportStatus() inclui dbVersion:
   DatabaseVersionReader.readDbVersion() → lê gerenciador.id_versaodb via JDBC
   → enviado no AgentStatusReport para que o servidor central componha a versão
     do WAR no formato 1.<dbVersion>.<patch>
```

## Extensibilidade

O agente suporta múltiplos runtimes via interface `RuntimeDriver`. Para adicionar um novo
runtime (ex: Quarkus standalone), basta implementar `RuntimeDriver` e registrá-lo como
bean CDI qualificado. Ver [driver-interface.md](driver-interface.md) e
[../development/adding-a-driver.md](../development/adding-a-driver.md).
