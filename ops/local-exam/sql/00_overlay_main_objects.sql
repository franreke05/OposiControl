-- EX16 (LOCAL ONLY). Objetos de MAIN que el runtime de examen lee y que quedan FUERA de
-- supabase/baseline/main_shape_subset.sql. Forma copiada de supabase/canonical/dry_run_fixture/001_live_shape.sql y
-- 002_content_live_shape.sql (replica de la forma viva de MAIN) + el CHECK de ubicacion de Q3
-- (20260917122503_questions_resources_location_chk.sql). NO es una migracion: nunca se aplica fuera del stack local.

create table if not exists public.recursos_pregunta (
  id uuid default gen_random_uuid() not null primary key,
  pregunta_id uuid not null references public.preguntas(id) on delete cascade,
  codigo_recurso text not null,
  tipo_recurso text not null,
  titulo text,
  descripcion text,
  bucket_storage text default 'recursos-pregunta'::text,
  ruta_storage text,
  url_externa text,
  contenido_texto text,
  posicion integer default 1 not null,
  activo boolean default true not null,
  metadatos jsonb default '{}'::jsonb not null,
  creado_en timestamp with time zone default now() not null,
  actualizado_en timestamp with time zone default now() not null,
  constraint recursos_pregunta_posicion_check check (posicion > 0),
  constraint recursos_pregunta_tipo_recurso_check check (tipo_recurso = any (array['imagen'::text, 'audio'::text, 'video'::text, 'texto'::text, 'pdf'::text, 'enlace'::text])),
  constraint recursos_pregunta_ubicacion_por_tipo_chk check (
    case
      when tipo_recurso = 'texto' then contenido_texto is not null and ruta_storage is null and url_externa is null
      when tipo_recurso = 'enlace' then url_externa is not null and ruta_storage is null
      else ruta_storage is not null and url_externa is null
    end)
);
create index if not exists idx_recursos_pregunta_pregunta on public.recursos_pregunta (pregunta_id);
alter table public.recursos_pregunta enable row level security;
revoke all on table public.recursos_pregunta from anon, authenticated;
grant select, insert, update, delete on table public.recursos_pregunta to service_role;

create table if not exists public.capacidades_especialidad (
  especialidad_id uuid primary key references public.especialidades(id) on delete cascade,
  permite_rafagas boolean not null default true,
  permite_razonados boolean not null default true,
  permite_inmersivos boolean not null default true,
  permite_demos boolean not null default true,
  permite_oposimarket boolean not null default true,
  mensaje_limitacion text,
  actualizado_en timestamptz not null default now()
);
alter table public.capacidades_especialidad enable row level security;
grant select on table public.capacidades_especialidad to service_role;

create table if not exists public.estado_premium_usuario (
  usuario_id uuid primary key references auth.users(id) on delete cascade,
  es_premium boolean not null default false,
  estado_suscripcion text not null default 'sin_suscripcion',
  producto_codigo text,
  proveedor text,
  inicio_en timestamptz,
  fin_en timestamptz,
  renovacion_en timestamptz,
  actualizado_en timestamptz not null default now()
);
alter table public.estado_premium_usuario enable row level security;
grant select on table public.estado_premium_usuario to service_role;
