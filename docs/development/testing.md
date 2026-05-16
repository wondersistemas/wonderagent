# Estratégia de Testes

## Princípios

- `RuntimeDriver` é mockável — testes de `StateMachine` e `DeployPipeline` não precisam
  de WildFly real
- `WildflyDriver.detectState()` é testado com servidor HTTP embutido (`com.sun.net.httpserver`)
  simulando a management API, e subclasse de teste que sobrescreve `isProcessAlive()`
- Testes de integração do `WildflyDriver` (start/stop real) requerem WildFly local e são
  executados manualmente com o profile `integration-tests`
- Todos os testes são JUnit 5 puro + Mockito — sem `@QuarkusTest`, sem container CDI

## Estrutura por módulo

### agent-model
Records e enums sem lógica comportamental.

| Arquivo | O que testa |
|---|---|
| `ArtifactTest` | `coordinates()`, imutabilidade de `withLocalFile()` |
| `DeployResultTest` | Factories `success()` e `failure()` |
| `HealthStatusTest` | Factories `ok()` e `unhealthy()` |
| `RuntimeStateTest` | `needsRecovery()` e `isActionable()` |

### agent-core
Testes com `@ExtendWith(MockitoExtension.class)` e `@InjectMocks`.

| Arquivo | O que testa |
|---|---|
| `StateMachineTest` | `detectAndRecover()` para todos os estados, `canDeploy()` |
| `DeployPipelineTest` | Fluxo completo: parar/não-parar, forceKill fallback, falhas em cada etapa |
| `AgentOrchestratorTest` | Versão já instalada, atualização, falha de download, falha de deploy, report de status |
| `ArtifactDownloaderTest` | Construção da URL zsync, reutilização de arquivo existente como input, wrapping de `ZsyncException` |
| `WildflyProvisionerTest` | Construção da URL do ZIP, versão já instalada (skip), versão fixada via `fixedVersion`, download via zsync, extração do ZIP com strip do primeiro componente, falha de download |

`@ConfigProperty` é injetado via reflexão nos testes do `AgentOrchestrator`:
```java
setField(orchestrator, "clientId", "cliente-abc");
setField(orchestrator, "agentVersion", "1.0.0-SNAPSHOT");
```

### agent-drivers
| Arquivo | O que testa |
|---|---|
| `WildflyDriverDeployTest` | Cópia do WAR, escrita/leitura do `.wonder-version`, arquivo fonte inexistente |
| `WildflyDriverDetectStateTest` | Estados RUNNING, HUNG (porta aberta/fechada), PARTIAL (deployment FAILED), STOPPED |

`WildflyDriverDetectStateTest` usa dois mecanismos para isolar do SO:
- **Servidor HTTP embutido** na porta 0 (porta aleatória) simulando `/management`
- **Subclasse `TestableWildflyDriver`** com `isProcessAlive()` sobrescrito para retornar `true`

`@TempDir` isola toda operação de arquivo em diretório temporário descartado após cada teste.

## Executar testes

```bash
# Unitários (todos os módulos)
mvn test

# Módulo específico
mvn test -pl agent-core

# Integração (requer WildFly local rodando)
mvn verify -Pintegration-tests
```

## Padrões de injeção em testes

### @ConfigProperty via reflexão
```java
private void setField(Object target, String fieldName, Object value) throws Exception {
    var field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
}
```

Para `WildflyDriver`, que herda campos da superclasse, a busca percorre a hierarquia:
```java
Class<?> clazz = target.getClass();
while (clazz != null) {
    try {
        var field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
        return;
    } catch (NoSuchFieldException e) {
        clazz = clazz.getSuperclass();
    }
}
```

### Zsync mockável via construtor
`ArtifactDownloader` e `WildflyProvisioner` têm construtor package-private que aceita `Zsync` para injeção de mock:
```java
Zsync zsync = mock(Zsync.class);
ArtifactDownloader downloader = new ArtifactDownloader(zsync);
WildflyProvisioner provisioner = new WildflyProvisioner(zsync);
```
