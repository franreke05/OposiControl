-- OPOSICONTROL CANONICAL V1 V2 — SIGNATURE-SAFE FUNCTION GRANTS
-- The UUID conversation overload is retained as a canonical candidate.
-- Only the integer overload and other explicitly legacy signatures are isolated.

begin;

-- LEGACY: integer conversation access. Do not expose through Data API.
revoke all on function public.can_access_conversation(integer)
  from public, anon, authenticated;

-- UUID/CANONICAL_CANDIDATE: preserve callable authenticated/service path.
revoke all on function public.can_access_conversation(uuid)
  from public, anon;
grant execute on function public.can_access_conversation(uuid)
  to authenticated, service_role;

-- LEGACY integer/content authorization signatures.
revoke all on function public.can_access_exam(integer)
  from public, anon, authenticated;
revoke all on function public.can_access_question(integer)
  from public, anon, authenticated;
revoke all on function public.has_role(text)
  from public, anon, authenticated;
revoke all on function public.has_any_role(text[])
  from public, anon, authenticated;
revoke all on function public.current_usuario_id()
  from public, anon, authenticated;
revoke all on function public.current_especialidad_id()
  from public, anon, authenticated;
revoke all on function public.ensure_wallet_for_usuario()
  from public, anon, authenticated;
revoke all on function public.fn_audit_row_change()
  from public, anon, authenticated;
revoke all on function public.rpc_grant_exam_access(integer, integer, character varying, boolean)
  from public, anon, authenticated;
revoke all on function public.rpc_purchase_articulo(integer, integer)
  from public, anon, authenticated;

-- oposibots_upsert_own_profile(...) and /v1/auth/* compatibility are retained.
-- They are not dropped or rewritten in the pre-OposiBot package.

commit;
