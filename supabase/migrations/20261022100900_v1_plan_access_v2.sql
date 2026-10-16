-- V1 Access v2 (client answers 2026-10-16, E1-V1-ACCESS-V2-DESIGN.md v2.1, E2 conditions C-A1..C-A7): plan coverage, the immersive quota the USER chooses, subscription lifecycle.
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Requires 20261022100500 (verdict), 20261022100700 (gift) and the MAIN plan tables.
--
-- 1. public.configuracion_v1: Control-editable numbers (never hardcoded in the app). Defaults are seeded here; every change is audited by trigger (C-A3).
-- 2. private.plan_benefit_int(plan, code, min, max): the ONE reader of beneficios_plan numbers: defensive (valor_integer only), bounded, malformed or out of range = NULL (fail closed, C-A2).
-- 3. private.premium_active(...): the ONE predicate "this subscription row gives access now" (activa / periodo_prueba / cancelada with its paid period left; a cancelada row without fin_en does NOT
--    give access: fail closed). private.user_plan(user, now) resolves estado_premium_usuario.producto_codigo -> productos_comerciales.plan_id -> planes_suscripcion.nivel.
-- 4. private.exam_access_verdict v2 (SAME signature and result): rule 0 specialty, capability, FREE, GRANTED (any active grant incl. quota), then SUBSCRIPTION ONLY when the plan covers the exam kind
--    (nivel >= 1 for rafaga, >= 2 for razonado; an immersive is NEVER covered by the subscription itself, only through a grant), then DEMO, then LOCKED. An unresolved plan covers nothing.
--    The gates live in private.exam_gate (one definition used by the verdict and by the quota choice).
-- 5. Quota: motivo 'plan_cupo' (dedicated, CHECK widened) + contenido_desbloqueado_usuario.cupo_suscripcion_inicio; only public.app_choose_plan_exam writes it (trigger guard). The choice persists while the
--    user is subscribed: renewals extend it (oldest N by desde, N = current quota), cancellation honours the paid period, a terminal state (not active any more) closes the rows at once. NO swap in V1.
-- 6. public.sincronizar_estado_premium_usuario (MAIN function, replaced when public.suscripciones exists): keeps `cancelada` with paid time left (Kotlin wins, C-A4) and ignores terminal motives
--    (suscripciones.fin_motivo: reembolso, revocada, contracargo, fallo_cobro) so a refunded/charged-back user is denied at once. fin_en must come only from a verified store receipt (adapters: later block).
-- Lock order (C-A6): wallet advisory lock FIRST everywhere; choose reads estado_premium_usuario with a plain read AFTER the lock; the lifecycle trigger takes the wallet lock before touching grants.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate; the gate compares catalog names/ACLs, not bodies; the verdict body of 20261022100500 is restored by re-applying that file)
-- ROLLBACK-BEGIN
-- begin;
-- alter table public.suscripciones drop constraint if exists suscripciones_fin_motivo_check;
-- alter table public.suscripciones drop column if exists fin_motivo;
-- create or replace function public.sincronizar_estado_premium_usuario() returns trigger language plpgsql security definer set search_path to 'public' as $b$
-- declare v_usuario_id uuid; v_suscripcion record;
-- begin
-- v_usuario_id := coalesce(new.usuario_id, old.usuario_id);
-- select s.usuario_id, s.estado, s.producto_codigo, s.proveedor, s.inicio_en, s.fin_en, s.renovacion_en into v_suscripcion from public.suscripciones s
-- where s.usuario_id = v_usuario_id and s.estado in ('activa', 'periodo_prueba') and (s.fin_en is null or s.fin_en > now())
-- order by case when s.estado = 'activa' then 1 when s.estado = 'periodo_prueba' then 2 else 3 end, s.renovacion_en desc nulls last, s.fin_en desc nulls last limit 1;
-- if v_suscripcion.usuario_id is not null then
-- insert into public.estado_premium_usuario (usuario_id, es_premium, estado_suscripcion, producto_codigo, proveedor, inicio_en, fin_en, renovacion_en, actualizado_en)
-- values (v_usuario_id, true, v_suscripcion.estado, v_suscripcion.producto_codigo, v_suscripcion.proveedor, v_suscripcion.inicio_en, v_suscripcion.fin_en, v_suscripcion.renovacion_en, now())
-- on conflict (usuario_id) do update set es_premium = excluded.es_premium, estado_suscripcion = excluded.estado_suscripcion, producto_codigo = excluded.producto_codigo, proveedor = excluded.proveedor, inicio_en = excluded.inicio_en, fin_en = excluded.fin_en, renovacion_en = excluded.renovacion_en, actualizado_en = now();
-- else
-- insert into public.estado_premium_usuario (usuario_id, es_premium, estado_suscripcion, producto_codigo, proveedor, inicio_en, fin_en, renovacion_en, actualizado_en) values (v_usuario_id, false, 'sin_suscripcion', null, null, null, null, null, now())
-- on conflict (usuario_id) do update set es_premium = false, estado_suscripcion = 'sin_suscripcion', producto_codigo = null, proveedor = null, inicio_en = null, fin_en = null, renovacion_en = null, actualizado_en = now();
-- end if;
-- return coalesce(new, old);
-- end; $b$;
-- revoke all on function public.sincronizar_estado_premium_usuario() from public, anon, authenticated;
-- grant execute on function public.sincronizar_estado_premium_usuario() to service_role;
-- drop trigger if exists trg_estado_premium_cupo on public.estado_premium_usuario;
-- drop function if exists private.tg_estado_premium_cupo();
-- drop trigger if exists trg_contenido_desbloqueado_plan_cupo on public.contenido_desbloqueado_usuario;
-- drop function if exists private.tg_contenido_desbloqueado_plan_cupo();
-- drop function if exists public.app_choose_plan_exam(uuid, uuid);
-- drop function if exists private.plan_quota_used(uuid, timestamptz, timestamptz);
-- drop function if exists private.user_plan(uuid, timestamptz);
-- drop function if exists private.premium_active(boolean, text, timestamptz, timestamptz);
-- drop function if exists private.plan_benefit_int(uuid, text, integer, integer);
-- drop function if exists private.config_int(text, integer);
-- drop trigger if exists trg_configuracion_v1_audit on public.configuracion_v1;
-- drop function if exists private.tg_configuracion_v1_audit();
-- drop index if exists public.ux_contenido_desbloqueado_plan_cupo;
-- alter table public.contenido_desbloqueado_usuario drop constraint if exists contenido_desbloqueado_usuario_plan_cupo_check;
-- alter table public.contenido_desbloqueado_usuario drop column if exists cupo_suscripcion_inicio;
-- alter table public.contenido_desbloqueado_usuario drop constraint if exists contenido_desbloqueado_usuario_motivo_check;
-- alter table public.contenido_desbloqueado_usuario add constraint contenido_desbloqueado_usuario_motivo_check check ((motivo = any (array['premium'::text, 'compra'::text, 'regalo'::text, 'admin'::text, 'promocion'::text])));
-- drop function if exists private.exam_gate(uuid, uuid);
-- drop table if exists public.configuracion_v1;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'plan_access_v2_function_not_service_role_only' as check_name,
--        (case when has_function_privilege('anon', 'public.app_choose_plan_exam(uuid,uuid)', 'execute') or has_function_privilege('authenticated', 'public.app_choose_plan_exam(uuid,uuid)', 'execute')
--                or not has_function_privilege('service_role', 'public.app_choose_plan_exam(uuid,uuid)', 'execute') then 1 else 0 end)::bigint as failures
-- union all
-- select 'plan_access_v2_private_executable_by_clients:' || f.n,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute') then 1 else 0 end)::bigint
--   from (values ('private.exam_gate(uuid,uuid)'), ('private.plan_benefit_int(uuid,text,integer,integer)'), ('private.user_plan(uuid,timestamptz)'), ('private.premium_active(boolean,text,timestamptz,timestamptz)'),
--                ('private.plan_quota_used(uuid,timestamptz,timestamptz)'), ('private.config_int(text,integer)')) f(n)
-- union all
-- select 'configuracion_v1_client_access',
--        (case when has_table_privilege('anon', 'public.configuracion_v1', 'select') or has_table_privilege('authenticated', 'public.configuracion_v1', 'select')
--                or has_table_privilege('authenticated', 'public.configuracion_v1', 'insert') or not (select c.relrowsecurity from pg_class c where c.oid = 'public.configuracion_v1'::regclass) then 1 else 0 end)::bigint
-- union all
-- select 'plan_cupo_guard_missing',
--        (case when exists (select 1 from pg_trigger where tgname = 'trg_contenido_desbloqueado_plan_cupo' and not tgisinternal)
--               and exists (select 1 from pg_trigger where tgname = 'trg_estado_premium_cupo' and not tgisinternal) then 0 else 1 end)::bigint
-- union all
-- select 'motivo_check_without_plan_cupo',
--        (case when exists (select 1 from pg_constraint where conname = 'contenido_desbloqueado_usuario_motivo_check' and pg_get_constraintdef(oid) like '%plan_cupo%') then 0 else 1 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('private.exam_access_verdict(uuid,uuid,timestamptz)') is null or to_regclass('public.beneficios_plan') is null
     or to_regclass('public.planes_suscripcion') is null or to_regclass('public.productos_comerciales') is null or to_regclass('public.estado_premium_usuario') is null then
    raise exception using errcode = 'P0001', message = 'V1_PLAN_ACCESS_PREFLIGHT', hint = 'apply 20261022100500 and load the MAIN plan tables first';
  end if;
end
$$;

-- 1. Configuration (Control-editable numbers) with an audit trail.
create table if not exists public.configuracion_v1 (
  clave text primary key,
  valor_integer integer,
  valor_text text,
  actualizado_en timestamptz not null default now(),
  actualizado_por uuid
);
alter table public.configuracion_v1 enable row level security;
revoke all on table public.configuracion_v1 from public, anon, authenticated, service_role;
grant select, insert, update on table public.configuracion_v1 to service_role;
insert into public.configuracion_v1 (clave, valor_integer, valor_text) values
  ('demo_preguntas', 5, null),
  ('demo_inicios_usuario_dia', 20, null),
  ('demo_inicios_ip_dia', 60, null),
  ('zona_horaria', null, 'Europe/Madrid'),
  ('tope_ot_comprados_dia', 5200, null),
  ('max_inmersivos_incluidos', 4, null),
  ('max_limite_preguntas_24h', 500, null),
  ('max_ot_reto_diario', 200, null),
  ('max_ot_bonus_racha', 100, null)
on conflict (clave) do nothing;

create or replace function private.tg_configuracion_v1_audit()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, antes, despues)
  values (new.actualizado_por, 'configuracion_v1_editada', 'configuracion_v1', null,
          case when tg_op = 'UPDATE' then jsonb_build_object('clave', old.clave, 'valor_integer', old.valor_integer, 'valor_text', old.valor_text) end,
          jsonb_build_object('clave', new.clave, 'valor_integer', new.valor_integer, 'valor_text', new.valor_text));
  new.actualizado_en := now();
  return new;
end;
$$;
revoke all on function private.tg_configuracion_v1_audit() from public, anon, authenticated;
drop trigger if exists trg_configuracion_v1_audit on public.configuracion_v1;
create trigger trg_configuracion_v1_audit before insert or update on public.configuracion_v1 for each row execute function private.tg_configuracion_v1_audit();

create or replace function private.config_int(p_clave text, p_default integer)
returns integer
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce((select c.valor_integer from public.configuracion_v1 c where c.clave = p_clave), p_default);
$$;
revoke all on function private.config_int(text, integer) from public, anon, authenticated;

-- 2. The one reader of plan benefits (EAV): integer value only, bounded, anything else = NULL (= no benefit, fail closed).
create or replace function private.plan_benefit_int(p_plan uuid, p_code text, p_min integer, p_max integer)
returns integer
language sql
stable
security definer
set search_path = ''
as $$
  select case when b.valor_integer between p_min and p_max then b.valor_integer end
    from public.beneficios_plan b
   where b.plan_id = p_plan and b.codigo_beneficio = p_code
     and b.valor_boolean is null and b.valor_numeric is null and b.valor_text is null;  -- exactly one value (the integer); anything else is malformed
$$;
revoke all on function private.plan_benefit_int(uuid, text, integer, integer) from public, anon, authenticated;

-- 3. The one "subscription gives access now" predicate and the plan resolver.
create or replace function private.premium_active(p_es boolean, p_estado text, p_fin timestamptz, p_now timestamptz)
returns boolean
language sql
immutable
set search_path = ''
as $$
  select coalesce(p_es, false)
     and p_estado in ('activa', 'periodo_prueba', 'cancelada')
     and (p_fin is null or p_fin > p_now)
     and (p_estado <> 'cancelada' or p_fin is not null);  -- a cancelled subscription without a known end gives no access (fail closed)
$$;
revoke all on function private.premium_active(boolean, text, timestamptz, timestamptz) from public, anon, authenticated;

create or replace function private.user_plan(p_usuario uuid, p_now timestamptz)
returns table(subscribed boolean, resolved boolean, nivel integer, plan_id uuid, plan_codigo text, inicio_en timestamptz, fin_en timestamptz)
language sql
stable
security definer
set search_path = ''
as $$
  select private.premium_active(p.es_premium, p.estado_suscripcion, p.fin_en, p_now) as subscribed,
         (pl.id is not null) as resolved,
         coalesce(pl.nivel, 0), pl.id, pl.codigo, p.inicio_en, p.fin_en
    from public.estado_premium_usuario p
    left join public.productos_comerciales pc on pc.codigo = p.producto_codigo
    left join public.planes_suscripcion pl on pl.id = pc.plan_id
   where p.usuario_id = p_usuario;
$$;
revoke all on function private.user_plan(uuid, timestamptz) from public, anon, authenticated;

-- 4. Gates (rule 0 specialty, capability) in ONE place, then the verdict v2.
create or replace function private.exam_gate(p_usuario uuid, p_examen uuid)
returns table(ok boolean, reason text, tipo_examen text, tipo_acceso text, demo_disponible boolean, especialidad_id uuid, permite_demos boolean)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  e record;
  caps record;
  v_uspec uuid;
  v_allows boolean;
begin
  select x.tipo_examen, x.tipo_acceso, x.demo_disponible, x.especialidad_id into e from public.examenes x where x.id = p_examen;
  if not found then
    return;
  end if;
  -- SPECIALTY IS AN ACCESS GATE (owner 2026-10-16): another specialty, or a user WITHOUT one, is denied before anything else.
  select pf.especialidad_principal_id into v_uspec from public.perfiles_usuario pf where pf.usuario_id = p_usuario;
  if v_uspec is null or v_uspec <> e.especialidad_id then
    return query select false, 'SPECIALTY_MISMATCH'::text, e.tipo_examen, e.tipo_acceso, e.demo_disponible, e.especialidad_id, false;
    return;
  end if;
  select c.permite_rafagas, c.permite_razonados, c.permite_inmersivos, c.permite_demos into caps
    from public.capacidades_especialidad c where c.especialidad_id = e.especialidad_id;
  if not found then
    caps.permite_rafagas := true; caps.permite_razonados := true; caps.permite_inmersivos := true; caps.permite_demos := true;
  end if;
  v_allows := case e.tipo_examen when 'rafaga' then caps.permite_rafagas when 'razonado' then caps.permite_razonados when 'inmersivo' then caps.permite_inmersivos else false end;
  if not v_allows then
    return query select false, 'CAPABILITY_DISABLED'::text, e.tipo_examen, e.tipo_acceso, e.demo_disponible, e.especialidad_id, caps.permite_demos;
    return;
  end if;
  return query select true, null::text, e.tipo_examen, e.tipo_acceso, e.demo_disponible, e.especialidad_id, caps.permite_demos;
end;
$$;
revoke all on function private.exam_gate(uuid, uuid) from public, anon, authenticated;

create or replace function private.exam_access_verdict(p_usuario uuid, p_examen uuid, p_now timestamptz default now())
returns table(state text, reason text, unlimited_grant boolean)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  g record;
  v_grant boolean;
  v_unlimited boolean;
  v_covered boolean;
begin
  select * into g from private.exam_gate(p_usuario, p_examen);
  if not found then
    return;
  end if;
  if not g.ok then
    state := 'LOCKED'; reason := g.reason; unlimited_grant := false;
    return next;
    return;
  end if;
  select exists (select 1 from public.contenido_desbloqueado_usuario x
                  where x.usuario_id = p_usuario and x.examen_id = p_examen and x.valido_desde <= p_now and (x.valido_hasta is null or x.valido_hasta > p_now)),
         exists (select 1 from public.contenido_desbloqueado_usuario x
                  where x.usuario_id = p_usuario and x.examen_id = p_examen and x.valido_desde <= p_now and x.valido_hasta is null)
    into v_grant, v_unlimited;
  unlimited_grant := v_unlimited;
  -- the plan covers a RAFAGA from nivel 1 and a RAZONADO from nivel 2; an immersive only through a grant (purchase, gift, quota). An unresolved plan covers nothing.
  select exists (select 1 from private.user_plan(p_usuario, p_now) up
                  where up.subscribed and up.resolved
                    and ((g.tipo_examen = 'rafaga' and up.nivel >= 1) or (g.tipo_examen = 'razonado' and up.nivel >= 2)))
    into v_covered;
  if g.tipo_acceso = 'gratuito' then
    state := 'FREE'; reason := null;
  elsif v_grant then
    state := 'GRANTED'; reason := null;
  elsif v_covered then
    state := 'SUBSCRIPTION'; reason := null;
  elsif g.demo_disponible and g.permite_demos then
    state := 'DEMO'; reason := 'DEMO_ONLY';
  else
    state := 'LOCKED'; reason := 'NOT_ENTITLED';
  end if;
  return next;
end;
$$;
revoke all on function private.exam_access_verdict(uuid, uuid, timestamptz) from public, anon, authenticated;

-- 5. Quota rows: dedicated motivo + the subscription cycle they belong to.
alter table public.contenido_desbloqueado_usuario add column if not exists cupo_suscripcion_inicio timestamptz;
alter table public.contenido_desbloqueado_usuario drop constraint if exists contenido_desbloqueado_usuario_motivo_check;
alter table public.contenido_desbloqueado_usuario add constraint contenido_desbloqueado_usuario_motivo_check
  check ((motivo = any (array['premium'::text, 'compra'::text, 'regalo'::text, 'admin'::text, 'promocion'::text, 'plan_cupo'::text])));
alter table public.contenido_desbloqueado_usuario drop constraint if exists contenido_desbloqueado_usuario_plan_cupo_check;
alter table public.contenido_desbloqueado_usuario add constraint contenido_desbloqueado_usuario_plan_cupo_check
  check ((motivo = 'plan_cupo') = (cupo_suscripcion_inicio is not null) and (motivo <> 'plan_cupo' or valido_hasta is not null)) not valid;
create unique index if not exists ux_contenido_desbloqueado_plan_cupo on public.contenido_desbloqueado_usuario (usuario_id, examen_id) where motivo = 'plan_cupo';

-- only the quota function (and the lifecycle trigger) may write a plan_cupo row
create or replace function private.tg_contenido_desbloqueado_plan_cupo()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.motivo = 'plan_cupo' and coalesce(current_setting('oc.plan_cupo_writer', true), '') <> 'on' then
    raise exception using errcode = '42501', message = 'PLAN_CUPO_WRITER_ONLY';
  end if;
  return new;
end;
$$;
revoke all on function private.tg_contenido_desbloqueado_plan_cupo() from public, anon, authenticated;
drop trigger if exists trg_contenido_desbloqueado_plan_cupo on public.contenido_desbloqueado_usuario;
create trigger trg_contenido_desbloqueado_plan_cupo before insert or update on public.contenido_desbloqueado_usuario
  for each row execute function private.tg_contenido_desbloqueado_plan_cupo();

create or replace function private.plan_quota_used(p_usuario uuid, p_inicio timestamptz, p_now timestamptz)
returns integer
language sql
stable
security definer
set search_path = ''
as $$
  select count(*)::integer from public.contenido_desbloqueado_usuario g
   where g.usuario_id = p_usuario and g.motivo = 'plan_cupo' and g.cupo_suscripcion_inicio is not distinct from p_inicio
     and g.valido_desde <= p_now and g.valido_hasta > p_now;
$$;
revoke all on function private.plan_quota_used(uuid, timestamptz, timestamptz) from public, anon, authenticated;

create or replace function public.app_choose_plan_exam(p_usuario uuid, p_examen uuid)
returns table(outcome text, examen_id uuid, titulo text, desde timestamptz, hasta timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  v_now timestamptz;
  g record;
  v_exam record;
  v_row public.contenido_desbloqueado_usuario%rowtype;
  v_plan record;
  v_total integer;
  v_used integer;
  v_state text;
begin
  if p_usuario is null or p_examen is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  -- ONE lock order everywhere: the wallet advisory lock FIRST (so a choose, a purchase and a gift of the same exam serialise).
  perform pg_advisory_xact_lock(hashtextextended('wallet:' || p_usuario::text, 0));
  v_now := clock_timestamp();  -- DB clock, after the lock
  select x.id, x.titulo, x.activo, x.publicado_en into v_exam from public.examenes x where x.id = p_examen;
  if not found or not v_exam.activo or v_exam.publicado_en is null or v_exam.publicado_en > v_now then
    raise exception using errcode = 'P0001', message = 'EXAM_NOT_FOUND';
  end if;
  select * into g from private.exam_gate(p_usuario, p_examen);
  if not found or not g.ok then
    raise exception using errcode = 'P0001', message = 'EXAM_NOT_FOUND';  -- same answer as an unknown exam: no probing of other specialties
  end if;
  if g.tipo_examen <> 'inmersivo' then
    raise exception using errcode = 'P0001', message = 'EXAM_NOT_IMMERSIVE';
  end if;
  -- already held through the quota: idempotent
  select c.* into v_row from public.contenido_desbloqueado_usuario c
   where c.usuario_id = p_usuario and c.examen_id = p_examen and c.motivo = 'plan_cupo' and c.valido_desde <= v_now and c.valido_hasta > v_now;
  if found then
    return query select 'EXISTS'::text, p_examen, v_exam.titulo, v_row.valido_desde, v_row.valido_hasta;
    return;
  end if;
  -- already openable (free exam, purchase, gift): nothing to spend
  select v.state into v_state from private.exam_access_verdict(p_usuario, p_examen, v_now) v;
  if v_state in ('FREE', 'GRANTED', 'SUBSCRIPTION') then
    raise exception using errcode = 'P0001', message = 'ALREADY_ACCESSIBLE', detail = format('{"state":"%s"}', v_state);
  end if;
  -- plain read of the subscription AFTER the lock (no row locks)
  select * into v_plan from private.user_plan(p_usuario, v_now);
  if not found or not v_plan.subscribed or not v_plan.resolved or v_plan.fin_en is null then
    raise exception using errcode = 'P0001', message = 'PLAN_QUOTA_NONE';
  end if;
  v_total := coalesce(private.plan_benefit_int(v_plan.plan_id, 'inmersivos_incluidos', 0, private.config_int('max_inmersivos_incluidos', 4)), 0);
  if v_total <= 0 then
    raise exception using errcode = 'P0001', message = 'PLAN_QUOTA_NONE';
  end if;
  v_used := private.plan_quota_used(p_usuario, v_plan.inicio_en, v_now);
  if v_used >= v_total then
    raise exception using errcode = 'P0001', message = 'PLAN_QUOTA_FULL';
  end if;
  perform set_config('oc.plan_cupo_writer', 'on', true);
  insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta, cupo_suscripcion_inicio)
  values (p_usuario, p_examen, 'plan_cupo', v_now, v_plan.fin_en, coalesce(v_plan.inicio_en, 'epoch'::timestamptz))
  on conflict (usuario_id, examen_id) where motivo = 'plan_cupo'
  do update set valido_desde = excluded.valido_desde, valido_hasta = excluded.valido_hasta, cupo_suscripcion_inicio = excluded.cupo_suscripcion_inicio
  returning * into v_row;
  perform set_config('oc.plan_cupo_writer', 'off', true);
  insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
  values (p_usuario, 'plan_cupo_elegido', 'contenido_desbloqueado_usuario', v_row.id,
          jsonb_build_object('examen_id', p_examen, 'plan', v_plan.plan_codigo, 'hasta', v_row.valido_hasta, 'cupo_total', v_total));
  return query select 'CREATED'::text, p_examen, v_exam.titulo, v_row.valido_desde, v_row.valido_hasta;
end;
$$;
revoke all on function public.app_choose_plan_exam(uuid, uuid) from public, anon, authenticated;
grant execute on function public.app_choose_plan_exam(uuid, uuid) to service_role;

-- 6. Lifecycle of the quota rows follows the subscription projection (C-A6).
create or replace function private.tg_estado_premium_cupo()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_now timestamptz;
  v_new boolean;
  v_old boolean;
  v_plan_id uuid;
  v_total integer;
begin
  v_now := clock_timestamp();
  v_new := private.premium_active(new.es_premium, new.estado_suscripcion, new.fin_en, v_now);
  v_old := tg_op = 'UPDATE' and private.premium_active(old.es_premium, old.estado_suscripcion, old.fin_en, v_now);
  if v_new then
    if not v_old or (new.fin_en is not distinct from old.fin_en and new.inicio_en is not distinct from old.inicio_en and new.producto_codigo is not distinct from old.producto_codigo) then
      return new;  -- nothing renewed; a NEW subscription after a lapse starts with an empty quota (rows of the old one stay expired)
    end if;
    if not exists (select 1 from public.contenido_desbloqueado_usuario g where g.usuario_id = new.usuario_id and g.motivo = 'plan_cupo' and g.valido_hasta > v_now) then
      return new;
    end if;
    perform pg_advisory_xact_lock(hashtextextended('wallet:' || new.usuario_id::text, 0));
    select pc.plan_id into v_plan_id from public.productos_comerciales pc where pc.codigo = new.producto_codigo;
    v_total := coalesce(private.plan_benefit_int(v_plan_id, 'inmersivos_incluidos', 0, private.config_int('max_inmersivos_incluidos', 4)), 0);
    perform set_config('oc.plan_cupo_writer', 'on', true);
    -- renewal / continuous subscription: the oldest N active choices (N = the CURRENT quota) are re-bound to the cycle and extended, never shortened
    update public.contenido_desbloqueado_usuario g
       set cupo_suscripcion_inicio = coalesce(new.inicio_en, 'epoch'::timestamptz), valido_hasta = greatest(g.valido_hasta, new.fin_en)
     where g.id in (select k.id from public.contenido_desbloqueado_usuario k
                     where k.usuario_id = new.usuario_id and k.motivo = 'plan_cupo' and k.valido_hasta > v_now
                     order by k.valido_desde, k.id limit v_total);
    perform set_config('oc.plan_cupo_writer', 'off', true);
  else
    -- terminal / lapsed: deny at once and close the choices (an expiry with no row update just runs out at valido_hasta)
    if exists (select 1 from public.contenido_desbloqueado_usuario g where g.usuario_id = new.usuario_id and g.motivo = 'plan_cupo' and g.valido_hasta > v_now) then
      perform pg_advisory_xact_lock(hashtextextended('wallet:' || new.usuario_id::text, 0));
      perform set_config('oc.plan_cupo_writer', 'on', true);
      update public.contenido_desbloqueado_usuario g set valido_hasta = least(g.valido_hasta, v_now)
       where g.usuario_id = new.usuario_id and g.motivo = 'plan_cupo' and g.valido_hasta > v_now;
      perform set_config('oc.plan_cupo_writer', 'off', true);
    end if;
  end if;
  return new;
end;
$$;
revoke all on function private.tg_estado_premium_cupo() from public, anon, authenticated;
drop trigger if exists trg_estado_premium_cupo on public.estado_premium_usuario;
create trigger trg_estado_premium_cupo after insert or update on public.estado_premium_usuario
  for each row execute function private.tg_estado_premium_cupo();

-- 7. Subscription projection (MAIN function; replaced only where suscripciones exists): cancelada keeps access until fin_en, terminal motives deny at once.
do $migration$
begin
  if to_regclass('public.suscripciones') is not null then
    alter table public.suscripciones add column if not exists fin_motivo text;
    alter table public.suscripciones drop constraint if exists suscripciones_fin_motivo_check;
    alter table public.suscripciones add constraint suscripciones_fin_motivo_check
      check (fin_motivo is null or fin_motivo in ('usuario_cancelo', 'expiro', 'reembolso', 'revocada', 'contracargo', 'fallo_cobro'));
    execute $fn$
create or replace function public.sincronizar_estado_premium_usuario()
returns trigger
language plpgsql
security definer
set search_path = ''
as $body$
declare
  v_usuario_id uuid;
  v_s record;
begin
  v_usuario_id := coalesce(new.usuario_id, old.usuario_id);
  -- the best subscription that gives access NOW: activa / periodo_prueba (end unknown or future) or cancelada with its paid period left;
  -- a terminal motive (reembolso, revocada, contracargo, fallo_cobro) never gives access whatever its dates say
  select s.estado, s.producto_codigo, s.proveedor, s.inicio_en, s.fin_en, s.renovacion_en into v_s
    from public.suscripciones s
   where s.usuario_id = v_usuario_id
     and coalesce(s.fin_motivo, 'x') not in ('reembolso', 'revocada', 'contracargo', 'fallo_cobro')
     and ((s.estado in ('activa', 'periodo_prueba') and (s.fin_en is null or s.fin_en > clock_timestamp()))
          or (s.estado = 'cancelada' and s.fin_en is not null and s.fin_en > clock_timestamp()))
   order by case s.estado when 'activa' then 1 when 'periodo_prueba' then 2 else 3 end, s.renovacion_en desc nulls last, s.fin_en desc nulls last
   limit 1;
  if found then
    insert into public.estado_premium_usuario (usuario_id, es_premium, estado_suscripcion, producto_codigo, proveedor, inicio_en, fin_en, renovacion_en, actualizado_en)
    values (v_usuario_id, true, v_s.estado, v_s.producto_codigo, v_s.proveedor, v_s.inicio_en, v_s.fin_en, v_s.renovacion_en, now())
    on conflict (usuario_id) do update set es_premium = true, estado_suscripcion = excluded.estado_suscripcion, producto_codigo = excluded.producto_codigo,
      proveedor = excluded.proveedor, inicio_en = excluded.inicio_en, fin_en = excluded.fin_en, renovacion_en = excluded.renovacion_en, actualizado_en = now();
  else
    insert into public.estado_premium_usuario (usuario_id, es_premium, estado_suscripcion, producto_codigo, proveedor, inicio_en, fin_en, renovacion_en, actualizado_en)
    values (v_usuario_id, false, 'sin_suscripcion', null, null, null, null, null, now())
    on conflict (usuario_id) do update set es_premium = false, estado_suscripcion = 'sin_suscripcion', producto_codigo = null, proveedor = null,
      inicio_en = null, fin_en = null, renovacion_en = null, actualizado_en = now();
  end if;
  return coalesce(new, old);
end;
$body$
$fn$;
    revoke all on function public.sincronizar_estado_premium_usuario() from public, anon, authenticated;
    grant execute on function public.sincronizar_estado_premium_usuario() to service_role;
    if not exists (select 1 from pg_trigger t where t.tgrelid = 'public.suscripciones'::regclass and not t.tgisinternal and t.tgfoid = 'public.sincronizar_estado_premium_usuario()'::regprocedure) then
      create trigger trg_suscripciones_sync_estado_premium after insert or update or delete on public.suscripciones
        for each row execute function public.sincronizar_estado_premium_usuario();
    end if;
  end if;
end
$migration$;

commit;
