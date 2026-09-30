-- =============================================================================
-- SLICE 1 / AUDIT LOOP 1 -- DB hardening (MASTER-DB-001, -003, -004, -006, -008)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate). Forward-only fix on top of the already
-- validated Slice 1 files 20260930100000..20260930100300, which are NOT edited. Idempotent
-- (create or replace / if not exists), one transaction. The name 20260930100400 stays reserved for
-- the optional RLS migration listed in SLICE_1_DB_PLAN.md.
--
-- WHAT THIS MIGRATION DOES
--   1. MASTER-DB-001: public.handle_new_auth_user() no longer reads `username` from
--      auth.users.raw_user_meta_data (client-controlled through GoTrue user_metadata). username is
--      server-owned: only the service_role RPCs write it. The INSERT no longer lists username and the
--      ON CONFLICT DO UPDATE no longer sets it, so a metadata username is never copied and a colliding
--      metadata username can no longer raise 23505 inside a signup / metadata update. Everything else
--      (nombre_mostrar, avatar_url, `where estado_cuenta = 'activa'`, SECURITY DEFINER, search_path
--      public, ACL) is unchanged.
--   2. MASTER-DB-003: release gate for the case-insensitive username index. If
--      perfiles_usuario_username_lower_uq is missing it is created when there are no case collisions;
--      when collisions exist the migration RAISES (P0001 SLICE1_USERNAME_LOWER_INDEX_GATE) and nothing
--      is applied: resolve the collisions (VERIFY `username_lower_collisions` of 20260930100000) and
--      re-apply. In addition both username writers take pg_advisory_xact_lock(hashtext('slice1.username'),
--      hashtext(lower(username))) before the availability check, so two concurrent 'Foo'/'foo' claims are
--      serialised even on a database where the index could not exist.
--   3. MASTER-DB-004: documentos_legales on a fresh/branch database kept the Supabase default privileges
--      (INSERT/UPDATE/DELETE/TRUNCATE/REFERENCES/TRIGGER) for anon/authenticated. They are revoked and
--      SELECT is (re)granted; service_role keeps full access. Exactly MAIN's shape (MAIN: anon/authenticated
--      MAINTAIN+SELECT, service_role all), so on MAIN this step is a no-op. MAINTAIN is left untouched on
--      purpose (PG17-only privilege; MAIN has it; not a data-write privilege).
--   4. MASTER-DB-006: new helper private.slice1_trim(text) strips Unicode White_Space (tab, LF, VT, FF, CR,
--      space, NEL, NBSP, U+1680, U+2000-200A, U+2028/2029, U+202F, U+205F, U+3000) and U+FEFF at both ends
--      and returns NULL when nothing is left. private.slice1_text and app_completar_onboarding (nombre,
--      apellidos, referral code) use it instead of btrim (which only strips U+0020), so whitespace-only
--      names are rejected with INVALID_ARGUMENT.
--   5. MASTER-DB-008: app_completar_onboarding identical replay (same username/nombre/apellidos and
--      specialty) no longer returns before the legal-document and attribution code. The profile is still
--      not written (same actualizadoEn, replay:true), but the body's acceptedLegalDocumentIds are
--      validated (plataforma, DOCUMENT_NOT_ACTIVE) and recorded with ON CONFLICT DO NOTHING, and the
--      attribution/referral is validated and inserted write-once (ON CONFLICT DO NOTHING). No fake
--      success: a 200 replay now means every document in the body is accepted. A non-identical body is
--      still ALREADY_ONBOARDED_CONFLICT before anything else is validated or written.
--   app_actualizar_perfil is re-created with the same body plus the advisory lock of step 2 (its nombre/
--   apellidos/detail text go through private.slice1_text, fixed in step 4).
--
-- FORWARD PLAN
--   Apply after 20260930100300 on local/branch; MAIN only at the dedicated apply gate, after the
--   20260930100000 VERIFY `username_lower_collisions` returns 0 (otherwise this migration aborts by design).
--   Signatures, argument semantics and JSON shapes are unchanged (SLICE_1_DB_PLAN.md); Ktor needs no change.
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below re-creates the previous handle_new_auth_user (20260930100000 step 4), slice1_text,
--   app_completar_onboarding and app_actualizar_perfil (20260930100200) verbatim and drops slice1_trim.
--   NOT reverted on purpose: the documentos_legales privilege narrowing (never re-grant write/TRUNCATE to
--   clients) and perfiles_usuario_username_lower_uq (owned by 20260930100000; normally already present).
--   No data is touched by the migration itself.
--
-- COMPATIBILITY WITH OLD KTOR
--   Old Ktor never calls the RPCs and never relies on metadata usernames (legacy /v1/auth/register/profile
--   writes public.usuario through oposibots_upsert_own_profile, untouched). Signup keeps creating the
--   profile row; username simply stays NULL until onboarding.
--
-- LOCKING
--   Function DDL only (catalog locks); REVOKE/GRANT on documentos_legales takes a brief lock on its ACL;
--   the gate may CREATE UNIQUE INDEX (SHARE lock on perfiles_usuario, small) only when the index is missing.
--   lock_timeout 5s: fails fast, retry. Runtime: one extra transaction-scoped advisory lock per username write.
--
-- DATA LOSS
--   None.
--
-- INDEXES
--   None new. perfiles_usuario_username_lower_uq is created here only if 20260930100000 skipped it.
--
-- RLS AND GRANTS
--   No policy change. documentos_legales: revoke INSERT/UPDATE/DELETE/TRUNCATE/REFERENCES/TRIGGER from
--   public/anon/authenticated, grant select to
--   anon/authenticated, grant all to service_role (MAIN shape). private.slice1_trim: EXECUTE revoked from
--   public/anon/authenticated/service_role (called only by the SECURITY DEFINER RPCs, owner postgres).
--   RPC privileges re-asserted: EXECUTE only service_role. create or replace keeps every other ACL.
--
-- ROLLBACK (manual; executed verbatim by dbtest; re-creates the previous definitions verbatim)
-- ROLLBACK-BEGIN
-- begin;
-- create or replace function public.handle_new_auth_user()
-- returns trigger
-- language plpgsql
-- security definer
-- set search_path to 'public'
-- as $function$
-- begin
--   insert into public.perfiles_usuario (
--     usuario_id,
--     nombre_mostrar,
--     username,
--     avatar_url,
--     actualizado_en
--   )
--   values (
--     new.id,
--     coalesce(
--       new.raw_user_meta_data ->> 'nombre',
--       new.raw_user_meta_data ->> 'name',
--       split_part(coalesce(new.email, 'usuario'), '@', 1)
--     ),
--     nullif(new.raw_user_meta_data ->> 'username', ''),
--     nullif(new.raw_user_meta_data ->> 'avatar_url', ''),
--     now()
--   )
--   on conflict (usuario_id) do update
--   set
--     nombre_mostrar = coalesce(public.perfiles_usuario.nombre_mostrar, excluded.nombre_mostrar),
--     username = coalesce(public.perfiles_usuario.username, excluded.username),
--     avatar_url = coalesce(public.perfiles_usuario.avatar_url, excluded.avatar_url),
--     actualizado_en = now()
--   where public.perfiles_usuario.estado_cuenta = 'activa';
--
--   return new;
-- end;
-- $function$;
--
-- create or replace function private.slice1_text(p_json jsonb, p_key text, p_max integer)
-- returns text
-- language plpgsql
-- security invoker
-- set search_path = ''
-- as $$
-- declare
--     v_t text;
-- begin
--     if p_json -> p_key is null or jsonb_typeof(p_json -> p_key) = 'null' then
--         return null;
--     end if;
--     if jsonb_typeof(p_json -> p_key) is distinct from 'string' then
--         raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = p_key;
--     end if;
--     v_t := nullif(btrim(p_json ->> p_key), '');
--     if v_t is not null and char_length(v_t) > p_max then
--         raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = p_key;
--     end if;
--     return v_t;
-- end;
-- $$;
--
-- create or replace function public.app_completar_onboarding(
--     p_usuario_id uuid,
--     p_username text,
--     p_nombre text,
--     p_apellidos text,
--     p_especialidad_id uuid,
--     p_documentos uuid[],
--     p_plataforma text,
--     p_attribution jsonb,
--     p_referral_code text
-- )
-- returns jsonb
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--     v_p public.perfiles_usuario;
--     v_nombre text := btrim(p_nombre);
--     v_apellidos text := btrim(p_apellidos);
--     v_docs uuid[];
--     v_attr jsonb := coalesce(p_attribution, '{}'::jsonb);
--     v_ref text := nullif(btrim(p_referral_code), '');
--     v_fuente text;
--     v_canal text;
--     v_campania text;
--     v_plat text;
--     v_install text;
--     v_n integer;
-- begin
--     v_p := private.slice1_lock_profile(p_usuario_id);
--
--     if p_username is null or p_username !~ '^[A-Za-z0-9._-]{3,30}$' then
--         raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'username';
--     end if;
--     if v_nombre is null or char_length(v_nombre) not between 1 and 100 then
--         raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'nombre';
--     end if;
--     if v_apellidos is null or char_length(v_apellidos) not between 1 and 100 then
--         raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'apellidos';
--     end if;
--
--     -- Idempotent on usuario_id: identical replay = no-op, different identity = conflict.
--     if v_p.onboarding_completado then
--         if v_p.username = p_username and v_p.nombre = v_nombre and v_p.apellidos = v_apellidos
--            and (p_especialidad_id is null or v_p.especialidad_principal_id = p_especialidad_id) then
--             return private.slice1_profile_json(p_usuario_id) || jsonb_build_object('replay', true);
--         end if;
--         raise exception using errcode = 'OC409', message = 'ALREADY_ONBOARDED_CONFLICT';
--     end if;
--
--     if jsonb_typeof(v_attr) is distinct from 'object' then
--         raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'attribution';
--     end if;
--     v_fuente := private.slice1_text(v_attr, 'fuente', 500);
--     v_canal := private.slice1_text(v_attr, 'canal', 500);
--     v_campania := private.slice1_text(v_attr, 'campania', 500);
--     v_plat := private.slice1_text(v_attr, 'plataforma', 20);
--     v_install := private.slice1_text(v_attr, 'installReferrer', 500);
--     if v_plat is not null and v_plat not in ('android', 'ios', 'web') then
--         raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'attribution.plataforma';
--     end if;
--     if v_ref is not null and char_length(v_ref) > 64 then
--         raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'referralCode';
--     end if;
--
--     if exists (select 1 from public.perfiles_usuario x
--                where lower(x.username) = lower(p_username) and x.usuario_id <> p_usuario_id) then
--         raise exception using errcode = '23505', message = 'USERNAME_TAKEN';
--     end if;
--
--     if p_especialidad_id is not null
--        and not exists (select 1 from public.especialidades e where e.id = p_especialidad_id and e.activa) then
--         raise exception using errcode = '22023', message = 'UNKNOWN_SPECIALTY';
--     end if;
--
--     select coalesce(array_agg(distinct d), '{}'::uuid[]) into v_docs from unnest(p_documentos) d where d is not null;
--     if cardinality(v_docs) > 20 then
--         raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'documentos';
--     end if;
--     if cardinality(v_docs) > 0 then
--         if p_plataforma is null or p_plataforma not in ('android', 'ios') then
--             raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'plataforma';
--         end if;
--         select count(*) into v_n from public.documentos_legales d where d.id = any (v_docs) and d.activo;
--         if v_n <> cardinality(v_docs) then
--             raise exception using errcode = '22023', message = 'DOCUMENT_NOT_ACTIVE';
--         end if;
--         insert into public.aceptaciones_legales (usuario_id, documento_id, tipo_documento, version, plataforma)
--         select p_usuario_id, d.id, d.tipo_documento, d.version, p_plataforma
--         from public.documentos_legales d
--         where d.id = any (v_docs)
--         on conflict (usuario_id, documento_id) do nothing;
--     end if;
--
--     begin
--         update public.perfiles_usuario
--         set username = p_username,
--             nombre = v_nombre,
--             apellidos = v_apellidos,
--             especialidad_principal_id = coalesce(p_especialidad_id, especialidad_principal_id),
--             onboarding_completado = true
--         where usuario_id = p_usuario_id;
--     exception when unique_violation then
--         raise exception using errcode = '23505', message = 'USERNAME_TAKEN';
--     end;
--
--     if v_fuente is not null or v_canal is not null or v_campania is not null or v_plat is not null
--        or v_install is not null or v_ref is not null then
--         insert into public.atribucion_usuario (usuario_id, fuente, canal, campania, plataforma, install_referrer, codigo_referido)
--         values (p_usuario_id, v_fuente, v_canal, v_campania, v_plat, v_install, v_ref)
--         on conflict (usuario_id) do nothing;
--     end if;
--
--     return private.slice1_profile_json(p_usuario_id);
-- end;
-- $$;
--
-- create or replace function public.app_actualizar_perfil(
--     p_usuario_id uuid,
--     p_patch jsonb,
--     p_if_match timestamptz
-- )
-- returns jsonb
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--     v_p public.perfiles_usuario;
--     v_allowed constant text[] := array['username', 'nombre', 'apellidos', 'especialidadId',
--                                         'comunidadPrincipalId', 'detail', 'targetCommunityIds', 'introSeen'];
--     v_detail_allowed constant text[] := array['edad', 'ciudad', 'estadoLaboral', 'centroTrabajo',
--                                                'tiempoEstudioMin', 'franja', 'fechaExamenObjetivo'];
--     k text;
--     v_username text;
--     v_nombre text;
--     v_apellidos text;
--     v_esp uuid;
--     v_com uuid;
--     v_intro timestamptz;
--     v_touch boolean := false;
--     v_detail jsonb;
--     v_d public.perfiles_usuario_detalle;
--     v_edad integer;
--     v_ciudad text;
--     v_laboral text;
--     v_centro text;
--     v_tiempo integer;
--     v_franja text;
--     v_fecha date;
--     v_raw uuid[];
--     v_targets uuid[];
--     v_current uuid[];
--     v_i integer;
-- begin
--     v_p := private.slice1_lock_profile(p_usuario_id);
--
--     if p_patch is null or jsonb_typeof(p_patch) is distinct from 'object' then
--         raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'patch';
--     end if;
--     for k in select jsonb_object_keys(p_patch) loop
--         if not (k = any (v_allowed)) then
--             raise exception using errcode = '22023', message = 'UNKNOWN_FIELD', hint = k;
--         end if;
--     end loop;
--
--     if p_if_match is not null and p_if_match is distinct from v_p.actualizado_en then
--         raise exception using errcode = 'OC412', message = 'PRECONDITION_FAILED';
--     end if;
--
--     v_username := v_p.username;
--     v_nombre := v_p.nombre;
--     v_apellidos := v_p.apellidos;
--     v_esp := v_p.especialidad_principal_id;
--     v_com := v_p.comunidad_principal_id;
--     v_intro := v_p.perfil_intro_visto_en;
--
--     if p_patch ? 'username' then
--         if jsonb_typeof(p_patch -> 'username') is distinct from 'string'
--            or (p_patch ->> 'username') !~ '^[A-Za-z0-9._-]{3,30}$' then
--             raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'username';
--         end if;
--         v_username := p_patch ->> 'username';
--         if exists (select 1 from public.perfiles_usuario x
--                    where lower(x.username) = lower(v_username) and x.usuario_id <> p_usuario_id) then
--             raise exception using errcode = '23505', message = 'USERNAME_TAKEN';
--         end if;
--     end if;
--     if p_patch ? 'nombre' then
--         v_nombre := private.slice1_text(p_patch, 'nombre', 100);
--         if v_nombre is null then
--             raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'nombre';
--         end if;
--     end if;
--     if p_patch ? 'apellidos' then
--         v_apellidos := private.slice1_text(p_patch, 'apellidos', 100);
--         if v_apellidos is null then
--             raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'apellidos';
--         end if;
--     end if;
--     if p_patch ? 'especialidadId' then
--         if jsonb_typeof(p_patch -> 'especialidadId') is distinct from 'string' then
--             raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'especialidadId';
--         end if;
--         v_esp := private.slice1_uuid(p_patch ->> 'especialidadId', 'especialidadId');
--         if not exists (select 1 from public.especialidades e where e.id = v_esp and e.activa) then
--             raise exception using errcode = '22023', message = 'UNKNOWN_SPECIALTY';
--         end if;
--     end if;
--     if p_patch ? 'comunidadPrincipalId' then
--         if jsonb_typeof(p_patch -> 'comunidadPrincipalId') is distinct from 'string' then
--             raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'comunidadPrincipalId';
--         end if;
--         v_com := private.slice1_uuid(p_patch ->> 'comunidadPrincipalId', 'comunidadPrincipalId');
--         if not exists (select 1 from public.comunidades_autonomas c where c.id = v_com) then
--             raise exception using errcode = '22023', message = 'UNKNOWN_COMMUNITY';
--         end if;
--     end if;
--     if p_patch ? 'introSeen' then
--         if jsonb_typeof(p_patch -> 'introSeen') is distinct from 'boolean' then
--             raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'introSeen';
--         end if;
--         if (p_patch ->> 'introSeen')::boolean and v_intro is null then
--             v_intro := now();
--         end if;
--     end if;
--
--     -- detail (perfiles_usuario_detalle): only present keys change; JSON null clears the field.
--     if p_patch ? 'detail' then
--         v_detail := p_patch -> 'detail';
--         if jsonb_typeof(v_detail) is distinct from 'object' then
--             raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'detail';
--         end if;
--         for k in select jsonb_object_keys(v_detail) loop
--             if not (k = any (v_detail_allowed)) then
--                 raise exception using errcode = '22023', message = 'UNKNOWN_FIELD', hint = 'detail.' || k;
--             end if;
--         end loop;
--         select * into v_d from public.perfiles_usuario_detalle d where d.usuario_id = p_usuario_id for update;
--         v_edad := v_d.edad;
--         v_ciudad := v_d.ciudad;
--         v_laboral := v_d.estado_laboral;
--         v_centro := v_d.centro_trabajo;
--         v_tiempo := v_d.tiempo_disponible_estudio_minutos;
--         v_franja := v_d.franja_preferente_estudio;
--         v_fecha := v_d.fecha_examen_objetivo;
--         if v_detail ? 'edad' then v_edad := private.slice1_int(v_detail, 'edad', 14, 120); end if;
--         if v_detail ? 'ciudad' then v_ciudad := private.slice1_text(v_detail, 'ciudad', 100); end if;
--         if v_detail ? 'estadoLaboral' then v_laboral := private.slice1_text(v_detail, 'estadoLaboral', 100); end if;
--         if v_detail ? 'centroTrabajo' then v_centro := private.slice1_text(v_detail, 'centroTrabajo', 100); end if;
--         if v_detail ? 'tiempoEstudioMin' then v_tiempo := private.slice1_int(v_detail, 'tiempoEstudioMin', 0, 1440); end if;
--         if v_detail ? 'franja' then v_franja := private.slice1_text(v_detail, 'franja', 100); end if;
--         if v_detail ? 'fechaExamenObjetivo' then
--             if jsonb_typeof(v_detail -> 'fechaExamenObjetivo') = 'null' then
--                 v_fecha := null;
--             elsif jsonb_typeof(v_detail -> 'fechaExamenObjetivo') = 'string'
--                   and (v_detail ->> 'fechaExamenObjetivo') ~ '^\d{4}-\d{2}-\d{2}$' then
--                 begin
--                     v_fecha := (v_detail ->> 'fechaExamenObjetivo')::date;
--                 exception when others then
--                     raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'fechaExamenObjetivo';
--                 end;
--             else
--                 raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'fechaExamenObjetivo';
--             end if;
--         end if;
--
--         if v_d.usuario_id is null then
--             if (v_edad, v_ciudad, v_laboral, v_centro, v_tiempo, v_franja, v_fecha) is distinct from
--                (null::integer, null::text, null::text, null::text, null::integer, null::text, null::date) then
--                 insert into public.perfiles_usuario_detalle (usuario_id, edad, ciudad, estado_laboral, centro_trabajo,
--                     tiempo_disponible_estudio_minutos, franja_preferente_estudio, fecha_examen_objetivo, actualizado_en)
--                 values (p_usuario_id, v_edad, v_ciudad, v_laboral, v_centro, v_tiempo, v_franja, v_fecha, now());
--                 v_touch := true;
--             end if;
--         elsif (v_edad, v_ciudad, v_laboral, v_centro, v_tiempo, v_franja, v_fecha) is distinct from
--               (v_d.edad, v_d.ciudad, v_d.estado_laboral, v_d.centro_trabajo,
--                v_d.tiempo_disponible_estudio_minutos, v_d.franja_preferente_estudio, v_d.fecha_examen_objetivo) then
--             update public.perfiles_usuario_detalle
--             set edad = v_edad, ciudad = v_ciudad, estado_laboral = v_laboral, centro_trabajo = v_centro,
--                 tiempo_disponible_estudio_minutos = v_tiempo, franja_preferente_estudio = v_franja,
--                 fecha_examen_objetivo = v_fecha, actualizado_en = now()
--             where usuario_id = p_usuario_id;
--             v_touch := true;
--         end if;
--     end if;
--
--     -- target communities: the array REPLACES the set; prioridad = position (1..n).
--     if p_patch ? 'targetCommunityIds' then
--         if jsonb_typeof(p_patch -> 'targetCommunityIds') is distinct from 'array'
--            or jsonb_array_length(p_patch -> 'targetCommunityIds') > 20 then
--             raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'targetCommunityIds';
--         end if;
--         v_raw := '{}'::uuid[];
--         for v_i in 0 .. jsonb_array_length(p_patch -> 'targetCommunityIds') - 1 loop
--             if jsonb_typeof(p_patch -> 'targetCommunityIds' -> v_i) is distinct from 'string' then
--                 raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'targetCommunityIds';
--             end if;
--             v_raw := v_raw || private.slice1_uuid(p_patch -> 'targetCommunityIds' ->> v_i, 'targetCommunityIds');
--         end loop;
--         select coalesce(array_agg(s.id order by s.first_ord), '{}'::uuid[]) into v_targets
--         from (select t.id, min(t.ord) as first_ord from unnest(v_raw) with ordinality as t(id, ord) group by t.id) s;
--         if (select count(*) from public.comunidades_autonomas c where c.id = any (v_targets)) <> cardinality(v_targets) then
--             raise exception using errcode = '22023', message = 'UNKNOWN_COMMUNITY';
--         end if;
--         select coalesce(array_agg(o.comunidad_id order by o.prioridad, o.comunidad_id), '{}'::uuid[]) into v_current
--         from public.comunidades_objetivo_usuario o where o.usuario_id = p_usuario_id;
--         if v_current is distinct from v_targets then
--             delete from public.comunidades_objetivo_usuario where usuario_id = p_usuario_id;
--             insert into public.comunidades_objetivo_usuario (usuario_id, comunidad_id, prioridad)
--             select p_usuario_id, t.id, t.ord::integer from unnest(v_targets) with ordinality as t(id, ord);
--             v_touch := true;
--         end if;
--     end if;
--
--     if v_touch
--        or (v_username, v_nombre, v_apellidos, v_esp, v_com, v_intro) is distinct from
--           (v_p.username, v_p.nombre, v_p.apellidos, v_p.especialidad_principal_id,
--            v_p.comunidad_principal_id, v_p.perfil_intro_visto_en) then
--         begin
--             update public.perfiles_usuario
--             set username = v_username, nombre = v_nombre, apellidos = v_apellidos,
--                 especialidad_principal_id = v_esp, comunidad_principal_id = v_com,
--                 perfil_intro_visto_en = v_intro
--             where usuario_id = p_usuario_id;
--         exception when unique_violation then
--             raise exception using errcode = '23505', message = 'USERNAME_TAKEN';
--         end;
--     end if;
--
--     return private.slice1_profile_json(p_usuario_id);
-- end;
-- $$;
--
-- drop function if exists private.slice1_trim(text);
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read-only SQL for an operator running as the migration role; every check must
-- report failures = 0. Executed verbatim by dbtest.)
-- VERIFY-BEGIN
-- select 'trim_helper_missing' as check_name,
--        (case when to_regprocedure('private.slice1_trim(text)') is not null then 0 else 1 end)::bigint as failures,
--        null::text as info
-- union all
-- select 'handle_new_auth_user_reads_metadata_username',
--        (case when exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
--                            where n.nspname = 'public' and p.proname = 'handle_new_auth_user'
--                              and p.prosrc like '%''username''%') then 1 else 0 end)::bigint, null
-- union all
-- select 'handle_new_auth_user_unguarded',
--        (case when exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
--                            where n.nspname = 'public' and p.proname = 'handle_new_auth_user'
--                              and p.prosrc like '%estado_cuenta = ''activa''%') then 0 else 1 end)::bigint, null
-- union all
-- select 'username_lower_collisions', count(*), null
-- from (select lower(username) from public.perfiles_usuario where username is not null
--       group by 1 having count(*) > 1) c
-- union all
-- select 'username_lower_index_missing',
--        (case when exists (select 1 from pg_indexes where schemaname = 'public'
--                             and indexname = 'perfiles_usuario_username_lower_uq') then 0 else 1 end)::bigint, null
-- union all
-- select 'rpc_username_lock_missing', (2 - count(*))::bigint, null
-- from pg_proc p join pg_namespace n on n.oid = p.pronamespace
-- where n.nspname = 'public' and p.proname in ('app_completar_onboarding', 'app_actualizar_perfil')
--   and p.prosrc like '%pg_advisory_xact_lock(hashtext(''slice1.username'')%'
-- union all
-- select 'ascii_only_trim_left', count(*), null
-- from pg_proc p join pg_namespace n on n.oid = p.pronamespace
-- where (n.nspname = 'public' and p.proname in ('app_completar_onboarding', 'app_actualizar_perfil')
--        or n.nspname = 'private' and p.proname = 'slice1_text')
--   and p.prosrc like '%btrim(%'
-- union all
-- select 'onboarding_replay_skips_documents',
--        (case when exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
--                            where n.nspname = 'public' and p.proname = 'app_completar_onboarding'
--                              and p.prosrc like '%v_replay := true%') then 0 else 1 end)::bigint, null
-- union all
-- select 'documentos_legales_client_write_privileges', count(*), null
-- from (values ('anon'), ('authenticated'), ('public')) r(rol)
-- cross join (values ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE'), ('REFERENCES'), ('TRIGGER')) x(priv)
-- where has_table_privilege(r.rol, 'public.documentos_legales', x.priv)
-- union all
-- select 'documentos_legales_client_select_missing', count(*), null
-- from (values ('anon'), ('authenticated')) r(rol)
-- where not has_table_privilege(r.rol, 'public.documentos_legales', 'SELECT')
-- union all
-- select 'rpc_execute_outside_service_role', count(*), null
-- from pg_proc p join pg_namespace n on n.oid = p.pronamespace
-- cross join (values ('anon'), ('authenticated'), ('public')) r(rol)
-- where n.nspname = 'public' and p.proname in ('app_completar_onboarding', 'app_actualizar_perfil')
--   and has_function_privilege(r.rol, p.oid, 'execute');
-- VERIFY-END
-- =============================================================================

begin;

set local lock_timeout = '5s';

create schema if not exists private;

-- 4. Unicode-aware trim (MASTER-DB-006) ------------------------------------------------------
create or replace function private.slice1_trim(p_text text)
returns text
language sql
immutable
security invoker
set search_path = ''
as $$
    select nullif(
        regexp_replace(
            p_text,
            '^[\u0009-\u000d\u0020\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]+|[\u0009-\u000d\u0020\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]+$',
            '',
            'g'
        ),
        ''
    )
$$;
revoke all on function private.slice1_trim(text) from public, anon, authenticated, service_role;

-- Text member of a JSON object: JSON null / blank (any Unicode whitespace) -> NULL; not a string or too long -> INVALID_ARGUMENT.
create or replace function private.slice1_text(p_json jsonb, p_key text, p_max integer)
returns text
language plpgsql
security invoker
set search_path = ''
as $$
declare
    v_t text;
begin
    if p_json -> p_key is null or jsonb_typeof(p_json -> p_key) = 'null' then
        return null;
    end if;
    if jsonb_typeof(p_json -> p_key) is distinct from 'string' then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = p_key;
    end if;
    v_t := private.slice1_trim(p_json ->> p_key);
    if v_t is not null and char_length(v_t) > p_max then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = p_key;
    end if;
    return v_t;
end;
$$;
revoke all on function private.slice1_text(jsonb, text, integer) from public, anon, authenticated, service_role;

-- 1. handle_new_auth_user: username is server-owned, never copied from user metadata (MASTER-DB-001) ---
create or replace function public.handle_new_auth_user()
returns trigger
language plpgsql
security definer
set search_path to 'public'
as $function$
begin
  insert into public.perfiles_usuario (
    usuario_id,
    nombre_mostrar,
    avatar_url,
    actualizado_en
  )
  values (
    new.id,
    coalesce(
      new.raw_user_meta_data ->> 'nombre',
      new.raw_user_meta_data ->> 'name',
      split_part(coalesce(new.email, 'usuario'), '@', 1)
    ),
    nullif(new.raw_user_meta_data ->> 'avatar_url', ''),
    now()
  )
  on conflict (usuario_id) do update
  set
    nombre_mostrar = coalesce(public.perfiles_usuario.nombre_mostrar, excluded.nombre_mostrar),
    avatar_url = coalesce(public.perfiles_usuario.avatar_url, excluded.avatar_url),
    actualizado_en = now()
  where public.perfiles_usuario.estado_cuenta = 'activa';

  return new;
end;
$function$;

-- 2. Release gate: the case-insensitive username index must exist (MASTER-DB-003) ---------------
do $gate$
declare
    v_groups integer;
begin
    if exists (select 1 from pg_indexes where schemaname = 'public' and indexname = 'perfiles_usuario_username_lower_uq') then
        return;
    end if;
    select count(*) into v_groups
    from (select lower(username) from public.perfiles_usuario
          where username is not null group by 1 having count(*) > 1) c;
    if v_groups > 0 then
        raise exception using errcode = 'P0001', message = 'SLICE1_USERNAME_LOWER_INDEX_GATE',
            detail = v_groups || ' case-insensitive username collision group(s) in perfiles_usuario',
            hint = 'Resolve the collisions (VERIFY username_lower_collisions of 20260930100000) and re-apply.';
    end if;
    create unique index if not exists perfiles_usuario_username_lower_uq
        on public.perfiles_usuario (lower(username)) where username is not null;
end
$gate$;

-- 5. app_completar_onboarding: replay applies documents + write-once attribution (MASTER-DB-008),
--    Unicode trim (MASTER-DB-006), username advisory lock (MASTER-DB-003) ----------------------------
create or replace function public.app_completar_onboarding(
    p_usuario_id uuid,
    p_username text,
    p_nombre text,
    p_apellidos text,
    p_especialidad_id uuid,
    p_documentos uuid[],
    p_plataforma text,
    p_attribution jsonb,
    p_referral_code text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_p public.perfiles_usuario;
    v_nombre text := private.slice1_trim(p_nombre);
    v_apellidos text := private.slice1_trim(p_apellidos);
    v_docs uuid[];
    v_attr jsonb := coalesce(p_attribution, '{}'::jsonb);
    v_ref text := private.slice1_trim(p_referral_code);
    v_fuente text;
    v_canal text;
    v_campania text;
    v_plat text;
    v_install text;
    v_n integer;
    v_replay boolean := false;
begin
    v_p := private.slice1_lock_profile(p_usuario_id);

    if p_username is null or p_username !~ '^[A-Za-z0-9._-]{3,30}$' then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'username';
    end if;
    if v_nombre is null or char_length(v_nombre) not between 1 and 100 then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'nombre';
    end if;
    if v_apellidos is null or char_length(v_apellidos) not between 1 and 100 then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'apellidos';
    end if;

    -- Idempotent on usuario_id: identical replay = profile untouched but the body's legal documents and
    -- write-once attribution are still applied; different identity = conflict (nothing validated or written).
    if v_p.onboarding_completado then
        if coalesce(v_p.username = p_username and v_p.nombre = v_nombre and v_p.apellidos = v_apellidos
                    and (p_especialidad_id is null or v_p.especialidad_principal_id = p_especialidad_id), false) then
            v_replay := true;
        else
            raise exception using errcode = 'OC409', message = 'ALREADY_ONBOARDED_CONFLICT';
        end if;
    end if;

    if jsonb_typeof(v_attr) is distinct from 'object' then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'attribution';
    end if;
    v_fuente := private.slice1_text(v_attr, 'fuente', 500);
    v_canal := private.slice1_text(v_attr, 'canal', 500);
    v_campania := private.slice1_text(v_attr, 'campania', 500);
    v_plat := private.slice1_text(v_attr, 'plataforma', 20);
    v_install := private.slice1_text(v_attr, 'installReferrer', 500);
    if v_plat is not null and v_plat not in ('android', 'ios', 'web') then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'attribution.plataforma';
    end if;
    if v_ref is not null and char_length(v_ref) > 64 then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'referralCode';
    end if;

    if not v_replay then
        -- Serialise concurrent claims of the same case-insensitive username (the unique index stays the backstop).
        perform pg_advisory_xact_lock(hashtext('slice1.username'), hashtext(lower(p_username)));
        if exists (select 1 from public.perfiles_usuario x
                   where lower(x.username) = lower(p_username) and x.usuario_id <> p_usuario_id) then
            raise exception using errcode = '23505', message = 'USERNAME_TAKEN';
        end if;

        if p_especialidad_id is not null
           and not exists (select 1 from public.especialidades e where e.id = p_especialidad_id and e.activa) then
            raise exception using errcode = '22023', message = 'UNKNOWN_SPECIALTY';
        end if;
    end if;

    select coalesce(array_agg(distinct d), '{}'::uuid[]) into v_docs from unnest(p_documentos) d where d is not null;
    if cardinality(v_docs) > 20 then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'documentos';
    end if;
    if cardinality(v_docs) > 0 then
        if p_plataforma is null or p_plataforma not in ('android', 'ios') then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'plataforma';
        end if;
        select count(*) into v_n from public.documentos_legales d where d.id = any (v_docs) and d.activo;
        if v_n <> cardinality(v_docs) then
            raise exception using errcode = '22023', message = 'DOCUMENT_NOT_ACTIVE';
        end if;
        insert into public.aceptaciones_legales (usuario_id, documento_id, tipo_documento, version, plataforma)
        select p_usuario_id, d.id, d.tipo_documento, d.version, p_plataforma
        from public.documentos_legales d
        where d.id = any (v_docs)
        on conflict (usuario_id, documento_id) do nothing;
    end if;

    if not v_replay then
        begin
            update public.perfiles_usuario
            set username = p_username,
                nombre = v_nombre,
                apellidos = v_apellidos,
                especialidad_principal_id = coalesce(p_especialidad_id, especialidad_principal_id),
                onboarding_completado = true
            where usuario_id = p_usuario_id;
        exception when unique_violation then
            raise exception using errcode = '23505', message = 'USERNAME_TAKEN';
        end;
    end if;

    if v_fuente is not null or v_canal is not null or v_campania is not null or v_plat is not null
       or v_install is not null or v_ref is not null then
        insert into public.atribucion_usuario (usuario_id, fuente, canal, campania, plataforma, install_referrer, codigo_referido)
        values (p_usuario_id, v_fuente, v_canal, v_campania, v_plat, v_install, v_ref)
        on conflict (usuario_id) do nothing;
    end if;

    if v_replay then
        return private.slice1_profile_json(p_usuario_id) || jsonb_build_object('replay', true);
    end if;
    return private.slice1_profile_json(p_usuario_id);
end;
$$;

-- app_actualizar_perfil: same body as 20260930100200 + username advisory lock (MASTER-DB-003) ----------
create or replace function public.app_actualizar_perfil(
    p_usuario_id uuid,
    p_patch jsonb,
    p_if_match timestamptz
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_p public.perfiles_usuario;
    v_allowed constant text[] := array['username', 'nombre', 'apellidos', 'especialidadId',
                                        'comunidadPrincipalId', 'detail', 'targetCommunityIds', 'introSeen'];
    v_detail_allowed constant text[] := array['edad', 'ciudad', 'estadoLaboral', 'centroTrabajo',
                                               'tiempoEstudioMin', 'franja', 'fechaExamenObjetivo'];
    k text;
    v_username text;
    v_nombre text;
    v_apellidos text;
    v_esp uuid;
    v_com uuid;
    v_intro timestamptz;
    v_touch boolean := false;
    v_detail jsonb;
    v_d public.perfiles_usuario_detalle;
    v_edad integer;
    v_ciudad text;
    v_laboral text;
    v_centro text;
    v_tiempo integer;
    v_franja text;
    v_fecha date;
    v_raw uuid[];
    v_targets uuid[];
    v_current uuid[];
    v_i integer;
begin
    v_p := private.slice1_lock_profile(p_usuario_id);

    if p_patch is null or jsonb_typeof(p_patch) is distinct from 'object' then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'patch';
    end if;
    for k in select jsonb_object_keys(p_patch) loop
        if not (k = any (v_allowed)) then
            raise exception using errcode = '22023', message = 'UNKNOWN_FIELD', hint = k;
        end if;
    end loop;

    if p_if_match is not null and p_if_match is distinct from v_p.actualizado_en then
        raise exception using errcode = 'OC412', message = 'PRECONDITION_FAILED';
    end if;

    v_username := v_p.username;
    v_nombre := v_p.nombre;
    v_apellidos := v_p.apellidos;
    v_esp := v_p.especialidad_principal_id;
    v_com := v_p.comunidad_principal_id;
    v_intro := v_p.perfil_intro_visto_en;

    if p_patch ? 'username' then
        if jsonb_typeof(p_patch -> 'username') is distinct from 'string'
           or (p_patch ->> 'username') !~ '^[A-Za-z0-9._-]{3,30}$' then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'username';
        end if;
        v_username := p_patch ->> 'username';
        perform pg_advisory_xact_lock(hashtext('slice1.username'), hashtext(lower(v_username)));
        if exists (select 1 from public.perfiles_usuario x
                   where lower(x.username) = lower(v_username) and x.usuario_id <> p_usuario_id) then
            raise exception using errcode = '23505', message = 'USERNAME_TAKEN';
        end if;
    end if;
    if p_patch ? 'nombre' then
        v_nombre := private.slice1_text(p_patch, 'nombre', 100);
        if v_nombre is null then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'nombre';
        end if;
    end if;
    if p_patch ? 'apellidos' then
        v_apellidos := private.slice1_text(p_patch, 'apellidos', 100);
        if v_apellidos is null then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'apellidos';
        end if;
    end if;
    if p_patch ? 'especialidadId' then
        if jsonb_typeof(p_patch -> 'especialidadId') is distinct from 'string' then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'especialidadId';
        end if;
        v_esp := private.slice1_uuid(p_patch ->> 'especialidadId', 'especialidadId');
        if not exists (select 1 from public.especialidades e where e.id = v_esp and e.activa) then
            raise exception using errcode = '22023', message = 'UNKNOWN_SPECIALTY';
        end if;
    end if;
    if p_patch ? 'comunidadPrincipalId' then
        if jsonb_typeof(p_patch -> 'comunidadPrincipalId') is distinct from 'string' then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'comunidadPrincipalId';
        end if;
        v_com := private.slice1_uuid(p_patch ->> 'comunidadPrincipalId', 'comunidadPrincipalId');
        if not exists (select 1 from public.comunidades_autonomas c where c.id = v_com) then
            raise exception using errcode = '22023', message = 'UNKNOWN_COMMUNITY';
        end if;
    end if;
    if p_patch ? 'introSeen' then
        if jsonb_typeof(p_patch -> 'introSeen') is distinct from 'boolean' then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'introSeen';
        end if;
        if (p_patch ->> 'introSeen')::boolean and v_intro is null then
            v_intro := now();
        end if;
    end if;

    -- detail (perfiles_usuario_detalle): only present keys change; JSON null clears the field.
    if p_patch ? 'detail' then
        v_detail := p_patch -> 'detail';
        if jsonb_typeof(v_detail) is distinct from 'object' then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'detail';
        end if;
        for k in select jsonb_object_keys(v_detail) loop
            if not (k = any (v_detail_allowed)) then
                raise exception using errcode = '22023', message = 'UNKNOWN_FIELD', hint = 'detail.' || k;
            end if;
        end loop;
        select * into v_d from public.perfiles_usuario_detalle d where d.usuario_id = p_usuario_id for update;
        v_edad := v_d.edad;
        v_ciudad := v_d.ciudad;
        v_laboral := v_d.estado_laboral;
        v_centro := v_d.centro_trabajo;
        v_tiempo := v_d.tiempo_disponible_estudio_minutos;
        v_franja := v_d.franja_preferente_estudio;
        v_fecha := v_d.fecha_examen_objetivo;
        if v_detail ? 'edad' then v_edad := private.slice1_int(v_detail, 'edad', 14, 120); end if;
        if v_detail ? 'ciudad' then v_ciudad := private.slice1_text(v_detail, 'ciudad', 100); end if;
        if v_detail ? 'estadoLaboral' then v_laboral := private.slice1_text(v_detail, 'estadoLaboral', 100); end if;
        if v_detail ? 'centroTrabajo' then v_centro := private.slice1_text(v_detail, 'centroTrabajo', 100); end if;
        if v_detail ? 'tiempoEstudioMin' then v_tiempo := private.slice1_int(v_detail, 'tiempoEstudioMin', 0, 1440); end if;
        if v_detail ? 'franja' then v_franja := private.slice1_text(v_detail, 'franja', 100); end if;
        if v_detail ? 'fechaExamenObjetivo' then
            if jsonb_typeof(v_detail -> 'fechaExamenObjetivo') = 'null' then
                v_fecha := null;
            elsif jsonb_typeof(v_detail -> 'fechaExamenObjetivo') = 'string'
                  and (v_detail ->> 'fechaExamenObjetivo') ~ '^\d{4}-\d{2}-\d{2}$' then
                begin
                    v_fecha := (v_detail ->> 'fechaExamenObjetivo')::date;
                exception when others then
                    raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'fechaExamenObjetivo';
                end;
            else
                raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'fechaExamenObjetivo';
            end if;
        end if;

        if v_d.usuario_id is null then
            if (v_edad, v_ciudad, v_laboral, v_centro, v_tiempo, v_franja, v_fecha) is distinct from
               (null::integer, null::text, null::text, null::text, null::integer, null::text, null::date) then
                insert into public.perfiles_usuario_detalle (usuario_id, edad, ciudad, estado_laboral, centro_trabajo,
                    tiempo_disponible_estudio_minutos, franja_preferente_estudio, fecha_examen_objetivo, actualizado_en)
                values (p_usuario_id, v_edad, v_ciudad, v_laboral, v_centro, v_tiempo, v_franja, v_fecha, now());
                v_touch := true;
            end if;
        elsif (v_edad, v_ciudad, v_laboral, v_centro, v_tiempo, v_franja, v_fecha) is distinct from
              (v_d.edad, v_d.ciudad, v_d.estado_laboral, v_d.centro_trabajo,
               v_d.tiempo_disponible_estudio_minutos, v_d.franja_preferente_estudio, v_d.fecha_examen_objetivo) then
            update public.perfiles_usuario_detalle
            set edad = v_edad, ciudad = v_ciudad, estado_laboral = v_laboral, centro_trabajo = v_centro,
                tiempo_disponible_estudio_minutos = v_tiempo, franja_preferente_estudio = v_franja,
                fecha_examen_objetivo = v_fecha, actualizado_en = now()
            where usuario_id = p_usuario_id;
            v_touch := true;
        end if;
    end if;

    -- target communities: the array REPLACES the set; prioridad = position (1..n).
    if p_patch ? 'targetCommunityIds' then
        if jsonb_typeof(p_patch -> 'targetCommunityIds') is distinct from 'array'
           or jsonb_array_length(p_patch -> 'targetCommunityIds') > 20 then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'targetCommunityIds';
        end if;
        v_raw := '{}'::uuid[];
        for v_i in 0 .. jsonb_array_length(p_patch -> 'targetCommunityIds') - 1 loop
            if jsonb_typeof(p_patch -> 'targetCommunityIds' -> v_i) is distinct from 'string' then
                raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'targetCommunityIds';
            end if;
            v_raw := v_raw || private.slice1_uuid(p_patch -> 'targetCommunityIds' ->> v_i, 'targetCommunityIds');
        end loop;
        select coalesce(array_agg(s.id order by s.first_ord), '{}'::uuid[]) into v_targets
        from (select t.id, min(t.ord) as first_ord from unnest(v_raw) with ordinality as t(id, ord) group by t.id) s;
        if (select count(*) from public.comunidades_autonomas c where c.id = any (v_targets)) <> cardinality(v_targets) then
            raise exception using errcode = '22023', message = 'UNKNOWN_COMMUNITY';
        end if;
        select coalesce(array_agg(o.comunidad_id order by o.prioridad, o.comunidad_id), '{}'::uuid[]) into v_current
        from public.comunidades_objetivo_usuario o where o.usuario_id = p_usuario_id;
        if v_current is distinct from v_targets then
            delete from public.comunidades_objetivo_usuario where usuario_id = p_usuario_id;
            insert into public.comunidades_objetivo_usuario (usuario_id, comunidad_id, prioridad)
            select p_usuario_id, t.id, t.ord::integer from unnest(v_targets) with ordinality as t(id, ord);
            v_touch := true;
        end if;
    end if;

    if v_touch
       or (v_username, v_nombre, v_apellidos, v_esp, v_com, v_intro) is distinct from
          (v_p.username, v_p.nombre, v_p.apellidos, v_p.especialidad_principal_id,
           v_p.comunidad_principal_id, v_p.perfil_intro_visto_en) then
        begin
            update public.perfiles_usuario
            set username = v_username, nombre = v_nombre, apellidos = v_apellidos,
                especialidad_principal_id = v_esp, comunidad_principal_id = v_com,
                perfil_intro_visto_en = v_intro
            where usuario_id = p_usuario_id;
        exception when unique_violation then
            raise exception using errcode = '23505', message = 'USERNAME_TAKEN';
        end;
    end if;

    return private.slice1_profile_json(p_usuario_id);
end;
$$;

-- 3. documentos_legales: no client write/TRUNCATE/REFERENCES/TRIGGER on fresh/branch DBs (MASTER-DB-004) -
revoke insert, update, delete, truncate, references, trigger on table public.documentos_legales from public, anon, authenticated;
grant select on table public.documentos_legales to anon, authenticated;
grant all on table public.documentos_legales to service_role;

-- Privileges of the re-created RPCs: service_role only (idempotent re-assertion) --------------------------
revoke all on function public.app_completar_onboarding(uuid, text, text, text, uuid, uuid[], text, jsonb, text)
    from public, anon, authenticated;
revoke all on function public.app_actualizar_perfil(uuid, jsonb, timestamptz) from public, anon, authenticated;
grant execute on function public.app_completar_onboarding(uuid, text, text, text, uuid, uuid[], text, jsonb, text) to service_role;
grant execute on function public.app_actualizar_perfil(uuid, jsonb, timestamptz) to service_role;

commit;
