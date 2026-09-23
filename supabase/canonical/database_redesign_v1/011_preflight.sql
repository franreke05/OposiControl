-- DATABASE REDESIGN V1 — PREFLIGHT ONLY
-- Read-only assertions for the two technical migrations that follow.
begin;

do $$
declare
  duplicate_index record;
begin
  if to_regclass('public.intentos_examen') is null then
    raise exception 'DATABASE_REDESIGN_PREFLIGHT_MISSING_TABLE: public.intentos_examen';
  end if;

  if to_regclass('public.idx_intentos_examen_usuario') is null
     or to_regclass('public.idx_intentos_usuario') is null then
    raise exception 'DATABASE_REDESIGN_PREFLIGHT_DUPLICATE_INDEX_PAIR_MISSING';
  end if;

  select i1.indisunique as first_unique,
         i1.indisprimary as first_primary,
         i2.indisunique as second_unique,
         i2.indisprimary as second_primary,
         i1.indkey::text as first_keys,
         i2.indkey::text as second_keys,
         pg_get_expr(i1.indpred, i1.indrelid) as first_predicate,
         pg_get_expr(i2.indpred, i2.indrelid) as second_predicate
    into duplicate_index
  from pg_index i1
  join pg_class c1 on c1.oid = i1.indexrelid
  join pg_index i2 on i2.indrelid = i1.indrelid
  join pg_class c2 on c2.oid = i2.indexrelid
  where c1.oid = 'public.idx_intentos_examen_usuario'::regclass
    and c2.oid = 'public.idx_intentos_usuario'::regclass;

  if duplicate_index.first_unique or duplicate_index.first_primary
     or duplicate_index.second_unique or duplicate_index.second_primary
     or duplicate_index.first_keys is distinct from duplicate_index.second_keys
     or duplicate_index.first_predicate is distinct from duplicate_index.second_predicate then
    raise exception 'DATABASE_REDESIGN_PREFLIGHT_INDEXES_NOT_FUNCTIONALLY_DUPLICATE';
  end if;

  if coalesce((select idx_scan from pg_stat_user_indexes
              where schemaname = 'public'
                and relname = 'intentos_examen'
                and indexrelname = 'idx_intentos_examen_usuario'), 0) <> 0 then
    raise exception 'DATABASE_REDESIGN_PREFLIGHT_REDUNDANT_INDEX_HAS_USAGE';
  end if;

  if exists (
    select 1
    from pg_policies
    where schemaname = 'public'
      and roles && array['anon']::name[]
      and cmd in ('INSERT', 'UPDATE', 'DELETE', 'ALL')
  ) then
    raise exception 'DATABASE_REDESIGN_PREFLIGHT_ANON_WRITE_POLICY_PRESENT';
  end if;

  if exists (
    select 1
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public'
      and c.relkind in ('r', 'p')
      and not c.relrowsecurity
  ) then
    raise exception 'DATABASE_REDESIGN_PREFLIGHT_PUBLIC_TABLE_WITHOUT_RLS';
  end if;

  raise notice 'DATABASE_REDESIGN_PREFLIGHT: PASS';
end $$;

rollback;
