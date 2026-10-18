-- V1 WAVE 5a: INVITA Y GANA (referral). Contract: E1-V1-W5-REFERRAL-CONTRACT.md (FA34). Design: E4-V1-REFERRAL-DESIGN v3.1 (R-1..R-5, C-V1..C-V12, N-V1..N-V6).
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Requires 20261022101200 (app_credit_free_ot) and 20261022101500 (configuracion_v1 bounds + audit).
-- One stable code per user; the invitee attaches it only while completing the profile (neutral on every failure); a state machine moves registrada -> email_verificado -> diferida -> premiada | rechazada | revocada | error;
-- the payout is a bounded periodic job that pays BOTH sides through the single earn-cap function (all-or-nothing per beneficiary, ascending user-id lock order); the exam-finish and email-verified signals only RECORD
-- (cheap indexed updates wrapped so they can never raise). Reward OFF by default (referido_activo = 0). Configuration lives in configuracion_v1 (bounded and audited by 20261022101500).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate)
-- ROLLBACK-BEGIN
-- begin;
-- drop trigger if exists trg_auth_user_email_verified on auth.users;
-- drop trigger if exists trg_intentos_examen_referral on public.intentos_examen;
-- drop function if exists private.tg_auth_user_email_verified();
-- drop function if exists private.tg_intento_referral_first_exam();
-- drop function if exists public.admin_referral_revoke(uuid, uuid, text);
-- drop function if exists public.app_referral_list(uuid, text, integer);
-- drop function if exists public.app_job_referidos();
-- drop function if exists public.app_referral_attach(uuid, text, text);
-- drop function if exists public.app_referral_get(uuid);
-- drop function if exists private.referral_eligible(uuid);
-- drop function if exists private.referral_mail(uuid);
-- drop function if exists private.email_root(text);
-- drop function if exists private.gen_invite_code();
-- drop table if exists public.invitacion_intentos;
-- drop table if exists public.invitaciones_dominios_bloqueados;
-- drop table if exists public.invitaciones;
-- drop table if exists public.codigos_invitacion;
-- delete from public.configuracion_v1 where clave like 'referido\_%';
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'referral_functions_not_service_role_only:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute')
--                or not has_function_privilege('service_role', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('public.app_referral_get(uuid)'), ('public.app_referral_attach(uuid,text,text)'), ('public.app_job_referidos()'), ('public.app_referral_list(uuid,text,integer)'), ('public.admin_referral_revoke(uuid,uuid,text)')) f(n)
-- union all
-- select 'referral_tables_client_access:' || t.n,
--        (case when has_table_privilege('anon', t.n, 'select') or has_table_privilege('authenticated', t.n, 'select') or has_table_privilege('authenticated', t.n, 'insert')
--                or has_table_privilege('authenticated', t.n, 'update') or has_table_privilege('authenticated', t.n, 'delete') or has_table_privilege('service_role', t.n, 'select')
--                or not (select c.relrowsecurity from pg_class c where c.oid = t.n::regclass) then 1 else 0 end)::bigint
--   from (values ('public.codigos_invitacion'), ('public.invitaciones'), ('public.invitacion_intentos'), ('public.invitaciones_dominios_bloqueados')) t(n)
-- union all
-- select 'referral_reward_not_off_by_default',
--        (case when exists (select 1 from public.configuracion_v1 where clave = 'referido_activo' and valor_integer <> 0) then 1 else 0 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('public.app_credit_free_ot(uuid,text,integer,text,uuid,text,jsonb)') is null or to_regclass('public.configuracion_v1') is null
     or to_regprocedure('private.config_int(text,integer)') is null or to_regclass('public.intentos_examen') is null then
    raise exception using errcode = 'P0001', message = 'V1_REFERRAL_PREFLIGHT', hint = 'apply 20261022101200 and 20261022101500 first';
  end if;
end
$$;

-- ---- 1. configuration (Control editable through configuracion_v1; bounds + audit by 20261022101500). OFF by default.
insert into public.configuracion_v1 (clave, valor_integer) values
  ('referido_activo', 0), ('referido_ot_invitador', 0), ('referido_ot_invitado', 0), ('referido_min_respuestas', 10), ('referido_min_segundos', 60),
  ('referido_min_horas', 24), ('referido_retener_horas', 24), ('referido_tope_diario', 5), ('referido_tope_total', 50), ('referido_max_intentos_pago', 5),
  ('referido_intentos_hora', 10), ('referido_ip_rafaga', 20)
on conflict (clave) do nothing;

-- ---- 2. data (server only: RLS on, no grant, no policy)
create table if not exists public.codigos_invitacion (
  usuario_id uuid primary key references auth.users(id) on delete cascade,
  codigo text not null unique check (codigo ~ '^[A-HJKMNP-TV-Z2-9]{8}$'),
  creado_en timestamptz not null default clock_timestamp()
);

create table if not exists public.invitaciones (
  id uuid primary key default gen_random_uuid(),
  invitador_id uuid references auth.users(id) on delete set null,   -- the invitee keeps his reward if the inviter deletes the account
  invitado_id uuid not null unique references auth.users(id) on delete cascade,   -- one reward per invitee, ever
  codigo text not null,
  estado text not null default 'registrada' check (estado in ('registrada', 'email_verificado', 'diferida', 'premiada', 'rechazada', 'revocada', 'error')),
  email_verificado_en timestamptz,
  primer_examen_en timestamptz,
  intento_id uuid,
  diferida_hasta timestamptz,
  premiada_en timestamptz,
  pago_invitador text not null default 'pendiente' check (pago_invitador in ('pendiente', 'pagado', 'diferido', 'rechazado_tope')),
  pago_invitado text not null default 'pendiente' check (pago_invitado in ('pendiente', 'pagado', 'diferido', 'rechazado_tope')),
  intentos_pago integer not null default 0,
  ultimo_error text,
  flags jsonb not null default '{}'::jsonb,
  motivo text,
  creado_en timestamptz not null default clock_timestamp(),
  constraint ck_invitaciones_distintos check (invitador_id is distinct from invitado_id)
);
create index if not exists ix_invitaciones_invitador on public.invitaciones (invitador_id, estado);
create index if not exists ix_invitaciones_job on public.invitaciones (estado, diferida_hasta) where estado in ('email_verificado', 'diferida');

create table if not exists public.invitacion_intentos (
  id bigint generated always as identity primary key,
  usuario_id uuid not null,
  ip_hash text,
  creado_en timestamptz not null default clock_timestamp()
);
create index if not exists ix_invitacion_intentos_usuario on public.invitacion_intentos (usuario_id, creado_en desc);
create index if not exists ix_invitacion_intentos_ip on public.invitacion_intentos (ip_hash, creado_en desc) where ip_hash is not null;

create table if not exists public.invitaciones_dominios_bloqueados (
  dominio text primary key check (dominio = lower(dominio))
);

alter table public.codigos_invitacion enable row level security;
alter table public.invitaciones enable row level security;
alter table public.invitacion_intentos enable row level security;
alter table public.invitaciones_dominios_bloqueados enable row level security;
revoke all on table public.codigos_invitacion, public.invitaciones, public.invitacion_intentos, public.invitaciones_dominios_bloqueados from public, anon, authenticated, service_role;

-- ---- 3. helpers
-- 8 symbols out of a 30-symbol alphabet (no 0/O/1/I/L/U) from the CSPRNG behind gen_random_uuid(); never sequential, never derived from the user id
create or replace function private.gen_invite_code()
returns text
language plpgsql
volatile
set search_path = ''
as $$
declare
  v_alpha constant text := 'ABCDEFGHJKMNPQRSTVWXYZ23456789';
  v_bytes bytea := decode(substr(replace(gen_random_uuid()::text, '-', ''), 1, 16), 'hex');
  v_out text := '';
  i integer;
begin
  for i in 0..7 loop
    v_out := v_out || substr(v_alpha, 1 + (get_byte(v_bytes, i) % 30), 1);
  end loop;
  return v_out;
end;
$$;
revoke all on function private.gen_invite_code() from public, anon, authenticated;

-- the mailbox identity: lower case, +tag stripped, Gmail dots removed (googlemail = gmail)
create or replace function private.email_root(p_email text)
returns text
language sql
immutable
set search_path = ''
as $$
  select case when d in ('gmail.com', 'googlemail.com') then replace(split_part(l, '+', 1), '.', '') || '@gmail.com' else split_part(l, '+', 1) || '@' || d end
    from (select split_part(lower(btrim(coalesce(p_email, ''))), '@', 1) as l, split_part(lower(btrim(coalesce(p_email, ''))), '@', 2) as d) x
$$;
revoke all on function private.email_root(text) from public, anon, authenticated;

-- ---- 4. the signals (record only; they can NEVER raise: a raising trigger would block e-mail confirmation or the exam finish)
create or replace function private.tg_auth_user_email_verified()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  begin
    update public.invitaciones set estado = 'email_verificado', email_verificado_en = coalesce(email_verificado_en, clock_timestamp())
     where invitado_id = new.id and estado = 'registrada';
  exception when others then
    null;
  end;
  return new;
end;
$$;
revoke all on function private.tg_auth_user_email_verified() from public, anon, authenticated;
drop trigger if exists trg_auth_user_email_verified on auth.users;
create trigger trg_auth_user_email_verified after update of email_confirmed_at on auth.users
  for each row when (old.email_confirmed_at is null and new.email_confirmed_at is not null) execute function private.tg_auth_user_email_verified();

-- only the FIRST finished non-demo exam attempt of an invited user is recorded (the job re-evaluates ALL his attempts, R-2)
create or replace function private.tg_intento_referral_first_exam()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  begin
    update public.invitaciones set primer_examen_en = clock_timestamp(), intento_id = new.id
     where invitado_id = new.usuario_id and primer_examen_en is null;
  exception when others then
    null;
  end;
  return new;
end;
$$;
revoke all on function private.tg_intento_referral_first_exam() from public, anon, authenticated;
drop trigger if exists trg_intentos_examen_referral on public.intentos_examen;
create trigger trg_intentos_examen_referral after update of estado on public.intentos_examen
  for each row when (old.estado is distinct from 'finalizado' and new.estado = 'finalizado' and not new.es_demo and new.origen_intento = 'examen')
  execute function private.tg_intento_referral_first_exam();

-- ---- 5. the code and the counters (own data only)
create or replace function public.app_referral_get(p_usuario uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_code text;
  v_try integer := 0;
  v_ot integer := private.config_int('referido_ot_invitador', 0);
begin
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  select c.codigo into v_code from public.codigos_invitacion c where c.usuario_id = p_usuario;
  if v_code is null then
    perform pg_advisory_xact_lock(hashtextextended('referral:' || p_usuario::text, 0));
    select c.codigo into v_code from public.codigos_invitacion c where c.usuario_id = p_usuario;
    while v_code is null and v_try < 8 loop
      v_try := v_try + 1;
      begin
        insert into public.codigos_invitacion (usuario_id, codigo) values (p_usuario, private.gen_invite_code()) returning codigo into v_code;
      exception when unique_violation then
        select c.codigo into v_code from public.codigos_invitacion c where c.usuario_id = p_usuario;   -- a racing call created it, or the random code collided: retry
      end;
    end loop;
    if v_code is null then
      raise exception using errcode = 'P0001', message = 'REFERRAL_CODE_UNAVAILABLE';
    end if;
  end if;
  return jsonb_build_object(
    'codigo', v_code,
    'invitados', jsonb_build_object(
      'total', (select count(*) from public.invitaciones i where i.invitador_id = p_usuario and i.estado <> 'rechazada'),
      'premiados', (select count(*) from public.invitaciones i where i.invitador_id = p_usuario and i.estado = 'premiada')),
    'otGanados', coalesce((select sum(t.cantidad) from public.transacciones_cartera t where t.usuario_id = p_usuario and t.tipo = 'referido' and t.cantidad > 0), 0),
    'configuracion', jsonb_build_object('otPorInvitacion', case when private.config_int('referido_activo', 0) = 1 and v_ot > 0 then v_ot end));
end;
$$;
revoke all on function public.app_referral_get(uuid) from public, anon, authenticated;
grant execute on function public.app_referral_get(uuid) to service_role;

-- ---- 6. attach (registration only). NEUTRAL: every unusable code returns {adjunta:false}; it never raises and never says why.
create or replace function public.app_referral_attach(p_invitado uuid, p_codigo text, p_ip_hash text default null)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_code text := upper(btrim(coalesce(p_codigo, '')));
  v_inviter uuid;
  v_mail_inv text;
  v_mail_inv_dom text;
  v_mail_inviter text;
  v_conf timestamptz;
  v_flags jsonb := '{}'::jsonb;
  v_estado text;
  v_motivo text;
begin
  begin
    if v_code !~ '^[A-HJKMNP-TV-Z2-9]{8}$' or p_invitado is null or private.config_int('referido_activo', 0) <> 1 then
      return jsonb_build_object('adjunta', false);
    end if;
    -- attempts are counted (per account and per ip hash) before anything else, so a guessing loop is throttled whatever it guesses
    insert into public.invitacion_intentos (usuario_id, ip_hash) values (p_invitado, nullif(left(p_ip_hash, 64), ''));
    if (select count(*) from public.invitacion_intentos x where x.usuario_id = p_invitado and x.creado_en > clock_timestamp() - interval '1 hour') > private.config_int('referido_intentos_hora', 10) then
      return jsonb_build_object('adjunta', false);
    end if;
    select c.usuario_id into v_inviter from public.codigos_invitacion c where c.codigo = v_code;
    if v_inviter is null or v_inviter = p_invitado or exists (select 1 from public.invitaciones i where i.invitado_id = p_invitado) then
      return jsonb_build_object('adjunta', false);
    end if;
    if not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = v_inviter and pf.estado_cuenta = 'activa')
       or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_invitado and pf.estado_cuenta = 'activa') then
      return jsonb_build_object('adjunta', false);
    end if;
    if (select count(*) from public.invitaciones i where i.invitador_id = v_inviter and i.estado in ('registrada', 'email_verificado', 'diferida', 'premiada', 'error'))
         >= private.config_int('referido_tope_total', 50) then
      return jsonb_build_object('adjunta', false);
    end if;
    select m.email, m.confirmado into v_mail_inv, v_conf from private.referral_mail(p_invitado) m;
    select m.email into v_mail_inviter from private.referral_mail(v_inviter) m;
    v_mail_inv_dom := lower(split_part(coalesce(v_mail_inv, ''), '@', 2));
    -- hard flags => rejected on the spot (listed for review in Control); soft flags stay on the row
    if private.email_root(v_mail_inv) = private.email_root(v_mail_inviter) then v_flags := v_flags || '{"mismo_correo_raiz": true}'; end if;
    if exists (select 1 from public.invitaciones_dominios_bloqueados d where d.dominio = v_mail_inv_dom) then v_flags := v_flags || '{"dominio_bloqueado": true}'; end if;
    if p_ip_hash is not null and (select count(*) from public.invitacion_intentos x where x.ip_hash = left(p_ip_hash, 64) and x.creado_en > clock_timestamp() - interval '1 hour') > private.config_int('referido_ip_rafaga', 20) then
      v_flags := v_flags || '{"rafaga_ip": true}';
    end if;
    if v_flags ? 'mismo_correo_raiz' or v_flags ? 'dominio_bloqueado' then
      v_estado := 'rechazada'; v_motivo := 'flag_duro';
    else
      v_estado := case when v_conf is not null then 'email_verificado' else 'registrada' end;   -- R-4: the invitation is created after the e-mail was confirmed in the normal flow
    end if;
    insert into public.invitaciones (invitador_id, invitado_id, codigo, estado, email_verificado_en, flags, motivo)
    values (v_inviter, p_invitado, v_code, v_estado, case when v_conf is not null then v_conf end, v_flags, v_motivo);
    return jsonb_build_object('adjunta', true);
  exception when others then
    return jsonb_build_object('adjunta', false);
  end;
end;
$$;
revoke all on function public.app_referral_attach(uuid, text, text) from public, anon, authenticated;
grant execute on function public.app_referral_attach(uuid, text, text) to service_role;

-- the only reader of auth.users for the referral (kept out of the app_* functions: the gate forbids them to read auth.users)
create or replace function private.referral_mail(p_user uuid)
returns table(email text, confirmado timestamptz)
language sql
stable
security definer
set search_path = ''
as $$
  select u.email::text, u.email_confirmed_at from auth.users u where u.id = p_user
$$;
revoke all on function private.referral_mail(uuid) from public, anon, authenticated;

-- ---- 7. the rules, evaluated from the database over ALL the invitee's finished attempts (R-2)
create or replace function private.referral_eligible(p_inv uuid)
returns boolean
language sql
stable
set search_path = ''
as $$
  select exists (
    select 1 from public.invitaciones i
     where i.id = p_inv
       and i.email_verificado_en is not null
       and i.email_verificado_en <= clock_timestamp() - make_interval(hours => private.config_int('referido_min_horas', 24))
       and exists (select 1 from public.intentos_examen a
                    where a.usuario_id = i.invitado_id and a.estado = 'finalizado' and not a.es_demo and a.origen_intento = 'examen'
                      and a.total_respondidas >= private.config_int('referido_min_respuestas', 10)
                      and coalesce(a.duracion_segundos, extract(epoch from (a.finalizado_en - a.iniciado_en))::integer, 0) >= private.config_int('referido_min_segundos', 60))
       and exists (select 1 from public.perfiles_usuario p where p.usuario_id = i.invitado_id and p.estado_cuenta = 'activa')
       and (i.invitador_id is null or exists (select 1 from public.perfiles_usuario p where p.usuario_id = i.invitador_id and p.estado_cuenta = 'activa')))
$$;
revoke all on function private.referral_eligible(uuid) from public, anon, authenticated;

-- ---- 8. the payout job (bounded, throttled by the host loop, advisory lock, per-row sub-transaction, idempotent)
create or replace function public.app_job_referidos()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  r record;
  v_now timestamptz;
  v_hoy_ini timestamptz;
  v_ot_a integer := private.config_int('referido_ot_invitador', 0);
  v_ot_b integer := private.config_int('referido_ot_invitado', 0);
  v_max integer := private.config_int('referido_max_intentos_pago', 5);
  v_proc integer := 0; v_pag integer := 0; v_err integer := 0;
  v_inv uuid; v_dev integer; v_res record; v_out record;
  v_pa text; v_pb text; v_cur text; v_rol text; k integer; v_first_inviter boolean;
begin
  if not pg_try_advisory_xact_lock(hashtextextended('job:referidos', 0)) then
    return jsonb_build_object('estado', 'OCUPADO');
  end if;
  perform set_config('statement_timeout', '30000', true);
  if private.config_int('referido_activo', 0) <> 1 or (v_ot_a <= 0 and v_ot_b <= 0) then
    return jsonb_build_object('estado', 'APAGADO');
  end if;
  for r in select i.id from public.invitaciones i
            where i.estado in ('email_verificado', 'diferida') and (i.diferida_hasta is null or i.diferida_hasta <= clock_timestamp())
            order by i.creado_en limit 100
  loop
    v_proc := v_proc + 1;
    begin
      v_now := clock_timestamp();
      select * into v_res from public.invitaciones where id = r.id for update skip locked;
      if not found then continue; end if;
      if not private.referral_eligible(r.id) then
        continue;   -- not yet (hours, activity); re-evaluated on the next run
      end if;
      if v_res.estado = 'email_verificado' then
        update public.invitaciones set estado = 'diferida', diferida_hasta = v_now + make_interval(hours => private.config_int('referido_retener_horas', 24)) where id = r.id;
        continue;
      end if;
      -- diferida and the hold elapsed: pay (the inviter's daily cap keeps the invitation waiting, the total cap rejects that side)
      v_hoy_ini := date_trunc('day', v_now at time zone 'Europe/Madrid') at time zone 'Europe/Madrid';
      v_pa := v_res.pago_invitador; v_pb := v_res.pago_invitado;
      if v_res.invitador_id is not null and v_pa <> 'pagado' and v_ot_a > 0 then
        if (select count(*) from public.invitaciones x where x.invitador_id = v_res.invitador_id and x.estado = 'premiada' and x.premiada_en >= v_hoy_ini) >= private.config_int('referido_tope_diario', 5) then
          update public.invitaciones set diferida_hasta = v_hoy_ini + interval '1 day 5 minutes' where id = r.id;
          continue;
        end if;
        if (select count(*) from public.invitaciones x where x.invitador_id = v_res.invitador_id and x.estado = 'premiada') >= private.config_int('referido_tope_total', 50) then
          v_pa := 'rechazado_tope';
        end if;
      end if;
      -- both beneficiaries, ascending user id (the lock order of every two-user caller)
      v_first_inviter := v_res.invitador_id is not null and v_res.invitador_id < v_res.invitado_id;
      for k in 1..2 loop
        v_rol := case when (k = 1) = v_first_inviter then 'invitador' else 'invitado' end;
        v_inv := case when v_rol = 'invitador' then v_res.invitador_id else v_res.invitado_id end;
        v_dev := case when v_rol = 'invitador' then v_ot_a else v_ot_b end;
        v_cur := case when v_rol = 'invitador' then v_pa else v_pb end;
        if v_inv is null or v_cur in ('pagado', 'rechazado_tope') then
          continue;
        end if;
        if v_dev <= 0 then
          v_cur := 'pagado';   -- nothing owed to this side
        else
          select * into v_out from public.app_credit_free_ot(v_inv, 'referido', v_dev, 'invitacion', r.id, 'referido:' || r.id::text || ':' || v_rol, jsonb_build_object('rol', v_rol));
          v_cur := case v_out.estado when 'CREDITED' then 'pagado' when 'REPLAY' then 'pagado' when 'DEFERRED' then 'diferido' else 'rechazado_tope' end;
        end if;
        if v_rol = 'invitador' then v_pa := v_cur; else v_pb := v_cur; end if;
      end loop;
      if v_pa = 'pagado' and v_pb = 'pagado' then
        update public.invitaciones set estado = 'premiada', premiada_en = v_now, pago_invitador = v_pa, pago_invitado = v_pb, ultimo_error = null where id = r.id;
        v_pag := v_pag + 1;
      elsif v_pa = 'rechazado_tope' or v_pb = 'rechazado_tope' then
        update public.invitaciones set estado = 'rechazada', motivo = 'tope', pago_invitador = v_pa, pago_invitado = v_pb where id = r.id;
      else
        update public.invitaciones set pago_invitador = v_pa, pago_invitado = v_pb, diferida_hasta = v_now + interval '1 hour' where id = r.id;   -- a deferral is re-offered by the earn retry job; check again soon
      end if;
    exception when others then
      v_err := v_err + 1;
      update public.invitaciones
         set intentos_pago = intentos_pago + 1, ultimo_error = left(sqlstate || ' ' || sqlerrm, 120),
             estado = case when intentos_pago + 1 >= v_max then 'error' else estado end,
             diferida_hasta = clock_timestamp() + make_interval(mins => least(power(2, intentos_pago + 1)::integer, 1440))
       where id = r.id;
    end;
  end loop;
  return jsonb_build_object('estado', 'OK', 'procesadas', v_proc, 'premiadas', v_pag, 'errores', v_err);
end;
$$;
revoke all on function public.app_job_referidos() from public, anon, authenticated;
grant execute on function public.app_job_referidos() to service_role;

-- ---- 9. the inviter's list: ONLY rewarded invitations, the invitee's public username, keyset cursor (opaque)
create or replace function public.app_referral_list(p_usuario uuid, p_cursor text default null, p_limite integer default 20)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_lim integer := least(greatest(coalesce(p_limite, 20), 1), 50);
  v_ts timestamptz; v_id uuid;
  v_items jsonb; v_next text; v_n integer;
begin
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  if p_cursor is not null then
    begin
      v_ts := split_part(convert_from(decode(translate(p_cursor, '-_', '+/'), 'base64'), 'utf8'), '|', 1)::timestamptz;
      v_id := split_part(convert_from(decode(translate(p_cursor, '-_', '+/'), 'base64'), 'utf8'), '|', 2)::uuid;
    exception when others then
      raise exception using errcode = 'P0001', message = 'CURSOR_INVALID';
    end;
  end if;
  select coalesce(jsonb_agg(jsonb_build_object('handle', x.handle, 'premiadaEn', x.premiada_en) order by x.premiada_en desc, x.id desc), '[]'::jsonb), count(*),
         (array_agg(encode(convert_to(x.premiada_en::text || '|' || x.id::text, 'utf8'), 'base64') order by x.premiada_en asc, x.id asc))[1]
    into v_items, v_n, v_next
    from (select i.id, i.premiada_en, pf.username as handle
            from public.invitaciones i left join public.perfiles_usuario pf on pf.usuario_id = i.invitado_id
           where i.invitador_id = p_usuario and i.estado = 'premiada' and (v_ts is null or (i.premiada_en, i.id) < (v_ts, v_id))
           order by i.premiada_en desc, i.id desc limit v_lim) x;
  return jsonb_build_object('items', v_items, 'nextCursor', case when v_n >= v_lim then translate(replace(v_next, E'\n', ''), '+/', '-_') end);
end;
$$;
revoke all on function public.app_referral_list(uuid, text, integer) from public, anon, authenticated;
grant execute on function public.app_referral_list(uuid, text, integer) to service_role;

-- ---- 10. Control: revoke (audited; reverses at most the CURRENT unspent balance of each paid grant, never negative; the rest is recorded, not hidden)
create or replace function public.admin_referral_revoke(p_actor uuid, p_invitacion uuid, p_motivo text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  i public.invitaciones%rowtype;
  v_u uuid; v_rol text; v_paid integer; v_bal integer; v_rev integer; v_not integer := 0; v_total integer := 0;
begin
  select * into i from public.invitaciones where id = p_invitacion for update;
  if not found then
    raise exception using errcode = 'P0001', message = 'REFERRAL_NOT_FOUND';
  end if;
  if i.estado = 'revocada' then
    return jsonb_build_object('estado', 'revocada', 'revertido', 0, 'noRevertible', 0);
  end if;
  for v_u, v_rol in select u, r from (values (i.invitador_id, 'invitador'), (i.invitado_id, 'invitado')) t(u, r) where u is not null order by u loop
    perform pg_advisory_xact_lock(hashtextextended('wallet:' || v_u::text, 0));
    select coalesce(sum(t.cantidad), 0) into v_paid from public.transacciones_cartera t
     where t.usuario_id = v_u and t.tipo = 'referido' and t.referencia_id = i.id and t.cantidad > 0;
    if v_paid > 0 then
      select coalesce(w.saldo_tickets, 0) into v_bal from public.carteras_usuario w where w.usuario_id = v_u;
      v_rev := least(v_paid, greatest(coalesce(v_bal, 0), 0));
      if v_rev > 0 then
        perform public.admin_grant_tickets(v_u, -v_rev, 'revocar invitacion', 'revoke:referido:' || i.id::text || ':' || v_rol, 'ajuste');
      end if;
      v_total := v_total + v_rev; v_not := v_not + (v_paid - v_rev);
    end if;
  end loop;
  update public.invitaciones set estado = 'revocada', motivo = left(coalesce(p_motivo, 'revocada'), 200) || case when v_not > 0 then ' | no revertible: ' || v_not else '' end where id = i.id;
  insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, antes, despues)
  values (p_actor, 'invitacion_revocada', 'invitaciones', i.id, jsonb_build_object('estado', i.estado), jsonb_build_object('revertido', v_total, 'no_revertible', v_not));
  return jsonb_build_object('estado', 'revocada', 'revertido', v_total, 'noRevertible', v_not);
end;
$$;
revoke all on function public.admin_referral_revoke(uuid, uuid, text) from public, anon, authenticated;
grant execute on function public.admin_referral_revoke(uuid, uuid, text) to service_role;

commit;
