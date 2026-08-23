# Database Redesign V1 Execution Plan

Status: controlled MAIN package. It follows Product Metrics migration 010.

## Applied order

1. `011_preflight.sql` — read-only preconditions; never changes MAIN.
2. `012_drop_redundant_attempt_index.sql` — removes only the unused duplicate
   `idx_intentos_examen_usuario`; keeps the used `idx_intentos_usuario`.
3. `013_least_privilege_grants.sql` — removes anonymous mutation grants and
   authenticated mutation grants where no authenticated mutation policy exists.
4. `014_revoke_view_mutation_grants.sql` — removes mutation grants from the two
   public derived views while preserving read access.

Each applied migration is versioned in Supabase migration history with a
timestamped name. No previous migration is edited or replayed.

## Findings classification

| Finding | Classification | Decision |
|---|---|---|
| Duplicate attempt index | `MUST_FIX_NOW` | Remove unused exact duplicate only. |
| Anonymous nominal DML grants | `MUST_FIX_NOW` | Revoke; no anonymous mutation policy exists. |
| Authenticated grants without mutation policy | `MUST_FIX_NOW` | Revoke only those grants. |
| Public derived view mutation grants | `MUST_FIX_NOW` | Revoke mutation privileges; views remain read-only. |
| `contenido_desbloqueado_usuario` OR check | `DEFER_RUNTIME_EVIDENCE` | No XOR without a confirmed writer/domain contract. |
| `productos_comerciales.plan_id` index | `NO_CHANGE_NEEDED` | Four rows and observed catalog query does not filter by `plan_id`; advisor finding is informational. |
| Multiple permissive policies | `NO_CHANGE_NEEDED` | Admin/public or owner/staff semantics are explicit; merging risks access changes. |
| Session idempotency | `DEFER_RUNTIME_EVIDENCE` | `sesion_cliente_id` exists, but no active writer contract is verified. |
| Attempt creation idempotency | `DEFER_RUNTIME_EVIDENCE` | No client idempotency key exists in the current contract. |
| Product quotas and reward rules | `DEFER_PRODUCT_DECISION` | Remain outside database redesign. |
| `public.usuario`, OposiBot and legacy RPCs | `LEGACY_COMPAT_KEEP` | No cleanup in this phase. |

## Explicit non-actions

- No table, row, bucket, Storage object, function or RPC is deleted.
- No Product Metrics RPC is replaced.
- No OposiBot code or route is changed.
- No product decision is encoded.
- No provider integration is implemented.

## Data contract V1

| Domain | Source of truth | Derived models | Write boundary | Read boundary | Product blockers |
|---|---|---|---|---|---|
| Identity | `auth.users` | `perfiles_usuario`, `perfiles_usuario_detalle` | Ktor/service-role; own profile policies where proven | own profile; admin | OposiBot legacy identity |
| Authorization | `roles_app` + `roles_usuario` | none | admin/service-role | role predicates | none technical |
| Content | `especialidades`, `comunidades_autonomas`, `etiquetas` | content statistics | Admin/Ktor | public catalog + authorized content | import contract |
| Exams | `examenes` | exam statistics | Admin/Ktor | active/public or authorized | access policy |
| Questions | `preguntas`, options, solutions, joins | question/tag statistics | Admin/Ktor | free/premium policy | content population |
| Import | staging/lots | published content | Ktor/import boundary | admin only | PDF contract |
| Attempts | `intentos_examen` | summaries/statistics | Ktor | own attempts | creation idempotency |
| Answers | `respuestas_intento` via `record_answer` | competitive metrics | `record_answer`/service-role | own answers | none technical |
| Analytics | `eventos_app` via `record_event` | event rollups | `record_event`/service-role | admin/backend | retention/taxonomy |
| Metrics | source answers/events | statistics/rankings | backend rollup | authorized read models | product formulas |
| Ranking | valid first responses | league/ranking projections | backend batch/service | authorized ranking | thresholds |
| Wallet | `transacciones_cartera` ledger | `carteras_usuario` balance | RPC/service-role | own/admin read | provider/reward rules |
| Commercial | plans/products/tariffs | catalogue responses | admin/service-role | public active catalogue | provider contract |
| Subscriptions | `suscripciones` | `estado_premium_usuario` | provider/Ktor | own/admin read | external provider |
| Entitlements | credits/unlocks | access resolver | Ktor/service-role | own/authorized read | immersive quota |
| OposiRed | publications/comments/reactions | moderation projections | own-user policies/admin | public published content | semantic adapter |
| Support | conversations/messages | moderation/audit | own conversation/moderator | own/staff | endpoint usage |
| Storage | `storage.buckets` + `storage.objects` | none | admin/service-role | public or entitlement policy | unused bucket consumers |

## Derived models

| Table | Source | Rebuildable | Update mechanism |
|---|---|---|---|
| `estadisticas_usuario` | attempts + answers | YES | backend rollup required |
| `estadisticas_usuario_pregunta` | answers | YES | backend rollup required |
| `estadisticas_usuario_etiqueta` | answers + tags | YES | backend rollup required |
| `estadisticas_globales_pregunta` | valid answers | YES | backend batch required |
| `estadisticas_globales_etiqueta` | valid answers + tags | YES | backend batch required |
| `resumen_usuario_semanal` | attempts/events | YES | backend weekly rollup required |
| `resumen_intento_etiqueta` | attempt answers + tags | YES | backend attempt rollup required |
| `estado_competitivo_usuario` | first ranked answers | YES | ranking service required |
| `estado_opositester_usuario` | validated reports/events | YES | OposiTester service required |
| `estado_premium_usuario` | subscriptions | YES | subscription trigger/backend projection |
| `valor_usuario_resumen` | purchases/wallet/attribution | YES | commercial rollup required |
| `ranking_liga` | competitive state | YES | ranking batch required |
| `rachas_usuario` | valid activity | YES | activity rollup required |
| `recomendaciones_usuario` | stats/weaknesses | YES | recommendation service required |
| `cola_repaso_usuario` | answers/weaknesses | YES | review queue service required |

No mass triggers are introduced merely to satisfy the audit.
