-- V1 NIGHT-C: service-role RPCs the Control panel (E6) was waiting for. Contract: E1-V1-NIGHT-C.md. DEVELOP_CHECK: develop read-only; audit rows are only ever INSERTed (develop makes auditoria_log append-only).
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Requires 20261022100400 (orders), 20261022101300 (official rules), 20261022101800 (referral), 20261022101900 (foro).
-- Every write takes p_actor, which must be a real admin account (private.require_admin_actor, one error for unknown / null / non-admin) and lands in the audit row INSIDE the same transaction.
-- admin_referral_list, admin_foro_bloqueos_list, admin_foro_restricciones_list: reads (service role only), handles only, no audit.
-- admin_foro_bloqueos_add/remove, admin_foro_restriccion_levantar, admin_set_exam_question_flags, admin_set_exam_grading_rules, admin_payment_order_resolve: audited writes.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate)
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.admin_payment_order_resolve(uuid, uuid, text, text, boolean);
-- create or replace function private.tg_compras_usuario_order_machine()
-- returns trigger
-- language plpgsql
-- set search_path = ''
-- as $$
-- begin
--   if new.estado is distinct from old.estado and not (
--        (old.estado = 'pendiente'   and new.estado in ('pagada', 'fallida', 'cancelada', 'expirada', 'en_revision'))
--     or (old.estado = 'expirada'    and new.estado in ('pagada', 'en_revision'))
--     or (old.estado = 'pagada'      and new.estado in ('reembolsada', 'en_revision'))
--     or (old.estado in ('fallida', 'cancelada', 'reembolsada') and new.estado = 'en_revision')
--   ) then
--     raise exception using errcode = '55000', message = 'ORDER_STATE_TRANSITION_FORBIDDEN', hint = old.estado || ' -> ' || new.estado;
--   end if;
--   if old.cantidad_tickets is not null and (
--        new.cantidad_tickets is distinct from old.cantidad_tickets or new.precio_centimos is distinct from old.precio_centimos
--     or new.moneda is distinct from old.moneda or new.producto_codigo is distinct from old.producto_codigo
--     or new.usuario_id is distinct from old.usuario_id or new.proveedor is distinct from old.proveedor
--     or new.idempotency_key is distinct from old.idempotency_key or new.huella_solicitud is distinct from old.huella_solicitud
--     or new.expira_en is distinct from old.expira_en
--   ) then
--     raise exception using errcode = '55000', message = 'ORDER_FROZEN';
--   end if;
--   if old.identificador_transaccion is not null and new.identificador_transaccion is distinct from old.identificador_transaccion then
--     raise exception using errcode = '55000', message = 'ORDER_FROZEN';
--   end if;
--   return new;
-- end;
-- $$;
-- drop function if exists public.admin_set_exam_grading_rules(uuid, uuid, text, integer, integer, boolean, text);
-- drop function if exists public.admin_set_exam_question_flags(uuid, uuid, uuid, boolean, text, boolean, integer);
-- drop function if exists public.admin_foro_restriccion_levantar(uuid, uuid);
-- drop function if exists public.admin_foro_restricciones_list();
-- drop function if exists public.admin_foro_bloqueos_remove(uuid, text);
-- drop function if exists public.admin_foro_bloqueos_add(uuid, text);
-- drop function if exists public.admin_foro_bloqueos_list();
-- drop function if exists public.admin_referral_list(text, text, integer);
-- drop function if exists private.admin_reason(text, integer, integer);
-- drop function if exists private.require_admin_actor(uuid);
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'control_rpc_service_role_only:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute') or not has_function_privilege('service_role', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('public.admin_referral_list(text,text,integer)'), ('public.admin_foro_bloqueos_list()'), ('public.admin_foro_bloqueos_add(uuid,text)'), ('public.admin_foro_bloqueos_remove(uuid,text)'),
--                ('public.admin_foro_restricciones_list()'), ('public.admin_foro_restriccion_levantar(uuid,uuid)'), ('public.admin_set_exam_question_flags(uuid,uuid,uuid,boolean,text,boolean,integer)'),
--                ('public.admin_set_exam_grading_rules(uuid,uuid,text,integer,integer,boolean,text)'), ('public.admin_payment_order_resolve(uuid,uuid,text,text,boolean)')) f(n)
-- union all
-- select 'control_private_helpers_closed:' || f.n,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute') then 1 else 0 end)::bigint
--   from (values ('private.require_admin_actor(uuid)'), ('private.admin_reason(text,integer,integer)')) f(n);
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('public.app_refund_payment_order(text,text,uuid)') is null or to_regprocedure('public.admin_foro_moderate(uuid,text,uuid,text,integer,text)') is null
     or to_regprocedure('public.admin_referral_revoke(uuid,uuid,text)') is null or to_regprocedure('private.official_rules_problem(uuid)') is null
     or to_regclass('public.foro_bloqueos') is null or to_regclass('public.restricciones_foro') is null then
    raise exception using errcode = 'P0001', message = 'V1_CONTROL_RPCS_PREFLIGHT', hint = 'apply 20261022100400, 20261022101300, 20261022101800 and 20261022101900 first';
  end if;
end
$$;

-- ---- shared guards (private)
-- the actor of every Control write must be a real admin account (same role set as public.es_admin()); the answer is the same for unknown, null and non-admin (no oracle)
create or replace function private.require_admin_actor(p_actor uuid)
returns void
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if p_actor is null or not exists (select 1 from public.roles_usuario ru join public.roles_app ra on ra.id = ru.rol_id where ru.usuario_id = p_actor and ra.codigo in ('superadmin', 'admin')) then
    raise exception using errcode = 'P0001', message = 'ADMIN_ACTOR_INVALID';
  end if;
end;
$$;
revoke all on function private.require_admin_actor(uuid) from public, anon, authenticated;

-- a free-text reason / note: NFC, no control, bidi or zero-width characters, blanks collapsed, trimmed; too short or too long is REASON_INVALID (never silently truncated)
create or replace function private.admin_reason(p_text text, p_min integer, p_max integer)
returns text
language plpgsql
immutable
set search_path = ''
as $$
declare v text;
begin
  if p_text is null then
    if p_min > 0 then raise exception using errcode = 'P0001', message = 'REASON_INVALID'; end if;
    return null;
  end if;
  v := normalize(p_text, nfc);
  v := regexp_replace(v, E'[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f-\\x9f]', '', 'g');
  v := regexp_replace(v, E'[\\u200b-\\u200f\\u202a-\\u202e\\u2060-\\u2069\\ufeff\\u00ad]', '', 'g');
  v := regexp_replace(regexp_replace(v, E'\\s+', ' ', 'g'), E'^ | $', '', 'g');
  if char_length(v) < p_min or char_length(v) > p_max then raise exception using errcode = 'P0001', message = 'REASON_INVALID'; end if;
  return nullif(v, '');
end;
$$;
revoke all on function private.admin_reason(text, integer, integer) from public, anon, authenticated;

-- ---- 1. referral list (read; service role; handles only, never an email or a real name)
create or replace function public.admin_referral_list(p_estado text, p_cursor text, p_limite integer)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_lim integer := least(greatest(coalesce(p_limite, 20), 1), 100);
  v_ts timestamptz; v_id uuid;
  v_items jsonb; v_next text; v_n integer; v_resumen jsonb;
begin
  if p_estado is not null and p_estado not in ('registrada', 'email_verificado', 'diferida', 'premiada', 'rechazada', 'revocada', 'error') then
    raise exception using errcode = 'P0001', message = 'REFERRAL_FILTER_INVALID';
  end if;
  if p_cursor is not null then
    begin
      v_ts := split_part(convert_from(decode(translate(p_cursor, '-_', '+/'), 'base64'), 'utf8'), '|', 1)::timestamptz;
      v_id := split_part(convert_from(decode(translate(p_cursor, '-_', '+/'), 'base64'), 'utf8'), '|', 2)::uuid;
    exception when others then
      raise exception using errcode = 'P0001', message = 'CURSOR_INVALID';
    end;
  end if;
  select coalesce(jsonb_agg(x.item order by x.creado_en desc, x.id desc), '[]'::jsonb), count(*),
         (array_agg(encode(convert_to(x.creado_en::text || '|' || x.id::text, 'utf8'), 'base64') order by x.creado_en asc, x.id asc))[1]
    into v_items, v_n, v_next
    from (select i.id, i.creado_en, jsonb_build_object(
                   'id', i.id, 'estado', i.estado, 'creadoEn', i.creado_en,
                   'invitadorHandle', inv.username, 'invitadoHandle', ied.username,
                   'pagoInvitador', i.pago_invitador, 'pagoInvitado', i.pago_invitado, 'intentosPago', i.intentos_pago,
                   'ultimoError', left(i.ultimo_error, 200), 'diferidaHasta', i.diferida_hasta, 'premiadaEn', i.premiada_en, 'motivo', left(i.motivo, 200),
                   'marcas', coalesce((select jsonb_object_agg(f.key, true) from jsonb_each(i.flags) f where f.value = 'true'::jsonb), '{}'::jsonb)) as item
            from public.invitaciones i
            left join public.perfiles_usuario inv on inv.usuario_id = i.invitador_id
            left join public.perfiles_usuario ied on ied.usuario_id = i.invitado_id
           where (p_estado is null or i.estado = p_estado) and (v_ts is null or (i.creado_en, i.id) < (v_ts, v_id))
           order by i.creado_en desc, i.id desc limit v_lim) x;
  select coalesce(jsonb_object_agg(c.estado, c.n), '{}'::jsonb) into v_resumen from (select estado, count(*) as n from public.invitaciones group by estado) c;
  return jsonb_build_object('items', v_items, 'nextCursor', case when v_n >= v_lim then translate(replace(v_next, E'\n', ''), '+/', '-_') end, 'resumen', v_resumen);
end;
$$;
revoke all on function public.admin_referral_list(text, text, integer) from public, anon, authenticated;
grant execute on function public.admin_referral_list(text, text, integer) to service_role;

-- ---- 2. forum block list (LITERAL substring patterns, lower case: private.foro_has_link uses position(), never LIKE or regex) and active mutes
create or replace function public.admin_foro_bloqueos_list()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(jsonb_agg(b.patron order by b.patron), '[]'::jsonb) from public.foro_bloqueos b
$$;
revoke all on function public.admin_foro_bloqueos_list() from public, anon, authenticated;
grant execute on function public.admin_foro_bloqueos_list() to service_role;

create or replace function public.admin_foro_bloqueos_add(p_actor uuid, p_patron text)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare v text; v_rows integer;
begin
  perform private.require_admin_actor(p_actor);
  begin v := lower(private.admin_reason(p_patron, 2, 100)); exception when others then raise exception using errcode = 'P0001', message = 'FORO_BLOCK_INVALID'; end;
  perform pg_advisory_xact_lock(hashtextextended('foro_bloqueos', 0));
  if (select count(*) from public.foro_bloqueos) >= 500 and not exists (select 1 from public.foro_bloqueos where patron = v) then
    raise exception using errcode = 'P0001', message = 'FORO_BLOCK_LIMIT';
  end if;
  insert into public.foro_bloqueos (patron) values (v) on conflict (patron) do nothing;
  get diagnostics v_rows = row_count;
  if v_rows = 1 then
    insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues) values (p_actor, 'foro_bloqueo_agregado', 'foro_bloqueos', null, jsonb_build_object('patron', v));
  end if;
  return jsonb_build_object('patron', v, 'creado', v_rows = 1);
end;
$$;
revoke all on function public.admin_foro_bloqueos_add(uuid, text) from public, anon, authenticated;
grant execute on function public.admin_foro_bloqueos_add(uuid, text) to service_role;

create or replace function public.admin_foro_bloqueos_remove(p_actor uuid, p_patron text)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare v text; v_rows integer;
begin
  perform private.require_admin_actor(p_actor);
  begin v := lower(private.admin_reason(p_patron, 2, 100)); exception when others then raise exception using errcode = 'P0001', message = 'FORO_BLOCK_INVALID'; end;
  delete from public.foro_bloqueos where patron = v;
  get diagnostics v_rows = row_count;
  if v_rows = 1 then
    insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, antes) values (p_actor, 'foro_bloqueo_quitado', 'foro_bloqueos', null, jsonb_build_object('patron', v));
  end if;
  return jsonb_build_object('patron', v, 'eliminado', v_rows = 1);
end;
$$;
revoke all on function public.admin_foro_bloqueos_remove(uuid, text) from public, anon, authenticated;
grant execute on function public.admin_foro_bloqueos_remove(uuid, text) to service_role;

create or replace function public.admin_foro_restricciones_list()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(jsonb_agg(jsonb_build_object('usuarioId', r.usuario_id, 'handle', pf.username, 'hasta', r.hasta, 'motivo', left(r.motivo, 200), 'creadoEn', r.creado_en) order by r.hasta desc, r.usuario_id), '[]'::jsonb)
    from (select * from public.restricciones_foro where hasta > clock_timestamp() order by hasta desc, usuario_id limit 200) r
    left join public.perfiles_usuario pf on pf.usuario_id = r.usuario_id
$$;
revoke all on function public.admin_foro_restricciones_list() from public, anon, authenticated;
grant execute on function public.admin_foro_restricciones_list() to service_role;

create or replace function public.admin_foro_restriccion_levantar(p_actor uuid, p_usuario uuid)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare v_rows integer; v_prev record;
begin
  perform private.require_admin_actor(p_actor);
  if p_usuario is null then raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND'; end if;
  select r.hasta, r.motivo into v_prev from public.restricciones_foro r where r.usuario_id = p_usuario for update;
  delete from public.restricciones_foro where usuario_id = p_usuario;
  get diagnostics v_rows = row_count;
  if v_rows = 1 then
    insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, antes) values (p_actor, 'foro_restriccion_levantada', 'restricciones_foro', p_usuario, jsonb_build_object('hasta', v_prev.hasta, 'motivo', left(v_prev.motivo, 200)));
  end if;
  return jsonb_build_object('levantada', v_rows = 1);
end;
$$;
revoke all on function public.admin_foro_restriccion_levantar(uuid, uuid) from public, anon, authenticated;
grant execute on function public.admin_foro_restriccion_levantar(uuid, uuid) to service_role;

-- ---- 3. annulled / reserve flags of ONE question of an exam, one transaction (FA37 rules: demo never reserve nor annulled, reserves only in official exams, reserves keep orders 1..R and sit after the last main)
create or replace function public.admin_set_exam_question_flags(
  p_actor uuid, p_examen uuid, p_pregunta uuid, p_anulada boolean, p_motivo text, p_es_reserva boolean, p_orden_reserva integer)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  e record;
  r public.preguntas_examen%rowtype;
  v_motivo text;
  v_otros integer;
  v_orden integer;
  v_maxmain integer;
  v_problem text;
  v_before jsonb;
  v_became_main boolean;
begin
  perform private.require_admin_actor(p_actor);
  if p_examen is null or p_pregunta is null or p_anulada is null or p_es_reserva is null then
    raise exception using errcode = 'P0001', message = 'FLAGS_INPUT_INVALID';
  end if;
  v_motivo := private.admin_reason(p_motivo, 0, 500);
  if v_motivo is not null and not p_anulada then
    raise exception using errcode = 'P0001', message = 'FLAGS_REASON_REQUIRES_ANNULLED';
  end if;
  select x.es_oficial, x.publicado_en is not null as publicado into e from public.examenes x where x.id = p_examen for no key update;
  if not found then
    raise exception using errcode = 'P0001', message = 'EXAM_NOT_FOUND';
  end if;
  select * into r from public.preguntas_examen pe where pe.examen_id = p_examen and pe.pregunta_id = p_pregunta for update;
  if not found then
    raise exception using errcode = 'P0001', message = 'EXAM_QUESTION_NOT_FOUND';
  end if;
  if p_es_reserva and not e.es_oficial then
    raise exception using errcode = 'P0001', message = 'FLAGS_RESERVE_REQUIRES_OFFICIAL_EXAM';
  end if;
  if r.es_demo and (p_anulada or p_es_reserva) then
    raise exception using errcode = 'P0001', message = 'DEMO_CONFLICT';
  end if;
  if not p_es_reserva and p_orden_reserva is not null then
    raise exception using errcode = 'P0001', message = 'FLAGS_ORDER_INVALID';
  end if;
  select count(*) into v_otros from public.preguntas_examen pe where pe.examen_id = p_examen and pe.es_reserva and pe.pregunta_id <> p_pregunta;
  if p_es_reserva then
    v_orden := coalesce(p_orden_reserva, v_otros + 1);
    if v_orden < 1 or v_orden > v_otros + 1 then
      raise exception using errcode = 'P0001', message = 'FLAGS_ORDER_INVALID';
    end if;
  end if;
  v_before := jsonb_build_object('anulada', r.anulada, 'motivo', r.motivo_anulacion, 'esReserva', r.es_reserva, 'ordenReserva', r.orden_reserva);
  if r.anulada = p_anulada and r.motivo_anulacion is not distinct from v_motivo and r.es_reserva = p_es_reserva and (not p_es_reserva or r.orden_reserva = v_orden) then
    return jsonb_build_object('changed', false, 'problema', private.official_rules_problem(p_examen), 'avisos', to_jsonb(private.official_rules_warnings(p_examen)));
  end if;

  v_became_main := not p_es_reserva and r.es_reserva;
  -- 1) the flags of this row (a provisional high order keeps the partial unique index quiet)
  update public.preguntas_examen pe
     set anulada = p_anulada, motivo_anulacion = v_motivo, es_reserva = p_es_reserva, orden_reserva = case when p_es_reserva then 5000 end
   where pe.examen_id = p_examen and pe.pregunta_id = p_pregunta;
  -- 2) reserve orders: 1..R with this question at its requested place (two statements: the unique index is not deferrable)
  update public.preguntas_examen pe set orden_reserva = pe.orden_reserva + 1000 where pe.examen_id = p_examen and pe.es_reserva;
  update public.preguntas_examen pe set orden_reserva = o.ord
    from (select q.pregunta_id, row_number() over (order by case when q.pregunta_id = p_pregunta then v_orden - 0.5 else (q.orden_reserva - 1000)::numeric end) as ord
            from public.preguntas_examen q where q.examen_id = p_examen and q.es_reserva) o
   where pe.examen_id = p_examen and pe.pregunta_id = o.pregunta_id;
  -- 3) positions: mains keep theirs (a demo question never moves); a question that just became a main goes right after the last main; every reserve sits after the last main in reserve order
  select coalesce(max(pe.posicion), 0) into v_maxmain from public.preguntas_examen pe where pe.examen_id = p_examen and not pe.es_reserva and not (v_became_main and pe.pregunta_id = p_pregunta);
  update public.preguntas_examen pe set posicion = t.pos
    from (select q.pregunta_id,
                 case when not q.es_reserva then v_maxmain + 1
                      else v_maxmain + (case when v_became_main then 1 else 0 end) + row_number() over (partition by q.es_reserva order by q.orden_reserva) end as pos
            from public.preguntas_examen q
           where q.examen_id = p_examen and (q.es_reserva or (v_became_main and q.pregunta_id = p_pregunta))) t
   where pe.examen_id = p_examen and pe.pregunta_id = t.pregunta_id and pe.posicion is distinct from t.pos;
  v_problem := private.official_rules_problem(p_examen);
  if v_problem is not null and (e.publicado or exists (select 1 from public.preguntas_examen pe where pe.examen_id = p_examen and pe.es_reserva)) then
    raise exception using errcode = 'P0001', message = 'FLAGS_OFFICIAL_RULES_INVALID', hint = v_problem;
  end if;
  insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, antes, despues)
  values (p_actor, 'pregunta_examen_marcas_modificadas', 'preguntas_examen', p_examen, v_before || jsonb_build_object('pregunta', p_pregunta),
          jsonb_build_object('pregunta', p_pregunta, 'anulada', p_anulada, 'motivo', v_motivo, 'esReserva', p_es_reserva, 'ordenReserva', case when p_es_reserva then v_orden end));
  return jsonb_build_object('changed', true, 'problema', v_problem, 'avisos', to_jsonb(private.official_rules_warnings(p_examen)));
end;
$$;
revoke all on function public.admin_set_exam_question_flags(uuid, uuid, uuid, boolean, text, boolean, integer) from public, anon, authenticated;
grant execute on function public.admin_set_exam_question_flags(uuid, uuid, uuid, boolean, text, boolean, integer) to service_role;

-- ---- 4. grading rules of an exam in ONE statement; the actor (and the reason) reach the audit trigger of 20261022101300 through transaction-local settings (DEBT-159)
create or replace function public.admin_set_exam_grading_rules(
  p_actor uuid, p_examen uuid, p_regla_anuladas text, p_penalizacion_num integer, p_penalizacion_den integer, p_penalizacion_racional boolean, p_motivo text default null)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  e public.examenes%rowtype;
  v_motivo text;
  v_changed boolean;
begin
  perform private.require_admin_actor(p_actor);
  if p_examen is null or p_penalizacion_num is null or p_penalizacion_den is null or p_penalizacion_racional is null then
    raise exception using errcode = 'P0001', message = 'GRADING_INPUT_INVALID';
  end if;
  if p_regla_anuladas is not null and p_regla_anuladas not in ('REEMPLAZO_POR_RESERVA', 'NO_PUNTUA') then
    raise exception using errcode = 'P0001', message = 'GRADING_RULE_INVALID';
  end if;
  if p_penalizacion_racional and p_penalizacion_num <> 0 then
    raise exception using errcode = 'P0001', message = 'GRADING_PENALTY_CONFLICT';
  end if;
  if p_penalizacion_den < 1 or p_penalizacion_den > 100 or p_penalizacion_num < 0 or p_penalizacion_num > p_penalizacion_den then
    raise exception using errcode = 'P0001', message = 'GRADING_PENALTY_INVALID';
  end if;
  select * into e from public.examenes x where x.id = p_examen for no key update;
  if not found then
    raise exception using errcode = 'P0001', message = 'EXAM_NOT_FOUND';
  end if;
  -- a published exam changes rules only with a reason (it affects the attempts started afterwards)
  v_motivo := private.admin_reason(p_motivo, case when e.publicado_en is not null then 3 else 0 end, 300);
  v_changed := e.regla_anuladas is distinct from p_regla_anuladas or e.penalizacion_num <> p_penalizacion_num or e.penalizacion_den <> p_penalizacion_den or e.penalizacion_racional <> p_penalizacion_racional;
  if v_changed then
    perform set_config('app.audit_actor', p_actor::text, true);
    perform set_config('app.audit_reason', coalesce(v_motivo, ''), true);
    update public.examenes x
       set regla_anuladas = p_regla_anuladas, penalizacion_num = p_penalizacion_num::smallint, penalizacion_den = p_penalizacion_den::smallint, penalizacion_racional = p_penalizacion_racional
     where x.id = p_examen;
    perform set_config('app.audit_actor', '', true);
    perform set_config('app.audit_reason', '', true);
  end if;
  return jsonb_build_object('changed', v_changed, 'reglaEfectiva', private.official_rule_effective(p_examen), 'problema', private.official_rules_problem(p_examen),
                            'avisos', to_jsonb(private.official_rules_warnings(p_examen)));
end;
$$;
revoke all on function public.admin_set_exam_grading_rules(uuid, uuid, text, integer, integer, boolean, text) from public, anon, authenticated;
grant execute on function public.admin_set_exam_grading_rules(uuid, uuid, text, integer, integer, boolean, text) to service_role;

-- ---- 5. orders in review: the state machine allows en_revision -> pagada | fallida | reembolsada ONLY inside admin_payment_order_resolve (it names the order in a transaction-local setting)
create or replace function private.tg_compras_usuario_order_machine()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.estado is distinct from old.estado and not (
       (old.estado = 'pendiente'   and new.estado in ('pagada', 'fallida', 'cancelada', 'expirada', 'en_revision'))
    or (old.estado = 'expirada'    and new.estado in ('pagada', 'en_revision'))
    or (old.estado = 'pagada'      and new.estado in ('reembolsada', 'en_revision'))
    or (old.estado in ('fallida', 'cancelada', 'reembolsada') and new.estado = 'en_revision')
    -- NIGHT-C: leaving review only through admin_payment_order_resolve, which names the order in a transaction-local setting
    or (old.estado = 'en_revision' and new.estado in ('pagada', 'fallida') and coalesce(current_setting('app.order_resolve', true), '') = old.id::text)
  ) then
    raise exception using errcode = '55000', message = 'ORDER_STATE_TRANSITION_FORBIDDEN', hint = old.estado || ' -> ' || new.estado;
  end if;
  if old.cantidad_tickets is not null and (
       new.cantidad_tickets is distinct from old.cantidad_tickets or new.precio_centimos is distinct from old.precio_centimos
    or new.moneda is distinct from old.moneda or new.producto_codigo is distinct from old.producto_codigo
    or new.usuario_id is distinct from old.usuario_id or new.proveedor is distinct from old.proveedor
    or new.idempotency_key is distinct from old.idempotency_key or new.huella_solicitud is distinct from old.huella_solicitud
    or new.expira_en is distinct from old.expira_en
  ) then
    raise exception using errcode = '55000', message = 'ORDER_FROZEN';
  end if;
  if old.identificador_transaccion is not null and new.identificador_transaccion is distinct from old.identificador_transaccion then
    raise exception using errcode = '55000', message = 'ORDER_FROZEN';
  end if;
  return new;
end;
$$;

-- Decisions (the order must be en_revision; a repeated decision is a REPLAY that writes nothing):
--   acreditar  the order was never credited: ledger-first credit of its FROZEN quantity (same idempotency key as the webhook credit, so it can never be credited twice), order -> pagada
--   confirmar  the order was credited and was put in review: it stays paid, no ledger movement
--   rechazar   the order was never credited: -> fallida, no ledger movement
--   reembolsar policy P2 through app_refund_payment_order (reverses only the unspent part, shortens the purchased access, closes refund_case); needs p_reembolsos_habilitados = true (the Ktor
--              PAYMENTS_REFUNDS_ENABLED switch) and a credited order; -> reembolsada
create or replace function public.admin_payment_order_resolve(p_actor uuid, p_orden uuid, p_decision text, p_nota text, p_reembolsos_habilitados boolean default false)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_o public.compras_usuario%rowtype;
  v_user uuid;
  v_nota text;
  v_credited boolean;
  v_outcome text;
  v_estado_prev text;
  v_rf record;
begin
  perform private.require_admin_actor(p_actor);
  if p_orden is null or p_decision is null or p_decision not in ('acreditar', 'confirmar', 'rechazar', 'reembolsar') then
    raise exception using errcode = 'P0001', message = 'ORDER_DECISION_INVALID';
  end if;
  v_nota := private.admin_reason(p_nota, 3, 300);
  select c.usuario_id into v_user from public.compras_usuario c where c.id = p_orden and c.cantidad_tickets is not null;
  if not found then
    raise exception using errcode = 'P0001', message = 'ORDER_NOT_FOUND';
  end if;
  -- ONE lock order everywhere: wallet advisory lock first, then the order row
  perform pg_advisory_xact_lock(hashtextextended('wallet:' || v_user::text, 0));
  select c.* into v_o from public.compras_usuario c where c.id = p_orden for update;
  v_credited := exists (select 1 from public.transacciones_cartera t where t.compra_id = v_o.id and t.tipo = 'pack_tickets');
  v_estado_prev := v_o.estado;

  if v_o.estado <> 'en_revision' then
    if (p_decision in ('acreditar', 'confirmar') and v_o.estado = 'pagada' and v_credited)
       or (p_decision = 'rechazar' and v_o.estado = 'fallida' and not v_credited)
       or (p_decision = 'reembolsar' and v_o.estado = 'reembolsada') then
      return jsonb_build_object('outcome', 'REPLAY', 'estado', v_o.estado, 'ordenId', v_o.id);
    end if;
    raise exception using errcode = 'P0001', message = 'ORDER_NOT_IN_REVIEW';
  end if;

  perform set_config('app.order_resolve', v_o.id::text, true);
  if p_decision = 'acreditar' then
    if v_credited then raise exception using errcode = 'P0001', message = 'ORDER_ALREADY_CREDITED'; end if;
    insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, referencia_tipo, referencia_id, compra_id, idempotency_key, metadatos)
    values (v_o.usuario_id, 'pack_tickets', v_o.cantidad_tickets, 'Compra de OposiTickets (revision)', 'orden', v_o.id, v_o.id, 'orden:' || v_o.id::text,
            jsonb_build_object('op', 'credit_order', 'proveedor', v_o.proveedor, 'resolucion_admin', true));
    update public.compras_usuario c set estado = 'pagada', pagada_en = now() where c.id = v_o.id;
    v_outcome := 'CREDITED';
  elsif p_decision = 'confirmar' then
    if not v_credited then raise exception using errcode = 'P0001', message = 'ORDER_NOT_CREDITED'; end if;
    update public.compras_usuario c set estado = 'pagada' where c.id = v_o.id;
    v_outcome := 'CONFIRMED';
  elsif p_decision = 'rechazar' then
    if v_credited then raise exception using errcode = 'P0001', message = 'ORDER_ALREADY_CREDITED'; end if;
    update public.compras_usuario c set estado = 'fallida' where c.id = v_o.id;
    v_outcome := 'REJECTED';
  else
    if not coalesce(p_reembolsos_habilitados, false) then raise exception using errcode = 'P0001', message = 'REFUNDS_DISABLED'; end if;
    if not v_credited then raise exception using errcode = 'P0001', message = 'ORDER_NOT_CREDITED'; end if;
    update public.compras_usuario c set estado = 'pagada' where c.id = v_o.id;
    select * into v_rf from public.app_refund_payment_order(v_o.proveedor, 'admin-refund:' || v_o.id::text, v_o.id);
    v_outcome := v_rf.outcome;
  end if;
  perform set_config('app.order_resolve', '', true);
  insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, antes, despues)
  values (p_actor, 'pedido_resuelto', 'compras_usuario', v_o.id, jsonb_build_object('estado', v_estado_prev, 'acreditada', v_credited),
          jsonb_build_object('decision', p_decision, 'resultado', v_outcome, 'nota', v_nota, 'cantidad_tickets', v_o.cantidad_tickets));
  return jsonb_build_object('outcome', v_outcome, 'estado', (select c.estado from public.compras_usuario c where c.id = v_o.id), 'ordenId', v_o.id,
                            'casoReembolso', exists (select 1 from public.refund_case rc where rc.compra_id = v_o.id));
end;
$$;
revoke all on function public.admin_payment_order_resolve(uuid, uuid, text, text, boolean) from public, anon, authenticated;
grant execute on function public.admin_payment_order_resolve(uuid, uuid, text, text, boolean) to service_role;

commit;
