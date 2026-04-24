# Atualizando o Próprio Agente

O agente não se auto-atualiza — atualizar o `wonderagent.exe` é uma operação manual
(ou automatizada via script PowerShell no Task Scheduler).

## Processo

```powershell
# 1. Parar o serviço
sc stop WonderAgent

# 2. Fazer backup do executável atual
Copy-Item C:\ProgramData\WonderAgent\wonderagent.exe `
          C:\ProgramData\WonderAgent\wonderagent.exe.bak

# 3. Copiar novo executável
Copy-Item <caminho-do-novo-exe> C:\ProgramData\WonderAgent\wonderagent.exe

# 4. Iniciar o serviço
sc start WonderAgent

# 5. Verificar
wonderagent.exe --version
sc query WonderAgent
```

## Rollback

```powershell
sc stop WonderAgent
Copy-Item C:\ProgramData\WonderAgent\wonderagent.exe.bak `
          C:\ProgramData\WonderAgent\wonderagent.exe
sc start WonderAgent
```

## Notas

- O `application.yaml` não é alterado na atualização do agente
- O `.wonder-version` no diretório de deploy não é afetado
- O agente retoma o poll loop normalmente após restart do serviço
