-- V1 deploy · F4.3b · Relleno de la penalizacion racional. Paso 3: POST-CHECK (SOLO LECTURA, agregados).
-- Esperado: candidatos_restantes = 0 y oficiales_con_racional = oficiales_ya_con_racional (paso 1) + candidatos (paso 1).
-- auditoria_reglas_backfill = <N>: trg_examenes_audit_grading_rules (20261022101300) anota cada cambio con el motivo del paso 2.
begin read only;
select count(*) filter (where es_oficial)                                                        as oficiales,
       count(*) filter (where es_oficial and penalizacion_racional)                              as oficiales_con_racional,
       count(*) filter (where es_oficial and not penalizacion_racional and penalizacion_num = 0)  as candidatos_restantes,
       (select count(*) from public.auditoria_log
         where accion = 'examen_reglas_puntuacion_modificadas' and despues ->> 'motivo' like 'backfill penalizacion racional%'
           and (despues ->> 'penalizacion_racional')::boolean
           and coalesce((antes ->> 'penalizacion_racional')::boolean, false) = false)      as auditoria_reglas_backfill
  from public.examenes;
commit;
