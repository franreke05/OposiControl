-- PRODUCT METRICS CANDIDATE V1 — FIRST RESPONSE INTEGRITY
begin;

do $$
begin
  if to_regclass('public.respuestas_intento') is null then
    raise exception 'PRODUCT_METRICS_FIRST_RESPONSE_MISSING_TABLE';
  end if;
end $$;

alter table public.respuestas_intento drop constraint if exists respuestas_intento_first_response_user_not_null;
alter table public.respuestas_intento add constraint respuestas_intento_first_response_user_not_null
  check (not es_primer_intento_pregunta or usuario_id is not null);

alter table public.respuestas_intento drop constraint if exists respuestas_intento_ranking_requires_first_response;
alter table public.respuestas_intento add constraint respuestas_intento_ranking_requires_first_response
  check (not cuenta_para_ranking or es_primer_intento_pregunta);

-- cuenta_para_ranking is deliberately absent: ranking eligibility and
-- first-real-response identity are different concepts.
create unique index if not exists ux_respuestas_first_response
  on public.respuestas_intento (usuario_id, pregunta_id)
  where es_primer_intento_pregunta is true;

commit;
