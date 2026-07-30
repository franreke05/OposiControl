# AI Session State

Última actualización: 2026-07-29
Objetivo activo: implementar el plan coordinado no-News y producir una Release
Candidate verificable.
Veredicto actual: **NO-GO**.

## Alcance de esta sesión de Enciclopedia

- Leer `.ai/skills/project-context-map/SKILL.md` y las skills asignadas.
- Leer toda la documentación Markdown del proyecto.
- Contrastar `core/api`, migraciones Supabase y rutas/repositorios reales.
- Mantener contexto, decisiones y matriz de cortes.
- No editar Kotlin, SQL, deployment, observabilidad ni Noticias.

## Documentos creados

- `docs/AI_CONTEXT_MAP.md`
- `docs/AI_TOKEN_INDEX.md`
- `docs/AI_SESSION_STATE.md`
- `docs/AI_DECISION_LOG.md`
- `docs/DECISIONS.md`
- `docs/IMPLEMENTATION_STATUS_2026-07-29.md`

## Lecturas completadas

- `AGENTS.md`, `README.md`, `supabase/README.md` y todos los Markdown bajo
  `docs/` existentes en el corte.
- Skills: mapa de contexto, arquitectura Kotlin, KMP/Ktor, tests KMP, UI móvil,
  revisión UI, implementación de features, split state-holder/UI, Flow
  State/Event, debugging Android y Supabase.
- Contratos completos de `core/api`.
- Migraciones `001`–`007` y pgTAP.
- Rutas/gateways Ktor canónicos, operativos y heredados relevantes.
- Wiring del cliente, sesión segura, red, logging, DI, Effects y tests por
  feature.
- Dockerfile, Compose, Caddyfile y runbooks.

## Estado coordinado de agentes

| Propietario | Trabajo observado | Estado |
|---|---|---|
| Coordinador | integración, migración adicional/soft-delete y gates | en curso |
| Backend | rutas canónicas/operativas, JWT/sesión, RPC, Caddy y tests | en curso |
| UI/UX | theme/primitivas/revisión de pantallas | integración parcial |
| Lógica | Auth, Dashboard, Especialidades, Accesos y Tickets; tests | en curso |
| Logs | logger/redactor/sinks/scripts/runbook | parcial |
| Enciclopedia | contexto, decisiones, matriz y freeze News | en curso hasta verificación final |

## Decisiones activas

- `NEWS=FROZEN`.
- Esquema singular con ID entero.
- Auth directo; negocio por Ktor.
- Actor desde `sub`, sesión activa y RBAC.
- Ráfaga es subtipo de examen.
- Cuatro opciones, una correcta; recursos premium privados.
- Idempotencia durable y versión optimista.
- `core/api` separado de `domain`.
- MVI State/Action/Effect y UI glass exacta.
- Caddy público; Ktor interno.

Detalle: [`DECISIONS.md`](DECISIONS.md).

## Bloqueos que deben sobrevivir a una compactación

1. Supabase remoto devolvió `401`; no hay snapshot/backup ni verificación real.
2. Supabase CLI y Docker no están instalados.
3. RLS permite potencial bypass de `content.publish`.
4. Cliente de contenido aún consume `/content` legacy.
5. API canónica incompleta para identidad administrativa, dashboard, auditoría,
   preferencias y búsqueda.
6. Upload/compensación y timeout 120 s no cerrados.
7. Correlación no llega todavía hasta Supabase.
8. Scopes DI, Effects y matriz UI siguen parciales.
9. No hay evidencia final Android/JVM/backend/iOS ni despliegue VPS.

## Validaciones ejecutadas

- Inventario completo de Markdown, módulos, rutas, migraciones, Effects, tests
  y wiring DI.
- Contraste estático de contratos y migraciones.
- `caddy validate`: correcto.
- Verificación de herramientas: Supabase CLI ausente; Docker ausente.
- Revisión de freeze: repetir `git status` y `git diff --name-only` justo antes
  del cierre, porque el worktree es compartido.

## Próximo paso seguro

1. Esperar cierre de Backend/Lógica y leer sus diffs nuevos.
2. Incorporar migraciones/rutas realmente terminadas a la matriz.
3. Registrar resultados finales de build/test sin convertir ausencia de
   evidencia en éxito.
4. Ejecutar la comprobación de Noticias y validar enlaces/estructura de estos
   documentos.
