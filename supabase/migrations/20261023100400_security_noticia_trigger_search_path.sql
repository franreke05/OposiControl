-- =============================================================================
-- Advisor 0011 -- the two `noticia` trigger functions get a fixed, empty search_path
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (owner GO required). NEW forward-only migration: no earlier file is edited. P3 hardening.
--
-- EVIDENCE (MAIN advisors 2026-10-20): function_search_path_mutable on public.noticia_touch_actualizado_en() and
-- public.noticia_sellar_published_at() (20260923103000 / 20260923120000). Both are plpgsql SECURITY INVOKER trigger functions whose
-- bodies use only NEW and now(); now() lives in pg_catalog, which is always searched first, so an empty search_path keeps the exact
-- behaviour and removes the advisor finding (a caller-controlled search_path can no longer shadow anything they might call later).
--
-- WHAT THIS MIGRATION DOES: ALTER FUNCTION ... SET search_path = '' on both. Body, owner, security, ACL and triggers unchanged.
-- PREFLIGHT (fail closed, P0001 NOTICIA_SEARCH_PATH_PREFLIGHT): both functions exist, are plpgsql, SECURITY INVOKER, return trigger,
--   and their config is either unset (MAIN) or already search_path="" (rerun).
-- LOCKING: catalog only.   DATA LOSS: none.
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- alter function public.noticia_touch_actualizado_en() reset search_path;
-- alter function public.noticia_sellar_published_at() reset search_path;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- select 'noticia_trigger_search_path_mutable' as check_name,
--        count(*) filter (where coalesce(p.proconfig, '{}'::text[]) <> array['search_path=""'])::bigint as failures,
--        string_agg(p.proname, ',') filter (where coalesce(p.proconfig, '{}'::text[]) <> array['search_path=""']) as info
-- from pg_proc p
-- where p.oid in ('public.noticia_touch_actualizado_en()'::regprocedure, 'public.noticia_sellar_published_at()'::regprocedure);
-- VERIFY-END

begin;

do $$
declare
  f regprocedure;
begin
  foreach f in array array[to_regprocedure('public.noticia_touch_actualizado_en()'), to_regprocedure('public.noticia_sellar_published_at()')] loop
    if f is null then
      raise exception 'NOTICIA_SEARCH_PATH_PREFLIGHT' using detail = 'noticia trigger function missing';
    end if;
    if not exists (
      select 1 from pg_proc p join pg_language l on l.oid = p.prolang
       where p.oid = f and l.lanname = 'plpgsql' and not p.prosecdef and p.prorettype = 'trigger'::regtype
         and coalesce(p.proconfig, '{}'::text[]) in ('{}'::text[], array['search_path=""'])
    ) then
      raise exception 'NOTICIA_SEARCH_PATH_PREFLIGHT' using detail = format('%s is not the expected plpgsql SECURITY INVOKER trigger function', f);
    end if;
  end loop;
end;
$$;

alter function public.noticia_touch_actualizado_en() set search_path = '';
alter function public.noticia_sellar_published_at() set search_path = '';

commit;
