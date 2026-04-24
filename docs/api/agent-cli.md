# CLI — Referência de Comandos

```
wonderagent.exe [comando] [opções]
```

Sem comando: inicia em modo serviço (bloqueante — use NSSM para gerenciar como serviço Windows).

---

## Comandos

### `status`
Mostra estado atual do runtime, versão instalada e resultado do health check.
```
> wonderagent.exe status
Estado:  RUNNING
Versão:  2.45.1
Saúde:   OK
```

### `detect`
Detecta e imprime o estado do runtime sem tomar nenhuma ação.
Útil para diagnóstico.
```
> wonderagent.exe detect
HUNG
```

### `check`
Executa um ciclo de poll único: busca versão desejada, compara e aplica se necessário.
Equivalente a um disparo manual do scheduler.
```
> wonderagent.exe check
```

### `install`
Registra `wonderagent.exe` como serviço Windows via NSSM.
Deve ser executado como Administrador.
```
> wonderagent.exe install
```

### `uninstall`
Remove o serviço Windows.
```
> wonderagent.exe uninstall
```

### `config show`
Exibe a configuração ativa resolvida (application.yaml + variáveis de ambiente).
```
> wonderagent.exe config show
```

---

## Flags globais

| Flag | Descrição |
|---|---|
| `--help` / `-h` | Ajuda |
| `--version` / `-V` | Versão do agente |

---

## Modo serviço vs. modo CLI

O scheduler (`@Scheduled`) só dispara em modo serviço. Em modo CLI (`check`),
o poll é executado uma única vez e o processo encerra. Toda a lógica de core
é compartilhada — a diferença é apenas o ponto de entrada.
