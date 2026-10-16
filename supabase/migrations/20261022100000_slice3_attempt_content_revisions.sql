-- =============================================================================
-- SLICE 3 -- an attempt is bound to an IMMUTABLE, REUSABLE revision of each question (content versioning, P0)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate). Forward-only and additive: ONE new table, ONE new nullable
-- column on preguntas_intento (catalog-only), two immutability triggers, ONE additive BEFORE trigger on
-- respuestas_intento, two new private helpers, and four functions REDEFINED (create or replace: same signature, owner
-- and grants): private.app_congelar_preguntas_intento, public.app_record_attempt_answer, public.app_finish_exam_attempt.
-- public.app_start_exam_attempt is NOT redefined (it already calls the freeze helper in the same transaction).
-- No MAIN function (record_answer, preparar_intento_examen, validar_y_calcular_respuesta_intento) is replaced.
-- No content / authoring table, trigger, policy, grant or function is touched. No existing row is written.
-- Requires 20261008100000, 20261008100100, 20261008100200, 20261010100000. Idempotent, one transaction.
--
-- OWNER DECISION (2026-10-14, P0 versioning)
--   "Al iniciar un intento queda vinculado a una version INMUTABLE (pregunta, opciones, orden, recursos,
--   solucion/correccion, explicacion, review). Edicion admin posterior NO altera el intento ni reviews historicas."
--   Before this migration the frozen set (preguntas_intento) froze WHICH questions, but D2-08 / D2-12 / D2-65 joined the
--   LIVE content (preguntas, opciones_pregunta, soluciones_pregunta, recursos_pregunta, etiquetas_pregunta) and
--   app_record_attempt_answer graded against the LIVE solution: an admin edit between start and review changed what an
--   open attempt showed, how its next answer was graded, and what an old review displayed.
--
-- DESIGN (least invasive option; compared in docs/ledger/2026/10/OPB-20261014-E1_exam-server.md)
--   public.revisiones_pregunta: content-addressed, reusable, immutable snapshot of ONE question:
--     contenido jsonb  {v, codigo, tipo, titulo, opciones[{id,letra,texto,posicion}], recursos[...], etiquetas[...]}
--     solucion  jsonb  {opcion_correcta_id, razonamiento}   (separate column: D2-08 never selects it, SEC-004)
--     hash_contenido = sha256 hex of contenido::text || '|' || solucion::text (jsonb text output is canonical).
--     UNIQUE (pregunta_id, hash_contenido): N attempts over the same unchanged question share ONE row; an edit creates
--     a NEW row the next time an attempt STARTS. Nothing is copied per attempt; authoring is not touched (the revision
--     is taken by the runtime at start, not by the editor at publish).
--   public.preguntas_intento.revision_pregunta_id: the revision each frozen question is bound to (set at start, never
--     changed). NULL only for attempts frozen before this migration: Ktor then reads the live content (legacy path).
--   private.app_congelar_preguntas_intento: same set and order as 20261010100000 (every active question, annulled
--     included; returns the evaluable count), now also upserting the revisions and binding each row to its revision.
--   public.app_record_attempt_answer: option membership and correctness come from the BOUND revision (live tables only
--     for unbound legacy rows). It also returns es_correcta / opcion_correcta_id for ANSWERED (DEC-015 reveal), so
--     Ktor needs ONE round trip per answer instead of two.
--   trg_w_respuestas_intento_correccion_revision (BEFORE INSERT OR UPDATE, fires after the MAIN trigger
--     trg_validar_y_calcular_respuesta_intento by name order): the MAIN trigger overwrites es_correcta with the LIVE
--     solution; for a row whose question is bound to a revision this trigger restores es_correcta from the
--     opcion_correcta_id the command wrote (the revision's). Unbound rows are untouched (MAIN behaviour).
--   public.app_finish_exam_attempt: the per-tag summary uses the revision's primary tag (first of contenido.etiquetas,
--     only if the tag still exists: FK) instead of the live etiquetas_pregunta; unbound rows keep the live lookup.
--   Annulment (preguntas_examen.anulada) stays LIVE until finish: it is a tribunal decision, not a content edit
--   (owner decision 2026-10-10), and is frozen into estado_final at finish as before.
--
-- PREFLIGHT (fail closed, P0001 SLICE3_REVISIONS_PREFLIGHT): the tables/columns read here and the functions redefined
--   exist (public.recursos_pregunta included: it exists on MAIN but is outside main_shape_subset.sql).
--
-- FORWARD PLAN
--   Apply after 20261010100000 and BEFORE the Ktor build of feature/december-exam-server (that build reads
--   revisiones_pregunta; PostgREST answers 400 for an unknown relation, so the reads fail closed without it).
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below drops what this migration added (revisions are lost; attempts fall back to live content).
--   It does NOT restore the redefined function bodies: re-apply 20261008100200 and 20261010100000 verbatim afterwards.
--
-- LOCKING
--   ALTER TABLE preguntas_intento ADD COLUMN (nullable, no default): ACCESS EXCLUSIVE, catalog-only. FK added NOT VALID
--   then validated (every row NULL). CREATE INDEX (plain): SHARE on preguntas_intento for one scan. lock_timeout = 5s.
--
-- DATA LOSS: none.
--
-- RLS AND GRANTS
--   revisiones_pregunta: RLS enabled, NO policy, every privilege revoked from public/anon/authenticated; service_role
--   SELECT only (Ktor reads through PostgREST; writes happen only inside the SECURITY DEFINER start command).
--   New functions: private schema, revoked from clients.
--
-- ROLLBACK (manual; executed verbatim by the PG17 gate and dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- drop trigger if exists trg_w_respuestas_intento_correccion_revision on public.respuestas_intento;
-- drop function if exists private.tg_respuestas_intento_correccion_revision();
-- drop trigger if exists trg_preguntas_intento_revision_inmutable on public.preguntas_intento;
-- drop function if exists private.tg_preguntas_intento_revision_inmutable();
-- drop index if exists public.idx_preguntas_intento_revision_pregunta_id;
-- alter table public.preguntas_intento drop column if exists revision_pregunta_id;
-- drop trigger if exists trg_revisiones_pregunta_inmutable on public.revisiones_pregunta;
-- drop function if exists private.tg_revisiones_pregunta_inmutable();
-- drop table if exists public.revisiones_pregunta;
-- drop function if exists private.app_revision_pregunta_contenido(uuid);
-- drop function if exists private.app_revision_pregunta_solucion(uuid);
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- select 'revisions_table_missing_or_without_rls' as check_name,
--        (case when exists (select 1 from pg_class c where c.oid = to_regclass('public.revisiones_pregunta') and c.relrowsecurity)
--              then 0 else 1 end)::bigint as failures,
--        null::text as info
-- union all
-- select 'revisions_client_privileges', count(*)::bigint, null
-- from (values ('anon'), ('authenticated')) r(rol)
-- where to_regclass('public.revisiones_pregunta') is not null
--   and (has_table_privilege(r.rol, 'public.revisiones_pregunta', 'select')
--     or has_table_privilege(r.rol, 'public.revisiones_pregunta', 'insert')
--     or has_table_privilege(r.rol, 'public.revisiones_pregunta', 'update')
--     or has_table_privilege(r.rol, 'public.revisiones_pregunta', 'delete'))
-- union all
-- select 'revisions_service_role_cannot_read',
--        (case when to_regclass('public.revisiones_pregunta') is not null
--                   and has_table_privilege('service_role', 'public.revisiones_pregunta', 'select') then 0 else 1 end)::bigint, null
-- union all
-- select 'revisions_unique_missing', (1 - count(*))::bigint, null
-- from pg_constraint c
-- where c.conrelid = to_regclass('public.revisiones_pregunta') and c.conname = 'revisiones_pregunta_pregunta_hash_key' and c.contype = 'u'
-- union all
-- select 'frozen_revision_column_missing', (1 - count(*))::bigint, null
-- from pg_attribute a
-- where a.attrelid = to_regclass('public.preguntas_intento') and a.attname = 'revision_pregunta_id'
--   and not a.attisdropped and a.atttypid = 'uuid'::regtype
-- union all
-- select 'frozen_revision_fk_or_index_missing',
--        (2 - (select count(*) from pg_constraint c where c.conrelid = to_regclass('public.preguntas_intento')
--                and c.conname = 'preguntas_intento_revision_pregunta_id_fkey' and c.convalidated)
--           - (select count(*) from pg_index i where i.indexrelid = to_regclass('public.idx_preguntas_intento_revision_pregunta_id')
--                and i.indisvalid))::bigint, null
-- union all
-- select 'revision_triggers_missing', (3 - count(*))::bigint, null
-- from pg_trigger t
-- where not t.tgisinternal and t.tgenabled = 'O'
--   and (t.tgrelid, t.tgname) in ((to_regclass('public.revisiones_pregunta'), 'trg_revisiones_pregunta_inmutable'),
--                                 (to_regclass('public.preguntas_intento'), 'trg_preguntas_intento_revision_inmutable'),
--                                 (to_regclass('public.respuestas_intento'), 'trg_w_respuestas_intento_correccion_revision'))
-- union all
-- select 'revision_functions_client_executable', count(*)::bigint, null
-- from pg_proc p
-- where p.oid in (to_regprocedure('private.app_revision_pregunta_contenido(uuid)'), to_regprocedure('private.app_revision_pregunta_solucion(uuid)'),
--                 to_regprocedure('private.tg_revisiones_pregunta_inmutable()'), to_regprocedure('private.tg_preguntas_intento_revision_inmutable()'),
--                 to_regprocedure('private.tg_respuestas_intento_correccion_revision()'))
--   and (has_function_privilege('anon', p.oid, 'execute') or has_function_privilege('authenticated', p.oid, 'execute'))
-- union all
-- select 'revision_commands_not_redefined', count(*)::bigint, null
-- from (values ('private.app_congelar_preguntas_intento(uuid,uuid)'),
--              ('public.app_record_attempt_answer(uuid,uuid,uuid,uuid,integer,integer,integer)'),
--              ('public.app_finish_exam_attempt(uuid,uuid)')) f(sig)
-- where coalesce((select p.prosrc from pg_proc p where p.oid = to_regprocedure(f.sig)), '') !~ 'ATTEMPT_CONTENT_REVISIONS'
-- union all
-- select 'revision_commands_executable_by_clients', count(*)::bigint, null
-- from (values ('public.app_record_attempt_answer(uuid,uuid,uuid,uuid,integer,integer,integer)'),
--              ('public.app_finish_exam_attempt(uuid,uuid)')) f(sig)
-- join pg_proc p on p.oid = to_regprocedure(f.sig)
-- where has_function_privilege('anon', p.oid, 'execute') or has_function_privilege('authenticated', p.oid, 'execute')
--    or not has_function_privilege('service_role', p.oid, 'execute') or not (p.prosecdef and p.proconfig @> array['search_path=""']);
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
declare
  v_missing text;
begin
  select string_agg(need.t || '.' || need.c, ', ' order by need.t, need.c) into v_missing
  from (values
    ('preguntas', 'codigo_pregunta'), ('preguntas', 'tipo_pregunta'), ('preguntas', 'titulo'),
    ('opciones_pregunta', 'texto_opcion'), ('opciones_pregunta', 'letra'), ('opciones_pregunta', 'posicion'),
    ('soluciones_pregunta', 'opcion_correcta_id'), ('soluciones_pregunta', 'razonamiento'),
    ('recursos_pregunta', 'tipo_recurso'), ('recursos_pregunta', 'ruta_storage'), ('recursos_pregunta', 'bucket_storage'),
    ('recursos_pregunta', 'activo'), ('recursos_pregunta', 'metadatos'), ('recursos_pregunta', 'posicion'),
    ('etiquetas_pregunta', 'etiqueta_id'), ('etiquetas', 'nombre'),
    ('preguntas_intento', 'estado_final'), ('respuestas_intento', 'opcion_correcta_id'),
    ('intentos_examen', 'creado_por_comando')
  ) as need(t, c)
  where not exists (
    select 1 from pg_attribute a
    where a.attrelid = to_regclass('public.' || need.t) and a.attname = need.c and not a.attisdropped
  );
  if v_missing is not null then
    raise exception 'SLICE3_REVISIONS_PREFLIGHT' using detail = 'Missing on this database: ' || v_missing;
  end if;
  select string_agg(need.sig, ', ') into v_missing
  from (values ('private.app_congelar_preguntas_intento(uuid,uuid)'),
               ('public.app_start_exam_attempt(uuid,uuid)'),
               ('public.app_record_attempt_answer(uuid,uuid,uuid,uuid,integer,integer,integer)'),
               ('public.app_finish_exam_attempt(uuid,uuid)')) need(sig)
  where to_regprocedure(need.sig) is null;
  if v_missing is not null then
    raise exception 'SLICE3_REVISIONS_PREFLIGHT' using detail = 'Missing function(s): ' || v_missing;
  end if;
end;
$$;

-- 1. The content of one question as the attempt sees it (no solution) and its solution, as canonical jsonb.
create or replace function private.app_revision_pregunta_contenido(p_pregunta_id uuid)
returns jsonb
language sql
stable
set search_path = ''
as $$
  select jsonb_build_object(
    'v', 1,
    'codigo', p.codigo_pregunta,
    'tipo', p.tipo_pregunta,
    'titulo', p.titulo,
    'opciones', coalesce((
      select jsonb_agg(jsonb_build_object('id', o.id, 'letra', o.letra, 'texto', o.texto_opcion, 'posicion', o.posicion)
                       order by o.posicion, o.id)
      from public.opciones_pregunta o where o.pregunta_id = p.id), '[]'::jsonb),
    -- V1 media only (imagen, audio, video), active, stored in Storage. The object path is internal: Ktor never
    -- serialises it; it signs it on demand for the owner of an attempt bound to this revision.
    'recursos', coalesce((
      select jsonb_agg(jsonb_build_object(
               'id', r.id, 'tipo', r.tipo_recurso, 'titulo', r.titulo, 'posicion', r.posicion,
               'bucket', r.bucket_storage, 'ruta', r.ruta_storage,
               'mime', r.metadatos -> 'mime', 'bytes', r.metadatos -> 'bytes',
               'durationMs', r.metadatos -> 'durationMs', 'envelope', r.metadatos -> 'envelope')
             order by r.posicion, r.id)
      from public.recursos_pregunta r
      where r.pregunta_id = p.id and r.activo and r.tipo_recurso in ('imagen', 'audio', 'video')
        and r.ruta_storage is not null), '[]'::jsonb),
    'etiquetas', coalesce((
      select jsonb_agg(jsonb_build_object('id', e.id, 'nombre', e.nombre) order by ep.creado_en, ep.etiqueta_id)
      from public.etiquetas_pregunta ep join public.etiquetas e on e.id = ep.etiqueta_id
      where ep.pregunta_id = p.id), '[]'::jsonb))
  from public.preguntas p
  where p.id = p_pregunta_id
$$;

create or replace function private.app_revision_pregunta_solucion(p_pregunta_id uuid)
returns jsonb
language sql
stable
set search_path = ''
as $$
  select jsonb_build_object('opcion_correcta_id', s.opcion_correcta_id, 'razonamiento', s.razonamiento)
  from public.soluciones_pregunta s
  where s.pregunta_id = p_pregunta_id
$$;

revoke all on function private.app_revision_pregunta_contenido(uuid) from public, anon, authenticated;
revoke all on function private.app_revision_pregunta_solucion(uuid) from public, anon, authenticated;

-- 2. The reusable, immutable revision.
create table if not exists public.revisiones_pregunta (
  id uuid not null default gen_random_uuid(),
  pregunta_id uuid not null,
  hash_contenido text not null,
  contenido jsonb not null,
  solucion jsonb,
  creada_en timestamp with time zone not null default now(),
  constraint revisiones_pregunta_pkey primary key (id),
  constraint revisiones_pregunta_pregunta_hash_key unique (pregunta_id, hash_contenido),
  constraint revisiones_pregunta_hash_check check (hash_contenido ~ '^[0-9a-f]{64}$'),
  -- A question deleted by authoring takes its unused revisions with it; a revision still bound to an attempt blocks
  -- nothing new: preguntas_intento.pregunta_id (ON DELETE RESTRICT) already forbids deleting that question.
  constraint revisiones_pregunta_pregunta_id_fkey foreign key (pregunta_id) references public.preguntas(id) on delete cascade
);

comment on table public.revisiones_pregunta is
  'Immutable, content-addressed snapshot of a question taken when an attempt starts (ATTEMPT_CONTENT_REVISIONS). Reused by every attempt that starts while the content is unchanged. Server only.';

alter table public.revisiones_pregunta enable row level security;
revoke all on table public.revisiones_pregunta from public, anon, authenticated;
revoke all on table public.revisiones_pregunta from service_role;
grant select on table public.revisiones_pregunta to service_role;

create or replace function private.tg_revisiones_pregunta_inmutable()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  raise exception 'REVISION_PREGUNTA_INMUTABLE' using detail = 'public.revisiones_pregunta rows are never updated.';
end;
$$;
revoke all on function private.tg_revisiones_pregunta_inmutable() from public, anon, authenticated;

drop trigger if exists trg_revisiones_pregunta_inmutable on public.revisiones_pregunta;
create trigger trg_revisiones_pregunta_inmutable
  before update on public.revisiones_pregunta
  for each row execute function private.tg_revisiones_pregunta_inmutable();

-- 3. preguntas_intento -> revision (set at start, never changed).
alter table public.preguntas_intento add column if not exists revision_pregunta_id uuid;

alter table public.preguntas_intento drop constraint if exists preguntas_intento_revision_pregunta_id_fkey;
alter table public.preguntas_intento
  add constraint preguntas_intento_revision_pregunta_id_fkey
  foreign key (revision_pregunta_id) references public.revisiones_pregunta(id) not valid;
alter table public.preguntas_intento validate constraint preguntas_intento_revision_pregunta_id_fkey;

create index if not exists idx_preguntas_intento_revision_pregunta_id
  on public.preguntas_intento (revision_pregunta_id)
  where revision_pregunta_id is not null;

create or replace function private.tg_preguntas_intento_revision_inmutable()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if old.revision_pregunta_id is not null and new.revision_pregunta_id is distinct from old.revision_pregunta_id then
    raise exception 'PREGUNTA_INTENTO_REVISION_INMUTABLE'
      using detail = 'preguntas_intento.revision_pregunta_id is fixed when the attempt starts.';
  end if;
  return new;
end;
$$;
revoke all on function private.tg_preguntas_intento_revision_inmutable() from public, anon, authenticated;

drop trigger if exists trg_preguntas_intento_revision_inmutable on public.preguntas_intento;
create trigger trg_preguntas_intento_revision_inmutable
  before update of revision_pregunta_id on public.preguntas_intento
  for each row execute function private.tg_preguntas_intento_revision_inmutable();

-- 4. respuestas_intento: correctness of a revision-bound answer comes from the revision, not from the live solution.
create or replace function private.tg_respuestas_intento_correccion_revision()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  -- Runs AFTER trg_validar_y_calcular_respuesta_intento (BEFORE triggers fire in name order), which sets
  -- es_correcta from the LIVE solution. For a question bound to a revision the command wrote opcion_correcta_id from
  -- that revision: correctness is (selected = that option). Unbound rows keep MAIN's behaviour untouched.
  if new.opcion_seleccionada_id is not null and new.opcion_correcta_id is not null
     and exists (select 1 from public.preguntas_intento pi
                 where pi.intento_id = new.intento_id and pi.pregunta_id = new.pregunta_id
                   and pi.revision_pregunta_id is not null) then
    new.es_correcta := (new.opcion_seleccionada_id = new.opcion_correcta_id);
  end if;
  return new;
end;
$$;
revoke all on function private.tg_respuestas_intento_correccion_revision() from public, anon, authenticated;

drop trigger if exists trg_w_respuestas_intento_correccion_revision on public.respuestas_intento;
create trigger trg_w_respuestas_intento_correccion_revision
  before insert or update on public.respuestas_intento
  for each row execute function private.tg_respuestas_intento_correccion_revision();

-- 5. Freeze helper (redefined): same set / order / return value as 20261010100000, plus the revision binding.
create or replace function private.app_congelar_preguntas_intento(p_intento_id uuid, p_examen_id uuid)
returns integer
language plpgsql
set search_path = ''
as $$
declare
  v_n integer;
  v_preguntas uuid[];
  v_hashes text[];
  v_contenidos jsonb[];
  v_soluciones jsonb[];
  v_bound integer;
begin
  -- ATTEMPT_FREEZE_KEEPS_ANNULLED: every active question is frozen; the result is the number of EVALUABLE ones.
  -- ATTEMPT_CONTENT_REVISIONS: each frozen question is bound to the immutable revision of its content right now.
  if not exists (select 1 from public.preguntas_intento pi where pi.intento_id = p_intento_id) then
    select array_agg(c.pregunta_id order by c.posicion, c.pregunta_id),
           array_agg(encode(sha256(convert_to(c.contenido::text || '|' || coalesce(c.solucion::text, 'null'), 'UTF8')), 'hex')
                     order by c.posicion, c.pregunta_id),
           array_agg(c.contenido order by c.posicion, c.pregunta_id),
           array_agg(c.solucion order by c.posicion, c.pregunta_id)
      into v_preguntas, v_hashes, v_contenidos, v_soluciones
    from (
      select pe.pregunta_id, pe.posicion,
             private.app_revision_pregunta_contenido(pe.pregunta_id) as contenido,
             private.app_revision_pregunta_solucion(pe.pregunta_id) as solucion
      from public.preguntas_examen pe
      join public.preguntas p on p.id = pe.pregunta_id
      where pe.examen_id = p_examen_id and p.activa
    ) c;

    if v_preguntas is not null then
      -- Statement 1: reuse or create. A concurrent start inserting the same revision makes this wait and do nothing.
      insert into public.revisiones_pregunta (pregunta_id, hash_contenido, contenido, solucion)
      select u.pregunta_id, u.hash, u.contenido, u.solucion
      from unnest(v_preguntas, v_hashes, v_contenidos, v_soluciones) as u(pregunta_id, hash, contenido, solucion)
      on conflict (pregunta_id, hash_contenido) do nothing;

      -- Statement 2 (new snapshot: sees the revision a concurrent start committed).
      insert into public.preguntas_intento (intento_id, pregunta_id, posicion, revision_pregunta_id)
      select p_intento_id, u.pregunta_id, u.ord::integer, r.id
      from unnest(v_preguntas, v_hashes) with ordinality as u(pregunta_id, hash, ord)
      join public.revisiones_pregunta r on r.pregunta_id = u.pregunta_id and r.hash_contenido = u.hash;
      get diagnostics v_bound = row_count;
      if v_bound <> cardinality(v_preguntas) then
        raise exception 'APP_START_ATTEMPT_RETRY' using errcode = '40001', detail = 'revision binding incomplete';
      end if;
    end if;
  end if;
  select count(*) into v_n
  from public.preguntas_intento pi
  where pi.intento_id = p_intento_id
    and not exists (select 1 from public.preguntas_examen pe
                    where pe.examen_id = p_examen_id and pe.pregunta_id = pi.pregunta_id and pe.anulada);
  return v_n;
end;
$$;

revoke all on function private.app_congelar_preguntas_intento(uuid, uuid) from public, anon, authenticated;

-- 6. D2-09 (redefined): option membership + correctness from the bound revision; returns the DEC-015 reveal.
create or replace function public.app_record_attempt_answer(
  p_usuario_id uuid,
  p_intento_id uuid,
  p_pregunta_id uuid,
  p_opcion_id uuid,
  p_tiempo_respuesta_ms integer,
  p_base_revision integer,
  p_nivel_confianza integer
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_estado text;
  v_examen_id uuid;
  v_ranking boolean;
  v_iniciado timestamp with time zone;
  v_posicion integer;
  v_revision_id uuid;
  v_contenido jsonb;
  v_solucion jsonb;
  v_correcta_id uuid;
  v_row public.respuestas_intento%rowtype;
  v_exists boolean;
  v_first boolean;
  v_last_event timestamp with time zone;
  v_gap_ms bigint;
  v_ms integer;
  v_clamped boolean;
  v_meta jsonb;
  v_revision integer;
  v_respondidas integer;
  v_constraint text;
  v_es_correcta boolean;
  v_opcion_correcta uuid;
begin
  -- ATTEMPT_CONTENT_REVISIONS
  if p_usuario_id is null or p_intento_id is null or p_pregunta_id is null then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;

  perform pg_advisory_xact_lock(hashtextextended(p_usuario_id::text || ':' || p_pregunta_id::text, 0));

  select ie.estado, ie.examen_id, ie.cuenta_para_ranking, ie.iniciado_en
    into v_estado, v_examen_id, v_ranking, v_iniciado
  from public.intentos_examen ie
  where ie.id = p_intento_id and ie.usuario_id = p_usuario_id
    and ie.origen_intento = 'examen' and ie.creado_por_comando is not null
  for update;
  if not found then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;
  if v_estado <> 'en_progreso' then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_IN_PROGRESS', 'estado', v_estado);
  end if;

  select pi.posicion, pi.revision_pregunta_id, r.contenido, r.solucion
    into v_posicion, v_revision_id, v_contenido, v_solucion
  from public.preguntas_intento pi
  left join public.revisiones_pregunta r on r.id = pi.revision_pregunta_id
  where pi.intento_id = p_intento_id and pi.pregunta_id = p_pregunta_id;
  if not found then
    return jsonb_build_object('outcome', 'QUESTION_NOT_IN_ATTEMPT');
  end if;

  if p_opcion_id is not null and not (
       case when v_revision_id is not null
            then exists (select 1 from jsonb_array_elements(v_contenido -> 'opciones') o where o ->> 'id' = p_opcion_id::text)
            else exists (select 1 from public.opciones_pregunta op where op.id = p_opcion_id and op.pregunta_id = p_pregunta_id)
       end) then
    return jsonb_build_object('outcome', 'OPTION_NOT_IN_QUESTION');
  end if;

  select ri.* into v_row
  from public.respuestas_intento ri
  where ri.intento_id = p_intento_id and ri.pregunta_id = p_pregunta_id
  limit 1;
  v_exists := found;
  v_revision := case when v_exists then v_row.revision else 0 end;

  if exists (select 1 from public.preguntas_examen pe
             where pe.examen_id = v_examen_id and pe.pregunta_id = p_pregunta_id and pe.anulada) then
    select count(*) into v_respondidas
    from public.respuestas_intento ri
    where ri.intento_id = p_intento_id and ri.opcion_seleccionada_id is not null
      and not exists (select 1 from public.preguntas_examen pe
                      where pe.examen_id = v_examen_id and pe.pregunta_id = ri.pregunta_id and pe.anulada);
    return jsonb_build_object('outcome', 'OK', 'estado', 'ANNULLED', 'revision', v_revision,
                              'es_primera_respuesta', false, 'respondidas', v_respondidas, 'noop', true);
  end if;

  if v_exists and v_row.opcion_seleccionada_id is not distinct from p_opcion_id then
    select count(*) into v_respondidas
    from public.respuestas_intento ri
    where ri.intento_id = p_intento_id and ri.opcion_seleccionada_id is not null
      and not exists (select 1 from public.preguntas_examen pe
                      where pe.examen_id = v_examen_id and pe.pregunta_id = ri.pregunta_id and pe.anulada);
    return jsonb_build_object(
      'outcome', 'OK',
      'estado', case when v_row.opcion_seleccionada_id is null then 'SKIPPED' else 'ANSWERED' end,
      'revision', v_revision,
      'es_primera_respuesta', v_row.es_primer_intento_pregunta,
      'respondidas', v_respondidas,
      'es_correcta', case when v_row.opcion_seleccionada_id is not null then v_row.es_correcta end,
      'opcion_correcta_id', case when v_row.opcion_seleccionada_id is not null then v_row.opcion_correcta_id end,
      'noop', true);
  end if;

  if v_exists and v_row.opcion_seleccionada_id is not null then
    return jsonb_build_object('outcome', 'ANSWER_LOCKED', 'current_revision', v_revision);
  end if;

  if coalesce(p_base_revision, -1) <> v_revision then
    return jsonb_build_object('outcome', 'REVISION_CONFLICT', 'current_revision', v_revision);
  end if;

  if p_opcion_id is not null then
    if v_revision_id is not null then
      v_correcta_id := (v_solucion ->> 'opcion_correcta_id')::uuid;
    else
      select sp.opcion_correcta_id into v_correcta_id
      from public.soluciones_pregunta sp where sp.pregunta_id = p_pregunta_id limit 1;
    end if;
    if v_correcta_id is null then
      raise exception 'APP_RECORD_ANSWER_MISSING_SOLUTION';
    end if;
  end if;

  select greatest(v_iniciado, coalesce(max(greatest(ri.respondida_en, ri.actualizada_en, ri.creada_en)), v_iniciado))
    into v_last_event
  from public.respuestas_intento ri where ri.intento_id = p_intento_id;
  v_gap_ms := greatest(0, floor(extract(epoch from (clock_timestamp() - v_last_event)) * 1000))::bigint;
  v_ms := least(greatest(coalesce(p_tiempo_respuesta_ms, 0), 0)::bigint, v_gap_ms, 2147483647)::integer;
  v_clamped := coalesce(p_tiempo_respuesta_ms, 0) <> v_ms;
  v_meta := jsonb_strip_nulls(jsonb_build_object(
    'nivelConfianza', p_nivel_confianza,
    'tiempoRecortado', case when v_clamped then true end));

  v_first := p_opcion_id is not null and not exists (
    select 1 from public.respuestas_intento prev
    where prev.usuario_id = p_usuario_id and prev.pregunta_id = p_pregunta_id
      and prev.opcion_seleccionada_id is not null
      and (not v_exists or prev.id <> v_row.id));

  if not v_exists then
    begin
      insert into public.respuestas_intento as r (
        intento_id, pregunta_id, opcion_seleccionada_id, es_correcta, usuario_id, examen_id, opcion_correcta_id,
        posicion_pregunta, respondida_en, cuenta_para_ranking, es_primer_intento_pregunta,
        primera_opcion_id, primera_es_correcta, revision, actualizada_en, tiempo_respuesta_ms,
        tiempo_respuesta_segundos, metadatos
      ) values (
        p_intento_id, p_pregunta_id, p_opcion_id, p_opcion_id = v_correcta_id, p_usuario_id, v_examen_id, v_correcta_id,
        v_posicion, now(), coalesce(v_ranking, false) and v_first, v_first,
        p_opcion_id, p_opcion_id = v_correcta_id, 1, now(), v_ms,
        (v_ms / 1000), v_meta
      )
      returning r.revision, r.es_primer_intento_pregunta, r.es_correcta, r.opcion_correcta_id
        into v_revision, v_first, v_es_correcta, v_opcion_correcta;
    exception when unique_violation then
      get stacked diagnostics v_constraint = constraint_name;
      if v_constraint is distinct from 'ux_respuestas_first_response' or v_first is not true then
        raise;
      end if;
      insert into public.respuestas_intento as r (
        intento_id, pregunta_id, opcion_seleccionada_id, es_correcta, usuario_id, examen_id, opcion_correcta_id,
        posicion_pregunta, respondida_en, cuenta_para_ranking, es_primer_intento_pregunta,
        primera_opcion_id, primera_es_correcta, revision, actualizada_en, tiempo_respuesta_ms,
        tiempo_respuesta_segundos, metadatos
      ) values (
        p_intento_id, p_pregunta_id, p_opcion_id, p_opcion_id = v_correcta_id, p_usuario_id, v_examen_id, v_correcta_id,
        v_posicion, now(), false, false,
        p_opcion_id, p_opcion_id = v_correcta_id, 1, now(), v_ms,
        (v_ms / 1000), v_meta
      )
      returning r.revision, r.es_primer_intento_pregunta, r.es_correcta, r.opcion_correcta_id
        into v_revision, v_first, v_es_correcta, v_opcion_correcta;
    end;
  else
    begin
      update public.respuestas_intento r
         set opcion_seleccionada_id = p_opcion_id,
             es_correcta = (p_opcion_id = v_correcta_id),
             opcion_correcta_id = v_correcta_id,
             revision = r.revision + 1,
             actualizada_en = now(),
             cambio_respuesta = true,
             es_primer_intento_pregunta = v_first,
             cuenta_para_ranking = coalesce(v_ranking, false) and v_first,
             tiempo_respuesta_ms = least(coalesce(r.tiempo_respuesta_ms, 0)::bigint + v_ms, 2147483647)::integer,
             tiempo_respuesta_segundos = (least(coalesce(r.tiempo_respuesta_ms, 0)::bigint + v_ms, 2147483647) / 1000)::integer,
             metadatos = r.metadatos || v_meta
       where r.id = v_row.id
      returning r.revision, r.es_primer_intento_pregunta, r.es_correcta, r.opcion_correcta_id
        into v_revision, v_first, v_es_correcta, v_opcion_correcta;
    exception when unique_violation then
      get stacked diagnostics v_constraint = constraint_name;
      if v_constraint is distinct from 'ux_respuestas_first_response' or v_first is not true then
        raise;
      end if;
      update public.respuestas_intento r
         set opcion_seleccionada_id = p_opcion_id,
             es_correcta = (p_opcion_id = v_correcta_id),
             opcion_correcta_id = v_correcta_id,
             revision = r.revision + 1,
             actualizada_en = now(),
             cambio_respuesta = true,
             es_primer_intento_pregunta = false,
             cuenta_para_ranking = false,
             tiempo_respuesta_ms = least(coalesce(r.tiempo_respuesta_ms, 0)::bigint + v_ms, 2147483647)::integer,
             tiempo_respuesta_segundos = (least(coalesce(r.tiempo_respuesta_ms, 0)::bigint + v_ms, 2147483647) / 1000)::integer,
             metadatos = r.metadatos || v_meta
       where r.id = v_row.id
      returning r.revision, r.es_primer_intento_pregunta, r.es_correcta, r.opcion_correcta_id
        into v_revision, v_first, v_es_correcta, v_opcion_correcta;
    end;
  end if;

  select count(*) into v_respondidas
  from public.respuestas_intento ri
  where ri.intento_id = p_intento_id and ri.opcion_seleccionada_id is not null
    and not exists (select 1 from public.preguntas_examen pe
                    where pe.examen_id = v_examen_id and pe.pregunta_id = ri.pregunta_id and pe.anulada);

  update public.intentos_examen
     set total_respondidas = v_respondidas,
         ultima_pregunta_posicion = v_posicion
   where id = p_intento_id;

  return jsonb_build_object(
    'outcome', 'OK',
    'estado', case when p_opcion_id is null then 'SKIPPED' else 'ANSWERED' end,
    'revision', v_revision,
    'es_primera_respuesta', coalesce(v_first, false),
    'respondidas', v_respondidas,
    'es_correcta', case when p_opcion_id is not null then v_es_correcta end,
    'opcion_correcta_id', case when p_opcion_id is not null then v_opcion_correcta end,
    'noop', false);
end;
$$;

revoke all on function public.app_record_attempt_answer(uuid, uuid, uuid, uuid, integer, integer, integer) from public, anon, authenticated;
grant execute on function public.app_record_attempt_answer(uuid, uuid, uuid, uuid, integer, integer, integer) to service_role;

-- 7. D2-10 (redefined): the per-tag summary uses the revision's primary tag.
create or replace function public.app_finish_exam_attempt(p_usuario_id uuid, p_intento_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_estado text;
  v_examen_id uuid;
  v_iniciado timestamp with time zone;
  v_correctas integer;
  v_falladas integer;
  v_sin_responder integer;
begin
  -- ATTEMPT_CONTENT_REVISIONS
  if p_usuario_id is null or p_intento_id is null then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;

  select ie.estado, ie.examen_id, ie.iniciado_en into v_estado, v_examen_id, v_iniciado
  from public.intentos_examen ie
  where ie.id = p_intento_id and ie.usuario_id = p_usuario_id
    and ie.origen_intento = 'examen' and ie.creado_por_comando is not null
  for update;
  if not found then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;
  if v_estado = 'finalizado' then
    return jsonb_build_object('outcome', 'OK', 'already', true);
  end if;
  if v_estado <> 'en_progreso' then
    return jsonb_build_object('outcome', 'ATTEMPT_ABANDONED');
  end if;

  update public.preguntas_intento pi
     set estado_final = s.estado_final
  from (
    select q.id,
           case
             when exists (select 1 from public.preguntas_examen pe
                          where pe.examen_id = v_examen_id and pe.pregunta_id = q.pregunta_id and pe.anulada) then 'ANNULLED'
             when ri.id is null then 'UNANSWERED'
             when ri.opcion_seleccionada_id is null then 'SKIPPED'
             when ri.es_correcta is true then 'CORRECT'
             else 'WRONG'
           end as estado_final
    from public.preguntas_intento q
    left join public.respuestas_intento ri on ri.intento_id = q.intento_id and ri.pregunta_id = q.pregunta_id
    where q.intento_id = p_intento_id
  ) s
  where pi.id = s.id;

  select count(*) filter (where pi.estado_final = 'CORRECT'),
         count(*) filter (where pi.estado_final = 'WRONG'),
         count(*) filter (where pi.estado_final in ('SKIPPED', 'UNANSWERED'))
    into v_correctas, v_falladas, v_sin_responder
  from public.preguntas_intento pi
  where pi.intento_id = p_intento_id;

  insert into public.resumen_intento_etiqueta
    (intento_id, etiqueta_id, total_preguntas, correctas, falladas, sin_responder,
     tiempo_total_segundos, tiempo_medio_segundos, porcentaje_acierto)
  select p_intento_id,
         t.etiqueta_id,
         count(*)::integer,
         (count(*) filter (where pi.estado_final = 'CORRECT'))::integer,
         (count(*) filter (where pi.estado_final = 'WRONG'))::integer,
         (count(*) filter (where pi.estado_final in ('SKIPPED', 'UNANSWERED')))::integer,
         (coalesce(sum(ri.tiempo_respuesta_ms), 0) / 1000)::integer,
         round(coalesce(sum(ri.tiempo_respuesta_ms), 0) / 1000.0 / count(*), 2),
         case when count(*) filter (where pi.estado_final in ('CORRECT', 'WRONG')) > 0
              then round(count(*) filter (where pi.estado_final = 'CORRECT') * 100.0
                         / count(*) filter (where pi.estado_final in ('CORRECT', 'WRONG')), 2)
              else 0 end
  from public.preguntas_intento pi
  join lateral (
    -- Bound row: the revision's primary tag (if that tag still exists). Unbound legacy row: the live lookup.
    select x.etiqueta_id
    from (
      select (r.contenido -> 'etiquetas' -> 0 ->> 'id')::uuid as etiqueta_id
      from public.revisiones_pregunta r
      where r.id = pi.revision_pregunta_id
      union all
      select l.etiqueta_id
      from (select ep.etiqueta_id from public.etiquetas_pregunta ep
            where ep.pregunta_id = pi.pregunta_id
            order by ep.creado_en asc, ep.etiqueta_id asc
            limit 1) l
      where pi.revision_pregunta_id is null
    ) x
    join public.etiquetas e on e.id = x.etiqueta_id
    limit 1
  ) t on true
  left join public.respuestas_intento ri on ri.intento_id = pi.intento_id and ri.pregunta_id = pi.pregunta_id
  where pi.intento_id = p_intento_id and pi.estado_final <> 'ANNULLED'
  group by t.etiqueta_id
  on conflict (intento_id, etiqueta_id) do nothing;

  update public.intentos_examen
     set estado = 'finalizado',
         motivo_fin = 'USER',
         finalizado_en = now(),
         duracion_segundos = greatest(0, floor(extract(epoch from (now() - v_iniciado))))::integer,
         total_respondidas = v_correctas + v_falladas,
         total_correctas = v_correctas,
         total_falladas = v_falladas,
         total_sin_responder = v_sin_responder,
         porcentaje_acierto = case when v_correctas + v_falladas > 0
                                   then round(v_correctas * 100.0 / (v_correctas + v_falladas), 2) else 0 end,
         puntuacion = v_correctas,
         calculado_en = now()
   where id = p_intento_id;

  return jsonb_build_object('outcome', 'OK', 'already', false);
end;
$$;

revoke all on function public.app_finish_exam_attempt(uuid, uuid) from public, anon, authenticated;
grant execute on function public.app_finish_exam_attempt(uuid, uuid) to service_role;

do $$
begin
  if (select count(*) from pg_proc p
       where p.oid in (to_regprocedure('private.app_congelar_preguntas_intento(uuid,uuid)'),
                       to_regprocedure('public.app_record_attempt_answer(uuid,uuid,uuid,uuid,integer,integer,integer)'),
                       to_regprocedure('public.app_finish_exam_attempt(uuid,uuid)'))
         and p.prosrc ~ 'ATTEMPT_CONTENT_REVISIONS') <> 3 then
    raise exception 'SLICE3_REVISIONS_POSTCHECK' using detail = 'a command was not redefined';
  end if;
end;
$$;

commit;
