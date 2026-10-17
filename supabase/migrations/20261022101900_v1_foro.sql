-- V1 WAVE 5b: FORO / RED (text and images). Contract: E1-V1-W5-FORO-CONTRACT.md (FA35). Design: E4-V1-FORO-DESIGN v3.1 + E1-V1-FORO-MIGRATION-NOTES (M-F1..M-F10).
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Requires 20261022101200 (config), 20261022101500 (config bounds + audit) and 20261022101800 (private.referral_mail).
-- BLOCKER M-F3: MAIN lets any authenticated client write the forum tables straight through PostgREST. This migration REVOKES client INSERT/UPDATE/DELETE (and client SELECT: every read goes through the server, so the
-- specialty gate cannot be bypassed) on posts, comments, reactions, reports and moderation actions, drops their policies, and routes every write through service-role functions.
-- BLOCKER M-F5: the posting restriction lives in a SERVER-ONLY table (restricciones_foro), checked inside every write function; never a column a client can update.
-- Names are MAPPED to MAIN, not renamed (Control's reads keep working): cuerpo = contenido, visible = estado 'publicada'/'publicado', autor = usuario_id (ON DELETE SET NULL = the tombstone).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate)
-- ROLLBACK-BEGIN
-- begin;
-- drop trigger if exists trg_reacciones_oposired_contadores on public.reacciones_oposired;
-- drop trigger if exists trg_comentarios_oposired_contadores on public.comentarios_oposired;
-- drop function if exists private.tg_foro_reacciones_contadores();
-- drop function if exists private.tg_foro_comentarios_contadores();
-- drop function if exists public.app_foro_images_purge_failed(uuid[]);
-- drop function if exists public.app_foro_images_purged(uuid[]);
-- drop function if exists public.app_foro_images_to_purge(integer);
-- drop function if exists public.app_foro_image_path(uuid, uuid);
-- drop function if exists public.app_foro_image_status(uuid, uuid);
-- drop function if exists public.app_foro_image_reject(uuid, uuid, text);
-- drop function if exists public.app_foro_image_commit(uuid, uuid, text, integer, integer, integer);
-- drop function if exists public.app_foro_image_init(uuid, text, integer);
-- drop function if exists private.foro_retire_images(uuid, uuid);
-- drop function if exists public.app_job_foro();
-- drop function if exists public.foro_reconcile();
-- drop function if exists public.foro_tombstone_user(uuid);
-- drop function if exists public.admin_foro_moderate(uuid, text, uuid, text, integer, text);
-- drop function if exists public.app_foro_report(uuid, text, uuid, text, text);
-- drop function if exists public.app_foro_vote(uuid, text, uuid, boolean);
-- drop function if exists public.app_foro_delete_own(uuid, text, uuid);
-- drop function if exists public.app_foro_create_comment(uuid, uuid, text, text);
-- drop function if exists public.app_foro_create_post(uuid, uuid, text, text, text, uuid);
-- drop function if exists public.app_foro_comments(uuid, uuid, text, integer);
-- drop function if exists public.app_foro_post(uuid, uuid);
-- drop function if exists public.app_foro_list(uuid, uuid, text, text, integer);
-- drop function if exists public.app_foro_categorias(uuid);
-- drop function if exists private.foro_check_write(uuid, text);
-- drop function if exists private.foro_rate(uuid, text, interval, integer);
-- drop function if exists private.foro_clean_text(text, integer, boolean);
-- drop function if exists private.foro_has_link(text);
-- drop function if exists private.foro_account_info(uuid);
-- drop function if exists private.foro_user_specialty(uuid);
-- drop function if exists private.cur_dec(text);
-- drop function if exists private.cur_enc(text[]);
-- drop index if exists public.ux_publicaciones_oposired_imagen;
-- alter table public.publicaciones_oposired drop column if exists imagen_id;
-- drop table if exists public.imagenes_oposired;
-- drop table if exists public.foro_bloqueos;
-- drop table if exists public.foro_actividad;
-- drop table if exists public.moderacion_autor;
-- drop table if exists public.restricciones_foro;
-- drop index if exists public.ux_reportes_contenido_unico;
-- drop index if exists public.ix_publicaciones_oposired_lista;
-- drop index if exists public.ix_publicaciones_oposired_votadas;
-- drop index if exists public.ix_publicaciones_oposired_hash;
-- drop index if exists public.ix_comentarios_oposired_lista;
-- drop index if exists public.ux_publicaciones_oposired_idem;
-- drop index if exists public.ux_comentarios_oposired_idem;
-- alter table public.reportes_contenido drop column if exists motivo_codigo;
-- alter table public.reacciones_oposired drop column if exists cuenta;
-- alter table public.comentarios_oposired drop constraint if exists comentarios_oposired_plano_chk;
-- alter table public.comentarios_oposired drop column if exists hash_cuerpo, drop column if exists clave_idem, drop column if exists n_me_gusta;
-- alter table public.publicaciones_oposired drop column if exists hash_cuerpo, drop column if exists clave_idem, drop column if exists n_me_gusta, drop column if exists n_comentarios;
-- alter table public.categorias_oposired drop column if exists especialidad_id, drop column if exists solo_lectura;
-- delete from public.configuracion_v1 where clave like 'foro\_%';
-- grant delete, insert, maintain, select, update on public.reportes_contenido, public.acciones_moderacion to authenticated;
-- grant maintain, select on public.reportes_contenido, public.acciones_moderacion to anon;
-- revoke all on public.publicaciones_oposired, public.comentarios_oposired, public.reacciones_oposired, public.categorias_oposired from service_role;
-- grant all on public.publicaciones_oposired, public.comentarios_oposired, public.reacciones_oposired, public.categorias_oposired to anon, authenticated, service_role;
-- create policy categorias_oposired_select_publico on public.categorias_oposired for select to anon, authenticated using (activa = true or public.es_admin());
-- create policy publicaciones_oposired_select_publico on public.publicaciones_oposired for select to anon, authenticated using (estado = 'publicada' or public.es_moderador_o_admin());
-- create policy publicaciones_oposired_insert_auth on public.publicaciones_oposired for insert to authenticated with check (usuario_id = auth.uid());
-- create policy publicaciones_oposired_update_propias on public.publicaciones_oposired for update to authenticated using (usuario_id = auth.uid() or public.es_moderador_o_admin()) with check (usuario_id = auth.uid() or public.es_moderador_o_admin());
-- create policy comentarios_oposired_select_publico on public.comentarios_oposired for select to anon, authenticated using (estado = 'publicado' or public.es_moderador_o_admin());
-- create policy comentarios_oposired_insert_auth on public.comentarios_oposired for insert to authenticated with check (usuario_id = auth.uid());
-- create policy comentarios_oposired_update_propios on public.comentarios_oposired for update to authenticated using (usuario_id = auth.uid() or public.es_moderador_o_admin()) with check (usuario_id = auth.uid() or public.es_moderador_o_admin());
-- create policy reacciones_oposired_todo_propias on public.reacciones_oposired for all to authenticated using (usuario_id = auth.uid() or public.es_moderador_o_admin()) with check (usuario_id = auth.uid() or public.es_moderador_o_admin());
-- create policy reportes_contenido_insert_auth on public.reportes_contenido for insert to authenticated with check (reportado_por = (select auth.uid()) and estado = 'pendiente' and revisado_por is null and revisado_en is null and tickets_otorgados = 0 and recompensa_entregada = false and accion_pendiente = false and creado_en = now() and actualizado_en = now());
-- create policy reportes_contenido_select_staff on public.reportes_contenido for select to authenticated using (public.es_moderador_o_admin());
-- create policy acciones_moderacion_staff_all on public.acciones_moderacion for all to authenticated using (public.es_moderador_o_admin()) with check (public.es_moderador_o_admin());
-- alter table public.publicaciones_oposired drop constraint if exists publicaciones_oposired_estado_check;
-- alter table public.publicaciones_oposired add constraint publicaciones_oposired_estado_check check (estado in ('borrador', 'publicada', 'oculta', 'eliminada'));
-- alter table public.comentarios_oposired drop constraint if exists comentarios_oposired_estado_check;
-- alter table public.comentarios_oposired add constraint comentarios_oposired_estado_check check (estado in ('publicado', 'oculto', 'eliminado'));
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'foro_client_writes_open:' || t.n,
--        (case when has_table_privilege('anon', t.n, 'insert') or has_table_privilege('authenticated', t.n, 'insert') or has_table_privilege('authenticated', t.n, 'update') or has_table_privilege('authenticated', t.n, 'delete')
--                or has_table_privilege('anon', t.n, 'select') or has_table_privilege('authenticated', t.n, 'select') then 1 else 0 end)::bigint as failures
--   from (values ('public.publicaciones_oposired'), ('public.comentarios_oposired'), ('public.reacciones_oposired'), ('public.reportes_contenido'), ('public.acciones_moderacion'), ('public.categorias_oposired')) t(n)
-- union all
-- select 'foro_server_tables_client_access:' || t.n,
--        (case when has_table_privilege('anon', t.n, 'select') or has_table_privilege('authenticated', t.n, 'select') or has_table_privilege('authenticated', t.n, 'insert')
--                or has_table_privilege('authenticated', t.n, 'update') or has_table_privilege('authenticated', t.n, 'delete') or has_table_privilege('service_role', t.n, 'select')
--                or not (select c.relrowsecurity from pg_class c where c.oid = t.n::regclass) then 1 else 0 end)::bigint
--   from (values ('public.restricciones_foro'), ('public.moderacion_autor'), ('public.foro_actividad'), ('public.foro_bloqueos'), ('public.imagenes_oposired')) t(n)
-- union all
-- select 'foro_functions_not_service_role_only:' || f.n,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute') or not has_function_privilege('service_role', f.n, 'execute') then 1 else 0 end)::bigint
--   from (values ('public.app_foro_categorias(uuid)'), ('public.app_foro_list(uuid,uuid,text,text,integer)'), ('public.app_foro_post(uuid,uuid)'), ('public.app_foro_comments(uuid,uuid,text,integer)'),
--                ('public.app_foro_create_post(uuid,uuid,text,text,text,uuid)'), ('public.app_foro_image_init(uuid,text,integer)'), ('public.app_foro_image_commit(uuid,uuid,text,integer,integer,integer)'), ('public.app_foro_image_reject(uuid,uuid,text)'),
--                ('public.app_foro_image_status(uuid,uuid)'), ('public.app_foro_image_path(uuid,uuid)'), ('public.app_foro_images_to_purge(integer)'), ('public.app_foro_images_purged(uuid[])'), ('public.app_foro_images_purge_failed(uuid[])'), ('public.app_foro_create_comment(uuid,uuid,text,text)'), ('public.app_foro_delete_own(uuid,text,uuid)'), ('public.app_foro_vote(uuid,text,uuid,boolean)'),
--                ('public.app_foro_report(uuid,text,uuid,text,text)'), ('public.admin_foro_moderate(uuid,text,uuid,text,integer,text)'), ('public.foro_tombstone_user(uuid)'), ('public.app_job_foro()'), ('public.foro_reconcile()')) f(n);
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regclass('public.publicaciones_oposired') is null or to_regclass('public.comentarios_oposired') is null or to_regclass('public.reacciones_oposired') is null
     or to_regclass('public.categorias_oposired') is null or to_regclass('public.reportes_contenido') is null or to_regclass('public.acciones_moderacion') is null
     or to_regprocedure('private.referral_mail(uuid)') is null or to_regprocedure('private.config_int(text,integer)') is null then
    raise exception using errcode = 'P0001', message = 'V1_FORO_PREFLIGHT', hint = 'apply 20261022101800 first and load the MAIN forum shapes (main_shape_subset_extra_engagement.sql)';
  end if;
end
$$;

-- ---- 1. configuration (Control; bounded and audited by 20261022101500)
insert into public.configuracion_v1 (clave, valor_integer) values
  ('foro_activo', 1), ('foro_edad_min_horas', 24), ('foro_posts_hora', 5), ('foro_posts_dia', 20), ('foro_comentarios_hora', 30), ('foro_reportes_dia', 20),
  ('foro_votos_min', 60), ('foro_votos_dia', 500), ('foro_premoderacion_posts', 3), ('foro_titulo_max', 120), ('foro_cuerpo_max', 4000), ('foro_comentario_max', 1000),
  ('foro_dup_minutos', 10), ('foro_dup_cruzado_minutos', 60), ('foro_autoocultar_k', 0), ('foro_imagenes_hora', 5), ('foro_imagen_urls_hora', 120)
on conflict (clave) do nothing;

-- ---- 2. shape (M-F1, M-F2, M-F6): additive columns, mapped states
alter table public.categorias_oposired add column if not exists especialidad_id uuid references public.especialidades(id) on delete set null, add column if not exists solo_lectura boolean not null default false;
alter table public.publicaciones_oposired add column if not exists hash_cuerpo text, add column if not exists clave_idem text, add column if not exists n_me_gusta integer not null default 0, add column if not exists n_comentarios integer not null default 0;
alter table public.comentarios_oposired add column if not exists hash_cuerpo text, add column if not exists clave_idem text, add column if not exists n_me_gusta integer not null default 0;
alter table public.reacciones_oposired add column if not exists cuenta boolean not null default true;
alter table public.reportes_contenido add column if not exists motivo_codigo text;
create index if not exists ix_publicaciones_oposired_lista on public.publicaciones_oposired (categoria_id, estado, creado_en desc, id desc);
create index if not exists ix_publicaciones_oposired_votadas on public.publicaciones_oposired (categoria_id, estado, n_me_gusta desc, creado_en desc, id desc);
create index if not exists ix_comentarios_oposired_lista on public.comentarios_oposired (publicacion_id, estado, creado_en, id);
create index if not exists ix_publicaciones_oposired_hash on public.publicaciones_oposired (hash_cuerpo, creado_en desc) where hash_cuerpo is not null;
create unique index if not exists ux_publicaciones_oposired_idem on public.publicaciones_oposired (usuario_id, clave_idem) where clave_idem is not null;
create unique index if not exists ux_comentarios_oposired_idem on public.comentarios_oposired (usuario_id, clave_idem) where clave_idem is not null;
-- one report per reporter and entity for every NEW report (rows from before the migration have no motivo_codigo and are left alone)
create unique index if not exists ux_reportes_contenido_unico on public.reportes_contenido (reportado_por, tipo_entidad, entidad_id) where motivo_codigo is not null and reportado_por is not null;
do $$
declare c record;
begin
  for c in select conrelid::regclass as t, conname from pg_constraint
            where contype = 'c' and conrelid in ('public.publicaciones_oposired'::regclass, 'public.comentarios_oposired'::regclass, 'public.reportes_contenido'::regclass)
              and (pg_get_constraintdef(oid) like '%estado%' and conrelid <> 'public.reportes_contenido'::regclass) loop
    execute format('alter table %s drop constraint %I', c.t, c.conname);
  end loop;
end
$$;
alter table public.publicaciones_oposired add constraint publicaciones_oposired_estado_check check (estado in ('borrador', 'publicada', 'pendiente_moderacion', 'oculta', 'eliminada'));
alter table public.comentarios_oposired add constraint comentarios_oposired_estado_check check (estado in ('publicado', 'pendiente_moderacion', 'oculto', 'eliminado'));
alter table public.comentarios_oposired drop constraint if exists comentarios_oposired_plano_chk;
alter table public.comentarios_oposired add constraint comentarios_oposired_plano_chk check (comentario_padre_id is null) not valid;   -- comments are FLAT from now on; old nested rows stay untouched
alter table public.reportes_contenido drop constraint if exists reportes_contenido_motivo_codigo_chk;
alter table public.reportes_contenido add constraint reportes_contenido_motivo_codigo_chk check (motivo_codigo is null or motivo_codigo in ('spam', 'ofensivo', 'incorrecto', 'otro')) not valid;
alter table public.reportes_contenido validate constraint reportes_contenido_motivo_codigo_chk;

-- ---- 3. server-only tables (RLS on, no grant, no policy)
create table if not exists public.restricciones_foro (
  usuario_id uuid primary key references auth.users(id) on delete cascade,
  hasta timestamptz not null,
  motivo text,
  por uuid,
  creado_en timestamptz not null default clock_timestamp()
);
create table if not exists public.moderacion_autor (
  usuario_id uuid primary key references auth.users(id) on delete cascade,
  aprobadas integer not null default 0 check (aprobadas >= 0)
);
create table if not exists public.foro_actividad (
  id bigint generated always as identity primary key,
  usuario_id uuid not null,
  tipo text not null check (tipo in ('post', 'comentario', 'voto', 'reporte', 'imagen', 'imagen_url')),
  creado_en timestamptz not null default clock_timestamp()
);
create index if not exists ix_foro_actividad on public.foro_actividad (usuario_id, tipo, creado_en desc);
create table if not exists public.foro_bloqueos (
  patron text primary key check (patron = lower(patron) and char_length(patron) between 2 and 100)
);
-- M-F7 / N-F1: DEDICATED image table (never the exam-media tables/bucket). The path is generated by the server; the bytes are re-encoded by Ktor before they are stored.
create table if not exists public.imagenes_oposired (
  id uuid primary key default gen_random_uuid(),
  usuario_id uuid not null references auth.users(id) on delete cascade,
  path text not null,
  mime text not null check (mime in ('image/jpeg', 'image/png')),
  bytes integer not null check (bytes between 1 and 3145728),
  sha256 text,
  ancho integer,
  alto integer,
  estado text not null default 'subida' check (estado in ('subida', 'verificada', 'rechazada', 'retirada')),
  version integer not null default 1,
  creado_en timestamptz not null default clock_timestamp(),
  verificada_en timestamptz,
  retirada_en timestamptz,
  purgada_en timestamptz,
  intentos_purga integer not null default 0,
  motivo text
);
create index if not exists ix_imagenes_oposired_usuario on public.imagenes_oposired (usuario_id, estado, creado_en desc);
alter table public.imagenes_oposired add column if not exists intentos_purga integer not null default 0;
create index if not exists ix_imagenes_oposired_barrido on public.imagenes_oposired (estado, creado_en) where estado in ('subida', 'verificada', 'retirada') and purgada_en is null;
alter table public.publicaciones_oposired add column if not exists imagen_id uuid references public.imagenes_oposired(id) on delete set null;
create unique index if not exists ux_publicaciones_oposired_imagen on public.publicaciones_oposired (imagen_id) where imagen_id is not null;
alter table public.imagenes_oposired enable row level security;
revoke all on table public.imagenes_oposired from public, anon, authenticated, service_role;
alter table public.restricciones_foro enable row level security;
alter table public.moderacion_autor enable row level security;
alter table public.foro_actividad enable row level security;
alter table public.foro_bloqueos enable row level security;
revoke all on table public.restricciones_foro, public.moderacion_autor, public.foro_actividad, public.foro_bloqueos from public, anon, authenticated, service_role;

-- ---- 4. M-F3 / M-F4 BLOCKER: no client write, no client read (every read goes through the server and its specialty gate)
drop policy if exists categorias_oposired_select_publico on public.categorias_oposired;
drop policy if exists publicaciones_oposired_select_publico on public.publicaciones_oposired;
drop policy if exists publicaciones_oposired_insert_auth on public.publicaciones_oposired;
drop policy if exists publicaciones_oposired_update_propias on public.publicaciones_oposired;
drop policy if exists comentarios_oposired_select_publico on public.comentarios_oposired;
drop policy if exists comentarios_oposired_insert_auth on public.comentarios_oposired;
drop policy if exists comentarios_oposired_update_propios on public.comentarios_oposired;
drop policy if exists reacciones_oposired_todo_propias on public.reacciones_oposired;
drop policy if exists reportes_contenido_insert_auth on public.reportes_contenido;
drop policy if exists reportes_contenido_select_staff on public.reportes_contenido;
drop policy if exists acciones_moderacion_staff_all on public.acciones_moderacion;
revoke all on table public.categorias_oposired, public.publicaciones_oposired, public.comentarios_oposired, public.reacciones_oposired, public.reportes_contenido, public.acciones_moderacion from anon, authenticated;

-- ---- 5. counters by trigger (M-F1/M-F2). n_me_gusta counts only votes of accounts older than the age limit (cuenta = true): the ordering by votes ignores young accounts.
create or replace function private.tg_foro_comentarios_contadores()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if tg_op = 'INSERT' then
    if new.estado = 'publicado' then update public.publicaciones_oposired set n_comentarios = n_comentarios + 1 where id = new.publicacion_id; end if;
  elsif tg_op = 'UPDATE' then
    if old.estado = 'publicado' and new.estado <> 'publicado' then update public.publicaciones_oposired set n_comentarios = greatest(n_comentarios - 1, 0) where id = new.publicacion_id;
    elsif old.estado <> 'publicado' and new.estado = 'publicado' then update public.publicaciones_oposired set n_comentarios = n_comentarios + 1 where id = new.publicacion_id; end if;
  elsif tg_op = 'DELETE' then
    if old.estado = 'publicado' then update public.publicaciones_oposired set n_comentarios = greatest(n_comentarios - 1, 0) where id = old.publicacion_id; end if;
  end if;
  return null;
end;
$$;
revoke all on function private.tg_foro_comentarios_contadores() from public, anon, authenticated;
drop trigger if exists trg_comentarios_oposired_contadores on public.comentarios_oposired;
create trigger trg_comentarios_oposired_contadores after insert or update of estado or delete on public.comentarios_oposired for each row execute function private.tg_foro_comentarios_contadores();

create or replace function private.tg_foro_reacciones_contadores()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare r public.reacciones_oposired%rowtype; d integer;
begin
  r := case when tg_op = 'DELETE' then old else new end;
  d := case when tg_op = 'DELETE' then -1 else 1 end;
  if r.tipo_reaccion = 'me_gusta' and r.cuenta then
    if r.publicacion_id is not null then update public.publicaciones_oposired set n_me_gusta = greatest(n_me_gusta + d, 0) where id = r.publicacion_id;
    else update public.comentarios_oposired set n_me_gusta = greatest(n_me_gusta + d, 0) where id = r.comentario_id; end if;
  end if;
  return null;
end;
$$;
revoke all on function private.tg_foro_reacciones_contadores() from public, anon, authenticated;
drop trigger if exists trg_reacciones_oposired_contadores on public.reacciones_oposired;
create trigger trg_reacciones_oposired_contadores after insert or delete on public.reacciones_oposired for each row execute function private.tg_foro_reacciones_contadores();

-- backfill of rows that existed before (idempotent)
update public.publicaciones_oposired p set n_comentarios = (select count(*) from public.comentarios_oposired c where c.publicacion_id = p.id and c.estado = 'publicado'),
       n_me_gusta = (select count(*) from public.reacciones_oposired r where r.publicacion_id = p.id and r.tipo_reaccion = 'me_gusta' and r.cuenta);
update public.comentarios_oposired c set n_me_gusta = (select count(*) from public.reacciones_oposired r where r.comentario_id = c.id and r.tipo_reaccion = 'me_gusta' and r.cuenta);

-- ---- 6. helpers
create or replace function private.cur_enc(p_parts text[])
returns text
language sql
immutable
set search_path = ''
as $$ select translate(replace(encode(convert_to(array_to_string(p_parts, '|'), 'utf8'), 'base64'), E'\n', ''), '+/', '-_') $$;
revoke all on function private.cur_enc(text[]) from public, anon, authenticated;

create or replace function private.cur_dec(p_cursor text)
returns text[]
language plpgsql
immutable
set search_path = ''
as $$
begin
  if p_cursor is null then return null; end if;
  return string_to_array(convert_from(decode(translate(p_cursor, '-_', '+/'), 'base64'), 'utf8'), '|');
exception when others then
  raise exception using errcode = 'P0001', message = 'CURSOR_INVALID';
end;
$$;
revoke all on function private.cur_dec(text) from public, anon, authenticated;

create or replace function private.foro_user_specialty(p_usuario uuid)
returns uuid
language sql
stable
security definer
set search_path = ''
as $$ select pf.especialidad_principal_id from public.perfiles_usuario pf where pf.usuario_id = p_usuario and pf.estado_cuenta = 'activa' $$;
revoke all on function private.foro_user_specialty(uuid) from public, anon, authenticated;

-- the only reader of auth.users for the forum (e-mail verified + account age)
create or replace function private.foro_account_info(p_usuario uuid)
returns table(confirmado timestamptz, creado timestamptz)
language sql
stable
security definer
set search_path = ''
as $$ select u.email_confirmed_at, u.created_at from auth.users u where u.id = p_usuario $$;
revoke all on function private.foro_account_info(uuid) from public, anon, authenticated;

-- link detection with obfuscation: schemes, www, hxxp, spaced / bracketed / Unicode dots before a TLD, shorteners, Control block list
create or replace function private.foro_has_link(p_text text)
returns boolean
language sql
stable
set search_path = ''
as $$
  with t as (select lower(translate(coalesce(p_text, ''), E'。．․｡•·', '......')) as s)
  select s ~ '(https?|hxxps?|ftp)\s*:\s*/\s*/' or s ~ '\mwww\s*\.' or s ~ '[a-z0-9-]+\s*(\.|\[\.\]|\(\.\)|\{\.\}|\s+dot\s+|\s+punto\s+)\s*(com|net|org|es|io|me|ly|gl|co|info|xyz|ru|cn|app|dev|link|click|top|site|online|tk|ml|ga|cf|gq|to|cc|ws|us|uk|de|fr|it|pt|eu)\M'
         or s ~ '\m(bit\.ly|tinyurl|goo\.gl|t\.co|is\.gd|cutt\.ly|shorturl|wa\.me|t\.me|discord\.gg)\M'
         or exists (select 1 from public.foro_bloqueos b where position(b.patron in s) > 0)
    from t
$$;
revoke all on function private.foro_has_link(text) from public, anon, authenticated;

-- NFC; strip C0/C1 (except newline and tab), bidi overrides/isolates and zero-width/format characters; ZWJ is REJECTED (owner 2026-10-01); collapse blank runs; trim; caps in code points
create or replace function private.foro_clean_text(p_text text, p_max integer, p_allow_newlines boolean default true)
returns text
language plpgsql
stable
set search_path = ''
as $$
declare v text := normalize(coalesce(p_text, ''), nfc);
begin
  if v ~ E'‍' then
    raise exception using errcode = 'P0001', message = 'CONTENT_REJECTED';
  end if;
  v := regexp_replace(v, E'[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f-\\x9f]', '', 'g');
  v := regexp_replace(v, E'[​-‏‪-‮⁠-⁩﻿­]', '', 'g');
  v := regexp_replace(v, E'\r\n?', E'\n', 'g');
  if not p_allow_newlines then v := regexp_replace(v, E'\\s+', ' ', 'g'); end if;
  v := regexp_replace(regexp_replace(v, E'\n{3,}', E'\n\n', 'g'), E'^\\s+|\\s+$', '', 'g');
  if char_length(v) = 0 or char_length(v) > p_max or private.foro_has_link(v) then
    raise exception using errcode = 'P0001', message = 'CONTENT_REJECTED';
  end if;
  return v;
end;
$$;
revoke all on function private.foro_clean_text(text, integer, boolean) from public, anon, authenticated;

-- DB-authoritative rate limit (survives restarts and several instances)
create or replace function private.foro_rate(p_usuario uuid, p_tipo text, p_ventana interval, p_max integer)
returns void
language plpgsql
volatile
set search_path = ''
as $$
declare v_n integer; v_old timestamptz;
begin
  select count(*), min(a.creado_en) into v_n, v_old from public.foro_actividad a where a.usuario_id = p_usuario and a.tipo = p_tipo and a.creado_en > clock_timestamp() - p_ventana;
  if v_n >= p_max then
    raise exception using errcode = 'P0001', message = 'FORO_RATE_LIMITED', detail = format('{"retryAfter":%s}', greatest(1, ceil(extract(epoch from (v_old + p_ventana - clock_timestamp())))::integer));
  end if;
end;
$$;
revoke all on function private.foro_rate(uuid, text, interval, integer) from public, anon, authenticated;

-- write gate shared by post / comment: forum on, specialty set, e-mail verified + age, not restricted. The restriction answers 403 only AFTER the specialty gate and never says why.
create or replace function private.foro_check_write(p_usuario uuid, p_tipo text)
returns void
language plpgsql
volatile
set search_path = ''
as $$
declare v_conf timestamptz; v_creado timestamptz;
begin
  if private.config_int('foro_activo', 1) = 0 or private.foro_user_specialty(p_usuario) is null then
    raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND';
  end if;
  select i.confirmado, i.creado into v_conf, v_creado from private.foro_account_info(p_usuario) i;
  if v_conf is null or v_creado > clock_timestamp() - make_interval(hours => private.config_int('foro_edad_min_horas', 24))
     or exists (select 1 from public.restricciones_foro r where r.usuario_id = p_usuario and r.hasta > clock_timestamp()) then
    raise exception using errcode = 'P0001', message = 'POSTING_RESTRICTED';
  end if;
end;
$$;
revoke all on function private.foro_check_write(uuid, text) from public, anon, authenticated;

-- ---- 7. reads (the gate: a channel is usable when active and (no specialty scope or the caller's specialty); anything else answers FORO_NOT_FOUND)
create or replace function public.app_foro_categorias(p_usuario uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare v_esp uuid := private.foro_user_specialty(p_usuario);
begin
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  if v_esp is null or private.config_int('foro_activo', 1) = 0 then
    return jsonb_build_object('items', '[]'::jsonb);
  end if;
  return jsonb_build_object('items', coalesce((
    select jsonb_agg(jsonb_build_object('id', c.id, 'nombre', c.nombre, 'slug', c.slug, 'descripcion', c.descripcion, 'posicion', c.posicion, 'soloLectura', c.solo_lectura) order by c.posicion, c.nombre)
      from public.categorias_oposired c where c.activa and (c.especialidad_id is null or c.especialidad_id = v_esp)), '[]'::jsonb));
end;
$$;
revoke all on function public.app_foro_categorias(uuid) from public, anon, authenticated;
grant execute on function public.app_foro_categorias(uuid) to service_role;

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
             'autor', jsonb_build_object('handle', f.username), 'meGusta', f.n_me_gusta, 'comentarios', f.n_comentarios, 'creadoEn', f.creado_en,
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

-- a post is readable by the caller: its channel passes the gate and it is published, or it is the caller's own pending post. Same "not found" for everything else.
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
  select p.*, pf.username, c.solo_lectura into r
    from public.publicaciones_oposired p
    join public.categorias_oposired c on c.id = p.categoria_id and c.activa and (c.especialidad_id is null or c.especialidad_id = v_esp)
    left join public.perfiles_usuario pf on pf.usuario_id = p.usuario_id
   where p.id = p_id and v_esp is not null and private.config_int('foro_activo', 1) = 1
     and (p.estado = 'publicada' or (p.estado = 'pendiente_moderacion' and p.usuario_id = p_usuario));
  if not found then
    raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND';
  end if;
  return jsonb_build_object('id', r.id, 'categoriaId', r.categoria_id, 'titulo', r.titulo, 'cuerpo', r.contenido,
           'autor', jsonb_build_object('handle', r.username), 'meGusta', r.n_me_gusta, 'comentarios', r.n_comentarios, 'creadoEn', r.creado_en,
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
           exists (select 1 from public.reacciones_oposired r where r.comentario_id = c.id and r.usuario_id = p_usuario and r.tipo_reaccion = 'me_gusta') as mi_voto
      from public.comentarios_oposired c left join public.perfiles_usuario pf on pf.usuario_id = c.usuario_id
     where c.publicacion_id = p_post and (c.estado = 'publicado' or (c.estado = 'pendiente_moderacion' and c.usuario_id = p_usuario))
       and (k is null or (c.creado_en, c.id) > (k[1]::timestamptz, k[2]::uuid))
     order by c.creado_en, c.id limit v_lim
  ), agg as (
    select coalesce(jsonb_agg(jsonb_build_object('id', f.id, 'publicacionId', p_post, 'cuerpo', f.contenido, 'autor', jsonb_build_object('handle', f.username), 'meGusta', f.n_me_gusta,
             'creadoEn', f.creado_en, 'miVoto', f.mi_voto, 'enRevision', f.estado = 'pendiente_moderacion', 'esMio', f.usuario_id = p_usuario) order by f.creado_en, f.id), '[]'::jsonb) as items, count(*)::integer as n from page f
  ), ultimo as (select f.id, f.creado_en from page f order by f.creado_en desc, f.id desc limit 1)
  select agg.items, agg.n, u.id, u.creado_en into v_items, v_n, v_last_id, v_last_ts from agg left join ultimo u on true;
  return jsonb_build_object('items', v_items, 'nextCursor', case when v_n >= v_lim then private.cur_enc(array[v_last_ts::text, v_last_id::text]) end);
end;
$$;
revoke all on function public.app_foro_comments(uuid, uuid, text, integer) from public, anon, authenticated;
grant execute on function public.app_foro_comments(uuid, uuid, text, integer) to service_role;

-- ---- 8. writes
create or replace function public.app_foro_create_post(p_usuario uuid, p_categoria uuid, p_titulo text, p_cuerpo text, p_clave text, p_imagen uuid default null)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_esp uuid := private.foro_user_specialty(p_usuario);
  v_titulo text; v_cuerpo text; v_hash text; v_estado text; v_id uuid; v_prev record; v_cat record;
begin
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('foro:' || p_usuario::text, 0));
  -- replay of the same Idempotency-Key answers the stored post (authoritative even if the Ktor store lost the response)
  if p_clave is not null then
    select p.id into v_id from public.publicaciones_oposired p where p.usuario_id = p_usuario and p.clave_idem = p_clave;
    if found then return public.app_foro_post(p_usuario, v_id) || jsonb_build_object('repetida', true); end if;
  end if;
  select c.id, c.solo_lectura into v_cat from public.categorias_oposired c
   where c.id = p_categoria and c.activa and v_esp is not null and (c.especialidad_id is null or c.especialidad_id = v_esp);
  if not found then
    raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND';
  end if;
  perform private.foro_check_write(p_usuario, 'post');
  if v_cat.solo_lectura then
    raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND';
  end if;
  v_titulo := private.foro_clean_text(p_titulo, private.config_int('foro_titulo_max', 120), false);
  v_cuerpo := private.foro_clean_text(p_cuerpo, private.config_int('foro_cuerpo_max', 4000), true);
  perform private.foro_rate(p_usuario, 'post', interval '1 hour', private.config_int('foro_posts_hora', 5));
  perform private.foro_rate(p_usuario, 'post', interval '1 day', private.config_int('foro_posts_dia', 20));
  v_hash := encode(sha256(convert_to(lower(regexp_replace(v_titulo || ' ' || v_cuerpo, E'\\s+', ' ', 'g')), 'utf8')), 'hex');
  -- same user, same text in the channel within the window: 409 (a different key = a real second submit)
  if exists (select 1 from public.publicaciones_oposired p where p.usuario_id = p_usuario and p.categoria_id = p_categoria and p.hash_cuerpo = v_hash
              and p.estado in ('publicada', 'pendiente_moderacion') and p.creado_en > clock_timestamp() - make_interval(mins => private.config_int('foro_dup_minutos', 10))) then
    raise exception using errcode = 'P0001', message = 'DUPLICATE_POST';
  end if;
  -- pre-moderation: the first N posts of the account, or the same text posted by ANOTHER user recently (the poster is never told)
  v_estado := case
    when coalesce((select m.aprobadas from public.moderacion_autor m where m.usuario_id = p_usuario), 0) < private.config_int('foro_premoderacion_posts', 3) then 'pendiente_moderacion'
    when exists (select 1 from public.publicaciones_oposired p where p.hash_cuerpo = v_hash and p.usuario_id is distinct from p_usuario
                  and p.creado_en > clock_timestamp() - make_interval(mins => private.config_int('foro_dup_cruzado_minutos', 60))) then 'pendiente_moderacion'
    else 'publicada' end;
  -- an image may be attached only if it is VERIFIED, OWNED by the author and not used by another post (M-F: one post per image)
  if p_imagen is not null and not exists (select 1 from public.imagenes_oposired i where i.id = p_imagen and i.usuario_id = p_usuario and i.estado = 'verificada'
                                             and not exists (select 1 from public.publicaciones_oposired x where x.imagen_id = i.id)) then
    raise exception using errcode = 'P0001', message = 'IMAGE_NOT_USABLE';
  end if;
  insert into public.publicaciones_oposired (usuario_id, categoria_id, titulo, contenido, estado, hash_cuerpo, clave_idem, imagen_id, creado_en)
  values (p_usuario, p_categoria, v_titulo, v_cuerpo, v_estado, v_hash, p_clave, p_imagen, clock_timestamp()) returning id into v_id;   -- the real clock (not the transaction start) keeps the feed order stable
  insert into public.foro_actividad (usuario_id, tipo) values (p_usuario, 'post');
  return public.app_foro_post(p_usuario, v_id) || jsonb_build_object('repetida', false);
end;
$$;
revoke all on function public.app_foro_create_post(uuid, uuid, text, text, text, uuid) from public, anon, authenticated;
grant execute on function public.app_foro_create_post(uuid, uuid, text, text, text, uuid) to service_role;

create or replace function public.app_foro_create_comment(p_usuario uuid, p_post uuid, p_cuerpo text, p_clave text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare v_cuerpo text; v_id uuid; v_hash text; v_estado text := 'publicado'; v_post jsonb;
begin
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('foro:' || p_usuario::text, 0));
  if p_clave is not null then
    select c.id into v_id from public.comentarios_oposired c where c.usuario_id = p_usuario and c.clave_idem = p_clave;
    if found then return jsonb_build_object('id', v_id, 'repetido', true); end if;
  end if;
  v_post := public.app_foro_post(p_usuario, p_post);   -- the post gate (404 for unreadable / pending-of-others posts)
  if (v_post ->> 'enRevision')::boolean or (v_post ->> 'soloLectura')::boolean then
    raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND';
  end if;
  perform private.foro_check_write(p_usuario, 'comentario');
  v_cuerpo := private.foro_clean_text(p_cuerpo, private.config_int('foro_comentario_max', 1000), true);
  perform private.foro_rate(p_usuario, 'comentario', interval '1 hour', private.config_int('foro_comentarios_hora', 30));
  v_hash := encode(sha256(convert_to(lower(regexp_replace(v_cuerpo, E'\\s+', ' ', 'g')), 'utf8')), 'hex');
  if exists (select 1 from public.comentarios_oposired c where c.usuario_id = p_usuario and c.publicacion_id = p_post and c.hash_cuerpo = v_hash
              and c.creado_en > clock_timestamp() - make_interval(mins => private.config_int('foro_dup_minutos', 10))) then
    raise exception using errcode = 'P0001', message = 'DUPLICATE_POST';
  end if;
  insert into public.comentarios_oposired (publicacion_id, usuario_id, comentario_padre_id, contenido, estado, hash_cuerpo, clave_idem, creado_en)
  values (p_post, p_usuario, null, v_cuerpo, v_estado, v_hash, p_clave, clock_timestamp()) returning id into v_id;
  insert into public.foro_actividad (usuario_id, tipo) values (p_usuario, 'comentario');
  return jsonb_build_object('id', v_id, 'publicacionId', p_post, 'cuerpo', v_cuerpo, 'creadoEn', clock_timestamp(), 'repetido', false);
end;
$$;
revoke all on function public.app_foro_create_comment(uuid, uuid, text, text) from public, anon, authenticated;
grant execute on function public.app_foro_create_comment(uuid, uuid, text, text) to service_role;

-- retire the image of a post (or every image of a user): the object is deleted by the Ktor sweeper, the version bump kills cached URLs
create or replace function private.foro_retire_images(p_post uuid, p_usuario uuid)
returns void
language sql
volatile
set search_path = ''
as $$
  update public.imagenes_oposired i set estado = 'retirada', retirada_en = clock_timestamp(), version = i.version + 1, motivo = coalesce(i.motivo, 'retirada')
   where i.estado in ('verificada', 'subida') and (i.id = (select p.imagen_id from public.publicaciones_oposired p where p.id = p_post) or i.usuario_id = p_usuario)
$$;
revoke all on function private.foro_retire_images(uuid, uuid) from public, anon, authenticated;

-- delete OWN content (soft: the row stays for threads, reports and Control); the author comes from the JWT only
create or replace function public.app_foro_delete_own(p_usuario uuid, p_tipo text, p_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare v_n integer;
begin
  if p_tipo not in ('publicacion', 'comentario') then
    raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND';
  end if;
  if p_tipo = 'publicacion' then
    update public.publicaciones_oposired set estado = 'eliminada' where id = p_id and usuario_id = p_usuario and estado in ('publicada', 'pendiente_moderacion', 'oculta');
    if found then perform private.foro_retire_images(p_id, null); end if;
  else
    update public.comentarios_oposired set estado = 'eliminado' where id = p_id and usuario_id = p_usuario and estado in ('publicado', 'pendiente_moderacion', 'oculto');
  end if;
  get diagnostics v_n = row_count;
  if v_n = 0 then
    raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND';
  end if;
  return jsonb_build_object('eliminado', true);
end;
$$;
revoke all on function public.app_foro_delete_own(uuid, text, uuid) from public, anon, authenticated;
grant execute on function public.app_foro_delete_own(uuid, text, uuid) to service_role;

-- up-only vote (me_gusta), idempotent both ways; none on own / unreadable / pending / hidden content; young accounts vote with cuenta = false (ignored by the ordering)
create or replace function public.app_foro_vote(p_usuario uuid, p_tipo text, p_id uuid, p_on boolean)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare v_autor uuid; v_cuenta boolean; v_creado timestamptz; v_n integer;
begin
  if p_tipo not in ('publicacion', 'comentario') or p_on is null then
    raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('foro:' || p_usuario::text, 0));
  if p_tipo = 'publicacion' then
    perform public.app_foro_post(p_usuario, p_id);
    select p.usuario_id into v_autor from public.publicaciones_oposired p where p.id = p_id and p.estado = 'publicada';
  else
    select c.usuario_id into v_autor from public.comentarios_oposired c where c.id = p_id and c.estado = 'publicado';
    if not found then raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND'; end if;
    perform public.app_foro_post(p_usuario, (select c.publicacion_id from public.comentarios_oposired c where c.id = p_id));
  end if;
  if not found or v_autor = p_usuario then
    raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND';
  end if;
  if private.config_int('foro_activo', 1) = 0 or exists (select 1 from public.restricciones_foro r where r.usuario_id = p_usuario and r.hasta > clock_timestamp()) then
    raise exception using errcode = 'P0001', message = 'POSTING_RESTRICTED';
  end if;
  if p_on then
    select i.creado into v_creado from private.foro_account_info(p_usuario) i;
    v_cuenta := coalesce(v_creado <= clock_timestamp() - make_interval(hours => private.config_int('foro_edad_min_horas', 24)), false);
    if p_tipo = 'publicacion' and exists (select 1 from public.reacciones_oposired r where r.usuario_id = p_usuario and r.publicacion_id = p_id and r.tipo_reaccion = 'me_gusta') then
      null;   -- already voted: idempotent, nothing counted
    elsif p_tipo = 'comentario' and exists (select 1 from public.reacciones_oposired r where r.usuario_id = p_usuario and r.comentario_id = p_id and r.tipo_reaccion = 'me_gusta') then
      null;
    else
      perform private.foro_rate(p_usuario, 'voto', interval '1 minute', private.config_int('foro_votos_min', 60));
      perform private.foro_rate(p_usuario, 'voto', interval '1 day', private.config_int('foro_votos_dia', 500));
      insert into public.reacciones_oposired (usuario_id, publicacion_id, comentario_id, tipo_reaccion, cuenta)
      values (p_usuario, case when p_tipo = 'publicacion' then p_id end, case when p_tipo = 'comentario' then p_id end, 'me_gusta', v_cuenta);
      insert into public.foro_actividad (usuario_id, tipo) values (p_usuario, 'voto');
    end if;
  else
    delete from public.reacciones_oposired r where r.usuario_id = p_usuario and r.tipo_reaccion = 'me_gusta'
       and ((p_tipo = 'publicacion' and r.publicacion_id = p_id) or (p_tipo = 'comentario' and r.comentario_id = p_id));
  end if;
  get diagnostics v_n = row_count;
  return jsonb_build_object('miVoto', p_on, 'meGusta', case when p_tipo = 'publicacion' then (select n_me_gusta from public.publicaciones_oposired where id = p_id) else (select n_me_gusta from public.comentarios_oposired where id = p_id) end);
end;
$$;
revoke all on function public.app_foro_vote(uuid, text, uuid, boolean) from public, anon, authenticated;
grant execute on function public.app_foro_vote(uuid, text, uuid, boolean) to service_role;

-- report: unique per reporter and entity (a second one is a no-op), the 4-reason vocabulary, description capped; the reporter is never exposed; optional auto-hide of spam by K distinct aged reporters
create or replace function public.app_foro_report(p_usuario uuid, p_tipo text, p_id uuid, p_motivo text, p_descripcion text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare v_desc text; v_autor uuid; v_entidad text; v_k integer := private.config_int('foro_autoocultar_k', 0); v_post uuid;
begin
  if p_tipo not in ('publicacion', 'comentario') or p_motivo is null or p_motivo not in ('spam', 'ofensivo', 'incorrecto', 'otro') then
    raise exception using errcode = 'P0001', message = 'FORO_REPORT_INVALID';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('foro:' || p_usuario::text, 0));
  if p_tipo = 'publicacion' then
    perform public.app_foro_post(p_usuario, p_id);
    select p.usuario_id into v_autor from public.publicaciones_oposired p where p.id = p_id and p.estado = 'publicada';
    v_entidad := 'publicacion_oposired'; v_post := p_id;
  else
    select c.usuario_id, c.publicacion_id into v_autor, v_post from public.comentarios_oposired c where c.id = p_id and c.estado = 'publicado';
    if v_post is null then raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND'; end if;
    perform public.app_foro_post(p_usuario, v_post);
    v_entidad := 'comentario_oposired';
  end if;
  if not found or v_autor = p_usuario then
    raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND';
  end if;
  v_desc := case when p_descripcion is null then null else left(private.foro_clean_text(left(p_descripcion, 600), 500, true), 500) end;
  if not exists (select 1 from public.reportes_contenido r where r.reportado_por = p_usuario and r.tipo_entidad = v_entidad and r.entidad_id = p_id and r.motivo_codigo is not null) then
    perform private.foro_rate(p_usuario, 'reporte', interval '1 day', private.config_int('foro_reportes_dia', 20));
    insert into public.reportes_contenido (reportado_por, tipo_entidad, entidad_id, motivo, descripcion, motivo_codigo) values (p_usuario, v_entidad, p_id, p_motivo, v_desc, p_motivo);
    insert into public.foro_actividad (usuario_id, tipo) values (p_usuario, 'reporte');
    if p_motivo = 'spam' and v_k > 0 and (select count(distinct r.reportado_por) from public.reportes_contenido r join lateral private.foro_account_info(r.reportado_por) i on true
         where r.tipo_entidad = v_entidad and r.entidad_id = p_id and r.motivo_codigo = 'spam' and i.creado <= clock_timestamp() - make_interval(hours => private.config_int('foro_edad_min_horas', 24))) >= v_k then
      if v_entidad = 'publicacion_oposired' then update public.publicaciones_oposired set estado = 'oculta' where id = p_id and estado = 'publicada';
      else update public.comentarios_oposired set estado = 'oculto' where id = p_id and estado = 'publicado'; end if;
      insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues) values (null, 'foro_autoocultado', v_entidad, p_id, jsonb_build_object('motivo', 'spam', 'k', v_k));
    end if;
  end if;
  return jsonb_build_object('recibido', true);
end;
$$;
revoke all on function public.app_foro_report(uuid, text, uuid, text, text) from public, anon, authenticated;
grant execute on function public.app_foro_report(uuid, text, uuid, text, text) to service_role;

-- ---- 9. Control: moderation (actor from the admin JWT, audited in the same transaction)
create or replace function public.admin_foro_moderate(p_actor uuid, p_tipo text, p_id uuid, p_accion text, p_dias integer, p_notas text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare v_autor uuid; v_dias integer := least(greatest(coalesce(p_dias, 7), 1), 365);
begin
  if p_tipo not in ('publicacion', 'comentario') or p_accion not in ('aprobar', 'rechazar', 'ocultar', 'restaurar', 'silenciar') then
    raise exception using errcode = 'P0001', message = 'FORO_ACTION_INVALID';
  end if;
  if p_tipo = 'publicacion' then
    select usuario_id into v_autor from public.publicaciones_oposired where id = p_id for update;
  else
    select usuario_id into v_autor from public.comentarios_oposired where id = p_id for update;
  end if;
  if not found then
    raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND';
  end if;
  if p_accion = 'aprobar' then
    if p_tipo = 'publicacion' then update public.publicaciones_oposired set estado = 'publicada' where id = p_id and estado = 'pendiente_moderacion';
    else update public.comentarios_oposired set estado = 'publicado' where id = p_id and estado = 'pendiente_moderacion'; end if;
    if found and v_autor is not null then
      insert into public.moderacion_autor (usuario_id, aprobadas) values (v_autor, 1) on conflict (usuario_id) do update set aprobadas = public.moderacion_autor.aprobadas + 1;
    end if;
  elsif p_accion in ('rechazar', 'ocultar') then
    if p_tipo = 'publicacion' then update public.publicaciones_oposired set estado = 'oculta' where id = p_id and estado in ('pendiente_moderacion', 'publicada');
      if found then perform private.foro_retire_images(p_id, null); end if;   -- reject AND hide retire the image (a restore brings the post back without it)
    else update public.comentarios_oposired set estado = 'oculto' where id = p_id and estado in ('pendiente_moderacion', 'publicado'); end if;
  elsif p_accion = 'restaurar' then
    if p_tipo = 'publicacion' then update public.publicaciones_oposired set estado = 'publicada' where id = p_id and estado = 'oculta';
    else update public.comentarios_oposired set estado = 'publicado' where id = p_id and estado = 'oculto'; end if;
  elsif p_accion = 'silenciar' then
    if v_autor is null then raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND'; end if;
    insert into public.restricciones_foro (usuario_id, hasta, motivo, por) values (v_autor, clock_timestamp() + make_interval(days => v_dias), left(p_notas, 200), p_actor)
    on conflict (usuario_id) do update set hasta = excluded.hasta, motivo = excluded.motivo, por = excluded.por, creado_en = clock_timestamp();
  end if;
  insert into public.acciones_moderacion (moderador_id, tipo_accion, notas)
  values (p_actor, case p_accion when 'aprobar' then 'restaurar' when 'restaurar' then 'restaurar' when 'silenciar' then 'bloquear_usuario' else 'ocultar' end,
          left(coalesce(p_notas, '') || ' [' || p_tipo || ':' || p_id::text || ' ' || p_accion || ']', 2000));
  insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
  values (p_actor, 'foro_moderacion', case p_tipo when 'publicacion' then 'publicaciones_oposired' else 'comentarios_oposired' end, p_id, jsonb_build_object('accion', p_accion, 'dias', case when p_accion = 'silenciar' then v_dias end));
  return jsonb_build_object('accion', p_accion);
end;
$$;
revoke all on function public.admin_foro_moderate(uuid, text, uuid, text, integer, text) from public, anon, authenticated;
grant execute on function public.admin_foro_moderate(uuid, text, uuid, text, integer, text) to service_role;

-- ---- 10. account deletion: tombstones (the FK already nulls the author; the content is kept for threads, the votes go and the counters are recomputed)
create or replace function public.foro_tombstone_user(p_usuario uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare v_v integer;
begin
  update public.publicaciones_oposired set usuario_id = null where usuario_id = p_usuario;
  update public.comentarios_oposired set usuario_id = null where usuario_id = p_usuario;
  perform private.foro_retire_images(null, p_usuario);
  delete from public.reacciones_oposired where usuario_id = p_usuario;
  get diagnostics v_v = row_count;
  delete from public.restricciones_foro where usuario_id = p_usuario;
  delete from public.moderacion_autor where usuario_id = p_usuario;
  delete from public.foro_actividad where usuario_id = p_usuario;
  return jsonb_build_object('votosRetirados', v_v);
end;
$$;
revoke all on function public.foro_tombstone_user(uuid) from public, anon, authenticated;
grant execute on function public.foro_tombstone_user(uuid) to service_role;

-- ---- 11. jobs: drift report between the trigger counters and the rows (to Control), pruning of the rate-limit ledger
create or replace function public.foro_reconcile()
returns table(tipo text, id uuid, almacenado integer, recontado integer)
language sql
stable
security definer
set search_path = ''
as $$
  select 'publicacion_comentarios', p.id, p.n_comentarios, (select count(*)::integer from public.comentarios_oposired c where c.publicacion_id = p.id and c.estado = 'publicado')
    from public.publicaciones_oposired p where p.n_comentarios <> (select count(*)::integer from public.comentarios_oposired c where c.publicacion_id = p.id and c.estado = 'publicado')
  union all
  select 'publicacion_me_gusta', p.id, p.n_me_gusta, (select count(*)::integer from public.reacciones_oposired r where r.publicacion_id = p.id and r.tipo_reaccion = 'me_gusta' and r.cuenta)
    from public.publicaciones_oposired p where p.n_me_gusta <> (select count(*)::integer from public.reacciones_oposired r where r.publicacion_id = p.id and r.tipo_reaccion = 'me_gusta' and r.cuenta)
  union all
  select 'comentario_me_gusta', c.id, c.n_me_gusta, (select count(*)::integer from public.reacciones_oposired r where r.comentario_id = c.id and r.tipo_reaccion = 'me_gusta' and r.cuenta)
    from public.comentarios_oposired c where c.n_me_gusta <> (select count(*)::integer from public.reacciones_oposired r where r.comentario_id = c.id and r.tipo_reaccion = 'me_gusta' and r.cuenta)
$$;
revoke all on function public.foro_reconcile() from public, anon, authenticated;
grant execute on function public.foro_reconcile() to service_role;

create or replace function public.app_job_foro()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare v_pod integer; v_drift integer;
begin
  if not pg_try_advisory_xact_lock(hashtextextended('job:foro', 0)) then
    return jsonb_build_object('estado', 'OCUPADO');
  end if;
  perform set_config('statement_timeout', '30000', true);
  delete from public.foro_actividad where creado_en < clock_timestamp() - interval '2 days';
  get diagnostics v_pod = row_count;
  -- orphans: an upload never verified after 1 h, a verified image not attached to a post after 24 h (the sweeper deletes the objects)
  update public.imagenes_oposired i set estado = 'retirada', retirada_en = clock_timestamp(), version = i.version + 1, motivo = 'huerfana'
   where ((i.estado = 'subida' and i.creado_en < clock_timestamp() - interval '1 hour')
       or (i.estado = 'verificada' and i.creado_en < clock_timestamp() - interval '24 hours' and not exists (select 1 from public.publicaciones_oposired p where p.imagen_id = i.id)));
  select count(*) into v_drift from public.foro_reconcile();
  if v_drift > 0 then
    insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues) values (null, 'foro_contadores_desviados', 'publicaciones_oposired', null, jsonb_build_object('filas', v_drift));
  end if;
  return jsonb_build_object('estado', 'OK', 'podadas', v_pod, 'desviadas', v_drift);
end;
$$;
revoke all on function public.app_job_foro() from public, anon, authenticated;
grant execute on function public.app_job_foro() to service_role;

-- ---- 12. image pipeline (DB part; the bytes are decoded, re-encoded and stored by Ktor)
create or replace function public.app_foro_image_init(p_usuario uuid, p_mime text, p_bytes integer)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare v_id uuid := gen_random_uuid();
begin
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('foro:' || p_usuario::text, 0));
  perform private.foro_check_write(p_usuario, 'imagen');
  if p_mime is null or p_mime not in ('image/jpeg', 'image/png') or p_bytes is null or p_bytes < 1 or p_bytes > 3145728 then
    raise exception using errcode = 'P0001', message = 'IMAGE_NOT_USABLE';
  end if;
  perform private.foro_rate(p_usuario, 'imagen', interval '1 hour', private.config_int('foro_imagenes_hora', 5));
  -- a cap of unverified rows per user (a loop of inits cannot fill the table)
  if (select count(*) from public.imagenes_oposired i where i.usuario_id = p_usuario and i.estado = 'subida') >= 10 then
    raise exception using errcode = 'P0001', message = 'FORO_RATE_LIMITED', detail = '{"retryAfter":600}';
  end if;
  insert into public.imagenes_oposired (id, usuario_id, path, mime, bytes) values (v_id, p_usuario, p_usuario::text || '/' || v_id::text || case p_mime when 'image/png' then '.png' else '.jpg' end, p_mime, p_bytes);
  insert into public.foro_actividad (usuario_id, tipo) values (p_usuario, 'imagen');
  return jsonb_build_object('imagenId', v_id, 'path', p_usuario::text || '/' || v_id::text || case p_mime when 'image/png' then '.png' else '.jpg' end, 'mime', p_mime, 'bytesMax', p_bytes);
end;
$$;
revoke all on function public.app_foro_image_init(uuid, text, integer) from public, anon, authenticated;
grant execute on function public.app_foro_image_init(uuid, text, integer) to service_role;

-- Ktor verified the bytes (magic, header size, decode, re-encode) and stored the re-encoded object: it is now usable
create or replace function public.app_foro_image_commit(p_usuario uuid, p_id uuid, p_sha256 text, p_ancho integer, p_alto integer, p_bytes integer)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare v_n integer;
begin
  update public.imagenes_oposired set estado = 'verificada', sha256 = p_sha256, ancho = p_ancho, alto = p_alto, bytes = least(greatest(p_bytes, 1), 3145728), verificada_en = clock_timestamp()
   where id = p_id and usuario_id = p_usuario and estado = 'subida';
  get diagnostics v_n = row_count;
  if v_n = 0 then
    raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND';
  end if;
  return jsonb_build_object('estado', 'verificada');
end;
$$;
revoke all on function public.app_foro_image_commit(uuid, uuid, text, integer, integer, integer) from public, anon, authenticated;
grant execute on function public.app_foro_image_commit(uuid, uuid, text, integer, integer, integer) to service_role;

create or replace function public.app_foro_image_reject(p_usuario uuid, p_id uuid, p_motivo text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
begin
  update public.imagenes_oposired set estado = 'rechazada', motivo = left(coalesce(p_motivo, 'rechazada'), 60), retirada_en = clock_timestamp(), version = version + 1
   where id = p_id and usuario_id = p_usuario and estado = 'subida';
  if not found then
    raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND';
  end if;
  return jsonb_build_object('estado', 'rechazada');
end;
$$;
revoke all on function public.app_foro_image_reject(uuid, uuid, text) from public, anon, authenticated;
grant execute on function public.app_foro_image_reject(uuid, uuid, text) to service_role;

-- the owner sees the state of his image (never a URL); anything else is the same "not found"
create or replace function public.app_foro_image_status(p_usuario uuid, p_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare r record;
begin
  select i.estado, i.mime, i.path into r from public.imagenes_oposired i where i.id = p_id and i.usuario_id = p_usuario;
  if not found then
    raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND';
  end if;
  return jsonb_build_object('estado', r.estado, 'mime', r.mime, 'path', r.path);   -- for the owner's server only: the HTTP route returns the state alone
end;
$$;
revoke all on function public.app_foro_image_status(uuid, uuid) from public, anon, authenticated;
grant execute on function public.app_foro_image_status(uuid, uuid) to service_role;

-- the object path of the VERIFIED image of a post the caller can read (gate first); Ktor signs it. Per-user hourly cap on mints.
create or replace function public.app_foro_image_path(p_usuario uuid, p_post uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare v_img jsonb := public.app_foro_post(p_usuario, p_post); r record;
begin
  select i.path, i.version, i.mime into r from public.imagenes_oposired i where i.id = (v_img ->> 'imagenId')::uuid and i.estado = 'verificada';
  if not found then
    raise exception using errcode = 'P0001', message = 'FORO_NOT_FOUND';
  end if;
  perform private.foro_rate(p_usuario, 'imagen_url', interval '1 hour', private.config_int('foro_imagen_urls_hora', 120));
  insert into public.foro_actividad (usuario_id, tipo) values (p_usuario, 'imagen_url');
  return jsonb_build_object('path', r.path, 'version', r.version, 'mime', r.mime);
end;
$$;
revoke all on function public.app_foro_image_path(uuid, uuid) from public, anon, authenticated;
grant execute on function public.app_foro_image_path(uuid, uuid) to service_role;

-- sweeper support: retired / rejected / stale objects that still have to be deleted from Storage, and the confirmation
create or replace function public.app_foro_images_to_purge(p_limite integer default 50)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(jsonb_agg(jsonb_build_object('id', x.id, 'path', x.path)), '[]'::jsonb)
    from (select i.id, i.path from public.imagenes_oposired i where i.estado in ('retirada', 'rechazada') and i.purgada_en is null and i.intentos_purga < 10
           order by i.intentos_purga, i.retirada_en nulls first limit least(greatest(coalesce(p_limite, 50), 1), 200)) x   -- fewest failures first, and a row that failed 10 times leaves the queue: one bad row never blocks the head
$$;
revoke all on function public.app_foro_images_to_purge(integer) from public, anon, authenticated;
grant execute on function public.app_foro_images_to_purge(integer) to service_role;

create or replace function public.app_foro_images_purge_failed(p_ids uuid[])
returns integer
language sql
volatile
security definer
set search_path = ''
as $$
  with u as (update public.imagenes_oposired set intentos_purga = intentos_purga + 1 where id = any (p_ids) and purgada_en is null returning 1) select count(*)::integer from u
$$;
revoke all on function public.app_foro_images_purge_failed(uuid[]) from public, anon, authenticated;
grant execute on function public.app_foro_images_purge_failed(uuid[]) to service_role;

create or replace function public.app_foro_images_purged(p_ids uuid[])
returns integer
language sql
volatile
security definer
set search_path = ''
as $$
  with u as (update public.imagenes_oposired set purgada_en = clock_timestamp() where id = any (p_ids) and estado in ('retirada', 'rechazada') returning 1) select count(*)::integer from u
$$;
revoke all on function public.app_foro_images_purged(uuid[]) from public, anon, authenticated;
grant execute on function public.app_foro_images_purged(uuid[]) to service_role;

commit;
