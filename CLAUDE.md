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

## Estrutura do projeto

Projeto Maven único (sem sub-módulos). Pacotes Java sob `src/main/java/br/com/wonder/agent/`:

```
model/        ← DTOs, enums, interfaces, FileChecksum
model/driver/ ← interface RuntimeDriver
central/      ← CentralClient REST (MicroProfile Rest Client)
core/         ← StateMachine, DeployPipeline, AgentOrchestrator (poll loop)
driver/       ← WildflyDriver, DriverProducer (+ futuros QuarkusDriver, ProxyDriver)
cli/command/  ← Entry point Picocli (WonderAgentCommand, Main)
```

## Estado atual da implementação

### Implementado
- Interfaces e DTOs completos em `model/`
- `StateMachine` com `detectAndRecover()`
- `DeployPipeline` com sequência completa (stop → deploy → start → healthCheck)
- `AgentOrchestrator` com `@Scheduled` poll loop
- `WildflyDriver` com detecção via management API (porta 9990), deploy por cópia de arquivo, start/stop via CLI e `forceKill` via `taskkill`
- `CentralClient` MicroProfile Rest Client
- `WonderAgentCommand` com todos os subcomandos Picocli
- `application.yaml` com todas as propriedades documentadas
- Profile `native` no `pom.xml` para build do `.exe`
- `ArtifactDownloader` com download via zsync (delta transfer) de S3 público
- `DatabaseVersionReader` com leitura de `gerenciador.id_versaodb` via `DataSource` Quarkus/Agroal (extensão `quarkus-jdbc-oracle`); retorna `Optional.empty()` sem exceção se banco não configurado
- `AgentStatusReport` inclui campo `dbVersion` — enviado ao servidor central para que ele componha a versão do WAR no formato `1.<dbVersion>.<patch>` (branch `wildfly`)
- Conexão Oracle configurável via `.env`: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` — mapeados para `quarkus.datasource.*`; DataSource inativo quando `DB_URL` está vazio
- `DriverProducer` com seleção de driver via CDI `Instance<RuntimeDriver>` + `@Named`
- Comandos `install` e `uninstall` do NSSM implementados
- Comando `config show` implementado via `ConfigProvider`
- `reflect-config.json`, `resource-config.json`, `proxy-config.json` e `native-image.properties` em `src/main/resources/META-INF/native-image/br.com.wonder/wonderagent/`
- Índice Jandex gerado via `jandex-maven-plugin` 3.1.7
- `@TopCommand` + `@ApplicationScoped` + `@Unremovable` em todos os subcomandos Picocli (sem `@Unremovable` o Quarkus remove os beans em build-time e a injeção falha com NPE)
- `@RestClient` no injection point `AgentOrchestrator#centralClient`
- `@Typed(WildflyDriver.class)` em `WildflyDriver` evita ambiguidade CDI; por isso `DriverProducer` injeta `Instance<WildflyDriver>` (tipo concreto) em vez de `Instance<RuntimeDriver>`
- Propriedades opcionais que podem ser string vazia usam `Optional<String>` — SmallRye Config rejeita `String` com valor `""` (ex: `download.repository.password`)
- `agent.poll-interval` como ISO 8601 duration (ex: `PT300S`) — `@Scheduled` não aceita concatenação de placeholder
- Build Native Image validado no Linux: `mvn clean package -Pnative` → binário Linux funcional em ~45s

### Pendente / TODOs principais
- `QuarkusDriver`: placeholder documentado em `docs/drivers/quarkus-driver.md`
- `ProxyDriver`: placeholder documentado em `docs/drivers/proxy-driver.md`

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

```bash
# JVM (desenvolvimento — Linux ou Windows)
mvn clean package -Dmaven.test.skip=true

# Native Image no Linux (validação — gera binário Linux)
mvn clean package -Pnative -Dmaven.test.skip=true

# Native Image no Windows (produção — gera .exe)
# Dentro do x64 Native Tools Command Prompt for VS 2022:
mvn clean package -Pnative -Dmaven.test.skip=true
```

Ver `docs/development/building.md` para pré-requisitos e troubleshooting.
