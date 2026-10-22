-- V1 deploy · F4.3b · Relleno de la penalizacion racional en los examenes OFICIALES existentes. Paso 1: RECUENTO (SOLO LECTURA, agregados).
-- Decision del propietario 2026-10-22: SI. Solo despues de 20261022101300 (crea penalizacion_racional) y de 20261022103400.
-- Copiado de la cabecera de 20261022103400 (feature/v1-exam-penalty-scope 74554ccd). Anotar `candidatos`: es el <N> de 06_*.sql.
begin read only;
select count(*) filter (where es_oficial)                                                                         as oficiales,
       count(*) filter (where es_oficial and penalizacion_racional)                                               as oficiales_ya_con_racional,
       count(*) filter (where es_oficial and not penalizacion_racional and penalizacion_num = 0)                   as candidatos,
       count(*) filter (where es_oficial and not penalizacion_racional and penalizacion_num = 0 and publicado_en is not null) as candidatos_publicados,
       count(*) filter (where es_oficial and penalizacion_num > 0)                                                as con_fraccion_fija_no_se_tocan
  from public.examenes;
commit;
