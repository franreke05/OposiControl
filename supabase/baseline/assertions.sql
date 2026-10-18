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

  -- DEC-002 + owner 2026-10-16: the specialty is SET ONCE. The same id is a no-op; another id is refused (SPECIALTY_ALREADY_SET) and nothing else of that patch is written.
  perform pg_temp.expect($q$select public.app_actualizar_perfil('a0000000-0000-0000-0000-00000000000a','{"especialidadId":"e1000000-0000-0000-0000-000000000002","introSeen":true}'::jsonb, null)$q$, 'OC409', 'SPECIALTY_ALREADY_SET');
  r := public.app_actualizar_perfil('a0000000-0000-0000-0000-00000000000a','{"especialidadId":"e1000000-0000-0000-0000-000000000001","introSeen":true,"detail":{"edad":30,"ciudad":"Sevilla"},"targetCommunityIds":["c1000000-0000-0000-0000-000000000002","c1000000-0000-0000-0000-000000000001"]}'::jsonb, null);
  assert r->>'especialidadPrincipalId' = 'e1000000-0000-0000-0000-000000000001', 'specialty unchanged (same id = no-op)';
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
  -- (20261010100000; never evaluable), each bound to its immutable revision (20261022100000). Archived ones stay out.
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
  -- annulled between start and finish: nothing stored, not evaluable. (An attempt that predates OFFICIAL_RULES: no snapshot, no roles = the live M10 rule; new attempts freeze the annulment at start, see the official-rules block.)
  update public.preguntas_intento set rol = null, anulada_al_inicio = null, sustituye_a_posicion = null where intento_id = a;
  update public.intentos_examen set regla_anuladas = null, penalizacion_num = null, penalizacion_den = null, reservas_usadas = null, total_anuladas = null, anuladas_sin_reserva = null where id = a;
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
set local session_replication_role = replica; -- legacy unlimited gift rows (the table guard refuses new ones)
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
set local session_replication_role = origin;
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

-- 20261022100200: V1, un examen lleva comunidad (INSERT, poner NULL y publicar sin ella se rechazan; los heredados siguen editables).
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

-- 20261022100300: V1 wallet ledger hardening (per-wallet seq, append-only, snapshot only by the ledger, reconcile, per-user idempotency).
begin;
create function pg_temp.expect_e(p_sql text, p_msg text) returns void language plpgsql as $$
declare v_msg text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_msg = message_text; end;
  if v_msg is distinct from p_msg then raise exception 'ASSERT FAILED: % -> expected % got %', p_sql, p_msg, v_msg; end if;
end $$;
insert into auth.users (id, email, aud, role) values
  ('a0000000-0000-0000-0000-0000000000d1','led1@test.local','authenticated','authenticated'),
  ('a0000000-0000-0000-0000-0000000000d2','led2@test.local','authenticated','authenticated'),
  ('a0000000-0000-0000-0000-0000000000d3','led3@test.local','authenticated','authenticated');
do $$
declare r record; n int;
begin
  select * into r from public.admin_grant_tickets('a0000000-0000-0000-0000-0000000000d1', 100, 'credit', 'k-1', 'ajuste');
  assert r.saldo_anterior = 0 and r.saldo_resultante = 100, 'first credit';
  assert (select seq from public.transacciones_cartera where id = r.transaction_id) = 1, 'seq starts at 1 for the wallet';
  assert (select saldo_tickets from public.carteras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000d1') = 100, 'snapshot written by the ledger trigger';
  -- replay with the same key and content returns the SAME row and writes nothing
  select * into r from public.admin_grant_tickets('a0000000-0000-0000-0000-0000000000d1', 100, 'credit', 'k-1', 'ajuste');
  select count(*) into n from public.transacciones_cartera where usuario_id = 'a0000000-0000-0000-0000-0000000000d1';
  assert n = 1, 'replay must not create a second row';
  -- the same key from ANOTHER user is independent, and its seq starts at 1 (per wallet, never global)
  perform public.admin_grant_tickets('a0000000-0000-0000-0000-0000000000d2', 7, 'credit', 'k-1', 'ajuste');
  assert (select seq from public.transacciones_cartera where usuario_id = 'a0000000-0000-0000-0000-0000000000d2') = 1, 'seq is per wallet';
  -- a debit is allocated FIFO to the credit that paid it
  perform public.admin_grant_tickets('a0000000-0000-0000-0000-0000000000d1', -30, 'debit', 'k-2', 'ajuste');
  assert (select saldo_tickets from public.carteras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000d1') = 70, 'balance after debit';
  assert (select sum(cantidad) from public.ot_consumos where usuario_id = 'a0000000-0000-0000-0000-0000000000d1') = 30, 'debit traced to its credit';
  assert (select count(*) from public.app_wallet_reconcile(null)) = 0, 'reconcile is clean';
  assert (select count(*) from public.v_wallet_reconcile) = 0, 'reconcile view is clean';
end $$;
select pg_temp.expect_e($q$select public.admin_grant_tickets('a0000000-0000-0000-0000-0000000000d1', 101, 'credit', 'k-1', 'ajuste')$q$, 'WALLET_IDEMPOTENCY_CONFLICT');
select pg_temp.expect_e($q$select public.admin_grant_tickets('a0000000-0000-0000-0000-0000000000d1', -1000, 'debit', 'k-3', 'ajuste')$q$, 'WALLET_INSUFFICIENT_BALANCE');
-- a direct INSERT cannot break the chain and cannot overdraw
select pg_temp.expect_e($q$insert into public.transacciones_cartera (usuario_id, tipo, cantidad, seq) values ('a0000000-0000-0000-0000-0000000000d1','ajuste',1,99)$q$, 'WALLET_CHAIN_MISMATCH');
select pg_temp.expect_e($q$insert into public.transacciones_cartera (usuario_id, tipo, cantidad, saldo_anterior) values ('a0000000-0000-0000-0000-0000000000d1','ajuste',1,5)$q$, 'WALLET_CHAIN_MISMATCH');
select pg_temp.expect_e($q$insert into public.transacciones_cartera (usuario_id, tipo, cantidad, saldo_resultante) values ('a0000000-0000-0000-0000-0000000000d1','ajuste',1,5000)$q$, 'WALLET_CHAIN_MISMATCH');
select pg_temp.expect_e($q$insert into public.transacciones_cartera (usuario_id, tipo, cantidad) values ('a0000000-0000-0000-0000-0000000000d1','ajuste',-1000)$q$, 'WALLET_INSUFFICIENT_BALANCE');
select pg_temp.expect_e($q$insert into public.transacciones_cartera (usuario_id, tipo, cantidad) values ('a0000000-0000-0000-0000-0000000000d1','ajuste',0)$q$, 'WALLET_AMOUNT_INVALID');
-- the ledger is append-only
select pg_temp.expect_e($q$update public.transacciones_cartera set descripcion = 'x' where usuario_id = 'a0000000-0000-0000-0000-0000000000d1'$q$, 'LEDGER_IMMUTABLE');
select pg_temp.expect_e($q$delete from public.transacciones_cartera where usuario_id = 'a0000000-0000-0000-0000-0000000000d1'$q$, 'LEDGER_IMMUTABLE');
select pg_temp.expect_e($q$delete from public.ot_consumos where usuario_id = 'a0000000-0000-0000-0000-0000000000d1'$q$, 'LEDGER_IMMUTABLE');
-- the snapshot is written ONLY by the ledger trigger
select pg_temp.expect_e($q$update public.carteras_usuario set saldo_tickets = 1 where usuario_id = 'a0000000-0000-0000-0000-0000000000d1'$q$, 'WALLET_SNAPSHOT_READ_ONLY');
select pg_temp.expect_e($q$insert into public.carteras_usuario (usuario_id, saldo_tickets) values ('a0000000-0000-0000-0000-0000000000d3', 5)$q$, 'WALLET_SNAPSHOT_READ_ONLY');
select pg_temp.expect_e($q$delete from public.carteras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000d1'$q$, 'WALLET_SNAPSHOT_READ_ONLY');
create function pg_temp.sneak() returns void language plpgsql security definer as $$ begin update public.carteras_usuario set saldo_tickets = 999; end $$;
select pg_temp.expect_e($q$select pg_temp.sneak()$q$, 'WALLET_SNAPSHOT_READ_ONLY');
-- a NEW profile gets an EMPTY wallet (balance 0, no movement) in the same transaction: a real row, equivalent to a ledger sum of 0
do $$ begin
  assert (select saldo_tickets from public.carteras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000d3') = 0, 'a new profile has a real wallet row with balance 0';
  assert not exists (select 1 from public.transacciones_cartera where usuario_id = 'a0000000-0000-0000-0000-0000000000d3'), 'and no movement';
end $$;
-- reconcile DETECTS a tampered snapshot (guard switched off by the superuser of this test only)
alter table public.carteras_usuario disable trigger trg_wallet_snapshot_guard;
update public.carteras_usuario set saldo_tickets = 71 where usuario_id = 'a0000000-0000-0000-0000-0000000000d1';
alter table public.carteras_usuario enable trigger trg_wallet_snapshot_guard;
do $$ begin
  assert exists (select 1 from public.app_wallet_reconcile('a0000000-0000-0000-0000-0000000000d1') where kind = 'SNAPSHOT_NE_LEDGER' and expected = 70 and actual = 71), 'reconcile must flag a tampered snapshot';
end $$;
-- the ledger refuses to extend a chain whose snapshot disagrees
select pg_temp.expect_e($q$select public.admin_grant_tickets('a0000000-0000-0000-0000-0000000000d1', 1, 'credit', 'k-4', 'ajuste')$q$, 'WALLET_INCONSISTENT');
alter table public.carteras_usuario disable trigger trg_wallet_snapshot_guard;
update public.carteras_usuario set saldo_tickets = 70 where usuario_id = 'a0000000-0000-0000-0000-0000000000d1';
alter table public.carteras_usuario enable trigger trg_wallet_snapshot_guard;
-- grants: no write path for app roles
do $$ declare r text; t text; begin
  foreach t in array array['public.transacciones_cartera','public.carteras_usuario','public.ot_consumos'] loop
    foreach r in array array['anon','authenticated','service_role'] loop
      assert not has_table_privilege(r, t, 'insert') and not has_table_privilege(r, t, 'update') and not has_table_privilege(r, t, 'delete'), 'write privilege for ' || r || ' on ' || t;
    end loop;
  end loop;
  assert not has_function_privilege('authenticated', 'public.app_wallet_reconcile(uuid)', 'execute') and has_function_privilege('service_role', 'public.app_wallet_reconcile(uuid)', 'execute'), 'reconcile is service-role only';
end $$;
-- account deletion still works (the cascade is the only way a ledger row can go)
delete from auth.users where id = 'a0000000-0000-0000-0000-0000000000d2';
do $$ begin
  assert not exists (select 1 from public.transacciones_cartera where usuario_id = 'a0000000-0000-0000-0000-0000000000d2')
     and not exists (select 1 from public.carteras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000d2'), 'account deletion cascades the wallet';
  raise notice 'OK V1 wallet ledger';
end $$;
rollback;

-- 20261022100400: V1 payment orders (frozen amounts, credit exactly once, late payment, refund chain, state machine).
begin;
create function pg_temp.expect_e(p_sql text, p_msg text) returns void language plpgsql as $$
declare v_msg text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_msg = message_text; end;
  if v_msg is distinct from p_msg then raise exception 'ASSERT FAILED: % -> expected % got %', p_sql, p_msg, v_msg; end if;
end $$;
create function pg_temp.expect_state(p_sql text, p_state text) returns void language plpgsql as $$
declare v_state text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_state = returned_sqlstate; end;
  if v_state is distinct from p_state then raise exception 'ASSERT FAILED: % -> expected sqlstate % got %', p_sql, p_state, v_state; end if;
end $$;
insert into auth.users (id, email, aud, role) values
  ('a0000000-0000-0000-0000-0000000000e1','ord1@test.local','authenticated','authenticated'),
  ('a0000000-0000-0000-0000-0000000000e2','ord2@test.local','authenticated','authenticated'),
  ('a0000000-0000-0000-0000-0000000000e3','ord3@test.local','authenticated','authenticated');
insert into public.productos_comerciales (id, codigo, tipo_producto, nombre, precio_centimos, moneda, cantidad_tickets, activo, visible) values
  ('b0000000-0000-0000-0000-000000000001','pack-100','pack_tickets','Pack 100',499,'EUR',100,true,true),
  ('b0000000-0000-0000-0000-000000000002','pack-50','pack_tickets','Pack 50',299,'EUR',50,true,true),
  ('b0000000-0000-0000-0000-000000000003','pack-1000','pack_tickets','Pack 1000',3999,'EUR',1000,true,true),
  ('b0000000-0000-0000-0000-000000000004','pack-unpriced','pack_tickets','Sin precio',null,'EUR',100,true,true),
  ('b0000000-0000-0000-0000-000000000005','pack-hidden','pack_tickets','Oculto',499,'EUR',100,true,false);

-- ---- orders: catalogue, freeze, idempotency
select pg_temp.expect_e($q$select * from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e1','pack-unpriced','google_play','o-1')$q$, 'PRODUCT_NOT_AVAILABLE');
select pg_temp.expect_e($q$select * from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e1','pack-hidden','google_play','o-1')$q$, 'PRODUCT_NOT_AVAILABLE');
select pg_temp.expect_e($q$select * from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e1','nope','google_play','o-1')$q$, 'PRODUCT_NOT_AVAILABLE');
select pg_temp.expect_e($q$select * from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e1','pack-100','google_play','')$q$, 'IDEMPOTENCY_KEY_REQUIRED');
select pg_temp.expect_state($q$select * from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e1','pack-100','sandbox','o-sb')$q$, '23514');
do $$
declare o record; o2 record; n int;
begin
  select * into o from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e1','pack-100','google_play','o-1');
  assert o.outcome = 'CREATED' and o.estado = 'pendiente' and o.cantidad_tickets = 100 and o.importe_centimos = 499 and o.moneda = 'EUR', 'order frozen from the catalogue';
  assert o.expira_en > now() + interval '29 minutes' and o.expira_en <= now() + interval '30 minutes', 'TTL is 30 minutes';
  select * into o2 from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e1','pack-100','google_play','o-1');
  assert o2.outcome = 'REPLAY' and o2.orden_id = o.orden_id, 'same key and same body replays the same order';
  select * into o2 from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e2','pack-100','google_play','o-1');
  assert o2.outcome = 'CREATED' and o2.orden_id <> o.orden_id, 'another user with the same key is independent';
  select count(*) into n from public.compras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000e1' and idempotency_key = 'o-1';
  assert n = 1, 'one order per (user, key)';
  -- changing the catalogue price later does NOT change the frozen order
  update public.productos_comerciales set precio_centimos = 999 where codigo = 'pack-100';
  select * into o2 from public.app_get_payment_order('a0000000-0000-0000-0000-0000000000e1', o.orden_id);
  assert o2.importe_centimos = 499, 'amount is frozen at creation';
  update public.productos_comerciales set precio_centimos = 499 where codigo = 'pack-100';
end $$;
select pg_temp.expect_e($q$select * from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e1','pack-50','google_play','o-1')$q$, 'IDEMPOTENCY_KEY_REUSED');
select pg_temp.expect_e($q$select * from public.app_get_payment_order('a0000000-0000-0000-0000-0000000000e2', (select id from public.compras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000e1' and idempotency_key = 'o-1'))$q$, 'ORDER_NOT_FOUND');

-- ---- credit exactly once
do $$
declare o record; r record; oid uuid; n int; bal int;
begin
  select orden_id into oid from public.app_get_payment_order('a0000000-0000-0000-0000-0000000000e1', (select id from public.compras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000e1' and idempotency_key = 'o-1')) o;
  -- wrong amount, wrong currency, wrong product: no credit, order goes to review
  select * into r from public.app_credit_payment_order('google_play', 'ev-bad-amount', oid, 'tx-1', 100, 'EUR', 'pack-100');
  assert r.outcome = 'EN_REVISION' and r.estado = 'en_revision', 'amount mismatch -> en_revision';
  assert not exists (select 1 from public.transacciones_cartera where compra_id = oid), 'no credit on mismatch';
  -- a fresh order for the successful path
  select * into o from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e1','pack-100','google_play','o-2');
  select * into r from public.app_credit_payment_order('google_play', 'ev-bad-cur', o.orden_id, 'tx-2', 499, 'USD', 'pack-100');
  assert r.outcome = 'EN_REVISION', 'currency mismatch -> en_revision';
  select * into o from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e1','pack-100','google_play','o-3');
  select * into r from public.app_credit_payment_order('google_play', 'ev-bad-prod', o.orden_id, 'tx-3', 499, 'EUR', 'pack-50');
  assert r.outcome = 'EN_REVISION', 'product mismatch -> en_revision (tampered package)';
  -- success
  select * into o from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e1','pack-100','google_play','o-4');
  select * into r from public.app_credit_payment_order('google_play', 'ev-ok', o.orden_id, 'tx-4', 499, 'EUR', 'pack-100');
  assert r.outcome = 'CREDITED' and r.estado = 'pagada' and r.saldo = 100 and r.transaccion_id is not null, 'verified payment credits exactly the frozen OT';
  assert (select usuario_id from public.transacciones_cartera where id = r.transaccion_id) = 'a0000000-0000-0000-0000-0000000000e1', 'credit goes to the order owner';
  -- duplicate callback (same event) and a second event for the same order: no second credit
  select * into r from public.app_credit_payment_order('google_play', 'ev-ok', o.orden_id, 'tx-4', 499, 'EUR', 'pack-100');
  assert r.outcome = 'REPLAY' and r.saldo = 100, 'duplicate callback is a no-op';
  select * into r from public.app_credit_payment_order('google_play', 'ev-ok-2', o.orden_id, 'tx-4', 499, 'EUR', 'pack-100');
  assert r.outcome = 'REPLAY' and r.saldo = 100, 'a different event id for a credited order is a no-op';
  select count(*) into n from public.transacciones_cartera where compra_id = o.orden_id and tipo = 'pack_tickets';
  assert n = 1, 'exactly one credit row per order';
  -- the same provider transaction on ANOTHER order is refused (no credit, review)
  select * into o from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e1','pack-100','google_play','o-5');
  select * into r from public.app_credit_payment_order('google_play', 'ev-dup-tx', o.orden_id, 'tx-4', 499, 'EUR', 'pack-100');
  assert r.outcome = 'EN_REVISION', 'a provider transaction already used by another order is refused';
  select saldo_tickets into bal from public.carteras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000e1';
  assert bal = 100, 'balance only moved once';
  -- unknown order / foreign provider
  begin
    perform * from public.app_credit_payment_order('google_play', 'ev-x', 'b0000000-0000-0000-0000-0000000000ff', 'tx-9', 499, 'EUR', 'pack-100');
    assert false, 'unknown order must fail';
  exception when others then
    assert sqlerrm = 'ORDER_NOT_FOUND', 'unknown order -> ORDER_NOT_FOUND, got ' || sqlerrm;
  end;
  select * into o from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e1','pack-100','google_play','o-6');
  begin
    perform * from public.app_credit_payment_order('app_store', 'ev-y', o.orden_id, 'tx-10', 499, 'EUR', 'pack-100');
    assert false, 'another provider must not credit the order';
  exception when others then
    assert sqlerrm = 'ORDER_NOT_FOUND', 'provider mismatch -> ORDER_NOT_FOUND, got ' || sqlerrm;
  end;
end $$;

-- ---- late payment, cancelled and failed orders
do $$
declare o record; r record; bal int;
begin
  select * into o from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e2','pack-50','app_store','l-1');
  alter table public.compras_usuario disable trigger trg_compras_usuario_order_machine;
  update public.compras_usuario set expira_en = now() - interval '1 minute' where id = o.orden_id;
  alter table public.compras_usuario enable trigger trg_compras_usuario_order_machine;
  select * into r from public.app_get_payment_order('a0000000-0000-0000-0000-0000000000e2', o.orden_id);
  assert r.estado = 'expirada', 'a pending order past its TTL expires';
  select * into r from public.app_credit_payment_order('app_store', 'ev-late', o.orden_id, 'tx-l1', 299, 'EUR', 'pack-50');
  assert r.outcome = 'CREDITED_LATE' and r.estado = 'pagada' and r.saldo = 50, 'an authentic late confirmation of an expired order with matching data is paid';
  assert exists (select 1 from public.auditoria_log where accion = 'pago_tardio_acreditado' and registro_id = o.orden_id), 'late payment is audited';
  -- cancelled order + paid event -> review, NO credit
  select * into o from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e2','pack-50','app_store','l-2');
  update public.compras_usuario set estado = 'cancelada' where id = o.orden_id;
  select * into r from public.app_credit_payment_order('app_store', 'ev-cancel', o.orden_id, 'tx-l2', 299, 'EUR', 'pack-50');
  assert r.outcome = 'EN_REVISION' and r.saldo = 50, 'cancelled + paid event -> en_revision without credit';
  select * into o from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e2','pack-50','app_store','l-3');
  update public.compras_usuario set estado = 'fallida' where id = o.orden_id;
  select * into r from public.app_credit_payment_order('app_store', 'ev-failed', o.orden_id, 'tx-l3', 299, 'EUR', 'pack-50');
  assert r.outcome = 'EN_REVISION' and r.saldo = 50, 'failed + paid event -> en_revision without credit';
end $$;

-- ---- state machine and frozen fields
select pg_temp.expect_e($q$update public.compras_usuario set estado = 'pagada' where usuario_id = 'a0000000-0000-0000-0000-0000000000e2' and idempotency_key = 'l-3'$q$, 'ORDER_STATE_TRANSITION_FORBIDDEN');
select pg_temp.expect_e($q$update public.compras_usuario set precio_centimos = 1 where usuario_id = 'a0000000-0000-0000-0000-0000000000e2' and idempotency_key = 'l-1'$q$, 'ORDER_FROZEN');
select pg_temp.expect_e($q$update public.compras_usuario set estado = 'pendiente' where usuario_id = 'a0000000-0000-0000-0000-0000000000e2' and idempotency_key = 'l-1'$q$, 'ORDER_STATE_TRANSITION_FORBIDDEN');

-- ---- refund chain: the owner's example (buy 1000, spend 800, 200 left, refund the payment)
do $$
declare o record; r record; credit_id uuid; c record;
begin
  select * into o from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e1','pack-1000','google_play','r-1');
  select * into r from public.app_credit_payment_order('google_play', 'ev-r1', o.orden_id, 'tx-r1', 3999, 'EUR', 'pack-1000');
  assert r.outcome = 'CREDITED' and r.saldo = 1100, 'credit 1000 on top of the earlier 100';
  credit_id := r.transaccion_id;
  perform public.admin_grant_tickets('a0000000-0000-0000-0000-0000000000e1', -800, 'spend', 'sp-1', 'ajuste');
  -- the earlier 100 OT are consumed FIRST (FIFO), then 700 of the 1000: the refunded credit has 300 unspent
  assert private.ot_credit_remaining(credit_id) = 300, 'FIFO: the older credit is consumed first';
  select * into r from public.app_refund_payment_order('google_play', 'ev-r1-refund', o.orden_id);
  assert r.outcome = 'REFUNDED_P2' and r.estado = 'reembolsada', 'refund with spent OT closes under P2';
  select * into c from public.refund_case where compra_id = o.orden_id;
  assert c.ot_revertidos = 300 and c.ot_gastados = 700 and c.estado = 'resuelto' and c.politica = 'P2', 'U = 300 reverted, S = 700 recorded, case closed under P2 (B: spent on ajuste, no access to shorten)';
  assert (select cantidad from public.transacciones_cartera where compra_id = o.orden_id and tipo = 'devolucion') = -300, 'only the unspent part is reversed';
  assert (select referencia_id from public.transacciones_cartera where compra_id = o.orden_id and tipo = 'devolucion') = credit_id, 'the reversal names the credit it reverses';
  assert (select saldo_tickets from public.carteras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000e1') = 0, 'balance never negative';
  select * into r from public.app_refund_payment_order('google_play', 'ev-r1-refund', o.orden_id);
  assert r.outcome = 'REPLAY', 'duplicate refund event is a no-op';
  select * into r from public.app_refund_payment_order('google_play', 'ev-r1-refund-2', o.orden_id);
  assert r.outcome = 'REPLAY', 'a second refund of a refunded order is a no-op';
end $$;
-- THE OWNER'S EXAMPLE, exactly: buy 1000 OT, spend 800 on an immersive exam, 200 left, refund the original payment.
do $$
declare o record; r record; credit_id uuid;
begin
  select * into o from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e3','pack-1000','app_store','x-1');
  select * into r from public.app_credit_payment_order('app_store', 'ev-x1', o.orden_id, 'tx-x1', 3999, 'EUR', 'pack-1000');
  credit_id := r.transaccion_id;
  assert r.saldo = 1000, 'bought 1000';
  perform public.admin_grant_tickets('a0000000-0000-0000-0000-0000000000e3', -800, 'immersive exam', 'x-sp', 'ajuste');
  assert (select saldo_tickets from public.carteras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000e3') = 200, '200 left';
  select * into r from public.app_refund_payment_order('app_store', 'ev-x1-refund', o.orden_id);
  assert r.outcome = 'REFUNDED_P2' and r.estado = 'reembolsada' and r.saldo = 0, 'refund: balance 0, never negative';
  assert (select cantidad from public.transacciones_cartera where compra_id = o.orden_id and tipo = 'devolucion') = -200, 'exactly the unspent 200 are reversed';
  assert (select ot_revertidos from public.refund_case where compra_id = o.orden_id) = 200 and (select ot_gastados from public.refund_case where compra_id = o.orden_id) = 800, 'case: 800 spent, nothing revoked';
  assert (select detalle -> 0 ->> 'ot_desde_este_credito' from public.refund_case where compra_id = o.orden_id) = '800', 'the case traces the debit funded by this credit';
  assert (select count(*) from public.app_wallet_reconcile('a0000000-0000-0000-0000-0000000000e3')) = 0, 'reconcile clean';
end $$;
-- refund of an unspent order (case A), of an order never paid, and of mixed purchases (case F)
do $$
declare o1 record; o2 record; r record; c1 uuid; c2 uuid;
begin
  select * into o1 from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e2','pack-100','google_play','f-1');
  select * into r from public.app_refund_payment_order('google_play', 'ev-f1-unpaid', o1.orden_id);
  assert r.outcome = 'NOOP', 'refund of an unpaid order credits and reverses nothing';
  select * into r from public.app_credit_payment_order('google_play', 'ev-f1', o1.orden_id, 'tx-f1', 499, 'EUR', 'pack-100');
  c1 := r.transaccion_id;
  select * into r from public.app_refund_payment_order('google_play', 'ev-f1-refund', o1.orden_id);
  assert r.outcome = 'REFUNDED' and r.saldo = 50, 'case A: unspent credit fully reversed (the 50 from the late order remain)';
  assert not exists (select 1 from public.refund_case where compra_id = o1.orden_id), 'case A: no case';
  -- case F: two purchases, spending crosses both
  select * into o1 from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e2','pack-100','google_play','f-2');
  select * into r from public.app_credit_payment_order('google_play', 'ev-f2', o1.orden_id, 'tx-f2', 499, 'EUR', 'pack-100');
  c1 := r.transaccion_id;
  select * into o2 from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e2','pack-50','google_play','f-3');
  select * into r from public.app_credit_payment_order('google_play', 'ev-f3', o2.orden_id, 'tx-f3', 299, 'EUR', 'pack-50');
  c2 := r.transaccion_id;
  -- balance now 50 (late order) + 100 + 50 = 200; spend 170: 50 from the late credit, 100 from c1, 20 from c2
  perform public.admin_grant_tickets('a0000000-0000-0000-0000-0000000000e2', -170, 'spend', 'sp-f', 'ajuste');
  assert private.ot_credit_remaining(c1) = 0 and private.ot_credit_remaining(c2) = 30, 'FIFO across mixed purchases';
  select * into r from public.app_refund_payment_order('google_play', 'ev-f2-refund', o1.orden_id);
  assert r.outcome = 'REFUNDED_P2' and r.saldo = 30, 'fully spent credit: no reversal, case S = 100, the other purchase is untouched';
  assert (select ot_gastados from public.refund_case where compra_id = o1.orden_id) = 100 and (select ot_revertidos from public.refund_case where compra_id = o1.orden_id) = 0, 'case values';
  select * into r from public.app_refund_payment_order('google_play', 'ev-f3-refund', o2.orden_id);
  assert r.outcome = 'REFUNDED_P2' and r.saldo = 0, 'second purchase: 30 reversed, 20 case';
  assert (select ot_gastados from public.refund_case where compra_id = o2.orden_id) = 20 and (select ot_revertidos from public.refund_case where compra_id = o2.orden_id) = 30, 'second case values';
  assert (select count(*) from public.app_wallet_reconcile(null)) = 0, 'reconcile is clean after credits, debits and refunds';
end $$;

-- ---- P2 (E5-V1-F02): the refund shortens the access the refunded credit financed, reverses only the unspent part, never goes negative
insert into auth.users (id, email, aud, role) values
  ('a0000000-0000-0000-0000-0000000000d1','p21@test.local','authenticated','authenticated'), ('a0000000-0000-0000-0000-0000000000d2','p22@test.local','authenticated','authenticated'),
  ('a0000000-0000-0000-0000-0000000000d3','p23@test.local','authenticated','authenticated'), ('a0000000-0000-0000-0000-0000000000d4','p24@test.local','authenticated','authenticated');
insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-0000000000d1','PX','PX','px',true);
insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('c1000000-0000-0000-0000-0000000000d1','CD','Com D','com-d');
insert into public.capacidades_especialidad (especialidad_id, permite_rafagas, permite_razonados, permite_inmersivos, permite_demos) values ('e1000000-0000-0000-0000-0000000000d1', true, true, true, true);
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, comprable_con_tickets, precio_tickets, dias_acceso_compra, demo_disponible) values
  ('e5000000-0000-0000-0000-0000000000d1','P2-30','e1000000-0000-0000-0000-0000000000d1','c1000000-0000-0000-0000-0000000000d1','P2 treinta','inmersivo','premium',true, now() - interval '1 day', true, 30, 30, false),
  ('e5000000-0000-0000-0000-0000000000d2','P2-150','e1000000-0000-0000-0000-0000000000d1','c1000000-0000-0000-0000-0000000000d1','P2 ciento cincuenta','inmersivo','premium',true, now() - interval '1 day', true, 150, 30, false);
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
  select u, 'p2' || right(u::text, 1), 'p2' || right(u::text, 1), true, 'activa', 'e1000000-0000-0000-0000-0000000000d1'
  from (values ('a0000000-0000-0000-0000-0000000000d1'::uuid), ('a0000000-0000-0000-0000-0000000000d2'::uuid), ('a0000000-0000-0000-0000-0000000000d3'::uuid), ('a0000000-0000-0000-0000-0000000000d4'::uuid)) v(u) on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
create function pg_temp.buy(p_user uuid, p_pack text, p_tag text) returns uuid language plpgsql as $$
declare o record; r record; amount int;
begin
  select * into o from public.app_create_payment_order(p_user, p_pack, 'google_play', 'o-' || p_tag);
  select * into r from public.app_credit_payment_order('google_play', 'ev-' || p_tag, o.orden_id, 'tx-' || p_tag, o.importe_centimos, 'EUR', p_pack);
  return o.orden_id;
end $$;
do $$
declare u1 uuid := 'a0000000-0000-0000-0000-0000000000d1'; u2 uuid := 'a0000000-0000-0000-0000-0000000000d2'; u3 uuid := 'a0000000-0000-0000-0000-0000000000d3'; u4 uuid := 'a0000000-0000-0000-0000-0000000000d4';
        e30 uuid := 'e5000000-0000-0000-0000-0000000000d1'; e150 uuid := 'e5000000-0000-0000-0000-0000000000d2';
        o1 uuid; oa uuid; ob uuid; r record; c record; v timestamptz;
begin
  -- C: 100 OT bought, 30 spent on the 30-OT exam (30 days), the other 70 spent elsewhere: the whole access was financed by the refunded credit
  o1 := pg_temp.buy(u1, 'pack-100', 'p1');
  perform * from public.app_redeem_exam_access(u1, e30, 'k-p1', 30);
  perform public.admin_grant_tickets(u1, -70, 'spend', 'sp-p1', 'ajuste');
  select * into r from public.app_refund_payment_order('google_play', 'ev-p1-refund', o1);
  assert r.outcome = 'REFUNDED_P2' and r.saldo = 0, 'C: fully spent credit: nothing to reverse, balance stays 0 (never negative)';
  select valido_hasta into v from public.contenido_desbloqueado_usuario where usuario_id = u1 and examen_id = e30 and motivo = 'compra';
  assert v between now() - interval '1 minute' and clock_timestamp() + interval '1 second', 'C: the access is shortened to now, not revoked by deletion';
  assert exists (select 1 from public.contenido_desbloqueado_usuario where usuario_id = u1 and examen_id = e30), 'C: the entitlement row still exists';
  select * into c from public.refund_case where compra_id = o1;
  assert c.politica = 'P2' and c.estado = 'resuelto' and c.resuelto_en is not null and c.ot_gastados = 100 and c.ot_revertidos = 0, 'C: case closed under P2';
  assert c.detalle @> '[{"acceso_acortado":"e5000000-0000-0000-0000-0000000000d1"}]'::jsonb, 'C: the case records which access was shortened';
  assert (select count(*) from public.app_wallet_reconcile(u1)) = 0, 'C: reconcile clean';

  -- D + F: two 100 credits, one 150-OT exam: 100 from the first credit, 50 from the second; 50 OT left over
  oa := pg_temp.buy(u2, 'pack-100', 'p2a');
  ob := pg_temp.buy(u2, 'pack-100', 'p2b');
  perform * from public.app_redeem_exam_access(u2, e150, 'k-p2', 150);
  select * into r from public.app_refund_payment_order('google_play', 'ev-p2a-refund', oa);
  assert r.outcome = 'REFUNDED_P2' and r.saldo = 50, 'D: refund of the first credit: nothing unspent, the other purchase untouched, balance 50';
  select valido_hasta into v from public.contenido_desbloqueado_usuario where usuario_id = u2 and examen_id = e150 and motivo = 'compra';
  assert v between now() + interval '9 days 23 hours' and now() + interval '10 days 1 hour', 'D: 100 of 150 OT financed = 20 of 30 days cut: about 10 days left (' || v || ')';
  select * into r from public.app_refund_payment_order('google_play', 'ev-p2b-refund', ob);
  assert r.outcome = 'REFUNDED_P2' and r.saldo = 0, 'F: second credit: 50 unspent reversed, 50 spent recorded, balance 0';
  select valido_hasta into v from public.contenido_desbloqueado_usuario where usuario_id = u2 and examen_id = e150 and motivo = 'compra';
  assert v between now() - interval '1 minute' and clock_timestamp() + interval '1 second', 'F: the remaining 50 OT cut the last 10 days: shortened to now';
  assert (select ot_revertidos from public.refund_case where compra_id = ob) = 50 and (select ot_gastados from public.refund_case where compra_id = ob) = 50, 'F: case values';
  assert (select count(*) from public.app_wallet_reconcile(u2)) = 0, 'F: reconcile clean';

  -- E: the access already expired: nothing to shorten, nothing lengthened, still no negative balance
  o1 := pg_temp.buy(u3, 'pack-100', 'p3');
  perform * from public.app_redeem_exam_access(u3, e30, 'k-p3', 30);
  update public.contenido_desbloqueado_usuario set valido_hasta = now() - interval '1 day', valido_desde = now() - interval '31 days' where usuario_id = u3 and examen_id = e30;
  select * into r from public.app_refund_payment_order('google_play', 'ev-p3-refund', o1);
  assert r.outcome = 'REFUNDED_P2' and r.saldo = 0, 'E: 70 unspent reversed, 30 recorded';
  select valido_hasta into v from public.contenido_desbloqueado_usuario where usuario_id = u3 and examen_id = e30;
  assert v < now() - interval '23 hours', 'E: an expired access is untouched (never lengthened, never moved)';
  assert (select detalle @> '[{"acceso_expirado_o_ausente":"e5000000-0000-0000-0000-0000000000d1"}]'::jsonb from public.refund_case where compra_id = o1), 'E: the case says there was no live access to shorten';

  -- A/B regressions on a wallet with a live access that this credit did NOT finance: untouched
  o1 := pg_temp.buy(u4, 'pack-100', 'p4a');
  perform * from public.app_redeem_exam_access(u4, e30, 'k-p4', 30);
  oa := pg_temp.buy(u4, 'pack-50', 'p4b');
  select valido_hasta into v from public.contenido_desbloqueado_usuario where usuario_id = u4 and examen_id = e30;
  select * into r from public.app_refund_payment_order('google_play', 'ev-p4b-refund', oa);
  assert r.outcome = 'REFUNDED' and r.saldo = 70, 'A: the unspent 50 credit is reversed in full, no case';
  assert (select valido_hasta from public.contenido_desbloqueado_usuario where usuario_id = u4 and examen_id = e30) = v, 'A: the access financed by ANOTHER credit is untouched';
end $$;

-- ---- P01 (E2-V1-P01): the cut uses the days GRANTED at purchase (debit metadata), not the exam current setting; older debits fall back to the current days
insert into auth.users (id, email, aud, role) values
  ('a0000000-0000-0000-0000-0000000000d5','p25@test.local','authenticated','authenticated'), ('a0000000-0000-0000-0000-0000000000d6','p26@test.local','authenticated','authenticated');
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
  select u, 'p2' || right(u::text, 1), 'p2' || right(u::text, 1), true, 'activa', 'e1000000-0000-0000-0000-0000000000d1'
  from (values ('a0000000-0000-0000-0000-0000000000d5'::uuid), ('a0000000-0000-0000-0000-0000000000d6'::uuid)) v(u) on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
do $$
declare u5 uuid := 'a0000000-0000-0000-0000-0000000000d5'; u6 uuid := 'a0000000-0000-0000-0000-0000000000d6'; e150 uuid := 'e5000000-0000-0000-0000-0000000000d2';
        oa uuid; ob uuid;
begin
  -- both buy two 100-OT packs and the 150-OT exam (30 days granted); u6 will lose the metadata below
  oa := pg_temp.buy(u5, 'pack-100', 'p5a'); ob := pg_temp.buy(u5, 'pack-100', 'p5b');
  perform * from public.app_redeem_exam_access(u5, e150, 'k-p5', 150);
  assert (select metadatos ->> 'dias' from public.transacciones_cartera where usuario_id = u5 and tipo = 'compra_examen') = '30', 'the redeem debit stores the days granted';
  oa := pg_temp.buy(u6, 'pack-100', 'p6a'); ob := pg_temp.buy(u6, 'pack-100', 'p6b');
  perform * from public.app_redeem_exam_access(u6, e150, 'k-p6', 150);
end $$;
set local session_replication_role = replica;
update public.transacciones_cartera set metadatos = metadatos - 'dias' where usuario_id = 'a0000000-0000-0000-0000-0000000000d6' and tipo = 'compra_examen';
set local session_replication_role = origin;
-- the setting changes between purchase and refund: 30 -> 60 days
update public.examenes set dias_acceso_compra = 60 where id = 'e5000000-0000-0000-0000-0000000000d2';
do $$
declare r record; v timestamptz;
begin
  select * into r from public.app_refund_payment_order('google_play', 'ev-p5a-refund', (select id from public.compras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000d5' and idempotency_key = 'o-p5a'));
  select valido_hasta into v from public.contenido_desbloqueado_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000d5' and examen_id = 'e5000000-0000-0000-0000-0000000000d2' and motivo = 'compra';
  assert v between now() + interval '9 days 23 hours' and now() + interval '10 days 1 hour', 'P01: 100/150 of the 30 days GRANTED = 20 days cut, about 10 left, although the exam now says 60 (' || v || ')';
  select * into r from public.app_refund_payment_order('google_play', 'ev-p6a-refund', (select id from public.compras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000d6' and idempotency_key = 'o-p6a'));
  select valido_hasta into v from public.contenido_desbloqueado_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000d6' and examen_id = 'e5000000-0000-0000-0000-0000000000d2' and motivo = 'compra';
  assert v between now() - interval '1 minute' and clock_timestamp() + interval '1 second', 'P01 fallback: a debit without the stored days uses the current 60 (40 days cut = now) (' || v || ')';
end $$;

-- ---- no client path
do $$ declare t text; r text; f text; begin
  foreach t in array array['public.eventos_pago','public.refund_case'] loop
    foreach r in array array['anon','authenticated','service_role'] loop
      assert not has_table_privilege(r, t, 'insert') and not has_table_privilege(r, t, 'update') and not has_table_privilege(r, t, 'delete'), 'write privilege for ' || r || ' on ' || t;
    end loop;
    assert not has_table_privilege('anon', t, 'select') and not has_table_privilege('authenticated', t, 'select'), 'client read on ' || t;
  end loop;
  foreach f in array array['public.app_create_payment_order(uuid,text,text,text)','public.app_get_payment_order(uuid,uuid)','public.app_credit_payment_order(text,text,uuid,text,integer,text,text)','public.app_refund_payment_order(text,text,uuid)'] loop
    assert not has_function_privilege('anon', f, 'execute') and not has_function_privilege('authenticated', f, 'execute') and has_function_privilege('service_role', f, 'execute'), 'function grants ' || f;
  end loop;
  raise notice 'OK V1 payment orders';
end $$;
-- E2-V1-E02: at most 5 open (pending, unexpired) orders per user; the 6th is refused and writes nothing; a replay of an existing key is still answered
do $$
declare o record; n0 integer; n1 integer; nall integer;
begin
  for i in 1..4 loop
    select * into o from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e2','pack-100','google_play','cap-' || i);
  end loop;
  select count(*) into n0 from public.compras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000e2' and estado = 'pendiente' and expira_en > now();
  assert n0 = 5, 'five open orders: ' || n0;
  select count(*) into nall from public.compras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000e2';
  begin perform * from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e2','pack-100','google_play','cap-6'); assert false, 'the sixth must be refused';
  exception when others then assert sqlerrm = 'ORDER_PENDING_LIMIT', 'cap error: ' || sqlerrm; end;
  select count(*) into n1 from public.compras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000e2';
  assert n1 = nall, 'the refused order wrote nothing';
  select * into o from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000e2','pack-100','google_play','cap-1');
  assert o.outcome = 'REPLAY', 'a replay is still answered at the cap';
end $$;
rollback;

-- E5-V1-F01: legacy backfill. Old state = ledger rows WITHOUT seq (as they exist on MAIN). Five shapes; reconcile must report the double count first.
begin;
insert into auth.users (id, email, aud, role) values
  ('a0000000-0000-0000-0000-0000000000b1','bf1@test.local','authenticated','authenticated'),
  ('a0000000-0000-0000-0000-0000000000b2','bf2@test.local','authenticated','authenticated'),
  ('a0000000-0000-0000-0000-0000000000b3','bf3@test.local','authenticated','authenticated'),
  ('a0000000-0000-0000-0000-0000000000b4','bf4@test.local','authenticated','authenticated');
set local session_replication_role = replica;
-- B1 = user A of the fixtures: five old rows (50 -20 +5 +0... = 35) and a snapshot of 35
insert into public.carteras_usuario (usuario_id, saldo_tickets) values ('a0000000-0000-0000-0000-0000000000b1', 35), ('a0000000-0000-0000-0000-0000000000b2', 40),
  ('a0000000-0000-0000-0000-0000000000b3', 35), ('a0000000-0000-0000-0000-0000000000b4', 0)
  on conflict (usuario_id) do update set saldo_tickets = excluded.saldo_tickets;
insert into public.transacciones_cartera (usuario_id, tipo, cantidad, saldo_anterior, saldo_resultante, descripcion, creado_en) values
  ('a0000000-0000-0000-0000-0000000000b1','recarga',50,0,50,'old1', now() - interval '5 days'),
  ('a0000000-0000-0000-0000-0000000000b1','gasto',-20,50,30,'old2', now() - interval '4 days'),
  ('a0000000-0000-0000-0000-0000000000b1','regalo',5,30,35,'old3', now() - interval '3 days'),
  ('a0000000-0000-0000-0000-0000000000b3','recarga',30,0,30,'old1', now() - interval '2 days');
set local session_replication_role = origin;
do $$
declare n integer;
begin
  -- the double count the draft migration made would have shown here: rows without seq are REPORTED
  assert exists (select 1 from public.app_wallet_reconcile('a0000000-0000-0000-0000-0000000000b1') where kind = 'SEQLESS_ROWS'), 'reconcile reports legacy rows without seq';
  assert exists (select 1 from public.app_wallet_reconcile('a0000000-0000-0000-0000-0000000000b2') where kind = 'SNAPSHOT_NE_LEDGER'), 'reconcile reports a snapshot with no rows';
  n := private.v1_ledger_backfill();
  assert n >= 3, 'three wallets to repair: ' || n;
  -- B1: rows already sum to the snapshot -> sequenced 1..3, NO opening row, chain closed, sum == snapshot
  assert (select count(*) from public.transacciones_cartera where usuario_id = 'a0000000-0000-0000-0000-0000000000b1') = 3, 'no row added when the old rows already sum to the balance';
  assert (select array_agg(seq order by creado_en) from public.transacciones_cartera where usuario_id = 'a0000000-0000-0000-0000-0000000000b1') = array[1,2,3]::bigint[], 'old rows sequenced in creado_en order';
  assert (select sum(cantidad) from public.transacciones_cartera where usuario_id = 'a0000000-0000-0000-0000-0000000000b1') = 35, 'history sums to the balance';
  -- B2: snapshot 40 and no rows -> exactly one opening row = 40
  assert (select count(*) from public.transacciones_cartera where usuario_id = 'a0000000-0000-0000-0000-0000000000b2' and referencia_tipo = 'legacy_opening' and cantidad = 40 and seq = 1) = 1, 'a wallet with a snapshot and no rows gets one opening row';
  -- B3: rows sum 30, snapshot 35 -> opening row of +5 at the end
  assert (select cantidad from public.transacciones_cartera where usuario_id = 'a0000000-0000-0000-0000-0000000000b3' and referencia_tipo = 'legacy_opening') = 5, 'opening row = snapshot - sum(rows)';
  -- B4: snapshot 0 and no rows -> untouched
  assert not exists (select 1 from public.transacciones_cartera where usuario_id = 'a0000000-0000-0000-0000-0000000000b4'), 'an empty wallet stays empty';
  assert (select count(*) from public.app_wallet_reconcile(null) where usuario_id in ('a0000000-0000-0000-0000-0000000000b1','a0000000-0000-0000-0000-0000000000b2','a0000000-0000-0000-0000-0000000000b3','a0000000-0000-0000-0000-0000000000b4')) = 0, 'reconcile clean after the backfill';
  -- idempotent / re-runnable
  assert private.v1_ledger_backfill() = 0, 'a second run changes nothing';
  -- the wallet still works through the normal path afterwards
  perform public.admin_grant_tickets('a0000000-0000-0000-0000-0000000000b1', 5, 'post', 'post-1', 'ajuste');
  assert (select saldo_tickets from public.carteras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000b1') = 40, 'normal movements continue the chain';
  assert (select count(*) from public.app_wallet_reconcile('a0000000-0000-0000-0000-0000000000b1')) = 0, 'and stay reconciled';
  raise notice 'OK V1 legacy backfill';
end $$;
-- mixed state (some rows with seq, some without) fails closed
set local session_replication_role = replica;
insert into public.carteras_usuario (usuario_id, saldo_tickets) values ('a0000000-0000-0000-0000-0000000000b5', 10);
insert into auth.users (id, email, aud, role) values ('a0000000-0000-0000-0000-0000000000b5','bf5@test.local','authenticated','authenticated');
insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, seq, saldo_anterior, saldo_resultante) values ('a0000000-0000-0000-0000-0000000000b5','recarga',10,'x',1,0,10);
insert into public.transacciones_cartera (usuario_id, tipo, cantidad, saldo_anterior, saldo_resultante, descripcion) values ('a0000000-0000-0000-0000-0000000000b5','regalo',5,10,15,'y');
set local session_replication_role = origin;
create function pg_temp.expect_e(p_sql text, p_msg text) returns void language plpgsql as $$
declare v_msg text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_msg = message_text; end;
  if v_msg is distinct from p_msg then raise exception 'ASSERT FAILED: % -> expected % got %', p_sql, p_msg, v_msg; end if;
end $$;
select pg_temp.expect_e($q$select private.v1_ledger_backfill('a0000000-0000-0000-0000-0000000000b5')$q$, 'V1_LEDGER_BACKFILL_MIXED');
rollback;

-- 20261022100500: V1 redeem an exam with OT in ONE transaction (atomic, idempotent, extension, expiry, accessibility, no price from the client).
begin;
create function pg_temp.expect_e(p_sql text, p_msg text) returns void language plpgsql as $$
declare v_msg text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_msg = message_text; end;
  if v_msg is distinct from p_msg then raise exception 'ASSERT FAILED: % -> expected % got %', p_sql, p_msg, v_msg; end if;
end $$;
create function pg_temp.expect_state(p_sql text, p_state text) returns void language plpgsql as $$
declare v_state text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_state = returned_sqlstate; end;
  if v_state is distinct from p_state then raise exception 'ASSERT FAILED: % -> expected sqlstate % got %', p_sql, p_state, v_state; end if;
end $$;
insert into auth.users (id, email, aud, role) values
  ('a0000000-0000-0000-0000-0000000000f1','rd1@test.local','authenticated','authenticated'),
  ('a0000000-0000-0000-0000-0000000000f2','rd2@test.local','authenticated','authenticated'),
  ('a0000000-0000-0000-0000-0000000000f3','rd3@test.local','authenticated','authenticated');
insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-0000000000f1','RX','RX','rx',true), ('e1000000-0000-0000-0000-0000000000f2','RY','RY','ry',true);
insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('c1000000-0000-0000-0000-0000000000f1','CF','Com F','com-f');
insert into public.capacidades_especialidad (especialidad_id, permite_rafagas, permite_razonados, permite_inmersivos, permite_demos)
  values ('e1000000-0000-0000-0000-0000000000f2', true, true, false, true);
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, comprable_con_tickets, precio_tickets, dias_acceso_compra, demo_disponible) values
  ('e5000000-0000-0000-0000-0000000000a1','R-BUY','e1000000-0000-0000-0000-0000000000f1','c1000000-0000-0000-0000-0000000000f1','Compra','inmersivo','premium',true, now() - interval '1 day', true, 100, 30, false),
  ('e5000000-0000-0000-0000-0000000000a2','R-NOTBUY','e1000000-0000-0000-0000-0000000000f1','c1000000-0000-0000-0000-0000000000f1','No comprable','inmersivo','premium',true, now() - interval '1 day', false, 100, 30, false),
  ('e5000000-0000-0000-0000-0000000000a3','R-FUTURE','e1000000-0000-0000-0000-0000000000f1','c1000000-0000-0000-0000-0000000000f1','Futuro','inmersivo','premium',true, now() + interval '1 day', true, 100, 30, false),
  ('e5000000-0000-0000-0000-0000000000a4','R-FREE','e1000000-0000-0000-0000-0000000000f1','c1000000-0000-0000-0000-0000000000f1','Gratis','rafaga','gratuito',true, now() - interval '1 day', true, 100, 30, false),
  ('e5000000-0000-0000-0000-0000000000a5','R-NOPRICE','e1000000-0000-0000-0000-0000000000f1','c1000000-0000-0000-0000-0000000000f1','Sin precio','inmersivo','premium',true, now() - interval '1 day', true, null, 30, false),
  ('e5000000-0000-0000-0000-0000000000a6','R-CAPOFF','e1000000-0000-0000-0000-0000000000f2','c1000000-0000-0000-0000-0000000000f1','Modo apagado','inmersivo','premium',true, now() - interval '1 day', true, 100, 30, false),
  ('e5000000-0000-0000-0000-0000000000a7','R-DEMO','e1000000-0000-0000-0000-0000000000f1','c1000000-0000-0000-0000-0000000000f1','Demo','rafaga','premium',true, now() - interval '1 day', true, 100, 30, true),
  ('e5000000-0000-0000-0000-0000000000a8','R-ONE','e1000000-0000-0000-0000-0000000000f1','c1000000-0000-0000-0000-0000000000f1','Un OT','inmersivo','premium',true, now() - interval '1 day', true, 1, 30, false);
-- The specialty is an access gate and a user WITHOUT one is denied (owner 2026-10-16, E2-V1-E06): the redeem users hold the specialty of the exams they buy (RX).
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id) values ('a0000000-0000-0000-0000-0000000000f1', 'rd1', 'rd1', true, 'activa', 'e1000000-0000-0000-0000-0000000000f1') on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id) values ('a0000000-0000-0000-0000-0000000000f2', 'rd2', 'rd2', true, 'activa', 'e1000000-0000-0000-0000-0000000000f1') on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id) values ('a0000000-0000-0000-0000-0000000000f3', 'rd3', 'rd3', true, 'activa', 'e1000000-0000-0000-0000-0000000000f1') on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
select public.admin_grant_tickets('a0000000-0000-0000-0000-0000000000f1', 500, 'seed', 'seed-1', 'ajuste');

-- ---- refusals write NOTHING
select pg_temp.expect_e($q$select * from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f1','e5000000-0000-0000-0000-0000000000a2','k-1')$q$, 'NOT_PURCHASABLE');
select pg_temp.expect_e($q$select * from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f1','e5000000-0000-0000-0000-0000000000a5','k-1')$q$, 'NOT_PURCHASABLE');
select pg_temp.expect_e($q$select * from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f1','e5000000-0000-0000-0000-0000000000a3','k-1')$q$, 'EXAM_NOT_FOUND');
select pg_temp.expect_e($q$select * from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f1','e5000000-0000-0000-0000-0000000000ff','k-1')$q$, 'EXAM_NOT_FOUND');
select pg_temp.expect_e($q$select * from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f1','e5000000-0000-0000-0000-0000000000a1','k-1', 90)$q$, 'PRICE_CHANGED');
select pg_temp.expect_e($q$select * from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f1','e5000000-0000-0000-0000-0000000000a4','k-1')$q$, 'ALREADY_ACCESSIBLE');
select pg_temp.expect_e($q$select * from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f2','e5000000-0000-0000-0000-0000000000a1','k-1')$q$, 'INSUFFICIENT_BALANCE');
select pg_temp.expect_e($q$select * from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f1','e5000000-0000-0000-0000-0000000000a1','')$q$, 'IDEMPOTENCY_KEY_REQUIRED');
select pg_temp.expect_e($q$select * from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000ff','e5000000-0000-0000-0000-0000000000a1','k-1')$q$, 'WALLET_INVALID_USER');
do $$
declare d text;
begin
  begin perform * from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f2','e5000000-0000-0000-0000-0000000000a1','k-poor');
  exception when others then get stacked diagnostics d = pg_exception_detail; end;
  assert d::jsonb = '{"balance":0,"price":100}'::jsonb, 'INSUFFICIENT_BALANCE carries the SERVER numbers: ' || coalesce(d, 'null');
  begin perform * from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f1','e5000000-0000-0000-0000-0000000000a1','k-px', 90);
  exception when others then get stacked diagnostics d = pg_exception_detail; end;
  assert d::jsonb = '{"price":100}'::jsonb, 'PRICE_CHANGED carries the server price';
  assert (select saldo_tickets from public.carteras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000f1') = 500, 'refusals left the balance untouched';
  assert (select count(*) from public.transacciones_cartera where usuario_id = 'a0000000-0000-0000-0000-0000000000f1') = 1, 'refusals wrote no ledger row';
  assert not exists (select 1 from public.contenido_desbloqueado_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000f1'), 'and no entitlement';
end $$;

-- ---- success, replay, extension
do $$
declare r record; r2 record; first_until timestamptz; first_tx uuid;
begin
  select * into r from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f1','e5000000-0000-0000-0000-0000000000a1','k-buy-1', 100);
  assert r.outcome = 'CREATED' and r.precio_pagado = 100 and r.saldo = 400, 'debit and balance come from the server';
  assert r.valido_hasta between now() + interval '29 days 23 hours' and now() + interval '30 days 1 hour', 'access is 30 days (dias_acceso_compra)';
  first_until := r.valido_hasta; first_tx := r.transaccion_id;
  assert (select count(*) from public.contenido_desbloqueado_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000f1' and examen_id = 'e5000000-0000-0000-0000-0000000000a1' and motivo = 'compra') = 1, 'one purchased row';
  assert exists (select 1 from public.contenido_desbloqueado_usuario g where g.transaccion_id = first_tx and g.precio_pagado = 100 and g.moneda = 'OPOSITICKETS' and g.comprado_en is not null and g.valido_hasta = first_until), 'entitlement carries price, currency, transaction and purchase time';
  assert (select state from private.exam_access_verdict('a0000000-0000-0000-0000-0000000000f1','e5000000-0000-0000-0000-0000000000a1', clock_timestamp())) = 'GRANTED', 'the verdict sees the grant';
  assert (select sum(cantidad) from public.ot_consumos where debit_tx_id = first_tx) = 100, 'the debit is traced to its credit';
  -- same key, same body: the SAME result, one debit
  select * into r2 from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f1','e5000000-0000-0000-0000-0000000000a1','k-buy-1', 100);
  assert r2.outcome = 'REPLAY' and r2.transaccion_id = first_tx and r2.valido_hasta = first_until and r2.saldo = 400, 'replay returns the stored result';
  assert (select count(*) from public.transacciones_cartera where usuario_id = 'a0000000-0000-0000-0000-0000000000f1' and tipo = 'compra_examen') = 1, 'one debit';
  -- re-purchase while active EXTENDS: old end + 30 days, balance -100
  select * into r2 from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f1','e5000000-0000-0000-0000-0000000000a1','k-buy-2');
  assert r2.outcome = 'CREATED' and r2.valido_hasta = first_until + interval '30 days' and r2.saldo = 300, 'extension = max(now, current end) + 30 days';
  assert r2.valido_desde = r.valido_desde, 'an active entitlement keeps its start';
  assert (select count(*) from public.contenido_desbloqueado_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000f1' and examen_id = 'e5000000-0000-0000-0000-0000000000a1' and motivo = 'compra') = 1, 'still ONE purchased row';
  assert r2.valido_hasta > first_until, 'never shortened';
end $$;
select pg_temp.expect_e($q$select * from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f1','e5000000-0000-0000-0000-0000000000a8','k-buy-1')$q$, 'IDEMPOTENCY_KEY_REUSED');
-- expired entitlement: a re-buy starts NOW (not at the old end)
do $$
declare r record;
begin
  update public.contenido_desbloqueado_usuario set valido_hasta = now() - interval '1 day', valido_desde = now() - interval '31 days'
   where usuario_id = 'a0000000-0000-0000-0000-0000000000f1' and examen_id = 'e5000000-0000-0000-0000-0000000000a1';
  assert (select state from private.exam_access_verdict('a0000000-0000-0000-0000-0000000000f1','e5000000-0000-0000-0000-0000000000a1', clock_timestamp())) = 'LOCKED', 'expired entitlement is enforced by the server';
  select * into r from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f1','e5000000-0000-0000-0000-0000000000a1','k-buy-3');
  assert r.valido_desde >= now() - interval '1 minute' and r.valido_hasta between now() + interval '29 days 23 hours' and now() + interval '30 days 1 hour' and r.saldo = 200, 'a re-buy after expiry restarts from now';
  assert (select state from private.exam_access_verdict('a0000000-0000-0000-0000-0000000000f1','e5000000-0000-0000-0000-0000000000a1', clock_timestamp())) = 'GRANTED', 'and it opens again';
end $$;
-- E2-V1-E03: the base of an extension is max(valido_hasta) of the exam grants regardless of valido_desde, and the clock is read after the lock.
-- A grant that starts a second AFTER this transaction began (what a concurrent purchase committed just before us looks like) must still be extended, not ignored.
insert into auth.users (id, email, aud, role) values ('a0000000-0000-0000-0000-0000000000f6','rd6@test.local','authenticated','authenticated');
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id) values ('a0000000-0000-0000-0000-0000000000f6', 'rd6', 'rd6', true, 'activa', 'e1000000-0000-0000-0000-0000000000f1') on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
do $$
declare r record; seed_until timestamptz;
begin
  perform public.admin_grant_tickets('a0000000-0000-0000-0000-0000000000f6', 300, 'seed', 'seed-e03', 'ajuste');
  seed_until := now() + interval '40 days';
  insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('a0000000-0000-0000-0000-0000000000f6','e5000000-0000-0000-0000-0000000000a1','regalo', now() + interval '1 second', seed_until);
  select * into r from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f6','e5000000-0000-0000-0000-0000000000a1','k-e03-1');
  assert r.valido_hasta >= seed_until + interval '29 days 23 hours', 'extension starts from the latest end even if that grant started a moment ago: ' || r.valido_hasta;
  select * into r from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f6','e5000000-0000-0000-0000-0000000000a1','k-e03-2');
  assert r.valido_hasta >= seed_until + interval '59 days 23 hours', 'two purchases give 60 days from the base, never 30: ' || r.valido_hasta;
  assert (select count(*) from public.transacciones_cartera where usuario_id = 'a0000000-0000-0000-0000-0000000000f6' and tipo = 'compra_examen') = 2, 'both purchases were debited';
end $$;
-- E2-V1-E05: the COLECCION union (grants and attempts) takes the specialty predicate: a stored grant of another specialty is not listed
do $$
declare own jsonb; other jsonb;
begin
  set local session_replication_role = replica;
  insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('a0000000-0000-0000-0000-0000000000f1','e5000000-0000-0000-0000-0000000000a8','regalo', now() - interval '1 day', null);
  set local session_replication_role = origin;
  own := public.app_my_exams_collection('a0000000-0000-0000-0000-0000000000f1', null, null, null, null, null, 50, false, 'e1000000-0000-0000-0000-0000000000f1');
  other := public.app_my_exams_collection('a0000000-0000-0000-0000-0000000000f1', null, null, null, null, null, 50, false, 'e1000000-0000-0000-0000-0000000000f2');
  assert jsonb_array_length(own -> 'items') >= 1, 'own specialty lists the stored grant';
  assert jsonb_array_length(other -> 'items') = 0, 'another specialty lists nothing';
  delete from public.contenido_desbloqueado_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000f1' and examen_id = 'e5000000-0000-0000-0000-0000000000a8' and motivo = 'regalo';
end $$;
-- SPECIALTY GATE: the user's specialty is set (RX = e1..f1); an exam of another specialty (RY = e1..f2) is invisible: verdict LOCKED/SPECIALTY_MISMATCH, redeem = EXAM_NOT_FOUND, nothing written.
do $$
declare before_bal integer;
begin
  insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
    values ('a0000000-0000-0000-0000-0000000000f3', 'rd3', 'rd3', true, 'activa', 'e1000000-0000-0000-0000-0000000000f1') on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
  assert (select state || ':' || reason from private.exam_access_verdict('a0000000-0000-0000-0000-0000000000f3','e5000000-0000-0000-0000-0000000000a6', clock_timestamp())) = 'LOCKED:SPECIALTY_MISMATCH', 'an exam of another specialty is locked by rule 0';
  assert (select state from private.exam_access_verdict('a0000000-0000-0000-0000-0000000000f3','e5000000-0000-0000-0000-0000000000a4', clock_timestamp())) = 'FREE', 'an exam of the own specialty is unaffected';
  update public.examenes set especialidad_id = 'e1000000-0000-0000-0000-0000000000f2' where id = 'e5000000-0000-0000-0000-0000000000a8';
  select saldo_tickets into before_bal from public.carteras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000f3';
  begin perform * from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f3','e5000000-0000-0000-0000-0000000000a8','k-spec'); assert false, 'must refuse';
  exception when others then assert sqlerrm = 'EXAM_NOT_FOUND', 'out-of-specialty redeem: ' || sqlerrm; end;
  assert (select saldo_tickets from public.carteras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000f3') = before_bal and not exists (select 1 from public.transacciones_cartera where idempotency_key = 'k-spec'), 'nothing written';
  update public.examenes set especialidad_id = 'e1000000-0000-0000-0000-0000000000f1' where id = 'e5000000-0000-0000-0000-0000000000a8';
  -- the specialty cannot be changed once set (same value = no-op; NULL -> value allowed)
  begin update public.perfiles_usuario set especialidad_principal_id = 'e1000000-0000-0000-0000-0000000000f2' where usuario_id = 'a0000000-0000-0000-0000-0000000000f3'; assert false, 'must refuse';
  exception when others then assert sqlerrm = 'SPECIALTY_ALREADY_SET', 'change refused: ' || sqlerrm; end;
  begin update public.perfiles_usuario set especialidad_principal_id = null where usuario_id = 'a0000000-0000-0000-0000-0000000000f3'; assert false, 'must refuse';
  exception when others then assert sqlerrm = 'SPECIALTY_ALREADY_SET', 'clear refused: ' || sqlerrm; end;
  update public.perfiles_usuario set especialidad_principal_id = 'e1000000-0000-0000-0000-0000000000f1' where usuario_id = 'a0000000-0000-0000-0000-0000000000f3';
  assert (select especialidad_principal_id from public.perfiles_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000f3') = 'e1000000-0000-0000-0000-0000000000f1', 'the same value is a no-op';
end $$;
-- exact balance == price, then nothing left
do $$
declare r record;
begin
  perform public.admin_grant_tickets('a0000000-0000-0000-0000-0000000000f3', 1, 'seed', 'seed-3', 'ajuste');
  select * into r from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f3','e5000000-0000-0000-0000-0000000000a8','k-one');
  assert r.saldo = 0 and r.precio_pagado = 1, 'balance == price buys and leaves exactly 0';
  assert (select count(*) from public.app_wallet_reconcile(null)) = 0, 'reconcile clean';
end $$;
-- the CAPABILITY switch of the user's own specialty does not block a purchase (no C3); the policy still reports LOCKED/CAPABILITY_DISABLED. User f8 holds RY (inmersivos off), exam a6 is RY.
insert into auth.users (id, email, aud, role) values ('a0000000-0000-0000-0000-0000000000f8','rd8@test.local','authenticated','authenticated');
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id) values ('a0000000-0000-0000-0000-0000000000f8', 'rd8', 'rd8', true, 'activa', 'e1000000-0000-0000-0000-0000000000f2') on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
select public.admin_grant_tickets('a0000000-0000-0000-0000-0000000000f8', 300, 'seed', 'seed-8', 'ajuste');
do $$
declare r record;
begin
  select * into r from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f8','e5000000-0000-0000-0000-0000000000a6','k-cap');
  assert r.outcome = 'CREATED', 'purchase is not refused because of the capability switch (owner decision, C3 withdrawn)';
  assert (select reason from private.exam_access_verdict('a0000000-0000-0000-0000-0000000000f8','e5000000-0000-0000-0000-0000000000a6', clock_timestamp())) = 'CAPABILITY_DISABLED', 'rule 0 capability is unchanged';
  -- a user with NO specialty (f9: profile without one) is denied (E2-V1-E06): verdict and redeem
  insert into auth.users (id, email, aud, role) values ('a0000000-0000-0000-0000-0000000000f9','rd9@test.local','authenticated','authenticated');
  perform public.admin_grant_tickets('a0000000-0000-0000-0000-0000000000f9', 300, 'seed', 'seed-9', 'ajuste');
  assert (select reason from private.exam_access_verdict('a0000000-0000-0000-0000-0000000000f9','e5000000-0000-0000-0000-0000000000a6', clock_timestamp())) = 'SPECIALTY_MISMATCH', 'no specialty = denied';
  begin perform * from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f9','e5000000-0000-0000-0000-0000000000a6','k-cap-null'); assert false, 'must refuse';
  exception when others then assert sqlerrm = 'EXAM_NOT_FOUND', 'no specialty redeem: ' || sqlerrm; end;
end $$;
-- subscription and unlimited grants make a purchase pointless
-- Access v2 (client 2026-10-16): a subscription covers a rafaga from nivel 1, a razonado from nivel 2 and NEVER an immersive by itself.
insert into public.planes_suscripcion (id, codigo, nombre, nivel) values ('b1000000-0000-0000-0000-0000000000f1', 'plan-rafagas-f', 'Rafagas', 1);
insert into public.productos_comerciales (id, codigo, tipo_producto, nombre, precio_centimos, moneda, activo, visible, plan_id) values ('b2000000-0000-0000-0000-0000000000f1', 'sub-rafagas-f', 'suscripcion', 'Sub rafagas', 299, 'EUR', true, true, 'b1000000-0000-0000-0000-0000000000f1');
insert into public.estado_premium_usuario (usuario_id, es_premium, estado_suscripcion, producto_codigo, fin_en) values ('a0000000-0000-0000-0000-0000000000f2', true, 'activa', 'sub-rafagas-f', now() + interval '10 days');
select public.admin_grant_tickets('a0000000-0000-0000-0000-0000000000f2', 300, 'seed', 'seed-2', 'ajuste');
select pg_temp.expect_e($q$select * from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f2','e5000000-0000-0000-0000-0000000000a7','k-sub')$q$, 'ALREADY_ACCESSIBLE');
do $$ begin
  assert (select state from private.exam_access_verdict('a0000000-0000-0000-0000-0000000000f2', 'e5000000-0000-0000-0000-0000000000a7')) = 'SUBSCRIPTION', 'a rafagas plan covers a rafaga exam';
  assert (select state from private.exam_access_verdict('a0000000-0000-0000-0000-0000000000f2', 'e5000000-0000-0000-0000-0000000000a1')) = 'LOCKED', 'a subscription does NOT open an immersive by itself';
end $$;
set local session_replication_role = replica; -- legacy unlimited gift rows (the table guard refuses new ones)
insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('a0000000-0000-0000-0000-0000000000f3', 'e5000000-0000-0000-0000-0000000000a1', 'admin', now() - interval '1 day', null);
set local session_replication_role = origin;
select public.admin_grant_tickets('a0000000-0000-0000-0000-0000000000f3', 200, 'seed', 'seed-4', 'ajuste');
select pg_temp.expect_e($q$select * from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f3','e5000000-0000-0000-0000-0000000000a1','k-unl')$q$, 'ALREADY_ACCESSIBLE');
-- a purchased row cannot be written incomplete
select pg_temp.expect_e($q$insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('a0000000-0000-0000-0000-0000000000f2','e5000000-0000-0000-0000-0000000000a8','compra', now(), now() + interval '1 day')$q$, 'ENTITLEMENT_PURCHASE_INCOMPLETE');
-- verdict matrix spot checks: DEMO and NOT_ENTITLED
do $$ begin
  assert (select state from private.exam_access_verdict('a0000000-0000-0000-0000-0000000000f2','e5000000-0000-0000-0000-0000000000a7', clock_timestamp())) = 'SUBSCRIPTION', 'premium user gets SUBSCRIPTION';
  assert (select state from private.exam_access_verdict('a0000000-0000-0000-0000-0000000000f1','e5000000-0000-0000-0000-0000000000a7', clock_timestamp())) = 'DEMO', 'demo exam without entitlement is DEMO';
  assert (select reason from private.exam_access_verdict('a0000000-0000-0000-0000-0000000000f3','e5000000-0000-0000-0000-0000000000a2', clock_timestamp())) = 'NOT_ENTITLED', 'no entitlement is LOCKED';
end $$;
-- atomicity: a failure AFTER the debit leaves no trace (balance, ledger and entitlement all roll back)
create function pg_temp.boom() returns trigger language plpgsql as $$ begin raise exception 'BOOM'; end $$;
do $$
declare before_bal int; ok boolean := false;
begin
  select saldo_tickets into before_bal from public.carteras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000f1';
  create trigger trg_boom before insert on public.contenido_desbloqueado_usuario for each row when (new.motivo = 'compra') execute function pg_temp.boom();
  begin
    perform * from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f1','e5000000-0000-0000-0000-0000000000a8','k-atomic');
  exception when others then ok := sqlerrm = 'BOOM';
  end;
  drop trigger trg_boom on public.contenido_desbloqueado_usuario;
  assert ok, 'the injected failure surfaced';
  assert (select saldo_tickets from public.carteras_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000f1') = before_bal, 'balance rolled back';
  assert not exists (select 1 from public.transacciones_cartera where idempotency_key = 'k-atomic'), 'ledger row rolled back';
  assert not exists (select 1 from public.contenido_desbloqueado_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000f1' and examen_id = 'e5000000-0000-0000-0000-0000000000a8'), 'entitlement rolled back';
  assert (select count(*) from public.app_wallet_reconcile(null)) = 0, 'reconcile still clean';
  assert abs(extract(epoch from (public.app_server_now() - clock_timestamp()))) < 5, 'app_server_now is the database clock';
  assert has_function_privilege('service_role', 'public.app_server_now()', 'execute')
    and not has_function_privilege('anon', 'public.app_server_now()', 'execute') and not has_function_privilege('authenticated', 'public.app_server_now()', 'execute'), 'app_server_now is service_role only';
  raise notice 'OK V1 redeem';
end $$;
rollback;

-- 20261022100700: admin gift of an exam = ONE month set by the server, never unlimited, gates like any access, no wallet effect.
begin;
create function pg_temp.expect_e(p_sql text, p_msg text) returns void language plpgsql as $$
declare v_msg text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_msg = message_text; end;
  if v_msg is distinct from p_msg then raise exception 'ASSERT FAILED: % -> expected % got %', p_sql, p_msg, v_msg; end if;
end $$;
insert into auth.users (id, email, aud, role) values
  ('a0000000-0000-0000-0000-0000000000c1','gf1@test.local','authenticated','authenticated'), ('a0000000-0000-0000-0000-0000000000c2','gf2@test.local','authenticated','authenticated'),
  ('a0000000-0000-0000-0000-0000000000c3','gf3@test.local','authenticated','authenticated'), ('a0000000-0000-0000-0000-0000000000c4','gf4@test.local','authenticated','authenticated');
insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-0000000000c1','GA','GA','ga',true), ('e1000000-0000-0000-0000-0000000000c2','GB','GB','gb',true);
insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('c1000000-0000-0000-0000-0000000000c1','CG','Com G','com-g');
insert into public.capacidades_especialidad (especialidad_id, permite_rafagas, permite_razonados, permite_inmersivos, permite_demos) values ('e1000000-0000-0000-0000-0000000000c1', true, true, false, true);
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, dias_acceso_compra) values
  ('e5000000-0000-0000-0000-0000000000c1','G-OK','e1000000-0000-0000-0000-0000000000c1','c1000000-0000-0000-0000-0000000000c1','ok','rafaga','premium',true, now() - interval '1 day', 30),
  ('e5000000-0000-0000-0000-0000000000c2','G-OTHER','e1000000-0000-0000-0000-0000000000c2','c1000000-0000-0000-0000-0000000000c1','otra especialidad','rafaga','premium',true, now() - interval '1 day', 30),
  ('e5000000-0000-0000-0000-0000000000c3','G-CAP','e1000000-0000-0000-0000-0000000000c1','c1000000-0000-0000-0000-0000000000c1','capacidad apagada','inmersivo','premium',true, now() - interval '1 day', 30),
  ('e5000000-0000-0000-0000-0000000000c4','G-DRAFT','e1000000-0000-0000-0000-0000000000c1','c1000000-0000-0000-0000-0000000000c1','borrador','rafaga','premium',false, null, 30);
update public.perfiles_usuario set especialidad_principal_id = 'e1000000-0000-0000-0000-0000000000c1' where usuario_id in ('a0000000-0000-0000-0000-0000000000c1', 'a0000000-0000-0000-0000-0000000000c4');
do $$
declare r record; r2 record; first_until timestamptz;
begin
  select * into r from public.admin_grant_exam_access('a0000000-0000-0000-0000-0000000000c1', 'e5000000-0000-0000-0000-0000000000c1', 'admin', 'a0000000-0000-0000-0000-0000000000c2');
  assert r.outcome = 'CREATED' and r.valido_hasta between now() + interval '29 days 23 hours' and now() + interval '30 days 1 hour', 'a gift is ONE month set by the server: ' || r.valido_hasta;
  first_until := r.valido_hasta;
  assert (select count(*) from public.auditoria_log where accion = 'acceso_examen_concedido' and registro_id = r.grant_id and usuario_id = 'a0000000-0000-0000-0000-0000000000c2' and (despues ->> 'valido_hasta') is not null) = 1, 'the audit row commits WITH the grant, with the actor';
  select * into r2 from public.admin_grant_exam_access('a0000000-0000-0000-0000-0000000000c1', 'e5000000-0000-0000-0000-0000000000c1', 'admin', 'a0000000-0000-0000-0000-0000000000c2');
  assert r2.outcome = 'REPLAY' and (select count(*) from public.auditoria_log where accion = 'acceso_examen_concedido' and registro_id = r.grant_id) = 1, 'a replay writes no second audit row';
  select * into r2 from public.admin_grant_exam_access('a0000000-0000-0000-0000-0000000000c1', 'e5000000-0000-0000-0000-0000000000c1', 'admin', 'a0000000-0000-0000-0000-0000000000c2');
  assert r2.outcome = 'REPLAY' and r2.grant_id = r.grant_id and r2.valido_hasta = first_until, 'a double click is a replay, not a second month';
  -- stacking (another reason so it is not the double click): max(now, current end) + 30 days, never shortened
  select * into r2 from public.admin_grant_exam_access('a0000000-0000-0000-0000-0000000000c1', 'e5000000-0000-0000-0000-0000000000c1', 'regalo');
  assert r2.outcome = 'CREATED' and r2.valido_hasta >= first_until + interval '29 days 23 hours' and r2.valido_hasta > first_until, 'stacking extends from the current end: ' || r2.valido_hasta;
  assert not exists (select 1 from public.contenido_desbloqueado_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000c1' and valido_hasta is null), 'a gift never creates an unlimited row';
  assert (select state from private.exam_access_verdict('a0000000-0000-0000-0000-0000000000c1', 'e5000000-0000-0000-0000-0000000000c1', clock_timestamp())) = 'GRANTED', 'the verdict sees the gift';
  assert not exists (select 1 from public.transacciones_cartera where usuario_id = 'a0000000-0000-0000-0000-0000000000c1'), 'a gift is not an OT debit: the wallet is untouched';
  -- a legacy unlimited grant has nothing to extend
  set local session_replication_role = replica;
  insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('a0000000-0000-0000-0000-0000000000c4', 'e5000000-0000-0000-0000-0000000000c1', 'regalo', now() - interval '1 day', null);
  set local session_replication_role = origin;
  select * into r2 from public.admin_grant_exam_access('a0000000-0000-0000-0000-0000000000c4', 'e5000000-0000-0000-0000-0000000000c1', 'admin');
  assert r2.outcome = 'UNLIMITED_EXISTS' and (select count(*) from public.contenido_desbloqueado_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000c4') = 1, 'an active unlimited grant is returned as is';
  -- table guard: a NEW unlimited gift row is refused; a legacy unlimited row stays valid and an update that keeps NULL is allowed
  begin insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('a0000000-0000-0000-0000-0000000000c1', 'e5000000-0000-0000-0000-0000000000c1', 'promocion', now(), null); assert false, 'must refuse';
  exception when others then assert sqlerrm = 'GIFT_UNLIMITED_FORBIDDEN', 'table guard: ' || sqlerrm; end;
  begin insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('a0000000-0000-0000-0000-0000000000c1', 'e5000000-0000-0000-0000-0000000000c1', 'premium', now(), null); assert false, 'must refuse';
  exception when others then assert sqlerrm = 'GIFT_UNLIMITED_FORBIDDEN', 'E2-V1-G09: premium is guarded too: ' || sqlerrm; end;
  update public.contenido_desbloqueado_usuario set valido_hasta = null where usuario_id = 'a0000000-0000-0000-0000-0000000000c4' and examen_id = 'e5000000-0000-0000-0000-0000000000c1';
  begin update public.contenido_desbloqueado_usuario set valido_hasta = null where usuario_id = 'a0000000-0000-0000-0000-0000000000c1' and examen_id = 'e5000000-0000-0000-0000-0000000000c1' and motivo = 'admin'; assert false, 'must refuse';
  exception when others then assert sqlerrm = 'GIFT_UNLIMITED_FORBIDDEN', 'a limited gift cannot be made unlimited: ' || sqlerrm; end;
  -- revoke: ONE atomic function; closes every active manual grant of that user and exam (admin + regalo), audited once, idempotent, never a purchase
  select * into r2 from public.admin_revoke_exam_access(r.grant_id, 'a0000000-0000-0000-0000-0000000000c2');
  assert r2.outcome = 'REVOKED' and r2.concesiones = 2, 'revoke closes both active gifts: ' || r2.outcome || '/' || r2.concesiones;
  assert (select state from private.exam_access_verdict('a0000000-0000-0000-0000-0000000000c1', 'e5000000-0000-0000-0000-0000000000c1', clock_timestamp() + interval '1 second')) = 'LOCKED', 'after the revoke nothing is active';
  assert (select count(*) from public.auditoria_log where accion = 'acceso_examen_retirado' and registro_id = r.grant_id and usuario_id = 'a0000000-0000-0000-0000-0000000000c2') = 1, 'the revoke audit commits with it';
  select * into r2 from public.admin_revoke_exam_access(r.grant_id, 'a0000000-0000-0000-0000-0000000000c2');
  assert r2.outcome = 'ALREADY_INACTIVE' and (select count(*) from public.auditoria_log where accion = 'acceso_examen_retirado' and registro_id = r.grant_id) = 1, 'a second revoke is a no-op: no second audit row';
  assert not exists (select 1 from public.transacciones_cartera where usuario_id = 'a0000000-0000-0000-0000-0000000000c1'), 'revoke does not touch the wallet either';
  set local session_replication_role = replica;
  insert into public.contenido_desbloqueado_usuario (id, usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('a0000000-0000-0000-0000-0000000000b9', 'a0000000-0000-0000-0000-0000000000c3', 'e5000000-0000-0000-0000-0000000000c1', 'compra', now() - interval '1 day', now() + interval '10 days');
  set local session_replication_role = origin;
end $$;
select pg_temp.expect_e($q$select * from public.admin_revoke_exam_access('a0000000-0000-0000-0000-0000000000b9')$q$, 'GRANT_IS_PURCHASE');
select pg_temp.expect_e($q$select * from public.admin_revoke_exam_access('a0000000-0000-0000-0000-0000000000ba')$q$, 'GRANT_NOT_FOUND');
select pg_temp.expect_e($q$select * from public.admin_grant_exam_access('a0000000-0000-0000-0000-0000000000c1', 'e5000000-0000-0000-0000-0000000000c2', 'admin')$q$, 'GIFT_NOT_USABLE');
select pg_temp.expect_e($q$select * from public.admin_grant_exam_access('a0000000-0000-0000-0000-0000000000c1', 'e5000000-0000-0000-0000-0000000000c3', 'admin')$q$, 'GIFT_NOT_USABLE');
select pg_temp.expect_e($q$select * from public.admin_grant_exam_access('a0000000-0000-0000-0000-0000000000c2', 'e5000000-0000-0000-0000-0000000000c1', 'admin')$q$, 'GIFT_NOT_USABLE');
select pg_temp.expect_e($q$select * from public.admin_grant_exam_access('a0000000-0000-0000-0000-0000000000c1', 'e5000000-0000-0000-0000-0000000000c4', 'admin')$q$, 'GIFT_EXAM_NOT_AVAILABLE');
select pg_temp.expect_e($q$select * from public.admin_grant_exam_access('a0000000-0000-0000-0000-0000000000c1', 'e5000000-0000-0000-0000-0000000000ff', 'admin')$q$, 'GIFT_EXAM_NOT_AVAILABLE');
select pg_temp.expect_e($q$select * from public.admin_grant_exam_access('a0000000-0000-0000-0000-0000000000c1', 'e5000000-0000-0000-0000-0000000000c1', 'compra')$q$, 'GIFT_REASON_NOT_ALLOWED');
select pg_temp.expect_e($q$select * from public.admin_grant_exam_access('a0000000-0000-0000-0000-0000000000ee', 'e5000000-0000-0000-0000-0000000000c1', 'admin')$q$, 'GIFT_USER_NOT_FOUND');
do $$ begin raise notice 'OK V1 admin gift'; end $$;
rollback;

-- 20261022100800: V1 saved questions (Guardar + OposiTeca): idempotent save/unsave, per-user scope, specialty gate + access verdict, cap, keyset list.
begin;
create function pg_temp.expect_e(p_sql text, p_msg text) returns void language plpgsql as $$
declare v_msg text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_msg = message_text; end;
  if v_msg is distinct from p_msg then raise exception 'ASSERT FAILED: % -> expected % got %', p_sql, p_msg, v_msg; end if;
end $$;
insert into auth.users (id, email, aud, role) values
  ('a0000000-0000-0000-0000-0000000000b1','sv1@test.local','authenticated','authenticated'),
  ('a0000000-0000-0000-0000-0000000000b2','sv2@test.local','authenticated','authenticated'),
  ('a0000000-0000-0000-0000-0000000000b3','sv3@test.local','authenticated','authenticated');
insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-0000000000b1','SA','SA','sa',true), ('e1000000-0000-0000-0000-0000000000b2','SB','SB','sb',true);
insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('c1000000-0000-0000-0000-0000000000b1','CB','Com B','com-b');
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id) values
  ('a0000000-0000-0000-0000-0000000000b1','sv1','sv1',true,'activa','e1000000-0000-0000-0000-0000000000b1'),
  ('a0000000-0000-0000-0000-0000000000b2','sv2','sv2',true,'activa','e1000000-0000-0000-0000-0000000000b2')
  on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
set local session_replication_role = replica;  -- the code trigger overwrites codigo_pregunta from a private sequence the gate bootstrap does not carry
-- b2 free (SA), b1 premium (SA), b3 free (SB)
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, demo_disponible) values
  ('e5000000-0000-0000-0000-0000000000b1','S-PREM','e1000000-0000-0000-0000-0000000000b1','c1000000-0000-0000-0000-0000000000b1','Premium SA','rafaga','premium',true, now() - interval '1 day', false),
  ('e5000000-0000-0000-0000-0000000000b2','S-FREE','e1000000-0000-0000-0000-0000000000b1','c1000000-0000-0000-0000-0000000000b1','Gratis SA','rafaga','gratuito',true, now() - interval '2 days', false),
  ('e5000000-0000-0000-0000-0000000000b3','S-OTHER','e1000000-0000-0000-0000-0000000000b2','c1000000-0000-0000-0000-0000000000b1','Gratis SB','rafaga','gratuito',true, now() - interval '1 day', false);
-- q01..q09 (q05 inactive), q10 only in the SB exam, q11 only in the premium SA exam
insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
select ('f5000000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'SV-' || n, 'rafaga', 'Pregunta guardable ' || n, n <> 5
  from generate_series(1, 9) n;
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, demo_disponible) values
  ('e5000000-0000-0000-0000-0000000000b4','S-DEMO','e1000000-0000-0000-0000-0000000000b1','c1000000-0000-0000-0000-0000000000b1','Premium con demo SA','rafaga','premium',true, now() - interval '1 day', true);
insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) values
  ('f5000000-0000-0000-0000-000000000012', 'SV-12', 'rafaga', 'Solo demo', true),
  ('f5000000-0000-0000-0000-000000000010', 'SV-10', 'rafaga', 'Pregunta de SB', true),
  ('f5000000-0000-0000-0000-000000000011', 'SV-11', 'rafaga', 'Solo premium', true);
-- the free SA exam holds q01..q09 (q04 annulled there); the premium one holds q02 (also free elsewhere) and q11
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, anulada)
select 'e5000000-0000-0000-0000-0000000000b2', ('f5000000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, n, n, n = 4 from generate_series(1, 9) n;
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen) values
  ('e5000000-0000-0000-0000-0000000000b1','f5000000-0000-0000-0000-000000000002', 1, 1),
  ('e5000000-0000-0000-0000-0000000000b1','f5000000-0000-0000-0000-000000000011', 2, 2),
  ('e5000000-0000-0000-0000-0000000000b3','f5000000-0000-0000-0000-000000000010', 1, 1),
  ('e5000000-0000-0000-0000-0000000000b4','f5000000-0000-0000-0000-000000000012', 1, 1);
set local session_replication_role = origin;

do $$
declare
  u1 uuid := 'a0000000-0000-0000-0000-0000000000b1'; u2 uuid := 'a0000000-0000-0000-0000-0000000000b2';
  first_ts timestamptz; r record; n int; ids uuid[];
  v_last_ts timestamptz; v_last_id uuid; v_seen uuid[] := '{}'; v_page int := 0; v_rows int; rr record;
begin
  -- save, idempotent
  select guardado_en into first_ts from public.app_save_item(u1, 'pregunta', 'f5000000-0000-0000-0000-000000000001');
  assert first_ts is not null, 'saved';
  assert (select guardado_en from public.app_save_item(u1, 'pregunta', 'f5000000-0000-0000-0000-000000000001')) = first_ts, 'saving twice returns the ORIGINAL guardado_en';
  assert (select count(*) from public.recursos_guardados_usuario where usuario_id = u1) = 1, 'no duplicate row';
  -- gates: premium-only question without access, another specialty, annulled, inactive, unknown
  perform pg_temp.expect_e($q$select * from public.app_save_item('a0000000-0000-0000-0000-0000000000b1','pregunta','f5000000-0000-0000-0000-000000000011')$q$, 'NOT_FOUND');
  perform pg_temp.expect_e($q$select * from public.app_save_item('a0000000-0000-0000-0000-0000000000b1','pregunta','f5000000-0000-0000-0000-000000000010')$q$, 'NOT_FOUND');
  assert (select state from private.exam_access_verdict(u1, 'e5000000-0000-0000-0000-0000000000b4')) = 'DEMO', 'fixture: the exam is a DEMO for u1';
  perform pg_temp.expect_e($q$select * from public.app_save_item('a0000000-0000-0000-0000-0000000000b1','pregunta','f5000000-0000-0000-0000-000000000012')$q$, 'NOT_FOUND');  -- E2-V1-S01: DEMO is not enough to save
  perform pg_temp.expect_e($q$select * from public.app_save_item('a0000000-0000-0000-0000-0000000000b1','pregunta','f5000000-0000-0000-0000-000000000004')$q$, 'NOT_FOUND');
  perform pg_temp.expect_e($q$select * from public.app_save_item('a0000000-0000-0000-0000-0000000000b1','pregunta','f5000000-0000-0000-0000-000000000005')$q$, 'NOT_FOUND');
  perform pg_temp.expect_e($q$select * from public.app_save_item('a0000000-0000-0000-0000-0000000000b1','pregunta','f5000000-0000-0000-0000-0000000000ff')$q$, 'NOT_FOUND');
  perform pg_temp.expect_e($q$select * from public.app_save_item('a0000000-0000-0000-0000-0000000000b1','recurso','f5000000-0000-0000-0000-000000000001')$q$, 'SAVED_INVALID');
  perform pg_temp.expect_e($q$select * from public.app_save_item('a0000000-0000-0000-0000-0000000000b9','pregunta','f5000000-0000-0000-0000-000000000001')$q$, 'WALLET_INVALID_USER');
  assert (select count(*) from public.recursos_guardados_usuario where usuario_id = u1) = 1, 'refusals wrote nothing';
  -- q02 is in the premium exam AND in a free exam of the user's specialty: saveable through the free one
  perform public.app_save_item(u1, 'pregunta', 'f5000000-0000-0000-0000-000000000002');
  -- the other specialty saves its own, and cannot save u1's content
  perform public.app_save_item(u2, 'pregunta', 'f5000000-0000-0000-0000-000000000010');
  perform pg_temp.expect_e($q$select * from public.app_save_item('a0000000-0000-0000-0000-0000000000b2','pregunta','f5000000-0000-0000-0000-000000000001')$q$, 'NOT_FOUND');
  assert (select count(*) from public.app_saved_state(u2, 'pregunta', array['f5000000-0000-0000-0000-000000000001'::uuid, 'f5000000-0000-0000-0000-000000000010'::uuid])) = 1, 'state is per user';
  -- premium question after a grant
  insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta)
    values (u1, 'e5000000-0000-0000-0000-0000000000b1', 'admin', now() - interval '1 day', now() + interval '10 days');
  perform public.app_save_item(u1, 'pregunta', 'f5000000-0000-0000-0000-000000000011');
  select * into r from public.app_list_saved(u1, 'pregunta', null, null, 10) l where l.target_id = 'f5000000-0000-0000-0000-000000000011';
  assert r.accesible and r.titulo = 'Solo premium' and r.examen_titulo = 'Premium SA', 'accessible item carries the title and the exam';
  -- access lost later: row stays, content hidden, a re-save still answers
  update public.contenido_desbloqueado_usuario set valido_desde = now() - interval '2 days', valido_hasta = now() - interval '1 second' where usuario_id = u1 and examen_id = 'e5000000-0000-0000-0000-0000000000b1';
  select * into r from public.app_list_saved(u1, 'pregunta', null, null, 10) l where l.target_id = 'f5000000-0000-0000-0000-000000000011';
  assert not r.accesible and r.titulo is null and r.examen_id is null, 'lost access: item stays, content hidden';
  perform public.app_save_item(u1, 'pregunta', 'f5000000-0000-0000-0000-000000000011');
  -- state
  select array_agg(s.pregunta_id) into ids from public.app_saved_state(u1, 'pregunta', array['f5000000-0000-0000-0000-000000000001'::uuid, 'f5000000-0000-0000-0000-000000000003'::uuid, 'f5000000-0000-0000-0000-000000000002'::uuid]) s;
  assert cardinality(ids) = 2, 'state returns only saved ids';
  perform pg_temp.expect_e($q$select * from public.app_saved_state('a0000000-0000-0000-0000-0000000000b1','pregunta', (select array_agg(gen_random_uuid()) from generate_series(1, 51)))$q$, 'SAVED_INVALID');
  -- keyset paging: u1 has q01, q02, q11 saved; add q06..q09 -> 7 rows, pages of 3
  for n in 6..9 loop perform public.app_save_item(u1, 'pregunta', ('f5000000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid); end loop;
  loop
    v_rows := 0;
    for rr in select * from public.app_list_saved(u1, 'pregunta', v_last_ts, v_last_id, 3) loop
      v_rows := v_rows + 1; v_seen := v_seen || rr.target_id; v_last_ts := rr.guardado_en; v_last_id := rr.id;
    end loop;
    exit when v_rows = 0;
    v_page := v_page + 1;
    assert v_page < 10, 'paging terminates';
  end loop;
  assert cardinality(v_seen) = 7 and (select count(distinct x) from unnest(v_seen) x) = 7, 'keyset pages cover every saved row exactly once';
  assert v_seen[1] = 'f5000000-0000-0000-0000-000000000009', 'newest first';
  -- unsave: scoped, idempotent
  assert public.app_unsave_item(u2, 'pregunta', 'f5000000-0000-0000-0000-000000000001') = 0, 'another user cannot remove my row';
  assert public.app_unsave_item(u1, 'pregunta', 'f5000000-0000-0000-0000-000000000001') = 1, 'removed';
  assert public.app_unsave_item(u1, 'pregunta', 'f5000000-0000-0000-0000-000000000001') = 0, 'unsave twice is a no-op';
end $$;
-- cap (500 per user): fixture rows under replica (the code trigger would overwrite codigo_pregunta)
set local session_replication_role = replica;
insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
  select gen_random_uuid(), 'CAP-' || g, 'rafaga', 'cap ' || g, true from generate_series(1, 600) g;
insert into public.recursos_guardados_usuario (usuario_id, tipo, pregunta_id)
  select 'a0000000-0000-0000-0000-0000000000b1', 'pregunta', p.id from public.preguntas p where p.codigo_pregunta like 'CAP-%' order by p.codigo_pregunta
   limit (500 - (select count(*) from public.recursos_guardados_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000b1'))::int;
set local session_replication_role = origin;
do $$
declare u1 uuid := 'a0000000-0000-0000-0000-0000000000b1';
begin
  assert (select count(*) from public.recursos_guardados_usuario where usuario_id = u1) = 500, 'at the cap';
  perform pg_temp.expect_e($q$select * from public.app_save_item('a0000000-0000-0000-0000-0000000000b1','pregunta','f5000000-0000-0000-0000-000000000001')$q$, 'SAVED_LIMIT_REACHED');
  assert (select guardado_en from public.app_save_item(u1, 'pregunta', 'f5000000-0000-0000-0000-000000000002')) is not null, 'an already saved item still answers at the cap';
  -- no client path
  assert not has_table_privilege('anon', 'public.recursos_guardados_usuario', 'select') and not has_table_privilege('authenticated', 'public.recursos_guardados_usuario', 'select')
     and not has_table_privilege('service_role', 'public.recursos_guardados_usuario', 'insert') and not has_table_privilege('service_role', 'public.recursos_guardados_usuario', 'delete'), 'table is RPC-only';
  assert not has_function_privilege('authenticated', 'public.app_save_item(uuid,text,uuid)', 'execute') and has_function_privilege('service_role', 'public.app_save_item(uuid,text,uuid)', 'execute'), 'function grants';
end $$;
-- deleting a question removes the saved rows
delete from public.preguntas_examen where pregunta_id = 'f5000000-0000-0000-0000-000000000010';
delete from public.preguntas where id = 'f5000000-0000-0000-0000-000000000010';
do $$ begin
  assert not exists (select 1 from public.recursos_guardados_usuario where pregunta_id = 'f5000000-0000-0000-0000-000000000010'), 'on delete cascade from the question';
  raise notice 'OK V1 saved items';
end $$;
rollback;

-- 20261022100900: V1 Access v2 (plan coverage, quota choice, subscription lifecycle, benefit bounds).
begin;
create function pg_temp.expect_e(p_sql text, p_msg text) returns void language plpgsql as $$
declare v_msg text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_msg = message_text; end;
  if v_msg is distinct from p_msg then raise exception 'ASSERT FAILED: % -> expected % got %', p_sql, p_msg, v_msg; end if;
end $$;
create function pg_temp.vs(p_u uuid, p_e uuid) returns text language sql as $f$ select state from private.exam_access_verdict(p_u, p_e, clock_timestamp()) $f$;
insert into auth.users (id, email, aud, role) values
  ('a0000000-0000-0000-0000-0000000000a1','pl0@test.local','authenticated','authenticated'), ('a0000000-0000-0000-0000-0000000000a2','pl1@test.local','authenticated','authenticated'),
  ('a0000000-0000-0000-0000-0000000000a3','pl2@test.local','authenticated','authenticated'), ('a0000000-0000-0000-0000-0000000000a4','pl3@test.local','authenticated','authenticated'),
  ('a0000000-0000-0000-0000-0000000000a5','pl4@test.local','authenticated','authenticated');
insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-0000000000a1','PA','PA','pa',true), ('e1000000-0000-0000-0000-0000000000a2','PB','PB','pb',true);
insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('c1000000-0000-0000-0000-0000000000a1','CPA','Com PA','com-pa');
insert into public.capacidades_especialidad (especialidad_id, permite_rafagas, permite_razonados, permite_inmersivos, permite_demos) values ('e1000000-0000-0000-0000-0000000000a2', true, true, false, true);
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
  select u, 'pl' || right(u::text, 1), 'pl' || right(u::text, 1), true, 'activa', 'e1000000-0000-0000-0000-0000000000a1'
  from (values ('a0000000-0000-0000-0000-0000000000a1'::uuid), ('a0000000-0000-0000-0000-0000000000a2'::uuid), ('a0000000-0000-0000-0000-0000000000a3'::uuid), ('a0000000-0000-0000-0000-0000000000a4'::uuid), ('a0000000-0000-0000-0000-0000000000a5'::uuid)) v(u)
  on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
set local session_replication_role = replica;
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, comprable_con_tickets, precio_tickets, dias_acceso_compra, demo_disponible) values
  ('e5000000-0000-0000-0000-0000000000e1','PL-RAF','e1000000-0000-0000-0000-0000000000a1','c1000000-0000-0000-0000-0000000000a1','Rafaga premium','rafaga','premium',true, now() - interval '1 day', false, null, 30, false),
  ('e5000000-0000-0000-0000-0000000000e2','PL-RAZ','e1000000-0000-0000-0000-0000000000a1','c1000000-0000-0000-0000-0000000000a1','Razonado premium','razonado','premium',true, now() - interval '1 day', false, null, 30, false),
  ('e5000000-0000-0000-0000-0000000000e3','PL-IM1','e1000000-0000-0000-0000-0000000000a1','c1000000-0000-0000-0000-0000000000a1','Inmersivo 1','inmersivo','premium',true, now() - interval '1 day', true, 50, 30, false),
  ('e5000000-0000-0000-0000-0000000000e4','PL-IM2','e1000000-0000-0000-0000-0000000000a1','c1000000-0000-0000-0000-0000000000a1','Inmersivo 2','inmersivo','premium',true, now() - interval '1 day', true, 50, 30, false),
  ('e5000000-0000-0000-0000-0000000000e5','PL-IM3','e1000000-0000-0000-0000-0000000000a1','c1000000-0000-0000-0000-0000000000a1','Inmersivo 3','inmersivo','premium',true, now() - interval '1 day', true, 50, 30, false),
  ('e5000000-0000-0000-0000-0000000000e6','PL-OTHER','e1000000-0000-0000-0000-0000000000a2','c1000000-0000-0000-0000-0000000000a1','Inmersivo otra especialidad','inmersivo','premium',true, now() - interval '1 day', true, 50, 30, false),
  ('e5000000-0000-0000-0000-0000000000e7','PL-DRAFT','e1000000-0000-0000-0000-0000000000a1','c1000000-0000-0000-0000-0000000000a1','Inmersivo borrador','inmersivo','premium',false, null, true, 50, 30, false),
  ('e5000000-0000-0000-0000-0000000000e8','PL-FREE','e1000000-0000-0000-0000-0000000000a1','c1000000-0000-0000-0000-0000000000a1','Rafaga gratis','rafaga','gratuito',true, now() - interval '1 day', false, null, 30, false),
  ('e5000000-0000-0000-0000-0000000000e9','PL-RAFB','e1000000-0000-0000-0000-0000000000a1','c1000000-0000-0000-0000-0000000000a1','Rafaga comprable','rafaga','premium',true, now() - interval '1 day', true, 10, 30, false);
set local session_replication_role = origin;
insert into public.planes_suscripcion (id, codigo, nombre, nivel) values
  ('b1000000-0000-0000-0000-0000000000a0','plan-gratuito','Gratuito',0), ('b1000000-0000-0000-0000-0000000000a1','plan-rafagas','Rafagas',1),
  ('b1000000-0000-0000-0000-0000000000a2','plan-razonados','Razonados',2), ('b1000000-0000-0000-0000-0000000000a3','plan-inmersivo','Inmersivo',3);
insert into public.productos_comerciales (id, codigo, tipo_producto, nombre, precio_centimos, moneda, activo, visible, plan_id) values
  ('b2000000-0000-0000-0000-0000000000a1','sub-rafagas','suscripcion','Sub rafagas',299,'EUR',true,true,'b1000000-0000-0000-0000-0000000000a1'),
  ('b2000000-0000-0000-0000-0000000000a2','sub-razonados','suscripcion','Sub razonados',699,'EUR',true,true,'b1000000-0000-0000-0000-0000000000a2'),
  ('b2000000-0000-0000-0000-0000000000a3','sub-inmersivo','suscripcion','Sub inmersivo',999,'EUR',true,true,'b1000000-0000-0000-0000-0000000000a3'),
  ('b2000000-0000-0000-0000-0000000000a9','sub-huerfana','suscripcion','Sub sin plan',999,'EUR',true,true,null);
insert into public.beneficios_plan (plan_id, codigo_beneficio, valor_integer) values ('b1000000-0000-0000-0000-0000000000a3', 'inmersivos_incluidos', 2);

do $$
declare
  u0 uuid := 'a0000000-0000-0000-0000-0000000000a1'; u1 uuid := 'a0000000-0000-0000-0000-0000000000a2'; u2 uuid := 'a0000000-0000-0000-0000-0000000000a3';
  u3 uuid := 'a0000000-0000-0000-0000-0000000000a4'; u4 uuid := 'a0000000-0000-0000-0000-0000000000a5';
  raf uuid := 'e5000000-0000-0000-0000-0000000000e1'; raz uuid := 'e5000000-0000-0000-0000-0000000000e2'; im1 uuid := 'e5000000-0000-0000-0000-0000000000e3';
  im2 uuid := 'e5000000-0000-0000-0000-0000000000e4'; im3 uuid := 'e5000000-0000-0000-0000-0000000000e5'; other uuid := 'e5000000-0000-0000-0000-0000000000e6';
  draft uuid := 'e5000000-0000-0000-0000-0000000000e7'; free uuid := 'e5000000-0000-0000-0000-0000000000e8';
  r record; h1 timestamptz; h2 timestamptz; n int;
  function_state text;
begin
  insert into public.estado_premium_usuario (usuario_id, es_premium, estado_suscripcion, producto_codigo, inicio_en, fin_en) values
    (u1, true, 'activa', 'sub-rafagas', now() - interval '5 days', now() + interval '25 days'),
    (u2, true, 'activa', 'sub-razonados', now() - interval '5 days', now() + interval '25 days'),
    (u3, true, 'activa', 'sub-inmersivo', now() - interval '5 days', now() + interval '25 days'),
    (u4, true, 'activa', 'sub-huerfana', now() - interval '5 days', now() + interval '25 days');
  -- ---- plan coverage
  assert pg_temp.vs(u0, raf) = 'LOCKED' and pg_temp.vs(u0, free) = 'FREE', 'no subscription: only free';
  assert pg_temp.vs(u1, raf) = 'SUBSCRIPTION' and pg_temp.vs(u1, raz) = 'LOCKED', 'rafagas plan: rafagas yes, razonados no';
  assert pg_temp.vs(u2, raf) = 'SUBSCRIPTION' and pg_temp.vs(u2, raz) = 'SUBSCRIPTION' and pg_temp.vs(u2, im1) = 'LOCKED', 'razonados plan: rafagas + razonados, never an immersive';
  assert pg_temp.vs(u3, raz) = 'SUBSCRIPTION' and pg_temp.vs(u3, im1) = 'LOCKED', 'inmersivo plan: the immersive still needs a choice or OT';
  assert pg_temp.vs(u4, raf) = 'LOCKED', 'a product without plan covers nothing (fail closed)';
  -- cancelada keeps the paid period; without an end, or terminal, it does not
  update public.estado_premium_usuario set estado_suscripcion = 'cancelada' where usuario_id = u1;
  assert pg_temp.vs(u1, raf) = 'SUBSCRIPTION', 'cancelada with paid time left keeps access (Kotlin wins)';
  update public.estado_premium_usuario set fin_en = null where usuario_id = u1;
  assert pg_temp.vs(u1, raf) = 'LOCKED', 'cancelada without a known end: no access';
  update public.estado_premium_usuario set estado_suscripcion = 'caducada', fin_en = now() - interval '1 day' where usuario_id = u1;
  assert pg_temp.vs(u1, raf) = 'LOCKED', 'caducada: no access';
  update public.estado_premium_usuario set estado_suscripcion = 'activa', fin_en = now() + interval '25 days' where usuario_id = u1;

  -- ---- choose: order of checks
  perform pg_temp.expect_e(format($q$select * from public.app_choose_plan_exam(%L, %L)$q$, u3, other), 'EXAM_NOT_FOUND');   -- other specialty: gate
  perform pg_temp.expect_e(format($q$select * from public.app_choose_plan_exam(%L, %L)$q$, u3, draft), 'EXAM_NOT_FOUND');   -- unpublished
  perform pg_temp.expect_e(format($q$select * from public.app_choose_plan_exam(%L, %L)$q$, u3, gen_random_uuid()), 'EXAM_NOT_FOUND');
  perform pg_temp.expect_e(format($q$select * from public.app_choose_plan_exam(%L, %L)$q$, u3, raz), 'EXAM_NOT_IMMERSIVE');
  perform pg_temp.expect_e(format($q$select * from public.app_choose_plan_exam(%L, %L)$q$, u0, im1), 'PLAN_QUOTA_NONE');
  perform pg_temp.expect_e(format($q$select * from public.app_choose_plan_exam(%L, %L)$q$, u2, im1), 'PLAN_QUOTA_NONE');   -- razonados plan has no quota
  perform pg_temp.expect_e(format($q$select * from public.app_choose_plan_exam(%L, %L)$q$, u4, im1), 'PLAN_QUOTA_NONE');   -- unresolved plan
  perform pg_temp.expect_e(format($q$select * from public.app_choose_plan_exam(%L, %L)$q$, 'a0000000-0000-0000-0000-0000000000ff', im1), 'WALLET_INVALID_USER');
  -- the writer guard
  perform pg_temp.expect_e(format($q$insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta, cupo_suscripcion_inicio) values (%L, %L, 'plan_cupo', now(), now() + interval '1 day', now())$q$, u0, im1), 'PLAN_CUPO_WRITER_ONLY');
  -- choose: idempotent, counts, verdict GRANTED until the paid period end
  select * into r from public.app_choose_plan_exam(u3, im1);
  assert r.outcome = 'CREATED' and r.hasta between now() + interval '24 days 23 hours' and now() + interval '25 days 1 hour', 'quota choice lasts until the paid period end';
  h1 := r.hasta;
  assert pg_temp.vs(u3, im1) = 'GRANTED', 'a quota exam is GRANTED';
  select * into r from public.app_choose_plan_exam(u3, im1);
  assert r.outcome = 'EXISTS' and r.hasta = h1 and (select count(*) from public.contenido_desbloqueado_usuario where usuario_id = u3 and motivo = 'plan_cupo') = 1, 'choosing twice is a no-op';
  perform public.app_choose_plan_exam(u3, im2);
  perform pg_temp.expect_e(format($q$select * from public.app_choose_plan_exam(%L, %L)$q$, u3, im3), 'PLAN_QUOTA_FULL');
  assert (select count(*) from public.auditoria_log where accion = 'plan_cupo_elegido' and usuario_id = u3) = 2, 'every choice is audited';
  -- a purchase of a quota-held exam is refused (nothing to buy)
  perform public.admin_grant_tickets(u3, 200, 'seed', 'seed-pl3', 'ajuste');
  perform pg_temp.expect_e(format($q$select * from public.app_redeem_exam_access(%L, %L, 'k-pl-quota')$q$, u3, im1), 'ALREADY_ACCESSIBLE');
  -- ---- ALREADY_ACCESSIBLE comes from the SAME v2 verdict (E5-V1-QA-INT06 F1): only FREE / GRANTED(unlimited or non-purchase) / SUBSCRIPTION (plan coverage); never when LOCKED
  perform public.admin_grant_tickets(u1, 100, 'seed', 'seed-pl-u1', 'ajuste');
  perform public.admin_grant_tickets(u2, 100, 'seed', 'seed-pl-u2', 'ajuste');
  assert pg_temp.vs(u1, 'e5000000-0000-0000-0000-0000000000e9') = 'SUBSCRIPTION' and pg_temp.vs(u1, im1) = 'LOCKED', 'precondition: rafagas plan covers the rafaga, not the immersive';
  perform pg_temp.expect_e(format($q$select * from public.app_redeem_exam_access(%L, %L, 'k-pl-u1-raf')$q$, u1, 'e5000000-0000-0000-0000-0000000000e9'), 'ALREADY_ACCESSIBLE');   -- plan coverage of a rafaga: not bought again
  select * into r from public.app_redeem_exam_access(u1, im1, 'k-pl-u1-im1');
  assert r.outcome = 'CREATED' and pg_temp.vs(u1, im1) = 'GRANTED', 'a Rafagas subscriber buys an immersive with OT (LOCKED verdict is never ALREADY_ACCESSIBLE)';
  select * into r from public.app_redeem_exam_access(u2, im1, 'k-pl-u2-im1');
  assert r.outcome = 'CREATED', 'a Razonados subscriber buys an immersive with OT';
  perform pg_temp.expect_e(format($q$select * from public.app_redeem_exam_access(%L, %L, 'k-pl-u2-raf')$q$, u2, 'e5000000-0000-0000-0000-0000000000e9'), 'ALREADY_ACCESSIBLE');
  delete from public.contenido_desbloqueado_usuario where usuario_id in (u1, u2) and motivo = 'compra';
  -- an exam already owned by a gift / purchase is not spent from the quota
  insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values (u3, im3, 'admin', now() - interval '1 hour', now() + interval '5 days');
  perform pg_temp.expect_e(format($q$select * from public.app_choose_plan_exam(%L, %L)$q$, u3, im3), 'ALREADY_ACCESSIBLE');
  delete from public.contenido_desbloqueado_usuario where usuario_id = u3 and examen_id = im3;
  -- ---- benefit bounds (C-A2): malformed / out of range = no quota
  update public.beneficios_plan set valor_integer = 9999 where plan_id = 'b1000000-0000-0000-0000-0000000000a3';
  assert private.plan_benefit_int('b1000000-0000-0000-0000-0000000000a3', 'inmersivos_incluidos', 0, 4) is null, 'out of range = NULL';
  update public.beneficios_plan set valor_integer = null, valor_numeric = 2 where plan_id = 'b1000000-0000-0000-0000-0000000000a3';
  assert private.plan_benefit_int('b1000000-0000-0000-0000-0000000000a3', 'inmersivos_incluidos', 0, 4) is null, 'a numeric value is not an integer benefit';
  update public.beneficios_plan set valor_numeric = null, valor_integer = 2, valor_text = 'x' where plan_id = 'b1000000-0000-0000-0000-0000000000a3';
  assert private.plan_benefit_int('b1000000-0000-0000-0000-0000000000a3', 'inmersivos_incluidos', 0, 4) is null, 'two values = malformed';
  update public.beneficios_plan set valor_text = null where plan_id = 'b1000000-0000-0000-0000-0000000000a3';
  assert private.plan_benefit_int('b1000000-0000-0000-0000-0000000000a3', 'inmersivos_incluidos', 0, 4) = 2, 'restored';
  -- ---- lifecycle: renewal extends the OLDEST N; lowering the quota never revokes; a terminal state closes at once
  select hasta into h1 from public.app_choose_plan_exam(u3, im1);
  update public.beneficios_plan set valor_integer = 1 where plan_id = 'b1000000-0000-0000-0000-0000000000a3';
  update public.estado_premium_usuario set fin_en = now() + interval '55 days' where usuario_id = u3;
  assert (select valido_hasta from public.contenido_desbloqueado_usuario where usuario_id = u3 and examen_id = im1 and motivo = 'plan_cupo') > now() + interval '54 days', 'renewal extends the oldest choice';
  assert (select valido_hasta from public.contenido_desbloqueado_usuario where usuario_id = u3 and examen_id = im2 and motivo = 'plan_cupo') < now() + interval '26 days', 'the choice beyond the lowered quota is NOT extended and NOT revoked';
  assert pg_temp.vs(u3, im2) = 'GRANTED', 'not revoked before the paid period ends';
  update public.estado_premium_usuario set es_premium = false, estado_suscripcion = 'sin_suscripcion', fin_en = null where usuario_id = u3;
  assert pg_temp.vs(u3, im1) = 'LOCKED' and pg_temp.vs(u3, im2) = 'LOCKED', 'terminal state closes the quota rows at once';
  update public.beneficios_plan set valor_integer = 2 where plan_id = 'b1000000-0000-0000-0000-0000000000a3';
  -- a NEW subscription after the lapse starts with an empty quota
  update public.estado_premium_usuario set es_premium = true, estado_suscripcion = 'activa', producto_codigo = 'sub-inmersivo', inicio_en = now(), fin_en = now() + interval '30 days' where usuario_id = u3;
  select * into r from public.app_choose_plan_exam(u3, im3);
  assert r.outcome = 'CREATED' and private.plan_quota_used(u3, (select inicio_en from public.estado_premium_usuario where usuario_id = u3), clock_timestamp()) = 1, 'new subscription: empty quota';
  -- ---- projection from suscripciones (Kotlin wins on cancelada; terminal motives deny at once)
  insert into public.suscripciones (usuario_id, proveedor, producto_codigo, estado, inicio_en, fin_en) values (u0, 'google_play', 'sub-rafagas', 'cancelada', now() - interval '2 days', now() + interval '10 days');
  assert (select estado_suscripcion from public.estado_premium_usuario where usuario_id = u0) = 'cancelada' and pg_temp.vs(u0, raf) = 'SUBSCRIPTION', 'cancelada keeps access until fin_en through the projection';
  update public.suscripciones set fin_motivo = 'reembolso' where usuario_id = u0;
  assert (select estado_suscripcion from public.estado_premium_usuario where usuario_id = u0) = 'sin_suscripcion' and pg_temp.vs(u0, raf) = 'LOCKED', 'refund: denied at once';
  -- ---- configuration edits are audited
  update public.configuracion_v1 set valor_integer = 6 where clave = 'demo_preguntas';
  assert exists (select 1 from public.auditoria_log where accion = 'configuracion_v1_editada' and despues ->> 'clave' = 'demo_preguntas' and (despues ->> 'valor_integer')::int = 6), 'config edits are audited';
  update public.configuracion_v1 set valor_integer = 5 where clave = 'demo_preguntas';
  raise notice 'OK V1 access v2';
end $$;
rollback;

-- 20261022101000: V1 demo mode (the 5 flagged questions only, never the first answer, never ranking, limits).
begin;
create function pg_temp.expect_e(p_sql text, p_msg text) returns void language plpgsql as $$
declare v_msg text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_msg = message_text; end;
  if v_msg is distinct from p_msg then raise exception 'ASSERT FAILED: % -> expected % got %', p_sql, p_msg, v_msg; end if;
end $$;
insert into auth.users (id, email, aud, role) values
  ('a0000000-0000-0000-0000-0000000000c1','dm1@test.local','authenticated','authenticated'), ('a0000000-0000-0000-0000-0000000000c2','dm2@test.local','authenticated','authenticated');
insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-0000000000c1','DA','DA','da',true);
insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('c1000000-0000-0000-0000-0000000000c1','CDA','Com DA','com-da');
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
  select u, 'dm' || right(u::text, 1), 'dm' || right(u::text, 1), true, 'activa', 'e1000000-0000-0000-0000-0000000000c1'
  from (values ('a0000000-0000-0000-0000-0000000000c1'::uuid), ('a0000000-0000-0000-0000-0000000000c2'::uuid)) v(u)
  on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
set local session_replication_role = replica;
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, demo_disponible) values
  ('e5000000-0000-0000-0000-0000000000c1','DM-IMM','e1000000-0000-0000-0000-0000000000c1','c1000000-0000-0000-0000-0000000000c1','Inmersivo con demo','inmersivo','premium',true, now() - interval '1 day', false),
  ('e5000000-0000-0000-0000-0000000000c2','DM-NODEMO','e1000000-0000-0000-0000-0000000000c1','c1000000-0000-0000-0000-0000000000c1','Inmersivo sin demo','inmersivo','premium',true, now() - interval '1 day', false);
insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
select ('f6000000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'DM-' || n, 'inmersiva', 'Pregunta demo ' || n, true from generate_series(1, 8) n;
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
select ('0c600000-0000-0000-0000-000000000' || lpad(n::text, 2, '0') || o)::uuid, ('f6000000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, chr(64 + o), 'Opcion', o
from generate_series(1, 8) n, generate_series(1, 2) o;
insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
select ('f6000000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, ('0c600000-0000-0000-0000-000000000' || lpad(n::text, 2, '0') || '1')::uuid, 'porque si' from generate_series(1, 8) n;
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen)
select 'e5000000-0000-0000-0000-0000000000c1', ('f6000000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, n, n from generate_series(1, 8) n;
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen) values ('e5000000-0000-0000-0000-0000000000c2', 'f6000000-0000-0000-0000-000000000001', 1, 1);
set local session_replication_role = origin;

do $$
declare
  u1 uuid := 'a0000000-0000-0000-0000-0000000000c1'; u2 uuid := 'a0000000-0000-0000-0000-0000000000c2';
  ex uuid := 'e5000000-0000-0000-0000-0000000000c1'; nodemo uuid := 'e5000000-0000-0000-0000-0000000000c2';
  r jsonb; a uuid; a2 uuid; n int;
  q1 uuid := 'f6000000-0000-0000-0000-000000000001'; q6 uuid := 'f6000000-0000-0000-0000-000000000006';
  ok1 uuid := '0c600000-0000-0000-0000-000000000011'; ok6 uuid := '0c600000-0000-0000-0000-000000000061';
begin
  -- the demo can only be switched on with a complete set, and the set cannot exceed the configured size
  perform pg_temp.expect_e(format($q$update public.examenes set demo_disponible = true where id = %L$q$, ex), 'DEMO_SET_INCOMPLETE');
  update public.preguntas_examen set es_demo = true where examen_id = ex and posicion <= 5;
  perform pg_temp.expect_e(format($q$update public.preguntas_examen set es_demo = true where examen_id = %L and posicion = 6$q$, ex), 'DEMO_SET_FULL');
  update public.examenes set demo_disponible = true where id = ex;
  -- start a DEMO: no access, so the attempt holds ONLY the 5 flagged questions
  r := public.app_start_exam_attempt(u1, ex, 'DEMO');
  assert r ->> 'outcome' = 'CREATED' and (r ->> 'es_demo')::boolean, 'demo attempt created: ' || r::text;
  a := (r ->> 'intento_id')::uuid;
  assert (select count(*) from public.preguntas_intento where intento_id = a) = 5, 'exactly 5 questions frozen';
  assert not exists (select 1 from public.preguntas_intento pi join public.preguntas_examen pe on pe.pregunta_id = pi.pregunta_id and pe.examen_id = ex where pi.intento_id = a and not pe.es_demo), 'only flagged questions';
  assert (select es_demo and acceso_estado = 'DEMO' and not cuenta_para_ranking and not es_primer_intento_examen and total_preguntas = 5 from public.intentos_examen where id = a), 'demo attempt flags';
  assert (public.app_start_exam_attempt(u1, ex, 'DEMO')) ->> 'outcome' = 'RESUMED', 'a second demo start resumes it';
  -- nothing beyond the 5
  assert (public.app_record_attempt_answer(u1, a, q6, '0c600000-0000-0000-0000-000000000061', 0, 0, null)) ->> 'outcome' = 'QUESTION_NOT_IN_ATTEMPT', 'a sixth question cannot be answered';
  r := public.app_record_attempt_answer(u1, a, q1, ok1, 0, 0, null);
  assert r ->> 'outcome' = 'OK' and not (r ->> 'es_primera_respuesta')::boolean, 'a demo answer is never the first answer';
  assert (select not es_primer_intento_pregunta and not cuenta_para_ranking from public.respuestas_intento where intento_id = a and pregunta_id = q1), 'demo answer row: not first, not ranking';
  -- a user WITHOUT demo availability / set is simply locked
  assert (public.app_start_exam_attempt(u1, nodemo, 'DEMO')) ->> 'outcome' = 'EXAM_LOCKED', 'no demo on that exam';
  -- per-user start limit (config)
  update public.configuracion_v1 set valor_integer = 1 where clave = 'demo_inicios_usuario_dia';
  perform public.app_abandon_exam_attempt(u1, a, 1, 'salida_usuario');
  assert (public.app_start_exam_attempt(u1, ex, 'DEMO')) ->> 'outcome' = 'DEMO_LIMIT', 'the per-user demo start limit';
  update public.configuracion_v1 set valor_integer = 20 where clave = 'demo_inicios_usuario_dia';
  -- a user WITH access that sends DEMO gets a normal attempt; the open demo of the other kind is closed, never resumed
  r := public.app_start_exam_attempt(u1, ex, 'DEMO');
  a2 := (r ->> 'intento_id')::uuid;
  insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values (u1, ex, 'admin', now() - interval '1 hour', now() + interval '5 days');
  r := public.app_start_exam_attempt(u1, ex, 'DEMO');
  assert r ->> 'outcome' = 'CREATED' and not (r ->> 'es_demo')::boolean and (r ->> 'intento_id')::uuid <> a2, 'with access, DEMO is a normal attempt: ' || r::text;
  assert (select estado from public.intentos_examen where id = a2) = 'abandonado', 'the open demo was closed';
  a := (r ->> 'intento_id')::uuid;
  assert (select intento_numero_usuario = 1 and es_primer_intento_examen and not es_demo and acceso_estado = 'GRANTED' from public.intentos_examen where id = a), 'a demo does not shift the numbering: the real attempt is the first';
  assert (select count(*) from public.preguntas_intento where intento_id = a) = 8, 'the real attempt holds all 8 questions';
  -- the first real answer to a question already seen in a demo: first answer, NOT ranking; to a question never seen: first answer and ranking
  r := public.app_record_attempt_answer(u1, a, q1, ok1, 0, 0, null);
  assert (r ->> 'es_primera_respuesta')::boolean, 'the real answer is the first answer (the demo did not consume it)';
  assert (select es_primer_intento_pregunta and not cuenta_para_ranking from public.respuestas_intento where intento_id = a and pregunta_id = q1), 'seen in a demo: first answer, but no ranking (note: the start RPC never sets cuenta_para_ranking, the column default false shadows the trigger coalesce: stats use es_primer_intento_pregunta)';
  r := public.app_record_attempt_answer(u1, a, q6, ok6, 0, 0, null);
  assert (select es_primer_intento_pregunta from public.respuestas_intento where intento_id = a and pregunta_id = q6), 'never seen: first answer';
  select count(*) into n from public.respuestas_intento where usuario_id = u1 and es_primer_intento_pregunta;
  assert n = 2, 'only the two real answers are first answers';
  raise notice 'OK V1 demo mode';
end $$;
rollback;

-- 20261022101100: V1 daily limiter L1 (questions revealed), plan summary, daily cap on OT bought.
begin;
create function pg_temp.expect_e(p_sql text, p_msg text) returns void language plpgsql as $$
declare v_msg text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_msg = message_text; end;
  if v_msg is distinct from p_msg then raise exception 'ASSERT FAILED: % -> expected % got %', p_sql, p_msg, v_msg; end if;
end $$;
insert into auth.users (id, email, aud, role) values
  ('a0000000-0000-0000-0000-0000000000d7','lm1@test.local','authenticated','authenticated'), ('a0000000-0000-0000-0000-0000000000d8','lm2@test.local','authenticated','authenticated');
insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-0000000000d7','LA','LA','la',true);
insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('c1000000-0000-0000-0000-0000000000d7','CLA','Com LA','com-la');
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
  select u, 'lm' || right(u::text, 1), 'lm' || right(u::text, 1), true, 'activa', 'e1000000-0000-0000-0000-0000000000d7'
  from (values ('a0000000-0000-0000-0000-0000000000d7'::uuid), ('a0000000-0000-0000-0000-0000000000d8'::uuid)) v(u)
  on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
set local session_replication_role = replica;
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en) values
  ('e5000000-0000-0000-0000-0000000000d7','LM-FREE','e1000000-0000-0000-0000-0000000000d7','c1000000-0000-0000-0000-0000000000d7','Rafaga gratis','rafaga','gratuito',true, now() - interval '1 day');
insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
select ('f7000000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'LM-' || n, 'rafaga', 'Pregunta limite ' || n, true from generate_series(1, 6) n;
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
select ('0d700000-0000-0000-0000-000000000' || lpad(n::text, 2, '0') || o)::uuid, ('f7000000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, chr(64 + o), 'Opcion', o from generate_series(1, 6) n, generate_series(1, 2) o;
insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
select ('f7000000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, ('0d700000-0000-0000-0000-000000000' || lpad(n::text, 2, '0') || '1')::uuid, 'porque si' from generate_series(1, 6) n;
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen)
select 'e5000000-0000-0000-0000-0000000000d7', ('f7000000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, n, n from generate_series(1, 6) n;
set local session_replication_role = origin;
insert into public.planes_suscripcion (id, codigo, nombre, nivel) values ('b1000000-0000-0000-0000-0000000000d7', 'gratuito-l', 'Gratuito', 0), ('b1000000-0000-0000-0000-0000000000d8', 'razonados-l', 'Razonados', 2);
insert into public.productos_comerciales (id, codigo, tipo_producto, nombre, precio_centimos, moneda, activo, visible, plan_id) values
  ('b2000000-0000-0000-0000-0000000000d8', 'sub-razonados-l', 'suscripcion', 'Sub', 699, 'EUR', true, true, 'b1000000-0000-0000-0000-0000000000d8');
insert into public.beneficios_plan (plan_id, codigo_beneficio, valor_integer) values ('b1000000-0000-0000-0000-0000000000d7', 'limite_preguntas_24h', 3);

do $$
declare
  u1 uuid := 'a0000000-0000-0000-0000-0000000000d7'; u2 uuid := 'a0000000-0000-0000-0000-0000000000d8'; ex uuid := 'e5000000-0000-0000-0000-0000000000d7';
  q uuid[] := array(select ('f7000000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid from generate_series(1, 6) n);
  a uuid; r jsonb; j jsonb;
begin
  -- free user, FREE-state attempt: limit 3 per rolling 24 h (benefit of the Gratuito plan)
  a := (public.app_start_exam_attempt(u1, ex, 'NORMAL') ->> 'intento_id')::uuid;
  assert (select acceso_estado from public.intentos_examen where id = a) = 'FREE', 'the access state is stored at start';
  assert (public.app_reveal_attempt_questions(u1, a, q[1:2])) ->> 'outcome' = 'OK', 'two questions fit';
  assert (public.app_reveal_attempt_questions(u1, a, q[1:2])) ->> 'outcome' = 'OK', 'serving the same questions again is free (resume)';
  r := public.app_reveal_attempt_questions(u1, a, q[1:4]);
  assert r ->> 'outcome' = 'DAILY_LIMIT_REACHED' and (r ->> 'retry_after_seconds')::int between 1 and 86400, 'the page that would reveal 2 NEW questions beyond 3 is refused all or nothing: ' || r::text;
  assert (select count(*) from public.preguntas_reveladas_usuario where usuario_id = u1) = 2, 'a refused page writes nothing';
  assert (public.app_reveal_attempt_questions(u1, a, q[1:3])) ->> 'outcome' = 'OK', 'one new question still fits (the 3rd)';
  r := public.app_reveal_attempt_questions(u1, a, q[4:4]);
  assert r ->> 'outcome' = 'DAILY_LIMIT_REACHED', 'the 4th new question is refused';
  -- the window slides: a question revealed more than 24 h ago stops counting
  update public.preguntas_reveladas_usuario set revelada_en = now() - interval '25 hours' where usuario_id = u1 and pregunta_id = q[1];
  assert (public.app_reveal_attempt_questions(u1, a, q[4:4])) ->> 'outcome' = 'OK', 'after the window frees a slot the next question fits';
  assert (public.app_reveal_attempt_questions(u1, gen_random_uuid(), q[1:1])) ->> 'outcome' = 'ATTEMPT_NOT_FOUND', 'another attempt / user';
  assert (public.app_reveal_attempt_questions(u2, a, q[1:1])) ->> 'outcome' = 'ATTEMPT_NOT_FOUND', 'attempts are per user';
  -- exempt: GRANTED (purchase, gift, quota) and DEMO are decided at start and stored; an unknown state is NOT exempt
  update public.intentos_examen set acceso_estado = 'GRANTED' where id = a;
  assert (public.app_reveal_attempt_questions(u1, a, q[5:6])) ->> 'outcome' = 'OK' and (select count(*) from public.preguntas_reveladas_usuario where usuario_id = u1) = 4, 'GRANTED attempts are exempt and write nothing';
  update public.intentos_examen set acceso_estado = null where id = a;
  assert (public.app_reveal_attempt_questions(u1, a, q[5:6])) ->> 'outcome' = 'DAILY_LIMIT_REACHED', 'unknown access state: not exempt (fail closed)';
  -- a plan without the benefit is unlimited: no lock, no write
  insert into public.estado_premium_usuario (usuario_id, es_premium, estado_suscripcion, producto_codigo, inicio_en, fin_en) values (u2, true, 'activa', 'sub-razonados-l', now() - interval '1 day', now() + interval '20 days');
  a := (public.app_start_exam_attempt(u2, ex, 'NORMAL') ->> 'intento_id')::uuid;
  assert (public.app_reveal_attempt_questions(u2, a, q)) ->> 'outcome' = 'OK' and not exists (select 1 from public.preguntas_reveladas_usuario where usuario_id = u2), 'no limit: nothing written';
  -- a malformed / out-of-range limit never turns the limiter off: the configured fallback applies (fail closed)
  update public.beneficios_plan set valor_integer = 99999 where plan_id = 'b1000000-0000-0000-0000-0000000000d7';
  assert private.plan_daily_limit(u1, clock_timestamp()) = 30, 'an out-of-range limit falls back to the configured value, not to unlimited';
  update public.beneficios_plan set valor_integer = 3 where plan_id = 'b1000000-0000-0000-0000-0000000000d7';
  -- plan summary payload (D7-31)
  j := public.app_plan_quota(u2);
  assert j ->> 'planCodigo' = 'razonados-l' and (j ->> 'nivel')::int = 2 and (j ->> 'cupoTotal')::int = 0 and (j ->> 'cupoLibre')::int = 0 and jsonb_array_length(j -> 'elegidos') = 0, 'plan summary of a razonados subscriber';
  j := public.app_plan_quota(u1);
  assert j ->> 'planCodigo' is null and (j ->> 'nivel')::int = 0 and (j ->> 'limitePreguntas24h')::int = 3 and (j ->> 'reveladas24h')::int = 3, 'plan summary of a free user: limit and meter: ' || j::text;
  -- ---- daily cap on OT bought (C-P2)
  insert into public.productos_comerciales (id, codigo, tipo_producto, nombre, precio_centimos, moneda, cantidad_tickets, activo, visible) values
    ('b2000000-0000-0000-0000-0000000000d9', 'pack-cap-100', 'pack_tickets', 'Pack cap', 99, 'EUR', 100, true, true);
  update public.configuracion_v1 set valor_integer = 150 where clave = 'tope_ot_comprados_dia';
  perform public.app_create_payment_order(u1, 'pack-cap-100', 'google_play', 'cap-1');
  perform pg_temp.expect_e(format($q$select * from public.app_create_payment_order(%L, 'pack-cap-100', 'google_play', 'cap-2')$q$, u1), 'PURCHASE_CAP_REACHED');
  assert (public.app_create_payment_order(u1, 'pack-cap-100', 'google_play', 'cap-1')).outcome = 'REPLAY', 'a replay of an accepted order is not blocked by the cap';
  update public.compras_usuario set estado = 'cancelada' where usuario_id = u1 and idempotency_key = 'cap-1';
  assert (public.app_create_payment_order(u1, 'pack-cap-100', 'google_play', 'cap-2')).outcome = 'CREATED', 'a cancelled order does not count';
  update public.configuracion_v1 set valor_integer = 5200 where clave = 'tope_ot_comprados_dia';
  raise notice 'OK V1 limiter';
end $$;
rollback;

-- 20261022101200: V1 wave 2 (daily challenge, streak, claim, earn cap, security fixes).
begin;
create function pg_temp.expect_e(p_sql text, p_msg text) returns void language plpgsql as $$
declare v_msg text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_msg = message_text; end;
  if v_msg is distinct from p_msg then raise exception 'ASSERT FAILED: % -> expected % got %', p_sql, p_msg, v_msg; end if;
end $$;
insert into auth.users (id, email, aud, role, confirmed_at) values
  ('a0000000-0000-0000-0000-0000000000e1','ch-nat@test.local','authenticated','authenticated', now()), ('a0000000-0000-0000-0000-0000000000e2','ch-a@test.local','authenticated','authenticated', now()),
  ('a0000000-0000-0000-0000-0000000000e3','ch-sub@test.local','authenticated','authenticated', now()), ('a0000000-0000-0000-0000-0000000000e4','ch-nospec@test.local','authenticated','authenticated', null);
insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-0000000000e1','CH','CH','ch',true);
insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('c1000000-0000-0000-0000-0000000000a1','CHA','Com A','com-ch-a'), ('c1000000-0000-0000-0000-0000000000b1','CHB','Com B','com-ch-b');
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id, comunidad_principal_id) values
  ('a0000000-0000-0000-0000-0000000000e1','n1','chn1',true,'activa','e1000000-0000-0000-0000-0000000000e1', null),
  ('a0000000-0000-0000-0000-0000000000e2','n2','chn2',true,'activa','e1000000-0000-0000-0000-0000000000e1', 'c1000000-0000-0000-0000-0000000000a1'),
  ('a0000000-0000-0000-0000-0000000000e3','n3','chn3',true,'activa','e1000000-0000-0000-0000-0000000000e1', 'c1000000-0000-0000-0000-0000000000b1')
  on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id, comunidad_principal_id = excluded.comunidad_principal_id;
set local session_replication_role = replica;
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en) values
  ('e5000000-0000-0000-0000-0000000000e8','CH-IMM','e1000000-0000-0000-0000-0000000000e1','c1000000-0000-0000-0000-0000000000a1','Inm','inmersivo','premium',true, now() - interval '1 day'),
  ('e5000000-0000-0000-0000-0000000000e9','CH-RAZ','e1000000-0000-0000-0000-0000000000e1','c1000000-0000-0000-0000-0000000000a1','Raz','razonado','premium',true, now() - interval '1 day'),
  ('e5000000-0000-0000-0000-0000000000ea','CH-RAF','e1000000-0000-0000-0000-0000000000e1','c1000000-0000-0000-0000-0000000000a1','Raf','rafaga','gratuito',true, now() - interval '1 day');
-- questions: 1 immersive (demo flagged), razonado x3 (the 3rd is REGIONAL tag of community A), rafaga x2
insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
select ('f8000000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'CH-' || n, case when n = 1 then 'inmersiva' when n <= 4 then 'razonada' else 'rafaga' end, 'Pregunta reto ' || n, true from generate_series(1, 6) n;
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
select ('0e800000-0000-0000-0000-000000000' || lpad(n::text, 2, '0') || o)::uuid, ('f8000000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, chr(64 + o), 'Opcion', o from generate_series(1, 6) n, generate_series(1, 2) o;
insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
select ('f8000000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, ('0e800000-0000-0000-0000-000000000' || lpad(n::text, 2, '0') || '1')::uuid, 'porque si' from generate_series(1, 6) n;
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, es_demo)
select case when n = 1 then 'e5000000-0000-0000-0000-0000000000e8'::uuid when n <= 4 then 'e5000000-0000-0000-0000-0000000000e9'::uuid else 'e5000000-0000-0000-0000-0000000000ea'::uuid end,
       ('f8000000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, n, n, n = 1 from generate_series(1, 6) n;
insert into public.etiquetas (id, nombre, slug) values ('7a800000-0000-0000-0000-000000000001', 'Legislacion autonomica A', 'ch-leg-a');
insert into public.etiquetas_pregunta (pregunta_id, etiqueta_id) values ('f8000000-0000-0000-0000-000000000004', '7a800000-0000-0000-0000-000000000001');
set local session_replication_role = origin;
insert into public.recursos_pregunta (id, pregunta_id, codigo_recurso, tipo_recurso, titulo, bucket_storage, ruta_storage, posicion, metadatos)
values ('7e500000-0000-0000-0000-000000000001', 'f8000000-0000-0000-0000-000000000001', 'CH-R1', 'imagen', 'Imagen', 'recursos-pregunta', 'preguntas/f8000000-0000-0000-0000-000000000001/7e500000-0000-0000-0000-000000000001.png', 1, '{"mime":"image/png","bytes":1234}'::jsonb);
insert into public.etiquetas_comunidad (etiqueta_id, comunidad_id) values ('7a800000-0000-0000-0000-000000000001', 'c1000000-0000-0000-0000-0000000000a1');
insert into public.planes_suscripcion (id, codigo, nombre, nivel) values ('b1000000-0000-0000-0000-0000000000e0','ch-gratuito','Gratuito',0), ('b1000000-0000-0000-0000-0000000000e3','ch-inmersivo','Inmersivo',3);
insert into public.productos_comerciales (id, codigo, tipo_producto, nombre, precio_centimos, moneda, activo, visible, plan_id) values ('b2000000-0000-0000-0000-0000000000e3','ch-sub','suscripcion','Sub',999,'EUR',true,true,'b1000000-0000-0000-0000-0000000000e3');
insert into public.beneficios_plan (plan_id, codigo_beneficio, valor_integer) values
  ('b1000000-0000-0000-0000-0000000000e0','ot_reto_diario',5), ('b1000000-0000-0000-0000-0000000000e0','ot_bonus_racha',0),
  ('b1000000-0000-0000-0000-0000000000e3','ot_reto_diario',20), ('b1000000-0000-0000-0000-0000000000e3','ot_bonus_racha',20);
insert into public.estado_premium_usuario (usuario_id, es_premium, estado_suscripcion, producto_codigo, inicio_en, fin_en) values ('a0000000-0000-0000-0000-0000000000e3', true, 'activa', 'ch-sub', now() - interval '1 day', now() + interval '20 days');

do $$
declare
  un uuid := 'a0000000-0000-0000-0000-0000000000e1'; ua uuid := 'a0000000-0000-0000-0000-0000000000e2'; us uuid := 'a0000000-0000-0000-0000-0000000000e3'; u0 uuid := 'a0000000-0000-0000-0000-0000000000e4';
  esp uuid := 'e1000000-0000-0000-0000-0000000000e1'; comA uuid := 'c1000000-0000-0000-0000-0000000000a1';
  qreg uuid := 'f8000000-0000-0000-0000-000000000004';
  j jsonb; j2 jsonb; reto_n uuid; reto_a uuid; n int; i int; pid uuid; rid uuid; ok uuid; ko uuid;
  today date := private.reto_today(clock_timestamp());
begin
  -- ---- security fixes on MAIN objects (F1..F4)
  assert not has_table_privilege('authenticated', 'public.registros_reto_diario', 'insert') and not has_table_privilege('authenticated', 'public.registros_reto_diario', 'update')
     and not has_table_privilege('authenticated', 'public.registros_reto_diario', 'select') and not has_table_privilege('anon', 'public.registros_reto_diario', 'select'), 'F1: a client cannot touch challenge records';
  assert not has_table_privilege('authenticated', 'public.ranking_liga', 'select') and not has_table_privilege('authenticated', 'public.preguntas_reto_diario', 'select'), 'F3: ranking and challenge questions are server-only';
  assert not exists (select 1 from pg_policies where schemaname = 'public' and tablename in ('registros_reto_diario', 'ranking_liga', 'preguntas_reto_diario')), 'F1/F3: the permissive policies are gone';
  assert not has_function_privilege('anon', 'public.recalcular_estadisticas_usuario_globales(uuid)', 'execute') and not has_function_privilege('authenticated', 'public.recalcular_estadisticas_globales_preguntas()', 'execute')
     and has_function_privilege('service_role', 'public.recalcular_estadisticas_globales_preguntas()', 'execute'), 'F2: recompute functions are service-only';
  assert not has_table_privilege('anon', 'public.rachas_usuario', 'select'), 'F4';

  -- ---- pools: the regional rule
  assert qreg in (select private.reto_pool(esp, comA, 'razonada')) and qreg not in (select private.reto_pool(esp, null, 'razonada')) and qreg not in (select private.reto_pool(esp, 'c1000000-0000-0000-0000-0000000000b1', 'razonada')), 'a regional question reaches only its community';
  assert (select count(*) from private.reto_pool(esp, null, 'inmersiva')) = 1, 'the immersive slot comes from the demo-flagged set';
  -- ---- the job
  assert public.app_generate_daily_challenges(today) = 2, 'job: the national row and the regional row of community A';
  assert public.app_generate_daily_challenges(today) = 0, 'job is idempotent';
  assert (select count(*) from public.preguntas_reto_diario pr join public.retos_diarios r on r.id = pr.reto_id where r.fecha = today) = 6 and (select count(distinct tipo_slot) from public.preguntas_reto_diario) = 3, 'three questions per row: immersive, razonado, rafaga';
  assert not exists (select 1 from public.preguntas_reto_diario pr join public.retos_diarios r on r.id = pr.reto_id where r.comunidad_id is null and pr.pregunta_id = qreg), 'the national row never carries the regional question';
  -- ---- users: most specific row, frozen for the day
  j := public.app_get_daily_challenge(un);
  assert j ->> 'estado' = 'DISPONIBLE' and jsonb_array_length(j -> 'preguntas') = 3 and (j ->> 'necesarios')::int = 2, 'national user: challenge available';
  reto_n := (j ->> 'retoId')::uuid;
  assert (select comunidad_id from public.retos_diarios where id = reto_n) is null, 'no community: the national row';
  assert j::text !~ 'opcionCorrectaId' and j::text !~ 'respuesta', 'before answering, no correction is served';
  j2 := public.app_get_daily_challenge(ua);
  reto_a := (j2 ->> 'retoId')::uuid;
  assert (select comunidad_id from public.retos_diarios where id = reto_a) = comA, 'community A user: the regional row (most specific)';
  update public.perfiles_usuario set comunidad_principal_id = 'c1000000-0000-0000-0000-0000000000b1' where usuario_id = ua;
  assert (public.app_get_daily_challenge(ua) ->> 'retoId')::uuid = reto_a, 'the segment is frozen for the day: changing community gives no second row';
  assert (public.app_get_daily_challenge(us) ->> 'retoId')::uuid is not null, 'a subscriber gets a row too';
  assert public.app_get_daily_challenge(u0) ->> 'estado' = 'NO_DISPONIBLE', 'no specialty: nothing available';
  -- ---- answers: tampering and finality
  select pr.pregunta_id into pid from public.preguntas_reto_diario pr where pr.reto_id = reto_n and pr.posicion = 2;
  ok := ('0e800000-0000-0000-0000-000000000' || lpad(right(pid::text, 2), 2, '0') || '1')::uuid;
  ko := ('0e800000-0000-0000-0000-000000000' || lpad(right(pid::text, 2), 2, '0') || '2')::uuid;
  perform pg_temp.expect_e(format($q$select public.app_answer_daily_challenge(%L, %L, %L, %L)$q$, un, reto_a, pid, ok), 'NOT_FOUND');   -- another segment's reto
  perform pg_temp.expect_e(format($q$select public.app_answer_daily_challenge(%L, %L, %L, %L)$q$, un, reto_n, gen_random_uuid(), ok), 'NOT_FOUND');   -- not a question of the reto
  perform pg_temp.expect_e(format($q$select public.app_answer_daily_challenge(%L, %L, %L, %L)$q$, un, reto_n, pid, gen_random_uuid()), 'OPTION_NOT_IN_QUESTION');
  j := public.app_answer_daily_challenge(un, reto_n, pid, ko);
  assert not (j ->> 'esCorrecta')::boolean and (j ->> 'opcionCorrectaId')::uuid = ok and j ->> 'estado' = 'EN_CURSO' and (j ->> 'respondidas')::int = 1, 'wrong answer: correction returned, in progress';
  assert (public.app_answer_daily_challenge(un, reto_n, pid, ko)) = j, 'the same option again returns the stored result';
  perform pg_temp.expect_e(format($q$select public.app_answer_daily_challenge(%L, %L, %L, %L)$q$, un, reto_n, pid, ok), 'ANSWER_LOCKED');
  assert (select aciertos from public.registros_reto_diario where usuario_id = un and reto_id = reto_n) = 0 and (select fallos from public.registros_reto_diario where usuario_id = un and reto_id = reto_n) = 1, 'counters follow the stored answers only';
  -- the user wins with 2 of 3: answer the other two correctly
  for rid in select pr.pregunta_id from public.preguntas_reto_diario pr where pr.reto_id = reto_n and pr.pregunta_id <> pid order by pr.posicion loop
    j := public.app_answer_daily_challenge(un, reto_n, rid, ('0e800000-0000-0000-0000-000000000' || lpad(right(rid::text, 2), 2, '0') || '1')::uuid);
  end loop;
  assert j ->> 'estado' = 'SUPERADO' and (j ->> 'aciertos')::int = 2 and (j -> 'recompensa' ->> 'base')::int = 5 and (j -> 'recompensa' ->> 'bonusRacha')::int = 0 and not (j -> 'recompensa' ->> 'reclamada')::boolean, 'passed with 2 of 3: free-plan reward frozen, not paid yet: ' || j::text;
  assert (j -> 'racha' ->> 'actual')::int = 1 and (j -> 'racha' ->> 'maxima')::int = 1, 'streak starts at 1';
  -- ---- claim: only the own, passed row; gates; idempotent; ledger-first
  perform pg_temp.expect_e(format($q$select public.app_claim_daily_challenge(%L, %L)$q$, ua, reto_a), 'RETO_NOT_PASSED');
  perform pg_temp.expect_e(format($q$select public.app_claim_daily_challenge(%L, %L)$q$, un, reto_a), 'NOT_FOUND');
  update auth.users set confirmed_at = null where id = un;
  perform pg_temp.expect_e(format($q$select public.app_claim_daily_challenge(%L, %L)$q$, un, reto_n), 'EMAIL_UNVERIFIED');
  update auth.users set confirmed_at = now() where id = un;
  j := public.app_claim_daily_challenge(un, reto_n);
  assert (j -> 'recompensa' ->> 'total')::int = 5 and (j -> 'recompensa' ->> 'reclamada')::boolean and not (j -> 'recompensa' ->> 'pendiente')::boolean and (j ->> 'saldo')::int = 5, 'claim pays the frozen amount: ' || j::text;
  assert public.app_claim_daily_challenge(un, reto_n) = j and (select count(*) from public.transacciones_cartera where usuario_id = un and tipo = 'reto_diario') = 1, 'a second claim pays nothing more';
  assert (select idempotency_key from public.transacciones_cartera where usuario_id = un and tipo = 'reto_diario') = 'reto:' || un::text || ':' || today::text || ':base', 'one reward per user per Madrid day (key by day)';
  assert (public.app_get_daily_challenge(un) -> 'barraGlobal' ->> 'jugadores')::int = 1 and (public.app_get_daily_challenge(un) -> 'barraGlobal' ->> 'superados')::int = 1 and public.app_get_daily_challenge(un) ->> 'estado' = 'SUPERADO', 'the bar counts the completion of the user type';
  -- ---- challenge media binding (W-4)
  assert jsonb_array_length(public.app_get_daily_challenge(un) -> 'preguntas' -> 0 -> '_recursos') + jsonb_array_length(public.app_get_daily_challenge(un) -> 'preguntas' -> 1 -> '_recursos') + jsonb_array_length(public.app_get_daily_challenge(un) -> 'preguntas' -> 2 -> '_recursos') = 1, 'the payload carries the resource metadata of the questions of the reto';
  select pr.pregunta_id into pid from public.preguntas_reto_diario pr where pr.reto_id = reto_n and pr.tipo_slot = 'inmersiva_demo';
  select r.id into rid from public.recursos_pregunta r where r.pregunta_id = pid;
  assert (public.app_challenge_resource(un, reto_n, pid, rid) ->> 'id')::uuid = rid, 'the owner of the reto gets the resource of its question';
  perform pg_temp.expect_e(format($q$select public.app_challenge_resource(%L, %L, %L, %L)$q$, ua, reto_n, pid, rid), 'NOT_FOUND');          -- another user's reto
  perform pg_temp.expect_e(format($q$select public.app_challenge_resource(%L, %L, %L, %L)$q$, un, reto_a, pid, rid), 'NOT_FOUND');          -- not the user's stored reto
  perform pg_temp.expect_e(format($q$select public.app_challenge_resource(%L, %L, %L, %L)$q$, un, reto_n, qreg, rid), 'NOT_FOUND');         -- question outside the reto
  perform pg_temp.expect_e(format($q$select public.app_challenge_resource(%L, %L, %L, %L)$q$, un, reto_n, pid, gen_random_uuid()), 'NOT_FOUND');  -- resource of no question of the reto
  -- ---- the guard and the ONE earn function
  perform pg_temp.expect_e(format($q$insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, idempotency_key) values (%L, 'reto_diario', 1000, 'x', 'forged')$q$, un), 'FREE_EARN_WRITER_ONLY');
  perform pg_temp.expect_e(format($q$insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, idempotency_key) values (%L, 'bonus_racha', 1, 'x', 'forged2')$q$, un), 'FREE_EARN_WRITER_ONLY');
  perform pg_temp.expect_e(format($q$insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, idempotency_key) values (%L, 'referido', 1, 'x', 'forged3')$q$, un), 'FREE_EARN_WRITER_ONLY');
  perform public.admin_grant_tickets(un, 3, 'recompensa de reporte', 'k-rv', 'reporte_validado');
  assert exists (select 1 from public.transacciones_cartera where usuario_id = un and tipo = 'reporte_validado' and cantidad = 3), 'the admin-driven Opositester reward still works (counted in the cap)';
  assert not exists (select 1 from pg_proc p where p.pronamespace in ('public'::regnamespace, 'private'::regnamespace) and p.prosrc ~* 'insert into public\.transacciones_cartera' and p.prosrc ~ '(reto_diario|bonus_racha|referido)' and p.proname <> 'free_ot_apply'), 'W-10: no other function writes the free-earn ledger types';
  assert (select estado from public.app_credit_free_ot(un, 'referido', 1000, 'invitacion', null, 'ref-1', '{}'::jsonb)) = 'REJECTED_CAP' and not exists (select 1 from public.ot_ganancias_diferidas where clave = 'ref-1'), 'an amount above the cap alone is rejected, never split and never stored';
  assert (select estado from public.app_credit_free_ot(un, 'referido', 92, 'invitacion', null, 'ref-1', '{}'::jsonb)) = 'CREDITED', 'cap 100 - (5 + 3) = 92 fits exactly';
  assert (select estado from public.app_credit_free_ot(un, 'referido', 92, 'invitacion', null, 'ref-1', '{}'::jsonb)) = 'REPLAY' and (select count(*) from public.transacciones_cartera where usuario_id = un and idempotency_key = 'ref-1') = 1, 'replay returns the stored result, pays nothing';
  assert (select estado from public.app_credit_free_ot(un, 'referido', 10, 'invitacion', null, 'ref-2', '{}'::jsonb)) = 'DEFERRED' and (select reintentar_despues from public.app_credit_free_ot(un, 'referido', 10, 'invitacion', null, 'ref-2', '{}'::jsonb)) > now(), 'over the window now: DEFERRED with a retry time';
  assert (select count(*) from public.ot_ganancias_diferidas where usuario_id = un and clave = 'ref-2' and estado = 'pendiente') = 1 and not exists (select 1 from public.transacciones_cartera where idempotency_key = 'ref-2'), 'deferral stored once (replay of a deferred key does not duplicate), nothing paid';
  assert (select estado from public.app_credit_free_ot(un, 'referido', 0, 'invitacion', null, 'ref-0', '{}'::jsonb)) = 'CREDITED' and not exists (select 1 from public.transacciones_cartera where idempotency_key = 'ref-0'), 'a zero amount writes nothing';
  perform pg_temp.expect_e($q$select * from public.app_credit_free_ot('a0000000-0000-0000-0000-0000000000e1', 'ajuste', 5, 'x', null, 'k', '{}'::jsonb)$q$, 'FREE_EARN_INVALID');
  -- retry: still over the window = still pending; once the window allows it the deferral is paid exactly once
  n := (select aplicadas from public.app_retry_deferred_free_ot(50));
  assert n = 0 and (select count(*) from public.ot_ganancias_diferidas where estado = 'pendiente') = 1, 'the retry leaves it pending while the cap is reached';
  update public.configuracion_v1 set valor_integer = 200 where clave = 'tope_ot_gratis_dia';
  n := (select aplicadas from public.app_retry_deferred_free_ot(50));
  assert n = 1 and (select estado from public.ot_ganancias_diferidas where clave = 'ref-2') = 'aplicada' and (select count(*) from public.transacciones_cartera where usuario_id = un and idempotency_key = 'ref-2') = 1, 'the retry pays the deferral once';
  n := (select aplicadas from public.app_retry_deferred_free_ot(50));
  assert (select estado from public.app_credit_free_ot(un, 'referido', 10, 'invitacion', null, 'ref-2', '{}'::jsonb)) = 'REPLAY' and n = 0, 'and never twice';
  update public.configuracion_v1 set valor_integer = 100 where clave = 'tope_ot_gratis_dia';
  -- expiry is visible, never paid silently
  assert (select estado from public.app_credit_free_ot(un, 'referido', 10, 'invitacion', null, 'ref-3', '{}'::jsonb)) = 'DEFERRED', 'ref-3 deferred (day total is over the cap)';
  update public.ot_ganancias_diferidas set hasta = now() - interval '1 minute' where clave = 'ref-3';
  n := (select caducadas from public.app_retry_deferred_free_ot(50));
  assert n = 1 and (select estado from public.ot_ganancias_diferidas where clave = 'ref-3') = 'caducada', 'an old deferral expires with a visible state';
  assert (select estado from public.app_credit_free_ot(un, 'referido', 10, 'invitacion', null, 'ref-3', '{}'::jsonb)) = 'REJECTED_CAP' and not exists (select 1 from public.transacciones_cartera where idempotency_key = 'ref-3'), 'an expired deferral is not re-opened by the same key';
  -- per source cap and weekly cap
  insert into public.configuracion_v1 (clave, valor_integer) values ('tope_ot_referido_dia', 7), ('tope_ot_gratis_semana', 150) on conflict (clave) do update set valor_integer = excluded.valor_integer;
  assert (select estado from public.app_credit_free_ot(ua, 'referido', 8, 'invitacion', null, 'ref-s1', '{}'::jsonb)) = 'REJECTED_CAP', 'a per-source cap rejects an amount above it';
  update public.configuracion_v1 set valor_integer = 0 where clave in ('tope_ot_referido_dia', 'tope_ot_gratis_semana');
  insert into public.configuracion_v1 (clave, valor_integer) values ('ot_gratis_referido_activa', 0) on conflict (clave) do update set valor_integer = 0;
  assert (select estado from public.app_credit_free_ot(ua, 'referido', 1, 'invitacion', null, 'ref-s2', '{}'::jsonb)) = 'REJECTED_CAP', 'a disabled source rejects';
  update public.configuracion_v1 set valor_integer = 1 where clave = 'ot_gratis_referido_activa';
  -- ---- window: outside it answers are refused
  j := public.app_get_daily_challenge(us);
  rid := (j ->> 'retoId')::uuid;
  update public.retos_diarios set termina_en = now() - interval '1 minute' where id = rid;
  select pr.pregunta_id into pid from public.preguntas_reto_diario pr where pr.reto_id = rid and pr.posicion = 1;
  perform pg_temp.expect_e(format($q$select public.app_answer_daily_challenge(%L, %L, %L, %L)$q$, us, rid, pid, ('0e800000-0000-0000-0000-000000000' || lpad(right(pid::text, 2), 2, '0') || '1')::uuid), 'RETO_OUT_OF_WINDOW');
  assert public.app_get_daily_challenge(us) ->> 'estado' = 'FALLADO', 'an unfinished challenge after its window reads as failed';
  update public.retos_diarios set termina_en = (select empieza_en + interval '24 hours' from public.retos_diarios where id = rid) where id = rid;
  -- ---- streak bonus (from the 3rd consecutive day, plan amount)
  insert into public.rachas_usuario (usuario_id, racha_actual_dias, mejor_racha_dias, ultima_actividad_fecha) values (us, 2, 5, today - 1);
  for pid in select pr.pregunta_id from public.preguntas_reto_diario pr where pr.reto_id = rid order by pr.posicion loop
    j := public.app_answer_daily_challenge(us, rid, pid, ('0e800000-0000-0000-0000-000000000' || lpad(right(pid::text, 2), 2, '0') || '1')::uuid);
  end loop;
  assert j ->> 'estado' = 'SUPERADO' and (j -> 'racha' ->> 'actual')::int = 3 and (j -> 'racha' ->> 'maxima')::int = 5 and (j -> 'recompensa' ->> 'base')::int = 20 and (j -> 'recompensa' ->> 'bonusRacha')::int = 20, 'subscriber on day 3 of the streak: plan reward + streak bonus: ' || j::text;
  -- all or nothing: a rejected part rolls the whole claim back
  insert into public.configuracion_v1 (clave, valor_integer) values ('ot_gratis_bonus_racha_activa', 0) on conflict (clave) do update set valor_integer = 0;
  perform pg_temp.expect_e(format($q$select public.app_claim_daily_challenge(%L, %L)$q$, us, rid), 'OT_EARN_CAP_REACHED');
  assert not exists (select 1 from public.transacciones_cartera where usuario_id = us and tipo = 'reto_diario') and not (select recompensa_entregada from public.registros_reto_diario where usuario_id = us and reto_id = rid), 'a rejected part leaves the claim open and pays nothing';
  update public.configuracion_v1 set valor_integer = 1 where clave = 'ot_gratis_bonus_racha_activa';
  -- the cap defers the second part: 200 with pendiente, then the retry completes it
  update public.configuracion_v1 set valor_integer = 30 where clave = 'tope_ot_gratis_dia';
  j := public.app_claim_daily_challenge(us, rid);
  assert (j -> 'recompensa' ->> 'base')::int = 20 and (j -> 'recompensa' ->> 'bonusRacha')::int = 20 and not (j -> 'recompensa' ->> 'reclamada')::boolean and (j -> 'recompensa' ->> 'pendiente')::boolean and (j ->> 'saldo')::int = 20, 'base paid, the bonus is deferred, the claim answers pending: ' || j::text;
  assert public.app_claim_daily_challenge(us, rid) = j and (select count(*) from public.ot_ganancias_diferidas where usuario_id = us) = 1 and (public.app_get_daily_challenge(us) -> 'recompensa' ->> 'pendiente')::boolean, 'a second claim neither pays nor defers twice';
  update public.configuracion_v1 set valor_integer = 100 where clave = 'tope_ot_gratis_dia';
  n := (select aplicadas from public.app_retry_deferred_free_ot(50));
  assert n = 1 and (select recompensa_entregada from public.registros_reto_diario where usuario_id = us and reto_id = rid), 'the retry completes the reward and closes the claim';
  j := public.app_claim_daily_challenge(us, rid);
  assert (j -> 'recompensa' ->> 'reclamada')::boolean and not (j -> 'recompensa' ->> 'pendiente')::boolean and (j ->> 'saldo')::int = 40, 'after the retry the claim reads as paid in full: ' || j::text;
  -- ---- claim window and farming gates (W-9, W-6) with ua
  for pid in select pr.pregunta_id from public.preguntas_reto_diario pr where pr.reto_id = reto_a order by pr.posicion loop
    perform public.app_answer_daily_challenge(ua, reto_a, pid, ('0e800000-0000-0000-0000-000000000' || lpad(right(pid::text, 2), 2, '0') || '1')::uuid);
  end loop;
  update public.registros_reto_diario set completado_en = now() - interval '5 days' where usuario_id = ua and reto_id = reto_a;
  perform pg_temp.expect_e(format($q$select public.app_claim_daily_challenge(%L, %L)$q$, ua, reto_a), 'RETO_CLAIM_EXPIRED');
  assert not (select recompensa_entregada from public.registros_reto_diario where usuario_id = ua and reto_id = reto_a), 'an expired claim stays unpaid';
  update public.registros_reto_diario set completado_en = now() where usuario_id = ua and reto_id = reto_a;
  update public.configuracion_v1 set valor_integer = 1 where clave = 'reto_min_respuestas_cobrar';
  perform pg_temp.expect_e(format($q$select public.app_claim_daily_challenge(%L, %L)$q$, ua, reto_a), 'RETO_CLAIM_NOT_ELIGIBLE');
  update public.configuracion_v1 set valor_integer = 0 where clave = 'reto_min_respuestas_cobrar';
  update public.configuracion_v1 set valor_integer = 24 where clave = 'reto_edad_minima_horas';
  perform pg_temp.expect_e(format($q$select public.app_claim_daily_challenge(%L, %L)$q$, ua, reto_a), 'RETO_CLAIM_NOT_ELIGIBLE');
  update public.configuracion_v1 set valor_integer = 0 where clave = 'reto_edad_minima_horas';
  assert (public.app_claim_daily_challenge(ua, reto_a) -> 'recompensa' ->> 'total')::int = 5, 'and pays once the gates allow it';
  -- ---- W-11: no client privilege on any engagement table that exists
  assert not exists (
    select 1 from unnest(array['retos_diarios', 'preguntas_reto_diario', 'registros_reto_diario', 'respuestas_reto_diario', 'reto_contadores', 'etiquetas_comunidad', 'ot_ganancias_diferidas', 'rachas_usuario', 'ranking_liga',
                               'estadisticas_usuario', 'estadisticas_usuario_etiqueta', 'estadisticas_usuario_pregunta', 'estado_competitivo_usuario', 'estado_opositester_usuario', 'ligas_nivel', 'lotes_actualizacion_ranking',
                               'estadisticas_globales_etiqueta', 'estadisticas_globales_pregunta']) t(n)
     cross join unnest(array['anon', 'authenticated']) r(rol)
     cross join unnest(array['insert', 'update', 'delete', 'truncate']) pv(p)
    where to_regclass('public.' || t.n) is not null and has_table_privilege(r.rol, 'public.' || t.n, pv.p)), 'W-11: no client holds DML on any engagement table';
  assert not exists (
    select 1 from unnest(array['preguntas_reto_diario', 'registros_reto_diario', 'respuestas_reto_diario', 'reto_contadores', 'etiquetas_comunidad', 'ot_ganancias_diferidas', 'ranking_liga', 'retos_diarios',
                               'estadisticas_usuario', 'estadisticas_usuario_etiqueta', 'estadisticas_usuario_pregunta', 'estado_competitivo_usuario', 'estado_opositester_usuario',
                               'estadisticas_globales_etiqueta', 'estadisticas_globales_pregunta', 'rachas_usuario']) t(n)
    where to_regclass('public.' || t.n) is not null and has_table_privilege('anon', 'public.' || t.n, 'select')), 'W-11: anon reads none of them';
  -- ---- the streak read
  assert (public.app_get_streak(us) ->> 'actual')::int = 3 and (public.app_get_streak(u0) ->> 'actual')::int = 0, 'streak reads';
  update public.rachas_usuario set ultima_actividad_fecha = today - 2 where usuario_id = us;
  assert (public.app_get_streak(us) ->> 'actual')::int = 0, 'a streak missed for a whole day reads 0';
  raise notice 'OK V1 wave 2';
end $$;
rollback;

-- 20261022101150: plan catalog read (D7-33).
begin;
do $$
declare j jsonb;
begin
  insert into public.planes_suscripcion (id, codigo, nombre, nivel, posicion) values
    ('b1000000-0000-0000-0000-0000000000f0', 'pc-gratuito', 'Gratuito', 0, 1), ('b1000000-0000-0000-0000-0000000000f2', 'pc-razonados', 'Razonados', 2, 3),
    ('b1000000-0000-0000-0000-0000000000f3', 'pc-inmersivo', 'Inmersivo', 3, 4), ('b1000000-0000-0000-0000-0000000000f9', 'pc-oculto', 'Oculto', 1, 2);
  update public.planes_suscripcion set visible = false where codigo = 'pc-oculto';
  insert into public.productos_comerciales (id, codigo, tipo_producto, nombre, activo, visible, plan_id, destacado) values
    ('b2000000-0000-0000-0000-0000000000f2', 'pc-raz-m', 'suscripcion', 'Razonados mensual', true, true, 'b1000000-0000-0000-0000-0000000000f2', false),
    ('b2000000-0000-0000-0000-0000000000f3', 'pc-inm-m', 'suscripcion', 'Inmersivo mensual', true, true, 'b1000000-0000-0000-0000-0000000000f3', true),
    ('b2000000-0000-0000-0000-0000000000f4', 'pc-inm-a', 'suscripcion', 'Inmersivo anual', true, true, 'b1000000-0000-0000-0000-0000000000f3', true);
  insert into public.tarifas_producto_comercial (producto_id, periodo_facturacion, fase_precio, precio_centimos) values
    ('b2000000-0000-0000-0000-0000000000f2', 'mensual', 'standard', 999), ('b2000000-0000-0000-0000-0000000000f2', 'mensual', 'first_year', 499),
    ('b2000000-0000-0000-0000-0000000000f3', 'mensual', 'standard', 1999), ('b2000000-0000-0000-0000-0000000000f4', 'anual', 'standard', 19990);
  insert into public.beneficios_plan (plan_id, codigo_beneficio, valor_integer) values
    ('b1000000-0000-0000-0000-0000000000f0', 'limite_preguntas_24h', 30), ('b1000000-0000-0000-0000-0000000000f3', 'inmersivos_incluidos', 2);
  j := public.app_plan_catalog();
  assert (select count(*) from jsonb_array_elements(j -> 'items') e where e ->> 'planCodigo' like 'pc-%') = 3, 'only active and visible plans: ' || j::text;
  assert (select e ->> 'planCodigo' from jsonb_array_elements(j -> 'items') e where e ->> 'planCodigo' like 'pc-%' order by (e ->> 'orden')::int limit 1) = 'pc-gratuito', 'ordered by posicion';
  assert (select e -> 'cobertura' ->> 'inmersivos' from jsonb_array_elements(j -> 'items') e where e ->> 'planCodigo' = 'pc-inmersivo') = 'cupo'
     and (select (e -> 'cobertura' ->> 'cupoInmersivos')::int from jsonb_array_elements(j -> 'items') e where e ->> 'planCodigo' = 'pc-inmersivo') = 2
     and (select e -> 'cobertura' ->> 'inmersivos' from jsonb_array_elements(j -> 'items') e where e ->> 'planCodigo' = 'pc-razonados') = 'ninguno', 'immersive coverage comes from the plan benefit';
  assert (select (e -> 'cobertura' ->> 'razonados')::boolean and (e -> 'cobertura' ->> 'rafagas')::boolean from jsonb_array_elements(j -> 'items') e where e ->> 'planCodigo' = 'pc-razonados')
     and not (select (e -> 'cobertura' ->> 'rafagas')::boolean from jsonb_array_elements(j -> 'items') e where e ->> 'planCodigo' = 'pc-gratuito'), 'rafagas / razonados follow the level';
  assert (select (e ->> 'limitePreguntas24h')::int from jsonb_array_elements(j -> 'items') e where e ->> 'planCodigo' = 'pc-gratuito') = 30
     and (select e ->> 'limitePreguntas24h' from jsonb_array_elements(j -> 'items') e where e ->> 'planCodigo' = 'pc-inmersivo') is null, 'the 24 h limit is data (absent = unlimited)';
  assert (select jsonb_array_length(e -> 'tarifas') from jsonb_array_elements(j -> 'items') e where e ->> 'planCodigo' = 'pc-razonados') = 1
     and (select (e -> 'tarifas' -> 0 ->> 'precioCentimos')::int from jsonb_array_elements(j -> 'items') e where e ->> 'planCodigo' = 'pc-razonados') = 999, 'only standard tariffs are served (a plan with one period has one row)';
  assert (select jsonb_array_length(e -> 'tarifas') from jsonb_array_elements(j -> 'items') e where e ->> 'planCodigo' = 'pc-inmersivo') = 2
     and (select e -> 'tarifas' -> 0 ->> 'periodo' from jsonb_array_elements(j -> 'items') e where e ->> 'planCodigo' = 'pc-inmersivo') = 'mensual'
     and (select e -> 'tarifas' -> 1 ->> 'productoCodigo' from jsonb_array_elements(j -> 'items') e where e ->> 'planCodigo' = 'pc-inmersivo') = 'pc-inm-a', 'monthly and annual are separate rows with their store product code';
  assert not has_function_privilege('authenticated', 'public.app_plan_catalog()', 'execute') and has_function_privilege('service_role', 'public.app_plan_catalog()', 'execute'), 'service only';
  raise notice 'OK V1 plan catalog';
end $$;
rollback;

-- 20261022101300: official exams (annulled + reserve questions, OFFICIAL_RULES C-O1..C-O6).
begin;
create function pg_temp.expect_e(p_sql text, p_msg text) returns void language plpgsql as $$
declare v_msg text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_msg = message_text; end;
  if v_msg is distinct from p_msg then raise exception 'ASSERT FAILED: % -> expected % got %', p_sql, p_msg, v_msg; end if;
end $$;
create function pg_temp.q(n int, p int) returns uuid language sql as $$ select ('f9000000-0000-0000-' || lpad(n::text, 4, '0') || '-' || lpad(p::text, 12, '0'))::uuid $$;
create function pg_temp.ex(n int) returns uuid language sql as $$ select ('e9000000-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid $$;
create function pg_temp.good(q uuid) returns uuid language sql as $$ select opcion_correcta_id from public.soluciones_pregunta where pregunta_id = q $$;
create function pg_temp.bad(q uuid) returns uuid language sql as $$ select id from public.opciones_pregunta where pregunta_id = q and id <> pg_temp.good(q) limit 1 $$;
create function pg_temp.ans(u uuid, a uuid, q uuid, ok boolean) returns jsonb language sql as $$
  select public.app_record_attempt_answer(u, a, q, case when ok then pg_temp.good(q) else pg_temp.bad(q) end, 0, 0, null) $$;

insert into auth.users (id, email, aud, role) values
  ('a0000000-0000-0000-0000-0000000000f1', 'or-a@test.local', 'authenticated', 'authenticated'), ('a0000000-0000-0000-0000-0000000000f2', 'or-b@test.local', 'authenticated', 'authenticated');
insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-0000000000f1', 'OR', 'OR', 'or', true);
insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('c1000000-0000-0000-0000-0000000000f1', 'ORC', 'Com OR', 'com-or');
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id) values
  ('a0000000-0000-0000-0000-0000000000f1', 'oa', 'ora', true, 'activa', 'e1000000-0000-0000-0000-0000000000f1'),
  ('a0000000-0000-0000-0000-0000000000f2', 'ob', 'orb', true, 'activa', 'e1000000-0000-0000-0000-0000000000f1')
on conflict (usuario_id) do nothing;
set local session_replication_role = replica;
create temp table ox(n int, regla text, num int, den int, mains int, annulled int[], reserves int);
insert into ox values
  (0, 'NO_PUNTUA', 0, 1, 6, '{}', 0), (1, 'REEMPLAZO_POR_RESERVA', 0, 1, 6, '{}', 2), (2, 'REEMPLAZO_POR_RESERVA', 0, 1, 6, '{2}', 3),
  (3, 'REEMPLAZO_POR_RESERVA', 0, 1, 6, '{2,4,5}', 2), (4, 'NO_PUNTUA', 0, 1, 6, '{3}', 2), (5, 'NO_PUNTUA', 1, 3, 6, '{3,4}', 1),
  (6, 'REEMPLAZO_POR_RESERVA', 1, 3, 6, '{1}', 2), (7, 'NO_PUNTUA', 0, 1, 0, '{}', 2), (8, 'REEMPLAZO_POR_RESERVA', 0, 1, 6, '{2}', 3),
  (9, null, 0, 1, 6, '{2}', 3), (10, null, 0, 1, 6, '{}', 0), (11, null, 0, 1, 6, '{2,7}', 3);
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, regla_anuladas, penalizacion_num, penalizacion_den)
select pg_temp.ex(n), 'OR-' || n, 'e1000000-0000-0000-0000-0000000000f1', 'c1000000-0000-0000-0000-0000000000f1', 'Oficial ' || n, 'rafaga', 'gratuito', true, now() - interval '1 day', regla, num, den from ox;
create temp table oq as select o.n, p, (p > o.mains) as es_reserva, p = any (o.annulled) as anul, p - o.mains as r from ox o, generate_series(1, o.mains + o.reserves) p;
insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) select pg_temp.q(n, p), 'OR-' || n || '-' || p, 'rafaga', 'Pregunta ' || n || '-' || p, true from oq;
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
select ('0a000000-0000-0000-' || lpad(n::text, 4, '0') || '-' || lpad((p * 10 + o)::text, 12, '0'))::uuid, pg_temp.q(n, p), chr(64 + o), 'Opcion', o from oq, generate_series(1, 2) o;
insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
select pg_temp.q(n, p), ('0a000000-0000-0000-' || lpad(n::text, 4, '0') || '-' || lpad((p * 10 + 1)::text, 12, '0'))::uuid, 'x' from oq;
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, anulada, motivo_anulacion, es_reserva, orden_reserva)
select pg_temp.ex(n), pg_temp.q(n, p), p, p, anul, case when anul then 'tribunal' end, es_reserva, case when es_reserva then r end from oq;
set local session_replication_role = origin;

do $$
declare
  ua constant uuid := 'a0000000-0000-0000-0000-0000000000f1';
  ub constant uuid := 'a0000000-0000-0000-0000-0000000000f2';
  r jsonb; a uuid; b uuid; i int; j int;
begin
  -- ---- scoring function: exact half-up integer arithmetic (the Kotlin twin is pinned by the same matrix)
  assert private.official_nota(4, 1, 6, 0, 1) = 6.67 and private.official_nota(4, 1, 6, 1, 3) = 6.11 and private.official_nota(1, 5, 6, 1, 3) = 0.00 and private.official_nota(2, 4, 6, 1, 3) = 1.11, 'nota examples incl. 1/3';
  assert private.official_nota(5, 0, 10, 0, 1) = 5.00 and private.official_nota(1, 0, 3, 0, 1) = 3.33 and private.official_nota(1, 0, 8, 0, 1) = 1.25 and private.official_nota(1, 0, 16, 0, 1) = 0.63, 'half-up at the tie (0.625 -> 0.63)';
  assert private.official_nota(0, 0, 0, 0, 1) is null and private.official_nota(3, 1, null, 0, 1) is null, 'evaluables 0 = NULL, no division by zero';
  assert (select md5(string_agg(coalesce(private.official_nota(c, f, e, nu, de)::text, 'null'), ',' order by c, f, e, nu, de))
            from generate_series(0, 12) c, generate_series(0, 12) f, generate_series(1, 14) e, (values (0, 1), (1, 3), (1, 4), (1, 2), (2, 5)) pen(nu, de)
           where c + f <= e) = '450f6571dec69b16f142e60c29033415', 'the nota matrix equals the Kotlin matrix (OfficialScoringParityTest)';
  -- ---- M10 default unchanged: no reserves, NO_PUNTUA, no penalty
  r := public.app_start_exam_attempt(ua, pg_temp.ex(0));
  a := (r ->> 'intento_id')::uuid;
  assert r ->> 'outcome' = 'CREATED' and (select count(*) = 6 and count(*) filter (where rol = 'PRINCIPAL') = 6 and not bool_or(anulada_al_inicio) from public.preguntas_intento where intento_id = a), 'default exam: 6 PRINCIPAL';
  assert (select regla_anuladas = 'NO_PUNTUA' and penalizacion_num = 0 and penalizacion_den = 1 and reservas_usadas = 0 and total_anuladas = 0 and total_preguntas = 6 from public.intentos_examen where id = a), 'snapshot at start';
  for i in 1..4 loop perform pg_temp.ans(ua, a, pg_temp.q(0, i), true); end loop;
  perform pg_temp.ans(ua, a, pg_temp.q(0, 5), false);
  perform public.app_finish_exam_attempt(ua, a);
  assert (select nota = 6.67 and total_evaluables = 6 and total_correctas = 4 and total_falladas = 1 and total_sin_responder = 1 and total_respondidas = 5 from public.intentos_examen where id = a), 'default exam nota = DEC-013 (4/6 x 10)';

  -- ---- reserves of an exam with NO annulment are kept but unused (C-O1)
  r := public.app_start_exam_attempt(ua, pg_temp.ex(1));
  a := (r ->> 'intento_id')::uuid;
  assert (select count(*) = 8 and count(*) filter (where rol = 'RESERVA_NO_USADA') = 2 and (select total_preguntas from public.intentos_examen where id = a) = 6 from public.preguntas_intento where intento_id = a), 'REEMPLAZO without annulled: 2 reserves frozen as unused, 6 evaluable';
  assert public.app_record_attempt_answer(ua, a, pg_temp.q(1, 7), pg_temp.good(pg_temp.q(1, 7)), 0, 0, null) ->> 'outcome' = 'QUESTION_NOT_IN_ATTEMPT', 'an unused reserve cannot be answered while open';
  assert public.app_reveal_attempt_questions(ua, a, array[pg_temp.q(1, 1), pg_temp.q(1, 8)]) ->> 'outcome' = 'QUESTION_NOT_IN_ATTEMPT', 'an unused reserve is never revealed';
  assert not exists (select 1 from public.respuestas_intento where intento_id = a and pregunta_id in (pg_temp.q(1, 7), pg_temp.q(1, 8))), 'nothing stored for it';
  for i in 1..6 loop perform pg_temp.ans(ua, a, pg_temp.q(1, i), true); end loop;
  perform public.app_finish_exam_attempt(ua, a);
  assert (select count(*) filter (where estado_final = 'RESERVA_NO_UTILIZADA') = 2 from public.preguntas_intento where intento_id = a)
     and (select nota = 10.00 and total_evaluables = 6 from public.intentos_examen where id = a), 'finish: unused reserves labelled, nota 10';
  assert not exists (select 1 from public.resumen_intento_etiqueta where intento_id = a and total_preguntas > 6), 'unused reserves are not in the tag summary';

  -- ---- REEMPLAZO: 1 annulled, 3 reserves -> the first reserve is activated and replaces position 2
  r := public.app_start_exam_attempt(ua, pg_temp.ex(2));
  a := (r ->> 'intento_id')::uuid;
  assert (select rol from public.preguntas_intento where intento_id = a and pregunta_id = pg_temp.q(2, 7)) = 'RESERVA_ACTIVA'
     and (select sustituye_a_posicion from public.preguntas_intento where intento_id = a and pregunta_id = pg_temp.q(2, 7)) = 2
     and (select count(*) from public.preguntas_intento where intento_id = a and rol = 'RESERVA_NO_USADA') = 2
     and (select total_preguntas = 6 and reservas_usadas = 1 and total_anuladas = 1 and anuladas_sin_reserva = 0 from public.intentos_examen where id = a), 'reserve 1 replaces the annulled position 2; evaluables 5 + 1';
  assert pg_temp.ans(ua, a, pg_temp.q(2, 2), true) ->> 'estado' = 'ANNULLED', 'the annulled main question stores nothing';
  assert pg_temp.ans(ua, a, pg_temp.q(2, 7), true) ->> 'estado' = 'ANSWERED' and public.app_record_attempt_answer(ua, a, pg_temp.q(2, 8), pg_temp.good(pg_temp.q(2, 8)), 0, 0, null) ->> 'outcome' = 'QUESTION_NOT_IN_ATTEMPT', 'the activated reserve is answerable, the next one is not';
  -- the annulment is FROZEN at start: annulling another question later does not change this attempt
  update public.preguntas_examen set anulada = true, motivo_anulacion = 'tribunal' where examen_id = pg_temp.ex(2) and pregunta_id = pg_temp.q(2, 5);
  assert pg_temp.ans(ua, a, pg_temp.q(2, 5), true) ->> 'estado' = 'ANSWERED', 'a later annulment does not alter an attempt in progress';
  update public.preguntas_examen set anulada = false, motivo_anulacion = null where examen_id = pg_temp.ex(2) and pregunta_id = pg_temp.q(2, 5);
  perform pg_temp.ans(ua, a, pg_temp.q(2, 1), true); perform pg_temp.ans(ua, a, pg_temp.q(2, 3), true); perform pg_temp.ans(ua, a, pg_temp.q(2, 4), false);
  perform public.app_finish_exam_attempt(ua, a);
  -- answered: 7(res) ok, 5 ok, 1 ok, 3 ok, 4 wrong, 6 unanswered -> c=4 f=1 s=1 e=6
  assert (select total_correctas = 4 and total_falladas = 1 and total_sin_responder = 1 and total_evaluables = 6 and nota = 6.67 from public.intentos_examen where id = a), 'REEMPLAZO score over 5 main + 1 reserve';
  assert (select estado_final from public.preguntas_intento where intento_id = a and pregunta_id = pg_temp.q(2, 2)) = 'ANNULLED', 'annulled labelled';

  -- ---- REEMPLAZO with FEWER reserves than annulled (data that predates the validation): deterministic degrade
  r := public.app_start_exam_attempt(ua, pg_temp.ex(3));
  a := (r ->> 'intento_id')::uuid;
  assert (select total_preguntas = 5 and reservas_usadas = 2 and total_anuladas = 3 and anuladas_sin_reserva = 1 from public.intentos_examen where id = a)
     and (select array_agg(sustituye_a_posicion order by posicion) filter (where rol = 'RESERVA_ACTIVA') from public.preguntas_intento where intento_id = a) = array[2, 4], 'degrade: 2 reserves replace the first two annulled; one annulled has no reserve';
  perform public.app_abandon_exam_attempt(ua, a, 1, 'salida_usuario');

  -- ---- NO_PUNTUA with reserves: the reserves stay unused; annulled not evaluable
  r := public.app_start_exam_attempt(ua, pg_temp.ex(4));
  a := (r ->> 'intento_id')::uuid;
  assert (select total_preguntas = 5 and reservas_usadas = 0 from public.intentos_examen where id = a) and (select count(*) = 2 from public.preguntas_intento where intento_id = a and rol = 'RESERVA_NO_USADA'), 'NO_PUNTUA: reserves unused';
  perform public.app_abandon_exam_attempt(ua, a, 1, 'salida_usuario');

  -- ---- NO_PUNTUA + penalty 1/3 (PUNTUA_ACIERTO was dropped by the client answer of 2026-10-16 (c))
  r := public.app_start_exam_attempt(ua, pg_temp.ex(5));
  a := (r ->> 'intento_id')::uuid;
  perform pg_temp.ans(ua, a, pg_temp.q(5, 1), true); perform pg_temp.ans(ua, a, pg_temp.q(5, 2), true); perform pg_temp.ans(ua, a, pg_temp.q(5, 5), false);
  perform public.app_finish_exam_attempt(ua, a);
  -- 4 evaluable main questions: 2 correct, 1 wrong, 1 unanswered -> max(0, 2*3 - 1*1) = 5 -> 50/12 = 4.17
  assert (select total_correctas = 2 and total_falladas = 1 and total_sin_responder = 1 and total_respondidas = 3 and total_evaluables = 4 and nota = 4.17 and total_anuladas = 2 and reservas_usadas = 0 from public.intentos_examen where id = a), 'NO_PUNTUA with 1/3: nota 4.17';
  assert (select count(*) from public.respuestas_intento where intento_id = a) = 3 and not exists (select 1 from public.respuestas_intento where intento_id = a and pregunta_id in (pg_temp.q(5, 3), pg_temp.q(5, 4))), 'annulled questions store nothing';
  -- zero real answers: the grade is shown (0.00), nothing was answered
  r := public.app_start_exam_attempt(ub, pg_temp.ex(5));
  b := (r ->> 'intento_id')::uuid;
  perform public.app_finish_exam_attempt(ub, b);
  assert (select nota = 0.00 and total_respondidas = 0 and total_evaluables = 4 from public.intentos_examen where id = b) and not exists (select 1 from public.respuestas_intento where intento_id = b), 'no real answer: grade 0.00, nothing stored';
  -- ---- the rule is DERIVED when the exam does not set one (client answer (c)): reserves -> REEMPLAZO; none -> the annulled just do not count
  r := public.app_start_exam_attempt(ub, pg_temp.ex(9));
  assert (select regla_anuladas = 'REEMPLAZO_POR_RESERVA' and reservas_usadas = 1 and total_preguntas = 6 from public.intentos_examen where id = (r ->> 'intento_id')::uuid), 'derived REEMPLAZO for an exam with reserves';
  perform public.app_abandon_exam_attempt(ub, (r ->> 'intento_id')::uuid, 1, 'salida_usuario');
  r := public.app_start_exam_attempt(ub, pg_temp.ex(10));
  assert (select regla_anuladas = 'NO_PUNTUA' and reservas_usadas = 0 from public.intentos_examen where id = (r ->> 'intento_id')::uuid), 'derived NO_PUNTUA for an exam without reserves';
  perform public.app_abandon_exam_attempt(ub, (r ->> 'intento_id')::uuid, 1, 'salida_usuario');
  -- an annulled reserve is SKIPPED: the next one is taken (documented default, to be confirmed)
  r := public.app_start_exam_attempt(ub, pg_temp.ex(11));
  a := (r ->> 'intento_id')::uuid;
  assert (select rol from public.preguntas_intento where intento_id = a and pregunta_id = pg_temp.q(11, 7)) = 'RESERVA_NO_USADA'
     and (select rol from public.preguntas_intento where intento_id = a and pregunta_id = pg_temp.q(11, 8)) = 'RESERVA_ACTIVA'
     and (select sustituye_a_posicion from public.preguntas_intento where intento_id = a and pregunta_id = pg_temp.q(11, 8)) = 2, 'the annulled reserve is skipped, reserve 2 replaces position 2';
  assert private.official_rules_warnings(pg_temp.ex(11)) = array['ANNULLED_RESERVE_SKIPPED'] and private.official_rules_problem(pg_temp.ex(11)) is null, 'an annulled reserve is a warning, not an error';
  perform public.app_abandon_exam_attempt(ub, a, 1, 'salida_usuario');

  -- ---- REEMPLAZO + 1/3, penalty floors at 0
  r := public.app_start_exam_attempt(ub, pg_temp.ex(6));
  b := (r ->> 'intento_id')::uuid;
  assert (select total_preguntas = 6 and reservas_usadas = 1 from public.intentos_examen where id = b), 'exam 6: 5 main + 1 active reserve';
  perform pg_temp.ans(ub, b, pg_temp.q(6, 2), true);
  for i in 3..6 loop perform pg_temp.ans(ub, b, pg_temp.q(6, i), false); end loop;
  perform pg_temp.ans(ub, b, pg_temp.q(6, 7), false);
  perform public.app_finish_exam_attempt(ub, b);
  -- c 1 f 5 s 0 e 6 -> max(0, 1*3 - 5) = 0
  assert (select nota = 0.00 and total_correctas = 1 and total_falladas = 5 from public.intentos_examen where id = b), 'penalty never below 0';

  -- ---- publication / edit validation (C-O3, C-O4); the deferred check is forced to run now
  set constraints trg_preguntas_examen_official_rules, trg_examenes_official_rules immediate;
  assert private.official_rules_problem(pg_temp.ex(2)) is null and private.official_rules_problem(pg_temp.ex(3)) = 'REEMPLAZO_NOT_ENOUGH_RESERVES' and private.official_rules_problem(pg_temp.ex(7)) = 'RESERVE_WITHOUT_MAIN', 'validation function';
  perform pg_temp.expect_e(format($q$update public.preguntas_examen set anulada = true, motivo_anulacion = 'x' where examen_id = %L and pregunta_id = any (%L::uuid[])$q$, pg_temp.ex(8), array[pg_temp.q(8, 1), pg_temp.q(8, 3), pg_temp.q(8, 4)]), 'OFFICIAL_RULES_INVALID');
  update public.preguntas_examen set anulada = true, motivo_anulacion = 'x' where examen_id = pg_temp.ex(8) and pregunta_id = pg_temp.q(8, 8);   -- an annulled reserve is accepted (skipped) with a warning
  assert 'ANNULLED_RESERVE_SKIPPED' = any (private.official_rules_warnings(pg_temp.ex(8))) and private.official_rules_problem(pg_temp.ex(8)) is null and public.admin_official_rules_check(pg_temp.ex(8)) ->> 'regla' = 'REEMPLAZO_POR_RESERVA', 'warning, not a block';
  update public.configuracion_v1 set valor_integer = 2 where clave = 'max_reservas_oficiales';
  assert 'MORE_RESERVES_THAN_MAX' = any (private.official_rules_warnings(pg_temp.ex(8))) and private.official_rules_problem(pg_temp.ex(8)) is null, 'more reserves than the maximum is a warning at publish, never an error';
  update public.configuracion_v1 set valor_integer = 3 where clave = 'max_reservas_oficiales';
  perform pg_temp.expect_e(format($q$update public.preguntas_examen set orden_reserva = 9 where examen_id = %L and pregunta_id = %L$q$, pg_temp.ex(8), pg_temp.q(8, 8)), 'OFFICIAL_RULES_INVALID');                 -- gaps
  update public.preguntas_examen set anulada = true, motivo_anulacion = 'x' where examen_id = pg_temp.ex(8) and pregunta_id = pg_temp.q(8, 3);   -- 2 annulled <= 3 reserves: accepted
  assert (select anulada from public.preguntas_examen where examen_id = pg_temp.ex(8) and pregunta_id = pg_temp.q(8, 3)), 'a valid annulment after publication is accepted';
  update public.examenes set publicado_en = null where id = pg_temp.ex(3);
  perform pg_temp.expect_e(format($q$update public.examenes set publicado_en = now() where id = %L$q$, pg_temp.ex(3)), 'OFFICIAL_RULES_INVALID');   -- publishing an invalid exam
  perform pg_temp.expect_e(format($q$update public.examenes set penalizacion_num = 4, penalizacion_den = 3 where id = %L$q$, pg_temp.ex(0)), 'new row for relation "examenes" violates check constraint "examenes_penalizacion_chk"');
  perform pg_temp.expect_e(format($q$update public.examenes set regla_anuladas = 'OTRA' where id = %L$q$, pg_temp.ex(0)), 'new row for relation "examenes" violates check constraint "examenes_penalizacion_chk"');
  perform pg_temp.expect_e(format($q$insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, es_reserva, orden_reserva) values (%L, %L, 150, 150, true, null)$q$, pg_temp.ex(0), pg_temp.q(1, 1)),
    'new row for relation "preguntas_examen" violates check constraint "preguntas_examen_reserva_chk"');
  -- demo cross-check on EVERY edit (draft or published)
  update public.preguntas_examen set es_demo = true where examen_id = pg_temp.ex(0) and pregunta_id = pg_temp.q(0, 1);
  perform pg_temp.expect_e(format($q$update public.preguntas_examen set anulada = true, motivo_anulacion = 'x' where examen_id = %L and pregunta_id = %L$q$, pg_temp.ex(0), pg_temp.q(0, 1)), 'DEMO_CONFLICT');
  perform pg_temp.expect_e(format($q$update public.preguntas_examen set posicion = 120 where examen_id = %L and pregunta_id = %L$q$, pg_temp.ex(0), pg_temp.q(0, 1)), 'DEMO_CONFLICT');
  perform pg_temp.expect_e(format($q$update public.preguntas_examen set es_demo = true where examen_id = %L and pregunta_id = %L$q$, pg_temp.ex(2), pg_temp.q(2, 2)), 'DEMO_CONFLICT');   -- flagging an annulled question
  perform pg_temp.expect_e(format($q$update public.preguntas_examen set es_demo = true where examen_id = %L and pregunta_id = %L$q$, pg_temp.ex(2), pg_temp.q(2, 8)), 'DEMO_CONFLICT');   -- flagging a reserve
  update public.preguntas_examen set anulada = true, motivo_anulacion = 'x' where examen_id = pg_temp.ex(0) and pregunta_id = pg_temp.q(0, 6);   -- a NON-demo question is free
  assert true;

  -- ---- the daily challenge never takes a reserve
  assert not exists (select 1 from private.reto_pool('e1000000-0000-0000-0000-0000000000f1', null, 'rafaga') p where p in (pg_temp.q(1, 7), pg_temp.q(1, 8), pg_temp.q(2, 7))), 'a reserve is not in the challenge pool';
  raise notice 'OK V1 official rules';
end $$;

-- ---- FA36 (owner 2026-10-17): RATIONAL penalty PD = A - E/(n-1), n = options of each wrong question, frozen at start
create function pg_temp.mkq(n int, p int, opts int, anul boolean default false, res int default null) returns void language plpgsql as $f$
begin
  insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) values (pg_temp.q(n, p), 'OR-' || n || '-' || p, 'rafaga', 'Pregunta ' || n || '-' || p, true);
  insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
    select ('0a000000-0000-0000-' || lpad(n::text, 4, '0') || '-' || lpad((p * 10 + o)::text, 12, '0'))::uuid, pg_temp.q(n, p), chr(64 + o), 'Opcion', o from generate_series(1, opts) o;
  insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento) values (pg_temp.q(n, p), ('0a000000-0000-0000-' || lpad(n::text, 4, '0') || '-' || lpad((p * 10 + 1)::text, 12, '0'))::uuid, 'x');
  insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, anulada, motivo_anulacion, es_reserva, orden_reserva)
    values (pg_temp.ex(n), pg_temp.q(n, p), p, p, anul, case when anul then 'tribunal' end, res is not null, res);
end $f$;
set local session_replication_role = replica;
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, regla_anuladas, penalizacion_racional) values
  (pg_temp.ex(12), 'OR-12', 'e1000000-0000-0000-0000-0000000000f1', 'c1000000-0000-0000-0000-0000000000f1', 'Racional 12', 'rafaga', 'gratuito', true, now() - interval '1 day', 'NO_PUNTUA', true),
  (pg_temp.ex(13), 'OR-13', 'e1000000-0000-0000-0000-0000000000f1', 'c1000000-0000-0000-0000-0000000000f1', 'Racional 13', 'rafaga', 'gratuito', true, now() - interval '1 day', 'REEMPLAZO_POR_RESERVA', true),
  (pg_temp.ex(15), 'OR-15', 'e1000000-0000-0000-0000-0000000000f1', 'c1000000-0000-0000-0000-0000000000f1', 'Racional 15', 'rafaga', 'gratuito', true, now() - interval '1 day', 'NO_PUNTUA', true);
select pg_temp.mkq(12, 1, 2), pg_temp.mkq(12, 2, 3), pg_temp.mkq(12, 3, 4), pg_temp.mkq(12, 4, 4), pg_temp.mkq(12, 5, 3), pg_temp.mkq(12, 6, 4);
select pg_temp.mkq(13, 1, 4), pg_temp.mkq(13, 2, 4, true), pg_temp.mkq(13, 3, 4), pg_temp.mkq(13, 4, 4), pg_temp.mkq(13, 5, 3, false, 1), pg_temp.mkq(13, 6, 3, false, 2);
select pg_temp.mkq(15, 1, 2), pg_temp.mkq(15, 2, 2), pg_temp.mkq(15, 3, 2), pg_temp.mkq(15, 4, 2);
set local session_replication_role = origin;

do $$
declare
  ua constant uuid := 'a0000000-0000-0000-0000-0000000000f1';
  r jsonb; a uuid; b uuid;
begin
  assert (select md5(string_agg(coalesce(private.official_nota_racional(c, pen, e)::text, 'null'), ',' order by c, e, pen))
            from generate_series(0, 10) c, generate_series(1, 12) e, (values (0), (6930), (9240), (13860), (20790), (27720), (41580), (55440), (83160), (138600)) p(pen)
           where c <= e) = 'edc00d6f3b7fe469e9484dc94cdf189b', 'the rational matrix equals the Kotlin matrix (AttemptRoutesTest)';
  assert private.official_nota_racional(2, 30030, 6) = 1.53 and private.official_nota_racional(2, 23100, 4) = 2.92 and private.official_nota_racional(1, 83160, 4) = 0.00 and private.official_nota_racional(3, 0, 0) is null,
         'worked examples (4 options: three wrong cancel one right), floor 0, no evaluables = null';
  assert private.official_nota_racional(2, 0, 6) = private.official_nota(2, 4, 6, 0, 1), 'without wrong answers it is the plain formula';

  -- ---- no annulment: wrong answers on questions with 3, 4 and 5 options; one unanswered (never penalised)
  r := public.app_start_exam_attempt(ua, pg_temp.ex(12));
  a := (r ->> 'intento_id')::uuid;
  assert (select penalizacion_racional is true from public.intentos_examen where id = a), 'the rule is frozen into the attempt at start';
  perform pg_temp.ans(ua, a, pg_temp.q(12, 1), true); perform pg_temp.ans(ua, a, pg_temp.q(12, 2), false); perform pg_temp.ans(ua, a, pg_temp.q(12, 3), true);
  perform pg_temp.ans(ua, a, pg_temp.q(12, 4), false); perform pg_temp.ans(ua, a, pg_temp.q(12, 5), false);
  -- the exam and the question change AFTER the start: the grade already due must not move
  update public.examenes set penalizacion_racional = false where id = pg_temp.ex(12);
  insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
    select ('0b000000-0000-0000-0000-' || lpad(o::text, 12, '0'))::uuid, pg_temp.q(12, 2), chr(67 + o), 'Extra', 3 + o from generate_series(1, 1) o;
  perform public.app_finish_exam_attempt(ua, a);
  -- c=2 (q1,q3) wrong: q2 (3 opts: 1/2), q4 (4: 1/3), q5 (3: 1/2); q6 unanswered: PD = 2 - 4/3 = 2/3 over 6 evaluables (MAIN allows 2..4 options: letters A-D)
  assert (select nota = 1.11 and penalizacion_descuento = 1.33 and total_correctas = 2 and total_falladas = 3 and total_sin_responder = 1 and total_evaluables = 6 from public.intentos_examen where id = a),
         'rational grade 1.11 (frozen options and flag, later edits ignored)';
  -- a NEW attempt after the exam parameter changed uses the NEW parameter (off): plain 2/6
  r := public.app_start_exam_attempt(ua, pg_temp.ex(12));
  b := (r ->> 'intento_id')::uuid;
  assert (select penalizacion_racional is false from public.intentos_examen where id = b), 'a new attempt freezes the new value';
  perform pg_temp.ans(ua, b, pg_temp.q(12, 1), true); perform pg_temp.ans(ua, b, pg_temp.q(12, 2), true); perform pg_temp.ans(ua, b, pg_temp.q(12, 3), false);
  perform public.app_finish_exam_attempt(ua, b);
  assert (select nota = 3.33 and penalizacion_descuento is null from public.intentos_examen where id = b), 'penalty off = plain formula';

  -- ---- annulled + reserve: the activated reserve is evaluable and penalised with ITS OWN option count (3), the annulled one is neither
  r := public.app_start_exam_attempt(ua, pg_temp.ex(13));
  a := (r ->> 'intento_id')::uuid;
  assert (select penalizacion_racional is true and reservas_usadas = 1 and total_anuladas = 1 from public.intentos_examen where id = a), 'frozen: rational + 1 reserve activated';
  assert pg_temp.ans(ua, a, pg_temp.q(13, 2), false) ->> 'estado' = 'ANNULLED', 'the annulled question stores nothing';
  perform pg_temp.ans(ua, a, pg_temp.q(13, 1), true); perform pg_temp.ans(ua, a, pg_temp.q(13, 3), false); perform pg_temp.ans(ua, a, pg_temp.q(13, 4), true); perform pg_temp.ans(ua, a, pg_temp.q(13, 5), false);
  perform public.app_finish_exam_attempt(ua, a);
  -- evaluables 4 (3 mains + reserve 1), c=2, wrong q3 (4 opts: 1/3) and the reserve (3 opts: 1/2): PD = 2 - 5/6 = 7/6 over 4
  assert (select nota = 2.92 and total_evaluables = 4 and total_correctas = 2 and total_falladas = 2 and penalizacion_descuento = 0.83 from public.intentos_examen where id = a), 'annulled + reserve: 2.92';

  -- ---- the grade never goes below 0
  r := public.app_start_exam_attempt(ua, pg_temp.ex(15));
  a := (r ->> 'intento_id')::uuid;
  perform pg_temp.ans(ua, a, pg_temp.q(15, 1), true); perform pg_temp.ans(ua, a, pg_temp.q(15, 2), false); perform pg_temp.ans(ua, a, pg_temp.q(15, 3), false); perform pg_temp.ans(ua, a, pg_temp.q(15, 4), false);
  perform public.app_finish_exam_attempt(ua, a);
  assert (select nota = 0.00 and penalizacion_descuento = 3.00 from public.intentos_examen where id = a), 'PD = 1 - 3 < 0 -> floor 0';
  -- the constraint: the rational penalty and a fixed fraction never mix
  perform pg_temp.expect_e(format($q$update public.examenes set penalizacion_num = 1, penalizacion_den = 3 where id = %L$q$, pg_temp.ex(15)), 'new row for relation "examenes" violates check constraint "examenes_penalizacion_chk"');
  -- FA36b: changing the grading rules is audited (who, old/new); other columns and no-op updates are not
  perform set_config('request.jwt.claim.sub', 'a0000000-0000-0000-0000-0000000000f1', true);
  -- the audit log is append-only in develop: never DELETE from it, compare with a baseline taken before
  create temp table pg_aud_base on commit drop as select count(*) as n from public.auditoria_log where accion = 'examen_reglas_puntuacion_modificadas' and registro_id = pg_temp.ex(15);
  update public.examenes set titulo = 'Racional 15b' where id = pg_temp.ex(15);
  update public.examenes set penalizacion_racional = true where id = pg_temp.ex(15);
  assert (select count(*) from public.auditoria_log where accion = 'examen_reglas_puntuacion_modificadas' and registro_id = pg_temp.ex(15)) = (select n from pg_aud_base), 'a title change or a no-op rule update writes no audit';
  update public.examenes set penalizacion_racional = false, penalizacion_num = 1, penalizacion_den = 3 where id = pg_temp.ex(15);
  assert (select count(*) from public.auditoria_log where accion = 'examen_reglas_puntuacion_modificadas' and registro_id = pg_temp.ex(15)) = (select n from pg_aud_base) + 1, 'a rule change writes exactly ONE audit row';
  assert (select bool_and(usuario_id = 'a0000000-0000-0000-0000-0000000000f1' and tabla_afectada = 'examenes'
            and (antes ->> 'penalizacion_racional')::boolean is true and (despues ->> 'penalizacion_racional')::boolean is false and (despues ->> 'penalizacion_num')::int = 1 and (despues ->> 'penalizacion_den')::int = 3)
            from (select * from public.auditoria_log where accion = 'examen_reglas_puntuacion_modificadas' and registro_id = pg_temp.ex(15) order by creado_en desc, id limit 1) l), 'with the actor and old/new values';
  raise notice 'OK V1 rational penalty';
end $$;
rollback;

-- 20261022101500: configuracion_v1 bounds + audit (E2-V1-P02); a `premium` gift can never be quota (E2-V1-A01).
begin;
create function pg_temp.expect_e(p_sql text, p_msg text) returns void language plpgsql as $$
declare v_msg text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_msg = message_text; end;
  if v_msg is distinct from p_msg then raise exception 'ASSERT FAILED: % -> expected % got %', p_sql, p_msg, v_msg; end if;
end $$;
insert into auth.users (id, email, aud, role) values ('a0000000-0000-0000-0000-0000000000f9', 'cfg@test.local', 'authenticated', 'authenticated');
insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-0000000000f9', 'CF', 'CF', 'cf', true);
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id) values ('a0000000-0000-0000-0000-0000000000f9', 'cf', 'cfu', true, 'activa', 'e1000000-0000-0000-0000-0000000000f9') on conflict (usuario_id) do nothing;
insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('c1000000-0000-0000-0000-0000000000f9', 'CFC', 'Com CF', 'com-cf');
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo)
  values ('e9300000-0000-0000-0000-000000000001', 'CF-1', 'e1000000-0000-0000-0000-0000000000f9', 'c1000000-0000-0000-0000-0000000000f9', 'Cfg', 'inmersivo', 'premium', true);
do $$
declare n integer;
begin
  -- bounds: negative, absurd, unknown key, bad time zone, flag outside 0..1
  perform pg_temp.expect_e($q$update public.configuracion_v1 set valor_integer = -1 where clave = 'tope_ot_gratis_dia'$q$, 'CONFIG_VALUE_OUT_OF_BOUNDS');
  perform pg_temp.expect_e($q$update public.configuracion_v1 set valor_integer = 2147483647 where clave = 'tope_ot_comprados_dia'$q$, 'CONFIG_VALUE_OUT_OF_BOUNDS');
  perform pg_temp.expect_e($q$update public.configuracion_v1 set valor_integer = 1000 where clave = 'max_inmersivos_incluidos'$q$, 'CONFIG_VALUE_OUT_OF_BOUNDS');
  perform pg_temp.expect_e($q$update public.configuracion_v1 set valor_integer = 2 where clave = 'reto_exige_email_verificado'$q$, 'CONFIG_VALUE_OUT_OF_BOUNDS');
  perform pg_temp.expect_e($q$insert into public.configuracion_v1 (clave, valor_integer) values ('clave_inventada', 1)$q$, 'CONFIG_KEY_UNKNOWN');
  perform pg_temp.expect_e($q$update public.configuracion_v1 set valor_text = 'Mars/Olympus' where clave = 'zona_horaria'$q$, 'CONFIG_VALUE_OUT_OF_BOUNDS');
  perform pg_temp.expect_e($q$update public.configuracion_v1 set valor_integer = null where clave = 'demo_preguntas'$q$, 'CONFIG_VALUE_OUT_OF_BOUNDS');
  select count(*) into n from public.auditoria_log where accion = 'configuracion_v1_editada';
  assert (select count(*) from public.auditoria_log where accion = 'configuracion_v1_editada') = n, 'denied writes leave no audit row';
  -- an accepted change is audited with the old and the new value
  update public.configuracion_v1 set valor_integer = 120 where clave = 'tope_ot_gratis_dia';
  assert exists (select 1 from public.auditoria_log where accion = 'configuracion_v1_editada' and (despues ->> 'valor_integer')::int = 120 and (antes ->> 'valor_integer')::int = 100 and despues ->> 'clave' = 'tope_ot_gratis_dia'), 'audit row with old and new value';
  update public.configuracion_v1 set valor_text = 'UTC' where clave = 'zona_horaria';
  -- a premium gift is a plain grant: it can neither carry a quota start nor be written as quota
  insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('a0000000-0000-0000-0000-0000000000f9', 'e9300000-0000-0000-0000-000000000001', 'premium', now() - interval '1 day', now() + interval '30 days');
  perform pg_temp.expect_e($q$update public.contenido_desbloqueado_usuario set cupo_suscripcion_inicio = now() where usuario_id = 'a0000000-0000-0000-0000-0000000000f9'$q$,
    'new row for relation "contenido_desbloqueado_usuario" violates check constraint "contenido_desbloqueado_usuario_plan_cupo_check"');
  perform pg_temp.expect_e($q$update public.contenido_desbloqueado_usuario set motivo = 'plan_cupo', cupo_suscripcion_inicio = now() where usuario_id = 'a0000000-0000-0000-0000-0000000000f9'$q$, 'PLAN_CUPO_WRITER_ONLY');
  assert (public.app_plan_quota('a0000000-0000-0000-0000-0000000000f9') ->> 'cupoUsado')::int = 0, 'a premium gift never counts as quota';
  raise notice 'OK V1 config bounds';
end $$;
rollback;

-- 20261022101600: W3 stats (first-answer hook exactly once, Nota media windows, Evolucion, Debilidades, difficulty job, grants).
begin;
create function pg_temp.expect_e(p_sql text, p_msg text) returns void language plpgsql as $$
declare v_msg text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_msg = message_text; end;
  if v_msg is distinct from p_msg then raise exception 'ASSERT FAILED: % -> expected % got %', p_sql, p_msg, v_msg; end if;
end $$;
create function pg_temp.q(p int) returns uuid language sql as $$ select ('f9100000-0000-0000-0000-' || lpad(p::text, 12, '0'))::uuid $$;
create function pg_temp.good(q uuid) returns uuid language sql as $$ select opcion_correcta_id from public.soluciones_pregunta where pregunta_id = q $$;
create function pg_temp.bad(q uuid) returns uuid language sql as $$ select id from public.opciones_pregunta where pregunta_id = q and id <> pg_temp.good(q) limit 1 $$;
create function pg_temp.ans(u uuid, a uuid, q uuid, ok boolean) returns jsonb language sql as $$
  select public.app_record_attempt_answer(u, a, q, case when ok then pg_temp.good(q) else pg_temp.bad(q) end, 0, 0, null) $$;

insert into auth.users (id, email, aud, role) values ('a0000000-0000-0000-0000-0000000000a1', 'st-a@test.local', 'authenticated', 'authenticated'), ('a0000000-0000-0000-0000-0000000000a2', 'st-b@test.local', 'authenticated', 'authenticated');
insert into auth.users (id, email, aud, role) select ('a0000000-0000-0000-0000-' || lpad((100 + n)::text, 12, '0'))::uuid, 'st-s' || n || '@test.local', 'authenticated', 'authenticated' from generate_series(1, 6) n;
insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-0000000000a1', 'ST', 'ST', 'st', true);
insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('c1000000-0000-0000-0000-0000000000a1', 'STC', 'Com ST', 'com-st');
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id) values
  ('a0000000-0000-0000-0000-0000000000a1', 'sa', 'sta', true, 'activa', 'e1000000-0000-0000-0000-0000000000a1'),
  ('a0000000-0000-0000-0000-0000000000a2', 'sb', 'stb', true, 'activa', 'e1000000-0000-0000-0000-0000000000a1') on conflict (usuario_id) do nothing;
insert into public.etiquetas (id, nombre, slug) values ('7a910000-0000-0000-0000-000000000001', 'Tema A', 'st-a'), ('7a910000-0000-0000-0000-000000000002', 'Tema B', 'st-b');
set local session_replication_role = replica;
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en)
values ('e9400000-0000-0000-0000-000000000001', 'ST-1', 'e1000000-0000-0000-0000-0000000000a1', 'c1000000-0000-0000-0000-0000000000a1', 'Stats', 'rafaga', 'gratuito', true, now() - interval '1 day');
insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) select pg_temp.q(p), 'ST-' || p, 'rafaga', 'Pregunta stats ' || p, true from generate_series(1, 17) p;
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
select ('0a910000-0000-0000-0000-' || lpad((p * 10 + o)::text, 12, '0'))::uuid, pg_temp.q(p), chr(64 + o), 'Opcion', o from generate_series(1, 17) p, generate_series(1, 2) o;
insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento) select pg_temp.q(p), ('0a910000-0000-0000-0000-' || lpad((p * 10 + 1)::text, 12, '0'))::uuid, 'x' from generate_series(1, 17) p;
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen) select 'e9400000-0000-0000-0000-000000000001', pg_temp.q(p), p, p from generate_series(1, 12) p;
insert into public.etiquetas_pregunta (pregunta_id, etiqueta_id) select pg_temp.q(p), case when p <= 6 then '7a910000-0000-0000-0000-000000000001'::uuid else '7a910000-0000-0000-0000-000000000002'::uuid end from generate_series(1, 12) p;
set local session_replication_role = origin;
insert into public.ligas_nivel (codigo, nombre, preguntas_minimas, ventana_calculo, posicion) values ('st-1', 'Liga 1', 0, 5, 1), ('st-2', 'Liga 2', 10, 8, 2), ('st-3', 'Liga 3', 30, 8, 3);

do $$
declare
  ua constant uuid := 'a0000000-0000-0000-0000-0000000000a1'; ub constant uuid := 'a0000000-0000-0000-0000-0000000000a2';
  ex constant uuid := 'e9400000-0000-0000-0000-000000000001';
  r jsonb; a uuid; b uuid; s jsonb; i int; seqs boolean[] := array[true, false, true, true, true, false, true, true, true, false];
begin
  update public.configuracion_v1 set valor_integer = 4 where clave = 'evolucion_cada_preguntas';
  update public.configuracion_v1 set valor_integer = 5 where clave = 'dificultad_min_respuestas';
  update public.configuracion_v1 set valor_integer = 5 where clave = 'debilidad_min_respuestas';
  perform pg_temp.expect_e($q$update public.configuracion_v1 set valor_integer = 0 where clave = 'evolucion_cada_preguntas'$q$, 'CONFIG_VALUE_OUT_OF_BOUNDS');
  -- nothing yet
  s := public.app_stats_summary(ua);
  assert s -> 'notaMedia' ->> 'valor' is null and (s ->> 'preguntasValidas')::int = 0 and (s -> 'totales' ->> 'aciertos')::int = 0 and (s -> 'notaMedia' ->> 'provisional')::boolean, 'empty summary';
  r := public.app_start_exam_attempt(ua, ex);
  a := (r ->> 'intento_id')::uuid;
  -- answer 1 (correct), replay, skip then answer (the skip never consumes the first answer)
  perform pg_temp.ans(ua, a, pg_temp.q(1), true);
  assert (select count(*) from public.primeras_respuestas_usuario where usuario_id = ua) = 1 and (select preguntas_respondidas from public.estadisticas_usuario where usuario_id = ua) = 1, 'first answer counted';
  perform pg_temp.ans(ua, a, pg_temp.q(1), true);
  assert (select preguntas_respondidas from public.estadisticas_usuario where usuario_id = ua) = 1 and not exists (select 1 from public.estadisticas_globales_pregunta where pregunta_id = pg_temp.q(1) and total_respuestas > 0), 'a replay never counts twice (the global counters are written by the job only)';
  perform public.app_record_attempt_answer(ua, a, pg_temp.q(2), null, 0, 0, null);
  assert (select count(*) from public.primeras_respuestas_usuario where usuario_id = ua) = 1, 'a skip is not a first answer';
  perform public.app_record_attempt_answer(ua, a, pg_temp.q(2), pg_temp.bad(pg_temp.q(2)), 0, 1, null);
  assert (select count(*) from public.primeras_respuestas_usuario where usuario_id = ua) = 2 and not (select correcta from public.primeras_respuestas_usuario where usuario_id = ua and pregunta_id = pg_temp.q(2)), 'the real answer after a skip is the first answer';
  -- exactly once at the function level: the guard row refuses a second count of the same (user, question)
  perform private.app_apply_first_answer_stats(ua, pg_temp.q(2), true, 10, a);
  assert (select preguntas_respondidas from public.estadisticas_usuario where usuario_id = ua) = 2, 'the guard row makes the hook idempotent';
  -- answers 3..10 following the planned sequence (1 T, 2 F, 3 T, 4 T, 5 T, 6 F, 7 T, 8 T, 9 T, 10 F)
  for i in 3..10 loop perform pg_temp.ans(ua, a, pg_temp.q(i), seqs[i]); end loop;
  s := public.app_stats_summary(ua);
  -- after 10 first answers the user is in Liga 2 (min 10): window 8 over answers 3..10 = 6 of 8 correct = 7.50
  assert (s -> 'notaMedia' ->> 'valor')::numeric = 7.50 and (s -> 'notaMedia' ->> 'ventana')::int = 8 and (s -> 'notaMedia' ->> 'respondidas')::int = 8 and not (s -> 'notaMedia' ->> 'provisional')::boolean, 'nota media over the league window: ' || s::text;
  assert s -> 'liga' ->> 'codigo' = 'st-2' and s -> 'liga' -> 'siguiente' ->> 'codigo' = 'st-3' and (s ->> 'preguntasValidas')::int = 10, 'league and next league';
  -- evolution: reference set at answer 4 (7.50), recomputed at answer 8 (8.00 - 7.50 = 0.50), progress 2 since then
  assert (s -> 'evolucion' ->> 'valor')::numeric = 0.50 and (s -> 'evolucion' ->> 'referencia')::numeric = 8.00 and (s -> 'evolucion' ->> 'progreso')::int = 2 and (s -> 'evolucion' ->> 'cada')::int = 4, 'evolution every N with a stored reference: ' || s::text;
  assert (s -> 'totales' ->> 'aciertos')::int = 7 and (s -> 'totales' ->> 'fallos')::int = 3, 'totals over first answers';
  assert (s -> 'racha' ->> 'actual')::int = 0, 'streak read through the challenge function';
  -- weaknesses: aggregated (tag A 4 of 6 = 66.67, tag B 3 of 4 = 75.00; minimum 5 hides B), then with minimum 1, weakest first
  s := public.app_stats_weaknesses(ua, null, 10);
  assert s ->> 'alcance' = 'TOTAL' and jsonb_array_length(s -> 'items') = 1 and s -> 'items' -> 0 ->> 'nombre' = 'Tema A' and (s -> 'items' -> 0 ->> 'porcentajeAcierto')::numeric = 66.67 and s -> 'items' -> 0 ->> 'nivelDominio' = 'medio', 'weaknesses with the minimum: ' || s::text;
  update public.configuracion_v1 set valor_integer = 1 where clave = 'debilidad_min_respuestas';
  s := public.app_stats_weaknesses(ua, null, 10);
  assert jsonb_array_length(s -> 'items') = 2 and s -> 'items' -> 0 ->> 'nombre' = 'Tema A' and s -> 'items' -> 1 ->> 'nombre' = 'Tema B' and (s -> 'items' -> 1 ->> 'porcentajeAcierto')::numeric = 75.00, 'weakest first';
  -- per exam, from the finished attempt (D2-12 numbers summed); another user sees nothing of it
  perform public.app_finish_exam_attempt(ua, a);
  s := public.app_stats_weaknesses(ua, ex, 10);
  assert s ->> 'alcance' = 'EXAMEN' and (select (it ->> 'sinResponder')::int from jsonb_array_elements(s -> 'items') it where it ->> 'nombre' = 'Tema B') = 2 and (select (it ->> 'correctas')::int from jsonb_array_elements(s -> 'items') it where it ->> 'nombre' = 'Tema A') = 4, 'per exam: ' || s::text;
  assert jsonb_array_length(public.app_stats_weaknesses(ub, ex, 10) -> 'items') = 0, 'own data only';
  assert (public.app_stats_summary(ua) -> 'totales' ->> 'noContestadas')::int = 2, 'unanswered questions of finished attempts';
  -- a DEMO attempt never feeds the stats
  r := public.app_start_exam_attempt(ub, ex);
  b := (r ->> 'intento_id')::uuid;
  update public.intentos_examen set es_demo = true where id = b;
  perform pg_temp.ans(ub, b, pg_temp.q(1), true);
  assert not exists (select 1 from public.primeras_respuestas_usuario where usuario_id = ub) and not exists (select 1 from public.estadisticas_usuario where usuario_id = ub), 'demo rows never count';

  -- ---- question difficulty: periodic job over the last first answers (5 questions x 6 synthetic users: failure bands 1,1,3,5,5 and median-time quintiles 1..5)
  insert into public.estadisticas_globales_pregunta (pregunta_id) select pg_temp.q(p) from generate_series(13, 17) p on conflict (pregunta_id) do nothing;
  insert into public.primeras_respuestas_usuario (usuario_id, pregunta_id, correcta, tiempo_ms)
  select ('a0000000-0000-0000-0000-' || lpad((100 + u)::text, 12, '0'))::uuid, pg_temp.q(12 + d), u > (array[0, 1, 3, 5, 6])[d], d * 2000
    from generate_series(1, 5) d, generate_series(1, 6) u;
  assert (public.app_job_dificultad(false) ->> 'estado') = 'OK', 'the job runs';
  assert (select array_agg(dificultad_calculada_1_5 order by pregunta_id) from public.estadisticas_globales_pregunta where pregunta_id in (select pg_temp.q(p) from generate_series(13, 17) p)) = array[1, 2, 3, 5, 5], 'difficulty 1-5 from median-time groups and the failure band';
  assert (select dificultad_calculada_1_5 is null from public.estadisticas_globales_pregunta where pregunta_id = pg_temp.q(1)), 'below the minimum: no difficulty';
  assert (public.app_job_dificultad(false) ->> 'estado') = 'RECIENTE', 'throttled by Control';
  assert (public.app_job_dificultad(true) ->> 'estado') = 'OK', 'forced run';
  -- reading: only a question the caller answered
  insert into public.primeras_respuestas_usuario (usuario_id, pregunta_id, correcta, tiempo_ms) values (ua, pg_temp.q(15), true, 100);
  assert (public.app_stats_difficulty(ua, pg_temp.q(15)) ->> 'dificultad')::int = 3 and (public.app_stats_difficulty(ua, pg_temp.q(1)) ->> 'dificultad') is null, 'difficulty of an answered question (null below the minimum)';
  perform pg_temp.expect_e(format($q$select public.app_stats_difficulty(%L, %L)$q$, ub, pg_temp.q(15)), 'NOT_FOUND');
  -- admin recompute: audited, cooldown, scope
  perform pg_temp.expect_e($q$select public.admin_recompute_stats(null, 'todo')$q$, 'STATS_SCOPE_INVALID');
  r := public.admin_recompute_stats(ua, 'dificultad');
  assert r ->> 'estado' = 'OK' and exists (select 1 from public.auditoria_log where accion = 'estadisticas_recalculo' and usuario_id = ua), 'audited admin recompute';
  perform pg_temp.expect_e(format($q$select public.admin_recompute_stats(%L, 'dificultad')$q$, ua), 'STATS_RECOMPUTE_COOLDOWN');
  -- grants (C-S2): no client reads the ledger of first answers or the global question statistics; the functions are service-only
  assert not has_table_privilege('authenticated', 'public.primeras_respuestas_usuario', 'select') and not has_table_privilege('service_role', 'public.primeras_respuestas_usuario', 'select')
     and not has_table_privilege('authenticated', 'public.estadisticas_globales_pregunta', 'select') and not has_table_privilege('anon', 'public.estadisticas_globales_pregunta', 'select'), 'tables closed to clients';
  assert not has_function_privilege('authenticated', 'public.app_stats_summary(uuid)', 'execute') and not has_function_privilege('anon', 'public.app_job_dificultad(boolean)', 'execute')
     and not has_function_privilege('authenticated', 'public.admin_recompute_stats(uuid,text)', 'execute') and has_function_privilege('service_role', 'public.app_stats_weaknesses(uuid,uuid,integer)', 'execute'), 'functions service-only';
  -- E2-V1-RK01: 7 instant answers and ONE long pause: the MEAN would pass a 1000 ms minimum (7 x 50 + 60000) / 8; the stored MEDIAN stays instant
  insert into auth.users (id, email, aud, role) values ('a0000000-0000-0000-0000-0000000000c9', 'st-c9@test.local', 'authenticated', 'authenticated');
  for i in 1..7 loop
    perform private.app_apply_first_answer_stats('a0000000-0000-0000-0000-0000000000c9', pg_temp.q(i), true, 50, null);
  end loop;
  perform private.app_apply_first_answer_stats('a0000000-0000-0000-0000-0000000000c9', pg_temp.q(8), true, 60000, null);
  assert (select ventana_tiempo_mediano_ms from public.estado_competitivo_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000c9') = 50
     and (select avg(tiempo_ms) from (select tiempo_ms from public.primeras_respuestas_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000c9' order by seq desc limit 5) w) > 1000, 'the window median stays at the instant level although the mean would pass the minimum';
  -- E2 conditions: kill switch (C-W3-1) skips ONLY the statistics, the answer is stored; time clamp (C-W3-2); counters come from the job (C-W3-4/7); paged job with a seq watermark (C-W3-5/6)
  update public.configuracion_v1 set valor_integer = 0 where clave = 'stats_activo';
  b := ((public.app_start_exam_attempt(ub, ex)) ->> 'intento_id')::uuid;
  perform pg_temp.ans(ub, b, pg_temp.q(12), true);
  assert exists (select 1 from public.respuestas_intento where usuario_id = ub and pregunta_id = pg_temp.q(12) and opcion_seleccionada_id is not null)
     and not exists (select 1 from public.primeras_respuestas_usuario where usuario_id = ub and pregunta_id = pg_temp.q(12)), 'kill switch: the answer is stored, the statistics part is skipped';
  update public.configuracion_v1 set valor_integer = 1 where clave = 'stats_activo';
  perform pg_temp.expect_e($q$update public.configuracion_v1 set valor_integer = 2 where clave = 'stats_activo'$q$, 'CONFIG_VALUE_OUT_OF_BOUNDS');
  perform private.app_apply_first_answer_stats(ub, pg_temp.q(16), true, 99999999, null);
  assert (select tiempo_ms from public.primeras_respuestas_usuario where usuario_id = ub and pregunta_id = pg_temp.q(16)) = 300000, 'client time is clamped server side';
  r := public.app_job_dificultad(true);
  assert (r ->> 'pendiente')::boolean is false and (select total_respuestas from public.estadisticas_globales_pregunta where pregunta_id = pg_temp.q(16)) = (select count(*) from public.primeras_respuestas_usuario where pregunta_id = pg_temp.q(16))
     and (select (detalle ->> 'hasta_seq')::bigint from public.lotes_actualizacion_ranking where tipo = 'dificultad' order by iniciado_en desc limit 1) is not null, 'the job derives the global counters and keeps a seq watermark';
  raise notice 'OK V1 wave 3 stats';
end $$;
rollback;

-- 20261022101700: W4 ranking (cohort positions, place probability, top 3 with opt-in handles, minimum cohort, own data only).
begin;
create function pg_temp.expect_e(p_sql text, p_msg text) returns void language plpgsql as $$
declare v_msg text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_msg = message_text; end;
  if v_msg is distinct from p_msg then raise exception 'ASSERT FAILED: % -> expected % got %', p_sql, p_msg, v_msg; end if;
end $$;
create function pg_temp.u(n int) returns uuid language sql as $$ select ('a0000000-0000-0000-0000-' || lpad((200 + n)::text, 12, '0'))::uuid $$;
insert into auth.users (id, email, aud, role) select pg_temp.u(n), 'rk' || n || '@test.local', 'authenticated', 'authenticated' from generate_series(1, 10) n;
insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-0000000000b1', 'RK', 'RK', 'rk', true), ('e1000000-0000-0000-0000-0000000000b2', 'RK2', 'RK2', 'rk2', true);
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
select pg_temp.u(n), 'rk' || n, case when n = 10 then null else 'rk_user' || n end, true, 'activa', case when n = 7 then 'e1000000-0000-0000-0000-0000000000b2'::uuid else 'e1000000-0000-0000-0000-0000000000b1'::uuid end from generate_series(1, 10) n
on conflict (usuario_id) do update set username = excluded.username, especialidad_principal_id = excluded.especialidad_principal_id;
insert into public.ligas_nivel (id, codigo, nombre, preguntas_minimas, ventana_calculo, posicion) values ('1e910000-0000-0000-0000-000000000001', 'rk-1', 'Liga RK', 0, 3, 1);
-- six ranked users (full window) in specialty 1, one in specialty 2, one without league, one with an incomplete window
insert into public.estado_competitivo_usuario (usuario_id, liga_nivel_id, preguntas_validas_ranking, ventana_calculo, nota_media_actual)
select pg_temp.u(n), case when n = 8 then null else '1e910000-0000-0000-0000-000000000001'::uuid end, case when n = 9 then 1 else 3 end, 3, (array[9.0, 8.0, 7.0, 6.0, 5.0, 4.0, 9.5, null, 2.0])[n] from generate_series(1, 9) n;
update public.estado_competitivo_usuario set liga_nivel_id = null where usuario_id = pg_temp.u(8);
update public.perfiles_usuario set ranking_publico = true where usuario_id in (pg_temp.u(1), pg_temp.u(3), pg_temp.u(7));

do $$
declare
  r jsonb; s jsonb; i int;
begin
  update public.configuracion_v1 set valor_integer = 5 where clave = 'cohorte_minima_comparativa';
  perform pg_temp.expect_e($q$update public.configuracion_v1 set valor_integer = 0 where clave = 'ranking_job_minutos'$q$, 'CONFIG_VALUE_OUT_OF_BOUNDS');
  -- before the job nothing is ranked
  r := public.app_ranking_me(pg_temp.u(4));
  assert not (r ? 'posicion') and r -> 'liga' ->> 'codigo' = 'rk-1' and (r -> 'visibilidad' ->> 'publico')::boolean is false, 'not ranked before the job: ' || r::text;
  s := public.app_job_ranking(true);
  assert s ->> 'estado' = 'OK', 'the job runs: ' || s::text;
  -- positions inside tier x specialty ordered by Nota media (specialty 2, the no-league user and the incomplete-window user are not in the cohort)
  assert (select array_agg(ranking_liga_posicion order by ranking_liga_posicion) from public.estado_competitivo_usuario where usuario_id in (select pg_temp.u(n) from generate_series(1, 6) n)) = array[1, 2, 3, 4, 5, 6], 'positions 1..6';
  assert (select ranking_liga_posicion from public.estado_competitivo_usuario where usuario_id = pg_temp.u(7)) = 1 and (select ranking_cohorte from public.estado_competitivo_usuario where usuario_id = pg_temp.u(7)) = 1, 'another specialty is another cohort';
  -- place probability: worse / (comparable - 1) x 100 over full windows of the same specialty and window: u1 100, u3 60, u4 40, u6 0
  assert (select probabilidad_plaza from public.estado_competitivo_usuario where usuario_id = pg_temp.u(1)) = 100.00 and (select probabilidad_plaza from public.estado_competitivo_usuario where usuario_id = pg_temp.u(3)) = 60.00
     and (select probabilidad_plaza from public.estado_competitivo_usuario where usuario_id = pg_temp.u(4)) = 40.00 and (select probabilidad_plaza from public.estado_competitivo_usuario where usuario_id = pg_temp.u(6)) = 0.00, 'place probability';
  -- own view at or above the minimum cohort (6 >= 5): own position, bucket, public top 3 of the OWN cohort only
  r := public.app_ranking_me(pg_temp.u(4));
  assert (r ->> 'posicion')::int = 4 and (r -> 'cohorte' ->> 'tramo')::int = 5 and (r -> 'probabilidadPlaza' ->> 'disponible')::boolean and (r -> 'probabilidadPlaza' ->> 'porcentaje')::numeric = 40.00 and (r -> 'probabilidadPlaza' ->> 'tramoCohorte')::int = 5, 'own view: ' || r::text;
  assert jsonb_array_length(r -> 'top3') = 2 and r -> 'top3' -> 0 ->> 'handle' = 'rk_user1' and (r -> 'top3' -> 0 ->> 'posicion')::int = 1 and r -> 'top3' -> 1 ->> 'handle' = 'rk_user3' and (r -> 'top3' -> 1 ->> 'posicion')::int = 3, 'only opted-in users with their real positions: ' || (r -> 'top3')::text;
  assert not (r::text like '%rk_user2%') and not (r::text like '%rk1@test%'), 'no other identifier leaks';
  r := public.app_ranking_me(pg_temp.u(7));
  assert (r ->> 'posicion')::int = 1 and r -> 'top3' = '[]'::jsonb and not (r ? 'cohorte') and r -> 'probabilidadPlaza' ->> 'motivo' = 'cohorte_insuficiente', 'a user of another specialty sees only its own cohort: ' || r::text;
  r := public.app_ranking_me(pg_temp.u(8));
  assert r -> 'liga' = 'null'::jsonb and r -> 'probabilidadPlaza' ->> 'motivo' = 'sin_liga' and r -> 'top3' = '[]'::jsonb, 'no league';
  r := public.app_ranking_me(pg_temp.u(9));
  assert r -> 'probabilidadPlaza' ->> 'motivo' = 'ventana_incompleta', 'incomplete window';
  -- C-W4-1: a window answered too fast is not ranked and is listed for Control
  update public.configuracion_v1 set valor_integer = 1000 where clave = 'ranking_min_ms_respuesta';
  update public.estado_competitivo_usuario set ventana_tiempo_mediano_ms = 100 where usuario_id = pg_temp.u(5);
  perform public.app_job_ranking(true);
  assert (select ranking_liga_posicion from public.estado_competitivo_usuario where usuario_id = pg_temp.u(5)) is null and exists (select 1 from public.ranking_ventanas_rapidas where usuario_id = pg_temp.u(5)), 'fast window: not ranked, listed';
  assert not has_table_privilege('authenticated', 'public.ranking_ventanas_rapidas', 'select') and not has_table_privilege('authenticated', 'public.lotes_actualizacion_ranking', 'select') and not has_table_privilege('anon', 'public.lotes_actualizacion_ranking', 'select'), 'views and batches closed to clients';
  update public.estado_competitivo_usuario set ventana_tiempo_mediano_ms = null where usuario_id = pg_temp.u(5);
  perform public.app_job_ranking(true);
  -- below the minimum cohort: only the own position
  update public.configuracion_v1 set valor_integer = 7 where clave = 'cohorte_minima_comparativa';
  r := public.app_ranking_me(pg_temp.u(4));
  assert (r ->> 'posicion')::int = 4 and not (r ? 'cohorte') and r -> 'top3' = '[]'::jsonb and not (r -> 'probabilidadPlaza' ->> 'disponible')::boolean and r -> 'probabilidadPlaza' ->> 'motivo' = 'cohorte_insuficiente', 'minimum cohort enforced in the database: ' || r::text;
  update public.configuracion_v1 set valor_integer = 5 where clave = 'cohorte_minima_comparativa';
  -- opt-in / opt-out
  assert (public.app_set_ranking_visibility(pg_temp.u(2), true) ->> 'publico')::boolean, 'opt in';
  perform public.app_job_ranking(true);
  r := public.app_ranking_me(pg_temp.u(4));
  assert jsonb_array_length(r -> 'top3') = 3 and r -> 'top3' -> 1 ->> 'handle' = 'rk_user2', 'the new public user is listed at the real position';
  perform public.app_set_ranking_visibility(pg_temp.u(1), false);
  assert not exists (select 1 from public.ranking_top where usuario_id = pg_temp.u(1)), 'opting out removes the listing immediately';
  perform pg_temp.expect_e(format($q$select public.app_set_ranking_visibility(%L, true)$q$, pg_temp.u(10)), 'USERNAME_REQUIRED');
  -- job: throttled, watermarked, admin recompute with audit and cooldown
  assert public.app_job_ranking(false) ->> 'estado' = 'RECIENTE', 'throttled by Control';
  update public.estado_competitivo_usuario set nota_media_actual = 9.9, actualizado_en = now() where usuario_id = pg_temp.u(6);
  update public.lotes_actualizacion_ranking set iniciado_en = now() - interval '1 hour' where tipo = 'ranking';
  s := public.app_job_ranking(false);
  assert s ->> 'estado' = 'OK' and (select ranking_liga_posicion from public.estado_competitivo_usuario where usuario_id = pg_temp.u(6)) = 1, 'a changed user is re-ranked by the incremental run';
  s := public.admin_recompute_stats(pg_temp.u(1), 'ranking');
  assert s ->> 'estado' = 'OK' and exists (select 1 from public.auditoria_log where accion = 'estadisticas_recalculo' and despues ->> 'alcance' = 'ranking'), 'audited admin recompute of the ranking';
  perform pg_temp.expect_e(format($q$select public.admin_recompute_stats(%L, 'ranking')$q$, pg_temp.u(1)), 'STATS_RECOMPUTE_COOLDOWN');
  perform pg_temp.expect_e($q$select public.admin_recompute_stats(null, 'todo')$q$, 'STATS_SCOPE_INVALID');
  -- grants
  assert not has_table_privilege('authenticated', 'public.ranking_top', 'select') and not has_table_privilege('anon', 'public.ranking_top', 'select') and not has_table_privilege('service_role', 'public.ranking_top', 'select'), 'top table closed';
  assert not has_function_privilege('authenticated', 'public.app_ranking_me(uuid)', 'execute') and not has_function_privilege('anon', 'public.app_job_ranking(boolean)', 'execute') and has_function_privilege('service_role', 'public.app_set_ranking_visibility(uuid,boolean)', 'execute'), 'functions service-only';
  raise notice 'OK V1 wave 4 ranking';
end $$;
rollback;

-- 20261022101800: W5a referral (neutral attach, state machine, payout job, signals that never raise, caps, privacy, revoke, grants).
begin;
create function pg_temp.expect_e(p_sql text, p_msg text) returns void language plpgsql as $$
declare v_msg text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_msg = message_text; end;
  if v_msg is distinct from p_msg then raise exception 'ASSERT FAILED: % -> expected % got %', p_sql, p_msg, v_msg; end if;
end $$;
create function pg_temp.att(p_id uuid, p_user uuid, p_demo boolean, p_answers int, p_secs int) returns void language plpgsql as $f$
begin
  insert into public.intentos_examen (id, usuario_id, examen_id, estado, es_demo, total_respondidas, duracion_segundos, iniciado_en) values (p_id, p_user, 'e9400000-0000-0000-0000-0000000003c1', 'en_progreso', p_demo, p_answers, p_secs, now() - interval '2 hours');
  update public.intentos_examen set estado = 'finalizado', finalizado_en = now() where id = p_id;
end $f$;
create function pg_temp.u(n int) returns uuid language sql as $$ select ('a0000000-0000-0000-0000-' || lpad((300 + n)::text, 12, '0'))::uuid $$;
insert into auth.users (id, email, aud, role) select pg_temp.u(n), 'ref' || n || '@test.local', 'authenticated', 'authenticated' from generate_series(1, 7) n;
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta)
select pg_temp.u(n), 'ref' || n, 'ref_user' || n, true, 'activa' from generate_series(1, 7) n on conflict (usuario_id) do update set username = excluded.username, estado_cuenta = 'activa';
insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-0000000003c1', 'RFX', 'RFX', 'rfx', true);
insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('c1000000-0000-0000-0000-0000000003c1', 'RFC', 'Com RF', 'com-rf');
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en)
values ('e9400000-0000-0000-0000-0000000003c1', 'RF-1', 'e1000000-0000-0000-0000-0000000003c1', 'c1000000-0000-0000-0000-0000000003c1', 'Referral', 'rafaga', 'gratuito', true, now() - interval '1 day');
update auth.users set email = 'same.root+a@test.local' where id = pg_temp.u(6);
update auth.users set email = 'samerootb@test.local' where id = pg_temp.u(1);
update auth.users set email = 'same.root+b@test.local' where id = pg_temp.u(7);

do $$
declare
  r jsonb; c1 text; c2 text; i public.invitaciones%rowtype; a uuid; n integer; s jsonb;
begin
  -- OFF by default: nothing attaches, nothing pays, the client sees no amount
  assert (select valor_integer from public.configuracion_v1 where clave = 'referido_activo') = 0, 'reward OFF by default';
  r := public.app_referral_get(pg_temp.u(1));
  c1 := r ->> 'codigo';
  assert c1 ~ '^[A-HJKMNP-TV-Z2-9]{8}$' and r -> 'configuracion' ->> 'otPorInvitacion' is null and (r -> 'invitados' ->> 'total')::int = 0, 'code format, feature off: no amount ' || r::text;
  assert (public.app_referral_get(pg_temp.u(1)) ->> 'codigo') = c1, 'the code is stable';
  c2 := public.app_referral_get(pg_temp.u(2)) ->> 'codigo';
  assert c2 <> c1, 'one code per user';
  assert (public.app_referral_attach(pg_temp.u(2), c1, null) ->> 'adjunta')::boolean is false, 'feature off: neutral false';
  assert (public.app_job_referidos() ->> 'estado') = 'APAGADO', 'job is off';
  perform pg_temp.expect_e($q$update public.configuracion_v1 set valor_integer = 5000 where clave = 'referido_ot_invitador'$q$, 'CONFIG_VALUE_OUT_OF_BOUNDS');
  perform pg_temp.expect_e($q$update public.configuracion_v1 set valor_integer = 2 where clave = 'referido_activo'$q$, 'CONFIG_VALUE_OUT_OF_BOUNDS');
  update public.configuracion_v1 set valor_integer = 1 where clave = 'referido_activo';
  update public.configuracion_v1 set valor_integer = 10 where clave in ('referido_ot_invitador', 'referido_ot_invitado');
  update public.configuracion_v1 set valor_integer = 2 where clave = 'referido_min_respuestas';
  update public.configuracion_v1 set valor_integer = 0 where clave in ('referido_min_segundos', 'referido_min_horas', 'referido_retener_horas');
  assert (public.app_referral_get(pg_temp.u(1)) -> 'configuracion' ->> 'otPorInvitacion')::int = 10, 'feature on shows the server amount';

  -- neutral attach: every unusable code is the same {adjunta:false}, never an error, never a row
  assert (public.app_referral_attach(pg_temp.u(2), 'ZZZZZZZZ', null) ->> 'adjunta')::boolean is false and (public.app_referral_attach(pg_temp.u(2), 'nope', null) ->> 'adjunta')::boolean is false
     and (public.app_referral_attach(pg_temp.u(2), c2, null) ->> 'adjunta')::boolean is false and (public.app_referral_attach(null, c1, null) ->> 'adjunta')::boolean is false, 'unknown / malformed / own / null user: neutral false';
  assert not exists (select 1 from public.invitaciones), 'no row from unusable codes';
  -- same mailbox root (plus-tag + dots) is rejected on the spot, silently
  perform public.app_referral_get(pg_temp.u(6));
  r := public.app_referral_attach(pg_temp.u(7), (select codigo from public.codigos_invitacion where usuario_id = pg_temp.u(6)), null);
  assert (r ->> 'adjunta')::boolean is true, 'attach answered';
  assert (select estado from public.invitaciones where invitado_id = pg_temp.u(7)) = 'rechazada', 'same mailbox root => rechazada';
  -- valid attach
  assert (public.app_referral_attach(pg_temp.u(2), c1, 'iphash1') ->> 'adjunta')::boolean is true, 'attach';
  select * into i from public.invitaciones where invitado_id = pg_temp.u(2);
  assert i.estado = 'registrada' and i.invitador_id = pg_temp.u(1) and i.pago_invitador = 'pendiente', 'registered, e-mail not confirmed yet';
  assert (public.app_referral_attach(pg_temp.u(2), c1, null) ->> 'adjunta')::boolean is false, 'an invitee attaches once';
  -- e-mail verified signal
  update auth.users set last_sign_in_at = now() where id = pg_temp.u(2);
  assert (select estado from public.invitaciones where invitado_id = pg_temp.u(2)) = 'registrada', 'only email_confirmed_at moves the state';
  update auth.users set email_confirmed_at = now() - interval '1 hour' where id = pg_temp.u(2);
  assert (select estado from public.invitaciones where invitado_id = pg_temp.u(2)) = 'email_verificado', 'e-mail verified recorded';
  -- exam finish: a demo attempt is ignored, a normal one is recorded, the FIRST stays
  perform pg_temp.att('b0000000-0000-0000-0000-000000000301', pg_temp.u(2), true, 9, 500);
  assert (select primer_examen_en from public.invitaciones where invitado_id = pg_temp.u(2)) is null, 'a demo attempt never counts';
  perform pg_temp.att('b0000000-0000-0000-0000-000000000302', pg_temp.u(2), false, 1, 100);
  assert (select intento_id from public.invitaciones where invitado_id = pg_temp.u(2)) = 'b0000000-0000-0000-0000-000000000302', 'first finished attempt recorded';
  -- a weak first attempt does not block the reward forever (R-2): the job looks at ALL attempts
  s := public.app_job_referidos();
  assert (select estado from public.invitaciones where invitado_id = pg_temp.u(2)) = 'email_verificado', 'weak attempt (1 answer < 2): not eligible yet ' || s::text;
  perform pg_temp.att('b0000000-0000-0000-0000-000000000303', pg_temp.u(2), false, 3, 100);
  assert (select intento_id from public.invitaciones where invitado_id = pg_temp.u(2)) = 'b0000000-0000-0000-0000-000000000302', 'the recorded first attempt is not overwritten';
  s := public.app_job_referidos();
  assert (select estado from public.invitaciones where invitado_id = pg_temp.u(2)) = 'diferida', 'rules met => diferida (hold)';
  s := public.app_job_referidos();
  assert (select estado from public.invitaciones where invitado_id = pg_temp.u(2)) = 'premiada' and (select pago_invitador from public.invitaciones where invitado_id = pg_temp.u(2)) = 'pagado', 'hold elapsed => premiada ' || s::text || (select coalesce(ultimo_error, '-') from public.invitaciones where invitado_id = pg_temp.u(2));
  assert (select saldo_tickets from public.carteras_usuario where usuario_id = pg_temp.u(1)) = 10 and (select saldo_tickets from public.carteras_usuario where usuario_id = pg_temp.u(2)) = 10, 'both sides paid exactly the configured amount';
  assert (select count(*) from public.transacciones_cartera where tipo = 'referido' and referencia_tipo = 'invitacion') = 2, 'two ledger rows';
  perform public.app_job_referidos(); perform public.app_job_referidos();
  assert (select count(*) from public.transacciones_cartera where tipo = 'referido') = 2, 'a second run never pays twice';
  -- privacy: counters for the inviter; the list only has rewarded invitees by public username; the invitee sees nothing of the inviter
  r := public.app_referral_get(pg_temp.u(1));
  assert (r -> 'invitados' ->> 'premiados')::int = 1 and (r ->> 'otGanados')::int = 10, 'counters';
  r := public.app_referral_list(pg_temp.u(1), null, 20);
  assert jsonb_array_length(r -> 'items') = 1 and r -> 'items' -> 0 ->> 'handle' = 'ref_user2' and not (r::text like '%ref2@%') and not (r::text like '%a0000000%'), 'list: handle only ' || r::text;
  assert jsonb_array_length(public.app_referral_list(pg_temp.u(2), null, 20) -> 'items') = 0, 'the invitee list is empty';
  perform pg_temp.expect_e($q$select public.app_referral_list('a0000000-0000-0000-0000-000000000301', 'bad-cursor', 5)$q$, 'CURSOR_INVALID');

  -- inviter daily cap: the second invitation waits (diferida) instead of paying
  update public.configuracion_v1 set valor_integer = 1 where clave = 'referido_tope_diario';
  perform public.app_referral_get(pg_temp.u(3));
  assert (public.app_referral_attach(pg_temp.u(3), c1, null) ->> 'adjunta')::boolean is true, 'second invitee';
  update auth.users set email_confirmed_at = now() - interval '1 hour' where id = pg_temp.u(3);
  perform pg_temp.att(gen_random_uuid(), pg_temp.u(3), false, 5, 100);
  perform public.app_job_referidos(); perform public.app_job_referidos();
  assert (select estado from public.invitaciones where invitado_id = pg_temp.u(3)) = 'diferida' and (select diferida_hasta from public.invitaciones where invitado_id = pg_temp.u(3)) > now()
     and (select count(*) from public.transacciones_cartera where tipo = 'referido') = 2, 'daily cap of the inviter keeps it waiting, nothing paid';
  update public.configuracion_v1 set valor_integer = 5 where clave = 'referido_tope_diario';

  -- poison row: a failing payout goes to error after the allowed attempts and never blocks the others
  update public.configuracion_v1 set valor_integer = 1 where clave = 'referido_max_intentos_pago';
  perform public.app_referral_get(pg_temp.u(4));
  perform public.app_referral_attach(pg_temp.u(5), (select codigo from public.codigos_invitacion where usuario_id = pg_temp.u(4)), null);
  update auth.users set email_confirmed_at = now() - interval '1 hour' where id = pg_temp.u(5);
  perform pg_temp.att(gen_random_uuid(), pg_temp.u(5), false, 5, 100);
  update public.invitaciones set diferida_hasta = now() - interval '1 minute', estado = 'diferida' where invitado_id in (pg_temp.u(3), pg_temp.u(5));
  create function public.tmp_poison() returns trigger language plpgsql as $f$ begin if new.pago_invitador = 'pagado' and new.invitador_id = 'a0000000-0000-0000-0000-000000000304' then raise exception 'boom'; end if; return new; end $f$;
  create trigger tmp_poison before update on public.invitaciones for each row execute function public.tmp_poison();
  s := public.app_job_referidos();
  assert (select estado from public.invitaciones where invitado_id = pg_temp.u(5)) = 'error' and (s ->> 'errores')::int >= 1, 'poison row => error ' || s::text;
  assert (select estado from public.invitaciones where invitado_id = pg_temp.u(3)) = 'premiada', 'the poison row did not block the other invitation';
  drop trigger tmp_poison on public.invitaciones;
  drop function public.tmp_poison();

  -- revoke: audited, never negative, reverses at most the unspent part, idempotent
  r := public.admin_referral_revoke(pg_temp.u(1), (select id from public.invitaciones where invitado_id = pg_temp.u(2)), 'test');
  assert r ->> 'estado' = 'revocada' and (r ->> 'noRevertible')::int = 0 and (select saldo_tickets from public.carteras_usuario where usuario_id = pg_temp.u(2)) = 0, 'revoke reverses the unspent grant ' || r::text;
  assert (public.admin_referral_revoke(pg_temp.u(1), (select id from public.invitaciones where invitado_id = pg_temp.u(2)), 'again') ->> 'revertido')::int = 0, 'revoke twice is a no-op';
  assert exists (select 1 from public.auditoria_log where accion = 'invitacion_revocada'), 'audited';
  assert (select min(saldo_tickets) from public.carteras_usuario where usuario_id in (select pg_temp.u(g) from generate_series(1, 7) g)) >= 0, 'never negative';
  perform pg_temp.expect_e($q$select public.admin_referral_revoke(null, gen_random_uuid(), 'x')$q$, 'REFERRAL_NOT_FOUND');

  -- grants: tables closed, functions service-only
  assert not has_table_privilege('authenticated', 'public.invitaciones', 'select') and not has_table_privilege('service_role', 'public.codigos_invitacion', 'select')
     and not has_table_privilege('authenticated', 'public.invitacion_intentos', 'insert') and not has_table_privilege('anon', 'public.invitaciones_dominios_bloqueados', 'select'), 'tables closed';
  assert not has_function_privilege('authenticated', 'public.app_referral_attach(uuid,text,text)', 'execute') and not has_function_privilege('anon', 'public.app_job_referidos()', 'execute')
     and has_function_privilege('service_role', 'public.app_referral_get(uuid)', 'execute'), 'functions service-only';
  raise notice 'OK V1 wave 5a referral';
end $$;
rollback;

-- 20261022101900: W5b foro (text): client writes/reads closed, server-only restriction, specialty gate, pre-moderation, content rules, duplicates, limits, counters, votes, reports, tombstone.
begin;
create function pg_temp.expect_e(p_sql text, p_msg text) returns void language plpgsql as $$
declare v_msg text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_msg = message_text; end;
  if v_msg is distinct from p_msg then raise exception 'ASSERT FAILED: % -> expected % got %', p_sql, p_msg, v_msg; end if;
end $$;
create function pg_temp.u(n int) returns uuid language sql as $$ select ('a0000000-0000-0000-0000-' || lpad((400 + n)::text, 12, '0'))::uuid $$;
create function pg_temp.cat(n int) returns uuid language sql as $$ select ('c0000000-0000-0000-0000-' || lpad((400 + n)::text, 12, '0'))::uuid $$;
insert into auth.users (id, email, aud, role) select pg_temp.u(n), 'foro' || n || '@test.local', 'authenticated', 'authenticated' from generate_series(1, 8) n;
update auth.users set created_at = now() - interval '3 days', email_confirmed_at = now() - interval '3 days' where id in (select pg_temp.u(n) from generate_series(1, 7) n);   -- u8 stays young and unverified
insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-000000000401', 'FA', 'FA', 'fa', true), ('e1000000-0000-0000-0000-000000000402', 'FB', 'FB', 'fb', true);
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
select pg_temp.u(n), 'f' || n, 'foro_u' || n, true, 'activa', case when n = 4 then 'e1000000-0000-0000-0000-000000000402'::uuid when n = 5 then null else 'e1000000-0000-0000-0000-000000000401'::uuid end
  from generate_series(1, 8) n on conflict (usuario_id) do update set username = excluded.username, estado_cuenta = 'activa', especialidad_principal_id = excluded.especialidad_principal_id;
insert into public.categorias_oposired (id, nombre, slug, especialidad_id, solo_lectura) values
  (pg_temp.cat(1), 'General', 'f-general', null, false), (pg_temp.cat(2), 'Solo FA', 'f-fa', 'e1000000-0000-0000-0000-000000000401', false),
  (pg_temp.cat(3), 'Solo FB', 'f-fb', 'e1000000-0000-0000-0000-000000000402', false), (pg_temp.cat(4), 'Avisos', 'f-avisos', null, true);

do $$
declare
  r jsonb; p1 uuid; p2 uuid; p3 uuid; c1 uuid; n integer; s text;
begin
  update public.configuracion_v1 set valor_integer = 1 where clave = 'foro_premoderacion_posts';
  -- ---- M-F3 / M-F4 BLOCKER: no client privilege, no client policy on the forum tables
  assert not exists (select 1 from (values ('public.publicaciones_oposired'), ('public.comentarios_oposired'), ('public.reacciones_oposired'), ('public.reportes_contenido'), ('public.acciones_moderacion'), ('public.categorias_oposired')) t(n)
                      where has_table_privilege('anon', t.n, 'select') or has_table_privilege('authenticated', t.n, 'select') or has_table_privilege('authenticated', t.n, 'insert')
                         or has_table_privilege('authenticated', t.n, 'update') or has_table_privilege('authenticated', t.n, 'delete')), 'forum tables closed to clients (read and write)';
  assert (select count(*) from pg_policies where schemaname = 'public' and tablename in ('publicaciones_oposired', 'comentarios_oposired', 'reacciones_oposired', 'reportes_contenido', 'acciones_moderacion', 'categorias_oposired')) = 0, 'no client policy left';
  assert not exists (select 1 from (values ('public.restricciones_foro'), ('public.moderacion_autor'), ('public.foro_actividad'), ('public.foro_bloqueos')) t(n)
                      where has_table_privilege('anon', t.n, 'select') or has_table_privilege('authenticated', t.n, 'select') or has_table_privilege('authenticated', t.n, 'update') or has_table_privilege('service_role', t.n, 'select')), 'M-F5: the restriction table has no client access';
  assert not exists (select 1 from pg_attribute a where a.attrelid = 'public.perfiles_usuario'::regclass and a.attname in ('puede_publicar', 'restringido_hasta') and not a.attisdropped), 'M-F5: nothing about restrictions on the profile';
  -- as the authenticated role: denied, really (not only by privilege flags)
  set local role authenticated;
  perform pg_temp.expect_e($q$insert into public.publicaciones_oposired (usuario_id, contenido) values ('a0000000-0000-0000-0000-000000000401', 'x')$q$, 'permission denied for table publicaciones_oposired');
  perform pg_temp.expect_e($q$select count(*) from public.publicaciones_oposired$q$, 'permission denied for table publicaciones_oposired');
  perform pg_temp.expect_e($q$delete from public.reacciones_oposired$q$, 'permission denied for table reacciones_oposired');
  perform pg_temp.expect_e($q$insert into public.reportes_contenido (reportado_por, tipo_entidad, entidad_id, motivo) values ('a0000000-0000-0000-0000-000000000401', 'usuario', gen_random_uuid(), 'x')$q$, 'permission denied for table reportes_contenido');
  reset role;
  set local role anon;
  perform pg_temp.expect_e($q$select count(*) from public.categorias_oposired$q$, 'permission denied for table categorias_oposired');
  reset role;

  -- ---- the gate: channels by specialty; no specialty = no forum
  assert (select jsonb_array_length(public.app_foro_categorias(pg_temp.u(1)) -> 'items')) = 3, 'FA user: general + FA + read-only';
  assert (select jsonb_array_length(public.app_foro_categorias(pg_temp.u(4)) -> 'items')) = 3 and (public.app_foro_categorias(pg_temp.u(4)) -> 'items' -> 1 ->> 'nombre') is not null, 'FB user: general + FB + read-only';
  assert jsonb_array_length(public.app_foro_categorias(pg_temp.u(5)) -> 'items') = 0, 'no specialty: no channels';
  perform pg_temp.expect_e(format($q$select public.app_foro_list(%L, null, 'recientes', null, 10)$q$, pg_temp.u(5)), 'FORO_NOT_FOUND');
  perform pg_temp.expect_e(format($q$select public.app_foro_create_post(%L, %L, 'Titulo', 'Cuerpo del mensaje', 'k1')$q$, pg_temp.u(5), pg_temp.cat(1)), 'FORO_NOT_FOUND');
  perform pg_temp.expect_e(format($q$select public.app_foro_create_post(%L, %L, 'Titulo', 'Cuerpo del mensaje', 'k1')$q$, pg_temp.u(4), pg_temp.cat(2)), 'FORO_NOT_FOUND');   -- other specialty's channel

  -- ---- first post is pre-moderated: only its author (marked) and Control see it
  r := public.app_foro_create_post(pg_temp.u(1), pg_temp.cat(2), 'Mi primera duda', 'Una duda sobre el tema 3 del temario.', 'key-1');
  p1 := (r ->> 'id')::uuid;
  assert r ->> 'enRevision' = 'true' and r ->> 'repetida' = 'false', 'new account: pendiente_moderacion';
  assert jsonb_array_length(public.app_foro_list(pg_temp.u(1), null, 'recientes', null, 10) -> 'items') = 1, 'the author sees it';
  assert jsonb_array_length(public.app_foro_list(pg_temp.u(2), null, 'recientes', null, 10) -> 'items') = 0, 'others do not see it in lists';
  perform pg_temp.expect_e(format($q$select public.app_foro_post(%L, %L)$q$, pg_temp.u(2), p1), 'FORO_NOT_FOUND');
  perform pg_temp.expect_e(format($q$select public.app_foro_vote(%L, 'publicacion', %L, true)$q$, pg_temp.u(2), p1), 'FORO_NOT_FOUND');
  perform pg_temp.expect_e(format($q$select public.app_foro_create_comment(%L, %L, 'hola', 'c-x')$q$, pg_temp.u(2), p1), 'FORO_NOT_FOUND');
  perform pg_temp.expect_e(format($q$select public.app_foro_report(%L, 'publicacion', %L, 'spam', null)$q$, pg_temp.u(2), p1), 'FORO_NOT_FOUND');
  -- the idempotent replay answers the same post
  r := public.app_foro_create_post(pg_temp.u(1), pg_temp.cat(2), 'Otro titulo', 'Otro cuerpo distinto del anterior mensaje', 'key-1');
  assert (r ->> 'id')::uuid = p1 and r ->> 'repetida' = 'true', 'same Idempotency-Key = same post';
  -- other specialty cannot read it even after approval
  perform public.admin_foro_moderate(pg_temp.u(1), 'publicacion', p1, 'aprobar', null, 'ok');
  assert (select aprobadas from public.moderacion_autor where usuario_id = pg_temp.u(1)) = 1, 'approval counted';
  assert jsonb_array_length(public.app_foro_list(pg_temp.u(2), null, 'recientes', null, 10) -> 'items') = 1, 'visible to the same specialty after approval';
  perform pg_temp.expect_e(format($q$select public.app_foro_post(%L, %L)$q$, pg_temp.u(4), p1), 'FORO_NOT_FOUND');
  perform pg_temp.expect_e(format($q$select public.app_foro_vote(%L, 'publicacion', %L, true)$q$, pg_temp.u(4), p1), 'FORO_NOT_FOUND');
  -- afterwards the author posts without review
  r := public.app_foro_create_post(pg_temp.u(1), pg_temp.cat(1), 'Segundo mensaje', 'Contenido del segundo mensaje del foro.', 'key-2');
  p2 := (r ->> 'id')::uuid;
  assert r ->> 'enRevision' = 'false', 'after the allowed number of approvals: published at once';
  -- read-only channel and write guards
  perform pg_temp.expect_e(format($q$select public.app_foro_create_post(%L, %L, 'Aviso', 'Un aviso que no debe poder escribirse', 'k9')$q$, pg_temp.u(1), pg_temp.cat(4)), 'FORO_NOT_FOUND');
  perform pg_temp.expect_e(format($q$select public.app_foro_create_post(%L, %L, 'Titulo', 'Cuerpo del mensaje de prueba', 'k10')$q$, pg_temp.u(8), pg_temp.cat(1)), 'POSTING_RESTRICTED');   -- young + unverified account

  -- ---- content rules
  perform pg_temp.expect_e(format($q$select public.app_foro_create_post(%L, %L, 'Enlace', 'mira https://ejemplo.com ahora', 'k11')$q$, pg_temp.u(2), pg_temp.cat(1)), 'CONTENT_REJECTED');
  perform pg_temp.expect_e(format($q$select public.app_foro_create_post(%L, %L, 'Enlace', 'mira ejemplo . com ahora', 'k12')$q$, pg_temp.u(2), pg_temp.cat(1)), 'CONTENT_REJECTED');
  perform pg_temp.expect_e(format($q$select public.app_foro_create_post(%L, %L, 'Enlace', 'mira ejemplo[.]com ahora', 'k13')$q$, pg_temp.u(2), pg_temp.cat(1)), 'CONTENT_REJECTED');
  perform pg_temp.expect_e(format($q$select public.app_foro_create_post(%L, %L, 'Enlace', 'mira hxxp://ejemplo ahora', 'k14')$q$, pg_temp.u(2), pg_temp.cat(1)), 'CONTENT_REJECTED');
  perform pg_temp.expect_e(format($q$select public.app_foro_create_post(%L, %L, 'Enlace', 'mira www.ejemplo ahora', 'k15')$q$, pg_temp.u(2), pg_temp.cat(1)), 'CONTENT_REJECTED');
  perform pg_temp.expect_e(format($q$select public.app_foro_create_post(%L, %L, 'Enlace', 'mira bit.ly/abc ahora', 'k16')$q$, pg_temp.u(2), pg_temp.cat(1)), 'CONTENT_REJECTED');
  perform pg_temp.expect_e(format($q$select public.app_foro_create_post(%L, %L, 'Enlace', 'mira ejemplo。com ahora', 'k17')$q$, pg_temp.u(2), pg_temp.cat(1)), 'CONTENT_REJECTED');
  perform pg_temp.expect_e(format($q$select public.app_foro_create_post(%L, %L, 'Emoji', 'familia %s de prueba', 'k18')$q$, pg_temp.u(2), pg_temp.cat(1), E'\U0001F468‍\U0001F469‍\U0001F467'), 'CONTENT_REJECTED');
  perform pg_temp.expect_e(format($q$select public.app_foro_create_post(%L, %L, 'Vacio', E'  \n\t ', 'k19')$q$, pg_temp.u(2), pg_temp.cat(1)), 'CONTENT_REJECTED');
  perform pg_temp.expect_e(format($q$select public.app_foro_create_post(%L, %L, %L, 'Cuerpo normal del mensaje', 'k20')$q$, pg_temp.u(2), pg_temp.cat(1), repeat('t', 121)), 'CONTENT_REJECTED');
  insert into public.foro_bloqueos (patron) values ('palabrota');
  perform pg_temp.expect_e(format($q$select public.app_foro_create_post(%L, %L, 'Titulo', 'una Palabrota cualquiera', 'k21')$q$, pg_temp.u(2), pg_temp.cat(1)), 'CONTENT_REJECTED');
  -- normalisation: NFC, bidi override and zero-width stripped
  r := public.app_foro_create_post(pg_temp.u(2), pg_temp.cat(1), 'Normaliza', E'café con​ leche‮ invertida', 'k22');
  assert (r ->> 'cuerpo') = 'café con leche invertida', 'NFC + bidi/zero-width stripped: ' || (r ->> 'cuerpo');

  -- ---- duplicates: same user / same text within the window = 409; another user's identical text goes to review silently
  perform pg_temp.expect_e(format($q$select public.app_foro_create_post(%L, %L, 'Segundo mensaje', 'Contenido del segundo mensaje del foro.', 'key-3')$q$, pg_temp.u(1), pg_temp.cat(1)), 'DUPLICATE_POST');
  r := public.app_foro_create_post(pg_temp.u(3), pg_temp.cat(1), 'Segundo mensaje', 'Contenido del segundo mensaje del foro.', 'key-4');
  assert r ->> 'enRevision' = 'true', 'identical text from ANOTHER user: pre-moderated (not rejected, not told)';

  -- ---- rate limit is database-authoritative and carries Retry-After
  update public.configuracion_v1 set valor_integer = 2 where clave = 'foro_posts_hora';
  perform public.app_foro_create_post(pg_temp.u(6), pg_temp.cat(1), 'Uno', 'Primer mensaje de la tanda de seis.', 'k30');
  perform public.app_foro_create_post(pg_temp.u(6), pg_temp.cat(1), 'Dos', 'Segundo mensaje de la tanda de seis.', 'k31');
  begin
    perform public.app_foro_create_post(pg_temp.u(6), pg_temp.cat(1), 'Tres', 'Tercer mensaje de la tanda de seis.', 'k32');
    raise exception 'rate limit did not fire';
  exception when others then
    get stacked diagnostics s = pg_exception_detail;
    assert sqlerrm = 'FORO_RATE_LIMITED' and (s::jsonb ->> 'retryAfter')::int between 1 and 3600, 'rate limit with retryAfter: ' || sqlerrm || coalesce(s, '');
  end;
  update public.configuracion_v1 set valor_integer = 5 where clave = 'foro_posts_hora';

  -- ---- restriction: server only, silent about who/why; expires
  perform public.admin_foro_moderate(pg_temp.u(1), 'publicacion', (select id from public.publicaciones_oposired where usuario_id = pg_temp.u(2) limit 1), 'silenciar', 3, 'prueba');
  perform pg_temp.expect_e(format($q$select public.app_foro_create_post(%L, %L, 'Tras mute', 'Mensaje escrito estando silenciado', 'k40')$q$, pg_temp.u(2), pg_temp.cat(1)), 'POSTING_RESTRICTED');
  perform pg_temp.expect_e(format($q$select public.app_foro_vote(%L, 'publicacion', %L, true)$q$, pg_temp.u(2), p2), 'POSTING_RESTRICTED');
  update public.restricciones_foro set hasta = now() - interval '1 minute' where usuario_id = pg_temp.u(2);
  perform public.app_foro_create_post(pg_temp.u(2), pg_temp.cat(1), 'Tras mute', 'Mensaje escrito despues del mute', 'k41');

  -- ---- comments: flat, counters, own delete
  c1 := (public.app_foro_create_comment(pg_temp.u(2), p2, 'Buena pregunta, yo tambien la tenia.', 'c1') ->> 'id')::uuid;
  assert (select n_comentarios from public.publicaciones_oposired where id = p2) = 1 and not exists (select 1 from public.comentarios_oposired where comentario_padre_id is not null), 'flat comment + counter';
  assert (public.app_foro_create_comment(pg_temp.u(2), p2, 'Otra cosa distinta', 'c1') ->> 'repetido') = 'true', 'comment replay';
  perform pg_temp.expect_e(format($q$select public.app_foro_create_comment(%L, %L, 'Buena pregunta, yo tambien la tenia.', 'c2')$q$, pg_temp.u(2), p2), 'DUPLICATE_POST');
  assert jsonb_array_length(public.app_foro_comments(pg_temp.u(3), p2, null, 20) -> 'items') = 1, 'comments readable';
  perform pg_temp.expect_e(format($q$select public.app_foro_comments(%L, %L, null, 20)$q$, pg_temp.u(4), p1), 'FORO_NOT_FOUND');
  perform pg_temp.expect_e(format($q$select public.app_foro_delete_own(%L, 'comentario', %L)$q$, pg_temp.u(3), c1), 'FORO_NOT_FOUND');   -- not the author
  perform public.app_foro_delete_own(pg_temp.u(2), 'comentario', c1);
  assert (select n_comentarios from public.publicaciones_oposired where id = p2) = 0, 'counter follows the delete';

  -- ---- votes: up-only, idempotent, none on own content, young accounts do not count
  assert (public.app_foro_vote(pg_temp.u(2), 'publicacion', p2, true) ->> 'meGusta')::int = 1, 'vote counted';
  assert (public.app_foro_vote(pg_temp.u(2), 'publicacion', p2, true) ->> 'meGusta')::int = 1, 'repeat is idempotent';
  perform pg_temp.expect_e(format($q$select public.app_foro_vote(%L, 'publicacion', %L, true)$q$, pg_temp.u(1), p2), 'FORO_NOT_FOUND');
  update auth.users set created_at = now() where id = pg_temp.u(3);
  r := public.app_foro_vote(pg_temp.u(3), 'publicacion', p2, true);
  assert (r ->> 'meGusta')::int = 1, 'young vote does not change the counter ' || r::text;
  assert (select cuenta from public.reacciones_oposired where usuario_id = pg_temp.u(3) and publicacion_id = p2) is false, 'a young account votes but does not count';
  update auth.users set created_at = now() - interval '3 days' where id = pg_temp.u(3);
  assert (public.app_foro_vote(pg_temp.u(2), 'publicacion', p2, false) ->> 'meGusta')::int = 0, 'unvote';
  perform public.app_foro_vote(pg_temp.u(2), 'publicacion', p2, true);
  assert jsonb_array_length(public.app_foro_list(pg_temp.u(3), null, 'votadas', null, 10) -> 'items') >= 2 and (public.app_foro_list(pg_temp.u(3), null, 'votadas', null, 10) -> 'items' -> 0 ->> 'id')::uuid = p2, 'orden votadas puts the voted post first';
  update public.configuracion_v1 set valor_integer = 1 where clave = 'foro_votos_min';
  perform pg_temp.expect_e(format($q$select public.app_foro_vote(%L, 'publicacion', %L, true)$q$, pg_temp.u(2), p1), 'FORO_RATE_LIMITED');
  update public.configuracion_v1 set valor_integer = 60 where clave = 'foro_votos_min';
  assert not exists (select 1 from public.foro_reconcile()), 'counters match the rows (drift view empty)';

  -- ---- reports: vocabulary, unique per reporter and entity, auto-hide off by default and reversible
  perform pg_temp.expect_e(format($q$select public.app_foro_report(%L, 'publicacion', %L, 'otra cosa', null)$q$, pg_temp.u(2), p2), 'FORO_REPORT_INVALID');
  perform public.app_foro_report(pg_temp.u(2), 'publicacion', p2, 'spam', 'parece publicidad');
  perform public.app_foro_report(pg_temp.u(2), 'publicacion', p2, 'ofensivo', null);
  assert (select count(*) from public.reportes_contenido where reportado_por = pg_temp.u(2) and entidad_id = p2) = 1, 'one report per reporter and entity';
  perform pg_temp.expect_e(format($q$select public.app_foro_report(%L, 'publicacion', %L, 'spam', null)$q$, pg_temp.u(1), p2), 'FORO_NOT_FOUND');   -- own content
  update public.configuracion_v1 set valor_integer = 2 where clave = 'foro_autoocultar_k';
  perform public.app_foro_report(pg_temp.u(3), 'publicacion', p2, 'spam', null);
  assert (select estado from public.publicaciones_oposired where id = p2) = 'oculta', 'K distinct aged spam reporters hide the post';
  assert jsonb_array_length(public.app_foro_list(pg_temp.u(2), null, 'recientes', null, 10) -> 'items') >= 1 and not exists (select 1 from jsonb_array_elements(public.app_foro_list(pg_temp.u(2), null, 'recientes', null, 10) -> 'items') i where (i ->> 'id')::uuid = p2), 'hidden content is not listed';
  perform pg_temp.expect_e(format($q$select public.app_foro_post(%L, %L)$q$, pg_temp.u(2), p2), 'FORO_NOT_FOUND');
  perform public.admin_foro_moderate(pg_temp.u(1), 'publicacion', p2, 'restaurar', null, 'falsa alarma');
  assert (select estado from public.publicaciones_oposired where id = p2) = 'publicada', 'a moderator restores it';
  update public.configuracion_v1 set valor_integer = 0 where clave = 'foro_autoocultar_k';

  -- ---- pagination and cursors
  update public.configuracion_v1 set valor_integer = 100 where clave = 'foro_posts_hora';
  perform public.app_foro_create_post(pg_temp.u(7), pg_temp.cat(1), 'P7a', 'Primer mensaje del usuario siete.', 'k50');
  perform public.admin_foro_moderate(pg_temp.u(1), 'publicacion', (select id from public.publicaciones_oposired where usuario_id = pg_temp.u(7) limit 1), 'aprobar', null, null);
  perform public.app_foro_create_post(pg_temp.u(7), pg_temp.cat(1), 'P7b', 'Segundo mensaje del usuario siete.', 'k51');
  perform public.app_foro_create_post(pg_temp.u(7), pg_temp.cat(1), 'P7c', 'Tercer mensaje del usuario siete.', 'k52');
  r := public.app_foro_list(pg_temp.u(2), pg_temp.cat(1), 'recientes', null, 2);
  assert jsonb_array_length(r -> 'items') = 2 and r ->> 'nextCursor' is not null, 'first page with cursor';
  s := r ->> 'nextCursor';
  assert (public.app_foro_list(pg_temp.u(2), pg_temp.cat(1), 'recientes', s, 50) -> 'items' -> 0 ->> 'id') <> (r -> 'items' -> 0 ->> 'id'), 'next page continues';
  perform pg_temp.expect_e(format($q$select public.app_foro_list(%L, null, 'recientes', 'not-a-cursor', 10)$q$, pg_temp.u(2)), 'CURSOR_INVALID');
  perform pg_temp.expect_e(format($q$select public.app_foro_list(%L, %L, 'recientes', null, 10)$q$, pg_temp.u(2), pg_temp.cat(3)), 'FORO_NOT_FOUND');   -- another specialty's channel

  -- ---- delete own post, tombstone on account deletion
  perform public.app_foro_delete_own(pg_temp.u(7), 'publicacion', (select id from public.publicaciones_oposired where usuario_id = pg_temp.u(7) and titulo = 'P7c'));
  assert not exists (select 1 from jsonb_array_elements(public.app_foro_list(pg_temp.u(2), pg_temp.cat(1), 'recientes', null, 50) -> 'items') i where i ->> 'titulo' = 'P7c'), 'a deleted post is gone';
  perform pg_temp.expect_e(format($q$select public.app_foro_delete_own(%L, 'publicacion', %L)$q$, pg_temp.u(3), p2), 'FORO_NOT_FOUND');
  perform public.foro_tombstone_user(pg_temp.u(2));
  assert not exists (select 1 from public.reacciones_oposired where usuario_id = pg_temp.u(2)) and (select n_me_gusta from public.publicaciones_oposired where id = p2) = 0, 'votes of a deleted account go and the counter follows';
  update public.publicaciones_oposired set usuario_id = null where usuario_id = pg_temp.u(7) and titulo = 'P7a';
  assert (select (i -> 'autor' ->> 'handle') is null from jsonb_array_elements(public.app_foro_list(pg_temp.u(3), pg_temp.cat(1), 'recientes', null, 50) -> 'items') i where i ->> 'titulo' = 'P7a'), 'tombstone: no handle';

  -- ---- images: dedicated table, init -> (Ktor re-encodes) -> commit, one post per image, retirement, sweeper, orphans
  assert not has_table_privilege('authenticated', 'public.imagenes_oposired', 'select') and not has_table_privilege('service_role', 'public.imagenes_oposired', 'select'), 'image table is server-only';
  perform pg_temp.expect_e(format($q$select public.app_foro_image_init(%L, 'image/png', 1000)$q$, pg_temp.u(8)), 'POSTING_RESTRICTED');
  perform pg_temp.expect_e(format($q$select public.app_foro_image_init(%L, 'image/gif', 1000)$q$, pg_temp.u(1)), 'IMAGE_NOT_USABLE');
  perform pg_temp.expect_e(format($q$select public.app_foro_image_init(%L, 'image/png', 3145729)$q$, pg_temp.u(1)), 'IMAGE_NOT_USABLE');
  r := public.app_foro_image_init(pg_temp.u(1), 'image/png', 2000);
  assert (r ->> 'path') like pg_temp.u(1)::text || '/%.png' and (select estado from public.imagenes_oposired where id = (r ->> 'imagenId')::uuid) = 'subida', 'server-generated path under the owner prefix';
  perform pg_temp.expect_e(format($q$select public.app_foro_image_commit(%L, %L, 'abc', 10, 10, 1000)$q$, pg_temp.u(2), r ->> 'imagenId'), 'FORO_NOT_FOUND');
  perform pg_temp.expect_e(format($q$select public.app_foro_image_status(%L, %L)$q$, pg_temp.u(2), r ->> 'imagenId'), 'FORO_NOT_FOUND');
  perform pg_temp.expect_e(format($q$select public.app_foro_create_post(%L, %L, 'Con imagen', 'Mensaje que usa una imagen sin verificar', 'ki1', %L)$q$, pg_temp.u(1), pg_temp.cat(1), r ->> 'imagenId'), 'IMAGE_NOT_USABLE');
  perform public.app_foro_image_commit(pg_temp.u(1), (r ->> 'imagenId')::uuid, repeat('a', 64), 100, 80, 1500);
  assert public.app_foro_image_status(pg_temp.u(1), (r ->> 'imagenId')::uuid) ->> 'estado' = 'verificada', 'verified';
  perform pg_temp.expect_e(format($q$select public.app_foro_create_post(%L, %L, 'Con imagen', 'Mensaje que usa una imagen ajena de otro usuario', 'ki2', %L)$q$, pg_temp.u(3), pg_temp.cat(1), r ->> 'imagenId'), 'IMAGE_NOT_USABLE');
  p3 := (public.app_foro_create_post(pg_temp.u(1), pg_temp.cat(1), 'Con imagen', 'Mensaje que adjunta una imagen verificada propia', 'ki3', (r ->> 'imagenId')::uuid) ->> 'id')::uuid;
  assert (public.app_foro_post(pg_temp.u(1), p3) ->> 'imagenId') = r ->> 'imagenId' and (public.app_foro_list(pg_temp.u(2), pg_temp.cat(1), 'recientes', null, 1) -> 'items' -> 0 ->> 'tieneImagen')::boolean, 'detail and feed show the image';
  perform pg_temp.expect_e(format($q$select public.app_foro_create_post(%L, %L, 'Otra', 'Otro mensaje que intenta reutilizar la imagen', 'ki4', %L)$q$, pg_temp.u(1), pg_temp.cat(1), r ->> 'imagenId'), 'IMAGE_NOT_USABLE');
  assert (public.app_foro_image_path(pg_temp.u(2), p3) ->> 'path') = r ->> 'path', 'a reader of a published post gets the object path to sign';
  update public.configuracion_v1 set valor_integer = 1 where clave = 'foro_imagen_urls_hora';
  perform pg_temp.expect_e(format($q$select public.app_foro_image_path(%L, %L)$q$, pg_temp.u(2), p3), 'FORO_RATE_LIMITED');
  update public.configuracion_v1 set valor_integer = 120 where clave = 'foro_imagen_urls_hora';
  -- retirement: deleting the post retires the image, bumps the version, queues the object for the sweeper
  perform public.app_foro_delete_own(pg_temp.u(1), 'publicacion', p3);
  assert (select estado from public.imagenes_oposired where id = (r ->> 'imagenId')::uuid) = 'retirada' and (select version from public.imagenes_oposired where id = (r ->> 'imagenId')::uuid) = 2, 'retired with a new version';
  perform pg_temp.expect_e(format($q$select public.app_foro_image_path(%L, %L)$q$, pg_temp.u(2), p3), 'FORO_NOT_FOUND');
  assert jsonb_array_length(public.app_foro_images_to_purge(10)) >= 1, 'the retired object is queued for the sweeper';
  assert public.app_foro_images_purged(array[(r ->> 'imagenId')::uuid]) = 1, 'purge confirmed';
  assert not exists (select 1 from jsonb_array_elements(public.app_foro_images_to_purge(10)) e where (e ->> 'id')::uuid = (r ->> 'imagenId')::uuid), 'the sweeper queue empties after the purge';
  -- E2-V1-F06: a moderator HIDE retires the image as well
  r := public.app_foro_image_init(pg_temp.u(1), 'image/png', 2000);
  perform public.app_foro_image_commit(pg_temp.u(1), (r ->> 'imagenId')::uuid, repeat('b', 64), 50, 50, 900);
  p3 := (public.app_foro_create_post(pg_temp.u(1), pg_temp.cat(1), 'Para ocultar', 'Mensaje con imagen que un moderador va a ocultar.', 'ki9', (r ->> 'imagenId')::uuid) ->> 'id')::uuid;
  perform public.admin_foro_moderate(pg_temp.u(1), 'publicacion', p3, 'ocultar', null, 'prueba');
  assert (select estado from public.imagenes_oposired where id = (r ->> 'imagenId')::uuid) = 'retirada', 'hide retires the image';
  -- reject and orphans
  r := public.app_foro_image_init(pg_temp.u(1), 'image/jpeg', 1000);
  perform public.app_foro_image_reject(pg_temp.u(1), (r ->> 'imagenId')::uuid, 'no valida');
  assert (select estado from public.imagenes_oposired where id = (r ->> 'imagenId')::uuid) = 'rechazada', 'rejected';
  perform pg_temp.expect_e(format($q$select public.app_foro_image_commit(%L, %L, 'x', 1, 1, 1)$q$, pg_temp.u(1), r ->> 'imagenId'), 'FORO_NOT_FOUND');
  r := public.app_foro_image_init(pg_temp.u(1), 'image/jpeg', 1000);
  update public.imagenes_oposired set creado_en = now() - interval '2 hours' where id = (r ->> 'imagenId')::uuid;
  perform public.app_job_foro();
  assert (select estado from public.imagenes_oposired where id = (r ->> 'imagenId')::uuid) = 'retirada', 'an upload never verified is retired by the job';
  update public.configuracion_v1 set valor_integer = 2 where clave = 'foro_imagenes_hora';
  perform public.app_foro_image_init(pg_temp.u(6), 'image/png', 100);
  perform public.app_foro_image_init(pg_temp.u(6), 'image/png', 100);
  perform pg_temp.expect_e(format($q$select public.app_foro_image_init(%L, 'image/png', 100)$q$, pg_temp.u(6)), 'FORO_RATE_LIMITED');
  update public.configuracion_v1 set valor_integer = 5 where clave = 'foro_imagenes_hora';

  -- ---- jobs and grants
  assert public.app_job_foro() ->> 'estado' = 'OK', 'job runs';
  assert not has_function_privilege('authenticated', 'public.app_foro_create_post(uuid,uuid,text,text,text,uuid)', 'execute') and not has_function_privilege('anon', 'public.app_foro_list(uuid,uuid,text,text,integer)', 'execute')
     and has_function_privilege('service_role', 'public.admin_foro_moderate(uuid,text,uuid,text,integer,text)', 'execute'), 'functions service-only';
  raise notice 'OK V1 wave 5b foro (text)';
end $$;
rollback;

-- 20261022102000: FA37 bulk import with reserves / annulled / duplicate-lot guard (E1-V1-CONTROL-SPEC-IMPORT.md)
begin;
create function pg_temp.expect_e(p_sql text, p_msg text) returns void language plpgsql as $$
declare v_msg text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_msg = message_text; end;
  if v_msg is distinct from p_msg then raise exception 'ASSERT FAILED: % -> expected % got %', p_sql, p_msg, v_msg; end if;
end $$;
create function pg_temp.ex(n int) returns uuid language sql as $$ select ('e9500000-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid $$;
create function pg_temp.lot(n int) returns uuid language sql as $$ select ('b9500000-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid $$;
insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-0000000000f5', 'IMP', 'IMP', 'imp', true);
insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('c1000000-0000-0000-0000-0000000000f5', 'IMC', 'Com IMP', 'com-imp');
-- exams: 1 official draft (the main one), 2 NOT official, 3 official draft (annulled > reserves), 4 official draft with a demo question, 5 official PUBLISHED
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, es_oficial)
select pg_temp.ex(n), 'IMP-' || n, 'e1000000-0000-0000-0000-0000000000f5', 'c1000000-0000-0000-0000-0000000000f5', 'Import ' || n, 'rafaga', 'gratuito',
       n = 5, case when n = 5 then now() - interval '1 day' end, n <> 2 from generate_series(1, 5) n;
create function pg_temp.mklot(n int, p_exam int, p_code text default null) returns uuid language plpgsql as $$
begin
  insert into public.lotes_importacion_preguntas (id, examen_id, nombre_archivo_original, tipo_examen, estado, total_detectadas, codigo_examen)
    values (pg_temp.lot(n), pg_temp.ex(p_exam), 'f' || n || '.pdf', 'rafaga', 'parseado', 0, p_code);
  return pg_temp.lot(n);
end $$;
create function pg_temp.st(p_lot int, p_pos int, p_bloque text, p_tag text, p_anulada boolean default false, p_vincular uuid default null) returns void language sql as $$
  insert into public.preguntas_parseadas_staging (lote_id, posicion, bloque_original, titulo, opcion_a, opcion_b, opcion_c, opcion_d, respuesta_correcta, estado_parseo, bloque, anulada, motivo_anulacion, vincular_pregunta_id)
  values (pg_temp.lot(p_lot), p_pos, 'raw', 'Pregunta imp ' || p_tag, 'A ' || p_tag, 'B ' || p_tag, 'C ' || p_tag, 'D ' || p_tag, 'A', case when p_vincular is null then 'ok' else 'aprobada' end,
          p_bloque, p_anulada, case when p_anulada then 'tribunal' end, p_vincular) $$;
do $$
declare
  r jsonb;
begin
  assert has_function_privilege('service_role', 'public.admin_import_lote_huella(uuid)', 'execute') and not has_function_privilege('authenticated', 'public.admin_import_lote_huella(uuid)', 'execute')
     and not has_function_privilege('anon', 'private.import_lote_huella(uuid)', 'execute'), 'ACL: service role only';

  -- ---- lot 1 on the official draft: 3 MAIN (the 2nd annulled) + 2 RESERVA in the lot order
  perform pg_temp.mklot(1, 1, 'IMP-1');
  perform pg_temp.st(1, 1, 'PRINCIPAL', 'a1'); perform pg_temp.st(1, 2, 'PRINCIPAL', 'a2', true); perform pg_temp.st(1, 3, 'PRINCIPAL', 'a3');
  perform pg_temp.st(1, 4, 'RESERVA', 'r1'); perform pg_temp.st(1, 5, 'RESERVA', 'r2');
  r := public.admin_import_lote_preguntas(pg_temp.lot(1));
  assert (r ->> 'ejecutado')::boolean and (r ->> 'nuevas')::int = 5 and (r ->> 'reservas')::int = 2 and (r ->> 'anuladas')::int = 1 and r -> 'problema' = 'null'::jsonb and (r ->> 'huella') ~ '^[0-9a-f]{64}$', 'lot 1 published: ' || r::text;
  assert (select string_agg(posicion || ':' || es_reserva::text || ':' || coalesce(orden_reserva::text, '-') || ':' || anulada::text, ',' order by posicion) from public.preguntas_examen where examen_id = pg_temp.ex(1))
         = '1:false:-:false,2:false:-:true,3:false:-:false,4:true:1:false,5:true:2:false', 'reserves come after the mains, numbered in the lot order; the annulment is on the relation';
  assert (select huella from public.lotes_importacion_preguntas where id = pg_temp.lot(1)) = r ->> 'huella', 'the lot stores its fingerprint';
  r := public.admin_import_lote_preguntas(pg_temp.lot(1));
  assert not (r ->> 'ejecutado')::boolean and (r ->> 'nuevas')::int = 0, 'idempotent: an imported lot writes nothing';
  perform pg_temp.expect_e(format($q$update public.preguntas_parseadas_staging set bloque = 'PRINCIPAL' where lote_id = %L and bloque = 'RESERVA'$q$, pg_temp.lot(1)), 'STAGING_ROW_ALREADY_IMPORTED');

  -- ---- duplicate-lot guard: the same content again for the same exam
  perform pg_temp.mklot(2, 1, 'IMP-1');
  perform pg_temp.st(2, 1, 'PRINCIPAL', 'a1'); perform pg_temp.st(2, 2, 'PRINCIPAL', 'a2', true); perform pg_temp.st(2, 3, 'PRINCIPAL', 'a3');
  perform pg_temp.st(2, 4, 'RESERVA', 'r1'); perform pg_temp.st(2, 5, 'RESERVA', 'r2');
  assert (public.admin_import_lote_huella(pg_temp.lot(2)) ->> 'duplicado_de')::uuid = pg_temp.lot(1), 'Control sees the duplicate at review time';
  perform pg_temp.expect_e(format('select public.admin_import_lote_preguntas(%L)', pg_temp.lot(2)), 'IMPORT_LOT_DUPLICATE');
  assert (select count(*) from public.preguntas_examen where examen_id = pg_temp.ex(1)) = 5, 'the refused lot wrote nothing';

  -- ---- a new MAIN row after reserves exist is refused (reserves always come last)
  perform pg_temp.mklot(3, 1, null);
  perform pg_temp.st(3, 1, 'PRINCIPAL', 'late');
  perform pg_temp.expect_e(format('select public.admin_import_lote_preguntas(%L)', pg_temp.lot(3)), 'IMPORT_MAIN_AFTER_RESERVE');
  -- ... but a second lot of RESERVES continues the numbering
  perform pg_temp.mklot(4, 1, null);
  perform pg_temp.st(4, 1, 'RESERVA', 'r3');
  r := public.admin_import_lote_preguntas(pg_temp.lot(4));
  assert (r ->> 'reservas')::int = 1 and (select orden_reserva from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id where pe.examen_id = pg_temp.ex(1) and p.titulo = 'Pregunta imp r3') = 3, 'a later reserve lot continues the order';
  assert jsonb_array_length(r -> 'avisos') = 0, 'three reserves = the default maximum, no warning';

  -- ---- header mismatch
  perform pg_temp.mklot(5, 1, 'OTHER');
  perform pg_temp.st(5, 1, 'RESERVA', 'h1');
  perform pg_temp.expect_e(format('select public.admin_import_lote_preguntas(%L)', pg_temp.lot(5)), 'IMPORT_EXAM_CODE_MISMATCH');

  -- ---- reserves only for official exams
  perform pg_temp.mklot(6, 2, null);
  perform pg_temp.st(6, 1, 'PRINCIPAL', 'n1'); perform pg_temp.st(6, 2, 'RESERVA', 'n2');
  perform pg_temp.expect_e(format('select public.admin_import_lote_preguntas(%L)', pg_temp.lot(6)), 'IMPORT_RESERVE_REQUIRES_OFFICIAL_EXAM');

  -- ---- more annulled than reserves (the derived rule is REEMPLAZO because the lot brings a reserve): refused, nothing written
  perform pg_temp.mklot(7, 3, null);
  perform pg_temp.st(7, 1, 'PRINCIPAL', 'x1', true); perform pg_temp.st(7, 2, 'PRINCIPAL', 'x2', true); perform pg_temp.st(7, 3, 'PRINCIPAL', 'x3'); perform pg_temp.st(7, 4, 'RESERVA', 'x4');
  perform pg_temp.expect_e(format('select public.admin_import_lote_preguntas(%L)', pg_temp.lot(7)), 'IMPORT_OFFICIAL_RULES_INVALID');
  assert (select count(*) from public.preguntas_examen where examen_id = pg_temp.ex(3)) = 0, 'refused lot rolled back';
  -- an ANNULLED reserve is a warning, not a block
  perform pg_temp.mklot(8, 3, null);
  perform pg_temp.st(8, 1, 'PRINCIPAL', 'y1'); perform pg_temp.st(8, 2, 'RESERVA', 'y2', true);
  r := public.admin_import_lote_preguntas(pg_temp.lot(8));
  assert r -> 'avisos' ? 'ANNULLED_RESERVE_SKIPPED', 'annulled reserve is warned: ' || r::text;

  -- ---- demo question: never annulled nor reserve through an import; moving an existing main to the reserve block is refused
  insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) values ('f9500000-0000-0000-0000-000000000001', 'IMP-DEMO', 'rafaga', 'Pregunta imp d1', true);
  insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion) select ('0a950000-0000-0000-0000-00000000000' || o)::uuid, 'f9500000-0000-0000-0000-000000000001', chr(64 + o), chr(64 + o) || ' d1', o from generate_series(1, 4) o;
  insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento) values ('f9500000-0000-0000-0000-000000000001', '0a950000-0000-0000-0000-000000000001', 'x');
  insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, es_demo) values (pg_temp.ex(4), 'f9500000-0000-0000-0000-000000000001', 1, 1, true);
  perform pg_temp.mklot(9, 4, null);
  perform pg_temp.st(9, 1, 'PRINCIPAL', 'd1', true, 'f9500000-0000-0000-0000-000000000001');
  perform pg_temp.expect_e(format('select public.admin_import_lote_preguntas(%L)', pg_temp.lot(9)), 'IMPORT_DEMO_CONFLICT');
  perform pg_temp.mklot(10, 4, null);
  perform pg_temp.st(10, 1, 'RESERVA', 'd1', false, 'f9500000-0000-0000-0000-000000000001');
  perform pg_temp.expect_e(format('select public.admin_import_lote_preguntas(%L)', pg_temp.lot(10)), 'IMPORT_BLOCK_CONFLICT');

  -- ---- a published official exam still needs the explicit confirmation, and the result must be coherent
  perform pg_temp.mklot(11, 5, null);
  perform pg_temp.st(11, 1, 'PRINCIPAL', 'p1'); perform pg_temp.st(11, 2, 'RESERVA', 'p2');
  perform pg_temp.expect_e(format('select public.admin_import_lote_preguntas(%L)', pg_temp.lot(11)), 'IMPORT_EXAM_PUBLISHED_CONFIRMATION_REQUIRED');
  r := public.admin_import_lote_preguntas(pg_temp.lot(11), true);
  assert (r ->> 'ejecutado')::boolean and (r ->> 'reservas')::int = 1, 'confirmed publication into a published official exam';
  raise notice 'OK FA37 import with reserves';
end $$;
rollback;

-- 20261022102100: FA38 razonado rich text: whitelist validator, plain derivation, write-path triggers (E1-V1-MARKUP-CONTRACT.md)
-- The vectors below are SHARED with the Kotlin parser: backend/ktor RichMarkupTest reads them from this file (between the VECTORS markers), parses every
-- source and requires exactly the JSON and plain text listed; the BAD list must be rejected by RichMarkup.validateJson as well as by private.markup_valid.
begin;
create function pg_temp.expect_e(p_sql text, p_msg text) returns void language plpgsql as $$
declare v_msg text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_msg = message_text; end;
  if v_msg is distinct from p_msg then raise exception 'ASSERT FAILED: % -> expected % got %', p_sql, p_msg, v_msg; end if;
end $$;
create temp table mv_ok(src text, js text, pl text);
create temp table mv_bad(js text);
-- MARKUP-VECTORS-OK-BEGIN
insert into mv_ok values
($v$Hola **mundo**$v$, $v$ {"v":1,"blocks":[{"k":"p","spans":[{"text":"Hola "},{"text":"mundo","bold":true}]}]} $v$, $v$Hola mundo$v$),
($v$__a__ ++b++ ~~c~~$v$, $v$ {"v":1,"blocks":[{"k":"p","spans":[{"text":"a","italic":true},{"text":" "},{"text":"b","underline":true},{"text":" "},{"text":"c","strike":true}]}]} $v$, $v$a b c$v$),
($v$[color:red]rojo[/color] [size:18]grande[/size]$v$, $v$ {"v":1,"blocks":[{"k":"p","spans":[{"text":"rojo","color":"red"},{"text":" "},{"text":"grande","size":"lg"}]}]} $v$, $v$rojo grande$v$),
($v$[align:center]Centrado[p]Otro[/align]$v$, $v$ {"v":1,"blocks":[{"k":"p","a":"center","spans":[{"text":"Centrado"}]},{"k":"p","a":"center","spans":[{"text":"Otro"}]}]} $v$, $v$Centrado
Otro$v$),
($v$Intro
[-] uno
[-] dos$v$, $v$ {"v":1,"blocks":[{"k":"p","spans":[{"text":"Intro"}]},{"k":"li","spans":[{"text":"uno"}]},{"k":"li","spans":[{"text":"dos"}]}]} $v$, $v$Intro
• uno
• dos$v$),
($v$**negrita [color:#aa00ff]y color[/color]**$v$, $v$ {"v":1,"blocks":[{"k":"p","spans":[{"text":"negrita ","bold":true},{"text":"y color","bold":true,"color":"#AA00FF"}]}]} $v$, $v$negrita y color$v$),
($v$5 \* 3 y [sic] y 1 * 2 *** 3$v$, $v$ {"v":1,"blocks":[{"k":"p","spans":[{"text":"5 * 3 y [sic] y 1 * 2 *** 3"}]}]} $v$, $v$5 * 3 y [sic] y 1 * 2 *** 3$v$),
($v$linea uno
linea dos [p] tres$v$, $v$ {"v":1,"blocks":[{"k":"p","spans":[{"text":"linea uno linea dos"}]},{"k":"p","spans":[{"text":"tres"}]}]} $v$, $v$linea uno linea dos
tres$v$);
-- MARKUP-VECTORS-OK-END
-- MARKUP-VECTORS-BAD-BEGIN
insert into mv_bad values
($v$ {"v":2,"blocks":[]} $v$),
($v$ {"v":1,"blocks":[],"x":1} $v$),
($v$ {"v":1,"blocks":[{"k":"p","spans":[]}]} $v$),
($v$ {"v":1,"blocks":[{"k":"h1","spans":[{"text":"x"}]}]} $v$),
($v$ {"v":1,"blocks":[{"k":"p","a":"middle","spans":[{"text":"x"}]}]} $v$),
($v$ {"v":1,"blocks":[{"k":"p","spans":[{"text":""}]}]} $v$),
($v$ {"v":1,"blocks":[{"k":"p","spans":[{"text":"a\nb"}]}]} $v$),
($v$ {"v":1,"blocks":[{"k":"p","spans":[{"text":"x","bold":false}]}]} $v$),
($v$ {"v":1,"blocks":[{"k":"p","spans":[{"text":"x","size":"18"}]}]} $v$),
($v$ {"v":1,"blocks":[{"k":"p","spans":[{"text":"x","color":"#abc"}]}]} $v$),
($v$ {"v":1,"blocks":[{"k":"p","spans":[{"text":"x","color":"#aabbcc"}]}]} $v$),
($v$ {"v":1,"blocks":[{"k":"p","spans":[{"text":"x","href":"http://evil"}]}]} $v$),
($v$ {"v":1,"blocks":[{"k":"p","spans":[{"text":"x‮y"}]}]} $v$),
($v$ [1,2] $v$);
-- MARKUP-VECTORS-BAD-END
do $$
declare
  r record;
  rr jsonb;
  v_q uuid;
begin
  assert (select count(*) from mv_ok) = 8 and (select count(*) from mv_bad) = 14, 'vector counts';
  for r in select * from mv_ok loop
    assert private.markup_valid(r.js::jsonb), 'valid vector accepted: ' || r.src;
    assert private.markup_plain(r.js::jsonb) = r.pl, 'plain text of the vector: ' || r.src || ' got ' || coalesce(private.markup_plain(r.js::jsonb), 'null');
  end loop;
  for r in select * from mv_bad loop
    assert not private.markup_valid(r.js::jsonb), 'invalid vector rejected: ' || r.js;
  end loop;
  assert not private.markup_valid(null), 'null is not a document';
  assert not has_function_privilege('authenticated', 'private.markup_valid(jsonb)', 'execute') and not has_function_privilege('anon', 'private.markup_plain(jsonb)', 'execute'), 'private functions';

  -- ---- solutions: valid + consistent rich text; a stale plain edit drops it
  insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-0000000000f6', 'MKP', 'MKP', 'mkp', true);
  insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('c1000000-0000-0000-0000-0000000000f6', 'MKC', 'Com MKP', 'com-mkp');
  insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) values ('f9600000-0000-0000-0000-000000000001', 'MKP-1', 'razonada', 'Pregunta mk 1', true);
  insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion) select ('0a960000-0000-0000-0000-00000000000' || o)::uuid, 'f9600000-0000-0000-0000-000000000001', chr(64 + o), 'op ' || o, o from generate_series(1, 4) o;
  rr := (select js::jsonb from mv_ok where src = 'Hola **mundo**');
  insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento, razonamiento_markup, razonamiento_rico) values ('f9600000-0000-0000-0000-000000000001', '0a960000-0000-0000-0000-000000000001', 'Hola mundo', 'Hola **mundo**', rr);
  assert (select razonamiento_rico = rr and razonamiento_markup = 'Hola **mundo**' from public.soluciones_pregunta where pregunta_id = 'f9600000-0000-0000-0000-000000000001'), 'rich text stored';
  assert (private.app_revision_pregunta_solucion('f9600000-0000-0000-0000-000000000001') -> 'razonamiento_rico') = rr, 'the revision snapshot carries the rich text';
  perform pg_temp.expect_e($q$update public.soluciones_pregunta set razonamiento_rico = '{"v":1,"blocks":[{"k":"p","spans":[{"text":"otro"}]}]}'::jsonb where pregunta_id = 'f9600000-0000-0000-0000-000000000001'$q$, 'MARKUP_PLAIN_MISMATCH');
  perform pg_temp.expect_e($q$update public.soluciones_pregunta set razonamiento_rico = '{"v":9}'::jsonb where pregunta_id = 'f9600000-0000-0000-0000-000000000001'$q$, 'MARKUP_INVALID');
  perform pg_temp.expect_e($q$update public.soluciones_pregunta set razonamiento_markup = null where pregunta_id = 'f9600000-0000-0000-0000-000000000001'$q$, 'MARKUP_SOURCE_REQUIRED');
  update public.soluciones_pregunta set razonamiento = 'Texto nuevo' where pregunta_id = 'f9600000-0000-0000-0000-000000000001';
  assert (select razonamiento_rico is null and razonamiento_markup is null and razonamiento = 'Texto nuevo' from public.soluciones_pregunta where pregunta_id = 'f9600000-0000-0000-0000-000000000001'), 'a legacy plain edit drops the stale rich version';
  assert not (private.app_revision_pregunta_solucion('f9600000-0000-0000-0000-000000000001') ? 'razonamiento_rico'), 'no rich key when there is none (revision identity unchanged)';

  -- ---- the import: the staging row carries plain + rich + source; the created solution gets the rich text in the same transaction
  insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, es_oficial)
    values ('e9600000-0000-0000-0000-000000000001', 'MKP-EX', 'e1000000-0000-0000-0000-0000000000f6', 'c1000000-0000-0000-0000-0000000000f6', 'Razonado mk', 'razonado', 'gratuito', false, false);
  insert into public.lotes_importacion_preguntas (id, examen_id, nombre_archivo_original, tipo_examen, estado, total_detectadas) values ('b9600000-0000-0000-0000-000000000001', 'e9600000-0000-0000-0000-000000000001', 'mk.pdf', 'razonado', 'parseado', 2);
  perform pg_temp.expect_e($q$insert into public.preguntas_parseadas_staging (lote_id, posicion, bloque_original, titulo, opcion_a, opcion_b, opcion_c, opcion_d, respuesta_correcta, razonamiento, razonamiento_markup, razonamiento_rico, estado_parseo)
     values ('b9600000-0000-0000-0000-000000000001', 9, 'raw', 't', 'a', 'b', 'c', 'd', 'A', 'x', 'x', '{"v":1,"blocks":[{"k":"p","spans":[{"text":"y"}]}]}', 'ok')$q$, 'MARKUP_PLAIN_MISMATCH');
  insert into public.preguntas_parseadas_staging (lote_id, posicion, bloque_original, titulo, opcion_a, opcion_b, opcion_c, opcion_d, respuesta_correcta, razonamiento, razonamiento_markup, razonamiento_rico, estado_parseo) values
    ('b9600000-0000-0000-0000-000000000001', 1, 'raw', 'Pregunta mk estilo', 'a1', 'b1', 'c1', 'd1', 'A', 'Hola mundo', 'Hola **mundo**', rr, 'ok'),
    ('b9600000-0000-0000-0000-000000000001', 2, 'raw', 'Pregunta mk plana', 'a2', 'b2', 'c2', 'd2', 'B', 'Sin estilo', null, null, 'ok');
  perform public.admin_import_lote_preguntas('b9600000-0000-0000-0000-000000000001');
  assert (select s.razonamiento = 'Hola mundo' and s.razonamiento_rico = rr and s.razonamiento_markup = 'Hola **mundo**'
            from public.soluciones_pregunta s join public.preguntas p on p.id = s.pregunta_id where p.titulo = 'Pregunta mk estilo'), 'import: plain column, rich text and source reach the solution';
  assert (select s.razonamiento = 'Sin estilo' and s.razonamiento_rico is null from public.soluciones_pregunta s join public.preguntas p on p.id = s.pregunta_id where p.titulo = 'Pregunta mk plana'), 'import: an unstyled text stays plain';
  raise notice 'OK FA38 razonado markup';
end $$;
rollback;

-- 20261022102200: FA39 binding of the email deep-link code to the requesting device (E1-V1-NIGHT-PKCE.md)
begin;
create function pg_temp.expect_e(p_sql text, p_msg text) returns void language plpgsql as $$
declare v_msg text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_msg = message_text; end;
  if v_msg is distinct from p_msg then raise exception 'ASSERT FAILED: % -> expected % got %', p_sql, p_msg, v_msg; end if;
end $$;
do $$
declare
  e1 constant text := repeat('a', 64);
  e2 constant text := repeat('b', 64);
  c1 constant text := repeat('A', 43);
  c2 constant text := repeat('B', 43);
  n int;
begin
  assert has_function_privilege('service_role', 'public.auth_code_binding_register(text,text,text,integer)', 'execute')
     and has_function_privilege('service_role', 'public.auth_code_binding_consume(text,text,text)', 'execute')
     and not has_function_privilege('anon', 'public.auth_code_binding_consume(text,text,text)', 'execute')
     and not has_function_privilege('authenticated', 'public.auth_code_binding_register(text,text,text,integer)', 'execute')
     and not has_table_privilege('authenticated', 'private.auth_code_bindings', 'select'), 'service role only';

  perform public.auth_code_binding_register('signup', e1, c1);
  -- another email (or purpose) cannot spend it, and a wrong attempt does NOT burn it
  assert public.auth_code_binding_consume('signup', e2, c1) is null, 'a binding of another email is not spent';
  assert public.auth_code_binding_consume('recovery', e1, c1) is null, 'purposes are separate';
  assert (select count(*) from private.auth_code_bindings where challenge = c1) = 1, 'the failed attempts did not consume it';
  assert public.auth_code_binding_consume('signup', null, c2) is null, 'unknown challenge';
  -- single use, returns the pseudonym of the email it was requested for
  assert public.auth_code_binding_consume('signup', e1, c1) = e1, 'consumed once with the right email';
  assert public.auth_code_binding_consume('signup', e1, c1) is null, 'second use fails';

  -- without an email hint any holder of the verifier matches and learns the pseudonym to compare with the session
  perform public.auth_code_binding_register('recovery', e2, c2);
  assert public.auth_code_binding_consume('recovery', null, c2) = e2, 'no email hint: matches by challenge, returns the pseudonym';

  -- the first registration of a challenge wins (nobody can re-bind it to another email)
  perform public.auth_code_binding_register('signup', e1, c1);
  perform public.auth_code_binding_register('signup', e2, c1);
  assert (select email_ref from private.auth_code_bindings where purpose = 'signup' and challenge = c1) = e1, 'first registration wins';
  delete from private.auth_code_bindings;

  -- at most 5 live bindings per (purpose, email): the oldest go
  for n in 1..8 loop perform public.auth_code_binding_register('signup', e1, repeat(chr(64 + n), 43)); end loop;
  assert (select count(*) from private.auth_code_bindings where purpose = 'signup' and email_ref = e1) = 5, 'bounded per email';
  assert public.auth_code_binding_consume('signup', e1, repeat('A', 43)) is null and public.auth_code_binding_consume('signup', e1, repeat('H', 43)) = e1, 'the oldest were dropped, the newest kept';

  -- expiry
  perform public.auth_code_binding_register('signup', e2, repeat('Z', 43), 60);
  update private.auth_code_bindings set expires_at = clock_timestamp() - interval '1 second' where challenge = repeat('Z', 43);
  assert public.auth_code_binding_consume('signup', e2, repeat('Z', 43)) is null, 'expired bindings do not match';
  perform public.auth_code_binding_register('signup', e2, repeat('Y', 43));
  assert not exists (select 1 from private.auth_code_bindings where challenge = repeat('Z', 43)), 'expired rows are swept on write';

  -- shapes
  perform pg_temp.expect_e($q$select public.auth_code_binding_register('other', repeat('a', 64), repeat('A', 43))$q$, 'BINDING_PURPOSE_INVALID');
  perform pg_temp.expect_e($q$select public.auth_code_binding_register('signup', 'not-hex', repeat('A', 43))$q$, 'new row for relation "auth_code_bindings" violates check constraint "auth_code_bindings_email_ref_check"');
  perform pg_temp.expect_e($q$select public.auth_code_binding_register('signup', repeat('a', 64), 'short')$q$, 'new row for relation "auth_code_bindings" violates check constraint "auth_code_bindings_challenge_check"');
  raise notice 'OK FA39 auth code binding';
end $$;
rollback;

-- 20261022102300: NIGHT-C Control RPCs (E1-V1-NIGHT-C.md): admin-only, audited (append-only: only INSERTs), strict inputs, idempotent, no oracle
begin;
create function pg_temp.expect_e(p_sql text, p_msg text) returns void language plpgsql as $$
declare v_msg text;
begin
  begin execute p_sql; exception when others then get stacked diagnostics v_msg = message_text; end;
  if v_msg is distinct from p_msg then raise exception 'ASSERT FAILED: % -> expected % got %', p_sql, p_msg, v_msg; end if;
end $$;
create function pg_temp.u(n int) returns uuid language sql as $$ select ('a0000000-0000-0000-0000-' || lpad((700 + n)::text, 12, '0'))::uuid $$;
create function pg_temp.ex(n int) returns uuid language sql as $$ select ('e9700000-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid $$;
create function pg_temp.q(n int, p int) returns uuid language sql as $$ select ('f9700000-0000-0000-' || lpad(n::text, 4, '0') || '-' || lpad(p::text, 12, '0'))::uuid $$;
create function pg_temp.audits(p_accion text) returns bigint language sql as $$ select count(*) from public.auditoria_log where accion = p_accion $$;
insert into auth.users (id, email, aud, role) select pg_temp.u(n), 'nc' || n || '@test.local', 'authenticated', 'authenticated' from generate_series(1, 8) n;
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta)
  select pg_temp.u(n), 'nc' || n, 'nc_user_' || n, true, 'activa' from generate_series(1, 8) n
  on conflict (usuario_id) do update set username = excluded.username;
insert into public.roles_app (codigo, nombre) select 'admin', 'Admin' where not exists (select 1 from public.roles_app where codigo = 'admin');
insert into public.roles_usuario (usuario_id, rol_id) select pg_temp.u(1), id from public.roles_app where codigo = 'admin';
do $$
declare
  adm constant uuid := pg_temp.u(1);
  usr constant uuid := pg_temp.u(2);
  r jsonb; r2 jsonb; n0 bigint; o record; oid uuid; oid2 uuid; oid3 uuid;
begin
  -- ---- ACL: service role only, nothing for clients
  assert (select bool_and(has_function_privilege('service_role', f, 'execute') and not has_function_privilege('anon', f, 'execute') and not has_function_privilege('authenticated', f, 'execute'))
            from unnest(array['public.admin_referral_list(text,text,integer)', 'public.admin_foro_bloqueos_list()', 'public.admin_foro_bloqueos_add(uuid,text)', 'public.admin_foro_bloqueos_remove(uuid,text)',
                              'public.admin_foro_restricciones_list()', 'public.admin_foro_imagenes_purga_resumen()', 'public.admin_foro_restriccion_levantar(uuid,uuid)', 'public.admin_set_exam_question_flags(uuid,uuid,uuid,boolean,text,boolean,integer)',
                              'public.admin_set_exam_grading_rules(uuid,uuid,text,integer,integer,boolean,text)', 'public.admin_payment_order_resolve(uuid,uuid,text,text,boolean)']) f), 'service role only';

  -- ---- referral list: handles only, filter, cursor paging, summary
  insert into public.invitaciones (invitador_id, invitado_id, codigo, estado, creado_en, flags, ultimo_error)
    values (pg_temp.u(3), pg_temp.u(4), 'AAAAAAAA', 'registrada', now() - interval '3 hours', '{"misma_ip": true, "x": false}', 'boom'),
           (pg_temp.u(3), pg_temp.u(5), 'AAAAAAAA', 'premiada', now() - interval '2 hours', '{}', null),
           (pg_temp.u(3), pg_temp.u(6), 'AAAAAAAA', 'premiada', now() - interval '1 hours', '{}', null);
  r := public.admin_referral_list(null, null, 2);
  assert jsonb_array_length(r -> 'items') = 2 and r ->> 'nextCursor' is not null and r -> 'resumen' = '{"registrada": 1, "premiada": 2}'::jsonb, 'page 1 + summary: ' || r::text;
  assert (r -> 'items' -> 0 ->> 'invitadoHandle') = 'nc_user_6' and (r -> 'items' -> 0 ->> 'invitadorHandle') = 'nc_user_3' and not (r::text like '%nc3@test.local%'), 'handles only, newest first';
  r2 := public.admin_referral_list(null, r ->> 'nextCursor', 2);
  assert jsonb_array_length(r2 -> 'items') = 1 and r2 ->> 'nextCursor' is null and r2 -> 'items' -> 0 -> 'marcas' = '{"misma_ip": true}'::jsonb and r2 -> 'items' -> 0 ->> 'ultimoError' = 'boom', 'page 2, only true flags';
  assert jsonb_array_length(public.admin_referral_list('premiada', null, 50) -> 'items') = 2 and jsonb_array_length(public.admin_referral_list('revocada', null, 50) -> 'items') = 0, 'state filter';
  perform pg_temp.expect_e($q$select public.admin_referral_list('hacked', null, 10)$q$, 'REFERRAL_FILTER_INVALID');
  perform pg_temp.expect_e($q$select public.admin_referral_list(null, 'not-a-cursor', 10)$q$, 'CURSOR_INVALID');
  assert jsonb_array_length(public.admin_referral_list(null, null, 100000) -> 'items') = 3 and jsonb_array_length(public.admin_referral_list(null, null, -5) -> 'items') = 1, 'limit clamped 1..100';

  -- ---- forum block list: literal patterns, normalised, bounded, audited once, admin only
  n0 := pg_temp.audits('foro_bloqueo_agregado');
  r := public.admin_foro_bloqueos_add(adm, '  PalabroTA  ');
  assert r = '{"patron": "palabrota", "creado": true}'::jsonb and pg_temp.audits('foro_bloqueo_agregado') = n0 + 1, 'added, lower case, audited';
  assert public.admin_foro_bloqueos_add(adm, 'palabrota') = '{"patron": "palabrota", "creado": false}'::jsonb and pg_temp.audits('foro_bloqueo_agregado') = n0 + 1, 'idempotent: no second audit row';
  assert public.admin_foro_bloqueos_list() ? 'palabrota' and private.foro_has_link('eres un PALABROTA'), 'listed and enforced';
  perform public.admin_foro_bloqueos_add(adm, '%a%');
  assert private.foro_has_link('oferta 10%a% hoy') and not private.foro_has_link('hola caracola'), 'LITERAL match: % is not a LIKE wildcard';
  perform pg_temp.expect_e($q$select public.admin_foro_bloqueos_add(pg_temp.u(2), 'otro')$q$, 'ADMIN_ACTOR_INVALID');
  perform pg_temp.expect_e($q$select public.admin_foro_bloqueos_add(null, 'otro')$q$, 'ADMIN_ACTOR_INVALID');
  perform pg_temp.expect_e($q$select public.admin_foro_bloqueos_add(pg_temp.u(1), 'a')$q$, 'FORO_BLOCK_INVALID');
  perform pg_temp.expect_e($q$select public.admin_foro_bloqueos_add(pg_temp.u(1), null)$q$, 'FORO_BLOCK_INVALID');
  perform pg_temp.expect_e(format($q$select public.admin_foro_bloqueos_add(%L, %L)$q$, adm, repeat('x', 101)), 'FORO_BLOCK_INVALID');
  n0 := pg_temp.audits('foro_bloqueo_quitado');
  r := public.admin_foro_bloqueos_remove(adm, 'PALABROTA');
  assert r = '{"patron": "palabrota", "eliminado": true}'::jsonb and pg_temp.audits('foro_bloqueo_quitado') = n0 + 1 and not private.foro_has_link('eres un palabrota'), 'removed + audited';
  assert (public.admin_foro_bloqueos_remove(adm, 'palabrota') ->> 'eliminado') = 'false' and pg_temp.audits('foro_bloqueo_quitado') = n0 + 1, 'second removal is a no-op without audit';
  perform pg_temp.expect_e($q$select public.admin_foro_bloqueos_remove(pg_temp.u(2), 'x1')$q$, 'ADMIN_ACTOR_INVALID');
  insert into public.foro_bloqueos (patron) select 'bulk-' || lpad(g::text, 4, '0') from generate_series(1, 499) g;
  perform pg_temp.expect_e(format($q$select public.admin_foro_bloqueos_add(%L, 'one-too-many')$q$, adm), 'FORO_BLOCK_LIMIT');
  assert (public.admin_foro_bloqueos_add(adm, '%A%') ->> 'creado') = 'false', 'an existing pattern is still idempotent at the limit';

  -- ---- active mutes
  insert into public.restricciones_foro (usuario_id, hasta, motivo, por) values (pg_temp.u(3), now() + interval '2 days', 'spam', adm), (pg_temp.u(4), now() - interval '1 day', 'old', adm);
  r := public.admin_foro_restricciones_list();
  assert jsonb_array_length(r) = 1 and r -> 0 ->> 'handle' = 'nc_user_3' and r -> 0 ->> 'motivo' = 'spam' and r -> 0 ->> 'usuarioId' = pg_temp.u(3)::text, 'only active mutes, with the handle: ' || r::text;
  perform pg_temp.expect_e($q$select public.admin_foro_restriccion_levantar(pg_temp.u(2), pg_temp.u(3))$q$, 'ADMIN_ACTOR_INVALID');
  n0 := pg_temp.audits('foro_restriccion_levantada');
  r := public.admin_foro_restriccion_levantar(adm, pg_temp.u(3));
  assert r = '{"levantada": true}'::jsonb and pg_temp.audits('foro_restriccion_levantada') = n0 + 1 and public.admin_foro_restricciones_list() = '[]'::jsonb, 'lifted + audited';
  assert public.admin_foro_restriccion_levantar(adm, pg_temp.u(3)) = '{"levantada": false}'::jsonb and pg_temp.audits('foro_restriccion_levantada') = n0 + 1, 'second lift is a no-op';

  -- ---- forum image purge summary (read only)
  assert public.admin_foro_imagenes_purga_resumen() = '{"pendientes": 0, "atascadas": 0, "masAntiguaEn": null}'::jsonb, 'empty queue';
  insert into public.imagenes_oposired (usuario_id, path, mime, bytes, estado, retirada_en, intentos_purga) values
    (pg_temp.u(3), 'p/a.jpg', 'image/jpeg', 10, 'retirada', now() - interval '3 days', 0), (pg_temp.u(3), 'p/b.jpg', 'image/jpeg', 10, 'rechazada', now() - interval '1 day', 2),
    (pg_temp.u(3), 'p/c.jpg', 'image/jpeg', 10, 'retirada', now() - interval '2 days', 10), (pg_temp.u(3), 'p/d.jpg', 'image/jpeg', 10, 'verificada', null, 0);
  insert into public.imagenes_oposired (usuario_id, path, mime, bytes, estado, retirada_en, purgada_en) values (pg_temp.u(3), 'p/e.jpg', 'image/jpeg', 10, 'retirada', now() - interval '9 days', now());
  r := public.admin_foro_imagenes_purga_resumen();
  assert (r ->> 'pendientes')::int = 2 and (r ->> 'atascadas')::int = 1 and (r ->> 'masAntiguaEn')::timestamptz = (select min(retirada_en) from public.imagenes_oposired where path in ('p/a.jpg', 'p/b.jpg', 'p/c.jpg')), 'pending, stuck, oldest (verified and already purged rows excluded): ' || r::text;

  -- ---- exam question flags
  insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-0000000000f7', 'NC7', 'NC7', 'nc7', true);
  insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('c1000000-0000-0000-0000-0000000000f7', 'NCC', 'Com NC', 'com-nc');
  set local session_replication_role = replica;
  insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, es_oficial)
    values (pg_temp.ex(1), 'NC-1', 'e1000000-0000-0000-0000-0000000000f7', 'c1000000-0000-0000-0000-0000000000f7', 'NC official', 'rafaga', 'gratuito', false, true),
           (pg_temp.ex(2), 'NC-2', 'e1000000-0000-0000-0000-0000000000f7', 'c1000000-0000-0000-0000-0000000000f7', 'NC plain', 'rafaga', 'gratuito', false, false);
  insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) select pg_temp.q(1, p), 'NC-1-' || p, 'rafaga', 'NC pregunta ' || p, true from generate_series(1, 6) p;
  insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen) select pg_temp.ex(1), pg_temp.q(1, p), p, p from generate_series(1, 6) p;
  insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) values (pg_temp.q(2, 1), 'NC-2-1', 'rafaga', 'NC plain 1', true);
  insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen) values (pg_temp.ex(2), pg_temp.q(2, 1), 1, 1);
  set local session_replication_role = origin;
  perform pg_temp.expect_e(format($q$select public.admin_set_exam_question_flags(%L, %L, %L, true, 'x', false, null)$q$, usr, pg_temp.ex(1), pg_temp.q(1, 2)), 'ADMIN_ACTOR_INVALID');
  perform pg_temp.expect_e(format($q$select public.admin_set_exam_question_flags(%L, %L, %L, null, null, false, null)$q$, adm, pg_temp.ex(1), pg_temp.q(1, 2)), 'FLAGS_INPUT_INVALID');
  perform pg_temp.expect_e(format($q$select public.admin_set_exam_question_flags(%L, %L, %L, false, 'because', false, null)$q$, adm, pg_temp.ex(1), pg_temp.q(1, 2)), 'FLAGS_REASON_REQUIRES_ANNULLED');
  perform pg_temp.expect_e(format($q$select public.admin_set_exam_question_flags(%L, gen_random_uuid(), %L, false, null, false, null)$q$, adm, pg_temp.q(1, 2)), 'EXAM_NOT_FOUND');
  perform pg_temp.expect_e(format($q$select public.admin_set_exam_question_flags(%L, %L, gen_random_uuid(), false, null, false, null)$q$, adm, pg_temp.ex(1)), 'EXAM_QUESTION_NOT_FOUND');
  perform pg_temp.expect_e(format($q$select public.admin_set_exam_question_flags(%L, %L, %L, false, null, true, 1)$q$, adm, pg_temp.ex(2), pg_temp.q(2, 1)), 'FLAGS_RESERVE_REQUIRES_OFFICIAL_EXAM');
  perform pg_temp.expect_e(format($q$select public.admin_set_exam_question_flags(%L, %L, %L, false, null, false, 2)$q$, adm, pg_temp.ex(1), pg_temp.q(1, 2)), 'FLAGS_ORDER_INVALID');
  perform pg_temp.expect_e(format($q$select public.admin_set_exam_question_flags(%L, %L, %L, false, null, true, 9)$q$, adm, pg_temp.ex(1), pg_temp.q(1, 6)), 'FLAGS_ORDER_INVALID');
  n0 := pg_temp.audits('pregunta_examen_marcas_modificadas');
  r := public.admin_set_exam_question_flags(adm, pg_temp.ex(1), pg_temp.q(1, 2), true, '  Anulada   por el tribunal ', false, null);
  assert (r ->> 'changed')::boolean and r -> 'problema' = 'null'::jsonb and pg_temp.audits('pregunta_examen_marcas_modificadas') = n0 + 1
     and (select anulada and motivo_anulacion = 'Anulada por el tribunal' from public.preguntas_examen where examen_id = pg_temp.ex(1) and pregunta_id = pg_temp.q(1, 2)), 'annulled with a clean reason, audited';
  assert not (public.admin_set_exam_question_flags(adm, pg_temp.ex(1), pg_temp.q(1, 2), true, 'Anulada por el tribunal', false, null) ->> 'changed')::boolean and pg_temp.audits('pregunta_examen_marcas_modificadas') = n0 + 1, 'no-op: no audit';
  -- reserves: q6 becomes reserve 1 (after the last main), then q5 takes order 1 and q6 shifts to 2
  perform public.admin_set_exam_question_flags(adm, pg_temp.ex(1), pg_temp.q(1, 6), false, null, true, null);
  perform public.admin_set_exam_question_flags(adm, pg_temp.ex(1), pg_temp.q(1, 5), false, null, true, 1);
  assert (select string_agg(posicion || ':' || es_reserva::text || ':' || coalesce(orden_reserva::text, '-'), ',' order by posicion) from public.preguntas_examen where examen_id = pg_temp.ex(1))
         = '1:false:-,2:false:-,3:false:-,4:false:-,5:true:1,6:true:2', 'reserves 1..R after the mains, mains untouched';
  assert (select numero_en_examen from public.preguntas_examen where examen_id = pg_temp.ex(1) and pregunta_id = pg_temp.q(1, 5)) = 5, 'the official question number never changes';
  perform public.admin_set_exam_question_flags(adm, pg_temp.ex(1), pg_temp.q(1, 3), true, 'tribunal', false, null);
  perform pg_temp.expect_e(format($q$select public.admin_set_exam_question_flags(%L, %L, %L, true, 'tribunal', false, null)$q$, adm, pg_temp.ex(1), pg_temp.q(1, 4)), 'FLAGS_OFFICIAL_RULES_INVALID');
  assert not (select anulada from public.preguntas_examen where examen_id = pg_temp.ex(1) and pregunta_id = pg_temp.q(1, 4)), 'more annulled than reserves: refused and rolled back';
  -- an annulled reserve is allowed (warned) as long as the active reserves still cover the annulled mains
  perform public.admin_set_exam_question_flags(adm, pg_temp.ex(1), pg_temp.q(1, 3), false, null, false, null);
  r := public.admin_set_exam_question_flags(adm, pg_temp.ex(1), pg_temp.q(1, 6), true, 'anulada', true, 2);
  assert r -> 'avisos' ? 'ANNULLED_RESERVE_SKIPPED', 'annulled reserve warned';
  -- a demo question is never annulled nor reserve
  update public.preguntas_examen set es_demo = true where examen_id = pg_temp.ex(1) and pregunta_id = pg_temp.q(1, 1);
  perform pg_temp.expect_e(format($q$select public.admin_set_exam_question_flags(%L, %L, %L, true, 'x', false, null)$q$, adm, pg_temp.ex(1), pg_temp.q(1, 1)), 'DEMO_CONFLICT');
  perform pg_temp.expect_e(format($q$select public.admin_set_exam_question_flags(%L, %L, %L, false, null, true, 1)$q$, adm, pg_temp.ex(1), pg_temp.q(1, 1)), 'DEMO_CONFLICT');
  perform public.admin_set_exam_question_flags(adm, pg_temp.ex(1), pg_temp.q(1, 6), false, null, true, 2);
  -- back to a main: goes right after the last main, the remaining reserve is renumbered 1
  perform public.admin_set_exam_question_flags(adm, pg_temp.ex(1), pg_temp.q(1, 5), false, null, false, null);
  assert (select string_agg(posicion || ':' || es_reserva::text || ':' || coalesce(orden_reserva::text, '-'), ',' order by posicion) from public.preguntas_examen where examen_id = pg_temp.ex(1))
         = '1:false:-,2:false:-,3:false:-,4:false:-,5:false:-,6:true:1', 'a reserve turned main sits after the last main; orders compacted';
  assert exists (select 1 from public.auditoria_log where accion = 'pregunta_examen_marcas_modificadas' and registro_id = pg_temp.ex(1) and usuario_id = adm and antes ->> 'pregunta' = pg_temp.q(1, 5)::text
            and antes ->> 'esReserva' = 'true' and (despues ->> 'esReserva') = 'false' and despues ->> 'ordenReserva' is null), 'audit with actor, old and new';

  -- ---- grading rules in one statement, actor and reason in the trigger audit row (DEBT-159)
  n0 := pg_temp.audits('examen_reglas_puntuacion_modificadas');
  perform pg_temp.expect_e(format($q$select public.admin_set_exam_grading_rules(%L, %L, null, 0, 1, true, null)$q$, usr, pg_temp.ex(2)), 'ADMIN_ACTOR_INVALID');
  perform pg_temp.expect_e(format($q$select public.admin_set_exam_grading_rules(%L, %L, 'PUNTUA_ACIERTO', 0, 1, false, null)$q$, adm, pg_temp.ex(2)), 'GRADING_RULE_INVALID');
  perform pg_temp.expect_e(format($q$select public.admin_set_exam_grading_rules(%L, %L, null, 1, 3, true, null)$q$, adm, pg_temp.ex(2)), 'GRADING_PENALTY_CONFLICT');
  perform pg_temp.expect_e(format($q$select public.admin_set_exam_grading_rules(%L, %L, null, 5, 3, false, null)$q$, adm, pg_temp.ex(2)), 'GRADING_PENALTY_INVALID');
  perform pg_temp.expect_e(format($q$select public.admin_set_exam_grading_rules(%L, %L, null, null, 3, false, null)$q$, adm, pg_temp.ex(2)), 'GRADING_INPUT_INVALID');
  perform pg_temp.expect_e(format($q$select public.admin_set_exam_grading_rules(%L, gen_random_uuid(), null, 0, 1, false, null)$q$, adm), 'EXAM_NOT_FOUND');
  assert pg_temp.audits('examen_reglas_puntuacion_modificadas') = n0, 'refused calls wrote nothing';
  r := public.admin_set_exam_grading_rules(adm, pg_temp.ex(2), 'NO_PUNTUA', 1, 3, false, 'fraccion oficial');
  assert (r ->> 'changed')::boolean and r ->> 'reglaEfectiva' = 'NO_PUNTUA' and pg_temp.audits('examen_reglas_puntuacion_modificadas') = n0 + 1, 'changed';
  assert (select usuario_id = adm and despues ->> 'motivo' = 'fraccion oficial' and (despues ->> 'penalizacion_num')::int = 1 and (antes ->> 'penalizacion_num')::int = 0
            from public.auditoria_log where accion = 'examen_reglas_puntuacion_modificadas' and registro_id = pg_temp.ex(2) order by creado_en desc, id limit 1), 'the trigger audit row carries the ACTOR and the reason';
  assert not (public.admin_set_exam_grading_rules(adm, pg_temp.ex(2), 'NO_PUNTUA', 1, 3, false, null) ->> 'changed')::boolean and pg_temp.audits('examen_reglas_puntuacion_modificadas') = n0 + 1, 'same values: no audit';
  assert coalesce(current_setting('app.audit_actor', true), '') = '' and coalesce(current_setting('app.audit_reason', true), '') = '', 'the transaction-local settings are cleared';
  update public.examenes set publicado_en = now() - interval '1 day', activo = true where id = pg_temp.ex(2);
  perform pg_temp.expect_e(format($q$select public.admin_set_exam_grading_rules(%L, %L, null, 0, 1, true, null)$q$, adm, pg_temp.ex(2)), 'REASON_INVALID');
  assert (public.admin_set_exam_grading_rules(adm, pg_temp.ex(2), null, 0, 1, true, 'penalizacion racional') ->> 'changed')::boolean, 'published exam: allowed with a reason';

  -- ---- orders in review
  insert into public.productos_comerciales (id, codigo, tipo_producto, nombre, precio_centimos, moneda, cantidad_tickets, activo, visible)
    values ('b9700000-0000-0000-0000-000000000001', 'nc-pack-100', 'pack_tickets', 'NC 100', 499, 'EUR', 100, true, true);
  select * into o from public.app_create_payment_order(pg_temp.u(5), 'nc-pack-100', 'google_play', 'nc-o1');
  oid := o.orden_id;
  assert (select outcome from public.app_credit_payment_order('google_play', 'nc-ev1', oid, 'nc-tx1', 1, 'EUR', 'nc-pack-100')) = 'EN_REVISION', 'a mismatch puts the order in review';
  perform pg_temp.expect_e(format($q$select public.admin_payment_order_resolve(%L, %L, 'acreditar', 'verificado a mano', false)$q$, usr, oid), 'ADMIN_ACTOR_INVALID');
  perform pg_temp.expect_e(format($q$select public.admin_payment_order_resolve(%L, %L, 'regalar', 'verificado a mano', false)$q$, adm, oid), 'ORDER_DECISION_INVALID');
  perform pg_temp.expect_e(format($q$select public.admin_payment_order_resolve(%L, %L, 'acreditar', 'ok', false)$q$, adm, oid), 'REASON_INVALID');
  perform pg_temp.expect_e(format($q$select public.admin_payment_order_resolve(%L, gen_random_uuid(), 'acreditar', 'verificado a mano', false)$q$, adm), 'ORDER_NOT_FOUND');
  perform pg_temp.expect_e(format($q$select public.admin_payment_order_resolve(%L, %L, 'confirmar', 'verificado a mano', false)$q$, adm, oid), 'ORDER_NOT_CREDITED');
  perform pg_temp.expect_e(format($q$select public.admin_payment_order_resolve(%L, %L, 'reembolsar', 'verificado a mano', true)$q$, adm, oid), 'ORDER_NOT_CREDITED');
  perform pg_temp.expect_e(format($q$update public.compras_usuario set estado = 'pagada' where id = %L$q$, oid), 'ORDER_STATE_TRANSITION_FORBIDDEN');
  n0 := pg_temp.audits('pedido_resuelto');
  r := public.admin_payment_order_resolve(adm, oid, 'acreditar', 'verificado en el panel del proveedor', false);
  assert r ->> 'outcome' = 'CREDITED' and r ->> 'estado' = 'pagada' and pg_temp.audits('pedido_resuelto') = n0 + 1
     and (select saldo_tickets from public.carteras_usuario where usuario_id = pg_temp.u(5)) = 100
     and (select count(*) from public.transacciones_cartera where compra_id = oid and tipo = 'pack_tickets') = 1, 'ledger-first credit of the frozen quantity: ' || r::text;
  assert public.admin_payment_order_resolve(adm, oid, 'acreditar', 'verificado en el panel del proveedor', false) ->> 'outcome' = 'REPLAY' and pg_temp.audits('pedido_resuelto') = n0 + 1
     and (select saldo_tickets from public.carteras_usuario where usuario_id = pg_temp.u(5)) = 100, 'replay: no second credit, no second audit';
  assert (select outcome from public.app_credit_payment_order('google_play', 'nc-ev2', oid, 'nc-tx2', 499, 'EUR', 'nc-pack-100')) = 'REPLAY'
     and (select saldo_tickets from public.carteras_usuario where usuario_id = pg_temp.u(5)) = 100, 'a late webhook for the resolved order cannot credit again';
  perform pg_temp.expect_e(format($q$select public.admin_payment_order_resolve(%L, %L, 'rechazar', 'verificado en el panel', false)$q$, adm, oid), 'ORDER_NOT_IN_REVIEW');
  -- reject
  select * into o from public.app_create_payment_order(pg_temp.u(5), 'nc-pack-100', 'google_play', 'nc-o2');
  oid2 := o.orden_id;
  perform public.app_credit_payment_order('google_play', 'nc-ev3', oid2, 'nc-tx3', 1, 'EUR', 'nc-pack-100');
  r := public.admin_payment_order_resolve(adm, oid2, 'rechazar', 'fraude confirmado', false);
  assert r ->> 'outcome' = 'REJECTED' and r ->> 'estado' = 'fallida' and (select saldo_tickets from public.carteras_usuario where usuario_id = pg_temp.u(5)) = 100, 'rejected: no ledger movement';
  assert public.admin_payment_order_resolve(adm, oid2, 'rechazar', 'fraude confirmado', false) ->> 'outcome' = 'REPLAY', 'reject replay';
  -- a credited order that went to review: confirm, or refund (policy P2 through app_refund_payment_order, only when the switch is on)
  update public.compras_usuario set estado = 'en_revision' where id = oid;
  perform pg_temp.expect_e(format($q$select public.admin_payment_order_resolve(%L, %L, 'acreditar', 'verificado a mano', false)$q$, adm, oid), 'ORDER_ALREADY_CREDITED');
  perform pg_temp.expect_e(format($q$select public.admin_payment_order_resolve(%L, %L, 'rechazar', 'verificado a mano', false)$q$, adm, oid), 'ORDER_ALREADY_CREDITED');
  perform pg_temp.expect_e(format($q$select public.admin_payment_order_resolve(%L, %L, 'reembolsar', 'cliente reclama', false)$q$, adm, oid), 'REFUNDS_DISABLED');
  assert (select estado from public.compras_usuario where id = oid) = 'en_revision', 'a refused refund changed nothing';
  assert public.admin_payment_order_resolve(adm, oid, 'confirmar', 'el pago es correcto', false) ->> 'estado' = 'pagada', 'confirmed: back to paid, no ledger movement';
  update public.compras_usuario set estado = 'en_revision' where id = oid;
  r := public.admin_payment_order_resolve(adm, oid, 'reembolsar', 'cliente reclama', true);
  assert r ->> 'outcome' = 'REFUNDED' and r ->> 'estado' = 'reembolsada' and (select saldo_tickets from public.carteras_usuario where usuario_id = pg_temp.u(5)) = 0, 'refund P2: the unspent part is reversed: ' || r::text;
  assert public.admin_payment_order_resolve(adm, oid, 'reembolsar', 'cliente reclama', true) ->> 'outcome' = 'REPLAY' and (select saldo_tickets from public.carteras_usuario where usuario_id = pg_temp.u(5)) = 0, 'refund replay writes nothing';
  assert (select count(*) from public.auditoria_log where accion = 'pedido_resuelto' and registro_id = oid) = 3 and (select bool_and(usuario_id = adm) from public.auditoria_log where accion = 'pedido_resuelto'), 'every resolution audited with the actor';
  raise notice 'OK NIGHT-C control rpcs';
end $$;
rollback;
