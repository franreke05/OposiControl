-- M9 · La especialidad de un examen se puede corregir aunque ya tenga preguntas.
--
-- Consecuencia de M8 (las preguntas no tienen especialidad; la compatibilidad pregunta ↔ examen
-- es sólo por TIPO). M3 bloqueaba tanto el cambio de `tipo_examen` como el de `especialidad_id`
-- de un examen con preguntas vinculadas, porque la especialidad era parte de la regla de vínculo.
-- Ya no lo es: ninguna pregunta vinculada depende de la especialidad del examen, así que ese
-- bloqueo sólo impedía corregir un dato del examen sin ningún invariante que proteger.
--
-- El TIPO sí sigue bloqueado (una pregunta razonada no puede acabar en un examen ráfaga).
-- Rollback: supabase/canonical/questions_without_specialty_m8/ROLLBACK_M9.sql.

drop trigger if exists trg_examenes_bloquear_cambio_tipo on public.examenes;
create trigger trg_examenes_bloquear_cambio_tipo
  before update of tipo_examen on public.examenes
  for each row
  when (old.tipo_examen is distinct from new.tipo_examen)
  execute function private.tg_examenes_bloquear_cambio_tipo();
