-- PRODUCT METRICS CANDIDATE V1 — FINAL RUNTIME RETURNING FIX
-- Corrective follow-up to 007: qualify INSERT RETURNING columns by target alias.
begin;

create or replace function public.record_answer(
  p_usuario_id uuid,
  p_intento_id uuid,
  p_pregunta_id uuid,
  p_opcion_seleccionada_id uuid
)
returns table (
  respuesta_id uuid,
  es_correcta boolean,
  opcion_correcta_id uuid,
  es_primer_intento_pregunta boolean,
  cuenta_para_ranking boolean
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_examen_id uuid;
  v_cuenta_para_ranking boolean;
  v_correcta_id uuid;
  v_first boolean;
  v_constraint_name text;
  v_existing public.respuestas_intento%rowtype;
  v_response_id uuid;
  v_response_correct boolean;
  v_response_correcta_id uuid;
  v_response_first boolean;
  v_response_ranking boolean;
begin
  perform pg_advisory_xact_lock(hashtextextended(p_usuario_id::text || ':' || p_pregunta_id::text, 0));

  select ie.examen_id, ie.cuenta_para_ranking into v_examen_id, v_cuenta_para_ranking
  from public.intentos_examen ie
  where ie.id = p_intento_id and ie.usuario_id = p_usuario_id and ie.estado = 'en_progreso'
  for update;
  if not found then raise exception 'RECORD_ANSWER_INVALID_ATTEMPT'; end if;

  if not exists (select 1 from public.preguntas_examen pe where pe.examen_id = v_examen_id and pe.pregunta_id = p_pregunta_id) then
    raise exception 'RECORD_ANSWER_QUESTION_NOT_IN_ATTEMPT';
  end if;
  if not exists (select 1 from public.opciones_pregunta op where op.id = p_opcion_seleccionada_id and op.pregunta_id = p_pregunta_id) then
    raise exception 'RECORD_ANSWER_OPTION_NOT_IN_QUESTION';
  end if;

  select sp.opcion_correcta_id into v_correcta_id
  from public.soluciones_pregunta sp where sp.pregunta_id = p_pregunta_id limit 1;
  if v_correcta_id is null then raise exception 'RECORD_ANSWER_MISSING_SOLUTION'; end if;

  select ri.* into v_existing from public.respuestas_intento ri
  where ri.intento_id = p_intento_id and ri.pregunta_id = p_pregunta_id limit 1;
  if found then
    return query select v_existing.id, v_existing.es_correcta, v_existing.opcion_correcta_id,
      v_existing.es_primer_intento_pregunta, v_existing.cuenta_para_ranking;
    return;
  end if;

  select not exists (select 1 from public.respuestas_intento previous
    where previous.usuario_id = p_usuario_id and previous.pregunta_id = p_pregunta_id) into v_first;

  begin
    insert into public.respuestas_intento as response_row (
      intento_id, pregunta_id, opcion_seleccionada_id, es_correcta, usuario_id,
      examen_id, opcion_correcta_id, respondida_en, cuenta_para_ranking,
      es_primer_intento_pregunta
    ) values (
      p_intento_id, p_pregunta_id, p_opcion_seleccionada_id,
      p_opcion_seleccionada_id = v_correcta_id, p_usuario_id, v_examen_id,
      v_correcta_id, now(), v_cuenta_para_ranking and v_first, v_first
    ) returning response_row.id, response_row.es_correcta, response_row.opcion_correcta_id,
      response_row.es_primer_intento_pregunta, response_row.cuenta_para_ranking
      into v_response_id, v_response_correct, v_response_correcta_id, v_response_first, v_response_ranking;
    return query select v_response_id, v_response_correct, v_response_correcta_id, v_response_first, v_response_ranking;
  exception when unique_violation then
    get stacked diagnostics v_constraint_name = constraint_name;
    if v_constraint_name is distinct from 'ux_respuestas_first_response' or v_first is not true then
      raise;
    end if;

    insert into public.respuestas_intento as response_row (
      intento_id, pregunta_id, opcion_seleccionada_id, es_correcta, usuario_id,
      examen_id, opcion_correcta_id, respondida_en, cuenta_para_ranking,
      es_primer_intento_pregunta
    ) values (
      p_intento_id, p_pregunta_id, p_opcion_seleccionada_id,
      p_opcion_seleccionada_id = v_correcta_id, p_usuario_id, v_examen_id,
      v_correcta_id, now(), false, false
    ) returning response_row.id, response_row.es_correcta, response_row.opcion_correcta_id,
      response_row.es_primer_intento_pregunta, response_row.cuenta_para_ranking
      into v_response_id, v_response_correct, v_response_correcta_id, v_response_first, v_response_ranking;
    return query select v_response_id, v_response_correct, v_response_correcta_id, v_response_first, v_response_ranking;
  end;
end;
$$;

revoke execute on function public.record_answer(uuid, uuid, uuid, uuid) from public, anon, authenticated;
grant execute on function public.record_answer(uuid, uuid, uuid, uuid) to service_role;

commit;
