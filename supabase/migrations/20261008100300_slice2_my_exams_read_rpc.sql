-- =============================================================================
-- SLICE 2 -- consumer catalog: "my exams" collection read (contract D2-13, vista = COLECCION)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate). Forward-only and additive: ONE new read-only function.
-- No table, column, index, trigger, policy or row is created or changed. Published migrations are NOT edited.
-- Idempotent, one transaction.
-- Requires 20261008100200 (public.intentos_examen.creado_por_comando: the proof that an attempt was created by the
-- canonical start command).
--
-- WHY
--   The frozen contract D2-13 (GET /api/v1/app/me/examenes, openapi/catalog/catalog.yaml) defines vista = COLECCION
--   as the UNION of the exams the user has been granted and the exams the user has attempted, ONE item per exam,
--   paged by cursor, plus `grupos` (count per comunidad over the whole filtered set). PostgREST cannot express a
--   union of two tables with a stable keyset, so Ktor served COLECCION as ADQUIRIDOS + grupos (an attempted exam
--   without a grant never appeared). This function is the missing read.
--
-- WHAT THIS MIGRATION DOES
--   public.app_my_exams_collection(p_usuario_id uuid, p_tipo_examen text, p_comunidad_id uuid, p_anio integer,
--                                  p_despues_en timestamptz, p_despues_id uuid, p_limite integer,
--                                  p_con_grupos boolean) returns jsonb
--     STABLE, SECURITY DEFINER, search_path = '', EXECUTE for service_role ONLY. Reads; never writes.
--     Returns {"items": [...], "grupos": [...] | null}:
--       items[]  {"orden_en": <keyset instant>,
--                 "examenes": {id, titulo, tipo_examen, tipo_acceso, especialidad_id, demo_disponible,
--                              portada_storage_path, comunidades_autonomas: {codigo} | null},
--                 "intento":  {id, estado, total_respondidas, total_preguntas} | null}
--       grupos[] {"comunidad_codigo", "total"} ordered by code, only when p_con_grupos; otherwise null.
--     Exam METADATA only: no question, option, solution or answer is read (SEC-004). `accessState` is NOT computed
--     here: Ktor applies the one entitlement policy (EntitlementPolicy, D7-25) to the returned page, exactly as for
--     the other catalog reads.
--
-- SEMANTICS (the conservative reading of the open questions Q-06 / Q-07 / Q-08; nothing is invented)
--   Membership (Q-07). An exam is in the collection of the user when it is PUBLISHED NOW
--     (activo AND publicado_en IS NOT NULL AND publicado_en <= now(): never a draft, never an archived exam) AND
--       (a) the user holds a grant on it that is valid NOW (contenido_desbloqueado_usuario, any motivo,
--           valido_desde <= now() and (valido_hasta is null or valido_hasta > now())): the SAME predicate as
--           vista = ADQUIRIDOS; or
--       (b) the user has an attempt on it with origen_intento = 'examen' AND creado_por_comando IS NOT NULL, in any
--           estado (en_progreso, finalizado, abandonado). An attempt the canonical start command did not create
--           does not exist for any Slice 3 route (20261008100200, F-01): it does not put an exam in the collection
--           and is never offered as `intento` (its id would be a dead link).
--     An expired grant alone does not keep an exam in the collection. An attempted exam the user can no longer
--     access stays listed; Ktor reports it with accessState LOCKED.
--   One item per exam. `intento` is the most recently updated qualifying attempt (actualizado_en desc, id desc),
--     the same choice vista = ADQUIRIDOS makes.
--   Order (Q-06): orden_en DESC, examen id DESC, where
--     orden_en = greatest(latest valido_desde among the valid grants, latest actualizado_en among the qualifying
--     attempts): the union of the two orders the sibling views already use (ADQUIRIDOS: valido_desde; EN_CURSO:
--     actualizado_en). The exam id is unique in the result, so the order is total and the keyset
--     (orden_en, id) < (p_despues_en, p_despues_id) has neither duplicates nor gaps on ties.
--     orden_en moves when the user acts between two pages (as in EN_CURSO): an exam can then move to the top and
--     not be seen again in the pages that follow. It is never duplicated within one page.
--   Groups (Q-06): counts per comunidades_autonomas.codigo over the WHOLE filtered collection (not the page), exact
--     (no cap, no truncation). Exams without comunidad are not in any group (the wire requires a code).
--   Scores (Q-08): no score is read or returned. `nota` / `calificacion` stay null until DEC-013 defines them.
--   Limit: p_limite is clamped INSIDE the function to 1..51 (the app page maximum of 50 plus the look-ahead row);
--     null = 21. A caller cannot ask for an unbounded page.
--   Cursor: p_despues_en and p_despues_id are both null (first page) or both set; one without the other is
--     rejected (22023 APP_MY_EXAMS_CURSOR_INCOMPLETE), never silently treated as a first page.
--   Clock: now() of the database (the function is the authority on "published" and "valid"; no caller clock).
--
-- INDEXES
--   None is created. Access path, all on existing indexes of MAIN:
--     contenido_desbloqueado_usuario: usuario_id = $1 -> idx_contenido_desbloqueado_usuario_usuario_id (usuario_id);
--     intentos_examen: usuario_id = $1, newest per examen_id -> idx_intentos_examen_usuario_examen
--       (usuario_id, examen_id);
--     examenes / comunidades_autonomas: by primary key.
--   The order key is an aggregate over the rows of ONE user (bounded by the number of published exams), so no index
--   can serve the keyset itself; the function sorts that per-user set. The PREFLIGHT reports (never fails) if one of
--   the two supporting indexes is missing on the target database.
--
-- PREFLIGHT
--   Fail closed (nothing applied), SLICE2_MY_EXAMS_PREFLIGHT: the four tables and every column the function reads
--   must exist, including intentos_examen.creado_por_comando (apply 20261008100200 first).
--   REPORT only (NOTICE SLICE2_MY_EXAMS_PREFLIGHT_REPORT, never an error): how many of the two supporting indexes
--   exist. Expected on MAIN: 2. The same number is the `info` of the VERIFY row my_exams_supporting_indexes_info.
--
-- FORWARD PLAN
--   Apply after 20261008100200 and BEFORE deploying the Ktor build that calls it. That build answers
--   503 UPSTREAM_UNAVAILABLE for vista = COLECCION while the function is missing (PostgREST PGRST202); it does NOT
--   fall back to the partial ADQUIRIDOS-based answer. EN_CURSO and ADQUIRIDOS do not use the function.
--   An older Ktor build never calls it.
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below: drops the function. Nothing else to restore. After it, the Ktor build that calls the
--   function answers 503 for vista = COLECCION (fail-closed) until the function is re-applied.
--
-- LOCKING
--   CREATE FUNCTION / REVOKE / GRANT: catalog only, no table lock. `lock_timeout = 5s` bounds every lock wait.
--
-- DATA LOSS
--   None. No row is read for writing, written or deleted.
--
-- RLS AND GRANTS
--   No table policy or grant changes. The function is SECURITY DEFINER (owner: the migration role) with
--   `search_path = ''` and EXECUTE revoked from PUBLIC, anon and authenticated and granted to service_role only:
--   Ktor is the only caller and passes the `sub` of the verified JWT as p_usuario_id. Every row read is filtered by
--   that user id; there is no parameter that widens the owner filter.
--
-- CONTENT / AUTHORING FLOWS
--   Untouched. public.examenes is only read (metadata columns of published rows).
--
-- ROLLBACK (manual; executed verbatim by the PG17 gate and dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_my_exams_collection(uuid, text, uuid, integer, timestamp with time zone, uuid, integer, boolean);
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- with fn(f) as (
--   select to_regprocedure('public.app_my_exams_collection(uuid,text,uuid,integer,timestamp with time zone,uuid,integer,boolean)')::oid)
-- select 'my_exams_rpc_missing' as check_name,
--        (select count(*) from fn where fn.f is null)::bigint as failures,
--        null::text as info
-- union all
-- select 'my_exams_rpc_wrong_security_or_volatility', count(*)::bigint, null
-- from fn join pg_proc p on p.oid = fn.f
-- where not (p.prosecdef and p.proconfig @> array['search_path=""'] and p.provolatile = 's'
--            and p.prorettype = 'jsonb'::regtype)
-- union all
-- select 'my_exams_rpc_executable_by_clients', count(*)::bigint, null
-- from fn join pg_proc p on p.oid = fn.f
-- where has_function_privilege('anon', p.oid, 'execute') or has_function_privilege('authenticated', p.oid, 'execute')
--    or exists (select 1 from aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
--               where a.grantee = 0 and a.privilege_type = 'EXECUTE')
-- union all
-- select 'my_exams_rpc_not_executable_by_service_role', count(*)::bigint, null
-- from fn join pg_proc p on p.oid = fn.f
-- where not has_function_privilege('service_role', p.oid, 'execute')
-- union all
-- select 'my_exams_rpc_without_published_guard', count(*)::bigint, null
-- from fn join pg_proc p on p.oid = fn.f
-- where not (p.prosrc like '%where e.activo and e.publicado_en is not null and e.publicado_en <= v_ahora%')
-- union all
-- select 'my_exams_rpc_without_owner_or_marker_guard', count(*)::bigint, null
-- from fn join pg_proc p on p.oid = fn.f
-- where not (p.prosrc like '%where cd.usuario_id = p_usuario_id and cd.examen_id is not null%'
--            and p.prosrc like '%where ie.usuario_id = p_usuario_id and ie.examen_id is not null%'
--            and p.prosrc like '%and ie.origen_intento = ''examen'' and ie.creado_por_comando is not null%')
-- union all
-- select 'my_exams_rpc_without_bounded_limit', count(*)::bigint, null
-- from fn join pg_proc p on p.oid = fn.f
-- where not (p.prosrc like '%least(greatest(coalesce(p_limite, 21), 1), 51)%' and p.prosrc like '%limit v_limite%')
-- union all
-- select 'my_exams_rpc_writes_or_reads_question_content', count(*)::bigint, null
-- from fn join pg_proc p on p.oid = fn.f
-- where p.prosrc ~* '\m(insert|update|delete|truncate|merge|execute|perform)\M'
--    or p.prosrc ~* 'public\.(preguntas|opciones_pregunta|soluciones_pregunta|respuestas_intento|preguntas_intento|preguntas_examen)\M'
-- union all
-- select 'my_exams_supporting_indexes_info', 0::bigint, count(*)::text
-- from pg_index i
-- where i.indisvalid and i.indisready
--   and i.indexrelid in (to_regclass('public.idx_contenido_desbloqueado_usuario_usuario_id'),
--                        to_regclass('public.idx_intentos_examen_usuario_examen'));
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (fail closed): every table and column the function reads.
do $$
declare
  v_missing text;
begin
  select string_agg(need.t || '.' || need.c, ', ' order by need.t, need.c) into v_missing
  from (values
    ('examenes', 'id'), ('examenes', 'titulo'), ('examenes', 'tipo_examen'), ('examenes', 'tipo_acceso'),
    ('examenes', 'especialidad_id'), ('examenes', 'comunidad_id'), ('examenes', 'anio'), ('examenes', 'activo'),
    ('examenes', 'publicado_en'), ('examenes', 'demo_disponible'), ('examenes', 'portada_storage_path'),
    ('comunidades_autonomas', 'id'), ('comunidades_autonomas', 'codigo'),
    ('contenido_desbloqueado_usuario', 'usuario_id'), ('contenido_desbloqueado_usuario', 'examen_id'),
    ('contenido_desbloqueado_usuario', 'valido_desde'), ('contenido_desbloqueado_usuario', 'valido_hasta'),
    ('intentos_examen', 'id'), ('intentos_examen', 'usuario_id'), ('intentos_examen', 'examen_id'),
    ('intentos_examen', 'estado'), ('intentos_examen', 'origen_intento'), ('intentos_examen', 'creado_por_comando'),
    ('intentos_examen', 'total_respondidas'), ('intentos_examen', 'total_preguntas'),
    ('intentos_examen', 'actualizado_en')
  ) as need(t, c)
  where not exists (
    select 1 from pg_attribute a
    where a.attrelid = to_regclass('public.' || need.t) and a.attname = need.c and not a.attisdropped
  );
  if v_missing is not null then
    raise exception 'SLICE2_MY_EXAMS_PREFLIGHT'
      using detail = 'Missing on this database: ' || v_missing,
            hint = 'intentos_examen.creado_por_comando comes from 20261008100200: apply it first.';
  end if;
end;
$$;

-- PREFLIGHT (report only, never fails): the two existing indexes the access path relies on.
do $$
declare
  v_indices integer;
begin
  select count(*) into v_indices
  from pg_index i
  where i.indisvalid and i.indisready
    and i.indexrelid in (to_regclass('public.idx_contenido_desbloqueado_usuario_usuario_id'),
                         to_regclass('public.idx_intentos_examen_usuario_examen'));
  raise notice 'SLICE2_MY_EXAMS_PREFLIGHT_REPORT: % of the 2 supporting indexes exist (idx_contenido_desbloqueado_usuario_usuario_id, idx_intentos_examen_usuario_examen). Expected on MAIN: 2. With fewer the function is still correct; it scans the table of the missing one.',
    v_indices;
end;
$$;

-- ---------------------------------------------------------------------------------------------------------------
-- D2-13 vista = COLECCION: one page of the collection of a user (+ the groups per comunidad on request)
-- ---------------------------------------------------------------------------------------------------------------
create or replace function public.app_my_exams_collection(
  p_usuario_id uuid,
  p_tipo_examen text,
  p_comunidad_id uuid,
  p_anio integer,
  p_despues_en timestamp with time zone,
  p_despues_id uuid,
  p_limite integer,
  p_con_grupos boolean
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_ahora constant timestamp with time zone := now();
  v_limite constant integer := least(greatest(coalesce(p_limite, 21), 1), 51);
  v_resultado jsonb;
begin
  if (p_despues_en is null) <> (p_despues_id is null) then
    raise exception 'APP_MY_EXAMS_CURSOR_INCOMPLETE' using errcode = '22023';
  end if;

  with concedidos as (
    select cd.examen_id, max(cd.valido_desde) as en
    from public.contenido_desbloqueado_usuario cd
    where cd.usuario_id = p_usuario_id and cd.examen_id is not null
      and cd.valido_desde <= v_ahora and (cd.valido_hasta is null or cd.valido_hasta > v_ahora)
    group by cd.examen_id
  ),
  intentados as (
    select distinct on (ie.examen_id)
           ie.examen_id, ie.id, ie.estado, ie.total_respondidas, ie.total_preguntas, ie.actualizado_en as en
    from public.intentos_examen ie
    where ie.usuario_id = p_usuario_id and ie.examen_id is not null
      and ie.origen_intento = 'examen' and ie.creado_por_comando is not null
    order by ie.examen_id, ie.actualizado_en desc, ie.id desc
  ),
  coleccion as materialized (
    select e.id, e.titulo, e.tipo_examen, e.tipo_acceso, e.especialidad_id, e.demo_disponible, e.portada_storage_path,
           ca.codigo as comunidad_codigo,
           greatest(c.en, i.en) as orden_en,
           i.id as intento_id, i.estado as intento_estado,
           i.total_respondidas as intento_respondidas, i.total_preguntas as intento_preguntas
    from concedidos c
    full join intentados i on i.examen_id = c.examen_id
    join public.examenes e on e.id = coalesce(c.examen_id, i.examen_id)
    left join public.comunidades_autonomas ca on ca.id = e.comunidad_id
    where e.activo and e.publicado_en is not null and e.publicado_en <= v_ahora
      and (p_tipo_examen is null or e.tipo_examen = p_tipo_examen)
      and (p_comunidad_id is null or e.comunidad_id = p_comunidad_id)
      and (p_anio is null or e.anio = p_anio)
  )
  select jsonb_build_object(
    'items', coalesce((
      select jsonb_agg(
               jsonb_build_object(
                 'orden_en', pagina.orden_en,
                 'examenes', jsonb_build_object(
                   'id', pagina.id,
                   'titulo', pagina.titulo,
                   'tipo_examen', pagina.tipo_examen,
                   'tipo_acceso', pagina.tipo_acceso,
                   'especialidad_id', pagina.especialidad_id,
                   'demo_disponible', pagina.demo_disponible,
                   'portada_storage_path', pagina.portada_storage_path,
                   'comunidades_autonomas', case when pagina.comunidad_codigo is not null
                                                 then jsonb_build_object('codigo', pagina.comunidad_codigo) end),
                 'intento', case when pagina.intento_id is not null
                                 then jsonb_build_object(
                                   'id', pagina.intento_id,
                                   'estado', pagina.intento_estado,
                                   'total_respondidas', pagina.intento_respondidas,
                                   'total_preguntas', pagina.intento_preguntas) end)
               order by pagina.orden_en desc, pagina.id desc)
      from (
        select k.*
        from coleccion k
        where p_despues_en is null or (k.orden_en, k.id) < (p_despues_en, p_despues_id)
        order by k.orden_en desc, k.id desc
        limit v_limite
      ) pagina), '[]'::jsonb),
    'grupos', case when p_con_grupos then coalesce((
      select jsonb_agg(jsonb_build_object('comunidad_codigo', g.comunidad_codigo, 'total', g.total)
                       order by g.comunidad_codigo)
      from (
        select k.comunidad_codigo, count(*) as total
        from coleccion k
        where k.comunidad_codigo is not null
        group by k.comunidad_codigo
      ) g), '[]'::jsonb) end)
  into v_resultado;

  return v_resultado;
end;
$$;

revoke all on function public.app_my_exams_collection(uuid, text, uuid, integer, timestamp with time zone, uuid, integer, boolean) from public, anon, authenticated;
grant execute on function public.app_my_exams_collection(uuid, text, uuid, integer, timestamp with time zone, uuid, integer, boolean) to service_role;

commit;
