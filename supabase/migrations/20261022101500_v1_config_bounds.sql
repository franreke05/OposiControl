-- V1 CONFIG BOUNDS (E2-V1-P02, LOW): configuracion_v1 accepted negative or absurd values. Forward-only; local stack and PG17 gate only, NOT applied to MAIN.
-- Every write (insert or update) is validated by key: known keys only, integer range per key (flags 0..1), the time zone must exist. The audit trigger of 20261022100900 already writes an audit row on
-- every UPDATE (the old and the new value); a refused write rolls back with its audit row. Existing rows are checked before the trigger exists (the migration fails closed on a bad value).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate)
-- ROLLBACK-BEGIN
-- begin;
-- drop trigger if exists trg_configuracion_v1_bounds on public.configuracion_v1;
-- drop function if exists private.tg_configuracion_v1_bounds();
-- drop function if exists private.config_v1_range(text);
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'config_bounds_trigger_missing', (case when exists (select 1 from pg_trigger where tgname = 'trg_configuracion_v1_bounds' and not tgisinternal) then 0 else 1 end)::bigint
-- union all
-- select 'config_row_out_of_bounds:' || c.clave, (case when private.config_v1_range(c.clave) is null then 1
--                                                    when c.clave = 'zona_horaria' then (case when exists (select 1 from pg_timezone_names z where z.name = c.valor_text) then 0 else 1 end)
--                                                    when c.valor_integer between (private.config_v1_range(c.clave))[1] and (private.config_v1_range(c.clave))[2] then 0 else 1 end)::bigint
--   from public.configuracion_v1 c;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regclass('public.configuracion_v1') is null or to_regprocedure('private.tg_configuracion_v1_audit()') is null then
    raise exception using errcode = 'P0001', message = 'V1_CONFIG_BOUNDS_PREFLIGHT', hint = 'apply 20261022100900 first';
  end if;
end
$$;

-- [min, max] of the integer value of a key; null = the key is not allowed
create or replace function private.config_v1_range(p_clave text)
returns integer[]
language sql
immutable
set search_path = ''
as $$
  select case
    when p_clave = 'zona_horaria' then array[0, 0]
    when p_clave in ('reto_exige_email_verificado') or p_clave ~ '^ot_gratis_[a-z_]+_activa$' then array[0, 1]
    when p_clave = 'demo_preguntas' then array[1, 20]
    when p_clave = 'demo_inicios_usuario_dia' then array[0, 1000]
    when p_clave = 'demo_inicios_ip_dia' then array[0, 10000]
    when p_clave = 'tope_ot_comprados_dia' then array[0, 100000]
    when p_clave ~ '^tope_ot_[a-z_]+_(dia|semana)$' or p_clave in ('tope_ot_gratis_dia', 'tope_ot_gratis_semana') then array[0, 100000]
    when p_clave = 'max_inmersivos_incluidos' then array[0, 50]
    when p_clave = 'max_limite_preguntas_24h' or p_clave = 'limite_preguntas_24h_si_invalido' then array[1, 10000]
    when p_clave in ('max_ot_reto_diario', 'max_ot_bonus_racha') then array[0, 10000]
    when p_clave = 'max_reservas_oficiales' then array[1, 10]
    when p_clave = 'evolucion_cada_preguntas' then array[1, 1000]
    when p_clave in ('debilidad_min_respuestas') then array[0, 1000]
    when p_clave = 'dificultad_min_respuestas' then array[1, 10000]
    when p_clave = 'dificultad_muestra' then array[10, 1000]
    when p_clave in ('dominio_medio_pct', 'dominio_alto_pct') then array[0, 100]
    when p_clave = 'ventana_nota_defecto' then array[1, 1000]
    when p_clave = 'cohorte_minima_comparativa' then array[1, 100000]
    when p_clave in ('dificultad_job_minutos', 'recalculo_admin_cooldown_min', 'ranking_job_minutos') then array[1, 1440]
    when p_clave = 'reto_hora_desbloqueo' then array[0, 23]
    when p_clave in ('bonus_racha_desde_dia', 'bonus_racha_max_dias') then array[1, 365]
    when p_clave = 'reto_reclamar_dias' then array[0, 30]
    when p_clave = 'ot_diferido_dias_validez' then array[1, 90]
    when p_clave = 'reto_edad_minima_horas' then array[0, 720]
    when p_clave = 'reto_min_respuestas_cobrar' then array[0, 1000]
    else null end
$$;
revoke all on function private.config_v1_range(text) from public, anon, authenticated;

-- existing rows first (fail closed)
do $$
declare c record; r integer[];
begin
  for c in select * from public.configuracion_v1 loop
    r := private.config_v1_range(c.clave);
    if r is null or (c.clave = 'zona_horaria' and not exists (select 1 from pg_timezone_names z where z.name = c.valor_text))
       or (c.clave <> 'zona_horaria' and (c.valor_integer is null or c.valor_integer < r[1] or c.valor_integer > r[2])) then
      raise exception using errcode = 'P0001', message = 'V1_CONFIG_BOUNDS_EXISTING_ROW', hint = c.clave;
    end if;
  end loop;
end
$$;

create or replace function private.tg_configuracion_v1_bounds()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  r integer[] := private.config_v1_range(new.clave);
begin
  if r is null then
    raise exception using errcode = '23514', message = 'CONFIG_KEY_UNKNOWN', hint = new.clave;
  end if;
  if new.clave = 'zona_horaria' then
    if new.valor_text is null or not exists (select 1 from pg_timezone_names z where z.name = new.valor_text) then
      raise exception using errcode = '23514', message = 'CONFIG_VALUE_OUT_OF_BOUNDS', hint = new.clave;
    end if;
  elsif new.valor_integer is null or new.valor_integer < r[1] or new.valor_integer > r[2] then
    raise exception using errcode = '23514', message = 'CONFIG_VALUE_OUT_OF_BOUNDS', hint = new.clave || ' ' || r[1] || '..' || r[2];
  end if;
  return new;
end;
$$;
revoke all on function private.tg_configuracion_v1_bounds() from public, anon, authenticated;
drop trigger if exists trg_configuracion_v1_bounds on public.configuracion_v1;
create trigger trg_configuracion_v1_bounds before insert or update on public.configuracion_v1 for each row execute function private.tg_configuracion_v1_bounds();

commit;
