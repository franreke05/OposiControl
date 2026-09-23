-- OPOSICONTROL CANONICAL V1 — STORAGE AND DOMAIN PRESERVATION CHECKS
-- No bucket, object, Opositeca, PDF or import row is moved or deleted here.

begin;

do $$
begin
  if not exists (select 1 from storage.buckets where id = 'exam-resources') then
    raise exception 'CANONICAL_STORAGE_PREFLIGHT_MISSING_BUCKET: exam-resources';
  end if;
  if not exists (select 1 from storage.buckets where id = 'news-assets') then
    raise exception 'CANONICAL_STORAGE_PREFLIGHT_MISSING_BUCKET: news-assets';
  end if;

  if to_regclass('public.opositeca_items') is null
     or to_regclass('public.etiquetas_opositeca') is null
     or to_regclass('public.lotes_importacion_preguntas') is null then
    raise exception 'CANONICAL_PRESERVATION_PREFLIGHT_MISSING_PROTECTED_TABLE';
  end if;

  raise notice 'CANONICAL_STORAGE_PRESERVATION_OK: no object or bucket mutation requested';
end;
$$;

commit;
