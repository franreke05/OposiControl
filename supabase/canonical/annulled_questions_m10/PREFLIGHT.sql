-- PREFLIGHT de M10 (20260919122139_annulled_questions_in_exams.sql). Sólo SELECT.
-- Todas las filas deben decir OK antes de aplicar la migración.
select 'columna_ausente' as comprobacion,
       case when not exists (select 1 from information_schema.columns
                              where table_schema = 'public' and table_name = 'preguntas_examen' and column_name = 'anulada')
            then 'OK' else 'FALLA: ya existe' end as resultado
union all
select 'firmas_esperadas',
       case when to_regprocedure('public.admin_link_question_to_exam(uuid, uuid, integer)') is not null
             and to_regprocedure('public.admin_create_question(uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid)') is not null
             and to_regprocedure('public.admin_import_lote_preguntas(uuid, boolean, uuid)') is not null
             and to_regprocedure('public.record_answer(uuid, uuid, uuid, uuid)') is not null
            then 'OK' else 'FALLA: falta una firma (¿M8 sin aplicar?)' end
union all
select 'sin_vistas_sobre_preguntas_examen',
       case when not exists (select 1 from pg_depend d join pg_rewrite r on r.oid = d.objid
                               join pg_class c on c.oid = r.ev_class
                              where d.refobjid = 'public.preguntas_examen'::regclass and c.relkind in ('v', 'm'))
            then 'OK' else 'REVISAR: hay vistas' end
union all
select 'una_sola_sobrecarga_por_funcion',
       case when (select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                   where n.nspname = 'public'
                     and p.proname in ('admin_link_question_to_exam', 'admin_create_question', 'record_answer', 'admin_import_lote_preguntas')) = 4
            then 'OK' else 'REVISAR: sobrecargas inesperadas' end;
