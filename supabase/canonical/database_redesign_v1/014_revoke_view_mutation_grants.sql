-- DATABASE REDESIGN V1 — PUBLIC DERIVED VIEWS ARE READ-ONLY
begin;

do $$
declare
  view_row record;
  view_ref text;
begin
  for view_row in
    select schemaname as schema_name, viewname as view_name
    from pg_views
    where schemaname = 'public'
  loop
    view_ref := format('%I.%I', view_row.schema_name, view_row.view_name);
    execute format(
      'revoke insert, update, delete, truncate, references, trigger on table %s from anon, authenticated, public',
      view_ref
    );

    if has_table_privilege('anon', view_ref, 'INSERT')
       or has_table_privilege('anon', view_ref, 'UPDATE')
       or has_table_privilege('anon', view_ref, 'DELETE')
       or has_table_privilege('authenticated', view_ref, 'INSERT')
       or has_table_privilege('authenticated', view_ref, 'UPDATE')
       or has_table_privilege('authenticated', view_ref, 'DELETE') then
      raise exception 'DATABASE_REDESIGN_VIEW_MUTATION_REMAINS: %', view_ref;
    end if;
  end loop;
end $$;

commit;
