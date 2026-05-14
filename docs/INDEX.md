# WonderAgent — Índice de Documentação

Este é o entry point para qualquer leitura do projeto. Leia este arquivo primeiro.
Cada entrada indica o que o documento contém e quando consultá-lo.

## O que é o WonderAgent

Agente pull de deploy on-premises para servidores Windows. Roda como serviço Windows
(GraalVM Native Image — sem JVM), busca periodicamente o estado desejado de um servidor
central via HTTPS e aplica atualizações de forma autônoma.

---

## Arquitetura

| Documento | Quando consultar |
|---|---|
| [architecture/overview.md](architecture/overview.md) | Visão geral: problema, componentes, fluxo de dados |
| [architecture/state-machine.md](architecture/state-machine.md) | Estados do runtime, transições, recuperação de falhas |
| [architecture/driver-interface.md](architecture/driver-interface.md) | Contrato RuntimeDriver, regras de implementação |
| [architecture/decisions/ADR-001-graalvm-native.md](architecture/decisions/ADR-001-graalvm-native.md) | Por que GraalVM Native Image |
| [architecture/decisions/ADR-002-pull-model.md](architecture/decisions/ADR-002-pull-model.md) | Por que pull em vez de push |
| [architecture/decisions/ADR-003-quarkus.md](architecture/decisions/ADR-003-quarkus.md) | Por que Quarkus como framework |
| [architecture/decisions/ADR-004-driver-abstraction.md](architecture/decisions/ADR-004-driver-abstraction.md) | Por que a abstração de drivers |

## API

| Documento | Quando consultar |
|---|---|
| [api/central-api.md](api/central-api.md) | Endpoints consumidos pelo agente (contrato completo) |
| [api/agent-cli.md](api/agent-cli.md) | Comandos CLI com exemplos |

## Drivers

| Documento | Quando consultar |
|---|---|
| [drivers/wildfly-driver.md](drivers/wildfly-driver.md) | Como o WildflyDriver detecta estado, deploya, health check |
| [drivers/quarkus-driver.md](drivers/quarkus-driver.md) | Placeholder: contrato + TODOs para implementação futura |
| [drivers/proxy-driver.md](drivers/proxy-driver.md) | Placeholder: contrato + TODOs para implementação futura |

## Operações

| Documento | Quando consultar |
|---|---|
| [operations/installation.md](operations/installation.md) | Onboarding de nova instalação passo a passo |
| [operations/configuration.md](operations/configuration.md) | Referência completa do application.yaml |
| [operations/troubleshooting.md](operations/troubleshooting.md) | Estados problemáticos e como resolver |
| [operations/updating-agent.md](operations/updating-agent.md) | Como atualizar o próprio agente |

## Desenvolvimento

| Documento | Quando consultar |
|---|---|
| [development/building.md](development/building.md) | Build JVM (Linux/Windows), Native Image (Linux para validação, Windows para `.exe`) |
| [development/testing.md](development/testing.md) | Estratégia de testes, mocks de driver |
| [development/adding-a-driver.md](development/adding-a-driver.md) | Passo a passo para implementar novo RuntimeDriver |

---

## Mapa de módulos

```
agent-model           ← DTOs, enums, interfaces — zero dependências externas
agent-core            ← StateMachine, DeployPipeline, AgentOrchestrator (poll loop)
agent-drivers         ← WildflyDriver (+ futuros QuarkusDriver, ProxyDriver)
agent-central-client  ← CentralClient REST (MicroProfile Rest Client)
agent-cli             ← Entry point Picocli + produz o .exe via Native Image
```

## Convenções do projeto

- Nenhuma ação de deploy ocorre sem `StateMachine.detectAndRecover()` prévio
- `RuntimeDriver.detectState()` nunca tem efeitos colaterais
- Versão instalada é persistida em `.wonder-version` no diretório de deploy
- Toda comunicação com o servidor central é autenticada via JWT no header `Authorization`
