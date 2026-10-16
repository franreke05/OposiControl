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
  assert r.outcome = 'REFUNDED_CASE_OPEN' and r.estado = 'reembolsada', 'refund with spent OT opens a case';
  select * into c from public.refund_case where compra_id = o.orden_id;
  assert c.ot_revertidos = 300 and c.ot_gastados = 700 and c.estado = 'abierto', 'U = 300 reverted, S = 700 case';
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
  assert r.outcome = 'REFUNDED_CASE_OPEN' and r.estado = 'reembolsada' and r.saldo = 0, 'refund: balance 0, never negative';
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
  assert r.outcome = 'REFUNDED_CASE_OPEN' and r.saldo = 30, 'fully spent credit: no reversal, case S = 100, the other purchase is untouched';
  assert (select ot_gastados from public.refund_case where compra_id = o1.orden_id) = 100 and (select ot_revertidos from public.refund_case where compra_id = o1.orden_id) = 0, 'case values';
  select * into r from public.app_refund_payment_order('google_play', 'ev-f3-refund', o2.orden_id);
  assert r.outcome = 'REFUNDED_CASE_OPEN' and r.saldo = 0, 'second purchase: 30 reversed, 20 case';
  assert (select ot_gastados from public.refund_case where compra_id = o2.orden_id) = 20 and (select ot_revertidos from public.refund_case where compra_id = o2.orden_id) = 30, 'second case values';
  assert (select count(*) from public.app_wallet_reconcile(null)) = 0, 'reconcile is clean after credits, debits and refunds';
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
  insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('a0000000-0000-0000-0000-0000000000f1','e5000000-0000-0000-0000-0000000000a8','regalo', now() - interval '1 day', null);
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
insert into public.estado_premium_usuario (usuario_id, es_premium, estado_suscripcion, fin_en) values ('a0000000-0000-0000-0000-0000000000f2', true, 'activa', now() + interval '10 days');
select public.admin_grant_tickets('a0000000-0000-0000-0000-0000000000f2', 300, 'seed', 'seed-2', 'ajuste');
select pg_temp.expect_e($q$select * from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000f2','e5000000-0000-0000-0000-0000000000a1','k-sub')$q$, 'ALREADY_ACCESSIBLE');
insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('a0000000-0000-0000-0000-0000000000f3', 'e5000000-0000-0000-0000-0000000000a1', 'admin', now() - interval '1 day', null);
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
