-- IM1: estado_parseo admite las dos decisiones humanas de la revisión: aprobada y omitida.
-- lotes_importacion_preguntas.estado NO cambia; la publicación atómica termina en importado o revierte.
-- Rollback: restaurar el CHECK anterior sólo si no quedan filas aprobada/omitida.
set local lock_timeout = '5s';
lock table public.preguntas_parseadas_staging in share row exclusive mode;

do $pre$
begin
  if (select pg_get_constraintdef(c.oid) from pg_constraint c
       where c.conrelid = 'public.preguntas_parseadas_staging'::regclass
         and c.conname = 'preguntas_parseadas_staging_estado_parseo_check')
     is distinct from 'CHECK ((estado_parseo = ANY (ARRAY[''ok''::text, ''advertencia''::text, ''error''::text, ''importada''::text])))' then
    raise exception 'PREFLIGHT_FAILED: el CHECK de estado_parseo no es el verificado en la Fase 5';
  end if;
  -- Sin coalesce, la ausencia del constraint devolvería NULL y el IF no fallaría.
  if coalesce((select pg_get_constraintdef(c.oid) from pg_constraint c
                where c.conrelid = 'public.lotes_importacion_preguntas'::regclass
                  and c.conname = 'lotes_importacion_preguntas_estado_check'), '')
     not like '%pendiente%parseado%revisado%importado%fallido%' then
    raise exception 'PREFLIGHT_FAILED: el CHECK de estado del lote no es el verificado';
  end if;
end
$pre$;

alter table public.preguntas_parseadas_staging
  drop constraint preguntas_parseadas_staging_estado_parseo_check,
  add constraint preguntas_parseadas_staging_estado_parseo_check
    check (estado_parseo in ('ok', 'advertencia', 'error', 'importada', 'aprobada', 'omitida'));

comment on constraint preguntas_parseadas_staging_estado_parseo_check on public.preguntas_parseadas_staging is
  'ok/advertencia/error: los escribe el parser. aprobada/omitida: decisión humana en revisión. importada: sólo admin_import_lote_preguntas.';

do $post$
begin
  if not exists (select 1 from pg_constraint c
                  where c.conrelid = 'public.preguntas_parseadas_staging'::regclass
                    and c.conname = 'preguntas_parseadas_staging_estado_parseo_check' and c.convalidated
                    and pg_get_constraintdef(c.oid) like '%aprobada%omitida%') then
    raise exception 'POSTCHECK_FAILED: CHECK de estado_parseo no ampliado';
  end if;
end
$post$;
