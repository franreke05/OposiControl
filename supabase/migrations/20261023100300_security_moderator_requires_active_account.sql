-- =============================================================================
-- SEC-V1-24 -- a BLOCKED moderator/admin is no longer staff in RLS (es_moderador_o_admin() requires an active account)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (owner GO required; separate dossier from SEC-V1-01 and RB-16). NEW forward-only
-- migration: no earlier file is edited. Independent of 20261023100000 (which applies the same rule to es_admin()).
-- Idempotent, one transaction.
--
-- EVIDENCE (MAIN catalog read 2026-10-20, definitions/policy names/grants only)
--   - es_moderador_o_admin() (SECURITY DEFINER, search_path '') is `exists role superadmin|admin|moderador for auth.uid()`:
--     it ignores perfiles_usuario.estado_cuenta. Ktor already refuses a blocked account; RLS does not.
--   - 15 public policies call it (acciones_moderacion ALL, comentarios_oposired/publicaciones_oposired SELECT+UPDATE,
--     reacciones_oposired ALL, conversaciones_soporte SELECT/INSERT/UPDATE/DELETE, mensajes_soporte, feedback_examen,
--     feedback_pregunta, reportes_contenido), and authenticated holds SELECT/INSERT/UPDATE/DELETE on those 9 tables. Through
--     can_moderate() / can_access_conversation(uuid) it also reaches realtime and storage (chat-attachments, bucket absent).
--   - So a moderator or admin whose account is bloqueada / eliminacion_pendiente keeps, with its JWT + the anon key, moderation
--     and support powers through PostgREST.
--
-- WHAT THIS MIGRATION DOES
--   es_moderador_o_admin(): same signature, volatility, SECURITY DEFINER, search_path '' and ACL (CREATE OR REPLACE keeps it);
--   body additionally requires perfiles_usuario.estado_cuenta = 'activa' (same rule as Ktor and as es_admin() in 1023100000).
--   UNCHANGED: every policy, every grant (the direct-write surface on those tables is RB-17, coordinated with OposiBot),
--   can_moderate(), can_access_conversation(), rows.
--
-- PREFLIGHT (fail closed, P0001 MODERATOR_ACTIVE_PREFLIGHT, nothing changed)
--   perfiles_usuario.estado_cuenta exists; es_moderador_o_admin() exists, is SECURITY DEFINER with search_path '' and its
--   body is the MAIN body or the new body.
--
-- ROLLBACK (manual; executed verbatim by dbtest): restores the MAIN body.
-- ROLLBACK-BEGIN
-- begin;
-- create or replace function public.es_moderador_o_admin() returns boolean
-- language sql stable security definer set search_path to ''
-- as $f$
--   select exists (
--     select 1
--     from public.roles_usuario ru
--     join public.roles_app ra on ra.id = ru.rol_id
--     where ru.usuario_id = auth.uid()
--       and ra.codigo in ('superadmin', 'admin', 'moderador')
--   );
-- $f$;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- select 'moderator_ignores_account_state' as check_name,
--        (case when (select prosrc from pg_proc where oid = 'public.es_moderador_o_admin()'::regprocedure) ~ 'estado_cuenta' then 0 else 1 end)::bigint as failures,
--        null::text as info
-- union all
-- select 'moderator_fn_not_definer_or_path',
--        (case when (select prosecdef and proconfig = array['search_path=""'] from pg_proc where oid = 'public.es_moderador_o_admin()'::regprocedure) then 0 else 1 end)::bigint, null;
-- VERIFY-END
--
-- LOCKING: CREATE OR REPLACE FUNCTION, catalog only.   DATA LOSS: none.   INDEXES: none.

begin;

do $$
declare
  c_old_body constant text := 'select exists ( select 1 from public.roles_usuario ru join public.roles_app ra on ra.id = ru.rol_id where ru.usuario_id = auth.uid() and ra.codigo in (''superadmin'', ''admin'', ''moderador'') );';
  c_new_body constant text := 'select exists ( select 1 from public.roles_usuario ru join public.roles_app ra on ra.id = ru.rol_id join public.perfiles_usuario pu on pu.usuario_id = ru.usuario_id where ru.usuario_id = auth.uid() and ra.codigo in (''superadmin'', ''admin'', ''moderador'') and pu.estado_cuenta = ''activa'' );';
  v_body text;
  v_ok boolean;
begin
  if not exists (select 1 from pg_attribute where attrelid = to_regclass('public.perfiles_usuario') and attname = 'estado_cuenta' and not attisdropped) then
    raise exception 'MODERATOR_ACTIVE_PREFLIGHT' using detail = 'public.perfiles_usuario.estado_cuenta is required';
  end if;
  if to_regprocedure('public.es_moderador_o_admin()') is null then
    raise exception 'MODERATOR_ACTIVE_PREFLIGHT' using detail = 'public.es_moderador_o_admin() is missing';
  end if;
  select btrim(regexp_replace(p.prosrc, '\s+', ' ', 'g')), p.prosecdef and p.proconfig = array['search_path=""']
    into v_body, v_ok
  from pg_proc p where p.oid = 'public.es_moderador_o_admin()'::regprocedure;
  if not v_ok then
    raise exception 'MODERATOR_ACTIVE_PREFLIGHT' using detail = 'es_moderador_o_admin() is not SECURITY DEFINER with search_path ''''';
  end if;
  if v_body not in (c_old_body, c_new_body) then
    raise exception 'MODERATOR_ACTIVE_PREFLIGHT' using detail = 'es_moderador_o_admin() body differs from MAIN: ' || v_body;
  end if;
end;
$$;

create or replace function public.es_moderador_o_admin() returns boolean
language sql stable security definer set search_path to ''
as $f$
  select exists (
    select 1
    from public.roles_usuario ru
    join public.roles_app ra on ra.id = ru.rol_id
    join public.perfiles_usuario pu on pu.usuario_id = ru.usuario_id
    where ru.usuario_id = auth.uid()
      and ra.codigo in ('superadmin', 'admin', 'moderador')
      and pu.estado_cuenta = 'activa'
  );
$f$;

commit;
