-- Noticias: esquema canónico del editor de composiciones.
--
-- CONTEXTO (verificado en MAIN `ndiooyyqtaeysnedywer` el 2026-09-23):
-- la vertical de Noticias está construida entera —58 intents, casos de uso,
-- repositorio, rutas Ktor y subida multipart— sobre unas tablas que NUNCA se
-- crearon. `noticia` y `tipo_noticia` no existen en ningún esquema, y ninguna
-- de las 29 migraciones del repositorio las menciona. Por eso el panel muestra
-- «No se pudo actualizar la biblioteca»: no es un fallo de red, es que
-- `GET /rest/v1/noticia` no tiene contra qué resolver.
--
-- DECISIÓN 1 — las capas dejan de viajar dentro de `texto`.
-- `NewsGateway.encodeStoredNewsText` serializa la composición completa y la
-- concatena al cuerpo de la noticia con un prefijo. Eso hace que `texto`
-- signifique dos cosas a la vez, impide consultar o indexar la composición, y
-- convierte cualquier lectura directa de la tabla en basura ilegible. Aquí la
-- composición vive en `capas jsonb`, que es consultable y validable.
--
-- DECISIÓN 2 — los catálogos son `uuid`, no `int`.
-- `especialidades.id` es `uuid` en MAIN. El backend declara
-- `SupabaseNoticiaRow.especialidadId: Int` y resuelve con `toIntOrNull()`, lo
-- que no puede funcionar contra esta base. La tabla se crea con el tipo REAL;
-- el backend se corrige en el mismo cambio. No se crea una columna `int` para
-- acomodar un defecto del cliente.
--
-- Esta migración es ADITIVA: no toca `especialidades` ni ninguna tabla viva.

-- ---------------------------------------------------------------------------
-- Catálogo de tipos de noticia
-- ---------------------------------------------------------------------------
-- El resolutor del backend busca por `slug` o por `nombre`
-- (`/rest/v1/tipo_noticia?select=id&or=(slug.eq.X,nombre.eq.X)`), así que ambas
-- columnas son obligatorias y `slug` es única: dos filas con el mismo slug
-- harían que `limit=1` eligiera una al azar y la noticia cambiara de tipo sola.
create table if not exists public.tipo_noticia (
  id uuid primary key default gen_random_uuid(),
  codigo text not null,
  nombre text not null,
  slug text not null,
  descripcion text,
  activo boolean not null default true,
  posicion integer not null default 1,
  creado_en timestamptz not null default now(),
  actualizado_en timestamptz not null default now(),
  constraint tipo_noticia_codigo_unico unique (codigo),
  constraint tipo_noticia_slug_unico unique (slug)
);

-- Los cuatro tipos son los que YA valida el dominio en
-- `ValidateNewsDraftUseCase` (NewsArticleType): convocatoria y oferta tienen
-- reglas propias de publicación, así que no son un catálogo libre.
insert into public.tipo_noticia (codigo, nombre, slug, descripcion, posicion)
values
  ('CONVOCATORIA', 'Convocatoria', 'convocatoria', 'Publicación oficial con fecha programada.', 1),
  ('OFERTA',       'Oferta',       'oferta',       'Requiere oferta o artículo vinculado y capa CTA visible.', 2),
  ('RECURSO',      'Recurso',      'recurso',      'Material de apoyo; se recomienda capa de imagen.', 3),
  ('AVISO',        'Aviso',        'aviso',        'Comunicación breve sin reglas adicionales.', 4)
on conflict (codigo) do nothing;

-- ---------------------------------------------------------------------------
-- Noticias
-- ---------------------------------------------------------------------------
create table if not exists public.noticia (
  id uuid primary key default gen_random_uuid(),
  titulo text not null,
  texto text not null default '',

  -- La composición del lienzo. Es un array de capas; el objeto suelto o el
  -- `null` entrarían y romperían al deserializar en el cliente.
  capas jsonb not null default '[]'::jsonb,

  -- Plantilla de fondo. El backend traduce SKY/MINT/GOLD ↔ A/B/C; la base
  -- guarda la letra, que es lo que `toDatabaseBackground()` envía.
  tipo_fondo text not null default 'A',

  especialidad_id uuid not null references public.especialidades (id) on delete restrict,
  tipo_noticia_id uuid not null references public.tipo_noticia (id) on delete restrict,

  -- `background_path` es el fondo del lienzo; `imagen_path` es el recurso
  -- destacado. Filas antiguas guardaban el fondo en `imagen_path` y el backend
  -- aún lo compensa al leer; aquí nacen separadas para no heredar la ambigüedad.
  background_path text,
  imagen_path text,

  estado text not null default 'draft',
  publicar_desde timestamptz,
  published_at timestamptz,

  creado_en timestamptz not null default now(),
  actualizado_en timestamptz not null default now(),

  constraint noticia_titulo_no_vacio check (length(btrim(titulo)) > 0),
  constraint noticia_estado_valido check (estado in ('draft', 'scheduled', 'published', 'archived')),
  constraint noticia_fondo_valido check (tipo_fondo in ('A', 'B', 'C')),
  constraint noticia_capas_es_array check (jsonb_typeof(capas) = 'array'),

  -- Una noticia programada sin fecha no se publicaría nunca y aparecería como
  -- pendiente para siempre. `ValidateNewsDraftUseCase` ya lo exige para
  -- convocatorias; aquí se exige para cualquier tipo programado.
  constraint noticia_programada_con_fecha check (estado <> 'scheduled' or publicar_desde is not null)
);

-- La biblioteca filtra por estado, especialidad y tipo, y ordena por fecha.
create index if not exists noticia_estado_idx on public.noticia (estado);
create index if not exists noticia_especialidad_idx on public.noticia (especialidad_id);
create index if not exists noticia_tipo_idx on public.noticia (tipo_noticia_id);
create index if not exists noticia_publicar_desde_idx on public.noticia (publicar_desde desc nulls last);

-- ---------------------------------------------------------------------------
-- `actualizado_en`
-- ---------------------------------------------------------------------------
create or replace function public.noticia_touch_actualizado_en()
returns trigger
language plpgsql
as $$
begin
  new.actualizado_en := now();
  return new;
end;
$$;

drop trigger if exists noticia_actualizado_en on public.noticia;
create trigger noticia_actualizado_en
  before update on public.noticia
  for each row execute function public.noticia_touch_actualizado_en();

drop trigger if exists tipo_noticia_actualizado_en on public.tipo_noticia;
create trigger tipo_noticia_actualizado_en
  before update on public.tipo_noticia
  for each row execute function public.noticia_touch_actualizado_en();

-- ---------------------------------------------------------------------------
-- RLS — mismo patrón que `especialidades`: admin total, lectura pública acotada
-- ---------------------------------------------------------------------------
alter table public.tipo_noticia enable row level security;
alter table public.noticia enable row level security;

drop policy if exists tipo_noticia_admin_all on public.tipo_noticia;
create policy tipo_noticia_admin_all on public.tipo_noticia
  to authenticated using (es_admin()) with check (es_admin());

drop policy if exists tipo_noticia_select_publico on public.tipo_noticia;
create policy tipo_noticia_select_publico on public.tipo_noticia
  for select to authenticated, anon using (activo = true or es_admin());

drop policy if exists noticia_admin_all on public.noticia;
create policy noticia_admin_all on public.noticia
  to authenticated using (es_admin()) with check (es_admin());

-- Un borrador NO es contenido público, y una noticia programada tampoco lo es
-- hasta que llega su fecha. Sin la comprobación de `publicar_desde`, programar
-- sería lo mismo que publicar: OposiBot la serviría en cuanto se guardara.
drop policy if exists noticia_select_publico on public.noticia;
create policy noticia_select_publico on public.noticia
  for select to authenticated, anon
  using (
    es_admin()
    or (estado = 'published' and (publicar_desde is null or publicar_desde <= now()))
  );

-- ---------------------------------------------------------------------------
-- Postcondición: un `commit` limpio no prueba que quedara instalado.
-- Mismo patrón que `20260921071916_role_sync_guard.sql`.
-- ---------------------------------------------------------------------------
do $$
begin
  if to_regclass('public.noticia') is null or to_regclass('public.tipo_noticia') is null then
    raise exception 'NEWS_SCHEMA_NOT_INSTALLED';
  end if;

  if (select count(*) from public.tipo_noticia) < 4 then
    raise exception 'NEWS_SCHEMA_CATALOG_EMPTY';
  end if;

  if not exists (
    select 1 from pg_policy p join pg_class c on c.oid = p.polrelid
    where c.relname = 'noticia' and p.polname = 'noticia_select_publico'
  ) then
    raise exception 'NEWS_SCHEMA_RLS_MISSING';
  end if;

  -- La FK a `especialidades` es el punto exacto donde fallaba el diseño
  -- anterior (`int` contra `uuid`). Si no quedó creada, la tabla es inservible.
  if not exists (
    select 1 from pg_constraint
    where conrelid = 'public.noticia'::regclass and contype = 'f'
      and confrelid = 'public.especialidades'::regclass
  ) then
    raise exception 'NEWS_SCHEMA_FK_ESPECIALIDAD_MISSING';
  end if;
end;
$$;
