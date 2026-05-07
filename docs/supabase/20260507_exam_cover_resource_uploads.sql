alter table public.examen
    add column if not exists portada_url text,
    add column if not exists portada_storage_path text,
    add column if not exists portada_mime_type text;

alter table public.recurso_pregunta
    add column if not exists titulo text,
    add column if not exists storage_path text,
    add column if not exists public_url text,
    add column if not exists mime_type text,
    add column if not exists orden integer not null default 0;

update public.recurso_pregunta
set
    public_url = case
        when url like 'http://%' or url like 'https://%' then url
        else public_url
    end,
    storage_path = case
        when url not like 'http://%' and url not like 'https://%' then url
        else storage_path
    end
where public_url is null
  and storage_path is null;

create index if not exists idx_recurso_pregunta_pregunta_orden
    on public.recurso_pregunta (pregunta_id, orden);
