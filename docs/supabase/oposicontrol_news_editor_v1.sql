alter table public.noticia
add column if not exists background_path character varying;

alter table public.noticia
add column if not exists deleted_at timestamp with time zone;

create table if not exists public.noticia_layer (
  id uuid primary key default gen_random_uuid(),
  noticia_id integer not null references public.noticia(id) on delete cascade,
  name character varying not null,
  kind character varying not null default 'texto',
  content text not null default '',
  x integer not null default 24,
  y integer not null default 24,
  width integer not null default 260,
  height integer not null default 54,
  opacity integer not null default 100 check (opacity between 0 and 100),
  font_family character varying not null default 'sans',
  text_color character varying not null default '',
  visible boolean not null default true,
  sort_order integer not null default 0,
  created_at timestamp with time zone not null default now(),
  updated_at timestamp with time zone not null default now(),
  deleted_at timestamp with time zone
);

create index if not exists idx_noticia_layer_noticia_id
on public.noticia_layer (noticia_id, sort_order);

alter table public.noticia_layer enable row level security;

drop policy if exists "noticia layer read authenticated" on public.noticia_layer;
create policy "noticia layer read authenticated" on public.noticia_layer
for select to authenticated using (deleted_at is null);

drop policy if exists "noticia layer write authenticated" on public.noticia_layer;
drop policy if exists "noticia layer write service role" on public.noticia_layer;
create policy "noticia layer write service role" on public.noticia_layer
for all to service_role using (true) with check (true);
