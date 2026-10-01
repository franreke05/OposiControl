-- DB-CI-PG17: copia versionada de .oposibots-gate/slice1/evidence/local_assertions.sql (gate local PG17, 2026-09-29) que
-- ejecuta scripts/db-pg17-gate.sh tras bootstrap + migraciones pendientes. Cambios frente a esa copia: la asercion de
-- handle_new_auth_user sigue a MASTER-DB-001 (comentada abajo), se quita el \echo final y se anade la matriz grants/RLS.
-- SLICE 1 WP B: assertions on the LOCAL Supabase (Postgres 17.6) after baseline + Slice0 + Slice1 migrations.
-- Everything runs in one transaction that is ROLLED BACK at the end (no residue). Any failed ASSERT aborts the script.
begin;
\set ON_ERROR_STOP 1
set client_min_messages = notice;

create function pg_temp.expect(p_sql text, p_state text, p_msg text default null) returns void language plpgsql as $$
declare v_state text; v_msg text;
begin
  begin
    execute p_sql;
  exception when others then
    get stacked diagnostics v_state = returned_sqlstate, v_msg = message_text;
  end;
  if v_state is distinct from p_state or (p_msg is not null and v_msg is distinct from p_msg) then
    raise exception 'ASSERT FAILED: % -> expected %/% got %/%', p_sql, p_state, p_msg, v_state, v_msg;
  end if;
end $$;

-- ---------------------------------------------------------------- fixtures (synthetic)
insert into public.especialidades (id, codigo, nombre, slug, activa) values
  ('e1000000-0000-0000-0000-000000000001','E1','EF','ef',true),
  ('e1000000-0000-0000-0000-000000000002','E2','ING','ing',true),
  ('e1000000-0000-0000-0000-000000000003','E3','OFF','off',false);
insert into public.comunidades_autonomas (id, codigo, nombre, slug) values
  ('c1000000-0000-0000-0000-000000000001','C1','And','and'),
  ('c1000000-0000-0000-0000-000000000002','C2','Ara','ara');
insert into public.documentos_legales (id, tipo_documento, version, titulo, contenido, activo, publicado_en) values
  ('d1000000-0000-0000-0000-000000000001','terminos','v2','T','x',true,now()),
  ('d1000000-0000-0000-0000-000000000002','privacidad','v2','P','x',true,now()),
  ('d1000000-0000-0000-0000-000000000003','terminos','v1','T1','x',false,now()),
  ('d1000000-0000-0000-0000-000000000004','cookies','v1','C','x',true,now());
insert into auth.users (id, email) values
  ('a0000000-0000-0000-0000-00000000000a','a@fx.local'),   -- A active
  ('a0000000-0000-0000-0000-00000000000b','b@fx.local'),   -- B bloqueada
  ('a0000000-0000-0000-0000-00000000000c','c@fx.local'),   -- C eliminacion_pendiente
  ('a0000000-0000-0000-0000-00000000000d','d@fx.local'),   -- D unknown state
  ('a0000000-0000-0000-0000-00000000000e','e@fx.local'),   -- E no profile
  ('a0000000-0000-0000-0000-00000000000f','f@fx.local');   -- F active (second user)
update public.perfiles_usuario set estado_cuenta = 'bloqueada' where usuario_id = 'a0000000-0000-0000-0000-00000000000b';
update public.perfiles_usuario set estado_cuenta = 'eliminacion_pendiente' where usuario_id = 'a0000000-0000-0000-0000-00000000000c';
alter table public.perfiles_usuario drop constraint perfiles_usuario_estado_cuenta_chk;  -- rolled back at the end
update public.perfiles_usuario set estado_cuenta = 'zzz' where usuario_id = 'a0000000-0000-0000-0000-00000000000d';
delete from public.perfiles_usuario where usuario_id = 'a0000000-0000-0000-0000-00000000000e';
do $$ begin
  assert (select count(*) from public.perfiles_usuario) = 5, 'fixture: 5 profiles (E has none)';
  assert (select count(*) from public.usuario) = 0, 'public.usuario stays empty';
end $$;

-- ---------------------------------------------------------------- service_role: state gate
set local role service_role;
do $$
declare u text; st text; msg text; i int := 0;
begin
  perform pg_temp.expect($q$select public.app_completar_onboarding('a0000000-0000-0000-0000-00000000000b','abcd','A','B',null,null,null,null,null)$q$, '42501', 'ACCOUNT_BLOCKED');
  perform pg_temp.expect($q$select public.app_completar_onboarding('a0000000-0000-0000-0000-00000000000c','abcd','A','B',null,null,null,null,null)$q$, '42501', 'ACCOUNT_DELETION_PENDING');
  perform pg_temp.expect($q$select public.app_completar_onboarding('a0000000-0000-0000-0000-00000000000d','abcd','A','B',null,null,null,null,null)$q$, '42501', 'FORBIDDEN');
  perform pg_temp.expect($q$select public.app_completar_onboarding('a0000000-0000-0000-0000-00000000000e','abcd','A','B',null,null,null,null,null)$q$, 'OC404', 'PROFILE_MISSING');
  perform pg_temp.expect($q$select public.app_actualizar_perfil('a0000000-0000-0000-0000-00000000000b','{"nombre":"X"}'::jsonb,null)$q$, '42501', 'ACCOUNT_BLOCKED');
  perform pg_temp.expect($q$select public.app_actualizar_perfil('a0000000-0000-0000-0000-00000000000d','{"nombre":"X"}'::jsonb,null)$q$, '42501', 'FORBIDDEN');
  perform pg_temp.expect($q$select public.app_actualizar_perfil('a0000000-0000-0000-0000-00000000000e','{"nombre":"X"}'::jsonb,null)$q$, 'OC404', 'PROFILE_MISSING');
  perform pg_temp.expect($q$select public.app_aceptar_documentos_legales('a0000000-0000-0000-0000-00000000000c', array['d1000000-0000-0000-0000-000000000001']::uuid[], 'ios')$q$, '42501', 'ACCOUNT_DELETION_PENDING');
  perform pg_temp.expect($q$select public.app_aceptar_documentos_legales('a0000000-0000-0000-0000-00000000000e', array['d1000000-0000-0000-0000-000000000001']::uuid[], 'ios')$q$, 'OC404', 'PROFILE_MISSING');
  perform pg_temp.expect($q$select * from public.app_solicitar_eliminacion_cuenta('a0000000-0000-0000-0000-00000000000b')$q$, '42501', 'ACCOUNT_BLOCKED');
  perform pg_temp.expect($q$select * from public.app_solicitar_eliminacion_cuenta('a0000000-0000-0000-0000-00000000000d')$q$, '42501', 'FORBIDDEN');
  perform pg_temp.expect($q$select * from public.app_solicitar_eliminacion_cuenta('a0000000-0000-0000-0000-00000000000e')$q$, 'OC404', 'PROFILE_MISSING');
  assert (select count(*) from public.aceptaciones_legales) = 0, 'no acceptance written by denied calls';
  assert (select count(*) from public.solicitudes_eliminacion_cuenta) = 0, 'no request written by denied calls';
  assert (select count(*) from public.auditoria_log) = 0, 'no audit written by denied calls';
  raise notice 'OK state gate (blocked/pending/unknown/no-profile) for the 3 profile RPCs + deletion RPC';
end $$;

-- ---------------------------------------------------------------- service_role: onboarding / patch / legal
do $$
declare r jsonb; r2 jsonb; etag text;
begin
  r := public.app_completar_onboarding('a0000000-0000-0000-0000-00000000000a','Ana_G','Ana','Garcia','e1000000-0000-0000-0000-000000000001',
        array['d1000000-0000-0000-0000-000000000001','d1000000-0000-0000-0000-000000000002']::uuid[],'android',
        '{"fuente":"ads","plataforma":"android"}'::jsonb,'AMIGO');
  assert (r->>'onboardingCompleted')::boolean and r->>'username' = 'Ana_G' and r->>'especialidadPrincipalId' = 'e1000000-0000-0000-0000-000000000001', 'onboarding result';
  assert (select count(*) from public.aceptaciones_legales where usuario_id = 'a0000000-0000-0000-0000-00000000000a') = 2, 'two acceptances';
  assert (select codigo_referido from public.atribucion_usuario where usuario_id = 'a0000000-0000-0000-0000-00000000000a') = 'AMIGO', 'referral stored write-once';
  r2 := public.app_completar_onboarding('a0000000-0000-0000-0000-00000000000a','Ana_G','Ana','Garcia',null,null,null,null,null);
  assert (r2->>'replay')::boolean and r2->>'actualizadoEn' = r->>'actualizadoEn', 'identical replay is a no-op';
  perform pg_temp.expect($q$select public.app_completar_onboarding('a0000000-0000-0000-0000-00000000000a','Ana_G','Otra','Garcia',null,null,null,null,null)$q$, 'OC409', 'ALREADY_ONBOARDED_CONFLICT');
  perform pg_temp.expect($q$select public.app_completar_onboarding('a0000000-0000-0000-0000-00000000000f','ana_g','F','F',null,null,null,null,null)$q$, '23505', 'USERNAME_TAKEN');
  perform pg_temp.expect($q$select public.app_completar_onboarding('a0000000-0000-0000-0000-00000000000f','fefe','F','F','e1000000-0000-0000-0000-000000000003',null,null,null,null)$q$, '22023', 'UNKNOWN_SPECIALTY');
  perform pg_temp.expect($q$select public.app_completar_onboarding('a0000000-0000-0000-0000-00000000000f','fefe','F','F',null,array['d1000000-0000-0000-0000-000000000003']::uuid[],'ios',null,null)$q$, '22023', 'DOCUMENT_NOT_ACTIVE');
  assert (select onboarding_completado from public.perfiles_usuario where usuario_id = 'a0000000-0000-0000-0000-00000000000f') = false, 'F untouched after failed attempts (atomic)';
  assert (select count(*) from public.aceptaciones_legales where usuario_id = 'a0000000-0000-0000-0000-00000000000f') = 0, 'F no acceptance';

  -- DEC-002: change specialty; history untouched (no history writes possible: only one column written)
  r := public.app_actualizar_perfil('a0000000-0000-0000-0000-00000000000a','{"especialidadId":"e1000000-0000-0000-0000-000000000002","introSeen":true,"detail":{"edad":30,"ciudad":"Sevilla"},"targetCommunityIds":["c1000000-0000-0000-0000-000000000002","c1000000-0000-0000-0000-000000000001"]}'::jsonb, null);
  assert r->>'especialidadPrincipalId' = 'e1000000-0000-0000-0000-000000000002', 'specialty replaced';
  etag := r->>'actualizadoEn';
  assert public.app_actualizar_perfil('a0000000-0000-0000-0000-00000000000a','{"detail":{"edad":30}}'::jsonb, null)->>'actualizadoEn' = etag, 'no-op patch keeps ETag';
  perform pg_temp.expect($q$select public.app_actualizar_perfil('a0000000-0000-0000-0000-00000000000a','{"nombre":"Q"}'::jsonb,'2000-01-01T00:00:00Z')$q$, 'OC412', 'PRECONDITION_FAILED');
  perform pg_temp.expect($q$select public.app_actualizar_perfil('a0000000-0000-0000-0000-00000000000a','{"especialidadId":"e1000000-0000-0000-0000-000000000003"}'::jsonb,null)$q$, '22023', 'UNKNOWN_SPECIALTY');
  perform pg_temp.expect($q$select public.app_actualizar_perfil('a0000000-0000-0000-0000-00000000000a','{"estadoCuenta":"activa"}'::jsonb,null)$q$, '22023', 'UNKNOWN_FIELD');
  perform pg_temp.expect($q$select public.app_actualizar_perfil('a0000000-0000-0000-0000-00000000000a','{"detail":{"edad":5}}'::jsonb,null)$q$, '22023', 'INVALID_ARGUMENT');
  assert (select count(*) from public.comunidades_objetivo_usuario where usuario_id = 'a0000000-0000-0000-0000-00000000000a') = 2, 'target communities';
  assert public.app_actualizar_perfil('a0000000-0000-0000-0000-00000000000a','{"nombre":"Ana Maria"}'::jsonb, (etag)::timestamptz)->>'nombre' = 'Ana Maria', 'If-Match with exact micros accepted';

  r := public.app_aceptar_documentos_legales('a0000000-0000-0000-0000-00000000000f', array['d1000000-0000-0000-0000-000000000001']::uuid[], 'ios');
  assert jsonb_array_length(r->'pendingLegalDocuments') = 1 and r::text not like '%d1000000-0000-0000-0000-000000000004%', 'pending = privacy only, cookies never listed';
  r := public.app_aceptar_documentos_legales('a0000000-0000-0000-0000-00000000000f', array['d1000000-0000-0000-0000-000000000001']::uuid[], 'ios');
  assert (select count(*) from public.aceptaciones_legales where usuario_id = 'a0000000-0000-0000-0000-00000000000f') = 1, 'idempotent acceptance';
  raise notice 'OK onboarding / replay / conflict / specialty change / If-Match / legal acceptances';
end $$;

-- ---------------------------------------------------------------- service_role: deletion request
do $$
declare v record; v2 record;
begin
  select * into v from public.app_solicitar_eliminacion_cuenta('a0000000-0000-0000-0000-00000000000a');
  assert v.estado_cuenta = 'eliminacion_pendiente' and v.ya_pendiente = false, 'first request';
  select * into v2 from public.app_solicitar_eliminacion_cuenta('a0000000-0000-0000-0000-00000000000a');
  assert v2.ya_pendiente and v2.solicitada_en = v.solicitada_en, 'repeat returns original';
  assert (select count(*) from public.solicitudes_eliminacion_cuenta) = 1 and (select count(*) from public.auditoria_log where accion = 'cuenta_eliminacion_solicitada') = 1, 'one row, one audit';
  assert (select username from public.perfiles_usuario where usuario_id = 'a0000000-0000-0000-0000-00000000000a') = 'Ana_G', 'DEFERRED_EXECUTION: no anonymization';
  assert (select estado from public.solicitudes_eliminacion_cuenta) = 'solicitada', 'execution deferred';
  perform public.app_registrar_revocacion_sesiones('a0000000-0000-0000-0000-00000000000a','hecha');
  assert (select revocacion_sesiones from public.solicitudes_eliminacion_cuenta) = 'hecha', 'session revocation recorded';
  perform pg_temp.expect($q$select public.app_actualizar_perfil('a0000000-0000-0000-0000-00000000000a','{"nombre":"Z"}'::jsonb,null)$q$, '42501', 'ACCOUNT_DELETION_PENDING');
  raise notice 'OK deletion request (idempotent, atomic, deferred execution, revocation status)';
end $$;

-- ---------------------------------------------------------------- privilege boundaries (real Supabase roles)
do $$
declare t text; p text;
begin
  -- service_role must NOT have any auth.users privilege (SERVICE_ROLE_AUTH_USERS_BROAD_SELECT = DENIED)
  perform pg_temp.expect('select count(*) from auth.users', '42501');
  assert not has_table_privilege('service_role','auth.users','select') and not has_any_column_privilege('service_role','auth.users','select'), 'service_role auth.users select';
  -- append-only + no delete/truncate for service_role
  perform pg_temp.expect('update public.aceptaciones_legales set plataforma = ''android''', '42501');
  perform pg_temp.expect('delete from public.aceptaciones_legales', '42501');
  perform pg_temp.expect('delete from public.solicitudes_eliminacion_cuenta', '42501');
  perform pg_temp.expect($q$insert into public.solicitudes_eliminacion_cuenta (usuario_id) values ('a0000000-0000-0000-0000-00000000000a')$q$, '23505');
end $$;
reset role;
do $$
begin
  assert (select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname in ('public','private') and (p.proname like 'app\_%' or p.proname like 'slice1\_%') and p.prosrc ilike '%auth.users%') = 0, 'no Slice1 function reads auth.users';
end $$;

-- anon / authenticated cannot execute RPCs nor touch the Slice 1 tables
do $$
declare r text; f text; t text;
begin
  foreach r in array array['anon','authenticated'] loop
    execute format('set local role %I', r);
    perform pg_temp.expect($q$select public.app_completar_onboarding('a0000000-0000-0000-0000-00000000000a','abcd','A','B',null,null,null,null,null)$q$, '42501');
    perform pg_temp.expect($q$select public.app_actualizar_perfil('a0000000-0000-0000-0000-00000000000a','{}'::jsonb,null)$q$, '42501');
    perform pg_temp.expect($q$select public.app_aceptar_documentos_legales('a0000000-0000-0000-0000-00000000000a', array[]::uuid[], 'ios')$q$, '42501');
    perform pg_temp.expect($q$select * from public.app_solicitar_eliminacion_cuenta('a0000000-0000-0000-0000-00000000000a')$q$, '42501');
    perform pg_temp.expect($q$select public.app_registrar_revocacion_sesiones('a0000000-0000-0000-0000-00000000000a','hecha')$q$, '42501');
    foreach t in array array['aceptaciones_legales','atribucion_usuario','perfiles_usuario_detalle','comunidades_objetivo_usuario','solicitudes_eliminacion_cuenta'] loop
      perform pg_temp.expect('select * from public.'||t, '42501');
      perform pg_temp.expect('insert into public.'||t||' select * from public.'||t, '42501');
    end loop;
    perform pg_temp.expect('select * from auth.users', '42501');
    reset role;
  end loop;
  raise notice 'OK anon/authenticated boundaries';
end $$;

-- authenticated client: server-owned profile columns closed, own editable columns still open
select set_config('request.jwt.claim.sub','a0000000-0000-0000-0000-00000000000f', true);
select set_config('request.jwt.claims','{"sub":"a0000000-0000-0000-0000-00000000000f","role":"authenticated"}', true);
set local role authenticated;
do $$
begin
  perform pg_temp.expect($q$update public.perfiles_usuario set onboarding_completado = true where usuario_id = 'a0000000-0000-0000-0000-00000000000f'$q$, '42501', 'PROFILE_FIELD_NOT_CLIENT_WRITABLE');
  perform pg_temp.expect($q$update public.perfiles_usuario set username = 'hacker' where usuario_id = 'a0000000-0000-0000-0000-00000000000f'$q$, '42501', 'PROFILE_FIELD_NOT_CLIENT_WRITABLE');
  perform pg_temp.expect($q$update public.perfiles_usuario set especialidad_principal_id = 'e1000000-0000-0000-0000-000000000001' where usuario_id = 'a0000000-0000-0000-0000-00000000000f'$q$, '42501', 'PROFILE_FIELD_NOT_CLIENT_WRITABLE');
  perform pg_temp.expect($q$update public.perfiles_usuario set estado_cuenta = 'bloqueada' where usuario_id = 'a0000000-0000-0000-0000-00000000000f'$q$, '42501', 'ESTADO_CUENTA_NOT_CLIENT_WRITABLE');
  perform pg_temp.expect($q$update public.perfiles_usuario set nombre_mostrar = 'Fefe', avatar_url = null where usuario_id = 'a0000000-0000-0000-0000-00000000000f'$q$, null);
  perform pg_temp.expect($q$select count(*) from public.documentos_legales$q$, null);
end $$;
reset role;

-- backstop (any writer): inactive specialty rejected; auth.users touch cannot repopulate a pending profile
do $$
begin
  perform pg_temp.expect($q$update public.perfiles_usuario set especialidad_principal_id = 'e1000000-0000-0000-0000-000000000003' where usuario_id = 'a0000000-0000-0000-0000-00000000000f'$q$, '22023', 'UNKNOWN_SPECIALTY');
  update public.perfiles_usuario set nombre_mostrar = null, username = null where usuario_id = 'a0000000-0000-0000-0000-00000000000c';
  update auth.users set email = 'tomb@fx.local', raw_user_meta_data = '{"username":"back","nombre":"Back"}' where id = 'a0000000-0000-0000-0000-00000000000c';
  assert (select nombre_mostrar is null and username is null from public.perfiles_usuario where usuario_id = 'a0000000-0000-0000-0000-00000000000c'), 'pending profile not repopulated by handle_new_auth_user';
  update auth.users set raw_user_meta_data = '{"username":"newname","nombre":"New"}' where id = 'a0000000-0000-0000-0000-00000000000f';
  -- DB-CI-PG17 (cambio deliberado de la copia local del 2026-09-29, anterior a 20260930100500): MASTER-DB-001 hizo el username
  -- server-owned, handle_new_auth_user ya NO lo copia de los metadatos (antes se afirmaba = 'newname', el comportamiento de MAIN).
  assert (select username from public.perfiles_usuario where usuario_id = 'a0000000-0000-0000-0000-00000000000f') is distinct from 'newname', 'username copied from auth metadata (MASTER-DB-001)';
  -- retention: hard delete of the user is impossible while acceptances/requests exist
  perform pg_temp.expect($q$delete from auth.users where id = 'a0000000-0000-0000-0000-00000000000a'$q$, '23503');
  raise notice 'OK client guards, backstop, handle_new_auth_user, NO ACTION retention';
end $$;

-- ---------------------------------------------------------------- grants / RLS matrix (DB-CI-PG17)
-- Los VERIFY de cada migracion se ejecutan en scripts/db-pg17-gate.sh justo despues de aplicarla; esto es el estado FINAL.
do $$
declare v text;
begin
  -- 1. RLS activa en TODA tabla de public/private (subconjunto MAIN + tablas Slice 0/1).
  select string_agg(c.oid::regclass::text, ', ') into v from pg_class c
   where c.relnamespace in ('public'::regnamespace, 'private'::regnamespace) and c.relkind in ('r', 'p') and not c.relrowsecurity;
  assert v is null, 'tables without RLS: ' || v;
  -- 2. Tablas de datos de cuenta/servidor: NINGUN privilegio para anon/authenticated/PUBLIC (solo Ktor via service_role).
  select string_agg(t || ' ' || r || ' ' || p, ', ') into v
    from unnest(array['public.solicitudes_eliminacion_cuenta', 'public.aceptaciones_legales', 'public.idempotency_keys',
                      'public.atribucion_usuario', 'public.perfiles_usuario_detalle', 'public.comunidades_objetivo_usuario',
                      'public.eventos_app', 'private.identity_backfill_report']) t,
         unnest(array['anon', 'authenticated', 'public']) r,
         unnest(array['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER']) p
   where case when r = 'public'
              then exists (select 1 from pg_class c, aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) a
                            where c.oid = t::regclass and a.grantee = 0 and a.privilege_type = p)
              else has_table_privilege(r, t, p) end;
  assert v is null, 'client privilege on server-only table: ' || v;
  -- 3. Toda funcion SECURITY DEFINER de public/private fija search_path.
  select string_agg(p.oid::regprocedure::text, ', ') into v from pg_proc p
   where p.pronamespace in ('public'::regnamespace, 'private'::regnamespace) and p.prosecdef
     and not exists (select 1 from unnest(coalesce(p.proconfig, '{}')) c where c like 'search_path=%');
  assert v is null, 'SECURITY DEFINER without search_path: ' || v;
  -- 4. Ninguna SECURITY DEFINER es ejecutable por anon/authenticated, salvo los dos helpers de RLS de MAIN que las politicas
  --    evaluan como authenticated (es_admin, es_moderador_o_admin: solo devuelven un booleano del propio auth.uid()).
  select string_agg(p.oid::regprocedure::text || ' ' || r, ', ') into v
    from pg_proc p, unnest(array['anon', 'authenticated']) r
   where p.pronamespace in ('public'::regnamespace, 'private'::regnamespace) and p.prosecdef
     and p.oid not in ('public.es_admin()'::regprocedure, 'public.es_moderador_o_admin()'::regprocedure)
     and has_function_privilege(r, p.oid, 'EXECUTE');
  assert v is null, 'client EXECUTE on SECURITY DEFINER: ' || v;
  -- 5. Deny-by-default con el camino de servidor abierto: service_role ejecuta las RPC de Slice 0/1.
  select string_agg(f, ', ') into v
    from unnest(array['public.app_completar_onboarding(uuid,text,text,text,uuid,uuid[],text,jsonb,text)',
                      'public.app_actualizar_perfil(uuid,jsonb,timestamp with time zone)',
                      'public.app_aceptar_documentos_legales(uuid,uuid[],text)', 'public.app_solicitar_eliminacion_cuenta(uuid)',
                      'public.app_registrar_revocacion_sesiones(uuid,text)', 'public.admin_sync_roles_audited(uuid,uuid[],uuid,uuid[])',
                      'public.admin_record_report_action(uuid,text,uuid,text,text)',
                      'public.idem_begin(uuid,text,text,text,interval,interval)']) f
   where to_regprocedure(f) is null or not has_function_privilege('service_role', f::regprocedure, 'EXECUTE');
  assert v is null, 'Slice 1 RPC missing or not executable by service_role: ' || v;
  -- 6. El esquema private no es alcanzable por los clientes.
  assert not has_schema_privilege('anon', 'private', 'USAGE') and not has_schema_privilege('authenticated', 'private', 'USAGE'),
    'client USAGE on schema private';
  raise notice 'OK grants/RLS matrix';
end $$;

rollback;
