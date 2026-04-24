# ADR-001 — GraalVM Native Image

**Status**: Aceito

## Contexto

O agente precisa rodar em servidores Windows de clientes onde não controlamos o ambiente.
Exigir JVM instalada é um pré-requisito operacional que complica o onboarding e pode
causar conflitos com outras JVMs instaladas.

## Decisão

Compilar o agente para executável nativo Windows 64 via GraalVM Native Image.

## Consequências positivas

- Zero dependência de JVM no servidor do cliente
- Startup em milissegundos (importante para serviço reiniciado pelo NSSM)
- Footprint de memória significativamente menor (relevante: Oracle + WildFly já consomem bastante)
- Executável único `.exe` — deploy do próprio agente é trivial

## Consequências negativas

- Build requer GraalVM + Visual Studio Build Tools no ambiente de CI
- Reflection deve ser declarada em `reflect-config.json`
- Algumas bibliotecas precisam de ajuste para Native Image
- Tempo de build maior (minutos vs. segundos para JVM)

## Alternativas consideradas

- **JVM fat JAR + NSSM**: mais simples de buildar, mas exige JVM instalada
- **GraalVM no Windows**: suportado, requer `x64 Native Tools Command Prompt`
