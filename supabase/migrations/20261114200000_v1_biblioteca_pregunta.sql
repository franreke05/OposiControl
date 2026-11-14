-- V1 LIBRARY: SAVED QUESTION DETAIL (owner plan 2026-11-14 "Biblioteca: ver la pregunta guardada ahi mismo", workstream F30, contract amendment FA50). Forward-only; local stack and
-- PG17 gate only, NOT applied to MAIN. Requires 20261022100800 (saved items), 20261022100000 / 20261022102100 (revision content and solution builders), 20261022101100 (reveal
-- limiter) and 20261022101300 (reserves).
--
-- New: public.app_saved_question_detail(usuario, pregunta) -> jsonb. ONLY when the user saved the question AND can open one of its exams (same rule as the OposiTeca list: an
-- active, published exam whose access verdict is FREE / GRANTED / SUBSCRIPTION; the specialty gate is rule 0 of that verdict). It returns the LIVE content built by the function
-- that freezes attempt revisions (private.app_revision_pregunta_contenido), plus the chosen exam (id, title, type), the question position in it and whether it is annulled
-- there. The SOLUTION (private.app_revision_pregunta_solucion: correct option + explanation) is returned ONLY when the user already resolved the question
-- (private.saved_question_resolved: a FINISHED attempt of theirs contains it; an answer in an OPEN attempt never counts because it can still be changed, B1-bis);
-- otherwise 'solucion' is JSON null (still OK):
-- saving a question in an open attempt must never reveal its answer before it is answered (review Control 2026-11-14, B1).
-- Exam choice: a link where the question is NOT annulled first, then the latest publication (an annulled link is used only when no other accessible link exists: the client shows
-- it flagged as annulled, without the correct answer, like the review). A RESERVE link (pe.es_reserva) counts only when the user resolved the question (an unused reserve is not
-- shown). Not saved / no access / inactive question = NOT_FOUND. It never touches the daily reveal limiter (no quota).
-- Changed: public.app_list_saved returns tipo_examen of the chosen exam and uses the SAME exam choice (so an item is accesible exactly when its detail opens: a question annulled
-- in every accessible exam is now accesible and its detail says anulada). The return type changes, so the function is dropped and recreated (an older Ktor image ignores the
-- extra column).
-- Changed: public.app_save_item (B1): a NEW save needs the question to be REVEALED to the user (private.saved_question_revealed: resolved as above, answered by them in
-- any attempt, or served to them by the
-- daily limiter (preguntas_reveladas_usuario), or part of an attempt of theirs that the limiter does not count (demo / GRANTED / DEMO access, or a plan without daily limit));
-- the D2-07 id list of an open limited attempt is no longer enough, so saving cannot skip private.plan_daily_limit. Unused reserves are excluded like in the detail. An already
-- saved question still answers its original guardado_en (naturally idempotent, unchanged).
-- DEPLOY ORDER: this migration BEFORE the Ktor image that calls app_saved_question_detail (an older image never calls it).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate). Restores the list and save bodies of 20261022100800 verbatim and drops the detail function and its helpers.
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_saved_question_detail(uuid, uuid);
-- create or replace function public.app_save_item(p_usuario uuid, p_tipo text, p_target uuid)
-- returns table(guardado_en timestamptz)
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- #variable_conflict use_column
-- declare
--   v_now timestamptz;
--   v_row public.recursos_guardados_usuario%rowtype;
-- begin
--   if p_tipo is distinct from 'pregunta' or p_target is null then
--     raise exception using errcode = 'P0001', message = 'SAVED_INVALID';
--   end if;
--   if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
--     raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
--   end if;
--   perform pg_advisory_xact_lock(hashtextextended('saved:' || p_usuario::text, 0));
--   v_now := clock_timestamp();  -- DB clock, after the lock
--   select s.* into v_row from public.recursos_guardados_usuario s where s.usuario_id = p_usuario and s.tipo = p_tipo and s.pregunta_id = p_target;
--   if found then
--     return query select v_row.guardado_en;
--     return;
--   end if;
--   if not exists (
--     select 1
--       from public.preguntas q
--       join public.preguntas_examen pe on pe.pregunta_id = q.id and not pe.anulada
--       join public.examenes e on e.id = pe.examen_id and e.activo and e.publicado_en <= v_now
--      where q.id = p_target and q.activa
--        and exists (select 1 from private.exam_access_verdict(p_usuario, e.id, v_now) v where v.state in ('FREE', 'GRANTED', 'SUBSCRIPTION'))
--   ) then
--     raise exception using errcode = 'P0001', message = 'NOT_FOUND';
--   end if;
--   if (select count(*) from public.recursos_guardados_usuario s where s.usuario_id = p_usuario) >= 500 then
--     raise exception using errcode = 'P0001', message = 'SAVED_LIMIT_REACHED';
--   end if;
--   insert into public.recursos_guardados_usuario (usuario_id, tipo, pregunta_id, guardado_en) values (p_usuario, p_tipo, p_target, v_now)
--   returning * into v_row;
--   return query select v_row.guardado_en;
-- end;
-- $$;
-- revoke all on function public.app_save_item(uuid, text, uuid) from public, anon, authenticated;
-- grant execute on function public.app_save_item(uuid, text, uuid) to service_role;
-- drop function if exists private.saved_question_revealed(uuid, uuid);
-- drop function if exists private.saved_question_resolved(uuid, uuid);
-- drop function if exists public.app_list_saved(uuid, text, timestamptz, uuid, integer);
-- create or replace function public.app_list_saved(p_usuario uuid, p_tipo text, p_after_ts timestamptz, p_after_id uuid, p_limit integer)
-- returns table(id uuid, tipo text, target_id uuid, guardado_en timestamptz, accesible boolean, titulo text, examen_id uuid, examen_titulo text)
-- language plpgsql
-- stable
-- security definer
-- set search_path = ''
-- as $$
-- #variable_conflict use_column
-- declare
--   v_now timestamptz := clock_timestamp();
-- begin
--   if (p_tipo is not null and p_tipo is distinct from 'pregunta') or p_usuario is null or (p_after_ts is null) <> (p_after_id is null) then
--     raise exception using errcode = 'P0001', message = 'SAVED_INVALID';
--   end if;
--   return query
--     select s.id, s.tipo, s.pregunta_id, s.guardado_en, (x.examen_id is not null), case when x.examen_id is not null then q.titulo end, x.examen_id, x.titulo
--       from public.recursos_guardados_usuario s
--       join public.preguntas q on q.id = s.pregunta_id
--       left join lateral (
--         select e.id as examen_id, e.titulo
--           from public.preguntas_examen pe
--           join public.examenes e on e.id = pe.examen_id and e.activo and e.publicado_en <= v_now
--          where pe.pregunta_id = s.pregunta_id and not pe.anulada and q.activa
--            and exists (select 1 from private.exam_access_verdict(p_usuario, e.id, v_now) v where v.state in ('FREE', 'GRANTED', 'SUBSCRIPTION'))
--          order by e.publicado_en desc, e.id
--          limit 1
--       ) x on true
--      where s.usuario_id = p_usuario and s.tipo = 'pregunta'
--        and (p_after_ts is null or (s.guardado_en, s.id) < (p_after_ts, p_after_id))
--      order by s.guardado_en desc, s.id desc
--      limit least(greatest(coalesce(p_limit, 21), 1), 51);
-- end;
-- $$;
-- revoke all on function public.app_list_saved(uuid, text, timestamptz, uuid, integer) from public, anon, authenticated;
-- grant execute on function public.app_list_saved(uuid, text, timestamptz, uuid, integer) to service_role;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'biblioteca_functions_not_service_role_only:' || f.n as check_name,
--        (case when to_regprocedure(f.n) is null or has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute')
--                or not has_function_privilege('service_role', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('public.app_saved_question_detail(uuid,uuid)'), ('public.app_list_saved(uuid,text,timestamptz,uuid,integer)'), ('public.app_save_item(uuid,text,uuid)')) f(n)
-- union all
-- select 'biblioteca_helpers_missing_or_client_callable:' || f.n,
--        (case when to_regprocedure(f.n) is null or has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute') then 1 else 0 end)::bigint
--   from (values ('private.saved_question_resolved(uuid,uuid)'), ('private.saved_question_revealed(uuid,uuid)')) f(n)
-- union all
-- select 'biblioteca_save_without_reveal_rule',
--        (case when exists (select 1 from pg_proc p where p.oid = to_regprocedure('public.app_save_item(uuid,text,uuid)') and p.prosrc like '%private.saved_question_revealed%') then 0 else 1 end)::bigint
-- union all
-- select 'biblioteca_list_without_tipo_examen',
--        (case when exists (select 1 from pg_proc p where p.oid = to_regprocedure('public.app_list_saved(uuid,text,timestamptz,uuid,integer)') and 'tipo_examen' = any (p.proargnames)) then 0 else 1 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regclass('public.recursos_guardados_usuario') is null or to_regprocedure('public.app_list_saved(uuid,text,timestamptz,uuid,integer)') is null
     or to_regprocedure('private.exam_access_verdict(uuid,uuid,timestamptz)') is null
     or to_regprocedure('private.app_revision_pregunta_contenido(uuid)') is null or to_regprocedure('private.app_revision_pregunta_solucion(uuid)') is null
     or to_regclass('public.preguntas_reveladas_usuario') is null or to_regprocedure('private.plan_daily_limit(uuid,timestamptz)') is null
     or not exists (select 1 from information_schema.columns c where c.table_schema = 'public' and c.table_name = 'preguntas_examen' and c.column_name = 'es_reserva') then
    raise exception using errcode = 'P0001', message = 'V1_BIBLIOTECA_PREGUNTA_PREFLIGHT', hint = 'apply 20261022100000, 20261022100800, 20261022101100, 20261022101300 and 20261022102100 first';
  end if;
end
$$;

-- ---- 0. helpers (B1). RESOLVED = a FINISHED attempt of theirs contains it (B1-bis: open attempts never count): only then is the solution shown.
-- REVEALED = resolved, answered by them in any attempt, served to them (limiter row), or in an attempt of theirs the limiter does not count: only then can it be saved.
create or replace function private.saved_question_resolved(p_usuario uuid, p_pregunta uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  -- B1-bis: ONLY a finished attempt counts. An answer inside an OPEN attempt can still be changed (app_record_attempt_answer allows it in every mode,
  -- practice and demo included), so it never unlocks the solution: otherwise answer anything, read the correct option here, change it, finish.
  select exists (
           select 1 from public.preguntas_intento pi
             join public.intentos_examen ie on ie.id = pi.intento_id and ie.usuario_id = p_usuario and ie.estado = 'finalizado'
            where pi.pregunta_id = p_pregunta);
$$;
revoke all on function private.saved_question_resolved(uuid, uuid) from public, anon, authenticated;

create or replace function private.saved_question_revealed(p_usuario uuid, p_pregunta uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select private.saved_question_resolved(p_usuario, p_pregunta)
      or exists (  -- answered in any attempt of theirs: it was served to them (revealed), even if the solution stays hidden until the attempt ends
           select 1 from public.respuestas_intento ri
             join public.intentos_examen ie on ie.id = ri.intento_id and ie.usuario_id = p_usuario
            where ri.pregunta_id = p_pregunta and ri.opcion_seleccionada_id is not null)
      or exists (select 1 from public.preguntas_reveladas_usuario r where r.usuario_id = p_usuario and r.pregunta_id = p_pregunta)
      or exists (
           select 1 from public.preguntas_intento pi
             join public.intentos_examen ie on ie.id = pi.intento_id and ie.usuario_id = p_usuario
            where pi.pregunta_id = p_pregunta
              and (ie.es_demo or ie.acceso_estado in ('GRANTED', 'DEMO') or private.plan_daily_limit(p_usuario, clock_timestamp()) is null));
$$;
revoke all on function private.saved_question_revealed(uuid, uuid) from public, anon, authenticated;

-- ---- 1. the list: + tipo_examen, and the exam choice shared with the detail (non-annulled link first, then the latest publication)
drop function if exists public.app_list_saved(uuid, text, timestamptz, uuid, integer);
create or replace function public.app_list_saved(p_usuario uuid, p_tipo text, p_after_ts timestamptz, p_after_id uuid, p_limit integer)
returns table(id uuid, tipo text, target_id uuid, guardado_en timestamptz, accesible boolean, titulo text, examen_id uuid, examen_titulo text, tipo_examen text)
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
    select s.id, s.tipo, s.pregunta_id, s.guardado_en, (x.examen_id is not null), case when x.examen_id is not null then q.titulo end, x.examen_id, x.titulo, x.tipo_examen
      from public.recursos_guardados_usuario s
      join public.preguntas q on q.id = s.pregunta_id
      left join lateral (
        select e.id as examen_id, e.titulo, e.tipo_examen
          from public.preguntas_examen pe
          join public.examenes e on e.id = pe.examen_id and e.activo and e.publicado_en <= v_now
         where pe.pregunta_id = s.pregunta_id and q.activa
           and (not pe.es_reserva or private.saved_question_resolved(p_usuario, s.pregunta_id))
           and exists (select 1 from private.exam_access_verdict(p_usuario, e.id, v_now) v where v.state in ('FREE', 'GRANTED', 'SUBSCRIPTION'))
         order by (pe.anulada is true), e.publicado_en desc, e.id
         limit 1
      ) x on true
     where s.usuario_id = p_usuario and s.tipo = 'pregunta'
       and (p_after_ts is null or (s.guardado_en, s.id) < (p_after_ts, p_after_id))
     order by s.guardado_en desc, s.id desc
     limit least(greatest(coalesce(p_limit, 21), 1), 51);
end;
$$;
revoke all on function public.app_list_saved(uuid, text, timestamptz, uuid, integer) from public, anon, authenticated;
grant execute on function public.app_list_saved(uuid, text, timestamptz, uuid, integer) to service_role;

-- ---- 2. the detail of ONE saved question (Library sheet). No write, no limiter. Solution only once resolved (B1).
create or replace function public.app_saved_question_detail(p_usuario uuid, p_pregunta uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_now timestamptz := clock_timestamp();
  v_examen uuid;
  v_titulo text;
  v_tipo text;
  v_posicion integer;
  v_anulada boolean;
  v_resuelta boolean;
begin
  if p_usuario is null or p_pregunta is null
     or not exists (select 1 from public.recursos_guardados_usuario s where s.usuario_id = p_usuario and s.tipo = 'pregunta' and s.pregunta_id = p_pregunta) then
    return jsonb_build_object('outcome', 'NOT_FOUND');
  end if;
  v_resuelta := private.saved_question_resolved(p_usuario, p_pregunta);
  select e.id, e.titulo, e.tipo_examen, pe.posicion, (pe.anulada is true)
    into v_examen, v_titulo, v_tipo, v_posicion, v_anulada
    from public.preguntas q
    join public.preguntas_examen pe on pe.pregunta_id = q.id
    join public.examenes e on e.id = pe.examen_id and e.activo and e.publicado_en <= v_now
   where q.id = p_pregunta and q.activa
     and (not pe.es_reserva or v_resuelta)
     and exists (select 1 from private.exam_access_verdict(p_usuario, e.id, v_now) v where v.state in ('FREE', 'GRANTED', 'SUBSCRIPTION'))
   order by (pe.anulada is true), e.publicado_en desc, e.id
   limit 1;
  if v_examen is null then
    return jsonb_build_object('outcome', 'NOT_FOUND');
  end if;
  return jsonb_build_object(
    'outcome', 'OK',
    'pregunta_id', p_pregunta,
    'examen_id', v_examen,
    'examen_titulo', v_titulo,
    'tipo_examen', v_tipo,
    'posicion', v_posicion,
    'anulada', v_anulada,
    'contenido', private.app_revision_pregunta_contenido(p_pregunta),
    'solucion', case when v_resuelta then private.app_revision_pregunta_solucion(p_pregunta) else 'null'::jsonb end);
end;
$$;
revoke all on function public.app_saved_question_detail(uuid, uuid) from public, anon, authenticated;
grant execute on function public.app_saved_question_detail(uuid, uuid) to service_role;

-- ---- 3. saving needs the question REVEALED to the user (B1); otherwise the body of 20261022100800 unchanged.
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
  if not private.saved_question_revealed(p_usuario, p_target) or not exists (
    select 1
      from public.preguntas q
      join public.preguntas_examen pe on pe.pregunta_id = q.id and not pe.anulada
                                     and (not pe.es_reserva or private.saved_question_resolved(p_usuario, p_target))
      join public.examenes e on e.id = pe.examen_id and e.activo and e.publicado_en <= v_now
     where q.id = p_target and q.activa
       and exists (select 1 from private.exam_access_verdict(p_usuario, e.id, v_now) v where v.state in ('FREE', 'GRANTED', 'SUBSCRIPTION'))
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
revoke all on function public.app_save_item(uuid, text, uuid) from public, anon, authenticated;
grant execute on function public.app_save_item(uuid, text, uuid) to service_role;

commit;
