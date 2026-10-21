-- V1 P3 (E2): close DIRECT client writes (PostgREST) to the Bot-owned user tables. Forward-only; local stack and PG17 gate only, NOT applied to MAIN.
-- Finding (OposiControl read-only catalog read of MAIN): these tables carry policies `own OR es_admin()` and INSERT/UPDATE/DELETE grants to `authenticated`, so
--   (a) any admin JWT (role authenticated) could write OTHER users' rows straight through PostgREST (worst: tokens_dispositivo -> receive another user's push);
--   (b) every user could write his own rows bypassing Ktor (perfiles_usuario.ranking_publico skipped app_set_ranking_visibility's USERNAME_REQUIRED and the
--       ranking_top cleanup; nombre_mostrar/avatar_url skipped app_actualizar_perfil; sesiones_estudio.opositickets_ganados / cola_repaso / recomendaciones were
--       forgeable, today read by nobody to pay anything).
-- Writers today (verified 2026-10-19): Ktor with the service_role key only (SupabaseRequestAuthPolicy.elevatedHeaders; the user-scoped transport is never used),
-- and SECURITY DEFINER RPCs owned by postgres (app_actualizar_perfil, app_completar_onboarding, app_abandon_exam_attempt, app_set_ranking_visibility,
-- handle_new_auth_user...). The KMP client talks only to Ktor (scripts/verify-no-direct-supabase.sh green). tokens_dispositivo, configuraciones_usuario,
-- preguntas_favoritas_usuario, cola_repaso_usuario, recomendaciones_usuario and uso_recursos_pregunta have NO writer today: a future one goes through Ktor.
-- registros_reto_diario is NOT touched here: 20261022101200 already removed every client privilege and its policy.
-- Change: revoke INSERT, UPDATE, DELETE from anon and authenticated (closes the admin-JWT path too: an admin token is `authenticated`). SELECT and every policy stay
-- as they are (the read policies are Control's: removing `OR es_admin()` from them is a separate change). service_role keeps its privileges. No data is modified.
-- The Slice 1 profile guard triggers stay as a second layer.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate). MAIN's anon has no write grant on these tables: only authenticated gets them back.
-- ROLLBACK-BEGIN
-- begin;
-- grant insert, update, delete on table public.perfiles_usuario, public.tokens_dispositivo, public.sesiones_estudio, public.configuraciones_usuario, public.preguntas_favoritas_usuario, public.cola_repaso_usuario, public.recomendaciones_usuario, public.abandonos_examen, public.uso_recursos_pregunta to authenticated;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'user_table_client_write:' || t.n as check_name,
--        (case when has_table_privilege('anon', t.n, 'insert') or has_table_privilege('anon', t.n, 'update') or has_table_privilege('anon', t.n, 'delete')
--                or has_table_privilege('authenticated', t.n, 'insert') or has_table_privilege('authenticated', t.n, 'update') or has_table_privilege('authenticated', t.n, 'delete')
--                or not has_table_privilege('service_role', t.n, 'insert') or not has_table_privilege('service_role', t.n, 'update') or not has_table_privilege('service_role', t.n, 'delete')
--                or not has_table_privilege('authenticated', t.n, 'select') then 1 else 0 end)::bigint as failures
--   from (values ('public.perfiles_usuario'), ('public.tokens_dispositivo'), ('public.sesiones_estudio'), ('public.configuraciones_usuario'), ('public.preguntas_favoritas_usuario'),
--                ('public.cola_repaso_usuario'), ('public.recomendaciones_usuario'), ('public.abandonos_examen'), ('public.uso_recursos_pregunta')) t(n);
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
declare
  t text;
  missing text[] := array[]::text[];
begin
  foreach t in array array['perfiles_usuario', 'tokens_dispositivo', 'sesiones_estudio', 'configuraciones_usuario', 'preguntas_favoritas_usuario', 'cola_repaso_usuario',
                           'recomendaciones_usuario', 'abandonos_examen', 'uso_recursos_pregunta'] loop
    if to_regclass('public.' || t) is null then
      missing := missing || t;
    end if;
  end loop;
  if cardinality(missing) > 0 then
    raise exception using errcode = 'P0001', message = 'V1_USER_TABLES_REVOKE_PREFLIGHT',
      detail = 'missing tables: ' || array_to_string(missing, ', '), hint = 'load the MAIN user tables (supabase/baseline/main_shape_subset_extra_engagement.sql on a local stack) first';
  end if;
end
$$;

revoke insert, update, delete on table public.perfiles_usuario, public.tokens_dispositivo, public.sesiones_estudio, public.configuraciones_usuario,
  public.preguntas_favoritas_usuario, public.cola_repaso_usuario, public.recomendaciones_usuario, public.abandonos_examen, public.uso_recursos_pregunta
  from anon, authenticated;

commit;
