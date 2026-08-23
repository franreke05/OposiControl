-- OPOSICONTROL CANONICAL V1 — VOCABULARY AND LIVE CONSTRAINTS
-- Requires 001_preflight.sql to have passed in the same reviewed snapshot.

begin;

alter table public.examenes
  drop constraint if exists examenes_tipo_examen_check,
  drop constraint if exists examenes_tipo_y_acceso_chk,
  drop constraint if exists examenes_canonical_tipo_examen_check,
  drop constraint if exists examenes_canonical_tipo_y_acceso_check;

alter table public.preguntas
  drop constraint if exists preguntas_tipo_pregunta_check,
  drop constraint if exists preguntas_canonical_tipo_pregunta_check;

alter table public.intentos_examen
  drop constraint if exists intentos_examen_tipo_examen_snapshot_check,
  drop constraint if exists intentos_examen_canonical_tipo_examen_snapshot_check;

alter table public.lotes_importacion_preguntas
  drop constraint if exists lotes_importacion_preguntas_tipo_examen_check,
  drop constraint if exists lotes_importacion_preguntas_canonical_tipo_examen_check;

alter table public.creditos_contenido_usuario
  drop constraint if exists creditos_contenido_usuario_tipo_examen_check,
  drop constraint if exists creditos_contenido_usuario_canonical_tipo_examen_check;

alter table public.reglas_popup_comercial
  drop constraint if exists reglas_popup_comercial_tipo_examen_objetivo_check,
  drop constraint if exists reglas_popup_comercial_canonical_tipo_examen_objetivo_check;

alter table public.preguntas_reto_diario
  drop constraint if exists preguntas_reto_diario_tipo_slot_check,
  drop constraint if exists preguntas_reto_diario_canonical_tipo_slot_check;

alter table public.examenes
  add constraint examenes_canonical_tipo_examen_check
    check (tipo_examen in ('rafaga', 'razonado', 'inmersivo)),
  add constraint examenes_canonical_tipo_y_acceso_check
    check (
      (tipo_examen = 'rafaga' and tipo_acceso = 'gratuito')
      or (tipo_examen = 'razonado' and tipo_acceso in ('gratuito', 'premium'))
      or (tipo_examen = 'inmersivo' and tipo_acceso = 'premium')
    );

alter table public.preguntas
  add constraint preguntas_canonical_tipo_pregunta_check
    check (tipo_pregunta in ('rafaga', 'razonada', 'inmersiva'));

alter table public.intentos_examen
  add constraint intentos_examen_canonical_tipo_examen_snapshot_check
    check (tipo_examen_snapshot in ('rafaga', 'razonado', 'inmersivo'));

alter table public.lotes_importacion_preguntas
  add constraint lotes_importacion_preguntas_canonical_tipo_examen_check
    check (tipo_examen in ('rafaga', 'razonado', 'inmersivo'));

alter table public.creditos_contenido_usuario
  add constraint creditos_contenido_usuario_canonical_tipo_examen_check
    check (tipo_examen in ('rafaga', 'razonado', 'inmersivo'));

alter table public.reglas_popup_comercial
  add constraint reglas_popup_comercial_canonical_tipo_examen_objetivo_check
    check (tipo_examen_objetivo is null or tipo_examen_objetivo in ('rafaga', 'razonado', 'inmersivo'));

alter table public.preguntas_reto_diario
  add constraint preguntas_reto_diario_canonical_tipo_slot_check
    check (tipo_slot in ('inmersiva_demo', 'oposicard', 'rafaga', 'razonada'));

do $$
begin
  if exists (
    select 1 from information_schema.columns
    where table_schema = 'public'
      and table_name = 'capacidades_especialidad'
      and column_name = 'permite_guiados'
  ) then
    if exists (
      select 1 from information_schema.columns
      where table_schema = 'public'
        and table_name = 'capacidades_especialidad'
        and column_name = 'permite_razonados'
    ) then
      raise exception 'CANONICAL_VOCABULARY_AMBIGUOUS_CAPABILITY_COLUMNS';
    end if;
    alter table public.capacidades_especialidad
      rename column permite_guiados to permite_razonados;
  end if;
end;
$$;

commit;
