-- MAIN objects of the engagement area that the generated bootstrap subset does not carry (shapes from the structure-only reads of MAIN, 2026-10-16: columns, constraints, indexes, policies, grants).
-- Loaded by the PG17 gate after main_shape_subset_extra_plans.sql, and by the local stack (up.mjs, converge.mjs). IDEMPOTENT and safe on a live local DB. NOT a migration: MAIN already has these objects.
-- The MAIN client grants/policies are reproduced ON PURPOSE (registros_reto_diario writable by clients, ranking_liga and preguntas_reto_diario readable by every authenticated user, recalcular_* executable by
-- anon/authenticated): migration 20261022101200 removes them and the gate proves the removal.
create table if not exists public.retos_diarios (
  id uuid primary key default gen_random_uuid(),
  fecha date not null,
  titulo text not null,
  descripcion text,
  objetivo_preguntas integer not null default 3,
  recompensa_puntos integer not null default 0,
  activo boolean not null default true,
  creado_en timestamptz not null default now(),
  aciertos_necesarios integer not null default 2,
  empieza_en timestamptz,
  termina_en timestamptz,
  especialidad_id uuid references public.especialidades(id),
  comunidad_id uuid references public.comunidades_autonomas(id),
  segmento_usuario text,
  metadatos jsonb not null default '{}'::jsonb,
  constraint retos_diarios_fecha_key unique (fecha),
  constraint retos_diarios_segmento_usuario_check check (segmento_usuario is null or segmento_usuario = any (array['todos'::text, 'gratis'::text, 'suscritos'::text]))
);
create index if not exists idx_retos_diarios_comunidad_id on public.retos_diarios (comunidad_id);
create index if not exists idx_retos_diarios_especialidad_id on public.retos_diarios (especialidad_id);

create table if not exists public.preguntas_reto_diario (
  reto_id uuid not null references public.retos_diarios(id) on delete cascade,
  pregunta_id uuid not null references public.preguntas(id) on delete cascade,
  posicion integer not null,
  tipo_slot text not null,
  constraint preguntas_reto_diario_pkey primary key (reto_id, pregunta_id),
  constraint preguntas_reto_diario_reto_id_posicion_key unique (reto_id, posicion),
  constraint preguntas_reto_diario_posicion_check check (posicion > 0),
  constraint preguntas_reto_diario_canonical_v2_tipo_slot_check check (tipo_slot = any (array['inmersiva_demo'::text, 'oposicard'::text, 'rafaga'::text, 'razonada'::text]))
);
create index if not exists idx_preguntas_reto_diario_pregunta_id on public.preguntas_reto_diario (pregunta_id);

create table if not exists public.registros_reto_diario (
  usuario_id uuid not null references auth.users(id) on delete cascade,
  reto_id uuid not null references public.retos_diarios(id) on delete cascade,
  progreso integer not null default 0,
  completado boolean not null default false,
  completado_en timestamptz,
  creado_en timestamptz not null default now(),
  aciertos integer not null default 0,
  fallos integer not null default 0,
  superado boolean not null default false,
  recompensa_base_tickets integer not null default 0,
  bonus_racha_tickets integer not null default 0,
  multiplicador_aplicado numeric not null default 1,
  recompensa_total_tickets integer not null default 0,
  recompensa_entregada boolean not null default false,
  protector_usado_id uuid,
  potenciador_usado_id uuid,
  constraint registros_reto_diario_pkey primary key (usuario_id, reto_id)
);
create index if not exists idx_registros_reto_diario_reto_id on public.registros_reto_diario (reto_id);

create table if not exists public.rachas_usuario (
  usuario_id uuid primary key references auth.users(id) on delete cascade,
  racha_actual_dias integer not null default 0,
  mejor_racha_dias integer not null default 0,
  ultima_actividad_fecha date,
  actualizado_en timestamptz not null default now()
);

create table if not exists public.ligas (
  id uuid primary key default gen_random_uuid(),
  nombre text not null,
  slug text not null,
  descripcion text,
  fecha_inicio date not null,
  fecha_fin date not null,
  activa boolean not null default true,
  creado_en timestamptz not null default now(),
  constraint ligas_slug_key unique (slug),
  constraint ligas_fechas_chk check (fecha_fin >= fecha_inicio)
);
create table if not exists public.ranking_liga (
  id uuid primary key default gen_random_uuid(),
  liga_id uuid not null references public.ligas(id) on delete cascade,
  usuario_id uuid not null references auth.users(id) on delete cascade,
  puntos integer not null default 0,
  posicion integer,
  actualizado_en timestamptz not null default now(),
  constraint ranking_liga_liga_id_usuario_id_key unique (liga_id, usuario_id)
);
create index if not exists idx_ranking_liga_usuario_id on public.ranking_liga (usuario_id);

-- MAIN policies and grants, as they are TODAY
alter table public.retos_diarios enable row level security;
alter table public.preguntas_reto_diario enable row level security;
alter table public.registros_reto_diario enable row level security;
alter table public.rachas_usuario enable row level security;
alter table public.ligas enable row level security;
alter table public.ranking_liga enable row level security;
do $e$
begin
  if to_regprocedure('public.es_admin()') is not null then
    if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'registros_reto_diario') then
      create policy registros_reto_todo_propio on public.registros_reto_diario for all to authenticated using (usuario_id = (select auth.uid()) or es_admin()) with check (usuario_id = (select auth.uid()) or es_admin());
    end if;
    if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'ranking_liga') then
      create policy ranking_liga_select_auth_ligas_activas on public.ranking_liga for select to authenticated using (exists (select 1 from public.ligas l where l.id = ranking_liga.liga_id and l.activa) or es_admin());
    end if;
    if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'rachas_usuario') then
      create policy rachas_select_propio on public.rachas_usuario for select to authenticated using (usuario_id = (select auth.uid()) or es_admin());
    end if;
    if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'retos_diarios') then
      create policy retos_diarios_select_publico on public.retos_diarios for select to anon, authenticated using (activo or es_admin());
    end if;
  end if;
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'preguntas_reto_diario') then
    create policy canonical_v2_daily_challenge_read on public.preguntas_reto_diario for select to authenticated using (true);
  end if;
end
$e$;
revoke all on public.retos_diarios, public.preguntas_reto_diario, public.registros_reto_diario, public.rachas_usuario, public.ligas, public.ranking_liga from anon, authenticated;  -- the image default privileges are not MAIN's: set MAIN's exactly
grant select on public.retos_diarios, public.ligas, public.rachas_usuario, public.registros_reto_diario, public.ranking_liga to anon;
grant select on public.retos_diarios, public.preguntas_reto_diario, public.ligas, public.rachas_usuario, public.ranking_liga to authenticated;
grant select, insert, update, delete on public.registros_reto_diario to authenticated;
grant all on public.retos_diarios, public.preguntas_reto_diario, public.registros_reto_diario, public.rachas_usuario, public.ligas, public.ranking_liga to service_role;

-- The MAIN recompute functions exist with bodies that are not carried here: STUBS with the MAIN attributes (not security definer, search_path public, executable by everyone) so the
-- privilege matrix can be proven. Created only when absent (a live DB keeps whatever it has).
do $f$
begin
  if to_regprocedure('public.recalcular_estadisticas_globales_preguntas()') is null then
    create function public.recalcular_estadisticas_globales_preguntas() returns void language plpgsql set search_path = public as 'begin null; end';
    create function public.recalcular_estadisticas_globales_etiquetas() returns void language plpgsql set search_path = public as 'begin null; end';
    create function public.recalcular_estadisticas_usuario_etiquetas(p_usuario_id uuid) returns void language plpgsql set search_path = public as 'begin null; end';
    create function public.recalcular_estadisticas_usuario_globales(p_usuario_id uuid) returns void language plpgsql set search_path = public as 'begin null; end';
    create function public.recalcular_estadisticas_usuario_preguntas(p_usuario_id uuid) returns void language plpgsql set search_path = public as 'begin null; end';
    grant execute on function public.recalcular_estadisticas_globales_preguntas(), public.recalcular_estadisticas_globales_etiquetas(),
      public.recalcular_estadisticas_usuario_etiquetas(uuid), public.recalcular_estadisticas_usuario_globales(uuid), public.recalcular_estadisticas_usuario_preguntas(uuid) to anon, authenticated, service_role;
  end if;
end
$f$;
