-- OposiControl tag search v1
-- Ejecutar sobre la base actual para habilitar busqueda global por etiquetas automaticas.

create table if not exists public.tag (
  id integer generated always as identity primary key,
  nombre character varying not null,
  slug character varying not null unique,
  origen character varying not null default 'auto',
  created_at timestamp with time zone not null default now()
);

create table if not exists public.search_index (
  id bigint generated always as identity primary key,
  entity_type character varying not null,
  entity_id character varying not null,
  section character varying not null,
  title character varying not null,
  summary text not null default '',
  searchable_text text not null default '',
  updated_at timestamp with time zone not null default now(),
  constraint search_index_entity_unique unique (entity_type, entity_id)
);

create table if not exists public.entity_tag (
  id bigint generated always as identity primary key,
  tag_id integer not null references public.tag(id) on delete cascade,
  entity_type character varying not null,
  entity_id character varying not null,
  score numeric not null default 1,
  created_at timestamp with time zone not null default now(),
  constraint entity_tag_unique unique (tag_id, entity_type, entity_id)
);

create index if not exists idx_tag_slug on public.tag (slug);
create index if not exists idx_tag_nombre on public.tag (nombre);
create index if not exists idx_entity_tag_entity on public.entity_tag (entity_type, entity_id);
create index if not exists idx_entity_tag_tag_type on public.entity_tag (tag_id, entity_type);
create index if not exists idx_search_index_entity on public.search_index (entity_type, entity_id);
create index if not exists idx_search_index_section on public.search_index (section);
create index if not exists idx_search_index_updated_at on public.search_index (updated_at desc);
create index if not exists idx_search_index_text_gin
  on public.search_index
  using gin (to_tsvector('simple', searchable_text));

alter table public.tag enable row level security;
alter table public.entity_tag enable row level security;
alter table public.search_index enable row level security;

drop policy if exists "tag read authenticated" on public.tag;
create policy "tag read authenticated" on public.tag
for select to authenticated using (true);

drop policy if exists "tag service write" on public.tag;
create policy "tag service write" on public.tag
for all to service_role using (true) with check (true);

drop policy if exists "entity tag read authenticated" on public.entity_tag;
create policy "entity tag read authenticated" on public.entity_tag
for select to authenticated using (true);

drop policy if exists "entity tag service write" on public.entity_tag;
create policy "entity tag service write" on public.entity_tag
for all to service_role using (true) with check (true);

drop policy if exists "search index read authenticated" on public.search_index;
create policy "search index read authenticated" on public.search_index
for select to authenticated using (true);

drop policy if exists "search index service write" on public.search_index;
create policy "search index service write" on public.search_index
for all to service_role using (true) with check (true);

-- Despues de ejecutar esta migracion, llamar al backend:
-- POST /search/reindex
-- para generar tag, entity_tag y search_index desde los datos existentes.
