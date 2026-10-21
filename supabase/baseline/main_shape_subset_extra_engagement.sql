-- MAIN objects of the engagement area that the generated bootstrap subset does not carry (shapes from the structure-only reads of MAIN, 2026-10-16: columns, constraints, indexes, policies, grants).
-- Loaded by the PG17 gate after main_shape_subset_extra_plans.sql, and by the local stack (up.mjs, converge.mjs). IDEMPOTENT and safe on a live local DB. NOT a migration: MAIN already has these objects.
-- The MAIN client grants/policies are reproduced ON PURPOSE (registros_reto_diario writable by clients, ranking_liga and preguntas_reto_diario readable by every authenticated user, recalcular_* executable by
-- anon/authenticated): migration 20261022101200 removes them and the gate proves the removal.
-- Client ACLs and policies of every table/function below are MAIN's VERBATIM (READ-ONLY catalog capture of MAIN, 2026-10-14: baseline_main.sql, sha256 07acead5...), with MAIN's
-- grantee order, so the gate's ROLLBACK round trip compares against MAIN's real ACL/policy text (V1-DEPLOY-REHEARSAL finding 2026-10-21: the hand-written grants hid three wrong ROLLBACKs).
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
      create policy ranking_liga_select_auth_ligas_activas on public.ranking_liga for select to authenticated using (exists (select 1 from public.ligas l where l.id = ranking_liga.liga_id and l.activa = true) or es_admin());
    end if;
    if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'rachas_usuario') then
      create policy rachas_select_propio on public.rachas_usuario for select to authenticated using (usuario_id = (select auth.uid()) or es_admin());
    end if;
    if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'retos_diarios') then
      create policy retos_diarios_select_publico on public.retos_diarios for select to anon, authenticated using (activo = true or es_admin());
    end if;
    if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'ligas') then
      create policy ligas_select_publico on public.ligas for select to anon, authenticated using (activa = true or es_admin());
    end if;
  end if;
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'preguntas_reto_diario') then
    create policy canonical_v2_daily_challenge_read on public.preguntas_reto_diario for select to authenticated using (true);
  end if;
end
$e$;
-- the image default privileges are not MAIN's: set MAIN's exactly (capture 2026-10-14, verbatim, MAIN's grantee order)
revoke all on table public.retos_diarios, public.preguntas_reto_diario, public.registros_reto_diario, public.rachas_usuario, public.ligas, public.ranking_liga from public, anon, authenticated, service_role;
grant delete, insert, maintain, references, select, trigger, truncate, update on table public.retos_diarios, public.preguntas_reto_diario, public.registros_reto_diario, public.rachas_usuario, public.ligas, public.ranking_liga to service_role;
grant maintain, select on table public.retos_diarios, public.rachas_usuario, public.ligas, public.ranking_liga to anon, authenticated;
grant select on table public.preguntas_reto_diario to authenticated;
grant delete, insert, maintain, select, update on table public.registros_reto_diario to authenticated;
grant maintain, select on table public.registros_reto_diario to anon;

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
    -- MAIN's ACL verbatim (capture 2026-10-14): {postgres=X, =X, anon=X, authenticated=X, service_role=X}
    revoke all on function public.recalcular_estadisticas_globales_preguntas(), public.recalcular_estadisticas_globales_etiquetas(),
      public.recalcular_estadisticas_usuario_etiquetas(uuid), public.recalcular_estadisticas_usuario_globales(uuid), public.recalcular_estadisticas_usuario_preguntas(uuid) from public, anon, authenticated, service_role;
    grant execute on function public.recalcular_estadisticas_globales_preguntas(), public.recalcular_estadisticas_globales_etiquetas(),
      public.recalcular_estadisticas_usuario_etiquetas(uuid), public.recalcular_estadisticas_usuario_globales(uuid), public.recalcular_estadisticas_usuario_preguntas(uuid) to public, anon, authenticated, service_role;
  end if;
end
$f$;

-- ============================================================================================================================================================
-- W3 STATS TABLES that MAIN has and the bootstrap subset lacks (shapes from the structure-only reads of 2026-10-16 and the repository's live-shape captures; MAIN client grants and policies
-- reproduced ON PURPOSE and VERBATIM from the capture of 2026-10-14: anon SELECT without policy, authenticated own-row SELECT, lotes_actualizacion_ranking with authenticated DML behind an admin policy).
-- Migrations 20261022101200 (revokes, guarded) and 20261022101600 remove what the app must not expose; the gate proves it. IDEMPOTENT.
-- ============================================================================================================================================================
create table if not exists public.ligas_nivel (
  id uuid primary key default gen_random_uuid(),
  codigo text not null,
  nombre text not null,
  preguntas_minimas integer not null default 0,
  ventana_calculo integer not null default 50,
  activa boolean not null default true,
  posicion integer not null default 1,
  creado_en timestamptz not null default now(),
  constraint ligas_nivel_codigo_key unique (codigo),
  constraint ligas_nivel_ventana_chk check (ventana_calculo > 0)
);
create table if not exists public.estadisticas_usuario (
  usuario_id uuid primary key references auth.users(id) on delete cascade,
  examenes_finalizados integer not null default 0,
  preguntas_respondidas integer not null default 0,
  respuestas_correctas integer not null default 0,
  porcentaje_acierto numeric(5,2) not null default 0,
  puntos_totales integer not null default 0,
  nivel integer not null default 1,
  actualizado_en timestamptz not null default now()
);
create table if not exists public.estadisticas_usuario_etiqueta (
  usuario_id uuid not null references auth.users(id) on delete cascade,
  etiqueta_id uuid not null references public.etiquetas(id) on delete cascade,
  respondidas integer not null default 0,
  correctas integer not null default 0,
  falladas integer not null default 0,
  porcentaje_acierto numeric(5,2) not null default 0,
  nivel_dominio text,
  ultima_practica_en timestamptz,
  actualizado_en timestamptz not null default now(),
  primary key (usuario_id, etiqueta_id)
);
create index if not exists idx_estadisticas_usuario_etiqueta_etiqueta_id on public.estadisticas_usuario_etiqueta (etiqueta_id);
create table if not exists public.estadisticas_globales_pregunta (
  pregunta_id uuid primary key references public.preguntas(id) on delete cascade,
  total_respuestas integer not null default 0,
  total_aciertos integer not null default 0,
  total_fallos integer not null default 0,
  total_respuestas_ranking integer not null default 0,
  total_aciertos_ranking integer not null default 0,
  total_fallos_ranking integer not null default 0,
  porcentaje_acierto numeric(5,2) not null default 0,
  porcentaje_fallo numeric(5,2) not null default 0,
  tiempo_medio_respuesta_segundos numeric(8,2) not null default 0,
  dificultad_calculada_1_10 numeric(4,2) not null default 1,
  actualizado_en timestamptz not null default now(),
  dificultad_calculada_1_5 integer,
  puntos_fallo_1_5 integer,
  puntos_tiempo_1_5 integer,
  muestra_ultimos_usuarios integer not null default 100,
  mediana_tiempo_respuesta_segundos numeric,
  constraint estadisticas_globales_pregunta_dificultad_calculada_1_10_check check (dificultad_calculada_1_10 >= 1 and dificultad_calculada_1_10 <= 10),
  constraint estadisticas_globales_pregunta_dificultad_calculada_1_5_check check (dificultad_calculada_1_5 is null or (dificultad_calculada_1_5 >= 1 and dificultad_calculada_1_5 <= 5))
);
create table if not exists public.estado_competitivo_usuario (
  usuario_id uuid primary key references auth.users(id) on delete cascade,
  liga_nivel_id uuid references public.ligas_nivel(id),
  preguntas_validas_ranking integer not null default 0,
  ventana_calculo integer,
  nota_media_actual numeric(4,2),
  nota_referencia_evolucion numeric(4,2),
  evolucion_nota numeric(5,2),
  preguntas_desde_ultima_evolucion integer not null default 0,
  ranking_liga_posicion integer,
  ranking_global_posicion integer,
  percentil_global numeric(5,2),
  actualizado_en timestamptz not null default now()
);
create index if not exists idx_estado_competitivo_usuario_liga_nivel_id on public.estado_competitivo_usuario (liga_nivel_id);
create table if not exists public.lotes_actualizacion_ranking (
  id uuid primary key default gen_random_uuid(),
  tipo text not null,
  estado text not null default 'en_curso',
  iniciado_en timestamptz not null default now(),
  finalizado_en timestamptz,
  filas integer,
  detalle jsonb not null default '{}'::jsonb,
  error text
);
alter table public.ligas_nivel enable row level security;
alter table public.estadisticas_usuario enable row level security;
alter table public.estadisticas_usuario_etiqueta enable row level security;
alter table public.estadisticas_globales_pregunta enable row level security;
alter table public.estado_competitivo_usuario enable row level security;
alter table public.lotes_actualizacion_ranking enable row level security;
do $s$
begin
  if to_regprocedure('public.es_admin()') is not null then
    if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'estadisticas_usuario') then
      create policy estadisticas_select_propio on public.estadisticas_usuario for select to authenticated using (usuario_id = (select auth.uid()) or es_admin());
    end if;
    if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'estadisticas_usuario_etiqueta') then
      create policy estadisticas_usuario_etiqueta_select_propio on public.estadisticas_usuario_etiqueta for select to authenticated using (usuario_id = (select auth.uid()) or es_admin());
    end if;
    if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'estado_competitivo_usuario') then
      create policy canonical_v2_competitivo_select on public.estado_competitivo_usuario for select to authenticated using ((select auth.uid()) = usuario_id);
    end if;
    if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'ligas_nivel') then
      create policy canonical_v2_leagues_public_read on public.ligas_nivel for select to anon, authenticated using (activa);
    end if;
    if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'estadisticas_globales_pregunta') then
      create policy estadisticas_globales_pregunta_select_auth on public.estadisticas_globales_pregunta for select to authenticated using (true);
    end if;
    if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'lotes_actualizacion_ranking') then
      create policy lotes_actualizacion_ranking_staff_all on public.lotes_actualizacion_ranking for all to authenticated using (es_admin()) with check (es_admin());
    end if;
  end if;
end
$s$;
revoke all on table public.ligas_nivel, public.estadisticas_usuario, public.estadisticas_usuario_etiqueta, public.estadisticas_globales_pregunta, public.estado_competitivo_usuario, public.lotes_actualizacion_ranking from public, anon, authenticated, service_role;
grant delete, insert, maintain, references, select, trigger, truncate, update on table public.ligas_nivel, public.estadisticas_usuario, public.estadisticas_usuario_etiqueta, public.estadisticas_globales_pregunta, public.estado_competitivo_usuario, public.lotes_actualizacion_ranking to service_role;
grant select on table public.ligas_nivel to anon, authenticated;
grant maintain, select on table public.estadisticas_usuario, public.estadisticas_usuario_etiqueta, public.estadisticas_globales_pregunta to anon, authenticated;
grant select on table public.estado_competitivo_usuario to authenticated;
grant delete, insert, maintain, select, update on table public.lotes_actualizacion_ranking to authenticated;
grant maintain, select on table public.lotes_actualizacion_ranking to anon;

-- W5b FORO: MAIN shapes of the forum tables (E1-V1-MAIN-FACTS-FORO-REFERRAL.md, structure read), WITH MAIN's client policies and grants, so the gate proves that 20261022101900 closes them.
create table if not exists public.categorias_oposired (id uuid primary key default gen_random_uuid(), nombre text not null, slug text not null unique, descripcion text, activa boolean not null default true, posicion integer not null default 1, creado_en timestamptz not null default now());
create table if not exists public.publicaciones_oposired (
  id uuid primary key default gen_random_uuid(), usuario_id uuid references auth.users(id) on delete set null, categoria_id uuid references public.categorias_oposired(id) on delete set null,
  titulo text, contenido text not null, estado text not null default 'publicada' check (estado in ('borrador', 'publicada', 'oculta', 'eliminada')), fijada boolean not null default false,
  metadatos jsonb not null default '{}'::jsonb, creado_en timestamptz not null default now(), actualizado_en timestamptz not null default now());
create table if not exists public.comentarios_oposired (
  id uuid primary key default gen_random_uuid(), publicacion_id uuid not null references public.publicaciones_oposired(id) on delete cascade, usuario_id uuid references auth.users(id) on delete set null,
  comentario_padre_id uuid references public.comentarios_oposired(id) on delete cascade, contenido text not null, estado text not null default 'publicado' check (estado in ('publicado', 'oculto', 'eliminado')),
  creado_en timestamptz not null default now(), actualizado_en timestamptz not null default now());
create table if not exists public.reacciones_oposired (
  id uuid primary key default gen_random_uuid(), usuario_id uuid not null references auth.users(id) on delete cascade, publicacion_id uuid references public.publicaciones_oposired(id) on delete cascade,
  comentario_id uuid references public.comentarios_oposired(id) on delete cascade, tipo_reaccion text not null check (tipo_reaccion in ('me_gusta', 'util', 'apoyo')), creado_en timestamptz not null default now(),
  constraint reacciones_objetivo_chk check ((publicacion_id is not null)::int + (comentario_id is not null)::int = 1),
  constraint reacciones_oposired_pub_uq unique (usuario_id, publicacion_id, tipo_reaccion), constraint reacciones_oposired_com_uq unique (usuario_id, comentario_id, tipo_reaccion));
alter table public.categorias_oposired enable row level security;
alter table public.publicaciones_oposired enable row level security;
alter table public.comentarios_oposired enable row level security;
alter table public.reacciones_oposired enable row level security;
do $foro$
begin
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'categorias_oposired') then
    create policy categorias_oposired_select_publico on public.categorias_oposired for select to anon, authenticated using (activa = true or public.es_admin());
    create policy publicaciones_oposired_select_publico on public.publicaciones_oposired for select to anon, authenticated using (estado = 'publicada' or public.es_moderador_o_admin());
    create policy publicaciones_oposired_insert_auth on public.publicaciones_oposired for insert to authenticated with check (usuario_id = (select auth.uid()));
    create policy publicaciones_oposired_update_propias on public.publicaciones_oposired for update to authenticated using (usuario_id = (select auth.uid()) or public.es_moderador_o_admin()) with check (usuario_id = (select auth.uid()) or public.es_moderador_o_admin());
    create policy comentarios_oposired_select_publico on public.comentarios_oposired for select to anon, authenticated using (estado = 'publicado' or public.es_moderador_o_admin());
    create policy comentarios_oposired_insert_auth on public.comentarios_oposired for insert to authenticated with check (usuario_id = (select auth.uid()));
    create policy comentarios_oposired_update_propios on public.comentarios_oposired for update to authenticated using (usuario_id = (select auth.uid()) or public.es_moderador_o_admin()) with check (usuario_id = (select auth.uid()) or public.es_moderador_o_admin());
    create policy reacciones_oposired_todo_propias on public.reacciones_oposired for all to authenticated using (usuario_id = (select auth.uid()) or public.es_moderador_o_admin()) with check (usuario_id = (select auth.uid()) or public.es_moderador_o_admin());
  end if;
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'acciones_moderacion') then
    alter table public.acciones_moderacion enable row level security;
    create policy acciones_moderacion_staff_all on public.acciones_moderacion for all to authenticated using (public.es_moderador_o_admin()) with check (public.es_moderador_o_admin());
  end if;
end
$foro$;
-- MAIN's client ACL verbatim (capture 2026-10-14, MAIN's grantee order): anon SELECT/MAINTAIN, authenticated without TRUNCATE/REFERENCES/TRIGGER (NOT the image default privileges)
revoke all on table public.categorias_oposired, public.publicaciones_oposired, public.comentarios_oposired, public.reacciones_oposired from public, anon, authenticated, service_role;
grant delete, insert, maintain, references, select, trigger, truncate, update on table public.categorias_oposired, public.publicaciones_oposired, public.comentarios_oposired, public.reacciones_oposired to service_role;
grant maintain, select on table public.categorias_oposired to anon, authenticated;
grant delete, insert, maintain, select, update on table public.publicaciones_oposired, public.comentarios_oposired, public.reacciones_oposired to authenticated;
grant maintain, select on table public.publicaciones_oposired, public.comentarios_oposired, public.reacciones_oposired to anon;

-- V1 P3 (E2, 20261022102700): the Bot-owned user tables that MAIN has and the bootstrap subset lacks. Statements VERBATIM from the read-only MAIN catalog capture of 2026-10-14
-- (baseline_main.sql), MAIN client grants and policies INCLUDED (authenticated INSERT/UPDATE/DELETE, policies own OR es_admin()): 20261022102700 revokes the writes and the gate proves it.
-- Each table is created only when it does not exist yet (idempotent; a live local DB that already ran 20261022102700 keeps its revoked grants).
do $users$
begin
  if to_regclass('public.tokens_dispositivo') is null then
    CREATE TABLE public.tokens_dispositivo (id uuid NOT NULL DEFAULT gen_random_uuid(), usuario_id uuid NOT NULL, plataforma text NOT NULL, token_dispositivo text NOT NULL, activo boolean NOT NULL DEFAULT true, ultimo_uso_en timestamp with time zone, creado_en timestamp with time zone NOT NULL DEFAULT now());
    ALTER TABLE public.tokens_dispositivo ADD CONSTRAINT tokens_dispositivo_pkey PRIMARY KEY (id);
    ALTER TABLE public.tokens_dispositivo ADD CONSTRAINT tokens_dispositivo_token_dispositivo_key UNIQUE (token_dispositivo);
    ALTER TABLE public.tokens_dispositivo ADD CONSTRAINT tokens_dispositivo_plataforma_check CHECK ((plataforma = ANY (ARRAY['android'::text, 'ios'::text, 'web'::text])));
    ALTER TABLE public.tokens_dispositivo ADD CONSTRAINT tokens_dispositivo_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
    CREATE INDEX idx_tokens_dispositivo_usuario_id ON public.tokens_dispositivo USING btree (usuario_id);
    ALTER TABLE public.tokens_dispositivo ENABLE ROW LEVEL SECURITY;
    CREATE POLICY tokens_dispositivo_todo_propio ON public.tokens_dispositivo AS PERMISSIVE FOR ALL TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin())) WITH CHECK (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin()));
    revoke all on table public.tokens_dispositivo from public, anon, authenticated, service_role;
    GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.tokens_dispositivo TO service_role;
    GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.tokens_dispositivo TO authenticated;
    GRANT MAINTAIN, SELECT ON TABLE public.tokens_dispositivo TO anon;
  end if;
  if to_regclass('public.configuraciones_usuario') is null then
    CREATE TABLE public.configuraciones_usuario (usuario_id uuid NOT NULL, idioma text NOT NULL DEFAULT 'es'::text, tema text NOT NULL DEFAULT 'sistema'::text, notificaciones_push boolean NOT NULL DEFAULT true, notificaciones_email boolean NOT NULL DEFAULT true, sonido_activado boolean NOT NULL DEFAULT true, vibracion_activada boolean NOT NULL DEFAULT true, metadatos jsonb NOT NULL DEFAULT '{}'::jsonb, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
    ALTER TABLE public.configuraciones_usuario ADD CONSTRAINT configuraciones_usuario_pkey PRIMARY KEY (usuario_id);
    ALTER TABLE public.configuraciones_usuario ADD CONSTRAINT configuraciones_usuario_idioma_check CHECK ((idioma = ANY (ARRAY['es'::text, 'ca'::text, 'en'::text])));
    ALTER TABLE public.configuraciones_usuario ADD CONSTRAINT configuraciones_usuario_tema_check CHECK ((tema = ANY (ARRAY['claro'::text, 'oscuro'::text, 'sistema'::text])));
    ALTER TABLE public.configuraciones_usuario ADD CONSTRAINT configuraciones_usuario_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
    CREATE TRIGGER trg_configuraciones_usuario_actualizado_en BEFORE UPDATE ON public.configuraciones_usuario FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
    ALTER TABLE public.configuraciones_usuario ENABLE ROW LEVEL SECURITY;
    CREATE POLICY configuraciones_usuario_todo_propio ON public.configuraciones_usuario AS PERMISSIVE FOR ALL TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin())) WITH CHECK (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin()));
    revoke all on table public.configuraciones_usuario from public, anon, authenticated, service_role;
    GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.configuraciones_usuario TO service_role;
    GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.configuraciones_usuario TO authenticated;
    GRANT MAINTAIN, SELECT ON TABLE public.configuraciones_usuario TO anon;
  end if;
  if to_regclass('public.preguntas_favoritas_usuario') is null then
    CREATE TABLE public.preguntas_favoritas_usuario (usuario_id uuid NOT NULL, pregunta_id uuid NOT NULL, creado_en timestamp with time zone NOT NULL DEFAULT now());
    ALTER TABLE public.preguntas_favoritas_usuario ADD CONSTRAINT preguntas_favoritas_usuario_pkey PRIMARY KEY (usuario_id, pregunta_id);
    ALTER TABLE public.preguntas_favoritas_usuario ADD CONSTRAINT preguntas_favoritas_usuario_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES public.preguntas(id) ON DELETE CASCADE;
    ALTER TABLE public.preguntas_favoritas_usuario ADD CONSTRAINT preguntas_favoritas_usuario_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
    CREATE INDEX idx_preguntas_favoritas_usuario_pregunta_id ON public.preguntas_favoritas_usuario USING btree (pregunta_id);
    ALTER TABLE public.preguntas_favoritas_usuario ENABLE ROW LEVEL SECURITY;
    CREATE POLICY favoritas_todo_propio ON public.preguntas_favoritas_usuario AS PERMISSIVE FOR ALL TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin())) WITH CHECK (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin()));
    revoke all on table public.preguntas_favoritas_usuario from public, anon, authenticated, service_role;
    GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.preguntas_favoritas_usuario TO service_role;
    GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.preguntas_favoritas_usuario TO authenticated;
    GRANT MAINTAIN, SELECT ON TABLE public.preguntas_favoritas_usuario TO anon;
  end if;
  if to_regclass('public.cola_repaso_usuario') is null then
    CREATE TABLE public.cola_repaso_usuario (id uuid NOT NULL DEFAULT gen_random_uuid(), usuario_id uuid NOT NULL, pregunta_id uuid NOT NULL, origen text NOT NULL, prioridad integer NOT NULL DEFAULT 50, estado text NOT NULL DEFAULT 'pendiente'::text, programada_para timestamp with time zone, completada_en timestamp with time zone, descartada_en timestamp with time zone, metadatos jsonb NOT NULL DEFAULT '{}'::jsonb, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
    ALTER TABLE public.cola_repaso_usuario ADD CONSTRAINT cola_repaso_usuario_pkey PRIMARY KEY (id);
    ALTER TABLE public.cola_repaso_usuario ADD CONSTRAINT cola_repaso_usuario_usuario_id_pregunta_id_origen_key UNIQUE (usuario_id, pregunta_id, origen);
    ALTER TABLE public.cola_repaso_usuario ADD CONSTRAINT cola_repaso_usuario_estado_check CHECK ((estado = ANY (ARRAY['pendiente'::text, 'vista'::text, 'completada'::text, 'descartada'::text])));
    ALTER TABLE public.cola_repaso_usuario ADD CONSTRAINT cola_repaso_usuario_origen_check CHECK ((origen = ANY (ARRAY['fallo'::text, 'lentitud'::text, 'baja_confianza'::text, 'etiqueta_debil'::text, 'marcada_manual'::text, 'recomendacion'::text])));
    ALTER TABLE public.cola_repaso_usuario ADD CONSTRAINT cola_repaso_usuario_prioridad_check CHECK (((prioridad >= 0) AND (prioridad <= 100)));
    ALTER TABLE public.cola_repaso_usuario ADD CONSTRAINT cola_repaso_usuario_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES public.preguntas(id) ON DELETE CASCADE;
    ALTER TABLE public.cola_repaso_usuario ADD CONSTRAINT cola_repaso_usuario_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
    CREATE INDEX idx_cola_repaso_usuario_estado ON public.cola_repaso_usuario USING btree (usuario_id, estado, prioridad DESC);
    CREATE INDEX idx_cola_repaso_usuario_pregunta_id ON public.cola_repaso_usuario USING btree (pregunta_id);
    CREATE TRIGGER trg_cola_repaso_usuario_actualizado_en BEFORE UPDATE ON public.cola_repaso_usuario FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
    ALTER TABLE public.cola_repaso_usuario ENABLE ROW LEVEL SECURITY;
    CREATE POLICY cola_repaso_select_propio ON public.cola_repaso_usuario AS PERMISSIVE FOR SELECT TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin()));
    CREATE POLICY cola_repaso_update_propio ON public.cola_repaso_usuario AS PERMISSIVE FOR UPDATE TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin())) WITH CHECK (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin()));
    revoke all on table public.cola_repaso_usuario from public, anon, authenticated, service_role;
    GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.cola_repaso_usuario TO service_role;
    GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.cola_repaso_usuario TO authenticated;
    GRANT MAINTAIN, SELECT ON TABLE public.cola_repaso_usuario TO anon;
  end if;
  if to_regclass('public.recomendaciones_usuario') is null then
    CREATE TABLE public.recomendaciones_usuario (id uuid NOT NULL DEFAULT gen_random_uuid(), usuario_id uuid NOT NULL, tipo_recomendacion text NOT NULL, titulo text NOT NULL, descripcion text, entidad_tipo text, entidad_id uuid, motivo text, prioridad integer NOT NULL DEFAULT 50, estado text NOT NULL DEFAULT 'mostrada'::text, mostrada_en timestamp with time zone, aceptada_en timestamp with time zone, ignorada_en timestamp with time zone, descartada_en timestamp with time zone, resultado_posterior jsonb NOT NULL DEFAULT '{}'::jsonb, metadatos jsonb NOT NULL DEFAULT '{}'::jsonb, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
    ALTER TABLE public.recomendaciones_usuario ADD CONSTRAINT recomendaciones_usuario_pkey PRIMARY KEY (id);
    ALTER TABLE public.recomendaciones_usuario ADD CONSTRAINT recomendaciones_usuario_entidad_tipo_check CHECK (((entidad_tipo IS NULL) OR (entidad_tipo = ANY (ARRAY['etiqueta'::text, 'pregunta'::text, 'examen'::text, 'opositeca_item'::text, 'servicio'::text]))));
    ALTER TABLE public.recomendaciones_usuario ADD CONSTRAINT recomendaciones_usuario_estado_check CHECK ((estado = ANY (ARRAY['generada'::text, 'mostrada'::text, 'aceptada'::text, 'ignorada'::text, 'descartada'::text])));
    ALTER TABLE public.recomendaciones_usuario ADD CONSTRAINT recomendaciones_usuario_prioridad_check CHECK (((prioridad >= 0) AND (prioridad <= 100)));
    ALTER TABLE public.recomendaciones_usuario ADD CONSTRAINT recomendaciones_usuario_tipo_recomendacion_check CHECK ((tipo_recomendacion = ANY (ARRAY['repasar_etiqueta'::text, 'hacer_rafaga'::text, 'hacer_inmersivo'::text, 'repasar_fallos'::text, 'descansar'::text, 'premium'::text])));
    ALTER TABLE public.recomendaciones_usuario ADD CONSTRAINT recomendaciones_usuario_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
    CREATE INDEX idx_recomendaciones_usuario_estado ON public.recomendaciones_usuario USING btree (usuario_id, estado, prioridad DESC);
    CREATE TRIGGER trg_recomendaciones_usuario_actualizado_en BEFORE UPDATE ON public.recomendaciones_usuario FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
    ALTER TABLE public.recomendaciones_usuario ENABLE ROW LEVEL SECURITY;
    CREATE POLICY recomendaciones_select_propio ON public.recomendaciones_usuario AS PERMISSIVE FOR SELECT TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin()));
    CREATE POLICY recomendaciones_update_propio ON public.recomendaciones_usuario AS PERMISSIVE FOR UPDATE TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin())) WITH CHECK (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin()));
    revoke all on table public.recomendaciones_usuario from public, anon, authenticated, service_role;
    GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.recomendaciones_usuario TO service_role;
    GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.recomendaciones_usuario TO authenticated;
    GRANT MAINTAIN, SELECT ON TABLE public.recomendaciones_usuario TO anon;
  end if;
  if to_regclass('public.uso_recursos_pregunta') is null then
    CREATE TABLE public.uso_recursos_pregunta (id uuid NOT NULL DEFAULT gen_random_uuid(), usuario_id uuid, intento_id uuid, pregunta_id uuid NOT NULL, recurso_id uuid NOT NULL, accion text NOT NULL, tiempo_visualizacion_segundos integer, porcentaje_consumido numeric(5,2), plataforma text, version_app text, metadatos jsonb NOT NULL DEFAULT '{}'::jsonb, creado_en timestamp with time zone NOT NULL DEFAULT now());
    ALTER TABLE public.uso_recursos_pregunta ADD CONSTRAINT uso_recursos_pregunta_pkey PRIMARY KEY (id);
    ALTER TABLE public.uso_recursos_pregunta ADD CONSTRAINT uso_recursos_pregunta_accion_check CHECK ((accion = ANY (ARRAY['abierto'::text, 'cerrado'::text, 'reproducido'::text, 'pausado'::text, 'completado'::text, 'error_carga'::text])));
    ALTER TABLE public.uso_recursos_pregunta ADD CONSTRAINT uso_recursos_pregunta_plataforma_check CHECK (((plataforma IS NULL) OR (plataforma = ANY (ARRAY['android'::text, 'ios'::text, 'web'::text]))));
    ALTER TABLE public.uso_recursos_pregunta ADD CONSTRAINT uso_recursos_pregunta_porcentaje_consumido_check CHECK (((porcentaje_consumido IS NULL) OR ((porcentaje_consumido >= (0)::numeric) AND (porcentaje_consumido <= (100)::numeric))));
    ALTER TABLE public.uso_recursos_pregunta ADD CONSTRAINT uso_recursos_pregunta_tiempo_visualizacion_segundos_check CHECK (((tiempo_visualizacion_segundos IS NULL) OR (tiempo_visualizacion_segundos >= 0)));
    ALTER TABLE public.uso_recursos_pregunta ADD CONSTRAINT uso_recursos_pregunta_intento_id_fkey FOREIGN KEY (intento_id) REFERENCES public.intentos_examen(id) ON DELETE CASCADE;
    ALTER TABLE public.uso_recursos_pregunta ADD CONSTRAINT uso_recursos_pregunta_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES public.preguntas(id) ON DELETE CASCADE;
    ALTER TABLE public.uso_recursos_pregunta ADD CONSTRAINT uso_recursos_pregunta_recurso_id_fkey FOREIGN KEY (recurso_id) REFERENCES public.recursos_pregunta(id) ON DELETE CASCADE;
    ALTER TABLE public.uso_recursos_pregunta ADD CONSTRAINT uso_recursos_pregunta_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE SET NULL;
    CREATE INDEX idx_uso_recursos_pregunta ON public.uso_recursos_pregunta USING btree (pregunta_id);
    CREATE INDEX idx_uso_recursos_pregunta_intento_id ON public.uso_recursos_pregunta USING btree (intento_id);
    CREATE INDEX idx_uso_recursos_recurso ON public.uso_recursos_pregunta USING btree (recurso_id);
    CREATE INDEX idx_uso_recursos_usuario ON public.uso_recursos_pregunta USING btree (usuario_id);
    ALTER TABLE public.uso_recursos_pregunta ENABLE ROW LEVEL SECURITY;
    CREATE POLICY uso_recursos_insert_propio ON public.uso_recursos_pregunta AS PERMISSIVE FOR INSERT TO authenticated WITH CHECK (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin()));
    CREATE POLICY uso_recursos_select_staff_o_propio ON public.uso_recursos_pregunta AS PERMISSIVE FOR SELECT TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin()));
    revoke all on table public.uso_recursos_pregunta from public, anon, authenticated, service_role;
    GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.uso_recursos_pregunta TO service_role;
    GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.uso_recursos_pregunta TO authenticated;
    GRANT MAINTAIN, SELECT ON TABLE public.uso_recursos_pregunta TO anon;
  end if;
end
$users$;
