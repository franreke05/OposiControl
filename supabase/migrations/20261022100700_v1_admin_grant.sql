-- V1 (owner decision 2026-10-16): an admin gift of an exam gives ONE MONTH of access (the policy term, same as a purchase), set by the SERVER, never free-form and never unlimited.
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Requires 20261022100500 (private.exam_access_verdict) and 20261022100600.
--
-- public.admin_grant_exam_access(user, exam, motivo): in ONE transaction, under the SAME per-wallet advisory lock the economy uses (so it serialises with a redeem):
--   * motivo must be one of admin, regalo, promocion, premium (a purchase only comes from a purchase: GIFT_REASON_NOT_ALLOWED);
--   * the user must have a profile (GIFT_USER_NOT_FOUND); the exam must be active and published (GIFT_EXAM_NOT_AVAILABLE);
--   * the gates apply like to any access (rule 0 of the single verdict): an exam outside the user's specialty (or a user without one), or of a type the specialty switches off,
--     is refused (GIFT_NOT_USABLE) instead of storing an access nobody can use;
--   * term = examenes.dias_acceso_compra (30 by default), DB clock; stacking on an existing grant or purchase: validUntil = max(now, latest end of the active grants of that exam) + term,
--     never shortened; a new row is written (a purchase row is never extended by a gift);
--   * an active UNLIMITED grant (legacy rows) is returned as is (UNLIMITED_EXISTS: nothing to extend); a repeated identical gift within 30 seconds (double click) is a REPLAY, not a second extension;
--   * the function never creates a row with NULL valido_hasta and never touches the wallet (a gift is not an OT debit).
-- The Ktor route keeps writing the audit entry (append-only audit log) with the actor.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate):
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.admin_grant_exam_access(uuid, uuid, text);
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'admin_grant_exam_access_not_service_role_only' as check_name,
--        (case when has_function_privilege('anon', 'public.admin_grant_exam_access(uuid,uuid,text)', 'execute')
--                or has_function_privilege('authenticated', 'public.admin_grant_exam_access(uuid,uuid,text)', 'execute')
--                or not has_function_privilege('service_role', 'public.admin_grant_exam_access(uuid,uuid,text)', 'execute') then 1 else 0 end)::bigint as failures;
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

create or replace function public.admin_grant_exam_access(p_usuario uuid, p_examen uuid, p_motivo text)
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
  return query select 'CREATED'::text, v_row.id, v_row.valido_desde, v_row.valido_hasta;
end;
$$;
revoke all on function public.admin_grant_exam_access(uuid, uuid, text) from public, anon, authenticated;
grant execute on function public.admin_grant_exam_access(uuid, uuid, text) to service_role;

commit;
