# Driver Interface — Contrato RuntimeDriver

## Interface

```java
public interface RuntimeDriver {
    String       getRuntimeType();      // "wildfly", "quarkus", "proxy"
    RuntimeState detectState();         // sem efeitos colaterais, nunca lança
    String       getInstalledVersion(); // null se não detectável
    DeployResult deploy(Artifact artifact); // assume runtime STOPPED
    boolean      start();               // true se RUNNING dentro do timeout
    boolean      stop();                // true se STOPPED dentro do timeout
    boolean      forceKill();           // kill -9 equivalente
    HealthStatus healthCheck();         // verifica endpoint HTTP/porta
}
```

## Regras de contrato

1. **`detectState()` nunca tem efeitos colaterais** — só lê, nunca age.
   Pode ser chamado quantas vezes necessário sem risco.

2. **`detectState()` nunca lança exceção** — encapsula todas as falhas
   retornando `UNKNOWN` ou `HUNG` conforme apropriado.

3. **`deploy()` assume STOPPED** — o orquestrador garante isso antes de chamar.
   O driver não precisa parar o runtime.

4. **`forceKill()` é reservado** — chamado apenas quando `stop()` falha ou
   estado é `HUNG`/`PARTIAL`. Não deve ser chamado em situação normal.

5. **`getInstalledVersion()` lê do `.wonder-version`** — arquivo escrito pelo
   `deploy()` no diretório de deploy. Não deve consultar o runtime ao vivo.

## Registro CDI

Cada driver é um bean `@ApplicationScoped`. A seleção do driver ativo é feita
via configuração `driver.type` + qualificador CDI ou `Instance<RuntimeDriver>`.

## Implementações

| Classe | Tipo | Status |
|---|---|---|
| `WildflyDriver` | `wildfly` | Implementado |
| `QuarkusDriver` | `quarkus` | Placeholder |
| `ProxyDriver` | `proxy` | Placeholder |

Para implementar um novo driver: ver [../development/adding-a-driver.md](../development/adding-a-driver.md).
Para detalhes do WildflyDriver: ver [../drivers/wildfly-driver.md](../drivers/wildfly-driver.md).
