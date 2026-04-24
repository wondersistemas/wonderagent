# ADR-002 — Modelo Pull

**Status**: Aceito

## Contexto

O acesso aos servidores dos clientes é via RDP (às vezes com VPN por cliente). Não há
conectividade de rede direta de entrada — ferramentas push como Ansible (WinRM) não são viáveis.

## Decisão

O agente inicia a conexão (pull). O servidor central nunca conecta no agente.
Toda comunicação é HTTPS de saída, porta 443.

## Consequências positivas

- Funciona atrás de qualquer firewall/NAT (só saída HTTPS)
- Sem exposição de porta no servidor do cliente
- Modelo simples: agente acorda, verifica, age, dorme

## Consequências negativas

- Latência de até 1 intervalo de poll para deploy chegar (padrão: 5 minutos)
- Sem capacidade de push imediato do servidor central
  - Mitigação: `wonderagent.exe check` pode ser executado manualmente via RDP

## Alternativas consideradas

- **Ansible (push + WinRM)**: descartado — sem acesso de rede direta
- **Agente com WebSocket (push via conexão persistente)**: mais complexo, sem ganho real dado
  que deploys não são time-critical ao nível de segundos
