# ADR-004 — Abstração de Drivers de Runtime

**Status**: Aceito

## Contexto

Hoje o runtime é WildFly. No futuro pode ser Quarkus standalone ou incluir um proxy
(nginx/Caddy) na frente. O core do agente (poll, state machine, pipeline) não deve
mudar quando um novo runtime é adicionado.

## Decisão

Interface `RuntimeDriver` com implementação por runtime. O core depende apenas da interface.
Seleção do driver ativo via configuração `driver.type`.

## Consequências positivas

- Adicionar QuarkusDriver ou ProxyDriver não toca o core
- `RuntimeDriver` pode ser mockado em testes — sem WildFly real necessário
- `StateDetector` separado da ação: testável de forma isolada

## Consequências negativas

- Leve overhead de indireção
- Driver precisa implementar contrato completo mesmo que algumas operações não se apliquem
  (ex: ProxyDriver não tem `deploy()` no mesmo sentido — recarrega config)
  - Mitigação: métodos podem retornar `true` / resultado vazio quando não aplicável

## Alternativas consideradas

- **Switch/if por tipo no core**: simples inicialmente, cresce indefinidamente com novos runtimes
- **Plugin dinâmico (classloading)**: complexidade desnecessária para 2-3 runtimes previstos
