-- Transactional commands for operations that must never be split across HTTP calls.
-- Every actor is derived from auth.uid(); client-supplied actor fields are absent.

begin;

create or replace function private.require_permission(required_permission text)
returns integer
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    actor_usuario_id integer;
begin
    if auth.uid() is null then
        raise exception 'Authentication required' using errcode = '28000';
    end if;
    if not private.has_permission(required_permission) then
        raise exception 'Permission required: %', required_permission
            using errcode = '42501';
    end if;

    actor_usuario_id := private.current_usuario_id();
    if actor_usuario_id is null then
        raise exception 'Authenticated user has no active usuario profile'
            using errcode = '28000';
    end if;
    return actor_usuario_id;
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
    request_fingerprint text := md5(request_body::text);
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
        request_hash
    )
    values (
        actor_id,
        operation_name,
        supplied_key,
        request_fingerprint
    )
    on conflict (actor_auth_user_id, operation, idempotency_key) do nothing;

    select *
    into claim
    from private.api_idempotency
    where actor_auth_user_id = actor_id
      and operation = operation_name
      and idempotency_key = supplied_key
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
    set response_body = complete_idempotency.response_body
    where actor_auth_user_id = auth.uid()
      and operation = operation_name
      and idempotency_key = supplied_key
$$;

revoke all on function private.require_permission(text) from public;
revoke all on function private.claim_idempotency(text, text, jsonb) from public;
revoke all on function private.complete_idempotency(text, text, jsonb) from public;

create or replace function public.admin_adjust_tickets(
    p_user_id integer,
    p_delta bigint,
    p_reason text,
    p_reference_type text,
    p_reference_id integer,
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
    new_balance numeric;
    wallet_version bigint;
    wallet_updated_at timestamptz;
    transaction_id integer;
    transaction_created_at timestamptz;
    audit_id bigint;
    result jsonb;
begin
    actor_usuario_id := private.require_permission('tickets.adjust');
    if p_delta = 0 or abs(p_delta) > 1000000000 then
        raise exception 'Ticket delta must be non-zero and within the allowed range'
            using errcode = '22023';
    end if;
    if p_reason is null or length(trim(p_reason)) < 3 then
        raise exception 'A reason is required' using errcode = '22023';
    end if;

    request_data := jsonb_build_object(
        'userId', p_user_id,
        'delta', p_delta,
        'reason', trim(p_reason),
        'referenceType', p_reference_type,
        'referenceId', p_reference_id
    );
    select *
    into claim
    from private.claim_idempotency(
        'admin_adjust_tickets',
        p_idempotency_key,
        request_data
    );
    if claim.response_body is not null then
        return claim.response_body;
    end if;

    if not exists (
        select 1 from public.usuario
        where id = p_user_id and activo and deleted_at is null
    ) then
        raise exception 'Active user not found' using errcode = 'P0002';
    end if;

    insert into public.cartera_tickets(usuario_id, saldo)
    values (p_user_id, 0)
    on conflict (usuario_id) do nothing;

    perform 1
    from public.cartera_tickets
    where usuario_id = p_user_id
    for update;

    update public.cartera_tickets
    set saldo = saldo + p_delta
    where usuario_id = p_user_id
      and saldo + p_delta >= 0
    returning saldo, version, updated_at
    into new_balance, wallet_version, wallet_updated_at;

    if not found then
        raise exception 'Ticket balance cannot become negative'
            using errcode = '23514';
    end if;

    insert into public.transaccion_ticket(
        usuario_id,
        tipo,
        monto,
        razon,
        referencia_tipo,
        referencia_id,
        idempotency_key
    )
    values (
        p_user_id,
        case when p_delta > 0 then 'credito' else 'debito' end,
        abs(p_delta),
        trim(p_reason),
        nullif(trim(p_reference_type), ''),
        p_reference_id,
        p_idempotency_key
    )
    returning id, created_at
    into transaction_id, transaction_created_at;

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
        'tickets.adjust',
        'ticket_wallet',
        p_user_id::text,
        request_data || jsonb_build_object('newBalance', new_balance),
        p_idempotency_key
    )
    returning id into audit_id;

    result := jsonb_build_object(
        'wallet', jsonb_build_object(
            'userId', p_user_id,
            'balance', new_balance::bigint,
            'version', wallet_version,
            'updatedAt', wallet_updated_at
        ),
        'transaction', jsonb_build_object(
            'id', transaction_id,
            'userId', p_user_id,
            'type', case when p_delta > 0 then 'credito' else 'debito' end,
            'amount', abs(p_delta),
            'reason', trim(p_reason),
            'referenceType', nullif(trim(p_reference_type), ''),
            'referenceId', p_reference_id,
            'occurredAt', transaction_created_at
        ),
        'auditId', audit_id
    );
    perform private.complete_idempotency(
        'admin_adjust_tickets',
        p_idempotency_key,
        result
    );
    return result;
end;
$$;

create or replace function public.admin_set_exam_access(
    p_user_id integer,
    p_exam_id integer,
    p_access_type text,
    p_active boolean,
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
    access_id integer;
    access_version bigint;
    access_updated_at timestamptz;
    audit_id bigint;
    result jsonb;
begin
    actor_usuario_id := private.require_permission('exam_access.write');
    if p_access_type is null or trim(p_access_type) = '' then
        raise exception 'Access type is required' using errcode = '22023';
    end if;

    request_data := jsonb_build_object(
        'userId', p_user_id,
        'examId', p_exam_id,
        'accessType', trim(p_access_type),
        'active', p_active
    );
    select *
    into claim
    from private.claim_idempotency(
        'admin_set_exam_access',
        p_idempotency_key,
        request_data
    );
    if claim.response_body is not null then
        return claim.response_body;
    end if;

    if not exists (
        select 1 from public.usuario
        where id = p_user_id and activo and deleted_at is null
    ) then
        raise exception 'Active user not found' using errcode = 'P0002';
    end if;
    if not exists (
        select 1 from public.examen
        where id = p_exam_id and deleted_at is null
    ) then
        raise exception 'Exam not found' using errcode = 'P0002';
    end if;

    select id
    into access_id
    from public.acceso_examen_usuario
    where usuario_id = p_user_id
      and examen_id = p_exam_id
    order by id
    limit 1
    for update;

    if access_id is null then
        insert into public.acceso_examen_usuario(
            usuario_id,
            examen_id,
            tipo_acceso,
            activo,
            granted_by
        )
        values (
            p_user_id,
            p_exam_id,
            trim(p_access_type),
            p_active,
            auth.uid()
        )
        returning id, version, updated_at
        into access_id, access_version, access_updated_at;
    else
        update public.acceso_examen_usuario
        set
            tipo_acceso = trim(p_access_type),
            activo = p_active,
            granted_by = auth.uid()
        where usuario_id = p_user_id
          and examen_id = p_exam_id;

        select version, updated_at
        into access_version, access_updated_at
        from public.acceso_examen_usuario
        where id = access_id;
    end if;

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
        'exam_access.set',
        'exam_access',
        access_id::text,
        request_data,
        p_idempotency_key
    )
    returning id into audit_id;

    result := jsonb_build_object(
        'id', access_id,
        'userId', p_user_id,
        'examId', p_exam_id,
        'accessType', trim(p_access_type),
        'active', p_active,
        'version', access_version,
        'updatedAt', access_updated_at,
        'auditId', audit_id
    );
    perform private.complete_idempotency(
        'admin_set_exam_access',
        p_idempotency_key,
        result
    );
    return result;
end;
$$;

create or replace function public.admin_replace_user_roles(
    p_user_id integer,
    p_role_ids integer[],
    p_idempotency_key text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    actor_usuario_id integer;
    normalized_role_ids integer[];
    request_data jsonb;
    claim private.api_idempotency%rowtype;
    audit_id bigint;
    result jsonb;
begin
    actor_usuario_id := private.require_permission('roles.write');
    select coalesce(array_agg(distinct supplied.role_id order by supplied.role_id), '{}')
    into normalized_role_ids
    from unnest(coalesce(p_role_ids, '{}')) as supplied(role_id);

    if exists (
        select requested.role_id
        from unnest(normalized_role_ids) as requested(role_id)
        left join public.app_role
          on app_role.id = requested.role_id
         and app_role.deleted_at is null
        where app_role.id is null
    ) then
        raise exception 'One or more roles do not exist' using errcode = '22023';
    end if;
    if not exists (
        select 1 from public.usuario
        where id = p_user_id and deleted_at is null
    ) then
        raise exception 'User not found' using errcode = 'P0002';
    end if;

    request_data := jsonb_build_object(
        'userId', p_user_id,
        'roleIds', to_jsonb(normalized_role_ids)
    );
    select *
    into claim
    from private.claim_idempotency(
        'admin_replace_user_roles',
        p_idempotency_key,
        request_data
    );
    if claim.response_body is not null then
        return claim.response_body;
    end if;

    perform 1
    from public.usuario
    where id = p_user_id
    for update;

    delete from public.user_role
    where usuario_id = p_user_id;

    insert into public.user_role(usuario_id, role_id)
    select p_user_id, requested.role_id
    from unnest(normalized_role_ids) as requested(role_id);

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
        'user.roles.replace',
        'user',
        p_user_id::text,
        request_data,
        p_idempotency_key
    )
    returning id into audit_id;

    result := jsonb_build_object(
        'userId', p_user_id,
        'roleIds', to_jsonb(normalized_role_ids),
        'auditId', audit_id
    );
    perform private.complete_idempotency(
        'admin_replace_user_roles',
        p_idempotency_key,
        result
    );
    return result;
end;
$$;

create or replace function public.admin_send_support_message(
    p_conversation_id integer,
    p_body text,
    p_attachment_path text,
    p_internal_note boolean,
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
    message_id integer;
    message_created_at timestamptz;
    audit_id bigint;
    result jsonb;
begin
    actor_usuario_id := private.require_permission('support.reply');
    if p_internal_note and not private.has_permission('support.internal_note') then
        raise exception 'Permission required: support.internal_note'
            using errcode = '42501';
    end if;
    if coalesce(trim(p_body), '') = ''
        and coalesce(trim(p_attachment_path), '') = '' then
        raise exception 'A message body or attachment is required'
            using errcode = '22023';
    end if;

    request_data := jsonb_build_object(
        'conversationId', p_conversation_id,
        'body', coalesce(p_body, ''),
        'attachmentPath', nullif(trim(p_attachment_path), ''),
        'internalNote', p_internal_note
    );
    select *
    into claim
    from private.claim_idempotency(
        'admin_send_support_message',
        p_idempotency_key,
        request_data
    );
    if claim.response_body is not null then
        return claim.response_body;
    end if;

    perform 1
    from public.support_conversation
    where id = p_conversation_id
    for update;
    if not found then
        raise exception 'Support conversation not found' using errcode = 'P0002';
    end if;

    insert into public.support_message(
        conversation_id,
        sender_usuario_id,
        body,
        attachment_path,
        internal_note
    )
    values (
        p_conversation_id,
        actor_usuario_id,
        nullif(trim(p_body), ''),
        nullif(trim(p_attachment_path), ''),
        p_internal_note
    )
    returning id, created_at
    into message_id, message_created_at;

    update public.support_conversation
    set updated_at = now()
    where id = p_conversation_id;

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
        case when p_internal_note then 'support.internal_note' else 'support.reply' end,
        'support_conversation',
        p_conversation_id::text,
        jsonb_build_object(
            'messageId', message_id,
            'hasAttachment', nullif(trim(p_attachment_path), '') is not null
        ),
        p_idempotency_key
    )
    returning id into audit_id;

    result := jsonb_build_object(
        'id', message_id,
        'conversationId', p_conversation_id,
        'senderUserId', actor_usuario_id,
        'body', coalesce(p_body, ''),
        'attachmentPath', nullif(trim(p_attachment_path), ''),
        'internalNote', p_internal_note,
        'createdAt', message_created_at,
        'auditId', audit_id
    );
    perform private.complete_idempotency(
        'admin_send_support_message',
        p_idempotency_key,
        result
    );
    return result;
end;
$$;

create or replace function public.admin_create_moderation_action(
    p_target_user_id integer,
    p_conversation_id integer,
    p_action_type text,
    p_reason text,
    p_metadata jsonb,
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
    action_id integer;
    action_created_at timestamptz;
    audit_id bigint;
    result jsonb;
begin
    actor_usuario_id := private.require_permission('moderation.write');
    if p_action_type not in (
        'warning', 'mute', 'close_conversation', 'ban', 'internal_note'
    ) then
        raise exception 'Unsupported moderation action type'
            using errcode = '22023';
    end if;
    if p_reason is null or length(trim(p_reason)) < 3 then
        raise exception 'A moderation reason is required'
            using errcode = '22023';
    end if;

    request_data := jsonb_build_object(
        'targetUserId', p_target_user_id,
        'conversationId', p_conversation_id,
        'type', p_action_type,
        'reason', trim(p_reason),
        'metadata', coalesce(p_metadata, '{}'::jsonb)
    );
    select *
    into claim
    from private.claim_idempotency(
        'admin_create_moderation_action',
        p_idempotency_key,
        request_data
    );
    if claim.response_body is not null then
        return claim.response_body;
    end if;

    insert into public.moderation_action(
        target_usuario_id,
        actor_usuario_id,
        conversation_id,
        action_type,
        reason,
        metadata
    )
    values (
        p_target_user_id,
        actor_usuario_id,
        p_conversation_id,
        p_action_type,
        trim(p_reason),
        coalesce(p_metadata, '{}'::jsonb)
    )
    returning id, created_at
    into action_id, action_created_at;

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
        'moderation.' || p_action_type,
        'user',
        p_target_user_id::text,
        request_data || jsonb_build_object('moderationActionId', action_id),
        p_idempotency_key
    )
    returning id into audit_id;

    result := jsonb_build_object(
        'id', action_id,
        'targetUserId', p_target_user_id,
        'actorUserId', actor_usuario_id,
        'conversationId', p_conversation_id,
        'type', p_action_type,
        'reason', trim(p_reason),
        'metadata', coalesce(p_metadata, '{}'::jsonb),
        'createdAt', action_created_at,
        'auditId', audit_id
    );
    perform private.complete_idempotency(
        'admin_create_moderation_action',
        p_idempotency_key,
        result
    );
    return result;
end;
$$;

revoke all on function public.admin_adjust_tickets(
    integer, bigint, text, text, integer, text
) from public, anon;
revoke all on function public.admin_set_exam_access(
    integer, integer, text, boolean, text
) from public, anon;
revoke all on function public.admin_replace_user_roles(
    integer, integer[], text
) from public, anon;
revoke all on function public.admin_send_support_message(
    integer, text, text, boolean, text
) from public, anon;
revoke all on function public.admin_create_moderation_action(
    integer, integer, text, text, jsonb, text
) from public, anon;

grant execute on function public.admin_adjust_tickets(
    integer, bigint, text, text, integer, text
) to authenticated;
grant execute on function public.admin_set_exam_access(
    integer, integer, text, boolean, text
) to authenticated;
grant execute on function public.admin_replace_user_roles(
    integer, integer[], text
) to authenticated;
grant execute on function public.admin_send_support_message(
    integer, text, text, boolean, text
) to authenticated;
grant execute on function public.admin_create_moderation_action(
    integer, integer, text, text, jsonb, text
) to authenticated;

commit;
