-- OposiBots consumer profile. Authentication remains owned by Supabase Auth.
-- This migration is additive and does not touch News or business APIs.

begin;

create schema if not exists private;
revoke all on schema private from public, anon, authenticated;
grant usage on schema private to service_role;

alter table public.usuario
    add column if not exists apellidos varchar(100),
    add column if not exists username varchar(30),
    add column if not exists especialidad_registro varchar(100),
    add column if not exists perfil_completo boolean not null default false,
    add column if not exists perfil_completado_at timestamptz;

create unique index if not exists idx_usuario_auth_user_id_unique
    on public.usuario (auth_user_id)
    where auth_user_id is not null;

create unique index if not exists idx_usuario_username_active_unique
    on public.usuario (lower(username))
    where username is not null
      and deleted_at is null;

alter table public.usuario enable row level security;

drop policy if exists oposibots_consumer_profile_select on public.usuario;
create policy oposibots_consumer_profile_select
on public.usuario
for select
to authenticated
using (
    (select auth.uid()) is not null
    and auth_user_id = (select auth.uid())
);

revoke insert, update, delete on table public.usuario from anon, authenticated;
grant select on table public.usuario to authenticated;

create or replace function public.oposibots_upsert_own_profile(
    p_first_name text,
    p_last_name text,
    p_username text,
    p_specialty text
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    actor_id uuid := (select auth.uid());
    actor_email text;
    normalized_username text := nullif(btrim(p_username), '');
begin
    if actor_id is null or coalesce((select auth.jwt() ->> 'is_anonymous')::boolean, false) then
        raise exception using errcode = '28000', message = 'AUTHENTICATION_REQUIRED';
    end if;
    if nullif(btrim(p_first_name), '') is null or char_length(btrim(p_first_name)) > 100 then
        raise exception using errcode = '22023', message = 'INVALID_FIRST_NAME';
    end if;
    if nullif(btrim(p_last_name), '') is null or char_length(btrim(p_last_name)) > 100 then
        raise exception using errcode = '22023', message = 'INVALID_LAST_NAME';
    end if;
    if nullif(btrim(p_specialty), '') is null or char_length(btrim(p_specialty)) > 100 then
        raise exception using errcode = '22023', message = 'INVALID_SPECIALTY';
    end if;
    if normalized_username is not null and (
        char_length(normalized_username) not between 3 and 30 or
        normalized_username !~ '^[A-Za-z0-9._-]+$'
    ) then
        raise exception using errcode = '22023', message = 'INVALID_USERNAME';
    end if;

    select users.email
    into actor_email
    from auth.users
    where users.id = actor_id;

    if not found then
        raise exception using errcode = '28000', message = 'AUTHENTICATION_REQUIRED';
    end if;

    insert into public.usuario (
        auth_user_id,
        email,
        nombre,
        apellidos,
        username,
        especialidad_registro,
        perfil_completo,
        perfil_completado_at,
        updated_at
    )
    values (
        actor_id,
        actor_email,
        btrim(p_first_name),
        btrim(p_last_name),
        normalized_username,
        btrim(p_specialty),
        true,
        now(),
        now()
    )
    on conflict (auth_user_id)
        where auth_user_id is not null
    do update
    set
        email = excluded.email,
        nombre = excluded.nombre,
        apellidos = excluded.apellidos,
        username = excluded.username,
        especialidad_registro = excluded.especialidad_registro,
        perfil_completo = true,
        perfil_completado_at = now(),
        updated_at = now();
end;
$$;

revoke all on function public.oposibots_upsert_own_profile(
    text,
    text,
    text,
    text
) from public, anon, authenticated;
grant execute on function public.oposibots_upsert_own_profile(
    text,
    text,
    text,
    text
) to authenticated;

create table if not exists private.oposibots_oauth_credential (
    provider text not null check (provider in ('google', 'apple')),
    credential_hash char(64) not null check (credential_hash ~ '^[0-9a-f]{64}$'),
    expires_at timestamptz not null,
    consumed_at timestamptz not null default now(),
    primary key (provider, credential_hash)
);

create index if not exists idx_oposibots_oauth_credential_expires_at
    on private.oposibots_oauth_credential (expires_at);

revoke all on table private.oposibots_oauth_credential from public, anon, authenticated;
grant select, insert, delete on table private.oposibots_oauth_credential to service_role;

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
    if p_provider not in ('google', 'apple') or
       p_credential_hash !~ '^[0-9a-f]{64}$' or
       p_expires_at <= now() or
       p_expires_at > now() + interval '24 hours' then
        raise exception using errcode = '22023', message = 'INVALID_OAUTH_CREDENTIAL';
    end if;

    delete from private.oposibots_oauth_credential
    where ctid in (
        select ctid
        from private.oposibots_oauth_credential
        where expires_at <= now()
        order by expires_at
        limit 1000
    );

    insert into private.oposibots_oauth_credential(provider, credential_hash, expires_at)
    values (p_provider, p_credential_hash, p_expires_at);
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
