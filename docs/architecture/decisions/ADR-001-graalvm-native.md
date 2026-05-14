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

- Build do `.exe` de produção requer Windows + Visual Studio Build Tools
- Reflection deve ser declarada em `reflect-config.json` (ver `META-INF/native-image/`)
- Módulos JAR dependentes precisam de `META-INF/beans.xml` para serem visíveis ao CDI
- `@Scheduled(every=...)` requer expressão ISO 8601 isolada — não aceita concatenação de placeholder
- Tempo de build maior (minutos no Windows vs. segundos para JVM)

## Validação no Linux

O build Native Image funciona no Linux e gera um binário Linux equivalente.
Usar `mvn clean package -Pnative` no Linux para validar reflection e CDI antes
de fazer o build definitivo no Windows.

## Alternativas consideradas

- **JVM fat JAR + NSSM**: mais simples de buildar, mas exige JVM instalada
- **GraalVM no Windows**: suportado, requer `x64 Native Tools Command Prompt`
