-- V1 (owner decision 2026-10-16): an admin gift of an exam gives ONE MONTH of access (the policy term, same as a purchase), set by the SERVER, never free-form and never unlimited.
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Requires 20261022100500 (private.exam_access_verdict) and 20261022100600.
--
-- public.admin_grant_exam_access(user, exam, motivo, actor): in ONE transaction, under the SAME per-wallet advisory lock the economy uses (so it serialises with a redeem):
--   * motivo must be one of admin, regalo, promocion, premium (a purchase only comes from a purchase: GIFT_REASON_NOT_ALLOWED);
--   * the user must have a profile (GIFT_USER_NOT_FOUND); the exam must be active and published (GIFT_EXAM_NOT_AVAILABLE);
--   * the gates apply like to any access (rule 0 of the single verdict): an exam outside the user's specialty (or a user without one), or of a type the specialty switches off,
--     is refused (GIFT_NOT_USABLE) instead of storing an access nobody can use;
--   * term = examenes.dias_acceso_compra (30 by default), DB clock; stacking on an existing grant or purchase: validUntil = max(now, latest end of the active grants of that exam) + term,
--     never shortened; a new row is written (a purchase row is never extended by a gift);
--   * an active UNLIMITED grant (legacy rows) is returned as is (UNLIMITED_EXISTS: nothing to extend); a repeated identical gift within 30 seconds (double click) is a REPLAY, not a second extension;
--   * the audit entry (`acceso_examen_concedido`, actor = p_actor) is written INSIDE the same transaction: a grant can never commit without its audit row (append-only audit log format of develop);
--   * the function never creates a row with NULL valido_hasta and never touches the wallet (a gift is not an OT debit).
-- public.admin_revoke_exam_access(grant, actor): ONE atomic revoke under the same lock and the DB clock: closes (valido_hasta = now) EVERY active non-purchase grant of that user and exam,
--   never a purchase (GRANT_IS_PURCHASE), idempotent (ALREADY_INACTIVE writes nothing), audited in the same transaction (`acceso_examen_retirado`). A gift committed between a read and the update
--   cannot stay active while the revoke answers 200.
-- Table guard (trigger): an INSERT, or an UPDATE of valido_hasta, that leaves valido_hasta NULL on a GIFT row (motivo admin, regalo, promocion) is refused (GIFT_UNLIMITED_FORBIDDEN). Legacy unlimited
--   rows stay valid and untouched (an UPDATE that keeps NULL is allowed). Not applied to premium (a subscription may be open ended) nor to purchases (their own trigger).
--   RECONCILE NOTE: develop's un-merged `upsertExamAccess` writes NULL; this trigger would refuse it, but the merge replaces that body with the RPC call (develop did not change that function since the
--   merge-base), and this migration is only ever deployed together with that route.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate):
-- ROLLBACK-BEGIN
-- begin;
-- drop trigger if exists trg_contenido_desbloqueado_regalo_con_plazo on public.contenido_desbloqueado_usuario;
-- drop function if exists private.tg_contenido_desbloqueado_regalo_con_plazo();
-- drop function if exists public.admin_revoke_exam_access(uuid, uuid);
-- drop function if exists public.admin_grant_exam_access(uuid, uuid, text, uuid);
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'admin_gift_functions_not_service_role_only' as check_name,
--        (case when has_function_privilege('anon', 'public.admin_grant_exam_access(uuid,uuid,text,uuid)', 'execute') or has_function_privilege('authenticated', 'public.admin_grant_exam_access(uuid,uuid,text,uuid)', 'execute')
--                or has_function_privilege('anon', 'public.admin_revoke_exam_access(uuid,uuid)', 'execute') or has_function_privilege('authenticated', 'public.admin_revoke_exam_access(uuid,uuid)', 'execute')
--                or not has_function_privilege('service_role', 'public.admin_grant_exam_access(uuid,uuid,text,uuid)', 'execute')
--                or not has_function_privilege('service_role', 'public.admin_revoke_exam_access(uuid,uuid)', 'execute') then 1 else 0 end)::bigint as failures
-- union all
-- select 'gift_term_trigger_missing',
--        (case when exists (select 1 from pg_trigger where tgname = 'trg_contenido_desbloqueado_regalo_con_plazo' and tgrelid = 'public.contenido_desbloqueado_usuario'::regclass and not tgisinternal) then 0 else 1 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('private.exam_access_verdict(uuid,uuid,timestamptz)') is null then
    raise exception using errcode = 'P0001', message = 'V1_ADMIN_GRANT_PREFLIGHT', hint = 'apply 20261022100500 first';
  end if;
end
$$;

drop function if exists public.admin_grant_exam_access(uuid, uuid, text);

create or replace function public.admin_grant_exam_access(p_usuario uuid, p_examen uuid, p_motivo text, p_actor uuid default null)
returns table(outcome text, grant_id uuid, valido_desde timestamptz, valido_hasta timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  v_now timestamptz;
  v_exam public.examenes%rowtype;
  v_verdict record;
  v_row public.contenido_desbloqueado_usuario%rowtype;
  v_base timestamptz;
  v_days integer;
begin
  if p_motivo is null or p_motivo not in ('admin', 'regalo', 'promocion', 'premium') then
    raise exception using errcode = 'P0001', message = 'GIFT_REASON_NOT_ALLOWED';
  end if;
  if p_usuario is null or p_examen is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'GIFT_USER_NOT_FOUND';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('wallet:' || p_usuario::text, 0));
  v_now := clock_timestamp();
  select x.* into v_exam from public.examenes x where x.id = p_examen and x.activo and x.publicado_en is not null and x.publicado_en <= v_now;
  if not found then
    raise exception using errcode = 'P0001', message = 'GIFT_EXAM_NOT_AVAILABLE';
  end if;
  select v.* into v_verdict from private.exam_access_verdict(p_usuario, p_examen, v_now) v;
  if v_verdict.reason in ('SPECIALTY_MISMATCH', 'CAPABILITY_DISABLED') then
    raise exception using errcode = 'P0001', message = 'GIFT_NOT_USABLE', detail = format('{"reason":"%s"}', v_verdict.reason);
  end if;

  -- an active unlimited grant (legacy rows) has nothing to extend
  select g.* into v_row from public.contenido_desbloqueado_usuario g
   where g.usuario_id = p_usuario and g.examen_id = p_examen and g.valido_desde <= v_now and g.valido_hasta is null limit 1;
  if found then
    return query select 'UNLIMITED_EXISTS'::text, v_row.id, v_row.valido_desde, v_row.valido_hasta;
    return;
  end if;
  -- the same gift again within 30 seconds is a double click, not a second month
  select g.* into v_row from public.contenido_desbloqueado_usuario g
   where g.usuario_id = p_usuario and g.examen_id = p_examen and g.motivo = p_motivo and g.valido_hasta > v_now and g.creado_en > v_now - interval '30 seconds'
   order by g.creado_en desc limit 1;
  if found then
    return query select 'REPLAY'::text, v_row.id, v_row.valido_desde, v_row.valido_hasta;
    return;
  end if;

  v_days := case when v_exam.dias_acceso_compra is not null and v_exam.dias_acceso_compra > 0 then v_exam.dias_acceso_compra else 30 end;
  select coalesce(max(g.valido_hasta), v_now) into v_base from public.contenido_desbloqueado_usuario g
   where g.usuario_id = p_usuario and g.examen_id = p_examen and g.valido_hasta > v_now;
  v_base := greatest(v_base, v_now);
  insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta)
  values (p_usuario, p_examen, p_motivo, v_now, v_base + make_interval(days => v_days))
  returning * into v_row;
  -- the audit row commits WITH the grant (same transaction): there is no grant without its audit entry
  insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
  values (p_actor, 'acceso_examen_concedido', 'contenido_desbloqueado_usuario', v_row.id,
          jsonb_build_object('usuario_id', p_usuario, 'examen_id', p_examen, 'motivo', p_motivo, 'valido_hasta', v_row.valido_hasta));
  return query select 'CREATED'::text, v_row.id, v_row.valido_desde, v_row.valido_hasta;
end;
$$;
revoke all on function public.admin_grant_exam_access(uuid, uuid, text, uuid) from public, anon, authenticated;
grant execute on function public.admin_grant_exam_access(uuid, uuid, text, uuid) to service_role;

create or replace function public.admin_revoke_exam_access(p_grant uuid, p_actor uuid default null)
returns table(outcome text, grant_id uuid, usuario_id uuid, examen_id uuid, motivo text, valido_hasta timestamptz, concesiones integer)
language plpgsql
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  v_target public.contenido_desbloqueado_usuario%rowtype;
  v_now timestamptz;
  v_ids uuid[];
  v_motivos text;
begin
  select g.* into v_target from public.contenido_desbloqueado_usuario g where g.id = p_grant;
  if not found or v_target.examen_id is null then
    raise exception using errcode = 'P0001', message = 'GRANT_NOT_FOUND';
  end if;
  if v_target.motivo = 'compra' then
    raise exception using errcode = 'P0001', message = 'GRANT_IS_PURCHASE';
  end if;
  -- same lock as the gift and the redeem: nothing can be granted between the read below and the update
  perform pg_advisory_xact_lock(hashtextextended('wallet:' || v_target.usuario_id::text, 0));
  v_now := clock_timestamp();
  select coalesce(array_agg(g.id), '{}'::uuid[]), string_agg(distinct g.motivo, ',') into v_ids, v_motivos
    from public.contenido_desbloqueado_usuario g
   where g.usuario_id = v_target.usuario_id and g.examen_id = v_target.examen_id and g.motivo <> 'compra' and (g.valido_hasta is null or g.valido_hasta > v_now);
  if cardinality(v_ids) = 0 then
    return query select 'ALREADY_INACTIVE'::text, v_target.id, v_target.usuario_id, v_target.examen_id, v_target.motivo, (select g.valido_hasta from public.contenido_desbloqueado_usuario g where g.id = v_target.id), 0;
    return;
  end if;
  update public.contenido_desbloqueado_usuario g set valido_hasta = greatest(v_now, g.valido_desde) where g.id = any (v_ids);
  insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, antes, despues)
  values (p_actor, 'acceso_examen_retirado', 'contenido_desbloqueado_usuario', v_target.id,
          jsonb_build_object('usuario_id', v_target.usuario_id, 'examen_id', v_target.examen_id, 'motivos', v_motivos, 'concesiones', cardinality(v_ids)),
          jsonb_build_object('valido_hasta', v_now));
  return query select 'REVOKED'::text, v_target.id, v_target.usuario_id, v_target.examen_id, v_target.motivo, v_now, cardinality(v_ids);
end;
$$;
revoke all on function public.admin_revoke_exam_access(uuid, uuid) from public, anon, authenticated;
grant execute on function public.admin_revoke_exam_access(uuid, uuid) to service_role;

create or replace function private.tg_contenido_desbloqueado_regalo_con_plazo()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.valido_hasta is null and new.motivo in ('admin', 'regalo', 'promocion') and new.examen_id is not null
     and (tg_op = 'INSERT' or old.valido_hasta is not null) then
    raise exception using errcode = '23514', message = 'GIFT_UNLIMITED_FORBIDDEN';
  end if;
  return new;
end;
$$;
revoke all on function private.tg_contenido_desbloqueado_regalo_con_plazo() from public, anon, authenticated;
drop trigger if exists trg_contenido_desbloqueado_regalo_con_plazo on public.contenido_desbloqueado_usuario;
create trigger trg_contenido_desbloqueado_regalo_con_plazo before insert or update of valido_hasta on public.contenido_desbloqueado_usuario
  for each row execute function private.tg_contenido_desbloqueado_regalo_con_plazo();

commit;
