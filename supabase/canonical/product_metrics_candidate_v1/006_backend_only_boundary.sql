-- PRODUCT METRICS CANDIDATE V1 — KTOR/SERVICE-ROLE MUTATION BOUNDARY
-- Apply only after Ktor calls 005_record_answer_rpc.sql and event-contract
-- compatibility is verified in a disposable database.
begin;

-- Grants and policies must agree: authenticated/anon receive no mutation path.
revoke all on table public.eventos_app from public, anon, authenticated;
revoke all on table public.intentos_examen from public, anon, authenticated;
revoke all on table public.respuestas_intento from public, anon, authenticated;
revoke all on table public.carteras_usuario from public, anon, authenticated;
revoke all on table public.transacciones_cartera from public, anon, authenticated;
revoke all on table public.compras_usuario from public, anon, authenticated;
revoke all on table public.suscripciones from public, anon, authenticated;

grant select on table public.intentos_examen to authenticated;
grant select on table public.respuestas_intento to authenticated;
grant select on table public.carteras_usuario to authenticated;
grant select on table public.transacciones_cartera to authenticated;
grant select on table public.compras_usuario to authenticated;
grant select on table public.suscripciones to authenticated;

grant all on table public.eventos_app to service_role;
grant all on table public.intentos_examen to service_role;
grant all on table public.respuestas_intento to service_role;
grant all on table public.carteras_usuario to service_role;
grant all on table public.transacciones_cartera to service_role;
grant all on table public.compras_usuario to service_role;
grant all on table public.suscripciones to service_role;

-- Remove stale client-write policies before recreating the intended read model.
drop policy if exists eventos_app_insert_auth on public.eventos_app;
drop policy if exists eventos_app_select_staff on public.eventos_app;
drop policy if exists intentos_examen_todo_propio on public.intentos_examen;
drop policy if exists respuestas_intento_todo_propio on public.respuestas_intento;

-- Do not rely on policy names: remove any remaining client-role write policy
-- from the backend-only tables while preserving SELECT read models.
do $$
declare
  policy_row record;
begin
  for policy_row in
    select p.policyname, p.tablename
    from pg_policies p
    where p.schemaname = 'public'
      and p.tablename in ('eventos_app', 'intentos_examen', 'respuestas_intento', 'carteras_usuario', 'transacciones_cartera', 'compras_usuario', 'suscripciones')
      and p.cmd in ('INSERT', 'UPDATE', 'DELETE', 'ALL')
      and (p.roles @> array['public']::name[] or p.roles @> array['anon']::name[] or p.roles @> array['authenticated']::name[])
  loop
    execute format('drop policy if exists %I on public.%I', policy_row.policyname, policy_row.tablename);
  end loop;
end $$;

drop policy if exists product_metrics_intentos_select_propio on public.intentos_examen;
create policy product_metrics_intentos_select_propio on public.intentos_examen
  for select to authenticated using (usuario_id = (select auth.uid()));

drop policy if exists product_metrics_respuestas_select_propio on public.respuestas_intento;
create policy product_metrics_respuestas_select_propio on public.respuestas_intento
  for select to authenticated using (usuario_id = (select auth.uid()));

commit;
