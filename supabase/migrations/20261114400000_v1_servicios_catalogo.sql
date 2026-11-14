-- =============================================================================
-- V1 -- Market services: visibility, order, section and consumable metadata (F28 step 1, FA52).
-- =============================================================================
-- Forward-only. Requires the MAIN tables public.servicios and public.activaciones_servicio_usuario (MAIN shape: supabase/baseline/main_shape_subset_extra_engagement.sql).
--
-- OWNER DECISION (2026-11-14, plan ancient-baking-melody section 6): the Market shows at most THREE sections:
--   OPOSITICKETS   "OposiTickets"                  ticket packs (public.productos_comerciales tipo pack_tickets; real-money payments stay off)
--   POTENCIADORES  "Potenciadores y protecciones"  protector_racha, protector_tickets, protector_dual, potenciador, doble_oportunidad, triple_oportunidad, carta_ccaa_comodin
--   SERVICIOS      "Servicios y accesos"           premium, correccion, tutoria, contenido_extra, modo_papel
-- Consumables are bought with OposiTickets and must work in 1.3 (effects: migration 20261114400200).
--
-- WHAT IT DOES
--   * public.servicios gets: visible boolean NOT NULL default true (hidden rows are never sold to the app), posicion integer NOT NULL default 0 (order inside
--     its section), seccion text NOT NULL (one of the 3 codes; when a write leaves it NULL the trigger derives it from tipo_servicio with
--     private.servicio_seccion_por_tipo), and the consumable metadata as typed, checked columns:
--       usos            integer  NULL or > 0   uses granted by ONE purchase (NULL = not a use-counted consumable: 1 activation, 1 use)
--       duracion_horas  integer  NULL or > 0   lifetime of ONE activation from the purchase (NULL = no expiry)
--       multiplicador   numeric  NULL or 1..10 potenciador factor on the daily-challenge base reward (NULL = no multiplier)
--     All editable in Control > Catalogo > Servicios (admin DTO + /api/v1/admin/market/servicios).
--   * Existing rows: seccion from the type, posicion by type order (x10), usos 1 for every consumable type (triple_oportunidad 2), multiplicador 2 for a
--     potenciador without one. Nothing else of an existing row changes.
--   * PLACEHOLDER SEED (owner adjusts in Control; `on conflict (codigo) do nothing`, so a row Control already created is never touched):
--       protector_racha 20 OT, protector_tickets 30 OT, protector_dual 40 OT                (client document "Arquitectura compra OposiBots" section 8)
--       potenciador_x2 30 OT x2, potenciador_x3 60 OT x3, potenciador_x4 90 OT x4           (same document: "potenciadores de un dia", single use)
--       doble_oportunidad 30 OT (1 use), triple_oportunidad 50 OT (2 uses)                  (NO price in any document: PLACEHOLDER chosen by F28)
--     modo_papel, carta_ccaa_comodin, premium, correccion, tutoria and contenido_extra are NOT seeded (no in-app effect defined; see the F28 ledger).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate). The seeded rows are data and stay (activations reference them; ON DELETE CASCADE would erase
-- purchases): delete them by hand with the owner only if nothing references them.
-- ROLLBACK-BEGIN
-- begin;
-- drop trigger if exists trg_servicios_seccion on public.servicios;
-- drop function if exists private.tg_servicios_seccion();
-- alter table public.servicios drop column if exists visible;
-- alter table public.servicios drop column if exists posicion;
-- alter table public.servicios drop column if exists seccion;
-- alter table public.servicios drop column if exists usos;
-- alter table public.servicios drop column if exists duracion_horas;
-- alter table public.servicios drop column if exists multiplicador;
-- drop function if exists private.servicio_seccion_por_tipo(text);
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'servicios_columns_missing',
--        (6 - (select count(*) from information_schema.columns where table_schema = 'public' and table_name = 'servicios'
--               and column_name in ('visible', 'posicion', 'seccion', 'usos', 'duracion_horas', 'multiplicador')))::bigint
-- union all
-- select 'servicios_seccion_outside_the_three', (select count(*) from public.servicios where seccion is null or seccion not in ('OPOSITICKETS', 'POTENCIADORES', 'SERVICIOS'))::bigint
-- union all
-- select 'servicios_seccion_check_missing', (case when exists (select 1 from pg_constraint where conname = 'servicios_seccion_check') then 0 else 1 end)::bigint
-- union all
-- select 'servicios_seccion_trigger_missing', (case when exists (select 1 from pg_trigger where tgname = 'trg_servicios_seccion' and not tgisinternal) then 0 else 1 end)::bigint
-- union all
-- select 'seccion_helper_executable_by_clients',
--        (case when has_function_privilege('anon', 'private.servicio_seccion_por_tipo(text)', 'execute')
--                or has_function_privilege('authenticated', 'private.servicio_seccion_por_tipo(text)', 'execute') then 1 else 0 end)::bigint
-- union all
-- select 'consumable_without_uses', (select count(*) from public.servicios where tipo_servicio in ('protector_racha', 'protector_tickets', 'protector_dual', 'potenciador',
--          'doble_oportunidad', 'triple_oportunidad', 'carta_ccaa_comodin') and usos is null)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regclass('public.servicios') is null or to_regclass('public.activaciones_servicio_usuario') is null then
    raise exception using errcode = 'P0001', message = 'F28_SERVICIOS_PREFLIGHT', hint = 'public.servicios / public.activaciones_servicio_usuario missing';
  end if;
end
$$;

create or replace function private.servicio_seccion_por_tipo(p_tipo text)
returns text
language sql
immutable
set search_path = ''
as $$
  select case
    when p_tipo in ('protector_racha', 'protector_tickets', 'protector_dual', 'potenciador', 'doble_oportunidad', 'triple_oportunidad', 'carta_ccaa_comodin') then 'POTENCIADORES'
    else 'SERVICIOS' end
$$;
revoke all on function private.servicio_seccion_por_tipo(text) from public, anon, authenticated;

alter table public.servicios add column if not exists visible boolean not null default true;
alter table public.servicios add column if not exists posicion integer not null default 0;
alter table public.servicios add column if not exists seccion text;
alter table public.servicios add column if not exists usos integer;
alter table public.servicios add column if not exists duracion_horas integer;
alter table public.servicios add column if not exists multiplicador numeric(4,2);

-- existing rows: derived defaults (nothing else changes)
update public.servicios s set
  seccion = coalesce(s.seccion, private.servicio_seccion_por_tipo(s.tipo_servicio)),
  posicion = case when s.posicion = 0 then 10 * (array_position(array['protector_racha', 'protector_tickets', 'protector_dual', 'potenciador', 'doble_oportunidad',
               'triple_oportunidad', 'carta_ccaa_comodin', 'premium', 'correccion', 'tutoria', 'contenido_extra', 'modo_papel'], s.tipo_servicio)) else s.posicion end,
  usos = coalesce(s.usos, case when s.tipo_servicio = 'triple_oportunidad' then 2
                               when private.servicio_seccion_por_tipo(s.tipo_servicio) = 'POTENCIADORES' then 1 end),
  multiplicador = coalesce(s.multiplicador, case when s.tipo_servicio = 'potenciador' then 2 end)
where s.seccion is null or s.posicion = 0 or (s.usos is null and private.servicio_seccion_por_tipo(s.tipo_servicio) = 'POTENCIADORES')
   or (s.multiplicador is null and s.tipo_servicio = 'potenciador');

alter table public.servicios alter column seccion set not null;
alter table public.servicios drop constraint if exists servicios_seccion_check;
alter table public.servicios add constraint servicios_seccion_check check (seccion in ('OPOSITICKETS', 'POTENCIADORES', 'SERVICIOS'));
alter table public.servicios drop constraint if exists servicios_posicion_check;
alter table public.servicios add constraint servicios_posicion_check check (posicion >= 0);
alter table public.servicios drop constraint if exists servicios_usos_check;
alter table public.servicios add constraint servicios_usos_check check (usos is null or usos between 1 and 100);
alter table public.servicios drop constraint if exists servicios_duracion_horas_check;
alter table public.servicios add constraint servicios_duracion_horas_check check (duracion_horas is null or duracion_horas between 1 and 8760);
alter table public.servicios drop constraint if exists servicios_multiplicador_check;
alter table public.servicios add constraint servicios_multiplicador_check check (multiplicador is null or (multiplicador >= 1 and multiplicador <= 10));

-- a write that leaves seccion NULL (an older Control, a direct upsert) gets the section of its type
create or replace function private.tg_servicios_seccion()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.seccion is null then
    new.seccion := private.servicio_seccion_por_tipo(new.tipo_servicio);
  end if;
  return new;
end;
$$;
revoke all on function private.tg_servicios_seccion() from public, anon, authenticated;
drop trigger if exists trg_servicios_seccion on public.servicios;
create trigger trg_servicios_seccion before insert or update on public.servicios for each row execute function private.tg_servicios_seccion();

-- PLACEHOLDER seed (see the header). Never overwrites a row Control already created with the same code.
insert into public.servicios (codigo, nombre, descripcion, tipo_servicio, precio_tickets, activo, visible, posicion, seccion, usos, duracion_horas, multiplicador) values
  ('protector_racha', 'Protector de racha', 'Si fallas el reto diario, tu racha sigue contando (ese dia no ganas OposiTickets). Se usa solo cuando hace falta.',
     'protector_racha', 20, true, true, 10, 'POTENCIADORES', 1, null, null),
  ('protector_tickets', 'Protector de tickets', 'Si fallas el reto diario, cobras igualmente la recompensa del dia (la racha se rompe). Se usa solo cuando hace falta.',
     'protector_tickets', 30, true, true, 20, 'POTENCIADORES', 1, null, null),
  ('protector_dual', 'Protector dual', 'Si fallas el reto diario, conservas la racha y cobras la recompensa del dia. Se usa solo cuando hace falta.',
     'protector_dual', 40, true, true, 30, 'POTENCIADORES', 1, null, null),
  ('potenciador_x2', 'Potenciador x2', 'Multiplica por 2 la recompensa base del proximo reto diario que superes. Un uso.', 'potenciador', 30, true, true, 40, 'POTENCIADORES', 1, null, 2),
  ('potenciador_x3', 'Potenciador x3', 'Multiplica por 3 la recompensa base del proximo reto diario que superes. Un uso.', 'potenciador', 60, true, true, 50, 'POTENCIADORES', 1, null, 3),
  ('potenciador_x4', 'Potenciador x4', 'Multiplica por 4 la recompensa base del proximo reto diario que superes. Un uso.', 'potenciador', 90, true, true, 60, 'POTENCIADORES', 1, null, 4),
  ('doble_oportunidad', 'Doble oportunidad', 'Si fallas una pregunta del reto diario, puedes volver a responderla una vez antes de ver la solucion.',
     'doble_oportunidad', 30, true, true, 70, 'POTENCIADORES', 1, null, null),
  ('triple_oportunidad', 'Triple oportunidad', 'Como la doble oportunidad, pero con dos usos: el que no gastes queda para otro dia.',
     'triple_oportunidad', 50, true, true, 80, 'POTENCIADORES', 2, null, null)
on conflict (codigo) do nothing;

commit;
