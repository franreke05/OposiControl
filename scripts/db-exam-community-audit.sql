-- V1 STEP 2 legacy audit (AGGREGATE ONLY). Read-only; reads NO titles, ids of people or content, only counts by state.
-- Purpose: before `ALTER TABLE examenes ALTER COLUMN comunidad_id SET NOT NULL`, how many exams have no community, and where.
-- RUN POLICY: this reads MAIN rows (counts), so it needs the OWNER's explicit approval. It has NOT been run on MAIN. It is run freely on the
-- local stack (psql -f). Output = four counts and a per-state breakdown; nothing is modified.
begin read only;

select 'total_exams'                                  as metric, count(*)::bigint as value from public.examenes
union all
select 'without_community',                           count(*) from public.examenes where comunidad_id is null
union all
select 'without_community_and_published',             count(*) from public.examenes where comunidad_id is null and publicado_en is not null
union all
select 'without_community_published_and_active',      count(*) from public.examenes where comunidad_id is null and publicado_en is not null and activo
union all
select 'with_community_pointing_nowhere',             count(*) from public.examenes e where e.comunidad_id is not null and not exists (select 1 from public.comunidades_autonomas c where c.id = e.comunidad_id)
union all
select 'communities_in_catalog',                      count(*) from public.comunidades_autonomas
union all
select 'communities_active',                          count(*) from public.comunidades_autonomas where activa;

-- Breakdown of the NULL rows by access type and exam type (no identifiers, no titles).
select tipo_acceso, tipo_examen, (publicado_en is not null) as published, activo, count(*)::bigint as exams
from public.examenes
where comunidad_id is null
group by 1, 2, 3, 4
order by 1, 2, 3, 4;

commit;
