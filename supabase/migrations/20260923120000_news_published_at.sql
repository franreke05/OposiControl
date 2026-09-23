-- `published_at` se sella al publicar, no lo escribe quien llama.
--
-- La columna existía y `SupabaseNoticiaRow` la declaraba, pero NADIE la
-- rellenaba: `toSupabaseRow` no la envía, así que una noticia publicada se
-- quedaba con `published_at` nulo para siempre. Un campo que nunca se escribe
-- es peor que no tenerlo: invita a construir encima (ordenar por fecha de
-- publicación, "publicada hace X") sobre un dato que siempre es nulo.
--
-- Va en la base y no en Ktor a propósito. El sello tiene que valer para
-- CUALQUIER escritor —el panel, un trabajo programado que active noticias por
-- fecha, una corrección manual—, no solo para el camino que hoy existe.

create or replace function public.noticia_sellar_published_at()
returns trigger
language plpgsql
as $$
begin
  -- Sólo en la transición a 'published', y sólo si no venía ya sellada: quien
  -- importe contenido con su fecha real de publicación debe poder conservarla.
  if new.estado = 'published' and new.published_at is null then
    new.published_at := now();
  end if;

  -- Volver a borrador o archivar no borra el sello. La fecha responde a
  -- «cuándo se publicó por primera vez», y despublicar no lo deshace.
  return new;
end;
$$;

drop trigger if exists noticia_published_at on public.noticia;
create trigger noticia_published_at
  before insert or update on public.noticia
  for each row execute function public.noticia_sellar_published_at();

-- Postcondición: comprobar que el trigger sella de verdad, no que exista.
do $$
declare
  v_esp uuid; v_tipo uuid; v_sello timestamptz;
begin
  if to_regprocedure('public.noticia_sellar_published_at()') is null then
    raise exception 'NEWS_PUBLISHED_AT_TRIGGER_NOT_INSTALLED';
  end if;

  select id into v_esp from public.especialidades limit 1;
  select id into v_tipo from public.tipo_noticia limit 1;

  insert into public.noticia (titulo, especialidad_id, tipo_noticia_id, estado)
  values ('Postcondicion published_at', v_esp, v_tipo, 'published')
  returning published_at into v_sello;

  if v_sello is null then
    raise exception 'NEWS_PUBLISHED_AT_NOT_STAMPED';
  end if;

  -- La comprobación no deja filas: se deshace siempre.
  raise exception 'NEWS_PUBLISHED_AT_OK';
exception
  when others then
    if sqlerrm = 'NEWS_PUBLISHED_AT_OK' then
      return;
    end if;
    raise;
end;
$$;
