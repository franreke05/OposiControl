-- OPOSICONTROL CANONICAL V1 V2 — PRESERVATION ASSERTIONS
-- No bucket, object, Opositeca, OposiRed, PDF or import row is changed.

begin;

do $$
begin
  if not exists (select 1 from storage.buckets where id = 'exam-resources') then
    raise exception 'CANONICAL_V2_STORAGE_MISSING_BUCKET: exam-resources';
  end if;
  if not exists (select 1 from storage.buckets where id = 'news-assets') then
    raise exception 'CANONICAL_V2_STORAGE_MISSING_BUCKET: news-assets';
  end if;
  if to_regclass('public.opositeca_items') is null
     or to_regclass('public.etiquetas_opositeca') is null
     or to_regclass('public.lotes_importacion_preguntas') is null then
    raise exception 'CANONICAL_V2_PROTECTED_DOMAIN_MISSING';
  end if;
  raise notice 'CANONICAL_V2_PRESERVATION_OK';
end;
$$;

commit;
