-- V1 (Guardar + OposiTeca, E1-V1-SAVED-CONTRACT.md): the user's saved QUESTIONS (tipo = pregunta only; resources and exams are NEEDS_OWNER_DECISION).
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Requires 20261022100500 (private.exam_access_verdict).
--
-- public.recursos_guardados_usuario: one row per (user, tipo, question). Written ONLY by the RPCs below (security definer, service_role execute only); RLS on, no table grants.
--   Deleting the user or the question deletes the saved rows (on delete cascade).
-- public.app_save_item(user, tipo, target): ONE transaction under a per-user advisory lock ('saved:' || user, separate from the wallet lock: saving never waits for a payment):
--   * idempotent: an already saved question returns its ORIGINAL guardado_en, nothing is written (even if access was lost since);
--   * a NEW save needs the question to be active and to belong (preguntas_examen, not anulada) to an exam that is active, published and whose access verdict for the user is not
--     LOCKED (the verdict holds rule 0 = the SPECIALTY GATE, then capability, free / grant / subscription / demo). Missing or not openable = NOT_FOUND (same answer, no probing);
--   * cap of 500 saved questions per user (technical default, NEEDS_OWNER_DECISION) = SAVED_LIMIT_REACHED, checked under the lock so parallel saves cannot pass it together.
-- public.app_unsave_item(user, tipo, target): idempotent delete scoped to the user; returns the number of rows removed (0 or 1).
-- public.app_saved_state(user, tipo, ids[]): which of up to 50 ids the user has saved (initial state of the toggle).
-- public.app_list_saved(user, tipo, after_ts, after_id, limit): keyset page (guardado_en desc, id desc) with the access recomputed NOW: `accesible` and, only when accessible, the
--   question title and the exam it is reachable through (the accessible exam with the latest publication). Content of an exam the user can no longer open is never returned.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate)
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_list_saved(uuid, text, timestamptz, uuid, integer);
-- drop function if exists public.app_saved_state(uuid, text, uuid[]);
-- drop function if exists public.app_unsave_item(uuid, text, uuid);
-- drop function if exists public.app_save_item(uuid, text, uuid);
-- drop table if exists public.recursos_guardados_usuario;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'saved_functions_not_service_role_only:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute')
--                or not has_function_privilege('service_role', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('public.app_save_item(uuid,text,uuid)'), ('public.app_unsave_item(uuid,text,uuid)'), ('public.app_saved_state(uuid,text,uuid[])'),
--                ('public.app_list_saved(uuid,text,timestamptz,uuid,integer)')) f(n)
-- union all
-- select 'saved_table_client_access',
--        (case when has_table_privilege('anon', 'public.recursos_guardados_usuario', 'select') or has_table_privilege('authenticated', 'public.recursos_guardados_usuario', 'select')
--                or has_table_privilege('anon', 'public.recursos_guardados_usuario', 'insert') or has_table_privilege('authenticated', 'public.recursos_guardados_usuario', 'insert')
--                or has_table_privilege('service_role', 'public.recursos_guardados_usuario', 'insert') or has_table_privilege('service_role', 'public.recursos_guardados_usuario', 'update')
--                or has_table_privilege('service_role', 'public.recursos_guardados_usuario', 'delete')
--                or not (select c.relrowsecurity from pg_class c where c.oid = 'public.recursos_guardados_usuario'::regclass) then 1 else 0 end)::bigint
-- union all
-- select 'saved_unique_guard_missing',
--        (case when exists (select 1 from pg_indexes where indexname = 'ux_recursos_guardados_usuario_target') then 0 else 1 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('private.exam_access_verdict(uuid,uuid,timestamptz)') is null then
    raise exception using errcode = 'P0001', message = 'V1_SAVED_ITEMS_PREFLIGHT', hint = 'apply 20261022100500 first';
  end if;
end
$$;

create table if not exists public.recursos_guardados_usuario (
  id uuid primary key default gen_random_uuid(),
  usuario_id uuid not null references auth.users(id) on delete cascade,
  tipo text not null check (tipo = 'pregunta'),
  pregunta_id uuid not null references public.preguntas(id) on delete cascade,
  guardado_en timestamptz not null default clock_timestamp()
);
create unique index if not exists ux_recursos_guardados_usuario_target on public.recursos_guardados_usuario (usuario_id, tipo, pregunta_id);
create index if not exists idx_recursos_guardados_usuario_page on public.recursos_guardados_usuario (usuario_id, guardado_en desc, id desc);
create index if not exists idx_recursos_guardados_usuario_pregunta on public.recursos_guardados_usuario (pregunta_id);
alter table public.recursos_guardados_usuario enable row level security;
revoke all on table public.recursos_guardados_usuario from public, anon, authenticated, service_role;

create or replace function public.app_save_item(p_usuario uuid, p_tipo text, p_target uuid)
returns table(guardado_en timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  v_now timestamptz;
  v_row public.recursos_guardados_usuario%rowtype;
begin
  if p_tipo is distinct from 'pregunta' or p_target is null then
    raise exception using errcode = 'P0001', message = 'SAVED_INVALID';
  end if;
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('saved:' || p_usuario::text, 0));
  v_now := clock_timestamp();  -- DB clock, after the lock
  select s.* into v_row from public.recursos_guardados_usuario s where s.usuario_id = p_usuario and s.tipo = p_tipo and s.pregunta_id = p_target;
  if found then
    return query select v_row.guardado_en;
    return;
  end if;
  if not exists (
    select 1
      from public.preguntas q
      join public.preguntas_examen pe on pe.pregunta_id = q.id and not pe.anulada
      join public.examenes e on e.id = pe.examen_id and e.activo and e.publicado_en <= v_now
     where q.id = p_target and q.activa
       and exists (select 1 from private.exam_access_verdict(p_usuario, e.id, v_now) v where v.state <> 'LOCKED')
  ) then
    raise exception using errcode = 'P0001', message = 'NOT_FOUND';
  end if;
  if (select count(*) from public.recursos_guardados_usuario s where s.usuario_id = p_usuario) >= 500 then
    raise exception using errcode = 'P0001', message = 'SAVED_LIMIT_REACHED';
  end if;
  insert into public.recursos_guardados_usuario (usuario_id, tipo, pregunta_id, guardado_en) values (p_usuario, p_tipo, p_target, v_now)
  returning * into v_row;
  return query select v_row.guardado_en;
end;
$$;

create or replace function public.app_unsave_item(p_usuario uuid, p_tipo text, p_target uuid)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_n integer;
begin
  if p_tipo is distinct from 'pregunta' or p_target is null or p_usuario is null then
    raise exception using errcode = 'P0001', message = 'SAVED_INVALID';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('saved:' || p_usuario::text, 0));
  delete from public.recursos_guardados_usuario s where s.usuario_id = p_usuario and s.tipo = p_tipo and s.pregunta_id = p_target;
  get diagnostics v_n = row_count;
  return v_n;
end;
$$;

create or replace function public.app_saved_state(p_usuario uuid, p_tipo text, p_ids uuid[])
returns table(pregunta_id uuid)
language plpgsql
stable
security definer
set search_path = ''
as $$
#variable_conflict use_column
begin
  if p_tipo is distinct from 'pregunta' or p_usuario is null or p_ids is null or coalesce(array_length(p_ids, 1), 0) > 50 then
    raise exception using errcode = 'P0001', message = 'SAVED_INVALID';
  end if;
  return query select s.pregunta_id from public.recursos_guardados_usuario s
   where s.usuario_id = p_usuario and s.tipo = p_tipo and s.pregunta_id = any (p_ids) order by s.guardado_en desc, s.id desc;
end;
$$;

create or replace function public.app_list_saved(p_usuario uuid, p_tipo text, p_after_ts timestamptz, p_after_id uuid, p_limit integer)
returns table(id uuid, tipo text, target_id uuid, guardado_en timestamptz, accesible boolean, titulo text, examen_id uuid, examen_titulo text)
language plpgsql
stable
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  v_now timestamptz := clock_timestamp();
begin
  if (p_tipo is not null and p_tipo is distinct from 'pregunta') or p_usuario is null or (p_after_ts is null) <> (p_after_id is null) then
    raise exception using errcode = 'P0001', message = 'SAVED_INVALID';
  end if;
  return query
    select s.id, s.tipo, s.pregunta_id, s.guardado_en, (x.examen_id is not null), case when x.examen_id is not null then q.titulo end, x.examen_id, x.titulo
      from public.recursos_guardados_usuario s
      join public.preguntas q on q.id = s.pregunta_id
      left join lateral (
        select e.id as examen_id, e.titulo
          from public.preguntas_examen pe
          join public.examenes e on e.id = pe.examen_id and e.activo and e.publicado_en <= v_now
         where pe.pregunta_id = s.pregunta_id and not pe.anulada and q.activa
           and exists (select 1 from private.exam_access_verdict(p_usuario, e.id, v_now) v where v.state <> 'LOCKED')
         order by e.publicado_en desc, e.id
         limit 1
      ) x on true
     where s.usuario_id = p_usuario and s.tipo = 'pregunta'
       and (p_after_ts is null or (s.guardado_en, s.id) < (p_after_ts, p_after_id))
     order by s.guardado_en desc, s.id desc
     limit least(greatest(coalesce(p_limit, 21), 1), 51);
end;
$$;

revoke all on function public.app_save_item(uuid, text, uuid) from public, anon, authenticated;
revoke all on function public.app_unsave_item(uuid, text, uuid) from public, anon, authenticated;
revoke all on function public.app_saved_state(uuid, text, uuid[]) from public, anon, authenticated;
revoke all on function public.app_list_saved(uuid, text, timestamptz, uuid, integer) from public, anon, authenticated;
grant execute on function public.app_save_item(uuid, text, uuid) to service_role;
grant execute on function public.app_unsave_item(uuid, text, uuid) to service_role;
grant execute on function public.app_saved_state(uuid, text, uuid[]) to service_role;
grant execute on function public.app_list_saved(uuid, text, timestamptz, uuid, integer) to service_role;

commit;
