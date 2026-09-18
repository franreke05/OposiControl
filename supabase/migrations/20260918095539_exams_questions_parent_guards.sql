-- M3 · Guardas en los padres (C-06) y validación del vínculo con cerrojos (C-22).
set local lock_timeout = '5s';
lock table public.preguntas, public.recursos_pregunta, public.preguntas_examen, public.examenes in share row exclusive mode;

do $pre$
begin
  if exists (select 1 from public.preguntas_examen pe
               join public.examenes e on e.id = pe.examen_id
               join public.preguntas p on p.id = pe.pregunta_id
              where e.especialidad_id <> p.especialidad_id
                 or not ((e.tipo_examen = 'rafaga' and p.tipo_pregunta = 'rafaga')
                      or (e.tipo_examen = 'razonado' and p.tipo_pregunta = 'razonada')
                      or (e.tipo_examen = 'inmersivo' and p.tipo_pregunta = 'inmersiva'))) then
    raise exception 'PREFLIGHT_FAILED: hay vínculos incoherentes (las guardas los congelarían)';
  end if;
  if exists (select 1 from public.recursos_pregunta rp join public.preguntas p on p.id = rp.pregunta_id
              where p.tipo_pregunta <> 'inmersiva') then
    raise exception 'PREFLIGHT_FAILED: hay recursos en preguntas no inmersivas';
  end if;
  if (select md5(replace(p.prosrc, E'\r', '')) from pg_proc p
       where p.oid = 'public.validar_pregunta_examen()'::regprocedure)
     is distinct from '8df8ad4489c9fb9a451ec70f72e8959e' then
    raise exception 'PREFLIGHT_FAILED: validar_pregunta_examen cambió desde la lectura de la Fase 3';
  end if;
  -- Comprobación por catálogo (revisión C): el texto de pg_get_triggerdef depende del
  -- search_path de la sesión y haría abortar la migración sin motivo.
  if not exists (select 1 from pg_trigger t
                  where t.tgrelid = 'public.preguntas_examen'::regclass
                    and t.tgname = 'trg_validar_pregunta_examen'
                    and not t.tgisinternal and t.tgenabled = 'O'
                    and t.tgfoid = 'public.validar_pregunta_examen()'::regprocedure
                    and t.tgtype = 23            -- ROW(1)+BEFORE(2)+INSERT(4)+UPDATE(16)
                    and t.tgattr::text = '') then -- sin lista de columnas
    raise exception 'PREFLIGHT_FAILED: trg_validar_pregunta_examen no es el verificado';
  end if;
  if not exists (select 1 from pg_trigger t
                  where t.tgrelid = 'public.preguntas_examen'::regclass
                    and t.tgname = 'trg_validar_pregunta_examen_mismo_tipo'
                    and not t.tgisinternal and t.tgenabled = 'O'
                    and t.tgfoid = 'public.validar_pregunta_examen_mismo_tipo()'::regprocedure
                    and t.tgtype = 23 and t.tgattr::text = '') then
    raise exception 'PREFLIGHT_FAILED: trg_validar_pregunta_examen_mismo_tipo no es el verificado';
  end if;
  if exists (select 1 from pg_trigger where not tgisinternal
              and tgname in ('trg_examenes_bloquear_cambio_tipo', 'trg_preguntas_bloquear_cambio_tipo')) then
    raise exception 'PREFLIGHT_FAILED: las guardas ya existen';
  end if;
end
$pre$;

-- C-22 + códigos de dominio (antes: P0001 con texto en español, sin mapeo ⇒ 500).
create or replace function public.validar_pregunta_examen()
returns trigger
language plpgsql
set search_path = ''
as $function$
declare
  v_tipo_examen text;
  v_especialidad_examen uuid;
  v_tipo_pregunta text;
  v_especialidad_pregunta uuid;
begin
  -- Cerrojos en orden pregunta -> examen (mismo orden que el recuento de total_preguntas
  -- y que admin_link_question_to_exam). NO KEY UPDATE sobre el examen es el cerrojo que
  -- después pide el recuento: no hay subida de cerrojo, y sigue siendo compatible con el
  -- FOR KEY SHARE de las FK de consumidores.
  select p.tipo_pregunta, p.especialidad_id
    into v_tipo_pregunta, v_especialidad_pregunta
    from public.preguntas p
   where p.id = new.pregunta_id
     for share;

  select e.tipo_examen, e.especialidad_id
    into v_tipo_examen, v_especialidad_examen
    from public.examenes e
   where e.id = new.examen_id
     for no key update;

  if v_especialidad_examen <> v_especialidad_pregunta then
    raise exception 'QUESTION_EXAM_SPECIALTY_MISMATCH';
  end if;

  if not (
       (v_tipo_examen = 'rafaga' and v_tipo_pregunta = 'rafaga')
    or (v_tipo_examen = 'razonado' and v_tipo_pregunta = 'razonada')
    or (v_tipo_examen = 'inmersivo' and v_tipo_pregunta = 'inmersiva')
  ) then
    raise exception 'QUESTION_EXAM_TYPE_MISMATCH';
  end if;

  return new;
end;
$function$;

-- `create or replace trigger` (PG >= 14) sustituye la definición en sitio: no pasa por
-- ACCESS EXCLUSIVE ni deja la tabla un instante sin validación (revisión C, opcional b).
create or replace trigger trg_validar_pregunta_examen
  before insert or update of examen_id, pregunta_id on public.preguntas_examen
  for each row execute function public.validar_pregunta_examen();

create or replace trigger trg_validar_pregunta_examen_mismo_tipo
  before insert or update of examen_id, pregunta_id on public.preguntas_examen
  for each row execute function public.validar_pregunta_examen_mismo_tipo();

create function private.tg_examenes_bloquear_cambio_tipo()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if exists (select 1 from public.preguntas_examen pe where pe.examen_id = new.id) then
    raise exception 'EXAM_TYPE_LOCKED_BY_QUESTIONS';
  end if;
  return new;
end;
$$;

create function private.tg_preguntas_bloquear_cambio_tipo()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if exists (select 1 from public.preguntas_examen pe where pe.pregunta_id = new.id) then
    raise exception 'QUESTION_TYPE_LOCKED_BY_EXAMS';
  end if;
  if old.tipo_pregunta is distinct from new.tipo_pregunta
     and old.tipo_pregunta = 'inmersiva'
     and exists (select 1 from public.recursos_pregunta rp where rp.pregunta_id = new.id) then
    raise exception 'QUESTION_TYPE_LOCKED_BY_RESOURCES';
  end if;
  return new;
end;
$$;

revoke all on function private.tg_examenes_bloquear_cambio_tipo() from public, anon, authenticated;
revoke all on function private.tg_preguntas_bloquear_cambio_tipo() from public, anon, authenticated;

create trigger trg_examenes_bloquear_cambio_tipo
  before update of tipo_examen, especialidad_id on public.examenes
  for each row
  when (old.tipo_examen is distinct from new.tipo_examen
        or old.especialidad_id is distinct from new.especialidad_id)
  execute function private.tg_examenes_bloquear_cambio_tipo();

create trigger trg_preguntas_bloquear_cambio_tipo
  before update of tipo_pregunta, especialidad_id on public.preguntas
  for each row
  when (old.tipo_pregunta is distinct from new.tipo_pregunta
        or old.especialidad_id is distinct from new.especialidad_id)
  execute function private.tg_preguntas_bloquear_cambio_tipo();

do $post$
begin
  if (select count(*) from pg_trigger where not tgisinternal and tgname in
        ('trg_examenes_bloquear_cambio_tipo', 'trg_preguntas_bloquear_cambio_tipo')) <> 2 then
    raise exception 'POSTCHECK_FAILED: faltan guardas';
  end if;
  if (select count(*) from pg_trigger t where t.tgrelid = 'public.preguntas_examen'::regclass
        and t.tgname in ('trg_validar_pregunta_examen', 'trg_validar_pregunta_examen_mismo_tipo')
        and pg_get_triggerdef(t.oid) like '%BEFORE INSERT OR UPDATE OF examen_id, pregunta_id ON public.preguntas_examen%') <> 2 then
    raise exception 'POSTCHECK_FAILED: los triggers de validación no quedaron acotados a las claves';
  end if;
  if (select p.proacl::text from pg_proc p where p.oid = 'public.validar_pregunta_examen()'::regprocedure)
     is distinct from '{=X/postgres,postgres=X/postgres,anon=X/postgres,authenticated=X/postgres,service_role=X/postgres}' then
    raise exception 'POSTCHECK_FAILED: cambió el ACL de validar_pregunta_examen';
  end if;
end
$post$;
