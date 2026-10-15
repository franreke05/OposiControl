-- V1 STEP 2 (LOCAL ONLY, test data; never a migration, never MAIN). Comunidades de prueba y su asignacion a los examenes de fixture.
-- Los examenes locales nacieron SIN comunidad (antes de la regla V1). Aqui se les asigna una comunidad de prueba para que cada examen del
-- stack local cumpla la regla. NO es un valor por defecto de producto: en MAIN la asignacion la decide el propietario (E1-V1-COMMUNITY.md).
--   c1000000-...-01  ES-MD  Comunidad de Madrid   -> examenes LOC-* (e0000000-...) 
--   c1000000-...-02  ES-AN  Andalucia             -> examen LOC-ING (e1000000-...-e2)
-- Idempotente: solo asigna a los examenes que aun no tienen comunidad (UPDATE permitido: el trigger solo bloquea poner NULL o publicar sin ella).
insert into public.comunidades_autonomas (id, codigo, nombre, slug, activa, posicion) values
  ('c1000000-0000-4000-8000-000000000001', 'ES-MD', 'Comunidad de Madrid', 'comunidad-de-madrid', true, 13),
  ('c1000000-0000-4000-8000-000000000002', 'ES-AN', 'Andalucía', 'andalucia', true, 1)
on conflict (id) do nothing;

update public.examenes set comunidad_id = 'c1000000-0000-4000-8000-000000000001'
 where comunidad_id is null and id::text like 'e0000000-%';
update public.examenes set comunidad_id = 'c1000000-0000-4000-8000-000000000002'
 where comunidad_id is null and id = 'e1000000-0000-4000-8000-0000000000e2';
