-- OPOSICONTROL CANONICAL V1 — SECURITY DEFINER BOUNDARY
-- Revoke direct Data API execution from legacy helpers. Their replacement/drop
-- belongs to the final cleanup gate, not to the first canonical migration.

begin;

do $$
declare
  function_row record;
begin
  for function_row in
    select n.nspname as schema_name,
           p.proname as function_name,
           pg_get_function_identity_arguments(p.oid) as identity_arguments
    from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public'
      and p.proname in (
        'current_usuario_id',
        'current_especialidad_id',
        'has_role',
        'has_any_role',
        'can_access_conversation',
        'can_access_exam',
        'can_access_question',
        'ensure_wallet_for_usuario',
        'fn_audit_row_change',
        'rpc_grant_exam_access',
        'rpc_purchase_articulo'
      )
  loop
    execute format(
      'revoke all on function %I.%I(%s) from public, anon, authenticated',
      function_row.schema_name,
      function_row.function_name,
      function_row.identity_arguments
    );
  end loop;
end;
$$;

commit;
