-- DATABASE REDESIGN V1 — LEAST-PRIVILEGE TABLE GRANTS
-- Policies remain the authorization model. This removes nominal mutation
-- privileges where no client mutation policy exists.
begin;

do $$
declare
  table_row record;
  table_ref text;
begin
  if exists (
    select 1
    from pg_policies
    where schemaname = 'public'
      and roles && array['anon']::name[]
      and cmd in ('INSERT', 'UPDATE', 'DELETE', 'ALL')
  ) then
    raise exception 'DATABASE_REDESIGN_GRANTS_ANON_WRITE_POLICY_UNEXPECTED';
  end if;

  -- Anonymous clients have public read-only policies only. Explicitly remove
  -- nominal mutation privileges from every existing public table.
  for table_row in
    select n.nspname as schema_name, c.relname as table_name
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public'
      and c.relkind in ('r', 'p')
  loop
    execute format(
      'revoke insert, update, delete, truncate, references, trigger on table %I.%I from anon, public',
      table_row.schema_name, table_row.table_name
    );
  end loop;

  -- For authenticated users, only retain mutation grants where an explicit
  -- authenticated INSERT/UPDATE/DELETE/ALL policy exists.
  for table_row in
    select n.nspname as schema_name, c.relname as table_name
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public'
      and c.relkind in ('r', 'p')
      and not exists (
        select 1
        from pg_policies p
        where p.schemaname = 'public'
          and p.tablename = c.relname
          and p.roles && array['authenticated']::name[]
          and p.cmd in ('INSERT', 'UPDATE', 'DELETE', 'ALL')
      )
  loop
    execute format(
      'revoke insert, update, delete, truncate, references, trigger on table %I.%I from authenticated, public',
      table_row.schema_name, table_row.table_name
    );
  end loop;

  -- Postconditions: no anonymous mutation privilege remains, and an
  -- authenticated role has no mutation privilege without a matching policy.
  for table_row in
    select n.nspname as schema_name, c.relname as table_name
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public'
      and c.relkind in ('r', 'p')
  loop
    table_ref := format('%I.%I', table_row.schema_name, table_row.table_name);
    if has_table_privilege('anon', table_ref, 'INSERT')
       or has_table_privilege('anon', table_ref, 'UPDATE')
       or has_table_privilege('anon', table_ref, 'DELETE') then
      raise exception 'DATABASE_REDESIGN_GRANTS_ANON_MUTATION_REMAINS: %', table_ref;
    end if;
  end loop;

  for table_row in
    select n.nspname as schema_name, c.relname as table_name
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public'
      and c.relkind in ('r', 'p')
      and not exists (
        select 1
        from pg_policies p
        where p.schemaname = 'public'
          and p.tablename = c.relname
          and p.roles && array['authenticated']::name[]
          and p.cmd in ('INSERT', 'UPDATE', 'DELETE', 'ALL')
      )
  loop
    table_ref := format('%I.%I', table_row.schema_name, table_row.table_name);
    if has_table_privilege('authenticated', table_ref, 'INSERT')
       or has_table_privilege('authenticated', table_ref, 'UPDATE')
       or has_table_privilege('authenticated', table_ref, 'DELETE') then
      raise exception 'DATABASE_REDESIGN_GRANTS_AUTH_MUTATION_REMAINS: %', table_ref;
    end if;
  end loop;
end $$;

commit;
