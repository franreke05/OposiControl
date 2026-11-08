-- V1 HOME TOP BAR (F2, 2026-11-08): the streak read D3-04 GET /api/v1/app/racha also serves the two Home atoms that were hidden for lack of data:
-- the user's LEVEL (league tier) and the "OT hoy" gauge (free OposiTickets earned today / daily cap). Contract amendment FA43 (engagement.yaml,
-- schema StreakView, optional nullable fields). Additive; 20261022101200 and 20261022103500 are NOT edited.
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Requires 20261022101200 (streak, free-earn writer, config), 20261022101600
-- (league assignment), 20261022103500 (reto_day_end, DST-safe Madrid day).
--
-- WHAT IT DOES: public.app_get_streak(uuid) keeps its signature, owner, SECURITY DEFINER, empty search_path and ACL (EXECUTE service_role only),
-- stays STABLE (read only) and returns private.reto_streak_json(...) (unchanged keys actual, maxima, ultimoDiaSuperado) plus TWO keys:
--   * liga  = the league tier the W3 hook assigned (estado_competitivo_usuario.liga_nivel_id -> ligas_nivel): {codigo, nombre, orden = ligas_nivel.posicion};
--             JSON null while the user has not reached the first tier (same rule as app_ranking_me, which also does not filter ligas_nivel.activa).
--   * otHoy = {ganados, tope}. ganados = the EXACT day sum the earn cap compares against (private.free_ot_apply, v_d): ledger rows of the user with
--             tipo in ('reto_diario', 'bonus_racha', 'referido', 'reporte_validado') and cantidad > 0, created inside TODAY of the configured zone
--             (private.reto_tz(), default Europe/Madrid; same day as private.reto_today and the challenge window, DST-safe through private.reto_day_end).
--             Purchases (recarga, pack_tickets), admin grants and adjustments (ajuste, regalo), refunds (devolucion), spending and deferred rewards
--             still pending in ot_ganancias_diferidas are NOT counted. NOTE: reporte_validado written by admin_grant_tickets (referencia_tipo 'admin')
--             IS counted, because the cap counts it; that path is not capped, so ganados can exceed tope.
--             tope = configuracion_v1 'tope_ot_gratis_dia' (default 100, as the cap); null when <= 0 (0 = no daily cap, as in free_ot_apply).
--   The other readers of private.reto_streak_json (GET /retos/hoy, the answer payload, the stats summary) are unchanged.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate): the 20261022101200 body of app_get_streak comes back verbatim (same signature and ACL).
-- ROLLBACK-BEGIN
-- begin;
-- create or replace function public.app_get_streak(p_usuario uuid)
-- returns jsonb
-- language sql
-- stable
-- security definer
-- set search_path = ''
-- as $$ select private.reto_streak_json(p_usuario, private.reto_today(clock_timestamp())) $$;
-- revoke all on function public.app_get_streak(uuid) from public, anon, authenticated;
-- grant execute on function public.app_get_streak(uuid) to service_role;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'function_not_service_role_only:public.app_get_streak(uuid)' as check_name,
--        (case when has_function_privilege('anon', 'public.app_get_streak(uuid)', 'execute')
--                or has_function_privilege('authenticated', 'public.app_get_streak(uuid)', 'execute')
--                or not has_function_privilege('service_role', 'public.app_get_streak(uuid)', 'execute') then 1 else 0 end)::bigint as failures
-- union all
-- select 'streak_read_not_stable_definer_empty_path', (case when exists (select 1 from pg_proc p where p.oid = 'public.app_get_streak(uuid)'::regprocedure
--                                                         and p.provolatile = 's' and p.prosecdef and p.proconfig @> array['search_path=""']) then 0 else 1 end)::bigint
-- union all
-- select 'streak_read_without_level_or_ot_today', (case when (public.app_get_streak('00000000-0000-0000-0000-000000000000') ?& array['actual', 'maxima', 'liga', 'otHoy'])
--                                                       and (public.app_get_streak('00000000-0000-0000-0000-000000000000') -> 'otHoy') ?& array['ganados', 'tope'] then 0 else 1 end)::bigint
-- union all
-- select 'ot_today_types_differ_from_the_earn_cap', (case when (select p.prosrc from pg_proc p where p.oid = 'private.free_ot_apply(uuid,text,integer,text,uuid,text,jsonb,boolean)'::regprocedure)
--                                                          like '%t.tipo in (''reto_diario'', ''bonus_racha'', ''referido'', ''reporte_validado'') and t.cantidad > 0%'
--                                                         and (select p.prosrc from pg_proc p where p.oid = 'public.app_get_streak(uuid)'::regprocedure)
--                                                          like '%t.tipo in (''reto_diario'', ''bonus_racha'', ''referido'', ''reporte_validado'') and t.cantidad > 0%'
--                                                         and (select p.prosrc from pg_proc p where p.oid = 'public.app_get_streak(uuid)'::regprocedure) like '%tope_ot_gratis_dia%'
--                                                        then 0 else 1 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (fail closed)
do $$
begin
  if to_regprocedure('public.app_get_streak(uuid)') is null or to_regprocedure('private.reto_streak_json(uuid,date)') is null
     or to_regprocedure('private.reto_today(timestamptz)') is null or to_regprocedure('private.reto_day_end(date)') is null
     or to_regprocedure('private.config_int(text,integer)') is null
     or to_regprocedure('private.free_ot_apply(uuid,text,integer,text,uuid,text,jsonb,boolean)') is null
     or to_regclass('public.transacciones_cartera') is null or to_regclass('public.estado_competitivo_usuario') is null
     or not exists (select 1 from information_schema.columns c where c.table_schema = 'public' and c.table_name = 'ligas_nivel' and c.column_name = 'posicion') then
    raise exception using errcode = 'P0001', message = 'V1_HOME_LEVEL_OT_TODAY_PREFLIGHT', hint = 'apply 20261022101200, 20261022101600 and 20261022103500 first';
  end if;
end
$$;

create or replace function public.app_get_streak(p_usuario uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_hoy date := private.reto_today(clock_timestamp());
  v_tope integer := private.config_int('tope_ot_gratis_dia', 100);
  v_ganados integer;
  v_liga jsonb;
begin
  -- the same window and types as the earn cap (private.free_ot_apply): [start of today, start of tomorrow) in the configured zone
  select coalesce(sum(t.cantidad), 0)::integer into v_ganados
    from public.transacciones_cartera t
   where t.usuario_id = p_usuario and t.tipo in ('reto_diario', 'bonus_racha', 'referido', 'reporte_validado') and t.cantidad > 0
     and t.creado_en >= private.reto_day_end(v_hoy - 1) and t.creado_en < private.reto_day_end(v_hoy);
  select jsonb_build_object('codigo', l.codigo, 'nombre', l.nombre, 'orden', l.posicion) into v_liga
    from public.estado_competitivo_usuario ec
    join public.ligas_nivel l on l.id = ec.liga_nivel_id
   where ec.usuario_id = p_usuario;
  return private.reto_streak_json(p_usuario, v_hoy)
      || jsonb_build_object('liga', v_liga, 'otHoy', jsonb_build_object('ganados', v_ganados, 'tope', case when v_tope > 0 then v_tope end));
end;
$$;
revoke all on function public.app_get_streak(uuid) from public, anon, authenticated;
grant execute on function public.app_get_streak(uuid) to service_role;

commit;
