# ADR-003 — Quarkus como Framework

**Status**: Aceito

## Contexto

O agente precisa de: scheduler, cliente HTTP, injeção de dependência, suporte a Native Image
e CLI (Picocli). O time já conhece o ecossistema Jakarta EE/CDI do projeto principal.

## Decisão

Usar Quarkus como framework base.

## Consequências positivas

- Native Image como cidadão de primeira classe (extensions já testadas)
- `quarkus-picocli`: integração nativa CLI + CDI
- `quarkus-scheduler`: poll loop sem boilerplate
- `quarkus-rest-client-reactive`: cliente HTTP com MicroProfile Rest Client
- Serve como laboratório de migração do app principal (wnfe) para Quarkus no futuro
- CDI familiar para o time

## Consequências negativas

- Overhead de aprendizado inicial das extensões Quarkus
- Mais pesado que alternativas minimalistas (Picocli puro, Micronaut)

## Alternativas consideradas

- **Micronaut**: suporte a Native Image igualmente bom, mas menor sinergia com migração futura do app
- **Picocli puro + HttpClient nativo**: mínimo de dependências, mas sem scheduler, DI ou cliente HTTP declarativo
- **Spring Boot**: Native Image ainda em maturação, overhead maior
