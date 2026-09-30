-- =============================================================================
-- SLICE 1 / AUDIT LOOP 2 -- session revocation bookkeeping is monotonic (MASTER2-AUTH-009)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate). Forward-only fix on top of the already
-- validated 20260930100300_slice1_account_deletion_requests.sql, which is NOT edited. Idempotent
-- (create or replace), one transaction.
--
-- WHAT THIS MIGRATION DOES
--   public.app_registrar_revocacion_sesiones(p_usuario_id, p_resultado) used to run
--   `update ... set revocacion_sesiones = p_resultado` unconditionally, so a late or retried 'fallida'
--   (two concurrent DELETE /api/v1/app/me, or a retry whose logout hit an already revoked session)
--   overwrote a real 'hecha'. Now:
--     - 'hecha' is TERMINAL: once recorded, any later call (hecha or fallida) is a no-op that returns
--       {"revocacionSesiones": "hecha"} (the stored value, not the argument);
--     - the request row is read FOR UPDATE, so concurrent recorders are serialised on that row;
--     - the existence check (OC404 NO_DELETION_REQUEST) still matches on usuario_id only, so an ignored
--       downgrade never raises OC404;
--     - argument validation (22023 INVALID_ARGUMENT) and the actualizado_en rule (only touched when the
--       stored value changes) are unchanged.
--   Ktor companion change (same loop): on a RETRY, a logout answered 401/403 means the session is already
--   gone and is recorded as 'hecha' instead of 'fallida'.
--
-- FORWARD PLAN
--   Apply after 20260930100500 on local/branch; MAIN only at the dedicated apply gate. Signature, argument
--   semantics and JSON shape are unchanged (the returned value is the STORED state). Ktor needs no change
--   to keep working (it only checks the HTTP status of this RPC).
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below re-creates the 20260930100300 definition verbatim. No data is touched.
--
-- COMPATIBILITY WITH OLD KTOR
--   Old Ktor never calls this RPC (it is Slice 1 only). Slice 1 Ktor ignores the response body.
--
-- LOCKING
--   Function DDL only (catalog locks). Runtime: one row lock (SELECT ... FOR UPDATE) on the caller's own
--   solicitudes_eliminacion_cuenta row for the duration of the RPC. The `set local lock_timeout = '5s'` below
--   applies to the DDL of THIS migration only (it ends at commit and the function carries no setting), so the
--   runtime row lock waits up to statement_timeout, not 5s (MASTER3-DB-003).
--
-- DATA LOSS
--   None.
--
-- INDEXES
--   None (usuario_id is the primary key of solicitudes_eliminacion_cuenta).
--
-- RLS AND GRANTS
--   No policy change. EXECUTE re-asserted: revoked from public/anon/authenticated, granted to service_role
--   (create or replace keeps every other ACL).
--
-- ROLLBACK (manual; executed verbatim by dbtest; re-creates the previous definition verbatim)
-- ROLLBACK-BEGIN
-- begin;
-- create or replace function public.app_registrar_revocacion_sesiones(p_usuario_id uuid, p_resultado text)
-- returns jsonb
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--     v_n integer;
-- begin
--     if p_usuario_id is null or p_resultado is null or p_resultado not in ('hecha', 'fallida') then
--         raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'resultado';
--     end if;
--     update public.solicitudes_eliminacion_cuenta s
--     set revocacion_sesiones = p_resultado,
--         actualizado_en = case when s.revocacion_sesiones = p_resultado then s.actualizado_en else now() end
--     where s.usuario_id = p_usuario_id;
--     get diagnostics v_n = row_count;
--     if v_n = 0 then
--         raise exception using errcode = 'OC404', message = 'NO_DELETION_REQUEST';
--     end if;
--     return jsonb_build_object('revocacionSesiones', p_resultado);
-- end;
-- $$;
-- revoke all on function public.app_registrar_revocacion_sesiones(uuid, text) from public, anon, authenticated;
-- grant execute on function public.app_registrar_revocacion_sesiones(uuid, text) to service_role;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- select 'revocation_not_monotonic' as check_name,
--        (case when exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
--                            where n.nspname = 'public' and p.proname = 'app_registrar_revocacion_sesiones'
--                              and p.prosrc like '%for update%'
--                              and p.prosrc like '%v_actual = ''hecha''%') then 0 else 1 end)::bigint as failures,
--        null::text as info
-- union all
-- select 'revocation_rpc_executable_by_clients',
--        (case when has_function_privilege('anon', 'public.app_registrar_revocacion_sesiones(uuid, text)', 'execute')
--                or has_function_privilege('authenticated', 'public.app_registrar_revocacion_sesiones(uuid, text)', 'execute')
--              then 1 else 0 end)::bigint, null;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

create or replace function public.app_registrar_revocacion_sesiones(p_usuario_id uuid, p_resultado text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_actual text;
begin
    if p_usuario_id is null or p_resultado is null or p_resultado not in ('hecha', 'fallida') then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'resultado';
    end if;
    -- Existence on usuario_id only (an ignored downgrade must never look like a missing request) and a row
    -- lock that serialises concurrent recorders of the same request.
    select s.revocacion_sesiones into v_actual
    from public.solicitudes_eliminacion_cuenta s
    where s.usuario_id = p_usuario_id
    for update;
    if not found then
        raise exception using errcode = 'OC404', message = 'NO_DELETION_REQUEST';
    end if;
    -- MASTER2-AUTH-009: 'hecha' is terminal; a late/retried 'fallida' never downgrades it.
    if v_actual = 'hecha' then
        return jsonb_build_object('revocacionSesiones', 'hecha');
    end if;
    update public.solicitudes_eliminacion_cuenta s
    set revocacion_sesiones = p_resultado,
        actualizado_en = case when s.revocacion_sesiones = p_resultado then s.actualizado_en else now() end
    where s.usuario_id = p_usuario_id;
    return jsonb_build_object('revocacionSesiones', p_resultado);
end;
$$;

revoke all on function public.app_registrar_revocacion_sesiones(uuid, text) from public, anon, authenticated;
grant execute on function public.app_registrar_revocacion_sesiones(uuid, text) to service_role;

commit;
