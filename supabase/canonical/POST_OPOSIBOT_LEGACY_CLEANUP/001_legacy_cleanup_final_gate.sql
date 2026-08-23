-- POST_OPOSIBOT_LEGACY_CLEANUP — FINAL, DEFERRED AND IRREVERSIBLE

begin;

do $$
begin
  if coalesce(current_setting('oposicontrol.post_oposibot_legacy_cleanup_approved', true), '') <> 'true' then
    raise exception 'POST_OPOSIBOT_LEGACY_CLEANUP_HUMAN_GATE_REQUIRED';
  end if;
  if (select count(*) from public.usuario) <> 0 then
    raise exception 'POST_OPOSIBOT_LEGACY_CLEANUP_PUBLIC_USUARIO_NOT_EMPTY';
  end if;
  if to_regprocedure('public.oposibots_upsert_own_profile(text,text,text,text)') is not null then
    raise exception 'POST_OPOSIBOT_LEGACY_CLEANUP_PROFILE_ADAPTER_STILL_PRESENT';
  end if;
end;
$$;

drop function if exists public.current_usuario_id();
drop function if exists public.current_especialidad_id();
drop function if exists public.has_role(text);
drop function if exists public.has_any_role(text[]);
drop function if exists public.ensure_wallet_for_usuario();
drop function if exists public.fn_audit_row_change();
drop function if exists public.rpc_grant_exam_access(integer, integer, character varying, boolean);
drop function if exists public.rpc_purchase_articulo(integer, integer);
drop function if exists public.oposibots_upsert_own_profile(text, text, text, text);
drop function if exists public.can_access_conversation(integer);
drop function if exists public.can_access_exam(integer);
drop function if exists public.can_access_question(integer);

do $$
declare
  function_row record;
begin
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
      raise exception 'POST_OPOSIBOT_LEGACY_CLEANUP_FUNCTION_DEPENDS_ON_PUBLIC_USUARIO: %.%(%)',
        function_row.schema_name, function_row.function_name, function_row.identity_arguments;
    end if;
  end loop;
end;
$$;

drop table if exists public.usuario;

commit;
