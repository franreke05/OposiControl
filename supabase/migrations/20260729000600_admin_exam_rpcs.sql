-- Atomic exam-question import and reorder.
-- Question bodies are never copied into audit metadata.

begin;

create extension if not exists pgcrypto with schema extensions;

alter table private.api_idempotency
    add column if not exists state text not null default 'processing',
    add column if not exists response_status integer,
    add column if not exists updated_at timestamptz not null default now();

update private.api_idempotency
set
    state = case when response_body is null then 'processing' else 'completed' end,
    response_status = case when response_body is null then null else 200 end,
    updated_at = now();

do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conrelid = 'private.api_idempotency'::regclass
          and conname = 'api_idempotency_state_check'
    ) then
        alter table private.api_idempotency
            add constraint api_idempotency_state_check
            check (state in ('processing', 'completed', 'failed'));
    end if;
end;
$$;

create or replace function private.claim_idempotency(
    operation_name text,
    supplied_key text,
    request_body jsonb
)
returns private.api_idempotency
language plpgsql
security definer
set search_path = ''
as $$
declare
    actor_id uuid := auth.uid();
    request_fingerprint text := encode(
        extensions.digest(
            convert_to(request_body::text, 'UTF8'),
            'sha256'
        ),
        'hex'
    );
    claim private.api_idempotency%rowtype;
begin
    if actor_id is null then
        raise exception 'Authentication required' using errcode = '28000';
    end if;
    if supplied_key is null or length(trim(supplied_key)) < 8
        or length(supplied_key) > 200 then
        raise exception 'Idempotency key must contain between 8 and 200 characters'
            using errcode = '22023';
    end if;

    delete from private.api_idempotency
    where expires_at < now();

    insert into private.api_idempotency(
        actor_auth_user_id,
        operation,
        idempotency_key,
        request_hash,
        state
    )
    values (
        actor_id,
        operation_name,
        trim(supplied_key),
        request_fingerprint,
        'processing'
    )
    on conflict (actor_auth_user_id, operation, idempotency_key) do nothing;

    select *
    into claim
    from private.api_idempotency
    where actor_auth_user_id = actor_id
      and operation = operation_name
      and idempotency_key = trim(supplied_key)
    for update;

    if claim.request_hash <> request_fingerprint then
        raise exception 'Idempotency key was already used for a different request'
            using errcode = '23505';
    end if;

    return claim;
end;
$$;

create or replace function private.complete_idempotency(
    operation_name text,
    supplied_key text,
    response_body jsonb
)
returns void
language sql
security definer
set search_path = ''
as $$
    update private.api_idempotency
    set
        response_body = complete_idempotency.response_body,
        response_status = 200,
        state = 'completed',
        updated_at = now()
    where actor_auth_user_id = auth.uid()
      and operation = operation_name
      and idempotency_key = trim(supplied_key)
$$;

revoke all on function private.claim_idempotency(text, text, jsonb) from public;
revoke all on function private.complete_idempotency(text, text, jsonb) from public;

create or replace function public.admin_import_exam_questions(
    p_exam_id integer,
    p_questions jsonb,
    p_idempotency_key text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    actor_usuario_id integer;
    question_count integer;
    question_item jsonb;
    options jsonb;
    option_item jsonb;
    option_count integer;
    correct_count integer;
    distinct_label_count integer;
    question_id integer;
    question_ids integer[] := '{}';
    audit_id bigint;
    request_data jsonb;
    claim private.api_idempotency%rowtype;
    result jsonb;
begin
    actor_usuario_id := private.require_permission('content.write');

    if jsonb_typeof(p_questions) <> 'array' then
        raise exception 'Questions must be a JSON array' using errcode = '22023';
    end if;

    question_count := jsonb_array_length(p_questions);
    if question_count < 1 or question_count > 50 then
        raise exception 'Import must contain between 1 and 50 questions'
            using errcode = '22023';
    end if;

    perform 1
    from public.examen
    where id = p_exam_id
      and deleted_at is null
    for update;
    if not found then
        raise exception 'Exam not found' using errcode = 'P0002';
    end if;

    request_data := jsonb_build_object(
        'examId', p_exam_id,
        'questions', p_questions
    );
    select *
    into claim
    from private.claim_idempotency(
        'admin_import_exam_questions',
        p_idempotency_key,
        request_data
    );
    if claim.response_body is not null then
        return claim.response_body;
    end if;

    for question_item in
        select value from jsonb_array_elements(p_questions)
    loop
        if coalesce(trim(question_item ->> 'statement'), '') = '' then
            raise exception 'Every question requires a statement'
                using errcode = '22023';
        end if;

        options := coalesce(question_item -> 'options', '[]'::jsonb);
        if jsonb_typeof(options) <> 'array' then
            raise exception 'Question options must be a JSON array'
                using errcode = '22023';
        end if;

        option_count := jsonb_array_length(options);
        select
            count(*) filter (
                where coalesce((value ->> 'correct')::boolean, false)
            ),
            count(distinct lower(trim(value ->> 'label')))
        into correct_count, distinct_label_count
        from jsonb_array_elements(options);

        if option_count <> 4
            or correct_count <> 1
            or distinct_label_count <> 4 then
            raise exception 'Every question requires four unique options and one correct answer'
                using errcode = '22023';
        end if;

        if exists (
            select 1
            from jsonb_array_elements(options)
            where coalesce(trim(value ->> 'label'), '') = ''
               or coalesce(trim(value ->> 'content'), '') = ''
        ) then
            raise exception 'Every option requires a label and content'
                using errcode = '22023';
        end if;

        insert into public.pregunta(
            examen_id,
            titulo,
            enunciado,
            orden
        )
        values (
            p_exam_id,
            coalesce(question_item ->> 'title', ''),
            trim(question_item ->> 'statement'),
            coalesce((question_item ->> 'sortOrder')::integer, 0)
        )
        returning id into question_id;

        for option_item in
            select value from jsonb_array_elements(options)
        loop
            insert into public.opcion_pregunta(
                pregunta_id,
                etiqueta,
                contenido,
                es_correcta,
                orden
            )
            values (
                question_id,
                trim(option_item ->> 'label'),
                trim(option_item ->> 'content'),
                coalesce((option_item ->> 'correct')::boolean, false),
                coalesce((option_item ->> 'sortOrder')::integer, 0)
            );
        end loop;

        question_ids := array_append(question_ids, question_id);
    end loop;

    insert into public.audit_log(
        actor_auth_user_id,
        actor_usuario_id,
        action,
        resource,
        resource_id,
        metadata,
        idempotency_key
    )
    values (
        auth.uid(),
        actor_usuario_id,
        'exam.questions.import',
        'exam',
        p_exam_id::text,
        jsonb_build_object(
            'questionCount', question_count,
            'questionIds', to_jsonb(question_ids)
        ),
        p_idempotency_key
    )
    returning id into audit_id;

    result := jsonb_build_object(
        'examId', p_exam_id,
        'questionIds', to_jsonb(question_ids),
        'auditId', audit_id
    );
    perform private.complete_idempotency(
        'admin_import_exam_questions',
        p_idempotency_key,
        result
    );
    return result;
end;
$$;

create or replace function public.admin_reorder_exam_questions(
    p_exam_id integer,
    p_question_ids integer[],
    p_idempotency_key text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    actor_usuario_id integer;
    current_ids integer[];
    normalized_ids integer[];
    audit_id bigint;
    request_data jsonb;
    claim private.api_idempotency%rowtype;
    result jsonb;
begin
    actor_usuario_id := private.require_permission('content.write');

    perform 1
    from public.examen
    where id = p_exam_id
      and deleted_at is null
    for update;
    if not found then
        raise exception 'Exam not found' using errcode = 'P0002';
    end if;

    select coalesce(array_agg(id order by id), '{}')
    into current_ids
    from public.pregunta
    where examen_id = p_exam_id
      and deleted_at is null;

    select coalesce(array_agg(value order by value), '{}')
    into normalized_ids
    from unnest(coalesce(p_question_ids, '{}')) as supplied(value);

    if normalized_ids <> current_ids then
        raise exception 'Question order must contain every active question exactly once'
            using errcode = '22023';
    end if;

    request_data := jsonb_build_object(
        'examId', p_exam_id,
        'questionIds', to_jsonb(p_question_ids)
    );
    select *
    into claim
    from private.claim_idempotency(
        'admin_reorder_exam_questions',
        p_idempotency_key,
        request_data
    );
    if claim.response_body is not null then
        return claim.response_body;
    end if;

    update public.pregunta as question
    set orden = ordered.ordinality - 1
    from unnest(p_question_ids) with ordinality as ordered(id, ordinality)
    where question.id = ordered.id
      and question.examen_id = p_exam_id
      and question.deleted_at is null;

    insert into public.audit_log(
        actor_auth_user_id,
        actor_usuario_id,
        action,
        resource,
        resource_id,
        metadata,
        idempotency_key
    )
    values (
        auth.uid(),
        actor_usuario_id,
        'exam.questions.reorder',
        'exam',
        p_exam_id::text,
        jsonb_build_object('questionCount', cardinality(p_question_ids)),
        p_idempotency_key
    )
    returning id into audit_id;

    result := jsonb_build_object(
        'examId', p_exam_id,
        'questionIds', to_jsonb(p_question_ids),
        'auditId', audit_id
    );
    perform private.complete_idempotency(
        'admin_reorder_exam_questions',
        p_idempotency_key,
        result
    );
    return result;
end;
$$;

create or replace function public.admin_enqueue_storage_cleanup(
    p_bucket text,
    p_object_path text,
    p_reason text
)
returns bigint
language plpgsql
security definer
set search_path = ''
as $$
declare
    outbox_id bigint;
begin
    if coalesce(
        current_setting('request.jwt.claim.role', true),
        ''
    ) <> 'service_role' then
        raise exception 'Service role required' using errcode = '42501';
    end if;
    if p_bucket not in ('exam-covers', 'exam-resources') then
        raise exception 'Unsupported storage bucket' using errcode = '22023';
    end if;
    if coalesce(trim(p_object_path), '') = ''
        or length(p_object_path) > 1024
        or p_object_path like '%..%' then
        raise exception 'Invalid storage object path' using errcode = '22023';
    end if;
    if coalesce(trim(p_reason), '') = '' or length(p_reason) > 200 then
        raise exception 'Invalid cleanup reason' using errcode = '22023';
    end if;

    insert into private.storage_cleanup_outbox(
        bucket_id,
        object_path,
        reason,
        available_at,
        completed_at
    )
    values (
        p_bucket,
        p_object_path,
        trim(p_reason),
        now(),
        null
    )
    on conflict (bucket_id, object_path) do update
    set
        reason = excluded.reason,
        available_at = now(),
        completed_at = null
    returning id into outbox_id;

    return outbox_id;
end;
$$;

revoke all on function public.admin_import_exam_questions(
    integer, jsonb, text
) from public, anon;
revoke all on function public.admin_reorder_exam_questions(
    integer, integer[], text
) from public, anon;
revoke all on function public.admin_enqueue_storage_cleanup(
    text, text, text
) from public, anon, authenticated;

grant execute on function public.admin_import_exam_questions(
    integer, jsonb, text
) to authenticated;
grant execute on function public.admin_reorder_exam_questions(
    integer, integer[], text
) to authenticated;
grant execute on function public.admin_enqueue_storage_cleanup(
    text, text, text
) to service_role;

commit;
