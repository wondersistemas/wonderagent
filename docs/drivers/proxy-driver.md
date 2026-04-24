# ProxyDriver (placeholder)

Driver para proxy reverso (nginx ou Caddy) na frente do runtime principal.

**Status**: não implementado. Este documento define o contrato esperado.

## Comportamento diferente dos outros drivers

O ProxyDriver não faz deploy de artefato — ele atualiza e recarrega configuração.
Os métodos `deploy()` e `start()`/`stop()` têm semântica diferente:

- `deploy(artifact)`: substitui o arquivo de configuração do proxy
- `start()`: `nginx -s reload` ou `caddy reload`
- `stop()`: não aplicável — proxy deve ficar sempre no ar
- `forceKill()`: apenas em caso de emergência

## Detecção de estado

```
1. sc query nginx / caddy            → STOPPED se não rodando
2. GET http://localhost:<porta>/health → HUNG se não responde
3.                                   → RUNNING
```

## TODOs para implementação

- [ ] Decidir nginx vs. Caddy como padrão (Caddy tem recarga automática de certificado)
- [ ] Definir formato do "artefato" de configuração do proxy
- [ ] Implementar `ProxyDriver implements RuntimeDriver`
- [ ] Integrar com orquestração multi-runtime (proxy + app juntos)
