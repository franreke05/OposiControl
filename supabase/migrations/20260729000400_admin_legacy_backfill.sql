-- Safe compatibility backfill.
-- The original encoded question statement is retained until all clients use core:api.

begin;

do $$
declare
    question_row record;
    stored_payload jsonb;
    stored_option jsonb;
    payload_prefix constant text := E'__OPOSICONTROL_QUESTION_V1__\n';
begin
    for question_row in
        select id, enunciado
        from public.pregunta
        where enunciado like payload_prefix || '%'
          and legacy_payload is null
        order by id
    loop
        begin
            stored_payload := substring(
                question_row.enunciado
                from char_length(payload_prefix) + 1
            )::jsonb;

            update public.pregunta
            set
                titulo = coalesce(
                    nullif(stored_payload ->> 'titulo', ''),
                    titulo,
                    ''
                ),
                legacy_payload = stored_payload
            where id = question_row.id;

            for stored_option in
                select value
                from jsonb_array_elements(
                    coalesce(stored_payload -> 'opciones', '[]'::jsonb)
                )
            loop
                if coalesce(trim(stored_option ->> 'label'), '') <> ''
                    and not exists (
                        select 1
                        from public.opcion_pregunta
                        where pregunta_id = question_row.id
                          and etiqueta = stored_option ->> 'label'
                          and deleted_at is null
                    ) then
                    insert into public.opcion_pregunta(
                        pregunta_id,
                        etiqueta,
                        contenido,
                        es_correcta,
                        orden
                    )
                    values (
                        question_row.id,
                        stored_option ->> 'label',
                        coalesce(stored_option ->> 'contenido', ''),
                        coalesce(
                            (stored_option ->> 'esCorrecta')::boolean,
                            false
                        ),
                        coalesce((stored_option ->> 'orden')::integer, 0)
                    );
                end if;
            end loop;
        exception
            when others then
                insert into private.migration_guardrail(code, details)
                values (
                    'legacy_question_payload_parse_error',
                    jsonb_build_object(
                        'latestQuestionId', question_row.id,
                        'error', sqlerrm,
                        'action', 'repair payload manually; source enunciado was not changed'
                    )
                )
                on conflict (code) do update
                set
                    details = excluded.details,
                    detected_at = now(),
                    resolved_at = null;
        end;
    end loop;
end;
$$;

update public.recurso_pregunta
set texto = url
where tipo_recurso in ('texto', 'text')
  and coalesce(texto, '') = ''
  and coalesce(url, '') <> '';

do $$
declare
    legacy_tables jsonb;
begin
    select jsonb_agg(candidate.table_name order by candidate.table_name)
    into legacy_tables
    from (
        select table_name
        from unnest(array[
            'especialidades',
            'examenes',
            'preguntas_examen',
            'opciones_pregunta',
            'specialties',
            'exams',
            'exam_questions',
            'question_options',
            'question_resources',
            'store_items',
            'store_offers',
            'limited_resources',
            'burst_campaigns',
            'rafaga',
            'rafaga_pregunta'
        ]) as legacy(table_name)
        where to_regclass('public.' || table_name) is not null
    ) as candidate;

    if legacy_tables is not null then
        insert into private.migration_guardrail(code, details)
        values (
            'legacy_parallel_schema_detected',
            jsonb_build_object(
                'tables', legacy_tables,
                'action', 'confirm consumers and migrate explicitly before decommissioning'
            )
        )
        on conflict (code) do update
        set details = excluded.details, detected_at = now(), resolved_at = null;
    end if;
end;
$$;

do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conrelid = 'public.usuario'::regclass
          and conname = 'usuario_auth_user_id_fkey'
    ) then
        alter table public.usuario
            add constraint usuario_auth_user_id_fkey
            foreign key (auth_user_id)
            references auth.users(id)
            on delete set null
            not valid;
    end if;
end;
$$;

alter table public.examen
    validate constraint examen_tipo_examen_check;
alter table public.examen
    validate constraint examen_tipo_acceso_check;
alter table public.recurso_pregunta
    validate constraint recurso_pregunta_tipo_recurso_check;

commit;
