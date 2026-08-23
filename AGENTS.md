# Instrucciones Del Proyecto

## Regla De UI

- Usar medidas exactas y especificas en la interfaz. Definir tamanos concretos para componentes y layouts.
- Priorizar valores fijos y precisos: width, height, dp exactos en lugar de weights o fracciones.
- Usar BoxWithConstraints solo cuando sea estrictamente necesario para debugging o casos especiales.
- Mantener consistencia visual con medidas exactas definidas en el diseno.

## Reglas Codex Del Proyecto

### 1. Lee antes de escribir

Antes de tocar un archivo, leelo primero completo para entender el contexto y evitar errores a mitad del cambio.

Think before acting. Read existing files before writing code.

### 2. Se conciso pero profundo

Da la respuesta directa, sin relleno, pero con razonamiento suficiente para que la decision tenga sentido.

Be concise in output but thorough in reasoning.

### 3. Edita, no reescribas

Si solo hace falta cambiar una parte, edita esa parte en vez de rehacer el archivo entero.

Prefer editing over rewriting whole files.

### 4. No releas lo que ya leiste

Si un archivo ya fue leido y no ha cambiado, no lo vuelvas a leer innecesariamente.

Do not re-read files you have already read unless the file may have changed.

### 5. Prueba antes de decir listo

Verifica el cambio con pruebas o comprobaciones razonables antes de dar el trabajo por terminado.

Test your code before declaring done.

### 6. Cero relleno y saludos

Evita frases vacias, halagos automaticos y cierres largos. Ve directo al grano.

No sycophantic openers or closing fluff.

### 7. Soluciones simples y directas

Prioriza la solucion mas simple que resuelva bien el problema sin sobredisenar.

Keep solutions simple and direct.

### 8. Tu mandas

Si el usuario pide algo especifico, esa instruccion tiene prioridad sobre estas reglas.

User instructions always override this file.

### 9. Informes de cambios

Cuando el usuario pida un informe de cambios, crea un TXT profesional con fecha, resumen ejecutivo, archivos modificados, cambios principales, verificaciones y riesgos pendientes.

Si el usuario pide que el informe vaya al Escritorio, intenta guardarlo alli; si el sandbox lo impide, crea el informe en el workspace y explica la ruta exacta.

### 10. Socios críticos y validación

- Actuar como socios del usuario, no como asistentes complacientes: señalar de forma directa cualquier error, supuesto incorrecto o decisión peligrosa.
- Poder explicar cada cambio realizado en OposiControl y acompañarlo de una validación proporcional al riesgo antes de darlo por terminado.
- Mantener `ponytail` activo en cada cambio: aplicar la solución mínima correcta y usar librerías nuevas solo cuando la plataforma, la biblioteca estándar o las dependencias instaladas no resuelvan el problema.

## Entrada canónica y protocolo de trabajo

Este archivo es el punto de entrada operativo. En cada sesión, leer después [docs/README.md](./docs/README.md), [docs/brain/SOURCE_OF_TRUTH.md](./docs/brain/SOURCE_OF_TRUTH.md), [docs/brain/CURRENT_STATE.md](./docs/brain/CURRENT_STATE.md) y el documento canónico del dominio afectado. Las leyes y el routing Hashira están en [.claude/HASHIRA_LAWS.md](./.claude/HASHIRA_LAWS.md) y [.claude/HASHIRA_ROUTING.md](./.claude/HASHIRA_ROUTING.md).

Antes de editar:

- Capturar `git status --short`, rama, HEAD, `git diff` y `git diff --stat`.
- Clasificar cada cambio existente como `PREEXISTING_PROTECTED_CHANGE`; no asumir que es propio ni tocarlo sin autorización explícita.
- Separar `CURRENT_IMPLEMENTATION`, `TARGET_REQUIREMENT`, `CONFIRMED_PRODUCT_DECISION`, `PRODUCT_DECISION_PENDING`, `NOT_VERIFIED`, `DOCUMENTED_ONLY` y `LIVE_VERIFIED`.
- No usar `git add`, reset, clean, checkout, restore, commit, push, merge, rebase ni force salvo instrucción explícita del usuario.

Antes de cerrar:

- Ejecutar las pruebas proporcionales al cambio y registrar los resultados, incluidos fallos preexistentes.
- Actualizar `docs/brain/CURRENT_STATE.md` y el registro afectado. Las podas documentales no crean actas históricas adicionales.
- Comprobar que no se hayan introducido secretos, staging accidental ni cambios funcionales fuera de alcance.
- Aplicar revisión cruzada: quien implementa no se certifica en solitario.

## Task Routing Quick Guide

| Necesidad | Hashira |
|---|---|
| Arquitectura y limites de modulos | Architecture |
| Ktor, rutas y servicios | Backend/Ktor |
| Supabase, Postgres, migraciones, RLS y RPC | Database |
| Login, auth, JWT, OAuth, roles y seguridad | Auth/Security |
| Admin Compose UI, responsive y navegacion | Admin UI/UX |
| Examenes, preguntas, recursos, PDF e importacion | Content & Import |
| Contratos OposiBot y compatibilidad DTO | Integration |
| Tests, build, regresion y review | Quality |
| Deploy, entorno, Docker y observabilidad | Release/Infra |
| Second Brain, docs y registries | Documentation |

La matriz exacta y las reglas de seleccion estan en [.claude/HASHIRA_ROUTING.md](./.claude/HASHIRA_ROUTING.md); las leyes obligatorias estan en [.claude/HASHIRA_LAWS.md](./.claude/HASHIRA_LAWS.md).

## Canonical Location Quick Reference

- Decisiones duraderas: `docs/architecture/adr/`.
- Riesgos: `docs/registry/RISKS.md`.
- Deuda tecnica: `docs/registry/TECH_DEBT.md`.
- Unknowns: `docs/registry/UNKNOWNS.md`.
- Contradicciones: `docs/registry/CONTRADICTIONS.md`.
- Estado actual: `docs/brain/CURRENT_STATE.md`.
- Producto actual/objetivo: `docs/brain/PRODUCT_SCOPE.md`.

## Fuente de verdad

La precedencia es: comportamiento runtime observado; pruebas ejecutadas; configuración activa; Supabase/storage verificado en lectura; código fuente ejecutado; `docs/brain/SOURCE_OF_TRUTH.md`; ADR aceptadas; contratos y documentos especializados; registros. La documentación no puede elevar una hipótesis por encima de evidencia posterior.

## Alcance protegido de la reorganización

La reorganización actual es documental y de gobernanza. No arregla el login deshabilitado, `AuditViewModel`, endpoints, Ktor, UI, navegación, roles, importación PDF ni Supabase. No se declara resuelto ningún riesgo P1 ni se establece una migración canónica de base de datos mientras persista el drift documentado.
