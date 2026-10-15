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
-- MAIN crea el perfil desde auth.users: el trigger (y su funcion) tienen que estar en el bootstrap. Nombrarlo aqui lo
-- mete tambien en el cierre de scripts/db-pg17-bootstrap-extract.mjs (semillas = pendientes + este fichero).
do $$ begin
  assert exists (select 1 from pg_trigger t where t.tgrelid = 'auth.users'::regclass
                 and t.tgfoid = 'public.handle_new_auth_user()'::regprocedure and not t.tgisinternal), 'auth.users profile trigger present';
end $$;
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

-- ---------------------------------------------------------------- SLICE 3: exam runtime (20261008100000 + 20261008100100 + 20261008100200)
-- The commands asserted below are the bodies REDEFINED by 20261008100200 (hardening).
-- Content rows are seeded with the content triggers off (session_replication_role = replica): the closure of the CI
-- bootstrap does not carry every authoring helper those triggers call, and the authoring flow is not under test here.
-- Everything after the seed runs with triggers ON (preparar_intento_examen, validar_y_calcular_respuesta_intento, FKs).
set local session_replication_role = replica;
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en) values
  ('e3000000-0000-0000-0000-000000000001','S3-EX1','e1000000-0000-0000-0000-000000000001','c1000000-0000-0000-0000-000000000001','Slice 3','rafaga','gratuito',true,now() - interval '1 day'),
  ('e3000000-0000-0000-0000-000000000002','S3-EX2','e1000000-0000-0000-0000-000000000001','c1000000-0000-0000-0000-000000000001','Draft','rafaga','gratuito',false,null),
  ('e3000000-0000-0000-0000-000000000003','S3-EX3','e1000000-0000-0000-0000-000000000001','c1000000-0000-0000-0000-000000000001','Empty','rafaga','gratuito',true,now() - interval '1 day');
insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
select ('f3000000-0000-0000-0000-00000000000' || n)::uuid, 'S3-P' || n, 'rafaga', 'Pregunta ' || n, n <> 5
from generate_series(1, 6) n;
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
select ('0a300000-0000-0000-000' || n || '-00000000000' || o)::uuid, ('f3000000-0000-0000-0000-00000000000' || n)::uuid, chr(64 + o), 'Opcion', o
from generate_series(1, 6) n, generate_series(1, 2) o;
insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
select ('f3000000-0000-0000-0000-00000000000' || n)::uuid, ('0a300000-0000-0000-000' || n || '-000000000001')::uuid, 'porque si'
from generate_series(1, 6) n;
-- EX1: P1..P4 evaluable, P5 archived (activa = false), P6 annulled BEFORE the start.
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, anulada, motivo_anulacion)
select 'e3000000-0000-0000-0000-000000000001', ('f3000000-0000-0000-0000-00000000000' || n)::uuid, n * 10, n,
       n = 6, case when n = 6 then 'tribunal' end
from generate_series(1, 6) n;
insert into public.etiquetas (id, nombre, slug) values ('7a300000-0000-0000-0000-000000000001', 'Tema 1', 's3-tema-1');
insert into public.etiquetas_pregunta (pregunta_id, etiqueta_id)
select ('f3000000-0000-0000-0000-00000000000' || n)::uuid, '7a300000-0000-0000-0000-000000000001' from generate_series(1, 4) n;
set local session_replication_role = origin;

do $$
declare
  ua constant uuid := 'a0000000-0000-0000-0000-00000000000a';
  ub constant uuid := 'a0000000-0000-0000-0000-00000000000b';
  ex constant uuid := 'e3000000-0000-0000-0000-000000000001';
  p1 constant uuid := 'f3000000-0000-0000-0000-000000000001';
  p2 constant uuid := 'f3000000-0000-0000-0000-000000000002';
  p3 constant uuid := 'f3000000-0000-0000-0000-000000000003';
  p4 constant uuid := 'f3000000-0000-0000-0000-000000000004';
  ok1 constant uuid := '0a300000-0000-0000-0001-000000000001';
  ko2 constant uuid := '0a300000-0000-0000-0002-000000000002';
  ok3 constant uuid := '0a300000-0000-0000-0003-000000000001';
  r jsonb; a uuid; a2 uuid;
begin
  -- D2-03 start: published only, non-empty only, one open attempt. Frozen set: every ACTIVE question, annulled ones included
  -- (20261010100000; never evaluable), each bound to its immutable revision (20261016100000). Archived ones stay out.
  assert public.app_start_exam_attempt(ua, 'e3000000-0000-0000-0000-000000000002') ->> 'outcome' = 'EXAM_NOT_FOUND', 'draft exam is not startable';
  assert public.app_start_exam_attempt(ua, gen_random_uuid()) ->> 'outcome' = 'EXAM_NOT_FOUND', 'unknown exam';
  assert public.app_start_exam_attempt(ua, 'e3000000-0000-0000-0000-000000000003') ->> 'outcome' = 'EXAM_EMPTY', 'exam without questions';
  r := public.app_start_exam_attempt(ua, ex);
  assert r ->> 'outcome' = 'CREATED', 'start: ' || r;
  a := (r ->> 'intento_id')::uuid;
  r := public.app_start_exam_attempt(ua, ex);
  assert r ->> 'outcome' = 'RESUMED' and (r ->> 'intento_id')::uuid = a, 'second start returns the open attempt: ' || r;
  assert (select count(*) from public.intentos_examen where usuario_id = ua and examen_id = ex and estado = 'en_progreso') = 1, 'one open attempt';
  assert (select array_agg(pregunta_id order by posicion) from public.preguntas_intento where intento_id = a) = array[p1, p2, p3, p4, 'f3000000-0000-0000-0000-000000000006'::uuid],
    'frozen set = every active question (annulled included), in exam order';
  assert not exists (select 1 from public.preguntas_intento where intento_id = a and revision_pregunta_id is null), 'every frozen question bound to a revision';
  assert (select array_agg(posicion order by posicion) from public.preguntas_intento where intento_id = a) = array[1, 2, 3, 4, 5], 'positions renumbered 1..n';
  assert (select total_preguntas = 4 and especialidad_snapshot_id = 'e1000000-0000-0000-0000-000000000001' and tipo_examen_snapshot = 'rafaga'
            and intento_numero_usuario = 1 and motivo_fin is null from public.intentos_examen where id = a), 'attempt row at start';
  perform pg_temp.expect(format($q$insert into public.intentos_examen (usuario_id, examen_id) values (%L, %L)$q$, ua, ex), '23505');
  perform pg_temp.expect(format($q$update public.intentos_examen set especialidad_snapshot_id = 'e1000000-0000-0000-0000-000000000002' where id = %L$q$, a),
    'P0001', 'INTENTO_ESPECIALIDAD_SNAPSHOT_INMUTABLE');

  -- D2-09 answers: owner only, frozen set only, skip, revision, lock, first response.
  assert public.app_record_attempt_answer(ub, a, p1, ok1, 100, 0, null) ->> 'outcome' = 'ATTEMPT_NOT_FOUND', 'another user''s attempt';
  assert public.app_record_attempt_answer(ua, a, 'f3000000-0000-0000-0000-000000000005', null, 0, 0, null) ->> 'outcome' = 'QUESTION_NOT_IN_ATTEMPT', 'archived question is not in the set';
  assert public.app_record_attempt_answer(ua, a, 'f3000000-0000-0000-0000-000000000006', null, 0, 0, null) ->> 'estado' = 'ANNULLED', 'annulled-before-start question is in the set and stores nothing';
  assert public.app_record_attempt_answer(ua, a, p1, ko2, 0, 0, null) ->> 'outcome' = 'OPTION_NOT_IN_QUESTION', 'option of another question';
  assert public.app_record_attempt_answer(ua, a, p1, ok1, 0, 3, null) = '{"outcome":"REVISION_CONFLICT","current_revision":0}'::jsonb, 'stale base revision';
  r := public.app_record_attempt_answer(ua, a, p1, ok1, 999999999, 0, 2);
  assert r = jsonb_build_object('outcome', 'OK', 'estado', 'ANSWERED', 'revision', 1, 'es_primera_respuesta', true, 'respondidas', 1,
                               'es_correcta', true, 'opcion_correcta_id', ok1, 'noop', false), 'first answer (with its DEC-015 reveal): ' || r;
  assert (select es_correcta and primera_es_correcta and primera_opcion_id = ok1 and revision = 1 and actualizada_en is not null
            and tiempo_respuesta_ms < 60000 and (metadatos ->> 'tiempoRecortado')::boolean and (metadatos ->> 'nivelConfianza')::int = 2
            and posicion_pregunta = 1 and es_primer_intento_pregunta
          from public.respuestas_intento where intento_id = a and pregunta_id = p1), 'stored answer (client time clamped to the server gap)';
  assert public.app_record_attempt_answer(ua, a, p1, ok1, 5, 0, null) ->> 'noop' = 'true', 'same option again is a no-op';
  assert public.app_record_attempt_answer(ua, a, p1, null, 5, 1, null) ->> 'outcome' = 'ANSWER_LOCKED', 'an answer is not cleared';
  r := public.app_record_attempt_answer(ua, a, p2, null, 0, 0, null);
  assert r ->> 'estado' = 'SKIPPED' and (r ->> 'revision')::int = 1 and r ->> 'es_primera_respuesta' = 'false' and (r ->> 'respondidas')::int = 1, 'skip: ' || r;
  assert public.app_record_attempt_answer(ua, a, p2, ko2, 0, 0, null) = '{"outcome":"REVISION_CONFLICT","current_revision":1}'::jsonb, 'answering a skip needs its revision';
  r := public.app_record_attempt_answer(ua, a, p2, ko2, 0, 1, null);
  assert r ->> 'estado' = 'ANSWERED' and (r ->> 'revision')::int = 2 and r ->> 'es_primera_respuesta' = 'true' and (r ->> 'respondidas')::int = 2, 'skip then answer: ' || r;
  assert (select primera_opcion_id is null and primera_es_correcta is null and opcion_seleccionada_id = ko2 and es_correcta = false and cambio_respuesta
          from public.respuestas_intento where intento_id = a and pregunta_id = p2), 'the first submission (a skip) is immutable';
  -- annulled between start and finish: nothing stored, not evaluable.
  update public.preguntas_examen set anulada = true, motivo_anulacion = 'tribunal' where examen_id = ex and pregunta_id = p3;
  r := public.app_record_attempt_answer(ua, a, p3, ok3, 0, 0, null);
  assert r ->> 'estado' = 'ANNULLED' and (r ->> 'revision')::int = 0 and (r ->> 'respondidas')::int = 2, 'annulled question: ' || r;
  assert not exists (select 1 from public.respuestas_intento where intento_id = a and pregunta_id = p3), 'annulled answer is not stored';
  assert (select total_respondidas = 2 and ultima_pregunta_posicion = 2 from public.intentos_examen where id = a), 'live progress on the attempt';

  -- D2-10 finish: totals over evaluable questions, frozen statuses, tag summary, idempotent.
  assert public.app_finish_exam_attempt(ub, a) ->> 'outcome' = 'ATTEMPT_NOT_FOUND', 'another user cannot finish';
  assert public.app_finish_exam_attempt(ua, a) = '{"outcome":"OK","already":false}'::jsonb, 'finish';
  assert (select array_agg(estado_final order by posicion) from public.preguntas_intento where intento_id = a) = array['CORRECT', 'WRONG', 'ANNULLED', 'UNANSWERED', 'ANNULLED'],
    'statuses frozen at finish';
  assert (select estado = 'finalizado' and motivo_fin = 'USER' and finalizado_en is not null and duracion_segundos >= 0 and total_preguntas = 4
            and total_respondidas = 2 and total_correctas = 1 and total_falladas = 1 and total_sin_responder = 1 and porcentaje_acierto = 50
          from public.intentos_examen where id = a), 'totals (the annulled question is in none of them)';
  assert (select total_preguntas = 3 and correctas = 1 and falladas = 1 and sin_responder = 1 from public.resumen_intento_etiqueta where intento_id = a), 'tag summary';
  update public.preguntas_examen set anulada = false, motivo_anulacion = null where examen_id = ex and pregunta_id = p3;
  assert public.app_finish_exam_attempt(ua, a) = '{"outcome":"OK","already":true}'::jsonb, 'finish is idempotent';
  assert (select array_agg(estado_final order by posicion) from public.preguntas_intento where intento_id = a) = array['CORRECT', 'WRONG', 'ANNULLED', 'UNANSWERED', 'ANNULLED'],
    'a later annulment change does not rewrite the frozen result';
  assert public.app_record_attempt_answer(ua, a, p4, null, 0, 0, null) = '{"outcome":"ATTEMPT_NOT_IN_PROGRESS","estado":"finalizado"}'::jsonb, 'no answer after finish';
  assert public.app_abandon_exam_attempt(ua, a, 1, 'salida_usuario') ->> 'outcome' = 'ATTEMPT_ALREADY_FINISHED', 'no abandon after finish';

  -- retake + D2-11 abandon.
  r := public.app_start_exam_attempt(ua, ex);
  assert r ->> 'outcome' = 'CREATED' and (r ->> 'intento_id')::uuid <> a, 'a finished attempt does not block a retake: ' || r;
  a2 := (r ->> 'intento_id')::uuid;
  assert (select intento_numero_usuario = 2 and not es_primer_intento_examen from public.intentos_examen where id = a2), 'retake numbering';
  r := public.app_record_attempt_answer(ua, a2, p1, ok1, 0, 0, null);
  assert r ->> 'es_primera_respuesta' = 'false', 'a retake answer is not the user''s first response: ' || r;
  assert public.app_abandon_exam_attempt(ua, a2, 9, 'salida_usuario') ->> 'outcome' = 'INVALID_POSITION', 'position beyond the attempt';
  assert public.app_abandon_exam_attempt(ua, a2, 2, 'inactividad') ->> 'outcome' = 'INVALID_REASON', 'server-only reason';
  assert public.app_abandon_exam_attempt(ub, a2, 2, 'otro') ->> 'outcome' = 'ATTEMPT_NOT_FOUND', 'another user cannot abandon';
  assert public.app_abandon_exam_attempt(ua, a2, 2, 'salida_usuario') = '{"outcome":"OK","already":false}'::jsonb, 'abandon';
  assert public.app_abandon_exam_attempt(ua, a2, 2, 'salida_usuario') = '{"outcome":"OK","already":true}'::jsonb, 'abandon is idempotent';
  assert (select count(*) = 1 and min(motivo_detectado) = 'cerrar_examen' and min(posicion_pregunta) = 2 and min(pregunta_id::text) = p2::text
            and min(tiempo_transcurrido_segundos) >= 0 from public.abandonos_examen where intento_id = a2), 'exactly one abandonos_examen row';
  assert (select estado = 'abandonado' and abandonado_en_posicion = 2 and abandonado_en_pregunta_id = p2 from public.intentos_examen where id = a2), 'attempt abandoned';
  assert public.app_finish_exam_attempt(ua, a2) ->> 'outcome' = 'ATTEMPT_ABANDONED', 'no finish after abandon';
  raise notice 'OK slice 3 exam runtime (start, answer, finish, abandon)';
end $$;

-- SLICE 3 hardening (20261008100200, gate review F-01 / F-05 / F-06): an attempt that app_start_exam_attempt did not
-- create does not exist for any command and is never frozen; the commands serve origen_intento = 'examen' only; the
-- marker is immutable; the two FKs of 20261008100000 have their index.
do $$
declare
  ua constant uuid := 'a0000000-0000-0000-0000-00000000000a';
  ub constant uuid := 'a0000000-0000-0000-0000-00000000000b';
  ex constant uuid := 'e3000000-0000-0000-0000-000000000001';
  p1 constant uuid := 'f3000000-0000-0000-0000-000000000001';
  ok1 constant uuid := '0a300000-0000-0000-0001-000000000001';
  legacy constant uuid := '1e9ac100-0000-4000-8000-000000000001';
  repaso constant uuid := '4e9a5000-0000-4000-8000-000000000001';
  r jsonb; a uuid;
begin
  assert (select count(*) = 2 and bool_and(creado_por_comando = 'app_start_exam_attempt')
          from public.intentos_examen where usuario_id = ua and examen_id = ex), 'every attempt of the start command carries the marker';

  -- what the retired legacy start did: the bare row, no entitlement check, no frozen set.
  insert into public.intentos_examen (id, usuario_id, examen_id, total_preguntas) values (legacy, ub, ex, 4);
  assert (select creado_por_comando is null from public.intentos_examen where id = legacy), 'a direct insert has no marker';
  assert public.app_record_attempt_answer(ub, legacy, p1, ok1, 0, 0, null) = '{"outcome":"ATTEMPT_NOT_FOUND"}'::jsonb, 'unmarked attempt: answer';
  assert public.app_record_attempt_answer(ub, legacy, p1, null, 0, 0, null) = '{"outcome":"ATTEMPT_NOT_FOUND"}'::jsonb, 'unmarked attempt: skip';
  assert public.app_finish_exam_attempt(ub, legacy) = '{"outcome":"ATTEMPT_NOT_FOUND"}'::jsonb, 'unmarked attempt: finish';
  assert public.app_abandon_exam_attempt(ub, legacy, 1, 'salida_usuario') = '{"outcome":"ATTEMPT_NOT_FOUND"}'::jsonb, 'unmarked attempt: abandon';
  assert not exists (select 1 from public.preguntas_intento where intento_id = legacy), 'no late freeze';
  assert not exists (select 1 from public.respuestas_intento where intento_id = legacy), 'nothing stored for an unmarked attempt';
  assert (select estado = 'en_progreso' and finalizado_en is null from public.intentos_examen where id = legacy), 'a refused command does not touch the row';

  -- the marker is written on INSERT by the start command only.
  perform pg_temp.expect(format($q$update public.intentos_examen set creado_por_comando = 'app_start_exam_attempt' where id = %L$q$, legacy),
    'P0001', 'INTENTO_CREADO_POR_COMANDO_INMUTABLE');
  perform pg_temp.expect(format($q$update public.intentos_examen set creado_por_comando = null where usuario_id = %L and examen_id = %L$q$, ua, ex),
    'P0001', 'INTENTO_CREADO_POR_COMANDO_INMUTABLE');
  perform pg_temp.expect(format($q$insert into public.intentos_examen (usuario_id, examen_id, estado, creado_por_comando) values (%L, %L, 'abandonado', 'otro')$q$, ub, ex), '23514');

  -- the canonical start never resumes it: it closes it (kept, never deleted) and creates a marked attempt with its set.
  r := public.app_start_exam_attempt(ub, ex);
  assert r ->> 'outcome' = 'CREATED' and (r ->> 'intento_id')::uuid <> legacy, 'start does not resume an unmarked attempt: ' || r;
  a := (r ->> 'intento_id')::uuid;
  assert (select estado = 'abandonado' and creado_por_comando is null and metadatos ->> 'motivo' = 'intento_sin_marcador_de_comando'
            and metadatos ->> 'cerradoPor' = 'app_start_exam_attempt' from public.intentos_examen where id = legacy), 'the unmarked open attempt is closed';
  assert not exists (select 1 from public.preguntas_intento where intento_id = legacy), 'and still has no frozen set';
  assert (select creado_por_comando = 'app_start_exam_attempt' and origen_intento = 'examen' from public.intentos_examen where id = a), 'new attempt is marked';
  assert (select count(*) from public.preguntas_intento where intento_id = a) = 5, 'new attempt frozen at start (annulled question included, 20261010100000)';
  assert (select count(*) from public.intentos_examen where usuario_id = ub and examen_id = ex and estado = 'en_progreso') = 1, 'still one open attempt';
  r := public.app_start_exam_attempt(ub, ex);
  assert r ->> 'outcome' = 'RESUMED' and (r ->> 'intento_id')::uuid = a, 'a marked attempt is resumed: ' || r;
  assert public.app_finish_exam_attempt(ub, legacy) ->> 'outcome' = 'ATTEMPT_NOT_FOUND', 'closed, it still does not exist for the commands';

  -- F-06: a non-exam attempt is not served by the exam commands (even if it carried the marker).
  insert into public.intentos_examen (id, usuario_id, examen_id, origen_intento, creado_por_comando)
  values (repaso, ua, null, 'repaso_random', 'app_start_exam_attempt');
  assert public.app_record_attempt_answer(ua, repaso, p1, ok1, 0, 0, null) ->> 'outcome' = 'ATTEMPT_NOT_FOUND', 'repaso attempt: answer';
  assert public.app_finish_exam_attempt(ua, repaso) ->> 'outcome' = 'ATTEMPT_NOT_FOUND', 'repaso attempt: finish';
  assert public.app_abandon_exam_attempt(ua, repaso, 1, 'otro') ->> 'outcome' = 'ATTEMPT_NOT_FOUND', 'repaso attempt: abandon';
  assert (select estado = 'en_progreso' from public.intentos_examen where id = repaso), 'repaso attempt untouched';

  -- F-05: the two FKs added by 20261008100000 are indexed (partial, exact definition).
  assert (select count(*) from pg_index i
           where i.indisvalid and i.indisready and i.indpred is not null
             and i.indexrelid in (to_regclass('public.idx_respuestas_intento_primera_opcion_id'),
                                  to_regclass('public.idx_intentos_examen_especialidad_snapshot_id'))) = 2, 'FK indexes';
  raise notice 'OK slice 3 hardening (command marker, no late freeze, exam origin only, FK indexes)';
end $$;

-- ---------------------------------------------------------------- SLICE 2: "my exams" collection read (20261008100300, D2-13 vista = COLECCION)
-- Collection of a user = published exams with a VALID grant or with an attempt created by the start command; one item
-- per exam; order (orden_en desc, id desc); groups per comunidad over the whole filtered set. Users C and D have no
-- other grant or attempt. Rows are seeded with triggers off so that every instant is the one written here.
set local session_replication_role = replica;
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, anio) values
  ('52000000-0000-0000-0000-000000000001','S2-X1','e1000000-0000-0000-0000-000000000001','c1000000-0000-0000-0000-000000000001','X1','rafaga','premium',true,now() - interval '9 day',2024),
  ('52000000-0000-0000-0000-000000000002','S2-X2','e1000000-0000-0000-0000-000000000001','c1000000-0000-0000-0000-000000000001','X2','rafaga','premium',true,now() - interval '9 day',2023),
  ('52000000-0000-0000-0000-000000000003','S2-X3','e1000000-0000-0000-0000-000000000001','c1000000-0000-0000-0000-000000000002','X3','razonado','premium',true,now() - interval '9 day',2024),
  ('52000000-0000-0000-0000-000000000004','S2-X4','e1000000-0000-0000-0000-000000000001','c1000000-0000-0000-0000-000000000001','X4 draft','rafaga','premium',false,null,2024),
  ('52000000-0000-0000-0000-000000000005','S2-X5','e1000000-0000-0000-0000-000000000001','c1000000-0000-0000-0000-000000000001','X5 expired grant','rafaga','premium',true,now() - interval '9 day',2024),
  ('52000000-0000-0000-0000-000000000006','S2-X6','e1000000-0000-0000-0000-000000000001','c1000000-0000-0000-0000-000000000001','X6 legacy attempt','rafaga','premium',true,now() - interval '9 day',2024),
  ('52000000-0000-0000-0000-000000000007','S2-X7','e1000000-0000-0000-0000-000000000001','c1000000-0000-0000-0000-000000000002','X7 other user','rafaga','premium',true,now() - interval '9 day',2024),
  ('52000000-0000-0000-0000-000000000008','S2-X8','e1000000-0000-0000-0000-000000000001','c1000000-0000-0000-0000-000000000001','X8 scheduled','rafaga','premium',true,now() + interval '1 day',2024),
  ('52000000-0000-0000-0000-000000000009','S2-X9','e1000000-0000-0000-0000-000000000001','c1000000-0000-0000-0000-000000000001','X9 archived','rafaga','premium',false,now() - interval '9 day',2024);
insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values
  ('a0000000-0000-0000-0000-00000000000c','52000000-0000-0000-0000-000000000001','compra',now() - interval '3 day',null),
  ('a0000000-0000-0000-0000-00000000000c','52000000-0000-0000-0000-000000000001','regalo',now() - interval '8 day',now() + interval '1 day'),
  ('a0000000-0000-0000-0000-00000000000c','52000000-0000-0000-0000-000000000003','admin',now() - interval '5 day',null),
  ('a0000000-0000-0000-0000-00000000000c','52000000-0000-0000-0000-000000000004','compra',now() - interval '1 hour',null),
  ('a0000000-0000-0000-0000-00000000000c','52000000-0000-0000-0000-000000000005','compra',now() - interval '4 day',now() - interval '1 minute'),
  ('a0000000-0000-0000-0000-00000000000c','52000000-0000-0000-0000-000000000005','promocion',now() + interval '1 day',null),
  ('a0000000-0000-0000-0000-00000000000c','52000000-0000-0000-0000-000000000008','compra',now() - interval '1 hour',null),
  ('a0000000-0000-0000-0000-00000000000c','52000000-0000-0000-0000-000000000009','compra',now() - interval '1 hour',null),
  ('a0000000-0000-0000-0000-00000000000d','52000000-0000-0000-0000-000000000007','compra',now() - interval '1 hour',null);
insert into public.intentos_examen (id, usuario_id, examen_id, estado, origen_intento, creado_por_comando, total_preguntas, total_respondidas, actualizado_en) values
  ('52a00000-0000-0000-0000-000000000001','a0000000-0000-0000-0000-00000000000c','52000000-0000-0000-0000-000000000002','abandonado','examen','app_start_exam_attempt',10,2,now() - interval '6 day'),
  ('52a00000-0000-0000-0000-000000000002','a0000000-0000-0000-0000-00000000000c','52000000-0000-0000-0000-000000000002','finalizado','examen','app_start_exam_attempt',10,10,now() - interval '1 day'),
  ('52a00000-0000-0000-0000-000000000003','a0000000-0000-0000-0000-00000000000c','52000000-0000-0000-0000-000000000003','en_progreso','examen','app_start_exam_attempt',8,4,now() - interval '2 hour'),
  ('52a00000-0000-0000-0000-000000000004','a0000000-0000-0000-0000-00000000000c','52000000-0000-0000-0000-000000000006','finalizado','examen',null,10,10,now() - interval '1 minute'),
  ('52a00000-0000-0000-0000-000000000005','a0000000-0000-0000-0000-00000000000c','52000000-0000-0000-0000-000000000004','finalizado','examen','app_start_exam_attempt',10,10,now() - interval '1 minute'),
  ('52a00000-0000-0000-0000-000000000006','a0000000-0000-0000-0000-00000000000d','52000000-0000-0000-0000-000000000007','en_progreso','examen','app_start_exam_attempt',5,1,now() - interval '1 minute'),
  ('52a00000-0000-0000-0000-000000000007','a0000000-0000-0000-0000-00000000000d','52000000-0000-0000-0000-000000000001','finalizado','examen','app_start_exam_attempt',5,5,now() - interval '1 minute');
set local session_replication_role = origin;

create function pg_temp.titulos(p jsonb) returns text language sql as $$
  select coalesce(string_agg(i -> 'examenes' ->> 'titulo', ',' order by n), '')
  from jsonb_array_elements(p -> 'items') with ordinality t(i, n)
$$;

do $$
declare
  uc constant uuid := 'a0000000-0000-0000-0000-00000000000c';
  ud constant uuid := 'a0000000-0000-0000-0000-00000000000d';
  c2 constant uuid := 'c1000000-0000-0000-0000-000000000002';
  r jsonb; r2 jsonb; i jsonb;
begin
  -- Union, published only, valid grants only, command attempts only, own rows only; newest activity first.
  r := public.app_my_exams_collection(uc, null, null, null, null, null, 21, true);
  assert pg_temp.titulos(r) = 'X3,X2,X1',
    'my exams collection = valid grant or command attempt, published only: ' || pg_temp.titulos(r);
  assert r -> 'grupos' = '[{"comunidad_codigo":"C1","total":2},{"comunidad_codigo":"C2","total":1}]'::jsonb, 'groups over the whole set: ' || (r -> 'grupos');
  -- X3: granted AND attempted -> ONE item, ordered by its attempt (newer than its grant), carrying the open attempt.
  i := r -> 'items' -> 0;
  assert (i ->> 'orden_en')::timestamptz = now() - interval '2 hour'
     and i -> 'intento' = '{"id":"52a00000-0000-0000-0000-000000000003","estado":"en_progreso","total_respondidas":4,"total_preguntas":8}'::jsonb,
    'granted and attempted exam: ' || i;
  -- X2: attempted only (no grant) -> listed with its most recently updated attempt.
  i := r -> 'items' -> 1;
  assert i -> 'intento' ->> 'id' = '52a00000-0000-0000-0000-000000000002' and i -> 'intento' ->> 'estado' = 'finalizado', 'latest attempt: ' || i;
  -- X1: granted only -> no attempt; the other user's attempt on X1 is not offered. Latest valid grant is the key.
  i := r -> 'items' -> 2;
  assert i -> 'intento' = 'null'::jsonb and (i ->> 'orden_en')::timestamptz = now() - interval '3 day', 'granted only: ' || i;
  -- Exam METADATA only, exactly these keys (no question content, no score).
  assert (select array_agg(k order by k) from jsonb_object_keys(i) k) = array['examenes', 'intento', 'orden_en']
     and (select array_agg(k order by k) from jsonb_object_keys(i -> 'examenes') k)
         = array['comunidades_autonomas', 'demo_disponible', 'especialidad_id', 'id', 'portada_storage_path', 'tipo_acceso', 'tipo_examen', 'titulo'],
    'item shape: ' || i;

  -- Keyset: page of 1 + the look-ahead, then continue after the last returned key. No duplicate, no gap.
  r := public.app_my_exams_collection(uc, null, null, null, null, null, 1, false);
  assert pg_temp.titulos(r) = 'X3' and r -> 'grupos' = 'null'::jsonb, 'first page of one, groups only on request: ' || r;
  r2 := public.app_my_exams_collection(uc, null, null, null, (r -> 'items' -> 0 ->> 'orden_en')::timestamptz,
                                       (r -> 'items' -> 0 -> 'examenes' ->> 'id')::uuid, 21, false);
  assert pg_temp.titulos(r2) = 'X2,X1', 'page after the cursor: ' || pg_temp.titulos(r2);
  -- The limit is bounded inside the function.
  assert jsonb_array_length(public.app_my_exams_collection(uc, null, null, null, null, null, 0, false) -> 'items') = 1, 'limit below 1 is 1';
  assert jsonb_array_length(public.app_my_exams_collection(uc, null, null, null, null, null, 100000, false) -> 'items') = 3, 'huge limit is clamped';
  perform pg_temp.expect(format($q$select public.app_my_exams_collection(%L, null, null, null, now(), null, 21, false)$q$, uc), '22023', 'APP_MY_EXAMS_CURSOR_INCOMPLETE');

  -- Filters narrow the items AND the groups.
  r := public.app_my_exams_collection(uc, null, c2, null, null, null, 21, true);
  assert pg_temp.titulos(r) = 'X3' and r -> 'grupos' = '[{"comunidad_codigo":"C2","total":1}]'::jsonb, 'comunidad filter: ' || r;
  assert pg_temp.titulos(public.app_my_exams_collection(uc, 'rafaga', null, 2024, null, null, 21, false)) = 'X1', 'tipo + anio filter';
  assert public.app_my_exams_collection(uc, 'inmersivo', null, null, null, null, 21, true) = '{"items":[],"grupos":[]}'::jsonb, 'empty collection';

  -- Another user sees only its own rows; nobody (null) sees nothing.
  assert pg_temp.titulos(public.app_my_exams_collection(ud, null, null, null, null, null, 21, false)) = 'X7 other user,X1',
    'other user: ' || pg_temp.titulos(public.app_my_exams_collection(ud, null, null, null, null, null, 21, false));
  assert public.app_my_exams_collection(null, null, null, null, null, null, 21, true) = '{"items":[],"grupos":[]}'::jsonb, 'null user';
  -- Read only: nothing was written by any of the calls above.
  assert (select count(*) from public.contenido_desbloqueado_usuario) = 9 and (select count(*) from public.intentos_examen where id::text like '52a%') = 7, 'read only';
  raise notice 'OK slice 2 my exams collection (union, published only, own rows, keyset, groups)';
end $$;

-- clients cannot reach the Slice 3 commands nor write attempts directly
set local role authenticated;
select set_config('request.jwt.claim.sub', 'a0000000-0000-0000-0000-00000000000a', true);
do $$
begin
  perform pg_temp.expect($q$select public.app_start_exam_attempt('a0000000-0000-0000-0000-00000000000a', 'e3000000-0000-0000-0000-000000000001')$q$, '42501');
  perform pg_temp.expect($q$select public.app_finish_exam_attempt('a0000000-0000-0000-0000-00000000000a', gen_random_uuid())$q$, '42501');
  perform pg_temp.expect($q$select public.app_abandon_exam_attempt('a0000000-0000-0000-0000-00000000000a', gen_random_uuid(), 1, 'otro')$q$, '42501');
  perform pg_temp.expect($q$select public.app_record_attempt_answer('a0000000-0000-0000-0000-00000000000a', gen_random_uuid(), gen_random_uuid(), null, 0, 0, null)$q$, '42501');
  perform pg_temp.expect($q$select private.app_congelar_preguntas_intento(gen_random_uuid(), gen_random_uuid())$q$, '42501');
  -- 20261008100200: a client can neither forge the command marker nor write an attempt at all.
  perform pg_temp.expect($q$insert into public.intentos_examen (usuario_id, examen_id, creado_por_comando) values ('a0000000-0000-0000-0000-00000000000a', 'e3000000-0000-0000-0000-000000000001', 'app_start_exam_attempt')$q$, '42501');
  perform pg_temp.expect($q$update public.intentos_examen set creado_por_comando = 'app_start_exam_attempt' where usuario_id = 'a0000000-0000-0000-0000-00000000000a'$q$, '42501');
  perform pg_temp.expect($q$select private.tg_intentos_examen_creado_por_comando_inmutable()$q$, '42501');
  -- SLICE 2 (20261008100300): the collection read is server-only too, even for the caller's own id.
  perform pg_temp.expect($q$select public.app_my_exams_collection('a0000000-0000-0000-0000-00000000000a', null, null, null, null, null, 21, true)$q$, '42501');
  raise notice 'OK slice 3 RPCs closed to clients';
end $$;
reset role;
set local role anon;
do $$
begin
  perform pg_temp.expect($q$select public.app_my_exams_collection('a0000000-0000-0000-0000-00000000000c', null, null, null, null, null, 21, true)$q$, '42501');
  raise notice 'OK slice 2 collection read closed to anon';
end $$;
reset role;
-- ...and the server path is open: service_role reads the collection of the user Ktor names.
set local role service_role;
do $$
begin
  assert jsonb_array_length(public.app_my_exams_collection('a0000000-0000-0000-0000-00000000000c', null, null, null, null, null, 21, false) -> 'items') = 3,
    'service_role reads the collection';
  raise notice 'OK slice 2 collection read open to service_role';
end $$;
reset role;

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
                      'public.idem_begin(uuid,text,text,text,interval,interval)',
                      -- SLICE 3 (20261008100100, redefined by 20261008100200)
                      'public.app_start_exam_attempt(uuid,uuid)',
                      'public.app_record_attempt_answer(uuid,uuid,uuid,uuid,integer,integer,integer)',
                      'public.app_finish_exam_attempt(uuid,uuid)', 'public.app_abandon_exam_attempt(uuid,uuid,integer,text)',
                      -- SLICE 2 (20261008100300)
                      'public.app_my_exams_collection(uuid,text,uuid,integer,timestamp with time zone,uuid,integer,boolean)']) f
   where to_regprocedure(f) is null or not has_function_privilege('service_role', f::regprocedure, 'EXECUTE');
  assert v is null, 'Slice 1 / Slice 2 / Slice 3 RPC missing or not executable by service_role: ' || v;
  -- 5b. SLICE 3: ninguna politica RLS deja a un cliente (PUBLIC, anon, authenticated) ESCRIBIR en las tablas de intento
  --     (el camino de escritura es Ktor con service_role; las politicas de MAIN solo dejan SELECT de lo propio).
  select string_agg(t || ' ' || p, ', ') into v
    from unnest(array['public.intentos_examen', 'public.respuestas_intento', 'public.preguntas_intento']) t,
         unnest(array['INSERT', 'UPDATE', 'DELETE']) p
   where exists (select 1 from pg_policy pol
                  where pol.polrelid = t::regclass and pol.polcmd in ('*', case p when 'INSERT' then 'a' when 'UPDATE' then 'w' else 'd' end)
                    and pol.polroles && array[0::oid, 'anon'::regrole::oid, 'authenticated'::regrole::oid]);
  assert v is null, 'client write policy on an attempt table: ' || v;
  -- 6. El esquema private no es alcanzable por los clientes.
  assert not has_schema_privilege('anon', 'private', 'USAGE') and not has_schema_privilege('authenticated', 'private', 'USAGE'),
    'client USAGE on schema private';
  raise notice 'OK grants/RLS matrix';
end $$;

rollback;

-- 20261008100400: un admin con JWT de usuario ya no escribe concesiones de contenido por la Data API; lee las suyas.
do $$
begin
  assert not has_table_privilege('authenticated', 'public.contenido_desbloqueado_usuario', 'INSERT'), 'authenticated conserva INSERT en contenido_desbloqueado_usuario';
  assert not has_table_privilege('authenticated', 'public.contenido_desbloqueado_usuario', 'UPDATE'), 'authenticated conserva UPDATE en contenido_desbloqueado_usuario';
  assert not has_table_privilege('authenticated', 'public.contenido_desbloqueado_usuario', 'DELETE'), 'authenticated conserva DELETE en contenido_desbloqueado_usuario';
  assert not has_table_privilege('anon', 'public.contenido_desbloqueado_usuario', 'INSERT'), 'anon tiene INSERT en contenido_desbloqueado_usuario';
  assert has_table_privilege('authenticated', 'public.contenido_desbloqueado_usuario', 'SELECT'), 'authenticated perdio SELECT en contenido_desbloqueado_usuario';
  assert has_table_privilege('service_role', 'public.contenido_desbloqueado_usuario', 'INSERT'), 'service_role perdio INSERT en contenido_desbloqueado_usuario';
  assert not exists (select 1 from pg_policy p where p.polrelid = 'public.contenido_desbloqueado_usuario'::regclass and p.polpermissive and p.polcmd in ('a', 'w', 'd', '*')), 'queda una politica permisiva de escritura en contenido_desbloqueado_usuario';
  assert exists (select 1 from pg_policy p where p.polrelid = 'public.contenido_desbloqueado_usuario'::regclass and p.polname = 'contenido_desbloqueado_select_propio' and p.polcmd = 'r'), 'falta contenido_desbloqueado_select_propio';
end;
$$;

-- 20261018100000: V1, un examen lleva comunidad (INSERT, poner NULL y publicar sin ella se rechazan; los heredados siguen editables).
begin;
create function pg_temp.expect_c(p_sql text, p_msg text) returns void language plpgsql as $$
declare v_state text; v_msg text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_state = returned_sqlstate, v_msg = message_text; end;
  if v_state is distinct from '23514' or v_msg is distinct from p_msg then
    raise exception 'ASSERT FAILED: % -> expected 23514/% got %/%', p_sql, p_msg, v_state, v_msg;
  end if;
end $$;
insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-0000000000c1','EC','EC','ec',true);
insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('c1000000-0000-0000-0000-0000000000c1','CC','Com','com');
-- 1. INSERT sin comunidad.
select pg_temp.expect_c($q$insert into public.examenes (id, codigo_examen, especialidad_id, titulo, tipo_examen, tipo_acceso) values ('e4000000-0000-0000-0000-000000000001','C-NO','e1000000-0000-0000-0000-0000000000c1','x','rafaga','gratuito')$q$, 'EXAM_COMMUNITY_REQUIRED');
-- 2. INSERT con comunidad y borrador: vale.
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso) values ('e4000000-0000-0000-0000-000000000002','C-OK','e1000000-0000-0000-0000-0000000000c1','c1000000-0000-0000-0000-0000000000c1','x','rafaga','gratuito');
-- 3. Poner la comunidad a NULL.
select pg_temp.expect_c($q$update public.examenes set comunidad_id = null where id = 'e4000000-0000-0000-0000-000000000002'$q$, 'EXAM_COMMUNITY_REQUIRED');
-- 4. Una comunidad en uso no se puede borrar (FK RESTRICT, ya no SET NULL).
do $$ declare v_state text; begin
  begin delete from public.comunidades_autonomas where id = 'c1000000-0000-0000-0000-0000000000c1'; exception when others then get stacked diagnostics v_state = returned_sqlstate; end;
  assert v_state = '23503', 'community in use must not be deletable, got ' || coalesce(v_state, 'no error');
end $$;
-- 5. La columna es NOT NULL: ni siquiera con los triggers desactivados (replica) entra un examen sin comunidad.
set local session_replication_role = replica;
do $$ declare v_state text; begin
  begin insert into public.examenes (id, codigo_examen, especialidad_id, titulo, tipo_examen, tipo_acceso) values ('e4000000-0000-0000-0000-000000000003','C-LEG','e1000000-0000-0000-0000-0000000000c1','x','rafaga','gratuito'); exception when others then get stacked diagnostics v_state = returned_sqlstate; end;
  assert v_state = '23502', 'comunidad_id must be NOT NULL even with triggers off, got ' || coalesce(v_state, 'no error');
end $$;
set local session_replication_role = origin;
-- 6. Un examen con comunidad se publica.
update public.examenes set publicado_en = now() where id = 'e4000000-0000-0000-0000-000000000002';
do $$ begin raise notice 'OK exam community required'; end $$;
rollback;
