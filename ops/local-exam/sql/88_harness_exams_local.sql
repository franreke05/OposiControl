-- LOCAL ONLY (never a migration, never MAIN): registry of exams created by the test harnesses, so the shared Market does not fill up with them.
-- A harness marks the exams it created (harness-marker.mjs). Cleanup (`up.mjs --clean-test-exams`, also run by `--restore-fixtures`) touches ONLY marked exams:
-- it deletes each one in its own sub-transaction and, when a foreign key still references it (attempts, entitlements, ledger), hides it (activo = false) instead.
-- Baseline fixtures (LOC-*) and any unmarked exam are never touched.
create table if not exists public.local_harness_exams (
  examen_id uuid primary key,
  marcado_en timestamptz not null default now(),
  etiqueta text
);

create or replace function public.local_clean_harness_exams(p_include_legacy boolean default false)
returns table (borrados integer, ocultados integer)
language plpgsql
as $$
declare
  r record; v_del integer := 0; v_hid integer := 0;
begin
  if p_include_legacy then
    -- explicit one-off opt-in: the enumerated code prefixes the harnesses used BEFORE the marker existed (E2 security suites and the official-rules journey)
    insert into public.local_harness_exams (examen_id, etiqueta)
    select e.id, 'legacy-prefix' from public.examenes e
     where e.codigo_examen not like 'LOC-%'
       and (e.codigo_examen ~ '^(E2A-|E2B-|E2C|E2G-|E2O-|E2E-COM-|OFF-)')
    on conflict (examen_id) do nothing;
  end if;
  for r in select m.examen_id from public.local_harness_exams m join public.examenes e on e.id = m.examen_id where e.codigo_examen not like 'LOC-%' loop
    begin
      delete from public.examenes where id = r.examen_id;
      delete from public.local_harness_exams where examen_id = r.examen_id;
      v_del := v_del + 1;
    exception when others then
      update public.examenes set activo = false where id = r.examen_id and activo;
      v_hid := v_hid + 1;
    end;
  end loop;
  -- markers whose exam no longer exists
  delete from public.local_harness_exams m where not exists (select 1 from public.examenes e where e.id = m.examen_id);
  return query select v_del, v_hid;
end $$;

-- F5 (E5-V1-QA-INT09): test specialties created by harnesses (E2 challenge / foro / final8..., J20) must not be served by the public picker: only the baseline ones stay published.
create or replace function public.local_hide_test_specialties()
returns integer
language plpgsql
as $$
declare v_n integer;
begin
  update public.especialidades set activa = false where activa and codigo not in ('LOC', 'ING');
  get diagnostics v_n = row_count;
  return v_n;
end $$;
