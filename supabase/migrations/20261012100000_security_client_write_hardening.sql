-- Security pass 2026-10-12: tighten what an ordinary authenticated user can write DIRECTLY through PostgREST (bypassing the
-- backend) on tables that still carried permissive legacy policies. Forward-only; no data is modified. Size constraints are
-- NOT VALID: they are enforced for new and updated rows and never fail on existing ones.
--
-- 1. Support: a user could insert a message in their own conversation with any `remitente_id` and `es_staff = true`
--    (a forged staff reply), and could update or delete their own conversation rows.
-- 2. OposiRed: the author of a post or comment could set `estado` and `fijada` (re-publish what a moderator hid, pin it).
-- 3. Telemetry-like tables accepted unbounded `metadatos` / text: one account could fill the database.
-- 4. Profile: `nombre_mostrar` and `avatar_url` had no length limit when written directly.
-- 5. News: `anon` still held write grants (RLS was the only layer).

begin;

-- ---------------------------------------------------------------------------------------------------------------- 1. support
drop policy if exists mensajes_soporte_insert_propios on public.mensajes_soporte;
create policy mensajes_soporte_insert_propios on public.mensajes_soporte
    for insert to authenticated
    with check (
        public.es_moderador_o_admin()
        or (
            remitente_id = (select auth.uid())
            and es_staff = false
            and exists (
                select 1 from public.conversaciones_soporte cs
                where cs.id = mensajes_soporte.conversacion_id and cs.usuario_id = (select auth.uid())
            )
        )
    );

alter table public.mensajes_soporte
    add constraint mensajes_soporte_cuerpo_max check (char_length(cuerpo) <= 5000) not valid;

drop policy if exists conversaciones_soporte_todo_propio on public.conversaciones_soporte;
create policy conversaciones_soporte_select_propio on public.conversaciones_soporte
    for select to authenticated
    using (usuario_id = (select auth.uid()) or public.es_moderador_o_admin());
create policy conversaciones_soporte_insert_propio on public.conversaciones_soporte
    for insert to authenticated
    with check (usuario_id = (select auth.uid()) or public.es_moderador_o_admin());
create policy conversaciones_soporte_update_staff on public.conversaciones_soporte
    for update to authenticated
    using (public.es_moderador_o_admin()) with check (public.es_moderador_o_admin());
create policy conversaciones_soporte_delete_staff on public.conversaciones_soporte
    for delete to authenticated
    using (public.es_moderador_o_admin());

-- ---------------------------------------------------------------------------------------------------------------- 2. OposiRed
-- Moderation columns are owned by staff and by the backend (service role). For any other writer they keep their default on
-- insert and their previous value on update, whatever the request says.
create or replace function private.guard_publicacion_oposired_moderacion()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    if coalesce((select auth.role()), '') <> 'authenticated' or public.es_moderador_o_admin() then
        return new;
    end if;
    if tg_op = 'INSERT' then
        new.estado := 'publicada';
        new.fijada := false;
    else
        new.estado := old.estado;
        new.fijada := old.fijada;
        new.usuario_id := old.usuario_id;
    end if;
    return new;
end;
$$;

create or replace function private.guard_comentario_oposired_moderacion()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    if coalesce((select auth.role()), '') <> 'authenticated' or public.es_moderador_o_admin() then
        return new;
    end if;
    if tg_op = 'INSERT' then
        new.estado := 'publicado';
    else
        new.estado := old.estado;
        new.usuario_id := old.usuario_id;
    end if;
    return new;
end;
$$;

revoke all on function private.guard_publicacion_oposired_moderacion() from public, anon, authenticated;
revoke all on function private.guard_comentario_oposired_moderacion() from public, anon, authenticated;

drop trigger if exists trg_publicaciones_oposired_guard_moderacion on public.publicaciones_oposired;
create trigger trg_publicaciones_oposired_guard_moderacion
    before insert or update on public.publicaciones_oposired
    for each row execute function private.guard_publicacion_oposired_moderacion();

drop trigger if exists trg_comentarios_oposired_guard_moderacion on public.comentarios_oposired;
create trigger trg_comentarios_oposired_guard_moderacion
    before insert or update on public.comentarios_oposired
    for each row execute function private.guard_comentario_oposired_moderacion();

-- ---------------------------------------------------------------------------------------------------------------- 3. size caps
alter table public.sesiones_estudio
    add constraint sesiones_estudio_metadatos_max check (pg_column_size(metadatos) <= 16384) not valid;
alter table public.abandonos_examen
    add constraint abandonos_examen_metadatos_max check (pg_column_size(metadatos) <= 16384) not valid;
alter table public.eventos_conversion_premium
    add constraint eventos_conversion_premium_metadatos_max check (pg_column_size(metadatos) <= 16384) not valid;
alter table public.metricas_rendimiento_app
    add constraint metricas_rendimiento_app_metadatos_max check (pg_column_size(metadatos) <= 16384) not valid;
alter table public.errores_app
    add constraint errores_app_tamano_max check (
        pg_column_size(metadatos) <= 16384
        and char_length(coalesce(stacktrace, '')) <= 20000
        and char_length(coalesce(mensaje_error, '')) <= 4000
    ) not valid;
alter table public.feedback_examen
    add constraint feedback_examen_comentario_max check (char_length(coalesce(comentario, '')) <= 4000) not valid;
alter table public.feedback_pregunta
    add constraint feedback_pregunta_descripcion_max check (char_length(coalesce(descripcion, '')) <= 4000) not valid;

-- ---------------------------------------------------------------------------------------------------------------- 4. profile
alter table public.perfiles_usuario
    add constraint perfiles_usuario_nombre_mostrar_max check (char_length(coalesce(nombre_mostrar, '')) <= 120) not valid;
alter table public.perfiles_usuario
    add constraint perfiles_usuario_avatar_url_max check (char_length(coalesce(avatar_url, '')) <= 1000) not valid;

-- ---------------------------------------------------------------------------------------------------------------- 5. news
revoke insert, update, delete on public.noticia, public.tipo_noticia from anon;

commit;

-- VERIFY-BEGIN
-- select 'support_insert_policy_checks_sender', (count(*) = 0)::int from pg_policy p join pg_class c on c.oid = p.polrelid where c.relname = 'mensajes_soporte' and p.polname = 'mensajes_soporte_insert_propios' and pg_get_expr(p.polwithcheck, p.polrelid) like '%remitente_id%' and pg_get_expr(p.polwithcheck, p.polrelid) like '%es_staff%'
-- union all select 'conversation_for_all_policy_gone', count(*)::int from pg_policy p join pg_class c on c.oid = p.polrelid where c.relname = 'conversaciones_soporte' and p.polname = 'conversaciones_soporte_todo_propio'
-- union all select 'conversation_policies_present', (count(*) <> 4)::int from pg_policy p join pg_class c on c.oid = p.polrelid where c.relname = 'conversaciones_soporte'
-- union all select 'oposired_guard_triggers', (count(*) <> 2)::int from pg_trigger where tgname in ('trg_publicaciones_oposired_guard_moderacion', 'trg_comentarios_oposired_guard_moderacion') and not tgisinternal
-- union all select 'size_constraints', (count(*) <> 10)::int from pg_constraint where conname in ('mensajes_soporte_cuerpo_max', 'sesiones_estudio_metadatos_max', 'abandonos_examen_metadatos_max', 'eventos_conversion_premium_metadatos_max', 'metricas_rendimiento_app_metadatos_max', 'errores_app_tamano_max', 'feedback_examen_comentario_max', 'feedback_pregunta_descripcion_max', 'perfiles_usuario_nombre_mostrar_max', 'perfiles_usuario_avatar_url_max')
-- union all select 'anon_cannot_write_news', (has_table_privilege('anon', 'public.noticia', 'insert') or has_table_privilege('anon', 'public.tipo_noticia', 'delete'))::int;
-- VERIFY-END
