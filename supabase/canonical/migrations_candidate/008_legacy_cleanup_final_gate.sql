-- OPOSICONTROL CANONICAL V1 — FINAL LEGACY CLEANUP GATE
-- This migration is intentionally blocked unless the runner explicitly sets:
--   SET oposicontrol.legacy_cleanup_approved = 'true';
-- It is the last phase. It must never be folded into the first migration.

begin;

do $$
declare
  function_row record;
begin
  if coalesce(current_setting('oposicontrol.legacy_cleanup_approved', true), '') <> 'true' then
    raise exception 'CANONICAL_LEGACY_CLEANUP_HUMAN_GATE_REQUIRED';
  end if;

  if (select count(*) from public.usuario) <> 0 then
    raise exception 'CANONICAL_LEGACY_CLEANUP_PUBLIC_USUARIO_NOT_EMPTY';
  end if;

  for function_row in
    select n.nspname as schema_name,
           p.proname as function_name,
           pg_get_function_identity_arguments(p.oid) as identity_arguments,
           pg_get_functiondef(p.oid) as definition
    from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public'
  loop
    if function_row.definition ilike '%public.usuario%' then
      raise exception 'CANONICAL_LEGACY_CLEANUP_FUNCTION_DEPENDS_ON_PUBLIC_USUARIO: %.%(%)',
        function_row.schema_name, function_row.function_name, function_row.identity_arguments;
    end if;
  end loop;
end;
$$;

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
      and (
        p.proname in (
          'current_usuario_id',
          'current_especialidad_id',
          'has_role',
          'has_any_role',
          'ensure_wallet_for_usuario',
          'fn_audit_row_change',
          'rpc_grant_exam_access',
          'rpc_purchase_articulo'
        )
        or (p.proname = 'can_access_conversation' and pg_get_function_identity_arguments(p.oid) = 'integer')
        or (p.proname = 'can_access_exam' and pg_get_function_identity_arguments(p.oid) = 'integer')
        or (p.proname = 'can_access_question' and pg_get_function_identity_arguments(p.oid) = 'integer')
      )
  loop
    execute format(
      'drop function if exists %I.%I(%s)',
      function_row.schema_name,
      function_row.function_name,
      function_row.identity_arguments
    );
  end loop;
end;
$$;

drop table if exists public.usuario;

commit;
