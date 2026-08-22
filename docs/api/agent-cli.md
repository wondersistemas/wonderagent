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
Detecta e imprime o estado do runtime sem tomar nenhuma ação. Útil para diagnóstico.
```
> wonderagent.exe detect
HUNG
```

### `verif_atualizacao`
Executa um ciclo de poll único: busca versão desejada no servidor central, compara e aplica se necessário.
Equivalente a um disparo manual do scheduler.
```
> wonderagent.exe verif_atualizacao
```

### `download`
Consulta o servidor central e baixa nova versão do WAR se disponível, sem fazer deploy.
```
> wonderagent.exe download
```

### `deploy`
Faz deploy do artefato WAR já baixado pelo comando `download`.
```
> wonderagent.exe deploy
```

### `stop-wildfly`
Para o WildFly graciosamente via management API. Se não parar dentro do timeout configurado (`driver.wildfly.stop-timeout-seconds`), tenta `forceKill`.
```
> wonderagent.exe stop-wildfly
Parando WildFly...
WildFly parado com sucesso.
```

### `start-wildfly`
Inicia o WildFly e aguarda confirmar RUNNING dentro do timeout configurado (`driver.wildfly.start-timeout-seconds`).
```
> wonderagent.exe start-wildfly
Iniciando WildFly...
WildFly iniciado com sucesso.
```

### `provisionar`
Baixa e instala o WildFly provisionado (equivale a `download-server` + apply). Se já estiver na build correta (SHA-1 confere), não faz nada.
Para SNAPSHOTs, detecta nova build comparando o SHA-1 do `.zsync` remoto com `.wildfly-sha1` local.

```
> wonderagent.exe provisionar
> wonderagent.exe provisionar --version 1.0.0-SNAPSHOT
```

| Opção | Descrição |
|---|---|
| `--version` | Versão a instalar. Se omitido, resolve a última versão publicada no Reposilite. |

### `download-server`
Baixa o ZIP do WildFly para o cache local (`download.temp-dir`) sem instalar. Usa zsync delta se já houver versão em cache.
```
> wonderagent.exe download-server
> wonderagent.exe download-server --version 1.0.0-SNAPSHOT
```

| Opção | Descrição |
|---|---|
| `--version` | Versão a baixar. Se omitido, resolve a última versão publicada no Reposilite. |

### `apply-server`
Extrai e instala o ZIP do WildFly já presente no cache local. Execute `download-server` antes.
```
> wonderagent.exe apply-server
> wonderagent.exe apply-server --version 1.0.0-SNAPSHOT
```

| Opção | Descrição |
|---|---|
| `--version` | Versão a aplicar. Se omitido, usa o ZIP mais recente no cache. |

Após extrair, sincroniza o `.env` do WildFly com as credenciais de banco do `.env` do
agente e recria o usuário admin da management API (a extração zera o
`mgmt-users.properties`).

### `db-version`
Lê `gerenciador.id_versaodb` do banco Oracle e imprime na saída padrão.
Requer `DB_URL`, `DB_USERNAME` e `DB_PASSWORD` configurados no `.env`.
```
> wonderagent.exe db-version
42
```

### `install`
Registra `wonderagent.exe` como serviço Windows via NSSM. Deve ser executado como Administrador.
```
> wonderagent.exe install
> wonderagent.exe install C:\ProgramData\WonderAgent\wonderagent.exe --nssm C:\tools\nssm.exe
```

| Parâmetro | Padrão | Descrição |
|---|---|---|
| `[exePath]` | `C:\ProgramData\WonderAgent\wonderagent.exe` | Caminho para o executável |
| `--nssm` | `nssm` | Caminho para nssm.exe |

### `uninstall`
Remove o serviço Windows.
```
> wonderagent.exe uninstall
```

| Opção | Padrão | Descrição |
|---|---|---|
| `--nssm` | `nssm` | Caminho para nssm.exe |

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
| `--trace` | Ativa log no nível TRACE para diagnóstico detalhado (oculta no `--help`) |

---

## Modo serviço vs. modo CLI

O scheduler (`@Scheduled`) só dispara em modo serviço. Em modo CLI (`verif_atualizacao`),
o poll é executado uma única vez e o processo encerra. Toda a lógica de core
é compartilhada — a diferença é apenas o ponto de entrada.
