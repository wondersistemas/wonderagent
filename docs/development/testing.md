# Estratégia de Testes

## Princípios

- `RuntimeDriver` é mockável — testes de `StateMachine` e `DeployPipeline` não precisam
  de WildFly real
- `StateDetector` é testado com mocks de processo e porta
- Testes de integração do `WildflyDriver` requerem WildFly real (rodar manualmente)

## Estrutura por módulo

### agent-model
Sem lógica — apenas testes de criação de records e enums.

### agent-core
Testes unitários com Mockito:
```java
@ExtendWith(MockitoExtension.class)
class DeployPipelineTest {
    @Mock RuntimeDriver driver;
    @Mock StateMachine stateMachine;
    @InjectMocks DeployPipeline pipeline;

    @Test
    void deploy_quandoRuntimeHung_recuperaAntesDeployar() {
        when(stateMachine.detectAndRecover()).thenReturn(RuntimeState.STOPPED);
        when(driver.deploy(any())).thenReturn(DeployResult.success(...));
        // ...
    }
}
```

### agent-drivers
- Testes unitários: mock das chamadas HTTP (HttpURLConnection) e ProcessBuilder
- Testes de integração (profile `integration-tests`): WildFly local

### agent-central-client
Testes com WireMock simulando o servidor central:
```java
@QuarkusTest
class CentralClientTest {
    // WireMock stub para /api/v1/agents/{id}/desired-state
}
```

## Executar testes

```cmd
# Unitários (todos os módulos)
mvn test

# Integração (requer WildFly local)
mvn verify -Pintegration-tests
```

## Mock de RuntimeDriver para cenários de teste

```java
// Driver que simula WildFly sempre RUNNING
public class AlwaysRunningDriver implements RuntimeDriver {
    public RuntimeState detectState() { return RuntimeState.RUNNING; }
    public boolean stop() { return true; }
    public boolean start() { return true; }
    public HealthStatus healthCheck() { return HealthStatus.healthy(); }
    // ...
}
```
