# CONTROL_PANEL_V1_AUDIT

Fecha: 2026-10-19 · Ciclo 1: trazado estático UI → repositorio → HTTP → ruta Ktor → RPC/tabla. **Ningún flujo verificado en runtime en este ciclo.** Un estado `WIRED` significa cableado en código, no funcional certificado.

Ramas: panel `feature/december-v1-control-panel` @ `29a019f`; servidor `feature/december-v1-server` @ `86f0da9`; `develop` @ `7fb9000`.

## Hecho principal

Las rutas Ktor de Control V1 **no están en v1-server**: las añade la rama del panel (`backend/ktor/.../AdminControl{Alerts,Commerce,Config,Forum,Grading,OfficialQuestions,Referral,V1}.kt`, 29 rutas). Esas rutas llaman a 13 RPC que solo existen en migraciones de v1-server (`20261022101300`, `101800`, `101900`, `102300_v1_control_rpcs.sql`). **Ni el panel ni v1-server pueden publicarse solos**: el panel funciona únicamente sobre develop + v1-server + panel.

Comprobado en código: nombres de parámetros de las 13 RPC y claves JSON de salida coinciden con las llamadas del panel; DTOs Order, ExamEconomy, GradingRules, OrderResolution, ForumQueue coinciden campo a campo; claves de `config-v1` coinciden con el seed del servidor. Ambos lados usan `ignoreUnknownKeys` y `explicitNulls=false`.

## Matriz de capacidades

| Capacidad | Pantalla / VM | Ruta | ¿Servidor? | DTO | Estado | Evidencia |
|---|---|---|---|---|---|---|
| Exámenes con comunidad + especialidad | ExamBasicInfoSection / ExamsViewModel.kt:1026 | POST/PATCH `/api/v1/admin/examenes` | sí; v1-server exige comunidad activa (422) | sí | WIRED_END_TO_END_IN_CODE | AdminV1Routes.kt:416 |
| Reglas de puntuación + auditoría | ExamGradingRulesSection | GET/PUT `.../reglas-puntuacion` | solo panel → `admin_set_exam_grading_rules` | sí | WIRED en merge | AdminControlGrading.kt:279 |
| Preguntas oficiales (anulada/reserva) | ExamOfficialQuestionsSection | GET/PUT `.../preguntas-oficiales/{q}` | solo panel | sí | WIRED en merge | AdminControlOfficialQuestions.kt:122-151 |
| Importación (reservas FA37, razonamiento rico FA38) | ImportViewModel / ImportReviewPane | `/api/v1/admin/importaciones/preguntas*` | campos FA37/38 solo en v1-server | **contra develop solo: `bloque` ignorado → reservas importadas como principales** | WIRED en merge; DTO_MISMATCH con develop | KtorAdminV1ContentRepositoryImpl.kt:1205-1221 |
| Editor de marcado | MarkupEditorScreen | ninguna (parser local espejo) | n/a | n/a | UI_ONLY por diseño | HubScreens.kt |
| Packs y precios OposiTickets | Store CommercialCatalog | `/api/v1/admin/comercial/productos` | sí (develop) | sí; `metadatos.recarga_base` no editable | WIRED_END_TO_END_IN_CODE | AdminV1Routes.kt:1547 |
| Precio en tickets / días de acceso | ExamEconomyScreen | GET/PATCH `/api/v1/admin/examenes-economia` | solo panel | sí | WIRED en merge | AdminControlCommerce.kt:359 |
| Topes economía, reto diario, demo | — | `config-v1` solo admite foro/referido | claves existen en servidor | — | NOT_IMPLEMENTED | AdminControlConfig.kt |
| Pedidos / incidencias / reembolsos | OrdersScreen | `/pedidos*`, `/{id}/resolver` | solo panel | sí | WIRED en merge; **reembolsar → REFUNDS_DISABLED fuera de local/test** (SEC-V1-06) | AdminControlCommerce.kt:339-379 |
| Accesos a examen | ExamAccessScreen | `/accesos*` | sí; v1-server fija 1 mes vía RPC | **UI dice «Sin caducidad» y envía `validUntil` que el servidor ignora** | WIRED (P2 deriva de UI) | KtorAdminOperationsRepositoryImpl.kt:78-88, 232 |
| Usuarios y roles | Users / Roles | `/usuarios*`, `/roles` | sí | sí | WIRED_END_TO_END_IN_CODE | AdminV1Routes.kt:1226, 1371 |
| Corrección administrativa de especialidad | — | — | trigger bloquea a **todo** escritor | — | NOT_IMPLEMENTED (requisito: corrección admin auditada) | 20261022100600 |
| Referidos | ReferralScreen | `/referidos/invitaciones*` | solo panel | sí | WIRED en merge | AdminControlReferral.kt:84-137 |
| Moderación y soporte | moderation / support | `/moderacion/*`, `/soporte/*` | sí | sí | WIRED_END_TO_END_IN_CODE (moderación solo «Advertencia», DEBT-130) | AdminV1Routes.kt:1399, 1430 |
| Operaciones de foro | ForumOpsScreen | `/api/v1/admin/foro/*` | solo panel → 7 RPC | sí | WIRED en merge | AdminControlForum.kt:300-380 |
| Auditoría / analítica | Audit, Analytics | `/auditoria`, `/analytics` | sí | sí | WIRED_END_TO_END_IN_CODE | AdminV1Routes.kt:1201 |
| Alertas | AlertsScreen | `/alertas/*` | solo panel | sí | WIRED en merge | AdminControlAlerts.kt:107 |
| Recalcular estadísticas | — | POST `/api/v1/admin/estadisticas/recalcular` | solo v1-server | — | NOT_IMPLEMENTED en cliente | v1-server AdminV1Routes.kt |

## Errores visibles

`core/network/.../NetworkResultAdapter.kt:60-80`: 4xx muestra `message` + `code`; **404 se sustituye por texto genérico** (se pierden ORDER_NOT_FOUND, FORO_NOT_FOUND); 5xx genérico; 501 CONTROL_RPC_NOT_PROVISIONED legible.

## Hallazgos del panel

| ID | Sev | Problema | Corrección | Responsable | Estado |
|---|---|---|---|---|---|
| CP-01 | P1 | Ningún test ejecuta las rutas Ktor del panel contra el SQL real de las RPC (~90 tests usan Supabase simulado); los journeys `ops/local-exam` no cubren el panel | Journey E2E local del panel sobre el candidato integrado | Control | OPEN |
| CP-02 | P1 | Interdependencia de códigos de error: el panel 29a019f quitó IMPORT_*, OFFICIAL_RULES_INVALID, DEMO_CONFLICT (solo en v1-server); v1-server no tiene 27 códigos del panel que sus propias migraciones lanzan (EXAM_COMMUNITY_REQUIRED, FORO_NOT_FOUND ×20, …) → 500 en cualquiera de las dos ramas por separado | Publicar solo la unión; test de que todo `raise ... message` de migraciones tiene código mapeado | Control + Bot | OPEN |
| CP-03 | P3 (rebajado) | Revisado: el formulario de concesión no tiene campo de caducidad ni promete «sin caducidad»; ese texto solo sale en filas con `valido_hasta` nulo (concesiones antiguas, realmente sin fin) y v1-server devuelve la fecha en toda concesión nueva. Único hueco: el formulario no dice que dura un mes | Texto informativo en el formulario (opcional) | Control | NOT_A_DEFECT (sin cambios) |
| CP-04 | P2 | Topes de economía/reto/demo no editables | Ampliar whitelist de config-v1 con límites auditados | Control (+Bot si cambian límites SQL) | OPEN |
| CP-05 | P2 | 404 de dominio ocultos por texto genérico | `NetworkResultAdapter`: un 404 con sobre canónico (`error` presente) muestra su `message`; sin sobre, texto genérico. Rama `feature/december-v1-control-panel` (worktree E6-Panel), sin commit. Tests: `:core:network:jvmTest` 25/0 (nuevo `aDomain404ShowsTheBackendMessageAndNothingElse`), módulos que tratan `NotFound` 357/0 | Control | FIXED_IN_WORKTREE |
| CP-06 | P2 | 29 rutas Control V1 fuera de OpenAPI | Añadir contrato | Control | OPEN |
| CP-07 | P2 | Regla «oficial empieza con penalización razonada» solo en Ktor del panel (PATCH best-effort) | Mover a SQL | Bot (SQL) | OPEN |
| CP-08 | P2 | Ediciones masivas de flags por pregunta pueden chocar con el límite de 120 mutaciones/min (cuando SEC-V1-02 se arregle) | Endpoint por lote o límite ajustado | Control | OPEN |

## Cómo se certificará

Por capacidad: interfaz → API → Ktor → PostgreSQL (stack local) → respuesta → actualización visible → caso de error → E2E. Solo entonces `VERIFIED_RUNTIME`.
