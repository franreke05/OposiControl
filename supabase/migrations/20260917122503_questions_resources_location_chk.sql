-- Q3 · Un recurso vive donde dice su tipo, y nunca en una ruta local.
--   texto          -> contenido_texto (sin ruta ni URL)
--   enlace         -> url_externa http(s) (sin ruta)
--   imagen|audio|video|pdf -> objeto en el bucket privado recursos-pregunta: preguntas/<pregunta_id>/<id>.<ext> (sin URL)
set local lock_timeout = '5s';
lock table public.recursos_pregunta in share row exclusive mode;

do $pre$
begin
  if not exists (select 1 from storage.buckets b where b.id = 'recursos-pregunta' and not b.public) then
    raise exception 'PREFLIGHT_FAILED: el bucket recursos-pregunta no existe o es público';
  end if;
  if exists (select 1 from pg_constraint where conrelid = 'public.recursos_pregunta'::regclass
              and conname in ('recursos_pregunta_ubicacion_por_tipo_chk', 'recursos_pregunta_ubicacion_formato_chk')) then
    raise exception 'PREFLIGHT_FAILED: las restricciones ya existen';
  end if;
  if exists (
    select 1 from public.recursos_pregunta rp
     where not (case
                  when rp.tipo_recurso = 'texto' then rp.contenido_texto is not null and rp.ruta_storage is null and rp.url_externa is null
                  when rp.tipo_recurso = 'enlace' then rp.url_externa is not null and rp.ruta_storage is null
                  else rp.ruta_storage is not null and rp.url_externa is null
                end)
        or not ((rp.url_externa is null or rp.url_externa ~* '^https?://[^[:space:]]+$')
                and (rp.ruta_storage is null or (
                      coalesce(rp.bucket_storage, '') = 'recursos-pregunta'
                      and rp.ruta_storage ~ '^preguntas/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.[a-z0-9]{2,4}$'
                      and split_part(rp.ruta_storage, '/', 2) = rp.pregunta_id::text
                      and split_part(split_part(rp.ruta_storage, '/', 3), '.', 1) = rp.id::text)))) then
    raise exception 'PREFLIGHT_FAILED: hay recursos que violarían las restricciones (hoy 0 filas: contar y decidir antes de forzar)';
  end if;
end
$pre$;

alter table public.recursos_pregunta
  add constraint recursos_pregunta_ubicacion_por_tipo_chk check (
    case
      when tipo_recurso = 'texto' then contenido_texto is not null and ruta_storage is null and url_externa is null
      when tipo_recurso = 'enlace' then url_externa is not null and ruta_storage is null
      else ruta_storage is not null and url_externa is null
    end
  ),
  add constraint recursos_pregunta_ubicacion_formato_chk check (
    (url_externa is null or url_externa ~* '^https?://[^[:space:]]+$')
    and (ruta_storage is null or (
          coalesce(bucket_storage, '') = 'recursos-pregunta'   -- coalesce: un NULL haría pasar el CHECK
          and ruta_storage ~ '^preguntas/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.[a-z0-9]{2,4}$'
          and split_part(ruta_storage, '/', 2) = pregunta_id::text
          and split_part(split_part(ruta_storage, '/', 3), '.', 1) = id::text))
  );

comment on constraint recursos_pregunta_ubicacion_formato_chk on public.recursos_pregunta is
  'Nunca rutas locales ni URLs firmadas persistidas: url_externa sólo http(s) y sólo para enlace; los ficheros van a recursos-pregunta/preguntas/<pregunta_id>/<id>.<ext> (Fase 4, punto 7).';

do $post$
begin
  if (select count(*) from pg_constraint
       where conrelid = 'public.recursos_pregunta'::regclass and convalidated
         and conname in ('recursos_pregunta_ubicacion_por_tipo_chk', 'recursos_pregunta_ubicacion_formato_chk')) <> 2 then
    raise exception 'POSTCHECK_FAILED: faltan las restricciones validadas';
  end if;
end
$post$;
