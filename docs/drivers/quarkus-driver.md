# QuarkusDriver (placeholder)

Driver para aplicação Quarkus standalone rodando como serviço Windows.

**Status**: não implementado. Este documento define o contrato esperado.

## Detecção de estado

```
1. sc query <service-name>           → STOPPED se não rodando
2. GET /q/health/live                → HUNG se não responde
3. GET /q/health/ready               → PARTIAL se unhealthy
4.                                   → RUNNING
```

## Deploy

1. Parar serviço: `sc stop <service-name>`
2. Copiar novo JAR para o diretório de instalação
3. Escrever `.wonder-version`
4. Iniciar serviço: `sc start <service-name>`

## Health check

`GET /q/health` — endpoint padrão do Quarkus SmallRye Health.

## TODOs para implementação

- [ ] Definir nome do serviço Windows via configuração `driver.quarkus.service-name`
- [ ] Definir porta HTTP via `driver.quarkus.http-port`
- [ ] Implementar `QuarkusDriver implements RuntimeDriver`
- [ ] Adicionar testes unitários com mock de `sc` e health endpoint
- [ ] Documentar configuração em `operations/configuration.md`
