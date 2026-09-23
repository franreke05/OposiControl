# RLS Policies

## Database Redesign V1 grant closure — 2026-08-21

Read-only verification after the controlled migrations found 87/87 public base
tables with RLS, 0 forced-RLS drift, 109 policies, 0 anonymous mutation grants
and 0 authenticated mutation grants without a matching mutation policy. Existing
policy semantics were preserved; sensitive business writes remain backend-only.

## Current Product Metrics boundary — 2026-08-21

For the applied Product Metrics boundary, authenticated direct `INSERT`/`UPDATE`/`DELETE` is denied on the seven backend-only business tables; own `SELECT` remains only where explicitly intended. Wallet mutation is service-role-only through `admin_grant_tickets`; answer and event mutation are service-role-only through their canonical RPCs. The remaining no-policy tables reported by Supabase advisors are preserved as deny-by-default or legacy review items and were not broadened here.

The dated reconciliation below is historical evidence and does not override this current Product Metrics boundary.

## Live read-only reconciliation — 2026-08-20

The connector was available for this audit. Supabase reported 86 public tables with RLS enabled; 66 had at least one policy and 20 had none: `atribucion_usuario`, `beneficios_plan`, `capacidades_especialidad`, `comunidades_objetivo_usuario`, `creditos_contenido_usuario`, `efectos_servicio`, `estado_competitivo_usuario`, `estado_opositester_usuario`, `ligas_nivel`, `perfiles_usuario_detalle`, `planes_suscripcion`, `popup_usuario_historial`, `preguntas_reto_diario`, `productos_comerciales`, `productos_store`, `promociones`, `reglas_popup_comercial`, `resumen_intento_etiqueta`, `usos_promocion_usuario` and `valor_usuario_resumen`.

This supersedes the infrastructure classification for the current audit only. It does not prove that the 20 tables are unsafe: each requires a table-capability-role decision. No policy was created or modified.

RLS policies outside the applied Product Metrics boundary are catalogued from SQL/docs as `DOCUMENTED` and from code as `CODE_EXPECTED`; their effective live state remains `LIVE_VERIFIED: NO`.

No policy is declared secure merely because a SQL file exists. The deployed schema was inspected read-only for this audit; policy intent and authorization coverage remain unresolved.

The earlier `OPC-20260819-004` infrastructure gap is superseded for this audit by the read-only live counts above. No policy was created, altered or invoked.
