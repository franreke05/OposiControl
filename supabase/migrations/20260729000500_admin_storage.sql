-- Private premium resources and public exam covers.
-- News buckets and policies are intentionally untouched.

begin;

insert into storage.buckets(
    id,
    name,
    public,
    file_size_limit,
    allowed_mime_types
)
values (
    'exam-covers',
    'exam-covers',
    false,
    10485760,
    array['image/jpeg', 'image/png', 'image/webp']
)
on conflict (id) do update
set
    public = excluded.public,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

insert into storage.buckets(
    id,
    name,
    public,
    file_size_limit,
    allowed_mime_types
)
values (
    'exam-resources',
    'exam-resources',
    false,
    52428800,
    array[
        'image/jpeg',
        'image/png',
        'image/webp',
        'audio/mpeg',
        'audio/mp4',
        'audio/ogg',
        'video/mp4',
        'application/pdf',
        'text/plain'
    ]
)
on conflict (id) do update
set
    public = excluded.public,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "exam resources public read" on storage.objects;
drop policy if exists "exam resources authenticated write" on storage.objects;
drop policy if exists "exam resources service write" on storage.objects;
drop policy if exists "oposicontrol exam covers public read" on storage.objects;
drop policy if exists "oposicontrol exam content admin read" on storage.objects;
drop policy if exists "oposicontrol exam content admin insert" on storage.objects;
drop policy if exists "oposicontrol exam content admin update" on storage.objects;
drop policy if exists "oposicontrol exam content admin delete" on storage.objects;

create or replace function private.is_published_exam_cover(object_name text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from public.examen
        where examen.portada_storage_path = object_name
          and examen.estado = 'published'
          and examen.deleted_at is null
    )
$$;

revoke all on function private.is_published_exam_cover(text) from public;
grant usage on schema private to anon;
grant execute on function private.is_published_exam_cover(text) to anon, authenticated;

create policy "oposicontrol exam covers public read"
on storage.objects
for select
to public
using (
    bucket_id = 'exam-covers'
    and private.is_published_exam_cover(name)
);

create policy "oposicontrol exam content admin read"
on storage.objects
for select
to authenticated
using (
    bucket_id in ('exam-covers', 'exam-resources')
    and private.has_permission('content.read')
);

create policy "oposicontrol exam content admin insert"
on storage.objects
for insert
to authenticated
with check (
    bucket_id in ('exam-covers', 'exam-resources')
    and private.has_permission('content.write')
);

create policy "oposicontrol exam content admin update"
on storage.objects
for update
to authenticated
using (
    bucket_id in ('exam-covers', 'exam-resources')
    and private.has_permission('content.write')
)
with check (
    bucket_id in ('exam-covers', 'exam-resources')
    and private.has_permission('content.write')
);

create policy "oposicontrol exam content admin delete"
on storage.objects
for delete
to authenticated
using (
    bucket_id in ('exam-covers', 'exam-resources')
    and private.has_permission('content.write')
);

grant select on table storage.objects to anon, authenticated;
grant insert, update, delete on table storage.objects to authenticated;

commit;
