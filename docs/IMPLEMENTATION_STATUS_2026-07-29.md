# Estado de implementación — 29 de julio de 2026

Alcance: auditoría/mejora no-News del plan 29 julio–12 agosto.
Veredicto del corte: **NO-GO para producción**.

## Leyenda

- `HECHO`: código y prueba relevante verificados.
- `PARCIAL`: existe una parte utilizable, pero falta contrato, integración o
  evidencia obligatoria.
- `EN CURSO`: otro propietario está completando el cambio en esta sesión.
- `BLOQUEADO`: depende de acceso/infraestructura o de un gate P0.
- `NO INICIADO`: no hay implementación canónica identificada.
- `LEGACY`: funciona mediante una interfaz anterior, no satisface el destino.

## Resumen por capa

| Capa | Estado | Evidencia | Falta para cerrar |
|---|---|---|---|
| `core/api` | `PARCIAL` | DTO y rutas para identidad, contenido, operación y comercio | alinear todas las rutas reales; paginación efectiva; contrato de upload |
| Supabase 001–007 | `PARCIAL` | esquema singular, RBAC/RLS, RPC, Storage, backfill y ráfaga | ejecución local/remota, pruebas conductuales, snapshot/rollback, cerrar bypass de publicación |
| Auth cliente | `PARCIAL` | login real habilitado y sesión segura por plataforma | demostrar refresh automático concurrente ante 401 y limpieza total de cachés |
| Auth Ktor | `PARCIAL` | JWKS + claims + `/auth/v1/user` + permisos | pruebas con JWKS/JWT reales, revocación, caché RBAC y errores upstream |
| API Ktor canónica | `PARCIAL` | Auth/me, contenido, comercio, accesos, tickets, soporte y moderación | usuarios, roles, permisos, auditoría, preferencias, dashboard y búsqueda; retirar caminos legacy de escritura |
| Cliente de contenido | `LEGACY` | `DataModule` enlaza `KtorContentRepositoryImpl` a `/content` | migrar a `/api/admin/v1` y eliminar serialización mágica/multipaso |
| UI/UX | `PARCIAL` | tema completo, tokens, primitivas glass y breakpoints | matriz visual/accesibilidad; retirar `BoxWithConstraints` no raíz y weights no autorizados |
| Lógica | `PARCIAL` | MVI FIFO; Auth, Especialidades, Accesos y Tickets migrados con Effect | resto de pantallas, scopes DI, latest-wins/doble submit por corte |
| Logs | `PARCIAL` | modelo/redactor/sinks/tests/scripts | instrumentación integral y `correlationId` Ktor→Supabase |
| VPS/Caddy | `PARCIAL` | Dockerfile, Compose endurecido y Caddyfile válido | Docker build, perfiles staging/prod, digest, VPS/DNS y smoke real |
| Noticias | `CONGELADA` | no forma parte del corte | solo regresión compartida |

## Contrato declarado frente a ruta servida

| Contrato `/api/admin/v1` | Servido por Ktor | Consumido por cliente | Estado |
|---|---:|---:|---|
| `/auth/me` | sí | no como fuente única de sesión/rol | `PARCIAL` |
| `/specialties` | sí | contenido sigue en `/content` | `PARCIAL` |
| `/exams` y preguntas | sí | contenido sigue en `/content` | `PARCIAL` |
| opciones y recursos | sí | contenido sigue en `/content` | `PARCIAL` |
| `/users` | no | usa `/admin` legacy | `LEGACY` |
| `/roles`, `/permissions` | no | usa `/admin` legacy | `LEGACY` |
| `/exam-access` | sí | sí | `PARCIAL` |
| `/wallets` | sí | sí | `PARCIAL` |
| `/commerce/*` | sí | sí | `EN CURSO`: idempotencia durable |
| `/support/conversations` | sí | sí | `PARCIAL` |
| `/moderation/actions` | sí | sí | `PARCIAL` |
| `/audit` | no | usa `/admin` legacy | `LEGACY` |
| `/settings/me` | no | usa `/admin` legacy | `LEGACY` |
| `/dashboard/summary` | no | composición/legacy | `LEGACY` |
| `/search` | no canónica | repositorio cliente devuelve vacío/no-op | `NO INICIADO` |
| `/health/live` | sí, pública | diagnóstico cliente aún apunta a `/health` | `PARCIAL` |
| `/health/ready` | sí, permiso `maintenance` | no | `PARCIAL` |

## Matriz de cortes verticales

`Logs` expresa cobertura observada, no intención. `Tests` distingue unitarios de
los tests Ktor/Supabase obligatorios.

| Corte / pantalla | Tablas/RPC | Endpoint activo | Permiso | DTO público | Estado UI/lógica | Logs | Tests | News |
|---|---|---|---|---|---|---|---|---|
| Auth | `auth.users`, `usuario`, `user_role`; RPC `admin_permissions_for_subject` | Supabase Auth + `GET /api/admin/v1/auth/me` | usuario invitado; resolución RBAC | `AdminPrincipalResponse` | login habilitado; `AuthState/Intent/Effect`; Auth VM sigue `factory`, no app-scope | redactor y `OC-NET`; flujo completo no demostrado | tests VM/repositorio; faltan JWT/JWKS/sesión reales | `NEWS=FROZEN` |
| Dashboard | agregados de contenido/operación | canónico declarado, no servido; composición cliente/legacy | `dashboard.read` | `DashboardSummaryResponse` | state/intent y test; sin Effect tipado | sin instrumentación de pantalla demostrada | unitario VM; sin test Ktor canónico | `NEWS=FROZEN` |
| Usuarios | `usuario`, `user_role`, Auth invite | `/admin` legacy; canónico no servido | `users.read/write` | `UserResponse`, `InviteUserRequest`, `UpdateUserRequest` | pantalla existente; VM `single`, contrario a scope de pantalla | parcial en data; no corte completo | VM/data; sin Ktor canónico/RLS por rol | `NEWS=FROZEN` |
| Roles y permisos | `app_role`, `app_permission`, `role_permission`, `user_role`; RPC de reemplazo | `/admin` legacy; canónico no servido | `roles.read/write` | `RoleResponse`, `PermissionResponse`, `UpsertRoleRequest` | pantalla existente; no Effect tipado | no trazabilidad completa | VM; sin RPC/Ktor concurrencia | `NEWS=FROZEN` |
| Especialidades | `especialidad`, `examen` | canónico servido; cliente aún `/content` | `content.read/write` | `SpecialtyResponse`, `UpsertSpecialtyRequest` | State/Intent/Effect y selector por ID | parcial | VM; falta round-trip Ktor/Supabase | `NEWS=FROZEN` |
| Exámenes inmersivos/ráfaga | `examen`, `especialidad`; migración 007 | canónico servido; cliente aún `/content` | `content.read/write/publish` | `ExamResponse`, `UpsertExamRequest`, enums | editor existente; Exams VM global `single`, no workspace-scope | parcial | parser/VM; falta matriz tipo/acceso/estado round-trip | `NEWS=FROZEN` |
| Preguntas y opciones | `pregunta`, `opcion_pregunta`; RPC de importación | canónico servido y `/content` legacy | `content.read/write` | `ExamQuestionResponse`, `QuestionOption*` | editor integrado a Exams; camino activo legacy | parcial | VM/import parser; falta atomicidad save+4 opciones demostrada | `NEWS=FROZEN` |
| Recursos | `recurso_pregunta`, `storage.objects`, `storage_cleanup_outbox` | metadatos/signado canónicos; sin endpoint de upload completo | `content.read/write` + acceso al examen | `QuestionResourceResponse`, `UpsertQuestionResourceRequest` | editor existente | `OC-UPLOAD` definido, cobertura no demostrada | sin 50 MB/MIME/timeout/compensación E2E | `NEWS=FROZEN` |
| Importación/reordenación | RPC `admin_import_exam_questions`, `admin_reorder_exam_questions`, `api_idempotency` | canónico servido; bridge `/content` añadido | `content.write` | `ImportQuestionsRequest`, `ReorderQuestionsRequest` | parser Aiken/GIFT con preview existente | parcial | parser unitario; faltan rollback/idempotencia/50 preguntas en Supabase real | `NEWS=FROZEN` |
| Accesos | `acceso_examen_usuario`; RPC `admin_set_exam_access` | `GET/POST /api/admin/v1/exam-access` | `exam_access.read/write` | `ExamAccessResponse`, `SetExamAccessRequest` | IDs tipados, State/Intent/Effect; selector catálogo pendiente | parcial | VM; falta RPC duplicada/concurrencia/RLS | `NEWS=FROZEN` |
| Tickets | `cartera_tickets`, `transaccion_ticket`; RPC `admin_adjust_tickets` | `/api/admin/v1/wallets/*` | `tickets.read/adjust` | wallet, transacción y ajuste con `Long` | State/Intent/Effect y doble envío controlado | parcial | VM; faltan 20 ajustes concurrentes y ledger real | `NEWS=FROZEN` |
| Tienda | tablas `tienda_*`, `recurso_limitado`, `campana_rafaga`; RPC durable en curso | `/api/admin/v1/commerce/*` | `commerce.read/write` | contratos `Store*`, `Limited*`, `Burst*` | pantalla/VM existente; sin Effect ni test | parcial | sin test feature; integración/idempotencia pendiente | `NEWS=FROZEN` |
| Soporte | `support_conversation`, `support_message`; RPC `admin_send_support_message` | `/api/admin/v1/support/conversations/*` | `support.read/reply/internal_note` | `SupportConversationResponse`, `SupportMessageResponse` | pantalla/VM existe; VM `single`; sin Effect | contenido sensible debe redactarse; cobertura no demostrada | VM; falta Ktor/RPC/idempotencia | `NEWS=FROZEN` |
| Moderación | `moderation_action`; RPC `admin_create_moderation_action` | `/api/admin/v1/moderation/actions` | `moderation.read/write` | `ModerationActionResponse`, `CreateModerationActionRequest` | pantalla/VM factory; sin Effect | parcial | sin test feature/Ktor/RPC | `NEWS=FROZEN` |
| Auditoría | `audit_event` | `/admin` legacy; canónico no servido | `audit.read` | `AuditEventResponse` | pantalla/VM/test existentes; sin Effect | requestId previsto, no trazabilidad E2E | VM; sin Ktor canónico | `NEWS=FROZEN` |
| Ajustes | `admin_preference` | `/admin` legacy; canónico no servido | `settings.read/write` | `AdminPreferenceResponse`, `UpdateAdminPreferenceRequest` | pantalla/VM `single`; sin Effect | parcial | VM; sin persistencia canónica probada | `NEWS=FROZEN` |
| Búsqueda | `search_document`, `search_tag`, `search_document_tag` | `/search` legacy existe; repositorio cliente está no-op | `search.read` | `SearchResponse` | UI existe, integración deshabilitada | búsquedas no pueden registrarse | sin tests canónicos | `NEWS=FROZEN` |
| Diagnóstico | salud y readiness | backend `/health/live`; cliente usa `/health`; V1 requiere JWT | `maintenance` salvo live | `HealthResponse` | panel existente pero rutas desalineadas | requestId Ktor; correlation pendiente | prueba live/401/403 básica; smoke cliente falla por contrato | `NEWS=FROZEN` |
| Noticias | tablas/gateway/endpoints históricos | sin cambio deliberado | comportamiento previo | contratos previos | fuera de auditoría/mejora | sin métricas nuevas | solo regresión compartida | `NEWS=FROZEN` |

## Coherencia de migraciones

| Migración | Propósito | Estado de auditoría | Gate pendiente |
|---|---|---|---|
| `001_admin_core_schema` | esquema singular, constraints, índices, versiones y soft delete | revisada estáticamente | instalación desde cero y snapshot |
| `002_admin_rbac_rls` | permisos, roles, grants, RLS y resolución de permisos | contradicción detectada en publicación directa | test por rol y policy que preserve `content.publish` |
| `003_admin_transaction_rpcs` | tickets, accesos, roles, soporte, moderación | firmas contrastadas con gateways | pgTAP conductual y concurrencia |
| `004_admin_legacy_backfill` | extraer payload mágico y guardrails | aditiva | conteos, consumidores y rollback ensayado |
| `005_admin_storage` | buckets/policies | revisada estáticamente | upload, MIME, privacidad y compensación |
| `006_admin_exam_rpcs` | importación/reordenación/idempotencia | revisada estáticamente | rollback total e idempotencia real |
| `007_legacy_rafaga_to_exam` | ráfaga → examen canónico | revisada estáticamente | equivalencia de conteos y consumidores |

La suite `supabase/tests/admin_schema.test.sql` comprueba estructura, no toda la
conducta requerida. No sustituye pruebas por rol, concurrencia, replay de
idempotencia, snapshot ni rollback.

## Bloqueos priorizados

### P0 — impiden Release Candidate verificable

1. **Sin entorno Supabase utilizable:** el acceso observado devolvió `401`; no
   hay snapshot/backup remoto ni inventario de consumidores.
2. **Sin herramientas de integración local:** Supabase CLI y Docker no están
   disponibles en este equipo; no se pueden ejecutar `db reset`, pgTAP,
   `docker compose build` ni migración ensayada.
3. **RLS de publicación:** la policy DML de `examen` permite a
   `content.write` actualizar `estado` por Data API, saltándose el permiso
   `content.publish` que Ktor sí comprueba.
4. **Convivencia de caminos de escritura:** el cliente de contenido sigue
   enlazado a `/content`, cuyo gateway heredado contiene serialización mágica,
   operaciones multipaso y borrados físicos.
5. **Atomicidad/idempotencia aún en integración:** guardar pregunta con cuatro
   opciones y los upserts de comercio deben quedar en RPC durable, compilados y
   probados; una caché `ConcurrentHashMap` no cumple el contrato.
6. **API canónica incompleta:** usuarios, roles/permisos, auditoría, ajustes,
   dashboard y búsqueda carecen de ruta canónica servida/consumida.
7. **Sin prueba E2E de exámenes:** no hay evidencia de crear, importar, editar,
   publicar y refrescar todas las combinaciones sin pérdida.

### P1 — bloquean Go a producción

1. Upload de recursos sin contrato de transferencia/compensación completo;
   timeout HTTP Supabase de 15 s frente al objetivo de 120 s.
2. `correlationId` no se propaga Ktor → Supabase; instrumentación por pantalla
   y repositorio incompleta.
3. Refresh automático coordinado ante `401` y limpieza global de cachés al
   logout no demostrados.
4. `CursorPage` existe, pero varios listados no aplican cursor ni límite 50/100.
5. Scopes DI no alineados: Auth `factory`, Exams global `single`, Users,
   Settings y Support `single`.
6. Matriz UI requerida sin evidencia completa; `BoxWithConstraints` aparece
   fuera de raíz y quedan weights en UI no-News.
7. Compose sin perfiles staging/producción y sin imagen fijada por digest.
8. Sin ejecución CI macOS/iOS ni smoke real VPS/DNS/HTTPS.

## Verificaciones observadas

| Comprobación | Resultado |
|---|---|
| `caddy validate --adapter caddyfile --config deploy/caddy/Caddyfile` | `PASS` |
| presencia Supabase CLI | `BLOQUEADO`: no instalada |
| presencia Docker/Compose | `BLOQUEADO`: no instalado |
| acceso administrativo remoto Supabase | `BLOQUEADO`: respuesta `401` |
| inspección estática migraciones 001–007 | `PASS` con riesgos anteriores |
| build/test completo final | pendiente del corte de integración |
| diff directo de Noticias | pendiente de repetir al cierre |

## Condición para cambiar a Go

No basta con que compile. Deben cerrarse los P0 y aportar evidencia reproducible
de migración/rollback, RLS por rol, pruebas Ktor, MockEngine, concurrencia,
round-trip de exámenes, privacidad de Storage, matriz UI, builds Android/JVM/iOS
y smoke VPS. Noticias permanece fuera del criterio funcional de esta entrega,
pero su regresión compartida debe quedar verde.
