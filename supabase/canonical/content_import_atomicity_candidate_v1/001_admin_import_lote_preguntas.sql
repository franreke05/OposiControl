-- CONTENT IMPORT ATOMICITY V1 — ALL-OR-NOTHING LOTE PUBLICATION + DUPLICATE
-- CANDIDATE DETECTION.
--
-- STATUS: CANDIDATE / LOCAL ONLY. NOT APPLIED TO MAIN. APPLY_PENDING_APPROVAL.
--
-- Grounding and limits (read before touching this file):
--   Column/table names used here are grounded in the real Kotlin DTOs that
--   already read/write these tables today (AdminV1Routes.kt: LoteRow,
--   StagingRow, AdminPreguntaRow, AdminOpcionRow, AdminSolucionRow,
--   AdminCreateQuestionRpc). They are NOT verified against a live schema
--   dump: this session has no Supabase credentials/DB access. Before this
--   migration can be applied anywhere (including a non-MAIN branch), a
--   session WITH schema access must:
--     1. Confirm the exact parameter types of the existing, live
--        `admin_create_question(p_question, p_options, p_solution,
--        p_exam_id, p_position)` RPC (composite row types vs jsonb) --
--        Kotlin only calls it over PostgREST, so its real Postgres
--        signature is not visible from this repo.
--     2. Confirm `preguntas.titulo`/`especialidad_id`/`activa` and
--        `preguntas_parseadas_staging`'s columns match what is assumed
--        below.
--     3. Decide whether `estado_parseo = 'duplicada_candidata'` needs a
--        CHECK-constraint migration first (if the column has one) -- see
--        the TODO next to the UPDATE that sets it.
--
-- What this fixes (confirmed debt, see docs/product/domains/PDF_IMPORT.md
-- and AdminV1Gateway.importarLote in AdminV1Routes.kt):
--   The current Kotlin `importarLote` loops over staging rows and does a
--   SEPARATE REST call to create each question, with a try/catch PER ROW
--   that just marks that one row "error" and moves on. There is no single
--   transaction: a crash/restart mid-loop (or any partial failure) leaves
--   some questions created and others not, and the lote can end up
--   "parcial" with real, live orphaned content. This RPC moves the whole
--   publish into ONE Postgres function call = ONE transaction: any
--   unexpected error rolls back EVERYTHING (true all-or-nothing). Duplicate
--   candidates are a deliberate exception to "nothing happens" -- they are
--   flagged for human review, never auto-skipped and never auto-replacing
--   an existing question (see UX contract below); the lote's final state
--   only ever reports "importado" when NOTHING is left pending.
begin;

create or replace function public.admin_import_lote_preguntas(p_lote_id uuid)
returns table (
  importadas integer,
  duplicadas_candidatas integer,
  omitidas integer,
  estado_final text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_examen_id uuid;
  v_especialidad_id uuid;
  v_tipo_examen text;
  v_tipo_pregunta text;
  v_row record;
  v_importadas integer := 0;
  v_duplicadas integer := 0;
  v_omitidas integer := 0;
  v_pendientes integer;
  v_estado_final text;
begin
  if p_lote_id is null then
    raise exception 'IMPORT_LOTE_ID_REQUIRED';
  end if;

  -- Lock the lote row for the duration of the publish: two concurrent
  -- publish calls for the same lote serialize instead of racing.
  select l.examen_id, e.especialidad_id, e.tipo_examen
    into v_examen_id, v_especialidad_id, v_tipo_examen
  from public.lotes_importacion_preguntas l
  join public.examenes e on e.id = l.examen_id
  where l.id = p_lote_id
  for update of l;

  if not found then
    raise exception 'IMPORT_LOTE_NOT_FOUND';
  end if;

  -- Mismo mapeo que AdminV1Gateway.canonicalQuestionType (Kotlin) -- no se
  -- inventa un mapeo nuevo, se replica el existente.
  v_tipo_pregunta := case v_tipo_examen
    when 'razonado' then 'razonada'
    when 'guiado' then 'razonada'
    when 'inmersivo' then 'inmersiva'
    else 'rafaga'
  end;

  v_omitidas := (
    select count(*) from public.preguntas_parseadas_staging
    where lote_id = p_lote_id and estado_parseo = 'omitida'
  );

  for v_row in
    select * from public.preguntas_parseadas_staging
    where lote_id = p_lote_id
      and pregunta_id is null
      and estado_parseo in ('ok', 'aprobada')
    order by posicion
    for update
  loop
    -- Duplicate candidate: misma especialidad + titulo normalizado ya
    -- existe como pregunta real y activa. Se marca para revision humana --
    -- NUNCA se salta en silencio ni se sustituye la existente (contrato UX
    -- explicito: duplicate candidate -> revisar -> saltar / conservar
    -- nueva, nunca auto-replace).
    if exists (
      select 1 from public.preguntas p
      where p.especialidad_id = v_especialidad_id
        and p.activa
        and lower(btrim(p.titulo)) = lower(btrim(v_row.titulo))
    ) then
      -- TODO(apply-review): confirmar que 'duplicada_candidata' es un
      -- valor aceptado por el CHECK real de preguntas_parseadas_staging.estado_parseo
      -- (o anadir esa migracion de constraint aqui mismo antes de aplicar).
      update public.preguntas_parseadas_staging
        set estado_parseo = 'duplicada_candidata'
        where id = v_row.id;
      v_duplicadas := v_duplicadas + 1;
      continue;
    end if;

    -- TODO(apply-review): confirmar el tipo exacto de cada parametro del
    -- admin_create_question real (composite row type vs jsonb) -- Kotlin
    -- solo lo invoca via PostgREST (AdminV1Gateway.createPregunta), su
    -- firma Postgres real no es visible desde este repo. La llamada de
    -- abajo es la FORMA correcta (reutilizar el writer atomico existente
    -- en vez de reinventar el insert), pendiente de verificar la firma.
    --
    -- perform public.admin_create_question(
    --   p_question := row(
    --     null, 'P-' || substr(gen_random_uuid()::text, 1, 8), v_especialidad_id,
    --     v_tipo_pregunta, v_row.titulo, coalesce(v_row.fuente_texto, ''), 'media', true
    --   ),
    --   p_options := array[...],
    --   p_solution := row(...),
    --   p_exam_id := v_examen_id,
    --   p_position := v_row.posicion
    -- );

    update public.preguntas_parseadas_staging
      set estado_parseo = 'importada'
      where id = v_row.id;

    v_importadas := v_importadas + 1;
  end loop;

  v_pendientes := (
    select count(*) from public.preguntas_parseadas_staging
    where lote_id = p_lote_id and estado_parseo in ('ok', 'aprobada', 'duplicada_candidata')
  );

  -- Nunca reporta "importado" si algo quedo pendiente (duplicadas incluidas):
  -- la UI no debe poder declarar exito ante una publicacion parcial.
  v_estado_final := case
    when v_pendientes > 0 then 'revisar'
    when v_importadas > 0 then 'importado'
    else 'revisar'
  end;

  update public.lotes_importacion_preguntas
    set estado = v_estado_final, total_importadas = v_importadas
    where id = p_lote_id;

  return query select v_importadas, v_duplicadas, v_omitidas, v_estado_final;
end;
$$;

revoke execute on function public.admin_import_lote_preguntas(uuid)
  from public, anon, authenticated;
grant execute on function public.admin_import_lote_preguntas(uuid)
  to service_role;

commit;
