-- RB-16 · STUB de storage.objects con la forma relevante de MAIN (catálogo GO-1, 2026-10-19): dueño supabase_storage_admin, RLS
-- activo, grants del servicio para anon/authenticated/service_role, y las 12 políticas de escritura + 3 de lectura que llaman a
-- es_admin(). En MAIN 11 de las 12 de escritura NO están en migraciones del repo: aquí se reproducen con la forma
-- `bucket_id = '<bucket>' AND es_admin()`, que es lo que la migración necesita (la migración NO depende de su texto exacto:
-- las copia y el ROLLBACK las recrea desde la copia). Requiere un rol con CREATEROLE/superusuario (solo pruebas, nunca MAIN).
do $$
begin
  if to_regrole('supabase_storage_admin') is null then
    create role supabase_storage_admin nologin;
  end if;
end $$;
create schema if not exists storage;
create schema if not exists private;
create table storage.objects (
  id uuid primary key default gen_random_uuid(),
  bucket_id text not null,
  name text not null,
  owner uuid,
  created_at timestamptz not null default now()
);
alter table storage.objects owner to supabase_storage_admin;
alter table storage.objects enable row level security;
grant usage on schema storage to anon, authenticated, service_role;
grant all on table storage.objects to anon, authenticated, service_role;

create policy exam_resources_storage_admin_insert on storage.objects for insert to authenticated with check (bucket_id = 'exam-resources' and public.es_admin());
create policy exam_resources_storage_admin_update on storage.objects for update to authenticated using (bucket_id = 'exam-resources' and public.es_admin()) with check (bucket_id = 'exam-resources' and public.es_admin());
create policy exam_resources_storage_admin_delete on storage.objects for delete to authenticated using (bucket_id = 'exam-resources' and public.es_admin());
create policy exam_resources_storage_admin_select on storage.objects for select to authenticated using (bucket_id = 'exam-resources' and public.es_admin());
create policy news_assets_storage_admin_insert on storage.objects for insert to authenticated with check (bucket_id = 'news-assets' and public.es_admin());
create policy news_assets_storage_admin_update on storage.objects for update to authenticated using (bucket_id = 'news-assets' and public.es_admin()) with check (bucket_id = 'news-assets' and public.es_admin());
create policy news_assets_storage_admin_delete on storage.objects for delete to authenticated using (bucket_id = 'news-assets' and public.es_admin());
create policy question_resources_storage_admin_insert on storage.objects for insert to authenticated with check (bucket_id = 'question-resources' and public.es_admin());
create policy question_resources_storage_admin_update on storage.objects for update to authenticated using (bucket_id = 'question-resources' and public.es_admin()) with check (bucket_id = 'question-resources' and public.es_admin());
create policy question_resources_storage_admin_delete on storage.objects for delete to authenticated using (bucket_id = 'question-resources' and public.es_admin());
create policy question_resources_storage_admin_select on storage.objects for select to authenticated using (bucket_id = 'question-resources' and public.es_admin());
create policy recursos_pregunta_storage_insert_admin on storage.objects for insert to authenticated with check (bucket_id = 'recursos_pregunta' and public.es_admin());
create policy recursos_pregunta_storage_update_admin on storage.objects for update to authenticated using (bucket_id = 'recursos_pregunta' and public.es_admin()) with check (bucket_id = 'recursos_pregunta' and public.es_admin());
create policy recursos_pregunta_storage_delete_admin on storage.objects for delete to authenticated using (bucket_id = 'recursos_pregunta' and public.es_admin());
create policy recursos_pregunta_storage_select_premium on storage.objects for select to public using (bucket_id = 'recursos_pregunta' and (public.es_admin() or false));
insert into storage.objects (bucket_id, name) values ('news-assets', 'seed/portada.webp'), ('exam-resources', 'seed/recurso.png');
