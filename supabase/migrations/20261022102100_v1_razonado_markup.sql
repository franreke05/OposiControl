-- V1 NIGHT-B: rich text of the razonados (FA38). Contract: E1-V1-MARKUP-CONTRACT.md. The grammar and the parser live in Kotlin (RichMarkup.kt); the database stores and validates the STRUCTURE.
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Requires 20261022100000 (revisions: app_revision_pregunta_solucion) and 20261022102000 (staging bloque, import RPC).
-- soluciones_pregunta.razonamiento stays the PLAIN text everybody already reads; razonamiento_rico (jsonb, versioned, only when the text is styled) and razonamiento_markup (the authoring source,
-- so the editor round-trips) are additive. private.markup_valid(jsonb) is the whitelist validator (mirror of RichMarkup.validateJson, pinned by shared vectors in assertions.sql);
-- private.markup_plain(jsonb) derives the plain text (mirror of RichMarkup.plain) and every write must keep plain == markup_plain(rico).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate)
-- ROLLBACK-BEGIN
-- begin;
-- drop trigger if exists trg_staging_publica_markup on public.preguntas_parseadas_staging;
-- drop trigger if exists trg_staging_markup on public.preguntas_parseadas_staging;
-- drop trigger if exists trg_soluciones_markup on public.soluciones_pregunta;
-- drop function if exists private.tg_staging_publica_markup();
-- drop function if exists private.tg_staging_markup();
-- drop function if exists private.tg_soluciones_markup();
-- create or replace function private.app_revision_pregunta_solucion(p_pregunta_id uuid)
-- returns jsonb
-- language sql
-- stable
-- set search_path = ''
-- as $$
--   select jsonb_build_object('opcion_correcta_id', s.opcion_correcta_id, 'razonamiento', s.razonamiento)
--   from public.soluciones_pregunta s
--   where s.pregunta_id = p_pregunta_id
-- $$;
-- drop function if exists private.markup_plain(jsonb);
-- drop function if exists private.markup_valid(jsonb);
-- alter table public.preguntas_parseadas_staging drop column if exists razonamiento_rico, drop column if exists razonamiento_markup;
-- alter table public.soluciones_pregunta drop column if exists razonamiento_rico, drop column if exists razonamiento_markup;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'markup_functions_private_only:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('private.markup_valid(jsonb)'), ('private.markup_plain(jsonb)'), ('private.tg_soluciones_markup()'), ('private.tg_staging_markup()'), ('private.tg_staging_publica_markup()')) f(n)
-- union all
-- select 'markup_columns_missing',
--        (4 - (select count(*) from information_schema.columns c where (c.table_name, c.column_name) in (('soluciones_pregunta', 'razonamiento_rico'), ('soluciones_pregunta', 'razonamiento_markup'), ('preguntas_parseadas_staging', 'razonamiento_rico'), ('preguntas_parseadas_staging', 'razonamiento_markup'))))::bigint
-- union all
-- select 'markup_triggers_missing',
--        (3 - (select count(*) from pg_trigger t where t.tgname in ('trg_soluciones_markup', 'trg_staging_markup', 'trg_staging_publica_markup') and not t.tgisinternal))::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('private.app_revision_pregunta_solucion(uuid)') is null
     or not exists (select 1 from information_schema.columns where table_name = 'preguntas_parseadas_staging' and column_name = 'bloque') then
    raise exception using errcode = 'P0001', message = 'V1_MARKUP_PREFLIGHT', hint = 'apply 20261022100000 and 20261022102000 first';
  end if;
end
$$;

alter table public.soluciones_pregunta
  add column if not exists razonamiento_markup text check (razonamiento_markup is null or char_length(razonamiento_markup) <= 20000),
  add column if not exists razonamiento_rico jsonb;
alter table public.preguntas_parseadas_staging
  add column if not exists razonamiento_markup text check (razonamiento_markup is null or char_length(razonamiento_markup) <= 20000),
  add column if not exists razonamiento_rico jsonb;

-- ---- the whitelist validator of the stored structure (mirror of RichMarkup.validateJson)
create or replace function private.markup_valid(p jsonb)
returns boolean
language plpgsql
immutable
set search_path = ''
as $$
declare
  b jsonb;
  s jsonb;
  n integer := 0;
  t text;
  v text;
begin
  if p is null or jsonb_typeof(p) <> 'object' then return false; end if;
  if exists (select 1 from jsonb_object_keys(p) x where x not in ('v', 'blocks')) or not (p ? 'v') or not (p ? 'blocks') then return false; end if;
  if p -> 'v' <> '1'::jsonb then return false; end if;
  if jsonb_typeof(p -> 'blocks') <> 'array' or jsonb_array_length(p -> 'blocks') > 200 then return false; end if;
  for b in select e from jsonb_array_elements(p -> 'blocks') e loop
    if jsonb_typeof(b) <> 'object' then return false; end if;
    if exists (select 1 from jsonb_object_keys(b) x where x not in ('k', 'a', 'spans')) or not (b ? 'k') or not (b ? 'spans') then return false; end if;
    if jsonb_typeof(b -> 'k') <> 'string' or (b ->> 'k') not in ('p', 'li') then return false; end if;
    if b ? 'a' and (jsonb_typeof(b -> 'a') <> 'string' or (b ->> 'a') not in ('left', 'center', 'right', 'justify')) then return false; end if;
    if jsonb_typeof(b -> 'spans') <> 'array' or jsonb_array_length(b -> 'spans') = 0 then return false; end if;
    n := n + jsonb_array_length(b -> 'spans');
    if n > 2000 then return false; end if;
    for s in select e from jsonb_array_elements(b -> 'spans') e loop
      if jsonb_typeof(s) <> 'object' then return false; end if;
      if exists (select 1 from jsonb_object_keys(s) x where x not in ('text', 'bold', 'italic', 'underline', 'strike', 'color', 'size')) or not (s ? 'text') then return false; end if;
      if jsonb_typeof(s -> 'text') <> 'string' then return false; end if;
      t := s ->> 'text';
      if char_length(t) < 1 or char_length(t) > 20000 then return false; end if;
      if t ~ '[\u0000-\u001f\u007f- ​-‏ -‮⁠-⁤⁦-⁩﻿]' then return false; end if;
      v := null;
      if s ? 'bold' and s -> 'bold' <> 'true'::jsonb then return false; end if;
      if s ? 'italic' and s -> 'italic' <> 'true'::jsonb then return false; end if;
      if s ? 'underline' and s -> 'underline' <> 'true'::jsonb then return false; end if;
      if s ? 'strike' and s -> 'strike' <> 'true'::jsonb then return false; end if;
      if s ? 'color' then
        if jsonb_typeof(s -> 'color') <> 'string' then return false; end if;
        v := s ->> 'color';
        if v not in ('black', 'gray', 'red', 'orange', 'yellow', 'green', 'blue', 'purple') and v !~ '^#[0-9A-F]{6}$' then return false; end if;
      end if;
      if s ? 'size' and (jsonb_typeof(s -> 'size') <> 'string' or (s ->> 'size') not in ('xs', 'sm', 'md', 'lg', 'xl')) then return false; end if;
    end loop;
  end loop;
  return true;
end;
$$;
revoke all on function private.markup_valid(jsonb) from public, anon, authenticated;

-- ---- the plain text of a valid structure (mirror of RichMarkup.plain): paragraphs one per line, bullets prefixed with a bullet and a space
create or replace function private.markup_plain(p jsonb)
returns text
language sql
immutable
set search_path = ''
as $$
  select string_agg((case when b.e ->> 'k' = 'li' then E'• ' else '' end)
                    || (select string_agg(s.e ->> 'text', '' order by s.o) from jsonb_array_elements(b.e -> 'spans') with ordinality s(e, o)),
                    E'\n' order by b.o)
    from jsonb_array_elements(p -> 'blocks') with ordinality b(e, o)
$$;
revoke all on function private.markup_plain(jsonb) from public, anon, authenticated;

-- ---- solutions: a rich text must be valid and consistent with the plain column; a legacy edit of the plain text drops the (now stale) rich version
create or replace function private.tg_soluciones_markup()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'UPDATE' and new.razonamiento is distinct from old.razonamiento
     and new.razonamiento_rico is not distinct from old.razonamiento_rico and new.razonamiento_markup is not distinct from old.razonamiento_markup then
    new.razonamiento_rico := null;
    new.razonamiento_markup := null;
  end if;
  if new.razonamiento_rico is null then
    new.razonamiento_markup := null;
  else
    if not private.markup_valid(new.razonamiento_rico) then raise exception 'MARKUP_INVALID'; end if;
    if new.razonamiento is distinct from private.markup_plain(new.razonamiento_rico) then raise exception 'MARKUP_PLAIN_MISMATCH'; end if;
    if new.razonamiento_markup is null then raise exception 'MARKUP_SOURCE_REQUIRED'; end if;
  end if;
  return new;
end;
$$;
revoke all on function private.tg_soluciones_markup() from public, anon, authenticated;
drop trigger if exists trg_soluciones_markup on public.soluciones_pregunta;
create trigger trg_soluciones_markup before insert or update on public.soluciones_pregunta
  for each row execute function private.tg_soluciones_markup();

-- ---- staging: same rules (the row carries plain text in razonamiento, the structure in razonamiento_rico and the source in razonamiento_markup)
create or replace function private.tg_staging_markup()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.razonamiento_rico is null then
    new.razonamiento_markup := null;
  else
    if not private.markup_valid(new.razonamiento_rico) then raise exception 'MARKUP_INVALID'; end if;
    if new.razonamiento is distinct from private.markup_plain(new.razonamiento_rico) then raise exception 'MARKUP_PLAIN_MISMATCH'; end if;
    if new.razonamiento_markup is null then raise exception 'MARKUP_SOURCE_REQUIRED'; end if;
  end if;
  return new;
end;
$$;
revoke all on function private.tg_staging_markup() from public, anon, authenticated;
drop trigger if exists trg_staging_markup on public.preguntas_parseadas_staging;
create trigger trg_staging_markup before insert or update of razonamiento, razonamiento_rico, razonamiento_markup on public.preguntas_parseadas_staging
  for each row execute function private.tg_staging_markup();

-- ---- when the import creates the question (staging.pregunta_id set), its rich text goes to the new solution in the same transaction; linked (existing) questions keep theirs
create or replace function private.tg_staging_publica_markup()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.razonamiento_rico is not null and new.vincular_pregunta_id is null then
    update public.soluciones_pregunta s
       set razonamiento_rico = new.razonamiento_rico, razonamiento_markup = new.razonamiento_markup
     where s.pregunta_id = new.pregunta_id and s.razonamiento = new.razonamiento;
  end if;
  return null;
end;
$$;
revoke all on function private.tg_staging_publica_markup() from public, anon, authenticated;
drop trigger if exists trg_staging_publica_markup on public.preguntas_parseadas_staging;
create trigger trg_staging_publica_markup after update of pregunta_id on public.preguntas_parseadas_staging
  for each row when (old.pregunta_id is null and new.pregunta_id is not null) execute function private.tg_staging_publica_markup();

-- ---- the immutable question revision carries the rich text too (only when there is one, so the identity of every existing revision is unchanged)
create or replace function private.app_revision_pregunta_solucion(p_pregunta_id uuid)
returns jsonb
language sql
stable
set search_path = ''
as $$
  select jsonb_build_object('opcion_correcta_id', s.opcion_correcta_id, 'razonamiento', s.razonamiento)
         || case when s.razonamiento_rico is not null then jsonb_build_object('razonamiento_rico', s.razonamiento_rico) else '{}'::jsonb end
  from public.soluciones_pregunta s
  where s.pregunta_id = p_pregunta_id
$$;
revoke all on function private.app_revision_pregunta_solucion(uuid) from public, anon, authenticated;

commit;
