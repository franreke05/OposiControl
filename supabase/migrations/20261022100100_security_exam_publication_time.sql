-- E2-P03 (2026-10-14): a SCHEDULED exam (publicado_en in the future) must not be readable before its time. Forward-only;
-- rewrites the USING expression of four existing SELECT policies, touches no data.
--
-- The client SELECT policies of the exam content only required `publicado_en IS NOT NULL`, so a free exam scheduled for
-- tomorrow was readable today by anon/authenticated straight through PostgREST (and through the legacy /v1 routes, which
-- Ktor now also filters with `publicado_en <= now()`). The canonical catalogue (D2-01/D2-02/D2-03) already applied the
-- time bound in Ktor; this aligns the database boundary with it. Each policy keeps its exact predicate plus
-- `publicado_en <= now()` next to `publicado_en IS NOT NULL`. `es_admin()` keeps full read.
--
-- STATUS: PENDING. Applied only to the local stack and the PG17 gate. NOT applied to MAIN (no production in PHASE B):
-- it goes out with the deployment plan.
--
-- LOCKING: ALTER POLICY takes ACCESS EXCLUSIVE on each table for a catalog-only change (no scan). lock_timeout = 5s.
-- DATA LOSS: none.
-- RLS AND GRANTS: unchanged except the four USING expressions.
--
-- ROLLBACK (manual; executed verbatim by the PG17 gate): restores the four previous expressions.
-- ROLLBACK-BEGIN
-- begin;
-- alter policy examenes_select_activos on public.examenes
--   using ((((activo = true) and (publicado_en is not null)) or public.es_admin()));
-- alter policy preguntas_examen_select_gratuitas on public.preguntas_examen
--   using (((exists (select 1 from public.examenes e where ((e.id = preguntas_examen.examen_id) and (e.activo = true)
--     and (e.publicado_en is not null) and (e.tipo_acceso = 'gratuito'::text)))) or public.es_admin()));
-- alter policy preguntas_select_gratuitas on public.preguntas
--   using ((((activa = true) and (exists (select 1 from (public.preguntas_examen pe join public.examenes e on ((e.id = pe.examen_id)))
--     where ((pe.pregunta_id = preguntas.id) and (e.activo = true) and (e.publicado_en is not null) and (e.tipo_acceso = 'gratuito'::text)))))
--     or public.es_admin()));
-- alter policy opciones_pregunta_select_gratuitas on public.opciones_pregunta
--   using (((exists (select 1 from ((public.preguntas p join public.preguntas_examen pe on ((pe.pregunta_id = p.id)))
--     join public.examenes e on ((e.id = pe.examen_id)))
--     where ((p.id = opciones_pregunta.pregunta_id) and (p.activa = true) and (e.activo = true) and (e.publicado_en is not null)
--       and (e.tipo_acceso = 'gratuito'::text)))) or public.es_admin()));
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only): each of the four policies carries the time bound.
-- VERIFY-BEGIN
-- select 'policy_without_publication_time_bound:' || p.policyname as check_name,
--        (case when p.qual like '%publicado_en <= now()%' then 0 else 1 end)::bigint as failures
-- from pg_policies p
-- where p.schemaname = 'public'
--   and (p.tablename, p.policyname) in (('examenes', 'examenes_select_activos'),
--                                       ('preguntas_examen', 'preguntas_examen_select_gratuitas'),
--                                       ('preguntas', 'preguntas_select_gratuitas'),
--                                       ('opciones_pregunta', 'opciones_pregunta_select_gratuitas'))
-- union all
-- select 'four_policies_present', (4 - count(*))::bigint from pg_policies p
-- where p.schemaname = 'public'
--   and p.policyname in ('examenes_select_activos', 'preguntas_examen_select_gratuitas', 'preguntas_select_gratuitas',
--                        'opciones_pregunta_select_gratuitas');
-- VERIFY-END

begin;

set local lock_timeout = '5s';

alter policy examenes_select_activos on public.examenes
  using ((((activo = true) and (publicado_en is not null) and (publicado_en <= now())) or public.es_admin()));

alter policy preguntas_examen_select_gratuitas on public.preguntas_examen
  using (((exists (select 1 from public.examenes e where ((e.id = preguntas_examen.examen_id) and (e.activo = true)
    and (e.publicado_en is not null) and (e.publicado_en <= now()) and (e.tipo_acceso = 'gratuito'::text)))) or public.es_admin()));

alter policy preguntas_select_gratuitas on public.preguntas
  using ((((activa = true) and (exists (select 1 from (public.preguntas_examen pe join public.examenes e on ((e.id = pe.examen_id)))
    where ((pe.pregunta_id = preguntas.id) and (e.activo = true) and (e.publicado_en is not null) and (e.publicado_en <= now())
      and (e.tipo_acceso = 'gratuito'::text))))) or public.es_admin()));

alter policy opciones_pregunta_select_gratuitas on public.opciones_pregunta
  using (((exists (select 1 from ((public.preguntas p join public.preguntas_examen pe on ((pe.pregunta_id = p.id)))
    join public.examenes e on ((e.id = pe.examen_id)))
    where ((p.id = opciones_pregunta.pregunta_id) and (p.activa = true) and (e.activo = true) and (e.publicado_en is not null)
      and (e.publicado_en <= now()) and (e.tipo_acceso = 'gratuito'::text)))) or public.es_admin()));

commit;
