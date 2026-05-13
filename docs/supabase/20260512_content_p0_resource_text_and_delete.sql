-- P0 content fixes: stable text resource type and indexes for safe deletes.
-- Run in Supabase SQL editor. Idempotent where PostgreSQL allows it.

do $$
declare
    constraint_name text;
begin
    select conname into constraint_name
    from pg_constraint
    where conrelid = 'public.recurso_pregunta'::regclass
      and contype = 'c'
      and pg_get_constraintdef(oid) like '%tipo_recurso%';

    if constraint_name is not null then
        execute format('alter table public.recurso_pregunta drop constraint %I', constraint_name);
    end if;
end $$;

alter table public.recurso_pregunta
    add constraint recurso_pregunta_tipo_recurso_check
    check (tipo_recurso in ('imagen', 'audio', 'video', 'documento', 'texto'));

create index if not exists idx_recurso_pregunta_deleted_storage
    on public.recurso_pregunta (pregunta_id, deleted_at, storage_path);

create index if not exists idx_examen_cover_storage
    on public.examen (id, deleted_at, portada_storage_path);
