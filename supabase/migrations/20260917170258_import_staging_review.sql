-- IM2 · Una fila puede decidirse «vincular esta pregunta existente» (DEC-IMP-01), y una fila publicada ya no se edita.
-- Incluye los cambios de la revisión C: IM2-1 (identidad publicada inmutable), IM2-2 (cerrojo explícito de preguntas)
-- e IM2-3 (si desaparece la pregunta elegida para vincular, la fila vuelve a revisión en lugar de crear una copia).
set local lock_timeout = '5s';
lock table public.preguntas_parseadas_staging, public.lotes_importacion_preguntas, public.preguntas in share row exclusive mode;

do $pre$
begin
  if exists (select 1 from pg_attribute where attrelid = 'public.preguntas_parseadas_staging'::regclass
              and attname = 'vincular_pregunta_id' and not attisdropped) then
    raise exception 'PREFLIGHT_FAILED: la columna ya existe';
  end if;
  if exists (select 1 from pg_trigger where tgrelid = 'public.preguntas_parseadas_staging'::regclass
              and tgname = 'trg_staging_inmutable_tras_importar')
     or exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                 where n.nspname = 'private' and p.proname = 'tg_staging_inmutable_tras_importar') then
    raise exception 'PREFLIGHT_FAILED: el trigger o su función ya existen';
  end if;
  if not exists (select 1 from pg_attribute where attrelid = 'public.preguntas_parseadas_staging'::regclass
                  and attname = 'error_parseo' and not attisdropped) then
    raise exception 'PREFLIGHT_FAILED: falta la columna error_parseo';
  end if;
end
$pre$;

alter table public.preguntas_parseadas_staging
  add column vincular_pregunta_id uuid references public.preguntas(id) on delete set null;

-- Índice para el ON DELETE SET NULL (borrar una pregunta no recorre staging entero).
create index idx_preguntas_parseadas_staging_vincular
  on public.preguntas_parseadas_staging (vincular_pregunta_id)
  where vincular_pregunta_id is not null;

comment on column public.preguntas_parseadas_staging.vincular_pregunta_id is
  'Decisión de revisión (DEC-IMP-01): al publicar, vincular esta pregunta existente al examen del lote en lugar de crear una nueva. Exige estado aprobada.';

create function private.tg_staging_inmutable_tras_importar()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  v_publicada boolean;
begin
  v_publicada := old.pregunta_id is not null
    or exists (select 1 from public.lotes_importacion_preguntas l where l.id = old.lote_id and l.estado = 'importado');

  -- Contenido, decisión humana e identidad publicada: nada cambia tras publicar. pregunta_id y
  -- vincular_pregunta_id sí pueden pasar a NULL (ON DELETE SET NULL); error_parseo, codigo_pregunta_generado
  -- y los metadatos no cambian lo publicado.
  if v_publicada
     and (row(new.lote_id, new.posicion, new.bloque_original, new.titulo, new.opcion_a, new.opcion_b, new.opcion_c, new.opcion_d,
              new.respuesta_correcta, new.razonamiento, new.fuente_texto, new.etiqueta_texto, new.estado_parseo)
          is distinct from
          row(old.lote_id, old.posicion, old.bloque_original, old.titulo, old.opcion_a, old.opcion_b, old.opcion_c, old.opcion_d,
              old.respuesta_correcta, old.razonamiento, old.fuente_texto, old.etiqueta_texto, old.estado_parseo)
          or (new.pregunta_id is not null and new.pregunta_id is distinct from old.pregunta_id)
          or (new.vincular_pregunta_id is not null and new.vincular_pregunta_id is distinct from old.vincular_pregunta_id)) then
    raise exception 'STAGING_ROW_ALREADY_IMPORTED';
  end if;

  -- IM2-3: la pregunta elegida para vincular se ha BORRADO (la acción ON DELETE SET NULL llega como UPDATE anidado).
  -- Nunca se cae en silencio a «crear una nueva»: la fila vuelve a revisión humana.
  if not v_publicada
     and pg_trigger_depth() > 1
     and old.vincular_pregunta_id is not null
     and new.vincular_pregunta_id is null then
    new.estado_parseo := 'advertencia';
    new.error_parseo := 'La pregunta elegida para vincular ya no existe: vuelve a decidir.';
  end if;

  return new;
end;
$$;

revoke all on function private.tg_staging_inmutable_tras_importar() from public, anon, authenticated;

create trigger trg_staging_inmutable_tras_importar
  before update on public.preguntas_parseadas_staging
  for each row execute function private.tg_staging_inmutable_tras_importar();

do $post$
begin
  -- Por catálogo y no por pg_get_constraintdef (su texto depende del search_path de la sesión: revisión C, Fase 3).
  if not exists (select 1 from pg_constraint c
                  where c.conrelid = 'public.preguntas_parseadas_staging'::regclass and c.contype = 'f'
                    and c.confrelid = 'public.preguntas'::regclass and c.confdeltype = 'n'
                    and c.conkey = array[(select a.attnum from pg_attribute a
                                           where a.attrelid = 'public.preguntas_parseadas_staging'::regclass
                                             and a.attname = 'vincular_pregunta_id')]) then
    raise exception 'POSTCHECK_FAILED: falta la FK de vincular_pregunta_id';
  end if;
  if not exists (select 1 from pg_trigger where tgrelid = 'public.preguntas_parseadas_staging'::regclass
                  and tgname = 'trg_staging_inmutable_tras_importar' and not tgisinternal) then
    raise exception 'POSTCHECK_FAILED: falta el trigger';
  end if;
  if has_function_privilege('anon', 'private.tg_staging_inmutable_tras_importar()', 'EXECUTE')
     or has_function_privilege('authenticated', 'private.tg_staging_inmutable_tras_importar()', 'EXECUTE') then
    raise exception 'POSTCHECK_FAILED: ACL abierta';
  end if;
end
$post$;
