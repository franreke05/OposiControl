-- Close the remaining multi-request write paths for exam questions and commerce.
-- NEWS=FROZEN: no news table, policy, trigger or function is changed here.

begin;

create or replace function private.validate_exact_question_options(
    supplied_options jsonb
)
returns void
language plpgsql
immutable
set search_path = ''
as $$
declare
    option_count integer;
    correct_count integer;
    distinct_label_count integer;
begin
    if coalesce(jsonb_typeof(supplied_options), 'null') <> 'array' then
        raise exception 'Question options must be a JSON array'
            using errcode = '22023';
    end if;

    select
        count(*),
        count(*) filter (
            where coalesce((value ->> 'correct')::boolean, false)
        ),
        count(distinct lower(trim(value ->> 'label')))
    into option_count, correct_count, distinct_label_count
    from jsonb_array_elements(supplied_options);

    if option_count <> 4
        or correct_count <> 1
        or distinct_label_count <> 4 then
        raise exception 'Every question requires four unique options and one correct answer'
            using errcode = '22023';
    end if;

    if exists (
        select 1
        from jsonb_array_elements(supplied_options)
        where coalesce(trim(value ->> 'label'), '') = ''
           or coalesce(trim(value ->> 'content'), '') = ''
    ) then
        raise exception 'Every option requires a label and content'
            using errcode = '22023';
    end if;
end;
$$;

create or replace function private.assert_question_option_invariant(
    checked_question_id integer
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    option_count integer;
    correct_count integer;
    distinct_label_count integer;
begin
    if checked_question_id is null or not exists (
        select 1
        from public.pregunta
        where id = checked_question_id
          and deleted_at is null
    ) then
        return;
    end if;

    select
        count(*),
        count(*) filter (where es_correcta),
        count(distinct lower(trim(etiqueta)))
    into option_count, correct_count, distinct_label_count
    from public.opcion_pregunta
    where pregunta_id = checked_question_id
      and deleted_at is null;

    if option_count <> 4
        or correct_count <> 1
        or distinct_label_count <> 4 then
        raise exception 'Every active question requires four unique options and one correct answer'
            using errcode = '23514';
    end if;
end;
$$;

create or replace function private.enforce_question_option_invariant()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    if tg_table_name = 'pregunta' then
        perform private.assert_question_option_invariant(
            case when tg_op = 'DELETE' then old.id else new.id end
        );
    else
        perform private.assert_question_option_invariant(
            case when tg_op = 'DELETE' then old.pregunta_id else new.pregunta_id end
        );
        if tg_op = 'UPDATE' and old.pregunta_id is distinct from new.pregunta_id then
            perform private.assert_question_option_invariant(old.pregunta_id);
        end if;
    end if;

    if tg_op = 'DELETE' then
        return old;
    end if;
    return new;
end;
$$;

drop trigger if exists enforce_question_options_on_question on public.pregunta;
create constraint trigger enforce_question_options_on_question
after insert or update of deleted_at on public.pregunta
deferrable initially deferred
for each row execute function private.enforce_question_option_invariant();

drop trigger if exists enforce_question_options_on_option on public.opcion_pregunta;
create constraint trigger enforce_question_options_on_option
after insert or update or delete on public.opcion_pregunta
deferrable initially deferred
for each row execute function private.enforce_question_option_invariant();

create or replace function private.guard_exam_publication()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    if (
        (tg_op = 'INSERT' and new.estado <> 'draft')
        or (
            tg_op = 'UPDATE'
            and new.estado is distinct from old.estado
        )
    )
        and not private.has_permission('content.publish') then
        raise exception 'Permission required: content.publish'
            using errcode = '42501';
    end if;

    if new.estado = 'published' and (
        not exists (
            select 1
            from public.pregunta
            where examen_id = new.id
              and deleted_at is null
        )
        or exists (
            select 1
            from public.pregunta question
            left join public.opcion_pregunta option
              on option.pregunta_id = question.id
             and option.deleted_at is null
            where question.examen_id = new.id
              and question.deleted_at is null
            group by question.id
            having count(option.id) <> 4
                or count(option.id) filter (where option.es_correcta) <> 1
                or count(distinct lower(trim(option.etiqueta))) <> 4
        )
    ) then
        raise exception 'A published exam requires valid questions and options'
            using errcode = '23514';
    end if;

    if new.estado = 'published'
        and (tg_op = 'INSERT' or old.estado is distinct from 'published') then
        new.published_at := coalesce(new.published_at, now());
    elsif new.estado <> 'published' then
        new.published_at := null;
    end if;
    return new;
end;
$$;

drop trigger if exists guard_exam_publication on public.examen;
create trigger guard_exam_publication
before insert or update of estado on public.examen
for each row execute function private.guard_exam_publication();

create or replace function public.admin_save_exam_question(
    p_exam_id integer,
    p_question_id integer,
    p_title text,
    p_statement text,
    p_sort_order integer,
    p_options jsonb,
    p_expected_version bigint,
    p_idempotency_key text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    actor_usuario_id integer;
    request_data jsonb;
    claim private.api_idempotency%rowtype;
    saved_question_id integer;
    saved_version bigint;
    option_item jsonb;
    audit_id bigint;
    result jsonb;
begin
    actor_usuario_id := private.require_permission('content.write');
    if coalesce(trim(p_statement), '') = '' then
        raise exception 'Question statement is required' using errcode = '22023';
    end if;
    perform private.validate_exact_question_options(p_options);

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
        'questionId', p_question_id,
        'title', coalesce(trim(p_title), ''),
        'statement', trim(p_statement),
        'sortOrder', p_sort_order,
        'options', p_options,
        'expectedVersion', p_expected_version
    );
    select *
    into claim
    from private.claim_idempotency(
        'admin_save_exam_question',
        p_idempotency_key,
        request_data
    );
    if claim.response_body is not null then
        return claim.response_body;
    end if;

    if p_question_id is null then
        insert into public.pregunta(
            examen_id,
            titulo,
            enunciado,
            orden
        )
        values (
            p_exam_id,
            coalesce(trim(p_title), ''),
            trim(p_statement),
            p_sort_order
        )
        returning id, version
        into saved_question_id, saved_version;
    else
        if p_expected_version is null then
            raise exception 'expectedVersion is required for updates'
                using errcode = '22023';
        end if;

        update public.pregunta
        set
            titulo = coalesce(trim(p_title), ''),
            enunciado = trim(p_statement),
            orden = p_sort_order
        where id = p_question_id
          and examen_id = p_exam_id
          and deleted_at is null
          and version = p_expected_version
        returning id, version
        into saved_question_id, saved_version;

        if saved_question_id is null then
            raise sqlstate 'PT409' using message = 'version_conflict';
        end if;

        delete from public.opcion_pregunta
        where pregunta_id = saved_question_id;
    end if;

    for option_item in
        select value from jsonb_array_elements(p_options)
    loop
        insert into public.opcion_pregunta(
            pregunta_id,
            etiqueta,
            contenido,
            es_correcta,
            orden
        )
        values (
            saved_question_id,
            trim(option_item ->> 'label'),
            trim(option_item ->> 'content'),
            (option_item ->> 'correct')::boolean,
            coalesce((option_item ->> 'sortOrder')::integer, 0)
        );
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
        case when p_question_id is null then
            'exam.question.create'
        else
            'exam.question.update'
        end,
        'question',
        saved_question_id::text,
        jsonb_build_object(
            'examId', p_exam_id,
            'optionCount', 4
        ),
        p_idempotency_key
    )
    returning id into audit_id;

    result := jsonb_build_object(
        'questionId', saved_question_id,
        'version', saved_version,
        'auditId', audit_id
    );
    perform private.complete_idempotency(
        'admin_save_exam_question',
        p_idempotency_key,
        result
    );
    return result;
end;
$$;

create or replace function public.admin_upsert_store_item(
    p_id integer,
    p_payload jsonb,
    p_expected_version bigint,
    p_idempotency_key text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    actor_usuario_id integer;
    claim private.api_idempotency%rowtype;
    request_data jsonb;
    resource_id integer;
    resource_version bigint;
    audit_id bigint;
    price_tickets bigint;
    result jsonb;
begin
    actor_usuario_id := private.require_permission('commerce.write');
    if coalesce(jsonb_typeof(p_payload), 'null') <> 'object'
        or coalesce(trim(p_payload ->> 'name'), '') = '' then
        raise exception 'A valid store item payload is required' using errcode = '22023';
    end if;
    price_tickets := (p_payload ->> 'priceTickets')::bigint;
    if price_tickets is null
        or price_tickets < 0
        or price_tickets > 2147483647 then
        raise exception 'Invalid ticket price' using errcode = '22023';
    end if;
    if coalesce(p_payload ->> 'type', '') not in (
        'consumable', 'protector', 'streak', 'potentiator', 'exam_access', 'resource'
    ) or coalesce(p_payload ->> 'status', '') not in (
        'draft', 'active', 'scheduled', 'expired', 'archived'
    ) then
        raise exception 'Invalid store item type or status' using errcode = '22023';
    end if;

    request_data := jsonb_build_object(
        'id', p_id,
        'payload', p_payload,
        'expectedVersion', p_expected_version
    );
    select * into claim
    from private.claim_idempotency(
        'admin_upsert_store_item',
        p_idempotency_key,
        request_data
    );
    if claim.response_body is not null then
        return claim.response_body;
    end if;

    if p_id is null then
        insert into public.articulo_tienda(
            nombre,
            descripcion,
            tipo_articulo,
            precio_tickets,
            estado,
            activo,
            orden
        )
        values (
            trim(p_payload ->> 'name'),
            coalesce(trim(p_payload ->> 'description'), ''),
            p_payload ->> 'type',
            price_tickets::integer,
            p_payload ->> 'status',
            p_payload ->> 'status' = 'active',
            coalesce((p_payload ->> 'sortOrder')::integer, 0)
        )
        returning id, version into resource_id, resource_version;
    else
        if p_expected_version is null then
            raise exception 'expectedVersion is required for updates'
                using errcode = '22023';
        end if;
        update public.articulo_tienda
        set
            nombre = trim(p_payload ->> 'name'),
            descripcion = coalesce(trim(p_payload ->> 'description'), ''),
            tipo_articulo = p_payload ->> 'type',
            precio_tickets = price_tickets::integer,
            estado = p_payload ->> 'status',
            activo = p_payload ->> 'status' = 'active',
            orden = coalesce((p_payload ->> 'sortOrder')::integer, 0)
        where id = p_id
          and deleted_at is null
          and version = p_expected_version
        returning id, version into resource_id, resource_version;
        if resource_id is null then
            raise sqlstate 'PT409' using message = 'version_conflict';
        end if;
    end if;

    insert into public.audit_log(
        actor_auth_user_id, actor_usuario_id, action, resource,
        resource_id, metadata, idempotency_key
    )
    values (
        auth.uid(), actor_usuario_id, 'commerce.item.upsert', 'store_item',
        resource_id::text,
        jsonb_build_object('status', p_payload ->> 'status'),
        p_idempotency_key
    )
    returning id into audit_id;

    result := jsonb_build_object(
        'resourceId', resource_id,
        'version', resource_version,
        'auditId', audit_id
    );
    perform private.complete_idempotency(
        'admin_upsert_store_item', p_idempotency_key, result
    );
    return result;
end;
$$;

create or replace function public.admin_upsert_store_offer(
    p_id integer,
    p_payload jsonb,
    p_expected_version bigint,
    p_idempotency_key text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    actor_usuario_id integer;
    claim private.api_idempotency%rowtype;
    request_data jsonb;
    resource_id integer;
    resource_version bigint;
    audit_id bigint;
    v_discount_percent integer;
    v_special_price bigint;
    result jsonb;
begin
    actor_usuario_id := private.require_permission('commerce.write');
    if coalesce(jsonb_typeof(p_payload), 'null') <> 'object'
        or coalesce(trim(p_payload ->> 'title'), '') = ''
        or coalesce((p_payload ->> 'itemId')::integer, 0) <= 0 then
        raise exception 'A valid store offer payload is required' using errcode = '22023';
    end if;
    v_discount_percent := (p_payload ->> 'discountPercent')::integer;
    v_special_price := (p_payload ->> 'specialPriceTickets')::bigint;
    if v_discount_percent is null and v_special_price is null then
        raise exception 'Discount or special price is required' using errcode = '22023';
    end if;
    if v_discount_percent is not null and v_discount_percent not between 1 and 100 then
        raise exception 'Invalid discount' using errcode = '22023';
    end if;
    if v_special_price is not null
        and (v_special_price < 0 or v_special_price > 2147483647) then
        raise exception 'Invalid special price' using errcode = '22023';
    end if;
    if coalesce(p_payload ->> 'status', '') not in (
        'draft', 'active', 'scheduled', 'expired', 'archived'
    ) then
        raise exception 'Invalid commerce status' using errcode = '22023';
    end if;

    request_data := jsonb_build_object(
        'id', p_id,
        'payload', p_payload,
        'expectedVersion', p_expected_version
    );
    select * into claim
    from private.claim_idempotency(
        'admin_upsert_store_offer',
        p_idempotency_key,
        request_data
    );
    if claim.response_body is not null then
        return claim.response_body;
    end if;

    if p_id is null then
        insert into public.oferta_tienda(
            articulo_tienda_id,
            titulo,
            discount_percent,
            special_price_tickets,
            starts_at,
            ends_at,
            status
        )
        values (
            (p_payload ->> 'itemId')::integer,
            trim(p_payload ->> 'title'),
            v_discount_percent,
            v_special_price::integer,
            nullif(p_payload ->> 'startsAt', '')::timestamptz,
            nullif(p_payload ->> 'endsAt', '')::timestamptz,
            p_payload ->> 'status'
        )
        returning id, version into resource_id, resource_version;
    else
        if p_expected_version is null then
            raise exception 'expectedVersion is required for updates'
                using errcode = '22023';
        end if;
        update public.oferta_tienda
        set
            articulo_tienda_id = (p_payload ->> 'itemId')::integer,
            titulo = trim(p_payload ->> 'title'),
            discount_percent = v_discount_percent,
            special_price_tickets = v_special_price::integer,
            starts_at = nullif(p_payload ->> 'startsAt', '')::timestamptz,
            ends_at = nullif(p_payload ->> 'endsAt', '')::timestamptz,
            status = p_payload ->> 'status'
        where id = p_id
          and deleted_at is null
          and version = p_expected_version
        returning id, version into resource_id, resource_version;
        if resource_id is null then
            raise sqlstate 'PT409' using message = 'version_conflict';
        end if;
    end if;

    insert into public.audit_log(
        actor_auth_user_id, actor_usuario_id, action, resource,
        resource_id, metadata, idempotency_key
    )
    values (
        auth.uid(), actor_usuario_id, 'commerce.offer.upsert', 'store_offer',
        resource_id::text,
        jsonb_build_object('status', p_payload ->> 'status'),
        p_idempotency_key
    )
    returning id into audit_id;

    result := jsonb_build_object(
        'resourceId', resource_id,
        'version', resource_version,
        'auditId', audit_id
    );
    perform private.complete_idempotency(
        'admin_upsert_store_offer', p_idempotency_key, result
    );
    return result;
end;
$$;

create or replace function public.admin_upsert_limited_resource(
    p_id integer,
    p_payload jsonb,
    p_expected_version bigint,
    p_idempotency_key text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    actor_usuario_id integer;
    claim private.api_idempotency%rowtype;
    request_data jsonb;
    resource_id integer;
    resource_version bigint;
    audit_id bigint;
    result jsonb;
begin
    actor_usuario_id := private.require_permission('commerce.write');
    if coalesce(jsonb_typeof(p_payload), 'null') <> 'object'
        or coalesce(trim(p_payload ->> 'title'), '') = ''
        or coalesce((p_payload ->> 'itemId')::integer, 0) <= 0
        or coalesce((p_payload ->> 'resourceId')::integer, 0) <= 0
        or coalesce((p_payload ->> 'accessDurationHours')::integer, 0) <= 0 then
        raise exception 'A valid limited resource payload is required'
            using errcode = '22023';
    end if;
    if coalesce(p_payload ->> 'status', '') not in (
        'draft', 'active', 'scheduled', 'expired', 'archived'
    ) then
        raise exception 'Invalid commerce status' using errcode = '22023';
    end if;

    request_data := jsonb_build_object(
        'id', p_id,
        'payload', p_payload,
        'expectedVersion', p_expected_version
    );
    select * into claim
    from private.claim_idempotency(
        'admin_upsert_limited_resource',
        p_idempotency_key,
        request_data
    );
    if claim.response_body is not null then
        return claim.response_body;
    end if;

    if p_id is null then
        insert into public.recurso_temporal_tienda(
            articulo_tienda_id,
            recurso_id,
            titulo,
            starts_at,
            ends_at,
            access_duration_hours,
            status
        )
        values (
            (p_payload ->> 'itemId')::integer,
            (p_payload ->> 'resourceId')::integer,
            trim(p_payload ->> 'title'),
            nullif(p_payload ->> 'startsAt', '')::timestamptz,
            nullif(p_payload ->> 'endsAt', '')::timestamptz,
            (p_payload ->> 'accessDurationHours')::integer,
            p_payload ->> 'status'
        )
        returning id, version into resource_id, resource_version;
    else
        if p_expected_version is null then
            raise exception 'expectedVersion is required for updates'
                using errcode = '22023';
        end if;
        update public.recurso_temporal_tienda
        set
            articulo_tienda_id = (p_payload ->> 'itemId')::integer,
            recurso_id = (p_payload ->> 'resourceId')::integer,
            titulo = trim(p_payload ->> 'title'),
            starts_at = nullif(p_payload ->> 'startsAt', '')::timestamptz,
            ends_at = nullif(p_payload ->> 'endsAt', '')::timestamptz,
            access_duration_hours = (p_payload ->> 'accessDurationHours')::integer,
            status = p_payload ->> 'status'
        where id = p_id
          and deleted_at is null
          and version = p_expected_version
        returning id, version into resource_id, resource_version;
        if resource_id is null then
            raise sqlstate 'PT409' using message = 'version_conflict';
        end if;
    end if;

    insert into public.audit_log(
        actor_auth_user_id, actor_usuario_id, action, resource,
        resource_id, metadata, idempotency_key
    )
    values (
        auth.uid(), actor_usuario_id, 'commerce.limited_resource.upsert',
        'limited_resource', resource_id::text,
        jsonb_build_object('status', p_payload ->> 'status'),
        p_idempotency_key
    )
    returning id into audit_id;

    result := jsonb_build_object(
        'resourceId', resource_id,
        'version', resource_version,
        'auditId', audit_id
    );
    perform private.complete_idempotency(
        'admin_upsert_limited_resource', p_idempotency_key, result
    );
    return result;
end;
$$;

create or replace function public.admin_upsert_burst_campaign(
    p_id integer,
    p_payload jsonb,
    p_expected_version bigint,
    p_idempotency_key text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    actor_usuario_id integer;
    claim private.api_idempotency%rowtype;
    request_data jsonb;
    resource_id integer;
    resource_version bigint;
    audit_id bigint;
    multiplier_value numeric;
    v_reward_tickets bigint;
    result jsonb;
begin
    actor_usuario_id := private.require_permission('commerce.write');
    multiplier_value := (p_payload ->> 'multiplier')::numeric;
    v_reward_tickets := (p_payload ->> 'rewardTickets')::bigint;
    if coalesce(jsonb_typeof(p_payload), 'null') <> 'object'
        or coalesce(trim(p_payload ->> 'title'), '') = ''
        or multiplier_value is null
        or multiplier_value < 1
        or v_reward_tickets is null
        or v_reward_tickets < 0
        or v_reward_tickets > 2147483647 then
        raise exception 'A valid burst campaign payload is required'
            using errcode = '22023';
    end if;
    if coalesce(p_payload ->> 'type', '') not in (
        'daily_streak', 'exam_burst', 'ticket_multiplier', 'resource_unlock'
    ) or coalesce(p_payload ->> 'status', '') not in (
        'draft', 'active', 'scheduled', 'expired', 'archived'
    ) then
        raise exception 'Invalid campaign type or status' using errcode = '22023';
    end if;

    request_data := jsonb_build_object(
        'id', p_id,
        'payload', p_payload,
        'expectedVersion', p_expected_version
    );
    select * into claim
    from private.claim_idempotency(
        'admin_upsert_burst_campaign',
        p_idempotency_key,
        request_data
    );
    if claim.response_body is not null then
        return claim.response_body;
    end if;

    if p_id is null then
        insert into public.campana_rafaga(
            titulo,
            descripcion,
            tipo,
            starts_at,
            ends_at,
            multiplier,
            reward_tickets,
            linked_exam_id,
            linked_store_item_id,
            status
        )
        values (
            trim(p_payload ->> 'title'),
            coalesce(trim(p_payload ->> 'description'), ''),
            p_payload ->> 'type',
            nullif(p_payload ->> 'startsAt', '')::timestamptz,
            nullif(p_payload ->> 'endsAt', '')::timestamptz,
            multiplier_value,
            v_reward_tickets::integer,
            (p_payload ->> 'linkedExamId')::integer,
            (p_payload ->> 'linkedStoreItemId')::integer,
            p_payload ->> 'status'
        )
        returning id, version into resource_id, resource_version;
    else
        if p_expected_version is null then
            raise exception 'expectedVersion is required for updates'
                using errcode = '22023';
        end if;
        update public.campana_rafaga
        set
            titulo = trim(p_payload ->> 'title'),
            descripcion = coalesce(trim(p_payload ->> 'description'), ''),
            tipo = p_payload ->> 'type',
            starts_at = nullif(p_payload ->> 'startsAt', '')::timestamptz,
            ends_at = nullif(p_payload ->> 'endsAt', '')::timestamptz,
            multiplier = multiplier_value,
            reward_tickets = v_reward_tickets::integer,
            linked_exam_id = (p_payload ->> 'linkedExamId')::integer,
            linked_store_item_id = (p_payload ->> 'linkedStoreItemId')::integer,
            status = p_payload ->> 'status'
        where id = p_id
          and deleted_at is null
          and version = p_expected_version
        returning id, version into resource_id, resource_version;
        if resource_id is null then
            raise sqlstate 'PT409' using message = 'version_conflict';
        end if;
    end if;

    insert into public.audit_log(
        actor_auth_user_id, actor_usuario_id, action, resource,
        resource_id, metadata, idempotency_key
    )
    values (
        auth.uid(), actor_usuario_id, 'commerce.burst_campaign.upsert',
        'burst_campaign', resource_id::text,
        jsonb_build_object('status', p_payload ->> 'status'),
        p_idempotency_key
    )
    returning id into audit_id;

    result := jsonb_build_object(
        'resourceId', resource_id,
        'version', resource_version,
        'auditId', audit_id
    );
    perform private.complete_idempotency(
        'admin_upsert_burst_campaign', p_idempotency_key, result
    );
    return result;
end;
$$;

create or replace function public.admin_soft_delete_content(
    p_resource_type text,
    p_resource_id integer,
    p_expected_version bigint,
    p_idempotency_key text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    actor_usuario_id integer;
    claim private.api_idempotency%rowtype;
    request_data jsonb;
    current_version bigint;
    parent_exam_id integer;
    parent_exam_status text;
    cover_path text;
    affected_questions integer := 0;
    affected_resources integer := 0;
    audit_id bigint;
    cleanup_path text;
    result jsonb;
begin
    actor_usuario_id := private.require_permission('content.write');
    if coalesce(p_resource_type, '') not in ('specialty', 'exam', 'question', 'resource')
        or p_resource_id is null
        or p_resource_id <= 0
        or p_expected_version is null then
        raise exception 'A valid resource type, id and expectedVersion are required'
            using errcode = '22023';
    end if;

    request_data := jsonb_build_object(
        'resourceType', p_resource_type,
        'resourceId', p_resource_id,
        'expectedVersion', p_expected_version
    );
    select * into claim
    from private.claim_idempotency(
        'admin_soft_delete_content',
        p_idempotency_key,
        request_data
    );
    if claim.response_body is not null then
        return claim.response_body;
    end if;

    if p_resource_type = 'specialty' then
        select version into current_version
        from public.especialidad
        where id = p_resource_id
          and deleted_at is null
        for update;
        if current_version is null or current_version <> p_expected_version then
            raise sqlstate 'PT409' using message = 'version_conflict';
        end if;
        if exists (
            select 1
            from public.examen
            where especialidad_id = p_resource_id
              and deleted_at is null
        ) then
            raise exception 'Specialty still has active exams'
                using errcode = '23503';
        end if;
        update public.especialidad
        set deleted_at = now(), activa = false
        where id = p_resource_id;

    elsif p_resource_type = 'exam' then
        select version, portada_storage_path
        into current_version, cover_path
        from public.examen
        where id = p_resource_id
          and deleted_at is null
        for update;
        if current_version is null or current_version <> p_expected_version then
            raise sqlstate 'PT409' using message = 'version_conflict';
        end if;

        insert into private.storage_cleanup_outbox(
            bucket_id, object_path, reason
        )
        select
            'exam-resources',
            resource.storage_path,
            'exam_soft_delete'
        from public.recurso_pregunta resource
        join public.pregunta question
          on question.id = resource.pregunta_id
        where question.examen_id = p_resource_id
          and question.deleted_at is null
          and resource.deleted_at is null
          and nullif(trim(resource.storage_path), '') is not null
        on conflict (bucket_id, object_path) do update
        set
            reason = excluded.reason,
            available_at = now(),
            completed_at = null;

        if nullif(trim(cover_path), '') is not null then
            insert into private.storage_cleanup_outbox(
                bucket_id, object_path, reason
            )
            values ('exam-covers', cover_path, 'exam_soft_delete')
            on conflict (bucket_id, object_path) do update
            set
                reason = excluded.reason,
                available_at = now(),
                completed_at = null;
        end if;

        select count(*) into affected_questions
        from public.pregunta
        where examen_id = p_resource_id
          and deleted_at is null;
        select count(*) into affected_resources
        from public.recurso_pregunta resource
        join public.pregunta question
          on question.id = resource.pregunta_id
        where question.examen_id = p_resource_id
          and question.deleted_at is null
          and resource.deleted_at is null;

        update public.recurso_pregunta
        set deleted_at = now()
        where pregunta_id in (
            select id from public.pregunta
            where examen_id = p_resource_id
              and deleted_at is null
        )
          and deleted_at is null;
        update public.opcion_pregunta
        set deleted_at = now()
        where pregunta_id in (
            select id from public.pregunta
            where examen_id = p_resource_id
              and deleted_at is null
        )
          and deleted_at is null;
        update public.pregunta
        set deleted_at = now()
        where examen_id = p_resource_id
          and deleted_at is null;
        update public.acceso_examen_usuario
        set activo = false
        where examen_id = p_resource_id
          and activo;
        update public.examen
        set
            deleted_at = now(),
            portada_url = null,
            portada_storage_path = null
        where id = p_resource_id;

    elsif p_resource_type = 'question' then
        select question.version, question.examen_id, exam.estado
        into current_version, parent_exam_id, parent_exam_status
        from public.pregunta question
        join public.examen exam
          on exam.id = question.examen_id
        where question.id = p_resource_id
          and question.deleted_at is null
          and exam.deleted_at is null
        for update of question, exam;
        if current_version is null or current_version <> p_expected_version then
            raise sqlstate 'PT409' using message = 'version_conflict';
        end if;
        if parent_exam_status = 'published' then
            raise exception 'Unpublish the exam before deleting one of its questions'
                using errcode = '23514';
        end if;

        insert into private.storage_cleanup_outbox(
            bucket_id, object_path, reason
        )
        select
            'exam-resources',
            storage_path,
            'question_soft_delete'
        from public.recurso_pregunta
        where pregunta_id = p_resource_id
          and deleted_at is null
          and nullif(trim(storage_path), '') is not null
        on conflict (bucket_id, object_path) do update
        set
            reason = excluded.reason,
            available_at = now(),
            completed_at = null;

        select count(*) into affected_resources
        from public.recurso_pregunta
        where pregunta_id = p_resource_id
          and deleted_at is null;
        update public.recurso_pregunta
        set deleted_at = now()
        where pregunta_id = p_resource_id
          and deleted_at is null;
        update public.opcion_pregunta
        set deleted_at = now()
        where pregunta_id = p_resource_id
          and deleted_at is null;
        update public.pregunta
        set deleted_at = now()
        where id = p_resource_id;
        affected_questions := 1;

    else
        select version, storage_path
        into current_version, cleanup_path
        from public.recurso_pregunta
        where id = p_resource_id
          and deleted_at is null
        for update;
        if current_version is null or current_version <> p_expected_version then
            raise sqlstate 'PT409' using message = 'version_conflict';
        end if;
        if nullif(trim(cleanup_path), '') is not null then
            insert into private.storage_cleanup_outbox(
                bucket_id, object_path, reason
            )
            values ('exam-resources', cleanup_path, 'resource_soft_delete')
            on conflict (bucket_id, object_path) do update
            set
                reason = excluded.reason,
                available_at = now(),
                completed_at = null;
        end if;
        update public.recurso_pregunta
        set deleted_at = now()
        where id = p_resource_id;
        affected_resources := 1;
    end if;

    insert into public.audit_log(
        actor_auth_user_id, actor_usuario_id, action, resource,
        resource_id, metadata, idempotency_key
    )
    values (
        auth.uid(),
        actor_usuario_id,
        'content.' || p_resource_type || '.delete',
        p_resource_type,
        p_resource_id::text,
        jsonb_build_object(
            'questionCount', affected_questions,
            'resourceCount', affected_resources
        ),
        p_idempotency_key
    )
    returning id into audit_id;

    result := jsonb_build_object(
        'resourceType', p_resource_type,
        'resourceId', p_resource_id,
        'questionCount', affected_questions,
        'resourceCount', affected_resources,
        'auditId', audit_id
    );
    perform private.complete_idempotency(
        'admin_soft_delete_content', p_idempotency_key, result
    );
    return result;
end;
$$;

revoke all on function private.validate_exact_question_options(jsonb) from public;
revoke all on function private.assert_question_option_invariant(integer) from public;
revoke all on function private.enforce_question_option_invariant() from public;
revoke all on function private.guard_exam_publication() from public;

revoke all on function public.admin_save_exam_question(
    integer, integer, text, text, integer, jsonb, bigint, text
) from public, anon;
revoke all on function public.admin_upsert_store_item(
    integer, jsonb, bigint, text
) from public, anon;
revoke all on function public.admin_upsert_store_offer(
    integer, jsonb, bigint, text
) from public, anon;
revoke all on function public.admin_upsert_limited_resource(
    integer, jsonb, bigint, text
) from public, anon;
revoke all on function public.admin_upsert_burst_campaign(
    integer, jsonb, bigint, text
) from public, anon;
revoke all on function public.admin_soft_delete_content(
    text, integer, bigint, text
) from public, anon;

grant execute on function public.admin_save_exam_question(
    integer, integer, text, text, integer, jsonb, bigint, text
) to authenticated;
grant execute on function public.admin_upsert_store_item(
    integer, jsonb, bigint, text
) to authenticated;
grant execute on function public.admin_upsert_store_offer(
    integer, jsonb, bigint, text
) to authenticated;
grant execute on function public.admin_upsert_limited_resource(
    integer, jsonb, bigint, text
) to authenticated;
grant execute on function public.admin_upsert_burst_campaign(
    integer, jsonb, bigint, text
) to authenticated;
grant execute on function public.admin_soft_delete_content(
    text, integer, bigint, text
) to authenticated;

-- Commerce mutations are now available only through the audited RPCs above.
revoke insert, update, delete on table public.articulo_tienda from authenticated;
revoke insert, update, delete on table public.oferta_tienda from authenticated;
revoke insert, update, delete on table public.recurso_temporal_tienda from authenticated;
revoke insert, update, delete on table public.campana_rafaga from authenticated;

-- Hard deletes are disabled for canonical content. Ktor calls
-- admin_soft_delete_content with an optimistic version instead.
revoke delete on table public.especialidad from authenticated;
revoke delete on table public.examen from authenticated;
revoke delete on table public.pregunta from authenticated;
revoke delete on table public.opcion_pregunta from authenticated;
revoke delete on table public.recurso_pregunta from authenticated;

commit;
