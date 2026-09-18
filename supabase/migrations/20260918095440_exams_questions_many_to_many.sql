-- M2 · preguntas_examen pasa a M:N: una pregunta puede estar en varios exámenes.
-- C-09 (decidido): primera respuesta y ranking son globales por (usuario, pregunta).
set local lock_timeout = '5s';
lock table public.preguntas_examen in share row exclusive mode;

do $pre$
begin
  if not exists (select 1 from pg_constraint
                  where conrelid = 'public.preguntas_examen'::regclass
                    and conname = 'preguntas_examen_pregunta_unica' and contype = 'u') then
    raise exception 'PREFLIGHT_FAILED: preguntas_examen_pregunta_unica no existe';
  end if;
  if exists (select 1 from pg_depend d
               join pg_constraint c on c.oid = d.refobjid
              where d.refclassid = 'pg_constraint'::regclass
                and c.conname = 'preguntas_examen_pregunta_unica'
                and d.deptype <> 'i') then
    raise exception 'PREFLIGHT_FAILED: preguntas_examen_pregunta_unica tiene dependientes';
  end if;
  if not exists (select 1 from pg_indexes
                  where schemaname = 'public' and tablename = 'preguntas_examen'
                    and indexname = 'idx_preguntas_examen_pregunta') then
    raise exception 'PREFLIGHT_FAILED: falta idx_preguntas_examen_pregunta (debe quedar como único índice por pregunta_id)';
  end if;
end
$pre$;

alter table public.preguntas_examen drop constraint preguntas_examen_pregunta_unica;

comment on table public.preguntas_examen is
  'Vínculo M:N pregunta <-> examen (desde 2026-09-17). Primera respuesta y ranking son globales por (usuario, pregunta): '
  'una pregunta compartida puntúa ranking sólo la primera vez que el usuario la responde en cualquier examen (decisión C-09).';

do $post$
begin
  if exists (select 1 from pg_constraint where conrelid = 'public.preguntas_examen'::regclass
              and conname = 'preguntas_examen_pregunta_unica') then
    raise exception 'POSTCHECK_FAILED: la restricción sigue existiendo';
  end if;
  if not exists (select 1 from pg_indexes where schemaname = 'public' and tablename = 'preguntas_examen'
                  and indexname = 'idx_preguntas_examen_pregunta') then
    raise exception 'POSTCHECK_FAILED: se perdió idx_preguntas_examen_pregunta';
  end if;
end
$post$;
