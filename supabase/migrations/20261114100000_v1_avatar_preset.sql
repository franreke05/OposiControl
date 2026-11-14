-- =============================================================================
-- V1 -- preset avatars (owner decision 2026-11-14, plan 1.3 section 8; workstream F29): the user picks ONE of 10 predefined avatars instead of
-- uploading a photo. Contract amendment FA53 (openapi/profiles/profiles.yaml, ranking/ranking.yaml, foro/foro.yaml).
-- =============================================================================
-- LOCAL / PG17 GATE ONLY. NOT applied to MAIN. Forward-only; no earlier file is edited. Idempotent (if not exists / create or replace), one transaction.
-- Requires 20261108100000 (profile photo, FA42).
--
-- WHAT IT DOES
--   * public.perfiles_usuario.avatar_id smallint NULL, CHECK 1..10: the chosen preset (the client paints it; the 10 images ship with the app).
--   * private.avatar_visible_id(avatar_id, estado_cuenta): the preset travels only for an ACTIVE account (same rule as avatar_visible_path).
--   * public.app_set_avatar_preset(p_usuario_id, p_avatar_id): SECURITY DEFINER command (row lock + active account through private.slice1_lock_profile,
--     same errors as the other profile commands). Out of range = 22023 INVALID_ARGUMENT (hint avatarId). It stores the preset and CLEARS the photo
--     (avatar_url): the answer carries previousPath (a server-owned object of the same user only) so Ktor deletes the replaced object.
--   * public.app_set_avatar (photo, D1-14 raw upload, OFF by default in Ktor) now also clears avatar_id; public.app_clear_avatar (D1-15) clears both.
--     A profile holds a preset OR a photo, never both.
--   * app_ranking_me (top3) / app_foro_list / app_foro_post / app_foro_comments (autor): carry avatarId next to the existing avatarPath, under the same
--     gates (ranking: still public and active; forum: active account; a tombstone gets null).
--
-- ROLLBACK / MITIGATION
--   The 20261108100000 bodies of the six functions come back verbatim, the new function and helper are dropped and the column is dropped (the chosen
--   presets are lost: a cosmetic choice the user can repeat).
--
-- LOCKING: ALTER TABLE ADD COLUMN (nullable, no default: catalog only, brief ACCESS EXCLUSIVE on perfiles_usuario under lock_timeout) + function DDL.
-- DATA LOSS: none forward.   INDEXES: none.
--
-- RLS AND GRANTS
--   New public function: SECURITY DEFINER, search_path '', EXECUTE revoked from public/anon/authenticated, granted to service_role only. The private helper:
--   EXECUTE revoked from everyone but the owner. The new column inherits the table grants (clients cannot write perfiles_usuario directly).
--
-- ROLLBACK (manual; executed verbatim by the PG17 gate and dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- create or replace function public.app_set_avatar(p_usuario_id uuid, p_path text)
-- returns jsonb
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_p public.perfiles_usuario;
--   v_changed boolean;
-- begin
--   v_p := private.slice1_lock_profile(p_usuario_id);   -- row lock; active account only (PROFILE_MISSING / ACCOUNT_BLOCKED / ACCOUNT_DELETION_PENDING / FORBIDDEN)
--   if p_path is null or p_path !~ ('^avatars/' || p_usuario_id::text || '/[0-9a-f]{64}\.jpg$') then
--     raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'avatar';
--   end if;
--   v_changed := v_p.avatar_url is distinct from p_path;
--   if v_changed then
--     update public.perfiles_usuario set avatar_url = p_path where usuario_id = p_usuario_id;
--   end if;
--   -- previousPath: only a server-owned object of this user (the caller deletes it from Storage); an external URL is never returned
--   return jsonb_build_object(
--     'avatarPath', p_path,
--     'previousPath', case when v_changed then private.avatar_visible_path(p_usuario_id, v_p.avatar_url, 'activa') end,
--     'changed', v_changed);
-- end;
-- $$;
-- revoke all on function public.app_set_avatar(uuid, text) from public, anon, authenticated;
-- grant execute on function public.app_set_avatar(uuid, text) to service_role;
--
-- create or replace function public.app_clear_avatar(p_usuario_id uuid)
-- returns jsonb
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_p public.perfiles_usuario;
-- begin
--   v_p := private.slice1_lock_profile(p_usuario_id);
--   if v_p.avatar_url is not null then
--     update public.perfiles_usuario set avatar_url = null where usuario_id = p_usuario_id;
--   end if;
--   return jsonb_build_object(
--     'previousPath', private.avatar_visible_path(p_usuario_id, v_p.avatar_url, 'activa'),
--     'changed', v_p.avatar_url is not null);
-- end;
-- $$;
-- revoke all on function public.app_clear_avatar(uuid) from public, anon, authenticated;
-- grant execute on function public.app_clear_avatar(uuid) to service_role;
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
--     select coalesce(jsonb_agg(jsonb_build_object('posicion', t.posicion, 'handle', t.handle, 'notaMedia', t.nota_media,
--                                                  'avatarPath', case when tp.ranking_publico then private.avatar_visible_path(t.usuario_id, tp.avatar_url, tp.estado_cuenta) end)
--                               order by t.posicion), '[]'::jsonb) into v_top
--       from public.ranking_top t left join public.perfiles_usuario tp on tp.usuario_id = t.usuario_id
--      where t.especialidad_id = pf.especialidad_principal_id and t.liga_nivel_id = ec.liga_nivel_id;
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
--            private.avatar_visible_path(pf.usuario_id, pf.avatar_url, pf.estado_cuenta) as avatar_path,
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
--              'autor', jsonb_build_object('handle', f.username, 'avatarPath', f.avatar_path), 'meGusta', f.n_me_gusta, 'comentarios', f.n_comentarios, 'creadoEn', f.creado_en,
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
--   select p.*, pf.username, c.solo_lectura, private.avatar_visible_path(pf.usuario_id, pf.avatar_url, pf.estado_cuenta) as autor_avatar_path into r
--     from public.publicaciones_oposired p
--     join public.categorias_oposired c on c.id = p.categoria_id and c.activa and (c.especialidad_id is null or c.especialidad_id = v_esp)
--     left join public.perfiles_usuario pf on pf.usuario_id = p.usuario_id
--    where p.id = p_id and v_esp is not null and private.config_int('foro_activo', 1) = 1
--      and (p.estado = 'publicada' or (p.estado = 'pendiente_moderacion' and p.usuario_id = p_usuario));
--   if not found then
--     raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND';
--   end if;
--   return jsonb_build_object('id', r.id, 'categoriaId', r.categoria_id, 'titulo', r.titulo, 'cuerpo', r.contenido,
--            'autor', jsonb_build_object('handle', r.username, 'avatarPath', r.autor_avatar_path), 'meGusta', r.n_me_gusta, 'comentarios', r.n_comentarios, 'creadoEn', r.creado_en,
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
--            private.avatar_visible_path(pf.usuario_id, pf.avatar_url, pf.estado_cuenta) as avatar_path,
--            exists (select 1 from public.reacciones_oposired r where r.comentario_id = c.id and r.usuario_id = p_usuario and r.tipo_reaccion = 'me_gusta') as mi_voto
--       from public.comentarios_oposired c left join public.perfiles_usuario pf on pf.usuario_id = c.usuario_id
--      where c.publicacion_id = p_post and (c.estado = 'publicado' or (c.estado = 'pendiente_moderacion' and c.usuario_id = p_usuario))
--        and (k is null or (c.creado_en, c.id) > (k[1]::timestamptz, k[2]::uuid))
--      order by c.creado_en, c.id limit v_lim
--   ), agg as (
--     select coalesce(jsonb_agg(jsonb_build_object('id', f.id, 'publicacionId', p_post, 'cuerpo', f.contenido, 'autor', jsonb_build_object('handle', f.username, 'avatarPath', f.avatar_path), 'meGusta', f.n_me_gusta,
--              'creadoEn', f.creado_en, 'miVoto', f.mi_voto, 'enRevision', f.estado = 'pendiente_moderacion', 'esMio', f.usuario_id = p_usuario) order by f.creado_en, f.id), '[]'::jsonb) as items, count(*)::integer as n from page f
--   ), ultimo as (select f.id, f.creado_en from page f order by f.creado_en desc, f.id desc limit 1)
--   select agg.items, agg.n, u.id, u.creado_en into v_items, v_n, v_last_id, v_last_ts from agg left join ultimo u on true;
--   return jsonb_build_object('items', v_items, 'nextCursor', case when v_n >= v_lim then private.cur_enc(array[v_last_ts::text, v_last_id::text]) end);
-- end;
-- $$;
-- revoke all on function public.app_foro_comments(uuid, uuid, text, integer) from public, anon, authenticated;
-- grant execute on function public.app_foro_comments(uuid, uuid, text, integer) to service_role;
-- drop function if exists public.app_set_avatar_preset(uuid, smallint);
-- drop function if exists private.avatar_visible_id(smallint, text);
-- alter table public.perfiles_usuario drop constraint if exists perfiles_usuario_avatar_id_check;
-- alter table public.perfiles_usuario drop column if exists avatar_id;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'avatar_preset_column_missing' as check_name,
--        (case when exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'perfiles_usuario' and column_name = 'avatar_id'
--                             and data_type = 'smallint' and is_nullable = 'YES')
--               and exists (select 1 from pg_constraint where conrelid = 'public.perfiles_usuario'::regclass and conname = 'perfiles_usuario_avatar_id_check' and convalidated)
--              then 0 else 1 end)::bigint as failures
-- union all
-- select 'avatar_preset_function_missing_or_not_service_role_only:' || f.n,
--        (case when to_regprocedure(f.n) is null then 1
--              when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute')
--                or not has_function_privilege('service_role', f.n, 'execute') then 1 else 0 end)::bigint
--   from (values ('public.app_set_avatar_preset(uuid,smallint)'), ('public.app_set_avatar(uuid,text)'), ('public.app_clear_avatar(uuid)'), ('public.app_ranking_me(uuid)'),
--                ('public.app_foro_list(uuid,uuid,text,text,integer)'), ('public.app_foro_post(uuid,uuid)'), ('public.app_foro_comments(uuid,uuid,text,integer)')) f(n)
-- union all
-- select 'avatar_preset_command_not_definer_or_search_path',
--        (case when to_regprocedure('public.app_set_avatar_preset(uuid,smallint)') is not null
--               and exists (select 1 from pg_proc p where p.oid = to_regprocedure('public.app_set_avatar_preset(uuid,smallint)')
--                             and p.prosecdef and coalesce(p.proconfig, '{}'::text[]) @> array['search_path=""']) then 0 else 1 end)::bigint
-- union all
-- select 'avatar_preset_helper_missing_or_callable',
--        (case when to_regprocedure('private.avatar_visible_id(smallint,text)') is null then 1
--              when has_function_privilege('anon', 'private.avatar_visible_id(smallint,text)', 'execute')
--                or has_function_privilege('authenticated', 'private.avatar_visible_id(smallint,text)', 'execute')
--                or has_function_privilege('service_role', 'private.avatar_visible_id(smallint,text)', 'execute') then 1 else 0 end)::bigint
-- union all
-- select 'avatar_preset_reads_without_the_helper:' || p.proname,
--        (case when p.prosrc like '%private.avatar_visible_id(%' and p.prosrc like '%''avatarId''%' then 0 else 1 end)::bigint
--   from pg_proc p where p.pronamespace = 'public'::regnamespace and p.proname in ('app_ranking_me', 'app_foro_list', 'app_foro_post', 'app_foro_comments')
-- union all
-- select 'avatar_preset_photo_commands_ignore_the_preset:' || p.proname, (case when p.prosrc like '%avatar_id = null%' then 0 else 1 end)::bigint
--   from pg_proc p where p.pronamespace = 'public'::regnamespace and p.proname in ('app_set_avatar', 'app_clear_avatar')
-- union all
-- select 'avatar_preset_rule',
--        (case when to_regprocedure('private.avatar_visible_id(smallint,text)') is null then 1
--              when private.avatar_visible_id(3::smallint, 'activa') is distinct from 3::smallint
--                or private.avatar_visible_id(3::smallint, 'bloqueada') is not null
--                or private.avatar_visible_id(null::smallint, 'activa') is not null then 1 else 0 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('public.app_set_avatar(uuid,text)') is null or to_regprocedure('public.app_clear_avatar(uuid)') is null
     or to_regprocedure('private.avatar_visible_path(uuid,text,text)') is null or to_regprocedure('private.slice1_lock_profile(uuid,boolean)') is null then
    raise exception using errcode = 'P0001', message = 'V1_AVATAR_PRESET_PREFLIGHT', hint = 'apply 20261108100000 first';
  end if;
end
$$;

-- ---- 1. the column: one of the 10 presets, or none
alter table public.perfiles_usuario add column if not exists avatar_id smallint;
alter table public.perfiles_usuario drop constraint if exists perfiles_usuario_avatar_id_check;
alter table public.perfiles_usuario add constraint perfiles_usuario_avatar_id_check check (avatar_id is null or avatar_id between 1 and 10);

-- ---- 2. the rule that lets a preset travel: active account only (same gate as avatar_visible_path)
create or replace function private.avatar_visible_id(p_avatar_id smallint, p_estado text)
returns smallint
language sql
immutable
security invoker
set search_path = ''
as $$
  select case when p_estado = 'activa' then p_avatar_id end
$$;
revoke all on function private.avatar_visible_id(smallint, text) from public, anon, authenticated, service_role;

-- ---- 3. choose a preset (D1-14 with a JSON body {avatarId}): stores it and clears the photo
create or replace function public.app_set_avatar_preset(p_usuario_id uuid, p_avatar_id smallint)
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
  if p_avatar_id is null or p_avatar_id < 1 or p_avatar_id > 10 then
    raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'avatarId';
  end if;
  v_changed := v_p.avatar_id is distinct from p_avatar_id or v_p.avatar_url is not null;
  if v_changed then
    update public.perfiles_usuario set avatar_id = p_avatar_id, avatar_url = null where usuario_id = p_usuario_id;
  end if;
  -- previousPath: only a server-owned photo of this user (the caller deletes it from Storage); an external URL is never returned
  return jsonb_build_object(
    'avatarId', p_avatar_id,
    'previousPath', private.avatar_visible_path(p_usuario_id, v_p.avatar_url, 'activa'),
    'changed', v_changed);
end;
$$;
revoke all on function public.app_set_avatar_preset(uuid, smallint) from public, anon, authenticated;
grant execute on function public.app_set_avatar_preset(uuid, smallint) to service_role;

-- ---- 4. the photo commands: a photo replaces a preset; clearing clears both (bodies of 20261108100000 + avatar_id)
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
  v_changed := v_p.avatar_url is distinct from p_path or v_p.avatar_id is not null;
  if v_changed then
    update public.perfiles_usuario set avatar_url = p_path, avatar_id = null where usuario_id = p_usuario_id;   -- a photo replaces a preset (FA53)
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
  if v_p.avatar_url is not null or v_p.avatar_id is not null then
    update public.perfiles_usuario set avatar_url = null, avatar_id = null where usuario_id = p_usuario_id;
  end if;
  return jsonb_build_object(
    'previousPath', private.avatar_visible_path(p_usuario_id, v_p.avatar_url, 'activa'),
    'changed', v_p.avatar_url is not null or v_p.avatar_id is not null);
end;
$$;
revoke all on function public.app_clear_avatar(uuid) from public, anon, authenticated;
grant execute on function public.app_clear_avatar(uuid) to service_role;

-- ---- 5. ranking top3: avatarId next to avatarPath, same gate (still public and active). Body of 20261108100000 + avatarId.
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
                                                 'avatarPath', case when tp.ranking_publico then private.avatar_visible_path(t.usuario_id, tp.avatar_url, tp.estado_cuenta) end,
                                                 'avatarId', case when tp.ranking_publico then private.avatar_visible_id(tp.avatar_id, tp.estado_cuenta) end)
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

-- ---- 6. forum autor: avatarId next to avatarPath (active account; null for a tombstone). Bodies of 20261108100000 + avatarId.
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
           private.avatar_visible_path(pf.usuario_id, pf.avatar_url, pf.estado_cuenta) as avatar_path, private.avatar_visible_id(pf.avatar_id, pf.estado_cuenta) as avatar_id,
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
             'autor', jsonb_build_object('handle', f.username, 'avatarPath', f.avatar_path, 'avatarId', f.avatar_id), 'meGusta', f.n_me_gusta, 'comentarios', f.n_comentarios, 'creadoEn', f.creado_en,
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
  select p.*, pf.username, c.solo_lectura, private.avatar_visible_path(pf.usuario_id, pf.avatar_url, pf.estado_cuenta) as autor_avatar_path,
         private.avatar_visible_id(pf.avatar_id, pf.estado_cuenta) as autor_avatar_id into r
    from public.publicaciones_oposired p
    join public.categorias_oposired c on c.id = p.categoria_id and c.activa and (c.especialidad_id is null or c.especialidad_id = v_esp)
    left join public.perfiles_usuario pf on pf.usuario_id = p.usuario_id
   where p.id = p_id and v_esp is not null and private.config_int('foro_activo', 1) = 1
     and (p.estado = 'publicada' or (p.estado = 'pendiente_moderacion' and p.usuario_id = p_usuario));
  if not found then
    raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND';
  end if;
  return jsonb_build_object('id', r.id, 'categoriaId', r.categoria_id, 'titulo', r.titulo, 'cuerpo', r.contenido,
           'autor', jsonb_build_object('handle', r.username, 'avatarPath', r.autor_avatar_path, 'avatarId', r.autor_avatar_id), 'meGusta', r.n_me_gusta, 'comentarios', r.n_comentarios, 'creadoEn', r.creado_en,
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
           private.avatar_visible_path(pf.usuario_id, pf.avatar_url, pf.estado_cuenta) as avatar_path, private.avatar_visible_id(pf.avatar_id, pf.estado_cuenta) as avatar_id,
           exists (select 1 from public.reacciones_oposired r where r.comentario_id = c.id and r.usuario_id = p_usuario and r.tipo_reaccion = 'me_gusta') as mi_voto
      from public.comentarios_oposired c left join public.perfiles_usuario pf on pf.usuario_id = c.usuario_id
     where c.publicacion_id = p_post and (c.estado = 'publicado' or (c.estado = 'pendiente_moderacion' and c.usuario_id = p_usuario))
       and (k is null or (c.creado_en, c.id) > (k[1]::timestamptz, k[2]::uuid))
     order by c.creado_en, c.id limit v_lim
  ), agg as (
    select coalesce(jsonb_agg(jsonb_build_object('id', f.id, 'publicacionId', p_post, 'cuerpo', f.contenido, 'autor', jsonb_build_object('handle', f.username, 'avatarPath', f.avatar_path, 'avatarId', f.avatar_id), 'meGusta', f.n_me_gusta,
             'creadoEn', f.creado_en, 'miVoto', f.mi_voto, 'enRevision', f.estado = 'pendiente_moderacion', 'esMio', f.usuario_id = p_usuario) order by f.creado_en, f.id), '[]'::jsonb) as items, count(*)::integer as n from page f
  ), ultimo as (select f.id, f.creado_en from page f order by f.creado_en desc, f.id desc limit 1)
  select agg.items, agg.n, u.id, u.creado_en into v_items, v_n, v_last_id, v_last_ts from agg left join ultimo u on true;
  return jsonb_build_object('items', v_items, 'nextCursor', case when v_n >= v_lim then private.cur_enc(array[v_last_ts::text, v_last_id::text]) end);
end;
$$;
revoke all on function public.app_foro_comments(uuid, uuid, text, integer) from public, anon, authenticated;
grant execute on function public.app_foro_comments(uuid, uuid, text, integer) to service_role;

commit;
