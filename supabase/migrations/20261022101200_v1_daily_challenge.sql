-- V1 WAVE 2: DAILY CHALLENGE + RACHA + RECLAMAR (client answers 2026-10-16; E1-V1-ENGAGEMENT-DESIGN.md v2.1, E2 conditions C-E1..C-E7, C-S2). Contract: E1-V1-W2-CONTRACT.md (FA29).
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Requires 20261022100900 (config, benefit reader, user_plan) and 20261022101000 (demo set).
--
-- SECURITY FIXES on MAIN objects (structure-only read 2026-10-16): F1 registros_reto_diario was writable by every client (own rows) -> all client privileges and the policy removed; F2 the five
-- recalcular_estadisticas_* functions were executable by anon/authenticated -> EXECUTE removed (service_role only); F3 ranking_liga and preguntas_reto_diario were readable by every authenticated user
-- (future challenge questions!) -> client access removed, served by the server only; F4 anon table privileges on estadisticas_usuario, rachas_usuario, registros_reto_diario, estado_premium_usuario removed.
-- MODEL: retos_diarios gets one row per (fecha, especialidad, comunidad-or-national) instead of one per fecha (MAIN's UNIQUE(fecha) is replaced; the table has no writer and no rows in the app); a daily job (and a lazy
-- fallback on first open) creates the 3 questions per row: 1 immersive (taken from the demo-flagged set, owner GO), 1 razonado, 1 rafaga, at random. REGIONAL RULE: a question carrying a regional tag
-- (etiquetas_comunidad) is eligible only for segments of that community; national / untagged questions go everywhere. ONE row per user per Madrid day (registros_reto_diario.dia UNIQUE with usuario_id): the most
-- specific eligible row, frozen the first time it is opened that day. Answers are final, checked against the solution on the server, never feed exam statistics. Completion (3rd answer) updates the streak (single
-- writer), freezes the reward amounts from the plan benefits (ot_reto_diario, ot_bonus_racha; trial and free users = the Gratuito plan) and the aggregate bar. The reward is PAID by the claim.
-- EARN CAP (E2): public.app_credit_free_ot is the ONLY writer of the free-earn ledger types (reto_diario, bonus_racha, referido; reporte_validado also from the admin function): wallet lock first, idempotent by key,
-- per-user Madrid-day cap (config tope_ot_gratis_dia). A guard trigger refuses those types otherwise.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate)
-- ROLLBACK-BEGIN
-- begin;
-- drop trigger if exists trg_ledger_free_earn_guard on public.transacciones_cartera;
-- drop function if exists private.tg_ledger_free_earn_guard();
-- drop function if exists public.app_credit_free_ot(uuid, text, integer, text, uuid, text, jsonb);
-- drop function if exists public.app_get_streak(uuid);
-- drop function if exists public.app_claim_daily_challenge(uuid, uuid);
-- drop function if exists public.app_answer_daily_challenge(uuid, uuid, uuid, uuid);
-- drop function if exists public.app_get_daily_challenge(uuid);
-- drop function if exists private.user_challenge_reto(uuid, timestamptz);
-- drop function if exists public.app_generate_daily_challenges(date);
-- drop function if exists private.ensure_challenge_row(date, uuid, uuid);
-- drop function if exists private.reto_pool(uuid, uuid, text);
-- drop function if exists private.reto_streak_json(uuid, date);
-- drop function if exists private.reto_today(timestamptz);
-- drop function if exists private.reto_tz();
-- drop table if exists public.reto_contadores;
-- drop table if exists public.respuestas_reto_diario;
-- drop table if exists public.etiquetas_comunidad;
-- drop index if exists public.ux_registros_reto_usuario_dia;
-- alter table public.registros_reto_diario drop column if exists dia;
-- drop index if exists public.ux_retos_diarios_segmento;
-- alter table public.retos_diarios add constraint retos_diarios_fecha_key unique (fecha);
-- create policy registros_reto_todo_propio on public.registros_reto_diario for all to authenticated using (usuario_id = (select auth.uid()) or es_admin()) with check (usuario_id = (select auth.uid()) or es_admin());
-- create policy ranking_liga_select_auth_ligas_activas on public.ranking_liga for select to authenticated using (exists (select 1 from public.ligas l where l.id = ranking_liga.liga_id and l.activa) or es_admin());
-- create policy canonical_v2_daily_challenge_read on public.preguntas_reto_diario for select to authenticated using (true);
-- revoke select on public.rachas_usuario from authenticated;  -- (re-granted after anon below: the ACL keeps MAIN's grantee order)
-- grant select on public.retos_diarios, public.rachas_usuario, public.registros_reto_diario, public.ranking_liga, public.estado_premium_usuario to anon;
-- grant select on public.retos_diarios, public.rachas_usuario, public.preguntas_reto_diario, public.ranking_liga to authenticated;
-- grant select, insert, update, delete on public.registros_reto_diario to authenticated;
-- do $r$ begin if to_regclass('public.estadisticas_usuario') is not null then grant select on public.estadisticas_usuario to anon; end if; end $r$;
-- do $r$ declare f text; begin for f in select p.oid::regprocedure::text from pg_proc p where p.pronamespace = 'public'::regnamespace and p.proname like 'recalcular_estadisticas%' loop execute format('revoke all on function %s from postgres, service_role', f); execute format('grant execute on function %s to public', f); execute format('grant execute on function %s to postgres', f); execute format('grant execute on function %s to anon, authenticated, service_role', f); end loop; end $r$;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'challenge_functions_not_service_role_only:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute')
--                or not has_function_privilege('service_role', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('public.app_get_daily_challenge(uuid)'), ('public.app_answer_daily_challenge(uuid,uuid,uuid,uuid)'), ('public.app_claim_daily_challenge(uuid,uuid)'), ('public.app_get_streak(uuid)'),
--                ('public.app_generate_daily_challenges(date)'), ('public.app_credit_free_ot(uuid,text,integer,text,uuid,text,jsonb)')) f(n)
-- union all
-- select 'challenge_tables_client_access:' || t.n,
--        (case when has_table_privilege('anon', t.n, 'select') or has_table_privilege('authenticated', t.n, 'select') or has_table_privilege('authenticated', t.n, 'insert')
--                or has_table_privilege('authenticated', t.n, 'update') or has_table_privilege('authenticated', t.n, 'delete')
--                or not (select c.relrowsecurity from pg_class c where c.oid = t.n::regclass) then 1 else 0 end)::bigint
--   from (values ('public.registros_reto_diario'), ('public.preguntas_reto_diario'), ('public.ranking_liga'), ('public.respuestas_reto_diario'), ('public.etiquetas_comunidad'), ('public.reto_contadores')) t(n)
-- union all
-- select 'recalcular_executable_by_clients',
--        (select count(*) from pg_proc p where p.pronamespace = 'public'::regnamespace and p.proname like 'recalcular_estadisticas%'
--                 and (has_function_privilege('anon', p.oid, 'execute') or has_function_privilege('authenticated', p.oid, 'execute')))::bigint
-- union all
-- select 'free_earn_guard_missing', (case when exists (select 1 from pg_trigger where tgname = 'trg_ledger_free_earn_guard' and not tgisinternal) then 0 else 1 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regclass('public.retos_diarios') is null or to_regclass('public.registros_reto_diario') is null or to_regclass('public.rachas_usuario') is null
     or to_regprocedure('private.plan_benefit_int(uuid,text,integer,integer)') is null or to_regprocedure('private.app_revision_pregunta_contenido(uuid)') is null then
    raise exception using errcode = 'P0001', message = 'V1_CHALLENGE_PREFLIGHT', hint = 'apply 20261022100900 and 20261022101000 first and load the MAIN engagement tables';
  end if;
end
$$;

-- ---- 1. security fixes on MAIN objects
drop policy if exists registros_reto_todo_propio on public.registros_reto_diario;
revoke all on table public.registros_reto_diario from public, anon, authenticated;
drop policy if exists ranking_liga_select_auth_ligas_activas on public.ranking_liga;
revoke all on table public.ranking_liga from public, anon, authenticated;
drop policy if exists canonical_v2_daily_challenge_read on public.preguntas_reto_diario;
revoke all on table public.preguntas_reto_diario from public, anon, authenticated;
revoke select on table public.retos_diarios from anon, authenticated;
revoke select on table public.rachas_usuario from anon;
do $$
declare t text;
begin
  foreach t in array array['estadisticas_usuario', 'estado_premium_usuario'] loop
    if to_regclass('public.' || t) is not null then
      execute format('revoke select on table public.%I from anon', t);
    end if;
  end loop;
  for t in select p.oid::regprocedure::text from pg_proc p where p.pronamespace = 'public'::regnamespace and p.proname like 'recalcular_estadisticas%' loop
    execute format('revoke all on function %s from public, anon, authenticated', t);
    execute format('grant execute on function %s to service_role', t);
  end loop;
end
$$;

-- ---- 2. configuration (Control editable; defaults are test-neutral)
insert into public.configuracion_v1 (clave, valor_integer, valor_text) values
  ('reto_hora_desbloqueo', 0, null),
  ('bonus_racha_desde_dia', 3, null),
  ('bonus_racha_max_dias', 7, null),
  ('tope_ot_gratis_dia', 100, null),
  ('reto_exige_email_verificado', 1, null)
on conflict (clave) do nothing;

-- ---- 3. schema
alter table public.retos_diarios drop constraint if exists retos_diarios_fecha_key;
create unique index if not exists ux_retos_diarios_segmento on public.retos_diarios
  (fecha, coalesce(especialidad_id, '00000000-0000-0000-0000-000000000000'::uuid), coalesce(comunidad_id, '00000000-0000-0000-0000-000000000000'::uuid));
alter table public.registros_reto_diario add column if not exists dia date;
create unique index if not exists ux_registros_reto_usuario_dia on public.registros_reto_diario (usuario_id, dia) where dia is not null;

create table if not exists public.etiquetas_comunidad (
  etiqueta_id uuid not null references public.etiquetas(id) on delete cascade,
  comunidad_id uuid not null references public.comunidades_autonomas(id) on delete cascade,
  creado_en timestamptz not null default now(),
  primary key (etiqueta_id, comunidad_id)
);
alter table public.etiquetas_comunidad enable row level security;
revoke all on table public.etiquetas_comunidad from public, anon, authenticated;
grant select, insert, update, delete on table public.etiquetas_comunidad to service_role;

create table if not exists public.respuestas_reto_diario (
  usuario_id uuid not null references auth.users(id) on delete cascade,
  reto_id uuid not null references public.retos_diarios(id) on delete cascade,
  pregunta_id uuid not null references public.preguntas(id) on delete cascade,
  opcion_id uuid not null,
  es_correcta boolean not null,
  respondida_en timestamptz not null,
  primary key (usuario_id, reto_id, pregunta_id)
);
alter table public.respuestas_reto_diario enable row level security;
revoke all on table public.respuestas_reto_diario from public, anon, authenticated, service_role;

create table if not exists public.reto_contadores (
  reto_id uuid not null references public.retos_diarios(id) on delete cascade,
  tipo_usuario text not null check (tipo_usuario in ('gratis', 'suscritos')),
  jugadores integer not null default 0,
  superados integer not null default 0,
  primary key (reto_id, tipo_usuario)
);
alter table public.reto_contadores enable row level security;
revoke all on table public.reto_contadores from public, anon, authenticated, service_role;

-- ---- 4. clock helpers (DB clock, configured zone)
create or replace function private.reto_tz()
returns text
language sql
stable
security definer
set search_path = ''
as $$ select coalesce((select c.valor_text from public.configuracion_v1 c where c.clave = 'zona_horaria'), 'Europe/Madrid') $$;
revoke all on function private.reto_tz() from public, anon, authenticated;

create or replace function private.reto_today(p_now timestamptz)
returns date
language sql
stable
security definer
set search_path = ''
as $$ select (p_now at time zone private.reto_tz())::date $$;
revoke all on function private.reto_today(timestamptz) from public, anon, authenticated;

-- ---- 5. question pool of a slot for a segment (regional rule)
create or replace function private.reto_pool(p_esp uuid, p_com uuid, p_slot text)
returns setof uuid
language sql
stable
security definer
set search_path = ''
as $$
  select distinct p.id
    from public.preguntas p
    join public.preguntas_examen pe on pe.pregunta_id = p.id and pe.anulada is not true and (p_slot <> 'inmersiva' or pe.es_demo)
    join public.examenes e on e.id = pe.examen_id and e.activo and e.publicado_en is not null and e.publicado_en <= clock_timestamp() and e.especialidad_id = p_esp
   where p.activa and p.tipo_pregunta = p_slot
     and (not exists (select 1 from public.etiquetas_pregunta ep join public.etiquetas_comunidad ec on ec.etiqueta_id = ep.etiqueta_id where ep.pregunta_id = p.id)
          or (p_com is not null and exists (select 1 from public.etiquetas_pregunta ep join public.etiquetas_comunidad ec on ec.etiqueta_id = ep.etiqueta_id
                                             where ep.pregunta_id = p.id and ec.comunidad_id = p_com)))
$$;
revoke all on function private.reto_pool(uuid, uuid, text) from public, anon, authenticated;

-- one challenge row for a segment and a day; false = already there or no pool (nothing created)
create or replace function private.ensure_challenge_row(p_fecha date, p_esp uuid, p_com uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_reto uuid;
  v_inm uuid;
  v_raz uuid;
  v_raf uuid;
  v_inicio timestamptz;
  v_hay_regional boolean;
begin
  if exists (select 1 from public.retos_diarios r where r.fecha = p_fecha and r.especialidad_id = p_esp and r.comunidad_id is not distinct from p_com) then
    return false;
  end if;
  -- a regional row only exists when some regional-tagged question can enter it (otherwise it would equal the national one)
  if p_com is not null then
    select exists (select 1 from public.etiquetas_pregunta ep join public.etiquetas_comunidad ec on ec.etiqueta_id = ep.etiqueta_id
                    where ec.comunidad_id = p_com
                      and ep.pregunta_id in (select private.reto_pool(p_esp, p_com, 'rafaga') union select private.reto_pool(p_esp, p_com, 'razonada') union select private.reto_pool(p_esp, p_com, 'inmersiva')))
      into v_hay_regional;
    if not v_hay_regional then
      return false;
    end if;
  end if;
  select q into v_inm from private.reto_pool(p_esp, p_com, 'inmersiva') q order by random() limit 1;
  select q into v_raz from private.reto_pool(p_esp, p_com, 'razonada') q order by random() limit 1;
  select q into v_raf from private.reto_pool(p_esp, p_com, 'rafaga') q order by random() limit 1;
  if v_inm is null or v_raz is null or v_raf is null then
    return false;  -- a slot without pool: no challenge for this segment today (never relax the regional rule)
  end if;
  v_inicio := (p_fecha::timestamp + make_interval(hours => private.config_int('reto_hora_desbloqueo', 0))) at time zone private.reto_tz();
  insert into public.retos_diarios (fecha, titulo, descripcion, objetivo_preguntas, aciertos_necesarios, recompensa_puntos, activo, empieza_en, termina_en, especialidad_id, comunidad_id, segmento_usuario)
  values (p_fecha, 'Reto diario', null, 3, 2, 0, true, v_inicio, v_inicio + interval '24 hours', p_esp, p_com, 'todos')
  on conflict do nothing
  returning id into v_reto;
  if v_reto is null then
    return false;
  end if;
  insert into public.preguntas_reto_diario (reto_id, pregunta_id, posicion, tipo_slot) values
    (v_reto, v_inm, 1, 'inmersiva_demo'), (v_reto, v_raz, 2, 'razonada'), (v_reto, v_raf, 3, 'rafaga');
  return true;
end;
$$;
revoke all on function private.ensure_challenge_row(date, uuid, uuid) from public, anon, authenticated;

-- the daily job (also callable lazily): one row per active specialty (national) and per community that has regional-tagged questions
create or replace function public.app_generate_daily_challenges(p_fecha date default null)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_fecha date;
  v_n integer := 0;
  s record;
  c record;
begin
  perform pg_advisory_xact_lock(hashtextextended('reto-job', 0));  -- one generator at a time (single leader)
  v_fecha := coalesce(p_fecha, private.reto_today(clock_timestamp()));
  for s in select e.id from public.especialidades e where e.activa loop
    if private.ensure_challenge_row(v_fecha, s.id, null) then v_n := v_n + 1; end if;
    for c in select distinct ec.comunidad_id from public.etiquetas_comunidad ec loop
      if private.ensure_challenge_row(v_fecha, s.id, c.comunidad_id) then v_n := v_n + 1; end if;
    end loop;
  end loop;
  return v_n;
end;
$$;
revoke all on function public.app_generate_daily_challenges(date) from public, anon, authenticated;
grant execute on function public.app_generate_daily_challenges(date) to service_role;

-- ---- 6. the user's row of the day (frozen at the first open)
create or replace function private.user_challenge_reto(p_usuario uuid, p_now timestamptz)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_today date := private.reto_today(p_now);
  v_reto uuid;
  v_esp uuid;
  v_principal uuid;
  c record;
begin
  select r.reto_id into v_reto from public.registros_reto_diario r where r.usuario_id = p_usuario and r.dia = v_today;
  if found then
    return v_reto;
  end if;
  select pf.especialidad_principal_id, pf.comunidad_principal_id into v_esp, v_principal from public.perfiles_usuario pf where pf.usuario_id = p_usuario;
  if v_esp is null then
    return null;
  end if;
  -- lazy creation of the segments this user can see (the job normally did it already)
  perform pg_advisory_xact_lock(hashtextextended('reto-job', 0));
  perform private.ensure_challenge_row(v_today, v_esp, null);
  for c in select x.comunidad_id from (select v_principal as comunidad_id union select o.comunidad_id from public.comunidades_objetivo_usuario o where o.usuario_id = p_usuario) x where x.comunidad_id is not null loop
    perform private.ensure_challenge_row(v_today, v_esp, c.comunidad_id);
  end loop;
  -- most specific eligible row: the principal community first, then the target communities by priority, then the national row
  select r.id into v_reto
    from public.retos_diarios r
    left join public.comunidades_objetivo_usuario o on o.usuario_id = p_usuario and o.comunidad_id = r.comunidad_id
   where r.fecha = v_today and r.activo and r.especialidad_id = v_esp
     and (r.comunidad_id is null or r.comunidad_id = v_principal or o.comunidad_id is not null)
   order by (r.comunidad_id = v_principal) desc nulls last, (r.comunidad_id is not null) desc, o.prioridad nulls last, r.id
   limit 1;
  if v_reto is null then
    return null;
  end if;
  insert into public.registros_reto_diario (usuario_id, reto_id, dia) values (p_usuario, v_reto, v_today) on conflict do nothing;
  select r.reto_id into v_reto from public.registros_reto_diario r where r.usuario_id = p_usuario and r.dia = v_today;
  return v_reto;
end;
$$;
revoke all on function private.user_challenge_reto(uuid, timestamptz) from public, anon, authenticated;

create or replace function private.reto_streak_json(p_usuario uuid, p_today date)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'actual', case when r.ultima_actividad_fecha is not null and r.ultima_actividad_fecha >= p_today - 1 then r.racha_actual_dias else 0 end,
    'maxima', coalesce(r.mejor_racha_dias, 0),
    'ultimoDiaSuperado', r.ultima_actividad_fecha)
  from (select 1) x left join public.rachas_usuario r on r.usuario_id = p_usuario
$$;
revoke all on function private.reto_streak_json(uuid, date) from public, anon, authenticated;

-- ---- 7. GET today
create or replace function public.app_get_daily_challenge(p_usuario uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_now timestamptz := clock_timestamp();
  v_today date := private.reto_today(v_now);
  v_reto uuid;
  r public.retos_diarios%rowtype;
  g public.registros_reto_diario%rowtype;
  v_estado text;
  v_preguntas jsonb;
  v_recomp jsonb;
  v_tipo text;
  v_barra jsonb;
begin
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  v_reto := private.user_challenge_reto(p_usuario, v_now);
  if v_reto is null then
    return jsonb_build_object('estado', 'NO_DISPONIBLE', 'necesarios', 2, 'aciertos', 0, 'preguntas', '[]'::jsonb, 'racha', private.reto_streak_json(p_usuario, v_today),
                              'barraGlobal', jsonb_build_object('jugadores', 0, 'superados', 0));
  end if;
  select * into r from public.retos_diarios x where x.id = v_reto;
  select * into g from public.registros_reto_diario x where x.usuario_id = p_usuario and x.reto_id = v_reto;
  v_estado := case
    when v_now < r.empieza_en then 'NO_DISPONIBLE'
    when g.completado and g.superado then 'SUPERADO'
    when g.completado then 'FALLADO'
    when v_now >= r.termina_en then 'FALLADO'
    when g.progreso > 0 then 'EN_CURSO'
    else 'DISPONIBLE' end;
  select coalesce(jsonb_agg(jsonb_build_object(
           'posicion', pr.posicion,
           'tipoSlot', case pr.tipo_slot when 'inmersiva_demo' then 'INMERSIVA' when 'razonada' then 'RAZONADA' else 'RAFAGA' end,
           'preguntaId', pr.pregunta_id,
           'titulo', c.contenido ->> 'titulo',
           'opciones', coalesce((select jsonb_agg(jsonb_build_object('id', o ->> 'id', 'letra', o ->> 'letra', 'texto', o ->> 'texto') order by (o ->> 'posicion')::int, o ->> 'id')
                                   from jsonb_array_elements(c.contenido -> 'opciones') o), '[]'::jsonb),
           'respuesta', case when a.pregunta_id is not null then jsonb_build_object('opcionId', a.opcion_id, 'esCorrecta', a.es_correcta,
                                                                                  'opcionCorrectaId', (private.app_revision_pregunta_solucion(pr.pregunta_id) ->> 'opcion_correcta_id')::uuid) end)
         order by pr.posicion), '[]'::jsonb)
    into v_preguntas
    from public.preguntas_reto_diario pr
    cross join lateral (select private.app_revision_pregunta_contenido(pr.pregunta_id) as contenido) c
    left join public.respuestas_reto_diario a on a.usuario_id = p_usuario and a.reto_id = v_reto and a.pregunta_id = pr.pregunta_id
   where pr.reto_id = v_reto;
  if g.completado and g.superado then
    v_recomp := jsonb_build_object('base', g.recompensa_base_tickets, 'bonusRacha', g.bonus_racha_tickets, 'total', g.recompensa_total_tickets, 'reclamada', g.recompensa_entregada);
  end if;
  v_tipo := case when exists (select 1 from public.estado_premium_usuario e where e.usuario_id = p_usuario and e.estado_suscripcion in ('activa', 'cancelada')
                                 and private.premium_active(e.es_premium, e.estado_suscripcion, e.fin_en, v_now)) then 'suscritos' else 'gratis' end;
  select jsonb_build_object('jugadores', coalesce(sum(k.jugadores), 0), 'superados', coalesce(sum(k.superados), 0)) into v_barra
    from public.reto_contadores k where k.reto_id = v_reto and k.tipo_usuario = v_tipo;
  return jsonb_strip_nulls(jsonb_build_object('estado', v_estado, 'retoId', r.id, 'empiezaEn', r.empieza_en, 'terminaEn', r.termina_en, 'necesarios', r.aciertos_necesarios,
    'aciertos', g.aciertos, 'preguntas', v_preguntas, 'recompensa', v_recomp, 'racha', private.reto_streak_json(p_usuario, v_today), 'barraGlobal', v_barra));
end;
$$;
revoke all on function public.app_get_daily_challenge(uuid) from public, anon, authenticated;
grant execute on function public.app_get_daily_challenge(uuid) to service_role;

-- ---- 8. answer (final), completion, streak, frozen reward
create or replace function public.app_answer_daily_challenge(p_usuario uuid, p_reto uuid, p_pregunta uuid, p_opcion uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_now timestamptz;
  v_today date;
  r public.retos_diarios%rowtype;
  g public.registros_reto_diario%rowtype;
  v_prev public.respuestas_reto_diario%rowtype;
  v_content jsonb;
  v_correcta uuid;
  v_ok boolean;
  v_progreso integer;
  v_superado boolean;
  v_reward jsonb;
  st public.rachas_usuario%rowtype;
  v_esp uuid;
  v_plan uuid;
  v_base integer := 0;
  v_bonus integer := 0;
  v_tipo text;
  v_paid boolean;
  v_desde integer;
  v_max integer;
  v_hay_previa boolean;
begin
  if p_usuario is null or p_reto is null or p_pregunta is null or p_opcion is null then
    raise exception using errcode = 'P0001', message = 'NOT_FOUND';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('reto:' || p_usuario::text, 0));
  v_now := clock_timestamp();
  v_today := private.reto_today(v_now);
  -- the reto must be the row stored for THIS user today (no oracle for other rows / segments)
  select * into g from public.registros_reto_diario x where x.usuario_id = p_usuario and x.reto_id = p_reto and x.dia = v_today;
  if not found then
    raise exception using errcode = 'P0001', message = 'NOT_FOUND';
  end if;
  select * into r from public.retos_diarios x where x.id = p_reto;
  if not exists (select 1 from public.preguntas_reto_diario pr where pr.reto_id = p_reto and pr.pregunta_id = p_pregunta) then
    raise exception using errcode = 'P0001', message = 'NOT_FOUND';
  end if;
  select * into v_prev from public.respuestas_reto_diario x where x.usuario_id = p_usuario and x.reto_id = p_reto and x.pregunta_id = p_pregunta;
  v_hay_previa := found;
  v_content := private.app_revision_pregunta_contenido(p_pregunta);
  v_correcta := (private.app_revision_pregunta_solucion(p_pregunta) ->> 'opcion_correcta_id')::uuid;
  if v_hay_previa then
    if v_prev.opcion_id <> p_opcion then
      raise exception using errcode = 'P0001', message = 'ANSWER_LOCKED';
    end if;
    -- idempotent retry: the stored result, nothing changes
    return jsonb_strip_nulls(jsonb_build_object('esCorrecta', v_prev.es_correcta, 'opcionCorrectaId', v_correcta,
      'estado', case when g.completado and g.superado then 'SUPERADO' when g.completado then 'FALLADO' else 'EN_CURSO' end,
      'aciertos', g.aciertos, 'respondidas', g.progreso,
      'recompensa', case when g.completado and g.superado then jsonb_build_object('base', g.recompensa_base_tickets, 'bonusRacha', g.bonus_racha_tickets, 'total', g.recompensa_total_tickets, 'reclamada', g.recompensa_entregada) end,
      'racha', private.reto_streak_json(p_usuario, v_today)));
  end if;
  if v_now < r.empieza_en or v_now >= r.termina_en or g.completado then
    raise exception using errcode = 'P0001', message = 'RETO_OUT_OF_WINDOW';
  end if;
  if not exists (select 1 from jsonb_array_elements(v_content -> 'opciones') o where o ->> 'id' = p_opcion::text) then
    raise exception using errcode = 'P0001', message = 'OPTION_NOT_IN_QUESTION';
  end if;
  v_ok := (p_opcion = v_correcta);
  insert into public.respuestas_reto_diario (usuario_id, reto_id, pregunta_id, opcion_id, es_correcta, respondida_en) values (p_usuario, p_reto, p_pregunta, p_opcion, v_ok, v_now);
  v_progreso := g.progreso + 1;
  update public.registros_reto_diario set progreso = v_progreso, aciertos = g.aciertos + (case when v_ok then 1 else 0 end), fallos = g.fallos + (case when v_ok then 0 else 1 end)
   where usuario_id = p_usuario and reto_id = p_reto
  returning * into g;
  if v_progreso >= r.objetivo_preguntas then
    v_superado := g.aciertos >= r.aciertos_necesarios;
    -- tier of the user for the bar and the reward: a PAID plan (activa / cancelada with its period) earns its plan amount; trial and free users earn the Gratuito amount
    v_paid := exists (select 1 from public.estado_premium_usuario e where e.usuario_id = p_usuario and e.estado_suscripcion in ('activa', 'cancelada') and private.premium_active(e.es_premium, e.estado_suscripcion, e.fin_en, v_now));
    v_tipo := case when v_paid then 'suscritos' else 'gratis' end;
    if v_superado then
      -- streak: single writer
      insert into public.rachas_usuario (usuario_id, racha_actual_dias, mejor_racha_dias, ultima_actividad_fecha) values (p_usuario, 0, 0, null) on conflict do nothing;
      select * into st from public.rachas_usuario x where x.usuario_id = p_usuario for update;
      if st.ultima_actividad_fecha = v_today then
        null;  -- already counted today
      elsif st.ultima_actividad_fecha = v_today - 1
            or (st.ultima_actividad_fecha is not null and st.ultima_actividad_fecha < v_today - 1
                and not exists (select 1 from public.retos_diarios d where d.especialidad_id = r.especialidad_id and d.fecha > st.ultima_actividad_fecha and d.fecha < v_today and d.activo)) then
        st.racha_actual_dias := st.racha_actual_dias + 1;  -- consecutive, or only days WITHOUT an available challenge in between (they never break the streak)
        st.ultima_actividad_fecha := v_today;
      else
        st.racha_actual_dias := 1;
        st.ultima_actividad_fecha := v_today;
      end if;
      update public.rachas_usuario set racha_actual_dias = st.racha_actual_dias, mejor_racha_dias = greatest(st.mejor_racha_dias, st.racha_actual_dias),
             ultima_actividad_fecha = st.ultima_actividad_fecha, actualizado_en = now() where usuario_id = p_usuario;
      -- reward amounts, frozen now
      select pf.especialidad_principal_id into v_esp from public.perfiles_usuario pf where pf.usuario_id = p_usuario;
      if v_paid then
        select up.plan_id into v_plan from private.user_plan(p_usuario, v_now) up;
      end if;
      if v_plan is null then
        select pl.id into v_plan from public.planes_suscripcion pl where pl.nivel = 0 and pl.activo order by pl.posicion, pl.codigo limit 1;
      end if;
      v_base := coalesce(private.plan_benefit_int(v_plan, 'ot_reto_diario', 0, private.config_int('max_ot_reto_diario', 200)), 0);
      v_desde := private.config_int('bonus_racha_desde_dia', 3);
      v_max := private.config_int('bonus_racha_max_dias', 7);
      if st.racha_actual_dias >= v_desde and st.racha_actual_dias < v_desde + v_max then
        v_bonus := coalesce(private.plan_benefit_int(v_plan, 'ot_bonus_racha', 0, private.config_int('max_ot_bonus_racha', 100)), 0);
      end if;
    end if;
    update public.registros_reto_diario set completado = true, completado_en = v_now, superado = v_superado, recompensa_base_tickets = v_base, bonus_racha_tickets = v_bonus,
           recompensa_total_tickets = v_base + v_bonus where usuario_id = p_usuario and reto_id = p_reto returning * into g;
    insert into public.reto_contadores (reto_id, tipo_usuario, jugadores, superados) values (p_reto, v_tipo, 1, case when v_superado then 1 else 0 end)
    on conflict (reto_id, tipo_usuario) do update set jugadores = public.reto_contadores.jugadores + 1, superados = public.reto_contadores.superados + (case when v_superado then 1 else 0 end);
    if v_superado then
      v_reward := jsonb_build_object('base', v_base, 'bonusRacha', v_bonus, 'total', v_base + v_bonus, 'reclamada', false);
    end if;
  end if;
  return jsonb_strip_nulls(jsonb_build_object('esCorrecta', v_ok, 'opcionCorrectaId', v_correcta,
    'estado', case when g.completado and g.superado then 'SUPERADO' when g.completado then 'FALLADO' else 'EN_CURSO' end,
    'aciertos', g.aciertos, 'respondidas', g.progreso, 'recompensa', v_reward, 'racha', private.reto_streak_json(p_usuario, v_today)));
end;
$$;
revoke all on function public.app_answer_daily_challenge(uuid, uuid, uuid, uuid) from public, anon, authenticated;
grant execute on function public.app_answer_daily_challenge(uuid, uuid, uuid, uuid) to service_role;

-- ---- 9. the ONE writer of free-earn OT (E2 earn cap)
create or replace function private.tg_ledger_free_earn_guard()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if (new.tipo in ('reto_diario', 'bonus_racha', 'referido') or (new.tipo = 'reporte_validado' and new.referencia_tipo is distinct from 'admin'))
     and coalesce(current_setting('oc.free_earn_writer', true), '') <> 'on' then
    raise exception using errcode = '42501', message = 'FREE_EARN_WRITER_ONLY';
  end if;
  return new;
end;
$$;
revoke all on function private.tg_ledger_free_earn_guard() from public, anon, authenticated;
drop trigger if exists trg_ledger_free_earn_guard on public.transacciones_cartera;
create trigger trg_ledger_free_earn_guard before insert on public.transacciones_cartera for each row execute function private.tg_ledger_free_earn_guard();

create or replace function public.app_credit_free_ot(p_usuario uuid, p_tipo text, p_cantidad integer, p_referencia_tipo text, p_referencia_id uuid, p_idem_key text, p_meta jsonb)
returns table(outcome text, transaccion_id uuid, concedido integer, saldo integer)
language plpgsql
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  v_key text := btrim(p_idem_key);
  v_ex public.transacciones_cartera%rowtype;
  v_dia date;
  v_tz text := private.reto_tz();
  v_ganado integer;
  v_cap integer;
  v_grant integer;
  v_tx public.transacciones_cartera%rowtype;
begin
  if p_tipo is null or p_tipo not in ('reto_diario', 'bonus_racha', 'referido', 'reporte_validado') or p_cantidad is null or p_cantidad < 0 or v_key is null or length(v_key) = 0 or length(v_key) > 128 then
    raise exception using errcode = 'P0001', message = 'FREE_EARN_INVALID';
  end if;
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('wallet:' || p_usuario::text, 0));
  select t.* into v_ex from public.transacciones_cartera t where t.usuario_id = p_usuario and t.idempotency_key = v_key;
  if found then
    return query select 'REPLAY'::text, v_ex.id, v_ex.cantidad, (select w.saldo_tickets from public.carteras_usuario w where w.usuario_id = p_usuario);
    return;
  end if;
  if p_cantidad = 0 then
    return query select 'ZERO'::text, null::uuid, 0, coalesce((select w.saldo_tickets from public.carteras_usuario w where w.usuario_id = p_usuario), 0);
    return;
  end if;
  v_dia := (clock_timestamp() at time zone v_tz)::date;
  select coalesce(sum(t.cantidad), 0) into v_ganado from public.transacciones_cartera t
   where t.usuario_id = p_usuario and t.tipo in ('reto_diario', 'bonus_racha', 'referido', 'reporte_validado') and t.cantidad > 0 and (t.creado_en at time zone v_tz)::date = v_dia;
  v_cap := private.config_int('tope_ot_gratis_dia', 100);
  v_grant := least(p_cantidad, greatest(v_cap - v_ganado, 0));
  if v_grant <= 0 then
    return query select 'CAPPED'::text, null::uuid, 0, coalesce((select w.saldo_tickets from public.carteras_usuario w where w.usuario_id = p_usuario), 0);
    return;
  end if;
  perform set_config('oc.free_earn_writer', 'on', true);
  insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, referencia_tipo, referencia_id, idempotency_key, metadatos)
  values (p_usuario, p_tipo, v_grant, case p_tipo when 'reto_diario' then 'Reto diario' when 'bonus_racha' then 'Bonus de racha' when 'referido' then 'Invitacion' else 'Reporte validado' end,
          p_referencia_tipo, p_referencia_id, v_key, coalesce(p_meta, '{}'::jsonb) || jsonb_build_object('op', 'free_earn', 'solicitado', p_cantidad, 'tope_dia', v_cap))
  returning * into v_tx;
  perform set_config('oc.free_earn_writer', 'off', true);
  return query select case when v_grant < p_cantidad then 'CAPPED_PARTIAL' else 'CREDITED' end, v_tx.id, v_grant, v_tx.saldo_resultante;
end;
$$;
revoke all on function public.app_credit_free_ot(uuid, text, integer, text, uuid, text, jsonb) from public, anon, authenticated;
grant execute on function public.app_credit_free_ot(uuid, text, integer, text, uuid, text, jsonb) to service_role;

-- ---- 10. claim (idempotent per user per Madrid day)
create or replace function public.app_claim_daily_challenge(p_usuario uuid, p_reto uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  g public.registros_reto_diario%rowtype;
  v_now timestamptz;
  a record;
  b record;
  v_base integer;
  v_bonus integer;
  v_saldo integer;
begin
  if p_usuario is null or p_reto is null then
    raise exception using errcode = 'P0001', message = 'NOT_FOUND';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('wallet:' || p_usuario::text, 0));
  v_now := clock_timestamp();
  select * into g from public.registros_reto_diario x where x.usuario_id = p_usuario and x.reto_id = p_reto and x.dia is not null;
  if not found then
    raise exception using errcode = 'P0001', message = 'NOT_FOUND';
  end if;
  if not (g.completado and g.superado) then
    raise exception using errcode = 'P0001', message = 'RETO_NOT_PASSED';
  end if;
  select coalesce(w.saldo_tickets, 0) into v_saldo from public.carteras_usuario w where w.usuario_id = p_usuario;
  if g.recompensa_entregada then
    return jsonb_build_object('recompensa', jsonb_build_object('base', g.recompensa_base_tickets, 'bonusRacha', g.bonus_racha_tickets, 'total', g.recompensa_total_tickets, 'reclamada', true), 'saldo', coalesce(v_saldo, 0));
  end if;
  select * into a from public.app_credit_free_ot(p_usuario, 'reto_diario', g.recompensa_base_tickets, 'reto', p_reto, 'reto:' || p_usuario::text || ':' || g.dia::text, jsonb_build_object('dia', g.dia));
  select * into b from public.app_credit_free_ot(p_usuario, 'bonus_racha', g.bonus_racha_tickets, 'reto', p_reto, 'racha:' || p_usuario::text || ':' || g.dia::text, jsonb_build_object('dia', g.dia));
  v_base := coalesce(a.concedido, 0);
  v_bonus := coalesce(b.concedido, 0);
  if v_base + v_bonus = 0 and g.recompensa_total_tickets > 0 then
    raise exception using errcode = 'P0001', message = 'OT_EARN_CAP_REACHED';  -- nothing could be paid today: the claim stays open
  end if;
  update public.registros_reto_diario set recompensa_entregada = true, recompensa_base_tickets = v_base, bonus_racha_tickets = v_bonus, recompensa_total_tickets = v_base + v_bonus
   where usuario_id = p_usuario and reto_id = p_reto;
  select coalesce(w.saldo_tickets, 0) into v_saldo from public.carteras_usuario w where w.usuario_id = p_usuario;
  return jsonb_build_object('recompensa', jsonb_build_object('base', v_base, 'bonusRacha', v_bonus, 'total', v_base + v_bonus, 'reclamada', true), 'saldo', v_saldo);
end;
$$;
revoke all on function public.app_claim_daily_challenge(uuid, uuid) from public, anon, authenticated;
grant execute on function public.app_claim_daily_challenge(uuid, uuid) to service_role;

create or replace function public.app_get_streak(p_usuario uuid)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$ select private.reto_streak_json(p_usuario, private.reto_today(clock_timestamp())) $$;
revoke all on function public.app_get_streak(uuid) from public, anon, authenticated;
grant execute on function public.app_get_streak(uuid) to service_role;

commit;
