-- Q4 · Un examen publicado y activo no puede quedarse sin preguntas activas (archivar, desvincular o borrar la última).
-- Se engancha al único escritor de total_preguntas (el recuento de M1): cubre todos los caminos en un sitio.
set local lock_timeout = '5s';
lock table public.examenes in share row exclusive mode;

do $pre$
begin
  if to_regprocedure('private.recalcular_total_preguntas_examen(uuid)') is null
     or not exists (select 1 from pg_trigger where tgrelid = 'public.examenes'::regclass and not tgisinternal
                     and tgname = 'trg_examenes_total_preguntas_guard') then
    raise exception 'PREFLIGHT_FAILED: requiere M1 (exams_total_preguntas_maintained)';
  end if;
  if exists (select 1 from pg_trigger where tgrelid = 'public.examenes'::regclass and not tgisinternal
              and tgname = 'trg_examenes_total_preguntas_publicado') then
    raise exception 'PREFLIGHT_FAILED: la guarda ya existe';
  end if;
end
$pre$;

create function private.tg_examenes_publicado_no_vacio()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  raise exception 'EXAM_PUBLISHED_WOULD_BE_EMPTY';
end;
$$;

revoke all on function private.tg_examenes_publicado_no_vacio() from public, anon, authenticated;

-- BEFORE triggers en orden alfabético: '..._publicado' corre DESPUÉS de '..._guard' (M1) y ve el NEW ya saneado
-- (una escritura externa de total_preguntas la revierte la guarda y aquí ya no cumple el WHEN).
-- Borrar el examen no dispara nada: el recuento no encuentra la fila y no actualiza (Q-P4-20).
create trigger trg_examenes_total_preguntas_publicado
  before update of total_preguntas on public.examenes
  for each row
  when (old.total_preguntas > 0 and new.total_preguntas = 0 and new.publicado_en is not null and new.activo)
  execute function private.tg_examenes_publicado_no_vacio();

do $post$
begin
  if not exists (select 1 from pg_trigger where tgrelid = 'public.examenes'::regclass and not tgisinternal
                  and tgname = 'trg_examenes_total_preguntas_publicado') then
    raise exception 'POSTCHECK_FAILED: falta la guarda';
  end if;
  if has_function_privilege('anon', 'private.tg_examenes_publicado_no_vacio()', 'EXECUTE')
     or has_function_privilege('authenticated', 'private.tg_examenes_publicado_no_vacio()', 'EXECUTE') then
    raise exception 'POSTCHECK_FAILED: ACL abierta';
  end if;
end
$post$;
