-- PRODUCT METRICS CANDIDATE V1 — VERSIONED RAW EVENT CONTRACT
-- eventos_app is the only raw-event table.
begin;

alter table if exists public.eventos_app
  add column if not exists event_version integer not null default 1,
  add column if not exists occurred_en timestamptz not null default now(),
  add column if not exists received_en timestamptz not null default now(),
  add column if not exists especialidad_id uuid,
  add column if not exists intento_id uuid,
  add column if not exists pregunta_id uuid,
  add column if not exists idempotency_key text;

-- Production is empty for this table and every surviving writer (Ktor) already
-- rejects an absent key. The target contract is therefore globally keyed.
alter table public.eventos_app
  alter column idempotency_key set not null;

do $$
begin
  if not exists (select 1 from pg_constraint where conrelid = 'public.eventos_app'::regclass and conname = 'eventos_app_event_version_positive') then
    alter table public.eventos_app add constraint eventos_app_event_version_positive check (event_version > 0);
  end if;
  alter table public.eventos_app drop constraint if exists eventos_app_idempotency_key_non_blank;
  alter table public.eventos_app add constraint eventos_app_idempotency_key_non_blank
    check (length(btrim(idempotency_key)) > 0);
end $$;

-- id remains the server row identity. idempotency_key is the producer's stable,
-- globally unique key for one logical event across retries and sessions.
drop index if exists public.ux_eventos_app_idempotency_key;
create unique index ux_eventos_app_idempotency_key
  on public.eventos_app (idempotency_key);

commit;
