-- DATABASE REDESIGN V1 — REMOVE ONE PROVEN REDUNDANT INDEX
-- Keeps idx_intentos_usuario because it is the equivalent index with observed usage.
begin;

do $$
declare
  v_keys_a text;
  v_keys_b text;
  v_predicate_a text;
  v_predicate_b text;
  v_unique_a boolean;
  v_unique_b boolean;
  v_primary_a boolean;
  v_primary_b boolean;
  v_usage bigint;
begin
  if to_regclass('public.idx_intentos_examen_usuario') is null then
    raise notice 'DATABASE_REDESIGN_INDEX: redundant index already absent';
    return;
  end if;

  if to_regclass('public.idx_intentos_usuario') is null then
    raise exception 'DATABASE_REDESIGN_INDEX_CANONICAL_INDEX_MISSING';
  end if;

  select i.indkey::text, pg_get_expr(i.indpred, i.indrelid), i.indisunique, i.indisprimary
    into v_keys_a, v_predicate_a, v_unique_a, v_primary_a
  from pg_index i
  where i.indexrelid = 'public.idx_intentos_examen_usuario'::regclass;

  select i.indkey::text, pg_get_expr(i.indpred, i.indrelid), i.indisunique, i.indisprimary
    into v_keys_b, v_predicate_b, v_unique_b, v_primary_b
  from pg_index i
  where i.indexrelid = 'public.idx_intentos_usuario'::regclass;

  select coalesce(idx_scan, 0)
    into v_usage
  from pg_stat_user_indexes
  where schemaname = 'public'
    and relname = 'intentos_examen'
    and indexrelname = 'idx_intentos_examen_usuario';

  if v_keys_a is distinct from v_keys_b
     or v_predicate_a is distinct from v_predicate_b
     or v_unique_a or v_unique_b or v_primary_a or v_primary_b
     or coalesce(v_usage, 0) <> 0 then
    raise exception 'DATABASE_REDESIGN_INDEX_PRECONDITION_FAILED';
  end if;

  drop index public.idx_intentos_examen_usuario;
end $$;

commit;
