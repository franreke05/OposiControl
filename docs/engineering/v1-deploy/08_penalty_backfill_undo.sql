-- V1 deploy · F4.3b · DESHACER el relleno de la penalizacion racional (P, ESCRITURA, GO expreso; solo si hace falta, idealmente antes de GO-6).
-- Vuelve a false SOLO los examenes que cambio 06_penalty_backfill_apply.sql (los identifica auditoria_log por su motivo) y que siguen en
-- true. Una transaccion; actor ACTIVO comprobado igual que en 06. Sustituir antes:
--   <ADMIN_UUID> = admin/superadmin activo (actor de auditoria)
--   <N>          = auditoria_reglas_backfill de 07_penalty_backfill_postcheck_readonly.sql (examenes que cambio el relleno)
-- Si las filas devueltas a false no son exactamente <N> (p. ej. un admin ya cambio a mano alguno), se deshace todo y se revisa a mano.
-- Los intentos empezados entre el relleno y esta vuelta atras conservan la regla que congelaron (penalizados).
-- En R-2 no hace falta: el ROLLBACK de 20261022101300 borra la columna entera.
begin;
set local lock_timeout = '5s';
select set_config('app.audit_actor', '<ADMIN_UUID>'::uuid::text, true),
       set_config('app.audit_reason', 'deshacer backfill penalizacion racional', true);
do $$
declare
  v_expected integer := <N>;
  v_done integer;
  v_actor uuid := nullif(current_setting('app.audit_actor', true), '')::uuid;
begin
  if not exists (select 1 from public.roles_usuario ru join public.roles_app ra on ra.id = ru.rol_id
                   join public.perfiles_usuario pu on pu.usuario_id = ru.usuario_id
                  where ru.usuario_id = v_actor and ra.codigo in ('admin', 'superadmin') and pu.estado_cuenta = 'activa') then
    raise exception using errcode = 'P0001', message = 'PENALTY_BACKFILL_ACTOR_NOT_ACTIVE_ADMIN';
  end if;
  update public.examenes e set penalizacion_racional = false
   where e.penalizacion_racional
     and e.id in (select a.registro_id from public.auditoria_log a
                   where a.accion = 'examen_reglas_puntuacion_modificadas' and a.tabla_afectada = 'examenes'
                     and a.despues ->> 'motivo' like 'backfill penalizacion racional%'
                     and (a.despues ->> 'penalizacion_racional')::boolean
                     and not coalesce((a.antes ->> 'penalizacion_racional')::boolean, false));
  get diagnostics v_done = row_count;
  if v_done <> v_expected then
    raise exception using errcode = 'P0001', message = 'PENALTY_BACKFILL_UNDO_COUNT_MISMATCH',
      detail = format('devueltas %s, esperadas %s: nada se guarda', v_done, v_expected);
  end if;
  raise notice 'PENALTY_BACKFILL_UNDO_OK: % examenes', v_done;
end
$$;
commit;
