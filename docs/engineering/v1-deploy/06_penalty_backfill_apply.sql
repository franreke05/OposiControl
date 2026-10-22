-- V1 deploy · F4.3b · Relleno de la penalizacion racional. Paso 2: ESCRITURA (P, GO del propietario), UNA transaccion.
-- UPDATE copiado de la cabecera de 20261022103400 (feature/v1-exam-penalty-scope 74554ccd). Antes de ejecutar, sustituir:
--   <ADMIN_UUID>  = id del admin propietario (actor de auditoria_log; tiene que ser admin/superadmin ACTIVO, se comprueba)
--   <N>           = `candidatos` de 05_penalty_backfill_count_readonly.sql
-- Sin sustituir, falla (uuid / entero invalidos) y no cambia nada. Si las filas actualizadas no son exactamente <N>, se deshace todo.
-- Los examenes publicados se re-validan con trg_examenes_official_rules al COMMIT. Los intentos ya empezados conservan la regla que
-- congelaron; solo se penalizan los intentos nuevos. Notas de antes y despues NO son comparables (aviso de Arturo, aceptado por el propietario).
begin;
set local lock_timeout = '5s';
select set_config('app.audit_actor', '<ADMIN_UUID>'::uuid::text, true),
       set_config('app.audit_reason', 'backfill penalizacion racional (decision propietario 2026-10-22)', true);
do $$
declare
  v_expected integer := <N>;
  v_done integer;
  v_actor uuid := nullif(current_setting('app.audit_actor', true), '')::uuid;
begin
  -- el actor de auditoria tiene que ser un admin o superadmin con la cuenta ACTIVA (si no: nada se guarda)
  if not exists (select 1 from public.roles_usuario ru join public.roles_app ra on ra.id = ru.rol_id
                   join public.perfiles_usuario pu on pu.usuario_id = ru.usuario_id
                  where ru.usuario_id = v_actor and ra.codigo in ('admin', 'superadmin') and pu.estado_cuenta = 'activa') then
    raise exception using errcode = 'P0001', message = 'PENALTY_BACKFILL_ACTOR_NOT_ACTIVE_ADMIN',
      detail = '<ADMIN_UUID> no es un admin/superadmin con estado_cuenta = activa: nada se guarda';
  end if;
  update public.examenes set penalizacion_racional = true
   where es_oficial and not penalizacion_racional and penalizacion_num = 0;
  get diagnostics v_done = row_count;
  if v_done <> v_expected then
    raise exception using errcode = 'P0001', message = 'PENALTY_BACKFILL_COUNT_MISMATCH',
      detail = format('actualizadas %s, esperadas %s: nada se guarda', v_done, v_expected);
  end if;
  raise notice 'PENALTY_BACKFILL_OK: % examenes', v_done;
end
$$;
commit;
