-- ROLLBACK de M8 (20260919085404_questions_without_specialty.sql).
--
-- Reabre la regla que M8 retiró («la pregunta pertenece a una especialidad»). Sólo es
-- razonable si el cambio de producto se revierte; no es un mecanismo de operación normal.
--
-- Restaura, en una transacción:
--   1. preguntas.especialidad_id (NOT NULL, FK, índice) desde
--      private.preguntas_especialidad_backup_20260919. Las preguntas creadas DESPUÉS de M8 no
--      tienen copia: heredan la especialidad del primer examen en el que están. Si alguna no
--      está en ningún examen, el rollback ABORTA en vez de inventar un dato: hay que
--      decidirlo a mano (vincularla a un examen o archivarla/borrarla) y repetir.
--   2. Las definiciones EXACTAS anteriores de las cinco funciones y del trigger, guardadas
--      por M8 en private.m8_previous_definitions_20260919.
--
-- Límites conocidos (por eso el rollback aborta o avisa en vez de adivinar):
--   * La especialidad heredada del «primer examen» no es fiable si la pregunta está en exámenes
--     de varias especialidades; el preflight 0) lo impide.
--   * El FK se recrea con la copia de seguridad: si una especialidad del respaldo se borró tras
--     M8, `add constraint` falla y el rollback entero se deshace (no queda a medias).
--   * Los UPDATE de restauración no tocan `actualizado_en`.
--
-- No borra las dos tablas private.* de respaldo: hágalo a mano cuando el rollback ya no
-- pueda hacer falta.

begin;

-- 0) Preflight: la regla antigua sólo se comprobaba al INSERTAR el vínculo, así que no puede
--    representar una pregunta ya vinculada a exámenes de especialidades distintas (lo que M8
--    permite). Se aborta ANTES de tocar nada; decide a mano qué vínculos quitar y repite.
do $$
declare
  v_cruces integer;
begin
  select count(*) into v_cruces
    from (select pe.pregunta_id
            from public.preguntas_examen pe
            join public.examenes e on e.id = pe.examen_id
           group by pe.pregunta_id
          having count(distinct e.especialidad_id) > 1) x;
  if v_cruces > 0 then
    raise exception 'ROLLBACK_M8_ABORT: % pregunta(s) están en exámenes de especialidades distintas; la regla antigua no admite ese estado. Quita vínculos hasta que cada una quede en una sola especialidad y repite', v_cruces;
  end if;
end;
$$;

-- 1) La columna.
alter table public.preguntas add column especialidad_id uuid;
alter table public.preguntas disable trigger trg_preguntas_actualizado_en;

update public.preguntas p
   set especialidad_id = b.especialidad_id
  from private.preguntas_especialidad_backup_20260919 b
 where b.pregunta_id = p.id;

update public.preguntas p
   set especialidad_id = (select e.especialidad_id
                            from public.preguntas_examen pe
                            join public.examenes e on e.id = pe.examen_id
                           where pe.pregunta_id = p.id
                           order by pe.posicion, pe.examen_id
                           limit 1)
 where p.especialidad_id is null;

do $$
begin
  if exists (select 1 from public.preguntas where especialidad_id is null) then
    raise exception 'ROLLBACK_M8_ABORT: hay preguntas sin copia de especialidad y fuera de todo examen; decide su especialidad a mano y repite';
  end if;
end;
$$;

alter table public.preguntas enable trigger trg_preguntas_actualizado_en;
alter table public.preguntas alter column especialidad_id set not null;
alter table public.preguntas
  add constraint preguntas_especialidad_id_fkey
  foreign key (especialidad_id) references public.especialidades(id) on delete restrict;
create index idx_preguntas_especialidad on public.preguntas using btree (especialidad_id);

-- 2) Funciones: primero se retira la firma nueva de admin_create_question (12 args).
drop function if exists public.admin_create_question(uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid);

do $$
declare
  r record;
begin
  for r in
    select objeto, definicion
      from private.m8_previous_definitions_20260919
     where objeto not like 'trigger %'
  loop
    execute r.definicion;
  end loop;
end;
$$;

revoke all on function public.admin_create_question(uuid, uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid) from public, anon, authenticated;
grant execute on function public.admin_create_question(uuid, uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid) to service_role;

-- 3) Trigger con la lista de columnas original.
drop trigger if exists trg_preguntas_bloquear_cambio_tipo on public.preguntas;
do $$
declare
  d text;
begin
  select definicion into d
    from private.m8_previous_definitions_20260919
   where objeto = 'trigger trg_preguntas_bloquear_cambio_tipo';
  if d is null then
    raise exception 'ROLLBACK_M8_ABORT: falta la definición anterior del trigger';
  end if;
  execute d;
end;
$$;

commit;
