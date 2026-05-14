# CLAUDE.md — WonderAgent

Guia para Claude Code ao trabalhar neste repositório.

## O que é

Agente pull de deploy on-premises para servidores Windows de clientes da Wonder.
Roda como serviço Windows (GraalVM Native Image — sem JVM), busca periodicamente
o estado desejado de um servidor central via HTTPS e aplica atualizações de forma autônoma.

Contexto mais amplo: a Wonder mantém múltiplas instalações on-premises (WildFly + Oracle)
em servidores Windows de clientes. O acesso é indireto (RDP, às vezes com VPN por cliente)
— ferramentas push como Ansible não são viáveis. O agente resolve isso com modelo pull.

## Decisões já tomadas (não reabrir sem motivo)

- **Pull model**: o agente inicia toda comunicação. Servidor central nunca conecta no agente.
- **GraalVM Native Image**: `.exe` único, sem JVM no servidor do cliente.
- **Quarkus**: framework base — Native Image first-class, CDI familiar, serve como lab de migração futura do app principal (wnfe).
- **Windows 64**: única plataforma alvo.
- **NSSM**: gerencia o `.exe` como serviço Windows.
- **Sem container**: certificados A3 (PKCS#11) usam keystore nativa do Windows — container não é viável.
- **Java 21**: versão do compilador para este projeto.

## Restrições importantes

- Certificados A3 via PKCS#11 com keystore nativa do Windows impedem containerização.
- O agente não se auto-atualiza — atualização do `wonderagent.exe` é manual ou via script PowerShell externo.
- Toda comunicação com o servidor central é HTTPS de saída (porta 443) — sem porta de entrada aberta no servidor do cliente.

## Módulos

```
agent-model           ← DTOs, enums, interfaces — zero dependências externas
agent-core            ← StateMachine, DeployPipeline, AgentOrchestrator (poll loop)
agent-drivers         ← WildflyDriver (+ futuros QuarkusDriver, ProxyDriver)
agent-central-client  ← CentralClient REST (MicroProfile Rest Client)
agent-cli             ← Entry point Picocli + produz o .exe via Native Image
```

Regra de dependência: todos os módulos enxergam `agent-model`. `agent-model` não enxerga ninguém.

## Estado atual da implementação

### Implementado
- Interfaces e DTOs completos em `agent-model`
- `StateMachine` com `detectAndRecover()`
- `DeployPipeline` com sequência completa (stop → deploy → start → healthCheck)
- `AgentOrchestrator` com `@Scheduled` poll loop
- `WildflyDriver` com detecção via management API (porta 9990), deploy por cópia de arquivo, start/stop via CLI e `forceKill` via `taskkill`
- `CentralClient` MicroProfile Rest Client
- `WonderAgentCommand` com todos os subcomandos Picocli
- `application.yaml` com todas as propriedades documentadas
- Profile `native` no `agent-cli/pom.xml` para build do `.exe`
- `ArtifactDownloader` com download via zsync (delta transfer) de S3 público
- `DriverProducer` com seleção de driver via CDI `Instance<RuntimeDriver>` + `@Named`
- Comandos `install` e `uninstall` do NSSM implementados
- Comando `config show` implementado via `ConfigProvider`

### Pendente / TODOs principais
- `QuarkusDriver`: placeholder documentado em `docs/drivers/quarkus-driver.md`
- `ProxyDriver`: placeholder documentado em `docs/drivers/proxy-driver.md`
- Configuração de `reflect-config.json` para Native Image

## Convenções do projeto

- `detectState()` nunca lança exceção e nunca tem efeitos colaterais
- Nenhuma ação de deploy ocorre sem `StateMachine.detectAndRecover()` prévio
- Versão instalada é persistida em `.wonder-version` no diretório de deploy
- Toda comunicação com o servidor central usa `Authorization: Bearer <jwt-token>`
- Logs em `C:\ProgramData\WonderAgent\logs\agent.log`
- Configuração em `C:\ProgramData\WonderAgent\application.yaml`

## Documentação

Leia `docs/INDEX.md` primeiro — contém mapa de todos os documentos com descrição
de quando consultar cada um. Documentação estruturada em:
- `docs/architecture/` — visão geral, state machine, driver interface, ADRs
- `docs/api/` — contrato da API central, referência CLI
- `docs/drivers/` — um arquivo por driver
- `docs/operations/` — instalação, configuração, troubleshooting
- `docs/development/` — build, testes, como adicionar driver

## Contexto do projeto principal (wnfe)

O `wonderagent` faz deploy do app `wnfe`, que é:
- Java multi-módulos (WildFly 36 + Oracle), WAR deploy em `/probusweb`
- Branch `richfaces_less` removendo RichFaces em favor de JSF nativo + OmniFaces
- Repo em `~/JavaApp/wnfe_wildfly`

O wonderagent não depende do wnfe — são projetos separados.

## Build

```cmd
# JVM (desenvolvimento)
mvn clean package -Dmaven.test.skip=true -pl agent-cli -am

# Native Image (requer x64 Native Tools Command Prompt + GraalVM)
cd agent-cli
mvn clean package -Pnative -Dmaven.test.skip=true
```

Ver `docs/development/building.md` para pré-requisitos completos.
