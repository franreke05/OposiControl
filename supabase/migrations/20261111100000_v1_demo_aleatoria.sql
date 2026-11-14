-- V1 RANDOM FIXED DEMO SET (owner decisions 2026-11-11 and 2026-11-14; workstream F14b). Forward-only; local stack and PG17 gate only, NOT applied to MAIN.
-- Requires 20261108100300 (F14 automatic demo).
--
-- Decision: the AUTOMATIC demo set of an exam is N (config 'demo_preguntas', default 5) questions chosen at random ONCE and then FIXED: the same for every user
-- and every call, and adding questions to the exam never changes it. Amended 2026-11-14 (never applied anywhere): a pure md5 ranking was stable per exam but
-- a NEW question with a better rank could enter the set; now the chosen questions are MATERIALIZED.
-- 1. The explicit set of Control still wins when it is complete (private.demo_set_ok), unchanged. Control's flags (preguntas_examen.es_demo) are NOT used to
--    store the automatic set on purpose: a flagged question cannot be annulled, made a reserve or moved (DEMO_CONFLICT in tg_preguntas_examen_demo_rules,
--    admin_set_question_flags, the import and official_rules_problem), which would break "an annulled member is replaced". The automatic set lives in its own
--    table private.demo_set_fijo (examen_id, pregunta_id), service-only.
-- 2. Choosing (private.demo_set_fijar(examen), volatile): eligible = main (not a reserve), not annulled, question active (as F14). The first time a PUBLISHED exam
--    (publicado_en not null) without a complete explicit set has at least N eligible questions, the first N by md5(examen_id::text || pregunta_id::text)
--    (tie: pregunta_id) are stored. From then on the set never changes, except: a member that stops being eligible (annulled, deactivated, turned into a reserve,
--    unlinked) is DELETED from the set and only that one is replaced by the next eligible question of the md5 rank that is not a member (it does not come back
--    when it is restored: the replacement stays). If fewer than N eligible remain, the members stay and the set is completed when there are N again.
-- 3. When: deferred constraint triggers (fire at COMMIT, so a whole import in one transaction is seen at once) on preguntas_examen (insert, delete, update of
--    anulada/es_reserva/es_demo/examen_id/pregunta_id; es_demo: Control's explicit set going from complete to incomplete hands the exam back to the fixed set), preguntas (update of activa) and examenes (insert, update of publicado_en). Drafts are not materialized until they
--    are published (Control may still be uploading questions). BACKFILL: the migration fixes the set of every exam already published.
-- 4. Reading (private.demo_preguntas_efectivas, stable, the function every demo path already calls: public.demo_listo, private.exam_start_verdict,
--    public.app_start_exam_attempt, private.app_congelar_preguntas_intento(..., true)): the eligible questions ordered members first, then by the md5 rank; the
--    first N in exam order. With a stored set that is exactly the stored set; without one (draft, or a write that bypassed triggers) it is the md5 preview the
--    first fixing will store. Fewer than N eligible = no demo (NULL, fail closed), unchanged. A change of 'demo_preguntas' is honoured at read time (members first).
-- 5. Attempts already started never move (the set is frozen at start).
-- 6. Control read (review 2026-11-14, C5): public.admin_exam_demo_efectiva(examen) -> jsonb {origen EXPLICITA | AUTOMATICA | null (no demo), pregunta_ids
--    (the effective set, exam order), explicita_marcadas (questions flagged es_demo), explicita_incompleta (flagged but not a complete explicit set: the app
--    silently serves the automatic one)}. Service role only; Ktor shows it in the official-questions admin read.
-- No Ktor change, no contract shape change (FA44 text already says "automatically ... N"; FA48 text-only amendment in attempts.yaml).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate): drops the triggers, the fixing function and the table, and restores the F14 body of
-- private.demo_preguntas_efectivas byte for byte (first N by posicion).
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.admin_exam_demo_efectiva(uuid);
-- drop trigger if exists trg_demo_set_fijo_preguntas_examen on public.preguntas_examen;
-- drop trigger if exists trg_demo_set_fijo_preguntas on public.preguntas;
-- drop trigger if exists trg_demo_set_fijo_examenes on public.examenes;
-- drop function if exists private.tg_demo_set_fijo_preguntas_examen();
-- drop function if exists private.tg_demo_set_fijo_preguntas();
-- drop function if exists private.tg_demo_set_fijo_examenes();
-- drop function if exists private.demo_set_fijar(uuid);
-- drop table if exists private.demo_set_fijo;
-- create or replace function private.demo_preguntas_efectivas(p_examen uuid)
-- returns uuid[]
-- language plpgsql
-- stable
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_n integer := private.config_int('demo_preguntas', 5);
--   v uuid[];
-- begin
--   if p_examen is null or v_n is null or v_n < 1 then
--     return null;
--   end if;
--   -- the explicit set of Control wins when it is complete (same predicate as the original demo mode)
--   if private.demo_set_ok(p_examen) then
--     select array_agg(pe.pregunta_id order by pe.posicion, pe.pregunta_id) into v
--       from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id
--      where pe.examen_id = p_examen and pe.es_demo and pe.anulada is not true and p.activa;
--     return v;
--   end if;
--   -- automatic: the first N main questions that can be served
--   select array_agg(x.pregunta_id order by x.posicion, x.pregunta_id) into v
--     from (select pe.pregunta_id, pe.posicion
--             from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id
--            where pe.examen_id = p_examen and not pe.es_reserva and pe.anulada is not true and p.activa
--            order by pe.posicion, pe.pregunta_id
--            limit v_n) x;
--   if coalesce(cardinality(v), 0) < v_n then
--     return null;  -- fewer than N servable questions: no demo (fail closed)
--   end if;
--   return v;
-- end;
-- $$;
-- revoke all on function private.demo_preguntas_efectivas(uuid) from public, anon, authenticated;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'demo_aleatoria_function_not_private_only:' || f.n as check_name,
--        (case when to_regprocedure(f.n) is null
--                or has_function_privilege('anon', f.n, 'execute')
--                or has_function_privilege('authenticated', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('private.demo_preguntas_efectivas(uuid)'), ('private.demo_set_fijar(uuid)')) f(n)
-- union all
-- select 'demo_efectiva_admin_read_not_service_role_only',
--        (case when to_regprocedure('public.admin_exam_demo_efectiva(uuid)') is null
--                or has_function_privilege('anon', 'public.admin_exam_demo_efectiva(uuid)', 'execute')
--                or has_function_privilege('authenticated', 'public.admin_exam_demo_efectiva(uuid)', 'execute')
--                or not has_function_privilege('service_role', 'public.admin_exam_demo_efectiva(uuid)', 'execute') then 1 else 0 end)::bigint
-- union all
-- select 'demo_aleatoria_trigger_ignores_es_demo',
--        (case when exists (select 1 from pg_trigger t join pg_attribute a on a.attrelid = t.tgrelid and a.attnum = any (t.tgattr::int2[])
--                            where t.tgname = 'trg_demo_set_fijo_preguntas_examen' and a.attname = 'es_demo') then 0 else 1 end)::bigint
-- union all
-- select 'demo_aleatoria_not_md5_ranked',
--        (case when pg_get_functiondef(to_regprocedure('private.demo_preguntas_efectivas(uuid)')) like '%md5(p_examen::text || pe.pregunta_id::text)%'
--               and pg_get_functiondef(to_regprocedure('private.demo_preguntas_efectivas(uuid)')) like '%private.demo_set_fijo%' then 0 else 1 end)::bigint
-- union all
-- select 'demo_aleatoria_table_not_service_only',
--        (case when to_regclass('private.demo_set_fijo') is null
--                or has_table_privilege('anon', 'private.demo_set_fijo', 'select')
--                or has_table_privilege('authenticated', 'private.demo_set_fijo', 'select') then 1 else 0 end)::bigint
-- union all
-- select 'demo_aleatoria_trigger_missing:' || t.n,
--        (case when exists (select 1 from pg_trigger where tgname = t.n and not tgisinternal and tgdeferrable and tginitdeferred) then 0 else 1 end)::bigint
--   from (values ('trg_demo_set_fijo_preguntas_examen'), ('trg_demo_set_fijo_preguntas'), ('trg_demo_set_fijo_examenes')) t(n)
-- union all
-- select 'demo_aleatoria_published_exam_without_fixed_set',
--        (select count(*) from public.examenes e
--          where e.publicado_en is not null and not private.demo_set_ok(e.id)
--            and (select count(*) from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id
--                  where pe.examen_id = e.id and not pe.es_reserva and pe.anulada is not true and p.activa) >= private.config_int('demo_preguntas', 5)
--            and (select count(*) from private.demo_set_fijo f join public.preguntas_examen pe on pe.examen_id = f.examen_id and pe.pregunta_id = f.pregunta_id
--                   join public.preguntas p on p.id = pe.pregunta_id
--                  where f.examen_id = e.id and not pe.es_reserva and pe.anulada is not true and p.activa) < private.config_int('demo_preguntas', 5))::bigint
-- union all
-- select 'demo_aleatoria_callers_missing:' || f.n,
--        (case when to_regprocedure(f.n) is not null and pg_get_functiondef(to_regprocedure(f.n)) like '%demo_preguntas_efectivas%' then 0 else 1 end)::bigint
--   from (values ('public.demo_listo(public.examenes)'), ('private.exam_start_verdict(uuid,uuid,text,timestamptz)'),
--                ('private.app_congelar_preguntas_intento(uuid,uuid,boolean)'), ('public.app_start_exam_attempt(uuid,uuid,text)')) f(n);
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('private.demo_preguntas_efectivas(uuid)') is null or to_regprocedure('public.demo_listo(public.examenes)') is null
     or to_regprocedure('private.demo_set_ok(uuid)') is null then
    raise exception using errcode = 'P0001', message = 'V1_DEMO_ALEATORIA_PREFLIGHT', hint = 'apply 20261108100300 first';
  end if;
end
$$;

-- ---- 1. the fixed automatic set (service-only)
create table if not exists private.demo_set_fijo (
  examen_id uuid not null references public.examenes (id) on delete cascade,
  pregunta_id uuid not null references public.preguntas (id) on delete cascade,
  fijada_en timestamptz not null default now(),
  primary key (examen_id, pregunta_id)
);
create index if not exists demo_set_fijo_pregunta_idx on private.demo_set_fijo (pregunta_id);
revoke all on table private.demo_set_fijo from public, anon, authenticated;
alter table private.demo_set_fijo enable row level security;

-- ---- 2. reading: members first, then the md5 rank; N in exam order; NULL = no demo
create or replace function private.demo_preguntas_efectivas(p_examen uuid)
returns uuid[]
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_n integer := private.config_int('demo_preguntas', 5);
  v uuid[];
begin
  if p_examen is null or v_n is null or v_n < 1 then
    return null;
  end if;
  -- the explicit set of Control wins when it is complete (same predicate as the original demo mode)
  if private.demo_set_ok(p_examen) then
    select array_agg(pe.pregunta_id order by pe.posicion, pe.pregunta_id) into v
      from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id
     where pe.examen_id = p_examen and pe.es_demo and pe.anulada is not true and p.activa;
    return v;
  end if;
  -- automatic (F14b): the FIXED members of private.demo_set_fijo that are still eligible, completed (preview / replacement) by the md5 rank
  select array_agg(x.pregunta_id order by x.posicion, x.pregunta_id) into v
    from (select pe.pregunta_id, pe.posicion
            from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id
           where pe.examen_id = p_examen and not pe.es_reserva and pe.anulada is not true and p.activa
           order by (not exists (select 1 from private.demo_set_fijo f where f.examen_id = p_examen and f.pregunta_id = pe.pregunta_id)),
                    md5(p_examen::text || pe.pregunta_id::text), pe.pregunta_id
           limit v_n) x;
  if coalesce(cardinality(v), 0) < v_n then
    return null;  -- fewer than N servable questions: no demo (fail closed)
  end if;
  return v;
end;
$$;
revoke all on function private.demo_preguntas_efectivas(uuid) from public, anon, authenticated;

-- ---- 3. fixing: choose once (published exam, N eligible), then only replace members that stop being eligible
create or replace function private.demo_set_fijar(p_examen uuid)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_n integer := private.config_int('demo_preguntas', 5);
  v_publicado boolean;
  v_miembros integer;
  v_elegibles integer;
begin
  if p_examen is null or v_n is null or v_n < 1 then
    return;
  end if;
  select e.publicado_en is not null into v_publicado from public.examenes e where e.id = p_examen;
  if not found then
    return;  -- exam deleted in the same transaction (the set goes with it, ON DELETE CASCADE)
  end if;
  -- one fixer per exam at a time (two commits touching the same exam serialize here)
  perform pg_advisory_xact_lock(hashtextextended('private.demo_set_fijo:' || p_examen::text, 0));
  -- a member that is no longer eligible leaves the set (it is replaced below, and does not come back when restored)
  delete from private.demo_set_fijo f
   where f.examen_id = p_examen
     and not exists (select 1 from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id
                      where pe.examen_id = f.examen_id and pe.pregunta_id = f.pregunta_id
                        and not pe.es_reserva and pe.anulada is not true and p.activa);
  select count(*) into v_miembros from private.demo_set_fijo f where f.examen_id = p_examen;
  if v_miembros >= v_n then
    return;
  end if;
  if v_miembros = 0 and not v_publicado then
    return;  -- a draft is not fixed yet: Control may still be uploading questions
  end if;
  if private.demo_set_ok(p_examen) then
    return;  -- the complete explicit set of Control wins; nothing to choose
  end if;
  select count(*) into v_elegibles
    from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id
   where pe.examen_id = p_examen and not pe.es_reserva and pe.anulada is not true and p.activa;
  if v_elegibles < v_n then
    return;  -- fewer than N eligible: no demo; the members (if any) wait until there are N again
  end if;
  insert into private.demo_set_fijo (examen_id, pregunta_id)
  select p_examen, x.pregunta_id
    from (select pe.pregunta_id
            from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id
           where pe.examen_id = p_examen and not pe.es_reserva and pe.anulada is not true and p.activa
             and not exists (select 1 from private.demo_set_fijo f where f.examen_id = p_examen and f.pregunta_id = pe.pregunta_id)
           order by md5(p_examen::text || pe.pregunta_id::text), pe.pregunta_id
           limit v_n - v_miembros) x
  on conflict do nothing;
end;
$$;
revoke all on function private.demo_set_fijar(uuid) from public, anon, authenticated;

-- ---- 4. deferred triggers (fire at COMMIT, on the final state of the transaction)
create or replace function private.tg_demo_set_fijo_preguntas_examen()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if tg_op in ('INSERT', 'UPDATE') then
    perform private.demo_set_fijar(new.examen_id);
  end if;
  if tg_op = 'DELETE' or (tg_op = 'UPDATE' and old.examen_id is distinct from new.examen_id) then
    perform private.demo_set_fijar(old.examen_id);
  end if;
  return null;
end;
$$;
revoke all on function private.tg_demo_set_fijo_preguntas_examen() from public, anon, authenticated;
drop trigger if exists trg_demo_set_fijo_preguntas_examen on public.preguntas_examen;
create constraint trigger trg_demo_set_fijo_preguntas_examen
  after insert or delete or update of anulada, es_reserva, es_demo, examen_id, pregunta_id on public.preguntas_examen
  deferrable initially deferred
  for each row execute function private.tg_demo_set_fijo_preguntas_examen();

create or replace function private.tg_demo_set_fijo_preguntas()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_examen uuid;
begin
  if old.activa is distinct from new.activa then
    for v_examen in select distinct pe.examen_id from public.preguntas_examen pe where pe.pregunta_id = new.id loop
      perform private.demo_set_fijar(v_examen);
    end loop;
  end if;
  return null;
end;
$$;
revoke all on function private.tg_demo_set_fijo_preguntas() from public, anon, authenticated;
drop trigger if exists trg_demo_set_fijo_preguntas on public.preguntas;
create constraint trigger trg_demo_set_fijo_preguntas
  after update of activa on public.preguntas
  deferrable initially deferred
  for each row execute function private.tg_demo_set_fijo_preguntas();

create or replace function private.tg_demo_set_fijo_examenes()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.publicado_en is not null then
    perform private.demo_set_fijar(new.id);
  end if;
  return null;
end;
$$;
revoke all on function private.tg_demo_set_fijo_examenes() from public, anon, authenticated;
drop trigger if exists trg_demo_set_fijo_examenes on public.examenes;
create constraint trigger trg_demo_set_fijo_examenes
  after insert or update of publicado_en on public.examenes
  deferrable initially deferred
  for each row execute function private.tg_demo_set_fijo_examenes();

-- ---- 4b. Control read of the effective demo (C5): which set the app serves and whether Control's explicit flags are being ignored
create or replace function public.admin_exam_demo_efectiva(p_examen uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v uuid[] := private.demo_preguntas_efectivas(p_examen);
  v_explicita boolean := private.demo_set_ok(p_examen);
  v_marcadas integer;
begin
  select count(*)::integer into v_marcadas from public.preguntas_examen pe where pe.examen_id = p_examen and pe.es_demo;
  return jsonb_build_object(
    'origen', case when v is null then null when v_explicita then 'EXPLICITA' else 'AUTOMATICA' end,
    'pregunta_ids', coalesce(to_jsonb(v), '[]'::jsonb),
    'explicita_marcadas', v_marcadas,
    'explicita_incompleta', v_marcadas > 0 and not v_explicita);
end;
$$;
revoke all on function public.admin_exam_demo_efectiva(uuid) from public, anon, authenticated;
grant execute on function public.admin_exam_demo_efectiva(uuid) to service_role;

-- ---- 5. backfill: every exam already published gets its fixed set now
select private.demo_set_fijar(e.id) from public.examenes e where e.publicado_en is not null;

commit;
