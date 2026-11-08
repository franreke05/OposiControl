-- =============================================================================
-- V1 -- profile photo (owner decision 2026-11-08): D1-14 PUT / D1-15 DELETE /api/v1/app/me/avatar, avatar shown to the user and to others
-- (ranking top 3 and forum authors). Contract amendment FA42 (openapi/profiles/profiles.yaml, ranking/ranking.yaml, foro/foro.yaml).
-- =============================================================================
-- LOCAL / PG17 GATE ONLY. NOT applied to MAIN. Forward-only; no earlier file is edited. Idempotent (create or replace), one transaction.
-- Requires 20260930100200 (slice1 profile helpers), 20261022101700 (ranking) and 20261022101900 (forum).
--
-- WHAT IT DOES
--   * private.avatar_visible_path(uid, avatar_url, estado_cuenta): the ONLY rule that makes a stored avatar servable. It returns the value only when
--     it is the server-owned object of THAT user ('avatars/{uid}/{sha256}.jpg', written by app_set_avatar) and the account is active; otherwise NULL.
--     perfiles_usuario.avatar_url can hold an EXTERNAL URL (handle_new_auth_user copies raw_user_meta_data->>'avatar_url' from OAuth): such a value is
--     never signed nor forwarded.
--   * public.app_set_avatar(p_usuario_id, p_path) / public.app_clear_avatar(p_usuario_id): SECURITY DEFINER commands (row lock + active account through
--     private.slice1_lock_profile, same errors as the other profile commands). Ktor re-encodes the image, stores the object in the PRIVATE bucket
--     `avatars` and then records its path here; the answer carries previousPath (a server-owned object of the same user only) so Ktor can delete the
--     replaced object. Same bytes = same path = no write (changed false).
--   * app_ranking_me: top3 entries carry avatarPath while the listed user is still public (ranking_publico) and active.
--   * app_foro_list / app_foro_post / app_foro_comments: every autor object carries avatarPath (null when none). The forum gates are unchanged.
--   Ktor turns every avatarPath into a short-lived signed avatarUrl (batch) and never forwards the path.
--
-- ROLLBACK / MITIGATION
--   The 20261022101700 / 20261022101900 bodies come back verbatim and the three new functions are dropped. Stored avatar_url values stay (they are
--   plain profile data, served by nobody after the rollback); the Storage objects stay in the bucket.
--
-- LOCKING: function DDL only. Runtime: one profile row lock per avatar command.   DATA LOSS: none.   INDEXES: none.
--
-- RLS AND GRANTS
--   New public functions: SECURITY DEFINER, owner = migration role, search_path '', EXECUTE revoked from public/anon/authenticated, granted to
--   service_role only. The private helper: EXECUTE revoked from everyone but the owner (called only from the definer functions).
--
-- ROLLBACK (manual; executed verbatim by the PG17 gate and dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- create or replace function public.app_ranking_me(p_usuario uuid)
-- returns jsonb
-- language plpgsql
-- stable
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   pf public.perfiles_usuario%rowtype;
--   ec public.estado_competitivo_usuario%rowtype;
--   lg public.ligas_nivel%rowtype;
--   v_min integer := private.config_int('cohorte_minima_comparativa', 50);
--   v_cohorte boolean;
--   v_prob jsonb;
--   v_top jsonb := '[]'::jsonb;
-- begin
--   select * into pf from public.perfiles_usuario x where x.usuario_id = p_usuario;
--   if not found then
--     raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
--   end if;
--   select * into ec from public.estado_competitivo_usuario x where x.usuario_id = p_usuario;
--   select * into lg from public.ligas_nivel x where x.id = ec.liga_nivel_id;
--   v_cohorte := ec.ranking_cohorte is not null and ec.ranking_cohorte >= v_min;
--   v_prob := case
--     when ec.liga_nivel_id is null then jsonb_build_object('disponible', false, 'motivo', 'sin_liga')
--     when ec.preguntas_validas_ranking < coalesce(ec.ventana_calculo, 1) then jsonb_build_object('disponible', false, 'motivo', 'ventana_incompleta')
--     when ec.probabilidad_plaza is null or coalesce(ec.probabilidad_cohorte, 0) < v_min then jsonb_build_object('disponible', false, 'motivo', 'cohorte_insuficiente')
--     else jsonb_build_object('disponible', true, 'porcentaje', ec.probabilidad_plaza, 'tramoCohorte', private.cohorte_tramo(ec.probabilidad_cohorte)) end;
--   if v_cohorte and pf.especialidad_principal_id is not null then
--     select coalesce(jsonb_agg(jsonb_build_object('posicion', t.posicion, 'handle', t.handle, 'notaMedia', t.nota_media) order by t.posicion), '[]'::jsonb) into v_top
--       from public.ranking_top t where t.especialidad_id = pf.especialidad_principal_id and t.liga_nivel_id = ec.liga_nivel_id;
--   end if;
--   -- absent fields are absent (contract); only `liga` is an explicit null (the user has not reached the first tier)
--   return jsonb_strip_nulls(jsonb_build_object(
--     'liga', case when lg.id is not null then jsonb_build_object('codigo', lg.codigo, 'nombre', lg.nombre) end,
--     'posicion', ec.ranking_liga_posicion,
--     'notaMedia', ec.nota_media_actual,
--     'cohorte', case when v_cohorte then jsonb_build_object('tramo', private.cohorte_tramo(ec.ranking_cohorte)) end,
--     'top3', v_top,
--     'probabilidadPlaza', v_prob,
--     'visibilidad', jsonb_build_object('publico', pf.ranking_publico),
--     'calculadoEn', ec.ranking_calculado_en)) || case when lg.id is null then '{"liga":null}'::jsonb else '{}'::jsonb end;
-- end;
-- $$;
-- revoke all on function public.app_ranking_me(uuid) from public, anon, authenticated;
-- grant execute on function public.app_ranking_me(uuid) to service_role;
-- create or replace function public.app_foro_list(p_usuario uuid, p_categoria uuid, p_orden text, p_cursor text, p_limite integer)
-- returns jsonb
-- language plpgsql
-- stable
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_esp uuid := private.foro_user_specialty(p_usuario);
--   v_lim integer := least(greatest(coalesce(p_limite, 20), 1), 50);
--   v_orden text := coalesce(p_orden, 'recientes');
--   k text[] := private.cur_dec(p_cursor);
--   v_items jsonb; v_n integer; v_last_id uuid; v_last_ts timestamptz; v_last_n integer;
-- begin
--   if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
--     raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
--   end if;
--   if v_orden not in ('recientes', 'votadas') then
--     raise exception using errcode = 'P0001', message = 'CURSOR_INVALID';
--   end if;
--   if v_esp is null or private.config_int('foro_activo', 1) = 0 or (p_categoria is not null and not exists (
--        select 1 from public.categorias_oposired c where c.id = p_categoria and c.activa and (c.especialidad_id is null or c.especialidad_id = v_esp))) then
--     raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND';
--   end if;
--   with page as (
--     select p.id, p.categoria_id, p.titulo, left(p.contenido, 200) as extracto, p.n_me_gusta, p.n_comentarios, p.creado_en, p.estado, pf.username,
--            exists (select 1 from public.reacciones_oposired r where r.publicacion_id = p.id and r.usuario_id = p_usuario and r.tipo_reaccion = 'me_gusta') as mi_voto,
--            exists (select 1 from public.imagenes_oposired i where i.id = p.imagen_id and i.estado = 'verificada') as tiene_imagen
--       from public.publicaciones_oposired p
--       join public.categorias_oposired c on c.id = p.categoria_id and c.activa and (c.especialidad_id is null or c.especialidad_id = v_esp)
--       left join public.perfiles_usuario pf on pf.usuario_id = p.usuario_id
--      where (p_categoria is null or p.categoria_id = p_categoria)
--        and (p.estado = 'publicada' or (p.estado = 'pendiente_moderacion' and p.usuario_id = p_usuario))
--        and (k is null or (case when v_orden = 'recientes' then (p.creado_en, p.id) < (k[1]::timestamptz, k[2]::uuid) else (p.n_me_gusta, p.creado_en, p.id) < (k[1]::integer, k[2]::timestamptz, k[3]::uuid) end))
--      order by case when v_orden = 'votadas' then p.n_me_gusta end desc nulls last, p.creado_en desc, p.id desc
--      limit v_lim
--   ), agg as (
--     select coalesce(jsonb_agg(jsonb_build_object('id', f.id, 'categoriaId', f.categoria_id, 'titulo', f.titulo, 'extracto', f.extracto,
--              'autor', jsonb_build_object('handle', f.username), 'meGusta', f.n_me_gusta, 'comentarios', f.n_comentarios, 'creadoEn', f.creado_en,
--              'miVoto', f.mi_voto, 'enRevision', f.estado = 'pendiente_moderacion', 'tieneImagen', f.tiene_imagen)
--            order by case when v_orden = 'votadas' then f.n_me_gusta end desc nulls last, f.creado_en desc, f.id desc), '[]'::jsonb) as items, count(*)::integer as n from page f
--   ), ultimo as (
--     select f.id, f.creado_en, f.n_me_gusta from page f order by case when v_orden = 'votadas' then f.n_me_gusta end asc nulls last, f.creado_en asc, f.id asc limit 1
--   )
--   select agg.items, agg.n, u.id, u.creado_en, u.n_me_gusta into v_items, v_n, v_last_id, v_last_ts, v_last_n from agg left join ultimo u on true;
--   return jsonb_build_object('items', v_items, 'nextCursor', case when v_n >= v_lim then
--     case when v_orden = 'recientes' then private.cur_enc(array[v_last_ts::text, v_last_id::text]) else private.cur_enc(array[v_last_n::text, v_last_ts::text, v_last_id::text]) end end);
-- end;
-- $$;
-- revoke all on function public.app_foro_list(uuid, uuid, text, text, integer) from public, anon, authenticated;
-- grant execute on function public.app_foro_list(uuid, uuid, text, text, integer) to service_role;
--
-- -- a post is readable by the caller: its channel passes the gate and it is published, or it is the caller's own pending post. Same "not found" for everything else.
-- create or replace function public.app_foro_post(p_usuario uuid, p_id uuid)
-- returns jsonb
-- language plpgsql
-- stable
-- security definer
-- set search_path = ''
-- as $$
-- declare v_esp uuid := private.foro_user_specialty(p_usuario); r record;
-- begin
--   if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
--     raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
--   end if;
--   select p.*, pf.username, c.solo_lectura into r
--     from public.publicaciones_oposired p
--     join public.categorias_oposired c on c.id = p.categoria_id and c.activa and (c.especialidad_id is null or c.especialidad_id = v_esp)
--     left join public.perfiles_usuario pf on pf.usuario_id = p.usuario_id
--    where p.id = p_id and v_esp is not null and private.config_int('foro_activo', 1) = 1
--      and (p.estado = 'publicada' or (p.estado = 'pendiente_moderacion' and p.usuario_id = p_usuario));
--   if not found then
--     raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND';
--   end if;
--   return jsonb_build_object('id', r.id, 'categoriaId', r.categoria_id, 'titulo', r.titulo, 'cuerpo', r.contenido,
--            'autor', jsonb_build_object('handle', r.username), 'meGusta', r.n_me_gusta, 'comentarios', r.n_comentarios, 'creadoEn', r.creado_en,
--            'miVoto', exists (select 1 from public.reacciones_oposired x where x.publicacion_id = r.id and x.usuario_id = p_usuario and x.tipo_reaccion = 'me_gusta'),
--            'enRevision', r.estado = 'pendiente_moderacion', 'esMio', r.usuario_id = p_usuario, 'soloLectura', r.solo_lectura,
--            'imagenId', (select i.id from public.imagenes_oposired i where i.id = r.imagen_id and i.estado = 'verificada'));
-- end;
-- $$;
-- revoke all on function public.app_foro_post(uuid, uuid) from public, anon, authenticated;
-- grant execute on function public.app_foro_post(uuid, uuid) to service_role;
--
-- create or replace function public.app_foro_comments(p_usuario uuid, p_post uuid, p_cursor text, p_limite integer)
-- returns jsonb
-- language plpgsql
-- stable
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_lim integer := least(greatest(coalesce(p_limite, 20), 1), 50);
--   k text[] := private.cur_dec(p_cursor);
--   v_items jsonb; v_n integer; v_last_id uuid; v_last_ts timestamptz;
-- begin
--   perform public.app_foro_post(p_usuario, p_post);   -- the gate of the post (raises FORO_NOT_FOUND)
--   with page as (
--     select c.id, c.contenido, c.n_me_gusta, c.creado_en, c.estado, c.usuario_id, pf.username,
--            exists (select 1 from public.reacciones_oposired r where r.comentario_id = c.id and r.usuario_id = p_usuario and r.tipo_reaccion = 'me_gusta') as mi_voto
--       from public.comentarios_oposired c left join public.perfiles_usuario pf on pf.usuario_id = c.usuario_id
--      where c.publicacion_id = p_post and (c.estado = 'publicado' or (c.estado = 'pendiente_moderacion' and c.usuario_id = p_usuario))
--        and (k is null or (c.creado_en, c.id) > (k[1]::timestamptz, k[2]::uuid))
--      order by c.creado_en, c.id limit v_lim
--   ), agg as (
--     select coalesce(jsonb_agg(jsonb_build_object('id', f.id, 'publicacionId', p_post, 'cuerpo', f.contenido, 'autor', jsonb_build_object('handle', f.username), 'meGusta', f.n_me_gusta,
--              'creadoEn', f.creado_en, 'miVoto', f.mi_voto, 'enRevision', f.estado = 'pendiente_moderacion', 'esMio', f.usuario_id = p_usuario) order by f.creado_en, f.id), '[]'::jsonb) as items, count(*)::integer as n from page f
--   ), ultimo as (select f.id, f.creado_en from page f order by f.creado_en desc, f.id desc limit 1)
--   select agg.items, agg.n, u.id, u.creado_en into v_items, v_n, v_last_id, v_last_ts from agg left join ultimo u on true;
--   return jsonb_build_object('items', v_items, 'nextCursor', case when v_n >= v_lim then private.cur_enc(array[v_last_ts::text, v_last_id::text]) end);
-- end;
-- $$;
-- revoke all on function public.app_foro_comments(uuid, uuid, text, integer) from public, anon, authenticated;
-- grant execute on function public.app_foro_comments(uuid, uuid, text, integer) to service_role;
-- drop function if exists public.app_clear_avatar(uuid);
-- drop function if exists public.app_set_avatar(uuid, text);
-- drop function if exists private.avatar_visible_path(uuid, text, text);
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'avatar_function_missing_or_not_service_role_only:' || f.n as check_name,
--        (case when to_regprocedure(f.n) is null then 1
--              when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute')
--                or not has_function_privilege('service_role', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('public.app_set_avatar(uuid,text)'), ('public.app_clear_avatar(uuid)'), ('public.app_ranking_me(uuid)'),
--                ('public.app_foro_list(uuid,uuid,text,text,integer)'), ('public.app_foro_post(uuid,uuid)'), ('public.app_foro_comments(uuid,uuid,text,integer)')) f(n)
-- union all
-- select 'avatar_command_not_definer_or_search_path:' || p.proname,
--        (case when p.prosecdef and coalesce(p.proconfig, '{}'::text[]) @> array['search_path=""'] then 0 else 1 end)::bigint
--   from pg_proc p where p.pronamespace = 'public'::regnamespace and p.proname in ('app_set_avatar', 'app_clear_avatar')
-- union all
-- select 'avatar_helper_missing_or_callable',
--        (case when to_regprocedure('private.avatar_visible_path(uuid,text,text)') is null then 1
--              when has_function_privilege('anon', 'private.avatar_visible_path(uuid,text,text)', 'execute')
--                or has_function_privilege('authenticated', 'private.avatar_visible_path(uuid,text,text)', 'execute')
--                or has_function_privilege('service_role', 'private.avatar_visible_path(uuid,text,text)', 'execute') then 1 else 0 end)::bigint
-- union all
-- select 'avatar_reads_without_the_helper:' || p.proname, (case when p.prosrc like '%private.avatar_visible_path(%' then 0 else 1 end)::bigint
--   from pg_proc p where p.pronamespace = 'public'::regnamespace and p.proname in ('app_ranking_me', 'app_foro_list', 'app_foro_post', 'app_foro_comments')
-- union all
-- select 'avatar_rule_serves_a_foreign_value',
--        (case when to_regprocedure('private.avatar_visible_path(uuid,text,text)') is null then 1
--              when private.avatar_visible_path('0f8fad5b-d9cb-469f-a165-70867728950e', 'https://lh3.googleusercontent.com/a/photo.jpg', 'activa') is not null
--                or private.avatar_visible_path('0f8fad5b-d9cb-469f-a165-70867728950e', 'avatars/11111111-1111-1111-1111-111111111111/' || repeat('a', 64) || '.jpg', 'activa') is not null
--                or private.avatar_visible_path('0f8fad5b-d9cb-469f-a165-70867728950e', 'avatars/0f8fad5b-d9cb-469f-a165-70867728950e/' || repeat('a', 64) || '.jpg', 'bloqueada') is not null
--                or private.avatar_visible_path('0f8fad5b-d9cb-469f-a165-70867728950e', 'avatars/0f8fad5b-d9cb-469f-a165-70867728950e/' || repeat('a', 64) || '.jpg', 'activa') is null
--              then 1 else 0 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('private.slice1_lock_profile(uuid,boolean)') is null or to_regprocedure('public.app_ranking_me(uuid)') is null
     or to_regprocedure('public.app_foro_list(uuid,uuid,text,text,integer)') is null or to_regprocedure('public.app_foro_post(uuid,uuid)') is null
     or to_regprocedure('public.app_foro_comments(uuid,uuid,text,integer)') is null
     or not exists (select 1 from pg_attribute a where a.attrelid = 'public.perfiles_usuario'::regclass and a.attname = 'avatar_url' and not a.attisdropped) then
    raise exception using errcode = 'P0001', message = 'V1_AVATAR_PREFLIGHT', hint = 'apply 20260930100200, 20261022101700 and 20261022101900 first';
  end if;
end
$$;

-- ---- 1. the only rule that turns a stored avatar_url into something servable
-- A path is served ONLY when it is the server-owned object of THAT user (avatars/{uid}/{sha256}.jpg, written by app_set_avatar) and the account is
-- active. Anything else (an OAuth URL copied by handle_new_auth_user, a legacy value, another user's path) is never signed: NULL.
create or replace function private.avatar_visible_path(p_usuario uuid, p_avatar text, p_estado text)
returns text
language sql
immutable
security invoker
set search_path = ''
as $$
  select case
           when p_usuario is not null and p_estado = 'activa'
                and p_avatar ~ ('^avatars/' || p_usuario::text || '/[0-9a-f]{64}\.jpg$') then p_avatar
         end
$$;
revoke all on function private.avatar_visible_path(uuid, text, text) from public, anon, authenticated, service_role;

-- ---- 2. the two commands (D1-14 PUT / D1-15 DELETE): Ktor stores the re-encoded object first, then records its path here
create or replace function public.app_set_avatar(p_usuario_id uuid, p_path text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_p public.perfiles_usuario;
  v_changed boolean;
begin
  v_p := private.slice1_lock_profile(p_usuario_id);   -- row lock; active account only (PROFILE_MISSING / ACCOUNT_BLOCKED / ACCOUNT_DELETION_PENDING / FORBIDDEN)
  if p_path is null or p_path !~ ('^avatars/' || p_usuario_id::text || '/[0-9a-f]{64}\.jpg$') then
    raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'avatar';
  end if;
  v_changed := v_p.avatar_url is distinct from p_path;
  if v_changed then
    update public.perfiles_usuario set avatar_url = p_path where usuario_id = p_usuario_id;
  end if;
  -- previousPath: only a server-owned object of this user (the caller deletes it from Storage); an external URL is never returned
  return jsonb_build_object(
    'avatarPath', p_path,
    'previousPath', case when v_changed then private.avatar_visible_path(p_usuario_id, v_p.avatar_url, 'activa') end,
    'changed', v_changed);
end;
$$;
revoke all on function public.app_set_avatar(uuid, text) from public, anon, authenticated;
grant execute on function public.app_set_avatar(uuid, text) to service_role;

create or replace function public.app_clear_avatar(p_usuario_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_p public.perfiles_usuario;
begin
  v_p := private.slice1_lock_profile(p_usuario_id);
  if v_p.avatar_url is not null then
    update public.perfiles_usuario set avatar_url = null where usuario_id = p_usuario_id;
  end if;
  return jsonb_build_object(
    'previousPath', private.avatar_visible_path(p_usuario_id, v_p.avatar_url, 'activa'),
    'changed', v_p.avatar_url is not null);
end;
$$;
revoke all on function public.app_clear_avatar(uuid) from public, anon, authenticated;
grant execute on function public.app_clear_avatar(uuid) to service_role;

-- ---- 3. ranking: top3 entries carry avatarPath (Ktor signs it as avatarUrl and never forwards the path).
-- Same body as 20261022101700 except the top3 query: the avatar of a listed user is served only while the user is STILL public (ranking_publico)
-- and active; an opted-out user is already removed from ranking_top by app_set_ranking_visibility.
create or replace function public.app_ranking_me(p_usuario uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  pf public.perfiles_usuario%rowtype;
  ec public.estado_competitivo_usuario%rowtype;
  lg public.ligas_nivel%rowtype;
  v_min integer := private.config_int('cohorte_minima_comparativa', 50);
  v_cohorte boolean;
  v_prob jsonb;
  v_top jsonb := '[]'::jsonb;
begin
  select * into pf from public.perfiles_usuario x where x.usuario_id = p_usuario;
  if not found then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  select * into ec from public.estado_competitivo_usuario x where x.usuario_id = p_usuario;
  select * into lg from public.ligas_nivel x where x.id = ec.liga_nivel_id;
  v_cohorte := ec.ranking_cohorte is not null and ec.ranking_cohorte >= v_min;
  v_prob := case
    when ec.liga_nivel_id is null then jsonb_build_object('disponible', false, 'motivo', 'sin_liga')
    when ec.preguntas_validas_ranking < coalesce(ec.ventana_calculo, 1) then jsonb_build_object('disponible', false, 'motivo', 'ventana_incompleta')
    when ec.probabilidad_plaza is null or coalesce(ec.probabilidad_cohorte, 0) < v_min then jsonb_build_object('disponible', false, 'motivo', 'cohorte_insuficiente')
    else jsonb_build_object('disponible', true, 'porcentaje', ec.probabilidad_plaza, 'tramoCohorte', private.cohorte_tramo(ec.probabilidad_cohorte)) end;
  if v_cohorte and pf.especialidad_principal_id is not null then
    select coalesce(jsonb_agg(jsonb_build_object('posicion', t.posicion, 'handle', t.handle, 'notaMedia', t.nota_media,
                                                 'avatarPath', case when tp.ranking_publico then private.avatar_visible_path(t.usuario_id, tp.avatar_url, tp.estado_cuenta) end)
                              order by t.posicion), '[]'::jsonb) into v_top
      from public.ranking_top t left join public.perfiles_usuario tp on tp.usuario_id = t.usuario_id
     where t.especialidad_id = pf.especialidad_principal_id and t.liga_nivel_id = ec.liga_nivel_id;
  end if;
  -- absent fields are absent (contract); only `liga` is an explicit null (the user has not reached the first tier)
  return jsonb_strip_nulls(jsonb_build_object(
    'liga', case when lg.id is not null then jsonb_build_object('codigo', lg.codigo, 'nombre', lg.nombre) end,
    'posicion', ec.ranking_liga_posicion,
    'notaMedia', ec.nota_media_actual,
    'cohorte', case when v_cohorte then jsonb_build_object('tramo', private.cohorte_tramo(ec.ranking_cohorte)) end,
    'top3', v_top,
    'probabilidadPlaza', v_prob,
    'visibilidad', jsonb_build_object('publico', pf.ranking_publico),
    'calculadoEn', ec.ranking_calculado_en)) || case when lg.id is null then '{"liga":null}'::jsonb else '{}'::jsonb end;
end;
$$;
revoke all on function public.app_ranking_me(uuid) from public, anon, authenticated;
grant execute on function public.app_ranking_me(uuid) to service_role;

-- ---- 4. forum: every autor object carries avatarPath (null when none). Same bodies as 20261022101900 plus the author's avatar_url / estado_cuenta.
-- The forum gates (specialty, pre-moderation, hidden content = 404) are unchanged: an avatar only travels with an author the caller can already read.
create or replace function public.app_foro_list(p_usuario uuid, p_categoria uuid, p_orden text, p_cursor text, p_limite integer)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_esp uuid := private.foro_user_specialty(p_usuario);
  v_lim integer := least(greatest(coalesce(p_limite, 20), 1), 50);
  v_orden text := coalesce(p_orden, 'recientes');
  k text[] := private.cur_dec(p_cursor);
  v_items jsonb; v_n integer; v_last_id uuid; v_last_ts timestamptz; v_last_n integer;
begin
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  if v_orden not in ('recientes', 'votadas') then
    raise exception using errcode = 'P0001', message = 'CURSOR_INVALID';
  end if;
  if v_esp is null or private.config_int('foro_activo', 1) = 0 or (p_categoria is not null and not exists (
       select 1 from public.categorias_oposired c where c.id = p_categoria and c.activa and (c.especialidad_id is null or c.especialidad_id = v_esp))) then
    raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND';
  end if;
  with page as (
    select p.id, p.categoria_id, p.titulo, left(p.contenido, 200) as extracto, p.n_me_gusta, p.n_comentarios, p.creado_en, p.estado, pf.username,
           private.avatar_visible_path(pf.usuario_id, pf.avatar_url, pf.estado_cuenta) as avatar_path,
           exists (select 1 from public.reacciones_oposired r where r.publicacion_id = p.id and r.usuario_id = p_usuario and r.tipo_reaccion = 'me_gusta') as mi_voto,
           exists (select 1 from public.imagenes_oposired i where i.id = p.imagen_id and i.estado = 'verificada') as tiene_imagen
      from public.publicaciones_oposired p
      join public.categorias_oposired c on c.id = p.categoria_id and c.activa and (c.especialidad_id is null or c.especialidad_id = v_esp)
      left join public.perfiles_usuario pf on pf.usuario_id = p.usuario_id
     where (p_categoria is null or p.categoria_id = p_categoria)
       and (p.estado = 'publicada' or (p.estado = 'pendiente_moderacion' and p.usuario_id = p_usuario))
       and (k is null or (case when v_orden = 'recientes' then (p.creado_en, p.id) < (k[1]::timestamptz, k[2]::uuid) else (p.n_me_gusta, p.creado_en, p.id) < (k[1]::integer, k[2]::timestamptz, k[3]::uuid) end))
     order by case when v_orden = 'votadas' then p.n_me_gusta end desc nulls last, p.creado_en desc, p.id desc
     limit v_lim
  ), agg as (
    select coalesce(jsonb_agg(jsonb_build_object('id', f.id, 'categoriaId', f.categoria_id, 'titulo', f.titulo, 'extracto', f.extracto,
             'autor', jsonb_build_object('handle', f.username, 'avatarPath', f.avatar_path), 'meGusta', f.n_me_gusta, 'comentarios', f.n_comentarios, 'creadoEn', f.creado_en,
             'miVoto', f.mi_voto, 'enRevision', f.estado = 'pendiente_moderacion', 'tieneImagen', f.tiene_imagen)
           order by case when v_orden = 'votadas' then f.n_me_gusta end desc nulls last, f.creado_en desc, f.id desc), '[]'::jsonb) as items, count(*)::integer as n from page f
  ), ultimo as (
    select f.id, f.creado_en, f.n_me_gusta from page f order by case when v_orden = 'votadas' then f.n_me_gusta end asc nulls last, f.creado_en asc, f.id asc limit 1
  )
  select agg.items, agg.n, u.id, u.creado_en, u.n_me_gusta into v_items, v_n, v_last_id, v_last_ts, v_last_n from agg left join ultimo u on true;
  return jsonb_build_object('items', v_items, 'nextCursor', case when v_n >= v_lim then
    case when v_orden = 'recientes' then private.cur_enc(array[v_last_ts::text, v_last_id::text]) else private.cur_enc(array[v_last_n::text, v_last_ts::text, v_last_id::text]) end end);
end;
$$;
revoke all on function public.app_foro_list(uuid, uuid, text, text, integer) from public, anon, authenticated;
grant execute on function public.app_foro_list(uuid, uuid, text, text, integer) to service_role;

create or replace function public.app_foro_post(p_usuario uuid, p_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare v_esp uuid := private.foro_user_specialty(p_usuario); r record;
begin
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  select p.*, pf.username, c.solo_lectura, private.avatar_visible_path(pf.usuario_id, pf.avatar_url, pf.estado_cuenta) as autor_avatar_path into r
    from public.publicaciones_oposired p
    join public.categorias_oposired c on c.id = p.categoria_id and c.activa and (c.especialidad_id is null or c.especialidad_id = v_esp)
    left join public.perfiles_usuario pf on pf.usuario_id = p.usuario_id
   where p.id = p_id and v_esp is not null and private.config_int('foro_activo', 1) = 1
     and (p.estado = 'publicada' or (p.estado = 'pendiente_moderacion' and p.usuario_id = p_usuario));
  if not found then
    raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND';
  end if;
  return jsonb_build_object('id', r.id, 'categoriaId', r.categoria_id, 'titulo', r.titulo, 'cuerpo', r.contenido,
           'autor', jsonb_build_object('handle', r.username, 'avatarPath', r.autor_avatar_path), 'meGusta', r.n_me_gusta, 'comentarios', r.n_comentarios, 'creadoEn', r.creado_en,
           'miVoto', exists (select 1 from public.reacciones_oposired x where x.publicacion_id = r.id and x.usuario_id = p_usuario and x.tipo_reaccion = 'me_gusta'),
           'enRevision', r.estado = 'pendiente_moderacion', 'esMio', r.usuario_id = p_usuario, 'soloLectura', r.solo_lectura,
           'imagenId', (select i.id from public.imagenes_oposired i where i.id = r.imagen_id and i.estado = 'verificada'));
end;
$$;
revoke all on function public.app_foro_post(uuid, uuid) from public, anon, authenticated;
grant execute on function public.app_foro_post(uuid, uuid) to service_role;

create or replace function public.app_foro_comments(p_usuario uuid, p_post uuid, p_cursor text, p_limite integer)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_lim integer := least(greatest(coalesce(p_limite, 20), 1), 50);
  k text[] := private.cur_dec(p_cursor);
  v_items jsonb; v_n integer; v_last_id uuid; v_last_ts timestamptz;
begin
  perform public.app_foro_post(p_usuario, p_post);   -- the gate of the post (raises FORO_NOT_FOUND)
  with page as (
    select c.id, c.contenido, c.n_me_gusta, c.creado_en, c.estado, c.usuario_id, pf.username,
           private.avatar_visible_path(pf.usuario_id, pf.avatar_url, pf.estado_cuenta) as avatar_path,
           exists (select 1 from public.reacciones_oposired r where r.comentario_id = c.id and r.usuario_id = p_usuario and r.tipo_reaccion = 'me_gusta') as mi_voto
      from public.comentarios_oposired c left join public.perfiles_usuario pf on pf.usuario_id = c.usuario_id
     where c.publicacion_id = p_post and (c.estado = 'publicado' or (c.estado = 'pendiente_moderacion' and c.usuario_id = p_usuario))
       and (k is null or (c.creado_en, c.id) > (k[1]::timestamptz, k[2]::uuid))
     order by c.creado_en, c.id limit v_lim
  ), agg as (
    select coalesce(jsonb_agg(jsonb_build_object('id', f.id, 'publicacionId', p_post, 'cuerpo', f.contenido, 'autor', jsonb_build_object('handle', f.username, 'avatarPath', f.avatar_path), 'meGusta', f.n_me_gusta,
             'creadoEn', f.creado_en, 'miVoto', f.mi_voto, 'enRevision', f.estado = 'pendiente_moderacion', 'esMio', f.usuario_id = p_usuario) order by f.creado_en, f.id), '[]'::jsonb) as items, count(*)::integer as n from page f
  ), ultimo as (select f.id, f.creado_en from page f order by f.creado_en desc, f.id desc limit 1)
  select agg.items, agg.n, u.id, u.creado_en into v_items, v_n, v_last_id, v_last_ts from agg left join ultimo u on true;
  return jsonb_build_object('items', v_items, 'nextCursor', case when v_n >= v_lim then private.cur_enc(array[v_last_ts::text, v_last_id::text]) end);
end;
$$;
revoke all on function public.app_foro_comments(uuid, uuid, text, integer) from public, anon, authenticated;
grant execute on function public.app_foro_comments(uuid, uuid, text, integer) to service_role;

commit;
