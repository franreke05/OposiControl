-- =============================================================================
-- V1 -- redeem an exam with OposiTickets: ONE transaction (economy step 3 of 3). Design: E1-V1-MINIDESIGN.md sections 4, 5, 7.
-- =============================================================================
-- LOCAL / PG17 GATE ONLY. NOT applied to MAIN. Forward-only. Requires 20261022100300 and 20261022100400.
--
-- WHAT IT DOES
--   * contenido_desbloqueado_usuario: purchase fields (pricePaid, currency, transactionId, purchasedAt), mandatory for every NEW 'compra' row (a trigger:
--     legacy 'compra' rows that predate these columns stay editable, e.g. an admin revocation); ONE purchased entitlement row per (user, exam) (partial unique).
--   * private.exam_access_verdict(user, exam, now): the ONE SQL definition of the access verdict. It mirrors EntitlementPolicy (Kotlin) as it is TODAY
--     (capability switch first, then FREE, GRANTED, SUBSCRIPTION, DEMO, LOCKED); a generated-matrix parity test runs both on the same facts. When the owner
--     decides the authorization rule (A/B/C in E1-V1-AUTHZ-DECISION.md) this function and the Kotlin policy change together.
--   * public.app_redeem_exam_access(user, exam, key, expected_price): in ONE transaction and under the wallet lock it replays by (user, key) or
--     checks exam/purchasable/price/accessibility/balance, debits the ledger (FIFO allocation by the ledger trigger), and creates or extends the entitlement:
--         newValidUntil = max(db_now, latest valido_hasta of the user's ACTIVE grants of that exam) + dias_acceso_compra days   (DB clock, never shortened)
--     Price, days and purchasability are read INSIDE from `examenes` (precio_tickets, dias_acceso_compra, comprable_con_tickets). Nothing is invented: unset
--     price or comprable_con_tickets = false -> NOT_PURCHASABLE. The request fingerprint is stored in the ledger row so a replay returns the same result.
--   * SPECIALTY GATE (owner 2026-10-16): the verdict has a rule 0, SPECIALTY_MISMATCH (LOCKED) when the user's specialty is missing or differs from the exam's; the redeem answers
--     EXAM_NOT_FOUND for such an exam. Mirrored in Kotlin (EntitlementPolicy) and covered by the generated-matrix parity test.
--   * public.app_server_now(): the database clock for Ktor. Ktor decides 'until when does this access last' for D2-02 / D2-13 with the SAME clock that wrote
--     `valido_hasta` here (ServerClock reads it, then advances monotonically); a JVM-clock skew can never show an expired exam as valid.
--   * Domain errors (SQLSTATE P0001, message = code, numbers in DETAIL as JSON): EXAM_NOT_FOUND, NOT_PURCHASABLE, PRICE_CHANGED {"price"},
--     ALREADY_ACCESSIBLE {"state"}, INSUFFICIENT_BALANCE {"balance","price"}, IDEMPOTENCY_KEY_REQUIRED, IDEMPOTENCY_KEY_REUSED, WALLET_INVALID_USER.
--     None of them writes anything.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate)
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_server_now();
-- drop function if exists public.app_redeem_exam_access(uuid, uuid, text, integer);
-- drop function if exists private.exam_access_verdict(uuid, uuid, timestamptz);
-- drop index if exists public.ux_contenido_desbloqueado_compra;
-- drop trigger if exists trg_contenido_desbloqueado_compra_completa on public.contenido_desbloqueado_usuario;
-- drop function if exists private.tg_contenido_desbloqueado_compra_completa();
-- alter table public.contenido_desbloqueado_usuario drop column if exists precio_pagado;
-- alter table public.contenido_desbloqueado_usuario drop column if exists moneda;
-- alter table public.contenido_desbloqueado_usuario drop column if exists transaccion_id;
-- alter table public.contenido_desbloqueado_usuario drop column if exists comprado_en;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'redeem_not_service_role_only',
--        (case when has_function_privilege('anon', 'public.app_redeem_exam_access(uuid,uuid,text,integer)', 'execute')
--                or has_function_privilege('authenticated', 'public.app_redeem_exam_access(uuid,uuid,text,integer)', 'execute')
--                or not has_function_privilege('service_role', 'public.app_redeem_exam_access(uuid,uuid,text,integer)', 'execute') then 1 else 0 end)::bigint
-- union all
-- select 'server_now_not_service_role_only',
--        (case when has_function_privilege('anon', 'public.app_server_now()', 'execute') or has_function_privilege('authenticated', 'public.app_server_now()', 'execute')
--                or not has_function_privilege('service_role', 'public.app_server_now()', 'execute') then 1 else 0 end)::bigint
-- union all
-- select 'verdict_executable_by_clients',
--        (case when has_function_privilege('anon', 'private.exam_access_verdict(uuid,uuid,timestamptz)', 'execute')
--                or has_function_privilege('authenticated', 'private.exam_access_verdict(uuid,uuid,timestamptz)', 'execute') then 1 else 0 end)::bigint
-- union all
-- select 'one_purchased_row_guard_missing',
--        (case when exists (select 1 from pg_indexes where indexname = 'ux_contenido_desbloqueado_compra') then 0 else 1 end)::bigint
-- union all
-- select 'purchase_fields_guard_missing',
--        (case when exists (select 1 from pg_trigger where tgname = 'trg_contenido_desbloqueado_compra_completa' and not tgisinternal) then 0 else 1 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (fail closed; nothing is changed to make it pass)
do $$
begin
  if to_regclass('public.contenido_desbloqueado_usuario') is null or to_regclass('public.examenes') is null or to_regclass('public.estado_premium_usuario') is null
     or to_regclass('public.ot_consumos') is null or to_regprocedure('public.app_wallet_reconcile(uuid)') is null
     or to_regclass('public.capacidades_especialidad') is null then
    raise exception using errcode = 'P0001', message = 'V1_REDEEM_PREFLIGHT', hint = 'apply 20261022100300 and 20261022100400 first';
  end if;
  if exists (select 1 from public.contenido_desbloqueado_usuario g where g.motivo = 'compra' and g.examen_id is not null
              group by g.usuario_id, g.examen_id having count(*) > 1) then
    raise exception using errcode = 'P0001', message = 'V1_REDEEM_PREFLIGHT',
      hint = 'a user already has more than one purchased entitlement row for the same exam; resolve with the owner first';
  end if;
end
$$;

alter table public.contenido_desbloqueado_usuario add column if not exists precio_pagado integer;
alter table public.contenido_desbloqueado_usuario add column if not exists moneda text;
alter table public.contenido_desbloqueado_usuario add column if not exists transaccion_id uuid references public.transacciones_cartera(id);
alter table public.contenido_desbloqueado_usuario add column if not exists comprado_en timestamptz;
create or replace function private.tg_contenido_desbloqueado_compra_completa()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  -- A purchased entitlement is complete or it is not written. Legacy 'compra' rows (no transaccion_id) are not touched by this rule.
  if new.motivo = 'compra' and (tg_op = 'INSERT' or old.motivo is distinct from 'compra' or old.transaccion_id is not null) then
    if new.valido_hasta is null or new.precio_pagado is null or new.precio_pagado <= 0 or new.moneda is distinct from 'OPOSITICKETS'
       or new.transaccion_id is null or new.comprado_en is null then
      raise exception using errcode = '23514', message = 'ENTITLEMENT_PURCHASE_INCOMPLETE';
    end if;
  end if;
  return new;
end;
$$;
revoke all on function private.tg_contenido_desbloqueado_compra_completa() from public, anon, authenticated;
drop trigger if exists trg_contenido_desbloqueado_compra_completa on public.contenido_desbloqueado_usuario;
create trigger trg_contenido_desbloqueado_compra_completa before insert or update on public.contenido_desbloqueado_usuario
  for each row execute function private.tg_contenido_desbloqueado_compra_completa();
create unique index if not exists ux_contenido_desbloqueado_compra
  on public.contenido_desbloqueado_usuario (usuario_id, examen_id) where motivo = 'compra';

-- The single SQL definition of the verdict (mirrors EntitlementPolicy as of 2026-10-15; see the header).
create or replace function private.exam_access_verdict(p_usuario uuid, p_examen uuid, p_now timestamptz default now())
returns table(state text, reason text, unlimited_grant boolean)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  e record;
  caps record;
  v_allows boolean;
  v_uspec uuid;
  v_grant boolean;
  v_unlimited boolean;
begin
  select x.tipo_examen, x.tipo_acceso, x.demo_disponible, x.especialidad_id into e from public.examenes x where x.id = p_examen;
  if not found then
    return;
  end if;
  -- SPECIALTY IS AN ACCESS GATE (owner 2026-10-16): an exam outside the user's specialty is not visible and not usable, whatever else the user holds (a stored
  -- purchase stays stored). A user WITHOUT a specialty is denied too (owner/E2-V1-E06): until the specialty is chosen nothing is visible or usable. Rule 0 of the single access rule.
  select pf.especialidad_principal_id into v_uspec from public.perfiles_usuario pf where pf.usuario_id = p_usuario;
  if v_uspec is null or v_uspec <> e.especialidad_id then
    state := 'LOCKED'; reason := 'SPECIALTY_MISMATCH'; unlimited_grant := false;
    return next;
    return;
  end if;
  select c.permite_rafagas, c.permite_razonados, c.permite_inmersivos, c.permite_demos into caps
    from public.capacidades_especialidad c where c.especialidad_id = e.especialidad_id;
  if not found then
    caps.permite_rafagas := true; caps.permite_razonados := true; caps.permite_inmersivos := true; caps.permite_demos := true;
  end if;
  v_allows := case e.tipo_examen when 'rafaga' then caps.permite_rafagas when 'razonado' then caps.permite_razonados when 'inmersivo' then caps.permite_inmersivos else false end;
  select exists (select 1 from public.contenido_desbloqueado_usuario g
                  where g.usuario_id = p_usuario and g.examen_id = p_examen and g.valido_desde <= p_now and (g.valido_hasta is null or g.valido_hasta > p_now)),
         exists (select 1 from public.contenido_desbloqueado_usuario g
                  where g.usuario_id = p_usuario and g.examen_id = p_examen and g.valido_desde <= p_now and g.valido_hasta is null)
    into v_grant, v_unlimited;
  unlimited_grant := v_unlimited;
  if not v_allows then
    state := 'LOCKED'; reason := 'CAPABILITY_DISABLED';
  elsif e.tipo_acceso = 'gratuito' then
    state := 'FREE'; reason := null;
  elsif v_grant then
    state := 'GRANTED'; reason := null;
  elsif exists (select 1 from public.estado_premium_usuario p
                 where p.usuario_id = p_usuario and p.es_premium and p.estado_suscripcion in ('activa', 'periodo_prueba', 'cancelada')
                   and (p.fin_en is null or p.fin_en > p_now)) then
    state := 'SUBSCRIPTION'; reason := null;
  elsif e.demo_disponible and caps.permite_demos then
    state := 'DEMO'; reason := 'DEMO_ONLY';
  else
    state := 'LOCKED'; reason := 'NOT_ENTITLED';
  end if;
  return next;
end;
$$;

create or replace function public.app_redeem_exam_access(p_usuario uuid, p_examen uuid, p_idem_key text, p_expected_price integer default null)
returns table(outcome text, transaccion_id uuid, examen_id uuid, precio_pagado integer, valido_desde timestamptz, valido_hasta timestamptz,
              comprado_en timestamptz, saldo integer)
language plpgsql
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  v_key text := btrim(p_idem_key);
  v_fp text;
  v_now timestamptz;
  v_exam public.examenes%rowtype;
  v_ledger public.transacciones_cartera%rowtype;
  v_verdict record;
  v_balance integer;
  v_active_until timestamptz;
  v_existing_from timestamptz;
  v_from timestamptz;
  v_until timestamptz;
begin
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  if v_key is null or length(v_key) = 0 or length(v_key) > 128 or p_examen is null then
    raise exception using errcode = 'P0001', message = 'IDEMPOTENCY_KEY_REQUIRED';
  end if;
  v_fp := encode(sha256(convert_to('redeem|' || p_examen::text || '|' || coalesce(p_expected_price::text, '-'), 'utf8')), 'hex');

  -- ONE lock per wallet; every economic function takes it first.
  perform pg_advisory_xact_lock(hashtextextended('wallet:' || p_usuario::text, 0));
  -- E2-V1-E03: the clock is read AFTER the lock. now() is the transaction START: a redeem that waited on the lock would use a stale instant, miss the
  -- row the other purchase just committed and overwrite its end date with an earlier one (two debits, 30 days instead of 60).
  v_now := clock_timestamp();

  -- Replay (authoritative: it works even if the Ktor idempotency store lost the response after the commit).
  select t.* into v_ledger from public.transacciones_cartera t where t.usuario_id = p_usuario and t.idempotency_key = v_key;
  if found then
    if v_ledger.metadatos ->> 'op' is distinct from 'redeem' or v_ledger.metadatos ->> 'fp' is distinct from v_fp then
      raise exception using errcode = 'P0001', message = 'IDEMPOTENCY_KEY_REUSED';
    end if;
    return query select 'REPLAY'::text, v_ledger.id, p_examen, -v_ledger.cantidad,
                        (v_ledger.metadatos ->> 'valido_desde')::timestamptz, (v_ledger.metadatos ->> 'valido_hasta')::timestamptz,
                        (v_ledger.metadatos ->> 'comprado_en')::timestamptz,
                        coalesce((select w.saldo_tickets from public.carteras_usuario w where w.usuario_id = p_usuario), 0);
    return;
  end if;

  select x.* into v_exam from public.examenes x
   where x.id = p_examen and x.activo and x.publicado_en is not null and x.publicado_en <= v_now;
  if not found then
    raise exception using errcode = 'P0001', message = 'EXAM_NOT_FOUND';
  end if;
  -- out of the user's specialty = the exam does not exist for this user (same answer as D2-02 / D2-03)
  select v.* into v_verdict from private.exam_access_verdict(p_usuario, p_examen, v_now) v;
  if v_verdict.reason = 'SPECIALTY_MISMATCH' then
    raise exception using errcode = 'P0001', message = 'EXAM_NOT_FOUND';
  end if;
  if not v_exam.comprable_con_tickets or v_exam.precio_tickets is null or v_exam.precio_tickets <= 0 or v_exam.dias_acceso_compra is null or v_exam.dias_acceso_compra <= 0 then
    raise exception using errcode = 'P0001', message = 'NOT_PURCHASABLE';
  end if;
  if p_expected_price is not null and p_expected_price <> v_exam.precio_tickets then
    raise exception using errcode = 'P0001', message = 'PRICE_CHANGED', detail = format('{"price":%s}', v_exam.precio_tickets);
  end if;

  select v.* into v_verdict from private.exam_access_verdict(p_usuario, p_examen, v_now) v;
  -- A free exam is never sold, even if its specialty switch makes the verdict LOCKED; premium subscribers and unlimited grants already have access.
  if v_exam.tipo_acceso = 'gratuito' or v_verdict.state in ('FREE', 'SUBSCRIPTION') or (v_verdict.state = 'GRANTED' and v_verdict.unlimited_grant) then
    raise exception using errcode = 'P0001', message = 'ALREADY_ACCESSIBLE', detail = format('{"state":"%s"}', case when v_exam.tipo_acceso = 'gratuito' then 'FREE' else v_verdict.state end);
  end if;

  select coalesce((select w.saldo_tickets from public.carteras_usuario w where w.usuario_id = p_usuario), 0) into v_balance;
  if v_balance < v_exam.precio_tickets then
    raise exception using errcode = 'P0001', message = 'INSUFFICIENT_BALANCE', detail = format('{"balance":%s,"price":%s}', v_balance, v_exam.precio_tickets);
  end if;

  -- Entitlement window: extend from the latest ACTIVE end, never shorten; DB clock.
  select max(g.valido_hasta) into v_active_until from public.contenido_desbloqueado_usuario g
   where g.usuario_id = p_usuario and g.examen_id = p_examen and g.valido_hasta > v_now;  -- regardless of valido_desde: a row committed an instant ago still counts
  v_until := greatest(v_now, coalesce(v_active_until, v_now)) + make_interval(days => v_exam.dias_acceso_compra);
  select g.valido_desde into v_existing_from from public.contenido_desbloqueado_usuario g
   where g.usuario_id = p_usuario and g.examen_id = p_examen and g.motivo = 'compra' and g.valido_hasta > v_now;
  v_from := coalesce(v_existing_from, v_now);

  insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, referencia_tipo, referencia_id, idempotency_key, metadatos)
  values (p_usuario, 'compra_examen', -v_exam.precio_tickets, 'Acceso a examen', 'examen', p_examen, v_key,
          jsonb_build_object('op', 'redeem', 'fp', v_fp, 'valido_desde', v_from, 'valido_hasta', v_until, 'comprado_en', v_now))
  returning * into v_ledger;

  insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta, precio_pagado, moneda, transaccion_id, comprado_en)
  values (p_usuario, p_examen, 'compra', v_from, v_until, v_exam.precio_tickets, 'OPOSITICKETS', v_ledger.id, v_now)
  on conflict (usuario_id, examen_id) where motivo = 'compra'
  do update set valido_desde = case when public.contenido_desbloqueado_usuario.valido_hasta >= excluded.valido_desde
                                    then least(public.contenido_desbloqueado_usuario.valido_desde, excluded.valido_desde) else excluded.valido_desde end,
                valido_hasta = greatest(public.contenido_desbloqueado_usuario.valido_hasta, excluded.valido_hasta), precio_pagado = excluded.precio_pagado,
                moneda = excluded.moneda, transaccion_id = excluded.transaccion_id, comprado_en = excluded.comprado_en;

  insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
  values (p_usuario, 'canje_examen', 'transacciones_cartera', v_ledger.id,
          jsonb_build_object('examen_id', p_examen, 'precio', v_exam.precio_tickets, 'valido_hasta', v_until));

  return query select 'CREATED'::text, v_ledger.id, p_examen, v_exam.precio_tickets, v_from, v_until, v_now, v_ledger.saldo_resultante;
end;
$$;

create or replace function public.app_server_now()
returns timestamptz
language sql
stable
set search_path = ''
as $$ select clock_timestamp(); $$;
revoke all on function public.app_server_now() from public, anon, authenticated;
grant execute on function public.app_server_now() to service_role;

revoke all on function private.exam_access_verdict(uuid, uuid, timestamptz) from public, anon, authenticated;
revoke all on function public.app_redeem_exam_access(uuid, uuid, text, integer) from public, anon, authenticated;
grant execute on function public.app_redeem_exam_access(uuid, uuid, text, integer) to service_role;

commit;
