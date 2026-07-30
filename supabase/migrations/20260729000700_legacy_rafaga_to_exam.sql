-- Preserve legacy burst data while making public.examen the only write model.
-- Legacy tables remain available for one-version read compatibility.

begin;

create table if not exists private.legacy_rafaga_exam_map (
    legacy_rafaga_id integer primary key,
    examen_id integer not null unique references public.examen(id) on delete restrict,
    migrated_at timestamptz not null default now()
);

create table if not exists private.legacy_rafaga_question_map (
    legacy_question_id integer primary key,
    pregunta_id integer not null unique references public.pregunta(id) on delete restrict,
    migrated_at timestamptz not null default now()
);

revoke all on table private.legacy_rafaga_exam_map
from public, anon, authenticated;
revoke all on table private.legacy_rafaga_question_map
from public, anon, authenticated;

do $$
declare
    missing_columns text[];
    legacy_row record;
    legacy_question record;
    canonical_exam_id integer;
    canonical_question_id integer;
    questions_without_options integer;
begin
    if to_regclass('public.rafaga') is null then
        return;
    end if;

    select array_agg(required.name order by required.name)
    into missing_columns
    from unnest(array[
        'id',
        'titulo',
        'precio_tickets',
        'especialidad_id',
        'estado'
    ]) as required(name)
    where not exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'rafaga'
          and column_name = required.name
    );

    if missing_columns is not null then
        insert into private.migration_guardrail(code, details)
        values (
            'legacy_rafaga_shape_unknown',
            jsonb_build_object(
                'missingColumns', to_jsonb(missing_columns),
                'action', 'map the legacy burst schema manually; no source rows were changed'
            )
        )
        on conflict (code) do update
        set details = excluded.details, detected_at = now(), resolved_at = null;
        return;
    end if;

    for legacy_row in
        execute $query$
            select id, titulo, precio_tickets, especialidad_id, estado
            from public.rafaga
            order by id
        $query$
    loop
        select examen_id
        into canonical_exam_id
        from private.legacy_rafaga_exam_map
        where legacy_rafaga_id = legacy_row.id;

        if canonical_exam_id is null then
            select id
            into canonical_exam_id
            from public.examen
            where codigo = 'legacy-rafaga-' || legacy_row.id::text
              and deleted_at is null
            limit 1;
        end if;

        if canonical_exam_id is null then
            insert into public.examen(
                codigo,
                titulo,
                descripcion,
                precio_tickets,
                especialidad_id,
                tipo_examen,
                tipo_acceso,
                estado,
                orden
            )
            values (
                'legacy-rafaga-' || legacy_row.id::text,
                legacy_row.titulo,
                '',
                greatest(coalesce(legacy_row.precio_tickets, 0), 0),
                legacy_row.especialidad_id,
                'rafaga',
                case
                    when coalesce(legacy_row.precio_tickets, 0) = 0
                        then 'gratuito'
                    else 'premium'
                end,
                case
                    when legacy_row.estado in ('draft', 'published', 'archived')
                        then legacy_row.estado
                    else 'draft'
                end,
                legacy_row.id
            )
            returning id into canonical_exam_id;
        end if;

        insert into private.legacy_rafaga_exam_map(
            legacy_rafaga_id,
            examen_id
        )
        values (legacy_row.id, canonical_exam_id)
        on conflict (legacy_rafaga_id) do update
        set examen_id = excluded.examen_id;
    end loop;

    if to_regclass('public.rafaga_pregunta') is null then
        return;
    end if;

    select array_agg(required.name order by required.name)
    into missing_columns
    from unnest(array[
        'id',
        'rafaga_id',
        'enunciado',
        'orden'
    ]) as required(name)
    where not exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'rafaga_pregunta'
          and column_name = required.name
    );

    if missing_columns is not null then
        insert into private.migration_guardrail(code, details)
        values (
            'legacy_rafaga_question_shape_unknown',
            jsonb_build_object(
                'missingColumns', to_jsonb(missing_columns),
                'action', 'map legacy burst questions manually; no source rows were changed'
            )
        )
        on conflict (code) do update
        set details = excluded.details, detected_at = now(), resolved_at = null;
        return;
    end if;

    for legacy_question in
        execute $query$
            select id, rafaga_id, enunciado, orden
            from public.rafaga_pregunta
            order by rafaga_id, orden, id
        $query$
    loop
        select examen_id
        into canonical_exam_id
        from private.legacy_rafaga_exam_map
        where legacy_rafaga_id = legacy_question.rafaga_id;

        if canonical_exam_id is null then
            continue;
        end if;

        select pregunta_id
        into canonical_question_id
        from private.legacy_rafaga_question_map
        where legacy_question_id = legacy_question.id;

        if canonical_question_id is null then
            insert into public.pregunta(
                examen_id,
                titulo,
                enunciado,
                orden,
                legacy_payload
            )
            values (
                canonical_exam_id,
                '',
                legacy_question.enunciado,
                coalesce(legacy_question.orden, 0),
                jsonb_build_object(
                    'source', 'rafaga_pregunta',
                    'legacyId', legacy_question.id
                )
            )
            returning id into canonical_question_id;

            insert into private.legacy_rafaga_question_map(
                legacy_question_id,
                pregunta_id
            )
            values (legacy_question.id, canonical_question_id);
        end if;
    end loop;

    select count(*)
    into questions_without_options
    from private.legacy_rafaga_question_map mapping
    join public.pregunta on pregunta.id = mapping.pregunta_id
    where pregunta.deleted_at is null
      and not exists (
          select 1
          from public.opcion_pregunta
          where opcion_pregunta.pregunta_id = pregunta.id
            and opcion_pregunta.deleted_at is null
      );

    if questions_without_options > 0 then
        update public.examen
        set estado = 'draft'
        where id in (
            select distinct pregunta.examen_id
            from private.legacy_rafaga_question_map mapping
            join public.pregunta on pregunta.id = mapping.pregunta_id
            where pregunta.deleted_at is null
              and not exists (
                  select 1
                  from public.opcion_pregunta
                  where opcion_pregunta.pregunta_id = pregunta.id
                    and opcion_pregunta.deleted_at is null
              )
        );

        insert into private.migration_guardrail(code, details)
        values (
            'legacy_rafaga_questions_need_options',
            jsonb_build_object(
                'count', questions_without_options,
                'action', 'add four normalized options and one correct answer before publishing'
            )
        )
        on conflict (code) do update
        set details = excluded.details, detected_at = now(), resolved_at = null;
    end if;
end;
$$;

commit;
