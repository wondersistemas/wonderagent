# Build — WonderAgent

## Pré-requisitos

1. **GraalVM JDK 21+** com suporte a Native Image
   - Download: https://github.com/graalvm/graalvm-ce-builds/releases
   - GraalVM 23+ já inclui `native-image` — não é mais necessário `gu install`
   - Verificar: `native-image --version`

2. **Maven 3.9+**

3. Para build do `.exe` no Windows: **Visual Studio Build Tools 2022** com "Desktop development with C++"
   - Build deve rodar dentro do `x64 Native Tools Command Prompt for VS 2022`

## Build JVM (desenvolvimento — Linux ou Windows)

```bash
mvn clean package -Dmaven.test.skip=true -pl agent-cli -am
java -jar agent-cli/target/quarkus-app/quarkus-run.jar --help
```

## Build Native Image no Linux (validação)

O binário gerado é para Linux (`agent-cli-1.0.0-SNAPSHOT-runner`) — não é o `.exe` final,
mas valida toda a configuração de reflection, CDI e Native Image antes de rodar no Windows.

```bash
# Requer GraalVM no PATH
mvn clean package -Pnative -Dmaven.test.skip=true

# Testar
./agent-cli/target/agent-cli-1.0.0-SNAPSHOT-runner --help
```

## Build Native Image no Windows (`.exe` de produção)

```cmd
# No x64 Native Tools Command Prompt for VS 2022
mvn clean package -Pnative -Dmaven.test.skip=true
```

Resultado: `agent-cli\target\agent-cli-1.0.0-SNAPSHOT-runner.exe`

## Tempo de build

| Etapa | JVM | Native Image |
|---|---|---|
| Linux | ~4s | ~45s |
| Windows | ~30s | ~5–10min |

## Configuração de Native Image

Os arquivos de configuração ficam em:
`agent-cli/src/main/resources/META-INF/native-image/br.com.wonder/wonderagent/`

| Arquivo | Propósito |
|---|---|
| `reflect-config.json` | Classes que precisam de reflection (records, enums, commands, drivers) |
| `resource-config.json` | Recursos incluídos no binário (application.yaml, services/) |
| `proxy-config.json` | Interface `CentralClient` para proxy dinâmico do REST Client |
| `native-image.properties` | Flags: `--initialize-at-run-time` (zsync) |

O Quarkus gerencia a maioria das configurações automaticamente via extensões.
Estes arquivos cobrem o que as extensões não detectam estaticamente.

### Compatibilidade de CPU

O perfil `native` em `agent-cli/pom.xml` passa `-march=compatibility` ao GraalVM.
Sem essa flag, o padrão é `x86-64-v3` (requer AVX2, Haswell 2013+), que **não roda**
em servidores Windows mais antigos de clientes.

| Flag | Nível | Requisito mínimo |
|---|---|---|
| `-march=compatibility` | x86-64-v2 | SSE4.2 — Sandy Bridge (2011+) |
| _(padrão GraalVM)_ | x86-64-v3 | AVX2 — Haswell (2013+) |
| `-march=native` | CPU local | Só para dev/benchmark |

## Beans CDI em módulos não-Quarkus

Os módulos `agent-core`, `agent-drivers` e `agent-central-client` são JARs simples
com `META-INF/beans.xml` (`bean-discovery-mode="annotated"`) para que o Quarkus Arc
os escaneie como bean archives. Sem esse arquivo, o CDI não encontra os beans.

## Troubleshooting de build

### Warning: "Unable to properly register hierarchy ... not in Jandex index"
Classes do zsync/okhttp (dependências transitivas) não têm índice Jandex.
É benigno — não causa falha em runtime. O `quarkus.index-dependency` em
`application.yaml` cobre o `zsync-core`.

### Erro: "MSVCRT not found" (Windows)
Rodar dentro do `x64 Native Tools Command Prompt`, não no CMD/PowerShell comum.

### Erro de CDI: "Unsatisfied dependency for RuntimeDriver"
Os módulos `agent-drivers` e `agent-core` precisam de `META-INF/beans.xml` com
`bean-discovery-mode="annotated"`. Se o erro reaparecer após um `clean`, verificar
se os `beans.xml` estão presentes nas pastas `src/main/resources/META-INF/`.

### Adicionar classe ao reflect-config
Editar `agent-cli/src/main/resources/META-INF/native-image/br.com.wonder/wonderagent/reflect-config.json`:
```json
{ "name": "com.exemplo.SuaClasse", "allDeclaredConstructors": true, "allDeclaredMethods": true }
```

### Verificar executável

```bash
# Linux
./agent-cli/target/agent-cli-1.0.0-SNAPSHOT-runner --help
./agent-cli/target/agent-cli-1.0.0-SNAPSHOT-runner config show
```

```cmd
rem Windows
target\agent-cli-1.0.0-SNAPSHOT-runner.exe --help
target\agent-cli-1.0.0-SNAPSHOT-runner.exe config show
```
