-- OPOSICONTROL CANONICAL V1 — API IDENTITY SUPPORT
-- This is a UUID-only helper for future API/RPC contracts. It does not alter
-- /v1/auth/* routes and does not create an integer compatibility path.

begin;

create or replace function public.current_auth_user_id()
returns uuid
language sql
stable
security invoker
set search_path = ''
as $$
  select auth.uid();
$$;

revoke all on function public.current_auth_user_id() from public, anon, authenticated;
grant execute on function public.current_auth_user_id() to authenticated, service_role;

commit;
