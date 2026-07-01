-- Operaciones admin que deben completarse enteras o no aplicarse.

create or replace function public.admin_sync_roles(
  p_usuario_id uuid,
  p_role_ids uuid[]
)
returns void
language plpgsql
security invoker
set search_path = public
as $$
begin
  delete from public.roles_usuario where usuario_id = p_usuario_id;

  insert into public.roles_usuario (usuario_id, rol_id)
  select p_usuario_id, role_id
  from (select distinct unnest(coalesce(p_role_ids, array[]::uuid[])) as role_id) roles;
end;
$$;

create or replace function public.admin_grant_tickets(
  p_usuario_id uuid,
  p_cantidad integer,
  p_motivo text,
  p_tipo text default 'ajuste'
)
returns integer
language plpgsql
security invoker
set search_path = public
as $$
declare
  saldo_anterior integer;
  saldo_resultante integer;
begin
  if p_cantidad = 0 then
    raise exception 'La cantidad de tickets no puede ser 0.';
  end if;

  insert into public.carteras_usuario (usuario_id, saldo_tickets, actualizado_en)
  values (p_usuario_id, 0, now())
  on conflict (usuario_id) do nothing;

  select saldo_tickets
  into saldo_anterior
  from public.carteras_usuario
  where usuario_id = p_usuario_id
  for update;

  saldo_resultante := saldo_anterior + p_cantidad;
  if saldo_resultante < 0 then
    raise exception 'El saldo resultante no puede ser negativo (actual: %).', saldo_anterior;
  end if;

  update public.carteras_usuario
  set saldo_tickets = saldo_resultante,
      actualizado_en = now()
  where usuario_id = p_usuario_id;

  insert into public.transacciones_cartera (
    usuario_id,
    tipo,
    cantidad,
    saldo_anterior,
    saldo_resultante,
    descripcion,
    referencia_tipo
  ) values (
    p_usuario_id,
    p_tipo,
    p_cantidad,
    saldo_anterior,
    saldo_resultante,
    p_motivo,
    'admin'
  );

  return saldo_resultante;
end;
$$;

create or replace function public.admin_create_question(
  p_question jsonb,
  p_options jsonb,
  p_solution jsonb,
  p_exam_id uuid default null,
  p_position integer default null
)
returns uuid
language plpgsql
security invoker
set search_path = public
as $$
declare
  question_id uuid := (p_question->>'id')::uuid;
begin
  insert into public.preguntas (
    id,
    codigo_pregunta,
    especialidad_id,
    tipo_pregunta,
    titulo,
    fuente_texto,
    dificultad,
    activa
  ) values (
    question_id,
    p_question->>'codigo_pregunta',
    (p_question->>'especialidad_id')::uuid,
    p_question->>'tipo_pregunta',
    p_question->>'titulo',
    coalesce(p_question->>'fuente_texto', ''),
    p_question->>'dificultad',
    coalesce((p_question->>'activa')::boolean, true)
  );

  insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
  select
    (option_row->>'id')::uuid,
    question_id,
    option_row->>'letra',
    option_row->>'texto_opcion',
    (option_row->>'posicion')::integer
  from jsonb_array_elements(p_options) option_row;

  insert into public.soluciones_pregunta (id, pregunta_id, opcion_correcta_id, razonamiento)
  values (
    (p_solution->>'id')::uuid,
    question_id,
    (p_solution->>'opcion_correcta_id')::uuid,
    coalesce(p_solution->>'razonamiento', '')
  );

  if p_exam_id is not null then
    insert into public.preguntas_examen (examen_id, pregunta_id, posicion)
    values (p_exam_id, question_id, greatest(coalesce(p_position, 1), 1));
  end if;

  return question_id;
end;
$$;

create or replace function public.admin_replace_question_options(
  p_question_id uuid,
  p_options jsonb,
  p_solution jsonb
)
returns void
language plpgsql
security invoker
set search_path = public
as $$
begin
  delete from public.soluciones_pregunta where pregunta_id = p_question_id;
  delete from public.opciones_pregunta where pregunta_id = p_question_id;

  insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
  select
    (option_row->>'id')::uuid,
    p_question_id,
    option_row->>'letra',
    option_row->>'texto_opcion',
    (option_row->>'posicion')::integer
  from jsonb_array_elements(p_options) option_row;

  insert into public.soluciones_pregunta (id, pregunta_id, opcion_correcta_id, razonamiento)
  values (
    (p_solution->>'id')::uuid,
    p_question_id,
    (p_solution->>'opcion_correcta_id')::uuid,
    coalesce(p_solution->>'razonamiento', '')
  );
end;
$$;

create schema if not exists private;
revoke all on schema private from public, anon, authenticated;
grant usage on schema private to service_role;

create or replace function private.admin_upsert_search_entry(entry jsonb)
returns void
language plpgsql
security invoker
set search_path = public
as $$
declare
  tag_slug text;
  tag_id integer;
begin
  insert into public.search_index (
    entity_type,
    entity_id,
    section,
    title,
    summary,
    searchable_text,
    updated_at
  ) values (
    entry->>'entityType',
    entry->>'entityId',
    entry->>'section',
    left(entry->>'title', 220),
    left(coalesce(entry->>'summary', ''), 500),
    left(concat_ws(' ', entry->>'title', entry->>'summary', entry->>'tagText'), 6000),
    now()
  )
  on conflict (entity_type, entity_id) do update set
    section = excluded.section,
    title = excluded.title,
    summary = excluded.summary,
    searchable_text = excluded.searchable_text,
    updated_at = excluded.updated_at;

  delete from public.entity_tag
  where entity_type = entry->>'entityType'
    and entity_id = entry->>'entityId';

  for tag_slug in
    select distinct value
    from jsonb_array_elements_text(coalesce(entry->'tags', '[]'::jsonb))
    where length(value) >= 2
    limit 16
  loop
    insert into public.tag (nombre, slug, origen)
    values (initcap(replace(tag_slug, '-', ' ')), tag_slug, 'auto')
    on conflict (slug) do update set nombre = excluded.nombre
    returning id into tag_id;

    insert into public.entity_tag (tag_id, entity_type, entity_id, score)
    values (tag_id, entry->>'entityType', entry->>'entityId', 1)
    on conflict (tag_id, entity_type, entity_id) do nothing;
  end loop;
end;
$$;

create or replace function public.admin_upsert_search_entry(p_entry jsonb)
returns void
language plpgsql
security invoker
set search_path = public
as $$
begin
  -- ponytail: un lock global basta para el volumen admin; separar por indice solo si hay contencion medida.
  perform pg_advisory_xact_lock(hashtext('admin_replace_search_index'));
  perform private.admin_upsert_search_entry(p_entry);
end;
$$;

create or replace function public.admin_replace_search_index(p_entries jsonb)
returns jsonb
language plpgsql
security invoker
set search_path = public
as $$
declare
  entry jsonb;
  indexed_count integer := 0;
begin
  -- ponytail: comparte lock con el reemplazo global para no perder escrituras concurrentes.
  perform pg_advisory_xact_lock(hashtext('admin_replace_search_index'));

  delete from public.entity_tag;
  delete from public.search_index;

  for entry in select value from jsonb_array_elements(p_entries)
  loop
    perform private.admin_upsert_search_entry(entry);
    indexed_count := indexed_count + 1;
  end loop;

  return jsonb_build_object(
    'indexed', indexed_count,
    'tags', (select count(*) from public.tag)
  );
end;
$$;

revoke execute on function public.admin_sync_roles(uuid, uuid[]) from public, anon, authenticated;
revoke execute on function public.admin_grant_tickets(uuid, integer, text, text) from public, anon, authenticated;
revoke execute on function public.admin_create_question(jsonb, jsonb, jsonb, uuid, integer) from public, anon, authenticated;
revoke execute on function public.admin_replace_question_options(uuid, jsonb, jsonb) from public, anon, authenticated;
revoke execute on function public.admin_upsert_search_entry(jsonb) from public, anon, authenticated;
revoke execute on function public.admin_replace_search_index(jsonb) from public, anon, authenticated;
revoke execute on function private.admin_upsert_search_entry(jsonb) from public, anon, authenticated;

grant execute on function public.admin_sync_roles(uuid, uuid[]) to service_role;
grant execute on function public.admin_grant_tickets(uuid, integer, text, text) to service_role;
grant execute on function public.admin_create_question(jsonb, jsonb, jsonb, uuid, integer) to service_role;
grant execute on function public.admin_replace_question_options(uuid, jsonb, jsonb) to service_role;
grant execute on function public.admin_upsert_search_entry(jsonb) to service_role;
grant execute on function public.admin_replace_search_index(jsonb) to service_role;
grant execute on function private.admin_upsert_search_entry(jsonb) to service_role;

do $$
begin
  if to_regprocedure('public.admin_sync_roles(uuid,uuid[])') is null
    or to_regprocedure('public.admin_grant_tickets(uuid,integer,text,text)') is null
    or to_regprocedure('public.admin_create_question(jsonb,jsonb,jsonb,uuid,integer)') is null
    or to_regprocedure('public.admin_replace_question_options(uuid,jsonb,jsonb)') is null
    or to_regprocedure('public.admin_upsert_search_entry(jsonb)') is null
    or to_regprocedure('private.admin_upsert_search_entry(jsonb)') is null
    or to_regprocedure('public.admin_replace_search_index(jsonb)') is null then
    raise exception 'No se crearon todas las RPC atomicas de administracion.';
  end if;
end;
$$;
