# Adicionando um Novo Driver de Runtime

## Passo a passo

### 1. Criar a classe em `agent-drivers`

```
agent-drivers/src/main/java/br/com/wonder/agent/driver/<tipo>/
└── <Tipo>Driver.java
```

```java
@ApplicationScoped
@Named("quarkus")   // deve corresponder a driver.type na configuração
public class QuarkusDriver implements RuntimeDriver {

    @Override
    public String getRuntimeType() { return "quarkus"; }

    @Override
    public RuntimeState detectState() {
        // NUNCA lança exceção — encapsula tudo em UNKNOWN/HUNG
        // NUNCA tem efeitos colaterais
    }

    // ... implementar todos os métodos
}
```

### 2. Registrar o driver

Em `agent-cli`, adicionar seleção por configuração `driver.type`.
O `AgentOrchestrator` usa `@Inject Instance<RuntimeDriver>` resolvendo pelo qualificador.

### 3. Adicionar configuração

Em `application.yaml`, adicionar seção `driver.<tipo>.*`.
Documentar em `docs/operations/configuration.md`.

### 4. Criar documentação do driver

Criar `docs/drivers/<tipo>-driver.md` seguindo a estrutura do
[wildfly-driver.md](../drivers/wildfly-driver.md).

### 5. Atualizar o INDEX.md

Adicionar entrada na tabela de Drivers em `docs/INDEX.md`.

### 6. Escrever testes

- Testes unitários cobrindo todos os estados de `detectState()`
- Cenário de recuperação (HUNG → forceKill → STOPPED)
- Cenário de deploy com sucesso e com falha
- Cenário de health check falhando após start

## Contrato obrigatório (checklist)

- [ ] `detectState()` nunca lança exceção
- [ ] `detectState()` nunca tem efeitos colaterais
- [ ] `deploy()` assume runtime STOPPED
- [ ] `getInstalledVersion()` lê de `.wonder-version`, não do runtime ao vivo
- [ ] `forceKill()` funciona mesmo quando `stop()` trava
- [ ] Todos os timeouts são configuráveis via `application.yaml`
