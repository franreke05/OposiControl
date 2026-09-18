-- M6 · Portada de examen: ruta de objeto privado + tipo MIME. Sin URL persistida (bucket privado; se firma en lectura).
-- Aplicar y desplegar K5 A CONTINUACIÓN (misma entrega). Hoy no hay ninguna portada guardada.
set local lock_timeout = '5s';
lock table public.examenes in share row exclusive mode;

do $pre$
begin
  if exists (select 1 from information_schema.columns
              where table_schema = 'public' and table_name = 'examenes'
                and column_name in ('portada_storage_path', 'portada_mime_type', 'portada_url')) then
    raise exception 'PREFLIGHT_FAILED: ya existen columnas de portada en examenes';
  end if;
  if (select b.public from storage.buckets b where b.id = 'exam-resources') is distinct from false then
    raise exception 'PREFLIGHT_FAILED: el bucket exam-resources no existe o no es privado';
  end if;
end
$pre$;

alter table public.examenes
  add column portada_storage_path text,
  add column portada_mime_type text;

alter table public.examenes
  add constraint examenes_portada_coherente_chk check (
    (portada_storage_path is null and portada_mime_type is null)
    or (
      -- Formato exacto (revisión C): exam-covers/<examen_id>/<uuid>.<ext>, sin `..` ni extensiones libres.
      portada_storage_path ~ ('^exam-covers/' || id::text || '/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|jpeg|png|webp|gif)$')
      and portada_mime_type in ('image/jpeg', 'image/png', 'image/webp', 'image/gif')
    )
  );

comment on column public.examenes.portada_storage_path is
  'Objeto en el bucket PRIVADO exam-resources: exam-covers/<examen_id>/<uuid>.<ext>. La URL se firma en lectura y nunca se guarda.';
comment on column public.examenes.portada_mime_type is
  'Tipo MIME de la portada. Ktor admite jpeg/png/webp; el CHECK acepta también gif por coherencia con el bucket.';

do $post$
begin
  if (select count(*) from information_schema.columns
       where table_schema = 'public' and table_name = 'examenes'
         and column_name in ('portada_storage_path', 'portada_mime_type')) <> 2 then
    raise exception 'POSTCHECK_FAILED: faltan columnas';
  end if;
  if not exists (select 1 from pg_constraint where conrelid = 'public.examenes'::regclass
                  and conname = 'examenes_portada_coherente_chk' and convalidated) then
    raise exception 'POSTCHECK_FAILED: falta examenes_portada_coherente_chk';
  end if;
end
$post$;
