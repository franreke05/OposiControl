-- ROLLBACK de M9 (20260919090808_exams_specialty_change_unlocked.sql): vuelve a bloquear el cambio
-- de `especialidad_id` de un examen con preguntas vinculadas, además del de `tipo_examen`.
-- Sólo tiene sentido junto con ROLLBACK.sql (M8): sin M8 la especialidad no forma parte de la regla.

begin;

drop trigger if exists trg_examenes_bloquear_cambio_tipo on public.examenes;
create trigger trg_examenes_bloquear_cambio_tipo
  before update of tipo_examen, especialidad_id on public.examenes
  for each row
  when (old.tipo_examen is distinct from new.tipo_examen
        or old.especialidad_id is distinct from new.especialidad_id)
  execute function private.tg_examenes_bloquear_cambio_tipo();

commit;
