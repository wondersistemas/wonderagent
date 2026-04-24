# Build — GraalVM Native Image no Windows

## Pré-requisitos

1. **GraalVM JDK 21+** com suporte a Native Image
   - Download: https://github.com/graalvm/graalvm-ce-builds/releases
   - Instalar `native-image`: `gu install native-image`

2. **Visual Studio Build Tools 2022** com "Desktop development with C++"
   - Necessário para compilar o executável nativo no Windows

3. **Maven 3.9+**

4. **Variável de ambiente**: build deve rodar dentro do
   `x64 Native Tools Command Prompt for VS 2022`

## Build JVM (desenvolvimento)

```cmd
cd agent-cli
mvn clean package -Dmaven.test.skip=true
java -jar target/quarkus-app/quarkus-run.jar
```

## Build Native Image

```cmd
# No x64 Native Tools Command Prompt
cd agent-cli
mvn clean package -Pnative -Dmaven.test.skip=true
```

Resultado: `agent-cli/target/agent-cli-1.0.0-SNAPSHOT-runner.exe`

## Tempo de build

- JVM: ~30s
- Native Image: ~5-10 minutos (normal)

## Troubleshooting de build

### Erro: "MSVCRT not found"
Rodar dentro do `x64 Native Tools Command Prompt`, não no CMD/PowerShell comum.

### Erro de reflection
Adicionar classes em `agent-cli/src/main/resources/reflection-config.json`:
```json
[
  { "name": "br.com.wonder.agent.SuaClasse", "allDeclaredMethods": true }
]
```

### Verificar executável
```cmd
target\agent-cli-1.0.0-SNAPSHOT-runner.exe --version
target\agent-cli-1.0.0-SNAPSHOT-runner.exe detect
```
