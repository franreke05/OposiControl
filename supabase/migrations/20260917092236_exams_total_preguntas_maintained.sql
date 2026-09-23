-- M1 · total_preguntas mantenido por la base (PW-3 con los cambios 1-4 de la revisión C).
-- Definición (OQ-7): nº de vínculos a preguntas ACTIVAS; la misma que sirve la app.
set local lock_timeout = '5s';
lock table public.preguntas, public.preguntas_examen, public.examenes in share row exclusive mode;

do $pre$
begin
  if (select pg_get_constraintdef(c.oid, true) from pg_constraint c
       where c.conrelid = 'public.examenes'::regclass and c.conname = 'examenes_total_preguntas_check')
     is distinct from 'CHECK (total_preguntas >= 0)' then
    raise exception 'PREFLIGHT_FAILED: falta PW-2 (CHECK total_preguntas >= 0)';
  end if;
  if exists (select 1 from pg_trigger where not tgisinternal and tgname in (
      'trg_examenes_total_preguntas_insert', 'trg_examenes_total_preguntas_guard',
      'trg_preguntas_examen_total_preguntas', 'trg_preguntas_activa_total_preguntas')) then
    raise exception 'PREFLIGHT_FAILED: los triggers de total_preguntas ya existen';
  end if;
  if exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
              where n.nspname = 'private' and p.proname in (
                'recalcular_total_preguntas_examen', 'tg_preguntas_examen_total_preguntas',
                'tg_preguntas_activa_total_preguntas', 'tg_examenes_total_preguntas_insert',
                'tg_examenes_total_preguntas_guard')) then
    raise exception 'PREFLIGHT_FAILED: las funciones de total_preguntas ya existen';
  end if;
  if not exists (select 1 from pg_namespace where nspname = 'private') then
    raise exception 'PREFLIGHT_FAILED: no existe el esquema private';
  end if;
end
$pre$;

create function private.recalcular_total_preguntas_examen(p_examen_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_total integer;
  v_prev text;
begin
  if p_examen_id is null then
    return;
  end if;
  -- C-03: NO KEY UPDATE es compatible con el FOR KEY SHARE de las FK (vínculos
  -- concurrentes e intentos de consumidores no se bloquean ni forman 40P01).
  perform 1 from public.examenes e where e.id = p_examen_id for no key update;
  if not found then
    return; -- inexistente o borrado en esta misma transacción (cascada)
  end if;
  -- Sentencia posterior al cerrojo: ve los vínculos confirmados por quien lo tenía.
  select count(*)::integer
    into v_total
    from public.preguntas_examen pe
    join public.preguntas p on p.id = pe.pregunta_id
   where pe.examen_id = p_examen_id
     and p.activa;
  -- C-05: sólo con esta bandera transaccional deja pasar la guarda la escritura.
  v_prev := current_setting('oposicontrol.total_preguntas_recount', true);
  perform set_config('oposicontrol.total_preguntas_recount', 'on', true);
  update public.examenes e
     set total_preguntas = v_total
   where e.id = p_examen_id
     and e.total_preguntas is distinct from v_total;
  perform set_config('oposicontrol.total_preguntas_recount', coalesce(nullif(v_prev, ''), 'off'), true);
end;
$$;

create function private.tg_preguntas_examen_total_preguntas()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if tg_op in ('INSERT', 'UPDATE') then
    -- C-04: cerrojo pregunta -> examen. Serializa con el archivado de la pregunta
    -- (que toma NO KEY UPDATE sobre ella) y cierra el write-skew.
    perform 1 from public.preguntas p where p.id = new.pregunta_id for share;
  end if;
  if tg_op = 'DELETE' then
    perform private.recalcular_total_preguntas_examen(old.examen_id);
  elsif tg_op = 'UPDATE' and old.examen_id is distinct from new.examen_id then
    perform private.recalcular_total_preguntas_examen(least(old.examen_id, new.examen_id));
    perform private.recalcular_total_preguntas_examen(greatest(old.examen_id, new.examen_id));
  else
    perform private.recalcular_total_preguntas_examen(new.examen_id);
  end if;
  return null;
end;
$$;

create function private.tg_preguntas_activa_total_preguntas()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_examen_id uuid;
begin
  -- C-04: orden determinista de cerrojos entre archivados concurrentes.
  for v_examen_id in
    select pe.examen_id
      from public.preguntas_examen pe
     where pe.pregunta_id = new.id
     order by pe.examen_id
  loop
    perform private.recalcular_total_preguntas_examen(v_examen_id);
  end loop;
  return null;
end;
$$;

create function private.tg_examenes_total_preguntas_insert()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.total_preguntas := 0; -- un examen recién insertado no puede tener vínculos (FK)
  return new;
end;
$$;

create function private.tg_examenes_total_preguntas_guard()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  -- C-05: la columna no es escribible desde fuera del recuento.
  if new.total_preguntas is distinct from old.total_preguntas
     and coalesce(current_setting('oposicontrol.total_preguntas_recount', true), 'off') <> 'on' then
    new.total_preguntas := old.total_preguntas;
  end if;
  return new;
end;
$$;

revoke all on function private.recalcular_total_preguntas_examen(uuid) from public, anon, authenticated;
revoke all on function private.tg_preguntas_examen_total_preguntas() from public, anon, authenticated;
revoke all on function private.tg_preguntas_activa_total_preguntas() from public, anon, authenticated;
revoke all on function private.tg_examenes_total_preguntas_insert() from public, anon, authenticated;
revoke all on function private.tg_examenes_total_preguntas_guard() from public, anon, authenticated;

alter table public.examenes alter column total_preguntas set default 0;

create trigger trg_examenes_total_preguntas_insert
  before insert on public.examenes
  for each row execute function private.tg_examenes_total_preguntas_insert();

create trigger trg_examenes_total_preguntas_guard
  before update of total_preguntas on public.examenes
  for each row execute function private.tg_examenes_total_preguntas_guard();

create trigger trg_preguntas_examen_total_preguntas
  after insert or delete or update of examen_id, pregunta_id on public.preguntas_examen
  for each row execute function private.tg_preguntas_examen_total_preguntas();

create trigger trg_preguntas_activa_total_preguntas
  after update of activa on public.preguntas
  for each row
  when (old.activa is distinct from new.activa)
  execute function private.tg_preguntas_activa_total_preguntas();

comment on column public.examenes.total_preguntas is
  'Nº de preguntas ACTIVAS vinculadas. Lo mantiene la base (M1: triggers trg_*_total_preguntas); no se escribe desde la API.';

-- Backfill (Q24 Fase 0: 7 filas, 50 y 6x1 -> 0). La bandera abre la guarda sólo aquí.
select set_config('oposicontrol.total_preguntas_recount', 'on', true);

update public.examenes e
   set total_preguntas = c.total
  from (
    select e2.id,
           (select count(*)::integer
              from public.preguntas_examen pe
              join public.preguntas p on p.id = pe.pregunta_id
             where pe.examen_id = e2.id
               and p.activa) as total
      from public.examenes e2
  ) c
 where c.id = e.id
   and e.total_preguntas is distinct from c.total;

select set_config('oposicontrol.total_preguntas_recount', 'off', true);

do $post$
begin
  if exists (
    select 1 from public.examenes e
     where e.total_preguntas <> (select count(*) from public.preguntas_examen pe
                                  join public.preguntas p on p.id = pe.pregunta_id
                                 where pe.examen_id = e.id and p.activa)) then
    raise exception 'POSTCHECK_FAILED: total_preguntas no reconcilia';
  end if;
  if (select count(*) from pg_trigger where not tgisinternal and tgname in (
        'trg_examenes_total_preguntas_insert', 'trg_examenes_total_preguntas_guard',
        'trg_preguntas_examen_total_preguntas', 'trg_preguntas_activa_total_preguntas')) <> 4 then
    raise exception 'POSTCHECK_FAILED: faltan triggers';
  end if;
  if (select column_default from information_schema.columns
       where table_schema = 'public' and table_name = 'examenes' and column_name = 'total_preguntas') is distinct from '0' then
    raise exception 'POSTCHECK_FAILED: default distinto de 0';
  end if;
  if exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
              where n.nspname = 'private' and p.proname like '%total_preguntas%'
                and (has_function_privilege('anon', p.oid, 'EXECUTE') or has_function_privilege('authenticated', p.oid, 'EXECUTE'))) then
    raise exception 'POSTCHECK_FAILED: función ejecutable por anon/authenticated';
  end if;
end
$post$;
