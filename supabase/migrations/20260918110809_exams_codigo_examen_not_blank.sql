-- M5 · codigo_examen obligatorio y no compuesto sólo por espacios.
-- Rollback preparado (NO ejecutar salvo decisión explícita):
--   alter table public.examenes drop constraint if exists examenes_codigo_examen_no_vacio_chk;
--   update public.examenes
--      set codigo_examen = ''
--    where id = '336071a5-e2c2-44e9-9b62-691912f7a776'::uuid
--      and codigo_examen = 'EX-336071A5';
-- El rollback reabre el defecto y restaura un dato inválido; la opción segura es retirar sólo la constraint.

set local lock_timeout = '5s';
lock table public.examenes in share row exclusive mode;

do $pre$
declare
  v_target_id constant uuid := '336071a5-e2c2-44e9-9b62-691912f7a776';
  v_replacement constant text := 'EX-336071A5';
begin
  if exists (
    select 1
      from pg_constraint
     where conrelid = 'public.examenes'::regclass
       and conname = 'examenes_codigo_examen_no_vacio_chk'
  ) then
    raise exception 'PREFLIGHT_FAILED: examenes_codigo_examen_no_vacio_chk ya existe';
  end if;

  if exists (
    select 1
      from public.examenes
     where codigo_examen !~ '[^[:space:]]'
       and id <> v_target_id
  ) then
    raise exception 'PREFLIGHT_FAILED: existen codigos en blanco fuera de la fila autorizada';
  end if;

  if exists (
    select 1
      from public.examenes
     where id = v_target_id
       and codigo_examen !~ '[^[:space:]]'
  ) and exists (
    select 1
      from public.examenes
     where codigo_examen = v_replacement
       and id <> v_target_id
  ) then
    raise exception 'PREFLIGHT_FAILED: el codigo de reemplazo ya esta ocupado';
  end if;
end
$pre$;

update public.examenes
   set codigo_examen = 'EX-336071A5'
 where id = '336071a5-e2c2-44e9-9b62-691912f7a776'::uuid
   and codigo_examen !~ '[^[:space:]]';

alter table public.examenes
  add constraint examenes_codigo_examen_no_vacio_chk
  check (codigo_examen ~ '[^[:space:]]');

do $post$
begin
  if exists (
    select 1 from public.examenes
    where codigo_examen !~ '[^[:space:]]'
  ) then
    raise exception 'POSTCHECK_FAILED: quedan codigos de examen en blanco';
  end if;

  if (select codigo_examen
        from public.examenes
       where id = '336071a5-e2c2-44e9-9b62-691912f7a776'::uuid)
     is distinct from 'EX-336071A5' then
    raise exception 'POSTCHECK_FAILED: la fila objetivo no quedo reparada';
  end if;

  if not exists (
    select 1
      from pg_constraint
     where conrelid = 'public.examenes'::regclass
       and conname = 'examenes_codigo_examen_no_vacio_chk'
       and contype = 'c'
       and convalidated
  ) then
    raise exception 'POSTCHECK_FAILED: la constraint no existe o no esta validada';
  end if;
end
$post$;
