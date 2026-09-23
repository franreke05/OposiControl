-- OPOSICONTROL CANONICAL V1 V2 — VOCABULARY ONLY
-- Decision: guiado -> razonado; guiada -> razonada.
-- RAZONADO_ACCESS_POLICY = RAZONADOS_PLAN_CONFIRMED.
-- Plan entitlement is defined separately in 007_plan_entitlements.sql.

begin;

alter table public.examenes
  drop constraint if exists examenes_tipo_examen_check,
  drop constraint if exists examenes_tipo_y_acceso_chk,
  drop constraint if exists examenes_canonical_tipo_examen_check,
  drop constraint if exists examenes_canonical_tipo_y_acceso_check,
  drop constraint if exists examenes_canonical_v2_tipo_examen_check,
  drop constraint if exists examenes_canonical_v2_rafaga_access_check,
  drop constraint if exists examenes_canonical_v2_inmersivo_access_check;

alter table public.preguntas
  drop constraint if exists preguntas_tipo_pregunta_check,
  drop constraint if exists preguntas_canonical_tipo_pregunta_check,
  drop constraint if exists preguntas_canonical_v2_tipo_pregunta_check;

alter table public.intentos_examen
  drop constraint if exists intentos_examen_tipo_examen_snapshot_check,
  drop constraint if exists intentos_examen_canonical_tipo_examen_snapshot_check,
  drop constraint if exists intentos_examen_canonical_v2_tipo_examen_snapshot_check;

alter table public.lotes_importacion_preguntas
  drop constraint if exists lotes_importacion_preguntas_tipo_examen_check,
  drop constraint if exists lotes_importacion_preguntas_canonical_tipo_examen_check,
  drop constraint if exists lotes_importacion_preguntas_canonical_v2_tipo_examen_check;

alter table public.creditos_contenido_usuario
  drop constraint if exists creditos_contenido_usuario_tipo_examen_check,
  drop constraint if exists creditos_contenido_usuario_canonical_tipo_examen_check,
  drop constraint if exists creditos_contenido_usuario_canonical_v2_tipo_examen_check;

alter table public.reglas_popup_comercial
  drop constraint if exists reglas_popup_comercial_tipo_examen_objetivo_check,
  drop constraint if exists reglas_popup_comercial_canonical_tipo_examen_objetivo_check,
  drop constraint if exists reglas_popup_comercial_canonical_v2_tipo_examen_objetivo_check;

alter table public.preguntas_reto_diario
  drop constraint if exists preguntas_reto_diario_tipo_slot_check,
  drop constraint if exists preguntas_reto_diario_canonical_tipo_slot_check,
  drop constraint if exists preguntas_reto_diario_canonical_v2_tipo_slot_check;

alter table public.examenes
  add constraint examenes_canonical_v2_tipo_examen_check
    check (tipo_examen in ('rafaga', 'razonado', 'inmersivo'));

alter table public.preguntas
  add constraint preguntas_canonical_v2_tipo_pregunta_check
    check (tipo_pregunta in ('rafaga', 'razonada', 'inmersiva'));

alter table public.intentos_examen
  add constraint intentos_examen_canonical_v2_tipo_examen_snapshot_check
    check (tipo_examen_snapshot in ('rafaga', 'razonado', 'inmersivo'));

alter table public.lotes_importacion_preguntas
  add constraint lotes_importacion_preguntas_canonical_v2_tipo_examen_check
    check (tipo_examen in ('rafaga', 'razonado', 'inmersivo'));

alter table public.creditos_contenido_usuario
  add constraint creditos_contenido_usuario_canonical_v2_tipo_examen_check
    check (tipo_examen in ('rafaga', 'razonado', 'inmersivo'));

alter table public.reglas_popup_comercial
  add constraint reglas_popup_comercial_canonical_v2_tipo_examen_objetivo_check
    check (tipo_examen_objetivo is null or tipo_examen_objetivo in ('rafaga', 'razonado', 'inmersivo'));

alter table public.preguntas_reto_diario
  add constraint preguntas_reto_diario_canonical_v2_tipo_slot_check
    check (tipo_slot in ('inmersiva_demo', 'oposicard', 'rafaga', 'razonada'));

-- tipo_acceso remains a content/publication classification only. It is not
-- used to encode plan entitlement; 007_plan_entitlements.sql owns that model.
-- The capability-column rename is executed with an explicit guard in
-- 007_plan_entitlements.sql so the complete 001-007 package owns the
-- canonical postcondition without creating a second cleanup path.

commit;
