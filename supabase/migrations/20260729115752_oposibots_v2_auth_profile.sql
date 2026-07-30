-- OposiBots V2 consumer authentication profile support.
-- Additive only: the administrative user model remains the source for RBAC.
-- News schemas, tables, policies, and functions are intentionally untouched.

begin;

alter table public.usuario
    add column if not exists apellidos varchar,
    add column if not exists username varchar,
    add column if not exists especialidad_registro text,
    add column if not exists perfil_completo boolean not null default false,
    add column if not exists perfil_completado_at timestamptz;

create unique index if not exists idx_usuario_username_active_unique
    on public.usuario (username)
    where username is not null
      and deleted_at is null;

create table if not exists private.oposibots_oauth_credential (
    provider text not null check (provider in ('google', 'apple')),
    credential_hash char(64) not null
        check (credential_hash ~ '^[0-9a-f]{64}$'),
    expires_at timestamptz not null,
    consumed_at timestamptz not null default now(),
    primary key (provider, credential_hash)
);

create index if not exists idx_oposibots_oauth_credential_expires_at
    on private.oposibots_oauth_credential (expires_at);

revoke all on table private.oposibots_oauth_credential
from public, anon, authenticated;
grant select, insert, delete on table private.oposibots_oauth_credential
to service_role;

create or replace function public.oposibots_complete_registration_profile(
    p_auth_user_id uuid,
    p_email text,
    p_first_name text,
    p_last_name text,
    p_username text,
    p_specialty text
)
returns void
language plpgsql
security invoker
set search_path = ''
as $$
declare
    current_user_row public.usuario%rowtype;
    normalized_username text;
begin
    if p_auth_user_id is null then
        raise exception using errcode = '22023', message = 'INVALID_AUTH_USER';
    end if;
    if nullif(btrim(p_first_name), '') is null then
        raise exception using errcode = '22023', message = 'BLANK_FIRST_NAME';
    end if;
    if nullif(btrim(p_last_name), '') is null then
        raise exception using errcode = '22023', message = 'BLANK_LAST_NAME';
    end if;
    if nullif(btrim(p_specialty), '') is null then
        raise exception using errcode = '22023', message = 'SPECIALTY_NOT_SELECTED';
    end if;

    select usuario.*
    into current_user_row
    from public.usuario
    where usuario.auth_user_id = p_auth_user_id
    for update;

    if found and (
        not current_user_row.activo or
        current_user_row.deleted_at is not null
    ) then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_BLOCKED';
    end if;

    normalized_username := nullif(btrim(p_username), '');

    insert into public.usuario (
        auth_user_id,
        email,
        nombre,
        apellidos,
        username,
        especialidad_registro,
        activo,
        perfil_completo,
        perfil_completado_at
    )
    values (
        p_auth_user_id,
        nullif(btrim(p_email), ''),
        btrim(p_first_name),
        btrim(p_last_name),
        normalized_username,
        btrim(p_specialty),
        true,
        true,
        now()
    )
    on conflict (auth_user_id)
        where auth_user_id is not null
    do update
    set
        email = coalesce(excluded.email, usuario.email),
        nombre = excluded.nombre,
        apellidos = excluded.apellidos,
        username = excluded.username,
        especialidad_registro = excluded.especialidad_registro,
        perfil_completo = true,
        perfil_completado_at = now(),
        updated_at = now(),
        version = usuario.version + 1;
end;
$$;

revoke all on function public.oposibots_complete_registration_profile(
    uuid,
    text,
    text,
    text,
    text,
    text
) from public, anon, authenticated;
grant execute on function public.oposibots_complete_registration_profile(
    uuid,
    text,
    text,
    text,
    text,
    text
) to service_role;

create or replace function public.oposibots_claim_oauth_credential(
    p_provider text,
    p_credential_hash text,
    p_expires_at timestamptz
)
returns void
language plpgsql
security invoker
set search_path = ''
as $$
begin
    if p_provider not in ('google', 'apple') then
        raise exception using errcode = '22023', message = 'INVALID_PROVIDER';
    end if;
    if p_credential_hash is null or
       p_credential_hash !~ '^[0-9a-f]{64}$' then
        raise exception using errcode = '22023', message = 'INVALID_CREDENTIAL_HASH';
    end if;
    if p_expires_at is null or p_expires_at <= now() then
        raise exception using errcode = '22023', message = 'EXPIRED_CREDENTIAL';
    end if;

    -- Keep the claim transaction short even if a dormant environment has
    -- accumulated a large number of expired credentials.
    delete from private.oposibots_oauth_credential
    where ctid in (
        select ctid
        from private.oposibots_oauth_credential
        where expires_at <= now()
        order by expires_at
        limit 1000
    );

    insert into private.oposibots_oauth_credential (
        provider,
        credential_hash,
        expires_at
    )
    values (
        p_provider,
        p_credential_hash,
        p_expires_at
    );
end;
$$;

revoke all on function public.oposibots_claim_oauth_credential(
    text,
    text,
    timestamptz
) from public, anon, authenticated;
grant execute on function public.oposibots_claim_oauth_credential(
    text,
    text,
    timestamptz
) to service_role;

commit;
