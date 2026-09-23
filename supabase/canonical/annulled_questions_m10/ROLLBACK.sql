-- ROLLBACK de M10 (20260919122139_annulled_questions_in_exams.sql).
--
-- Vuelve a la semántica anterior: ninguna pregunta se puede marcar como anulada en un examen.
-- Restaura, en UNA transacción, las definiciones EXACTAS guardadas por M10 en
-- private.m10_previous_definitions_20260919 (admin_link_question_to_exam de 3 args,
-- admin_create_question de 12 args, admin_import_lote_preguntas, record_answer y
-- tg_staging_inmutable_tras_importar) y elimina las columnas nuevas.
--
-- ABORTA (sin tocar nada) si existe alguna relación o fila de staging anulada: quitar la columna
-- perdería la anulación y devolvería esa pregunta al cómputo de la nota. Decide a mano (desanular
-- o desvincular) y repite. No borra private.m10_previous_definitions_20260919; una reaplicación
-- posterior de M10 REFRESCA ese respaldo (on conflict do update), así que no queda desfasado.
-- Ejecutar con execute_sql / psql (no con apply_migration: el script lleva su propio begin/commit).

begin;

do $$
begin
  if exists (select 1 from public.preguntas_examen where anulada)
     or exists (select 1 from public.preguntas_parseadas_staging where anulada) then
    raise exception 'ROLLBACK_M10_ABORT: hay preguntas o filas de staging anuladas; quitar las columnas perdería esa información';
  end if;
  if (select count(*) from private.m10_previous_definitions_20260919) < 5 then
    raise exception 'ROLLBACK_M10_ABORT: faltan definiciones previas en private.m10_previous_definitions_20260919';
  end if;
end;
$$;

-- Firmas nuevas fuera (para que las antiguas no queden como sobrecarga).
drop function if exists public.admin_create_question(uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid, boolean, text);
drop function if exists public.admin_link_question_to_exam(uuid, uuid, integer, boolean, text);

-- Definiciones anteriores, tal cual estaban.
do $$
declare
  r record;
begin
  for r in
    select objeto, definicion
      from private.m10_previous_definitions_20260919
     order by case objeto
                when 'admin_link_question_to_exam(uuid,uuid,integer)' then 1
                when 'admin_create_question(uuid,text,text,jsonb,text,text,text,boolean,uuid,integer,uuid,uuid)' then 2
                else 3 end
  loop
    execute r.definicion;
  end loop;
end;
$$;

revoke all on function public.admin_link_question_to_exam(uuid, uuid, integer) from public, anon, authenticated;
grant execute on function public.admin_link_question_to_exam(uuid, uuid, integer) to service_role;
revoke all on function public.admin_create_question(uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid) from public, anon, authenticated;
grant execute on function public.admin_create_question(uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid) to service_role;

alter table public.preguntas_parseadas_staging
  drop constraint preguntas_parseadas_staging_anulada_motivo_chk,
  drop column motivo_anulacion,
  drop column anulada;
alter table public.preguntas_examen
  drop constraint preguntas_examen_anulada_motivo_chk,
  drop column motivo_anulacion,
  drop column anulada;

commit;
