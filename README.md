# OposiControl

OposiControl es la aplicación administrativa y operativa de OposiControl. Es un proyecto Kotlin Multiplatform con objetivos Android, iOS y Desktop/JVM. Su backend actual está en Ktor. Product Metrics V1 y wallet están verificados en el alcance ejecutado de MAIN; la cobertura legacy, Storage y proveedores externos permanece `NOT_VERIFIED`.

## Entrada operativa

Antes de trabajar, leer en este orden:

1. [AGENTS.md](./AGENTS.md)
2. [docs/README.md](./docs/README.md)
3. [docs/brain/SOURCE_OF_TRUTH.md](./docs/brain/SOURCE_OF_TRUTH.md)
4. [docs/brain/CURRENT_STATE.md](./docs/brain/CURRENT_STATE.md)
5. El documento canónico del dominio afectado.

La gobernanza Hashira vive en [.claude/HASHIRA_LAWS.md](./.claude/HASHIRA_LAWS.md), [.claude/HASHIRA_ROUTING.md](./.claude/HASHIRA_ROUTING.md) y en los diez agentes de [.claude/agents](./.claude/agents). La configuración local `.claude/settings.local.json` no forma parte del conocimiento versionado.

## Estado de esta reorganización

- `SECOND_BRAIN_MIGRATION: COMPLETED` para la primera estructura documental.
- `HASHIRA: ACTIVE`.
- `MIGRATION_REMEDIATION: COMPLETED` para la documentación PASS 01.
- `DATABASE: VERIFIED` para Product Metrics/wallet.
- `BACKEND: BACKEND_PASS` con JWT E2E válido bloqueado por fixture externo.
- Las auditorías, actas cerradas y skills históricos se eliminaron del working tree; Git conserva su historial versionado.
- El cliente actualmente confirmado en código es `ARTURO`. `Claudia`/`CaravanTruck` no se consideran parte del producto sin evidencia específica.

## Construcción y pruebas

En Windows:

```powershell
.\gradlew.bat :backend:ktor:test --no-daemon --console=plain
.\gradlew.bat :features:exams:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat test --no-daemon --console=plain
```

El resultado de la validación está registrado en [docs/engineering/TESTING.md](./docs/engineering/TESTING.md). Las pruebas ejecutadas no sustituyen la verificación de runtime visual, despliegue o consumidores externos.

## Límites de esta fase

Esta fase organiza conocimiento, contratos y gobernanza. No cambia Kotlin funcional, endpoints, navegación, UI, roles, Supabase, migraciones, SQL/RPC/RLS/storage, autenticación ni importación PDF. Toda afirmación se etiqueta como implementación actual, decisión de producto, objetivo, documentación o desconocida.
