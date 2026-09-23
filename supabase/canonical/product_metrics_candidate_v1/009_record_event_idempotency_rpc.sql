-- PRODUCT METRICS CANDIDATE V1 — IDEMPOTENT EVENT WRITER
-- Ktor uses this service-role-only RPC so identical retries reuse the event row.
begin;

create or replace function public.record_event(
  p_usuario_id uuid,
  p_nombre_evento text,
  p_pantalla text default null,
  p_entidad_tipo text default null,
  p_entidad_id uuid default null,
  p_propiedades jsonb default '{}'::jsonb,
  p_event_version integer default 1,
  p_occurred_en timestamptz default now(),
  p_especialidad_id uuid default null,
  p_intento_id uuid default null,
  p_pregunta_id uuid default null,
  p_idempotency_key text default null
)
returns table (event_id uuid)
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_existing public.eventos_app%rowtype;
  v_event_id uuid;
begin
  if p_event_version is null or p_event_version <= 0 then
    raise exception 'EVENT_VERSION_INVALID';
  end if;
  if p_idempotency_key is null or length(btrim(p_idempotency_key)) = 0 then
    raise exception 'EVENT_IDEMPOTENCY_KEY_REQUIRED';
  end if;

  select e.* into v_existing
  from public.eventos_app e
  where e.idempotency_key = btrim(p_idempotency_key)
  for update;

  if found then
    if v_existing.usuario_id is not distinct from p_usuario_id
      and v_existing.nombre_evento is not distinct from p_nombre_evento
      and v_existing.pantalla is not distinct from p_pantalla
      and v_existing.entidad_tipo is not distinct from p_entidad_tipo
      and v_existing.entidad_id is not distinct from p_entidad_id
      and v_existing.propiedades is not distinct from coalesce(p_propiedades, '{}'::jsonb)
      and v_existing.event_version is not distinct from p_event_version
      and v_existing.especialidad_id is not distinct from p_especialidad_id
      and v_existing.intento_id is not distinct from p_intento_id
      and v_existing.pregunta_id is not distinct from p_pregunta_id then
      return query select v_existing.id;
      return;
    end if;
    raise exception 'EVENT_IDEMPOTENCY_CONFLICT';
  end if;

  insert into public.eventos_app (
    usuario_id, nombre_evento, pantalla, entidad_tipo, entidad_id,
    propiedades, event_version, occurred_en, received_en,
    especialidad_id, intento_id, pregunta_id, idempotency_key
  ) values (
    p_usuario_id, p_nombre_evento, p_pantalla, p_entidad_tipo, p_entidad_id,
    coalesce(p_propiedades, '{}'::jsonb), p_event_version, coalesce(p_occurred_en, now()), now(),
    p_especialidad_id, p_intento_id, p_pregunta_id, btrim(p_idempotency_key)
  ) returning id into v_event_id;

  return query select v_event_id;
end;
$$;

revoke execute on function public.record_event(uuid, text, text, text, uuid, jsonb, integer, timestamptz, uuid, uuid, uuid, text)
  from public, anon, authenticated;
grant execute on function public.record_event(uuid, text, text, text, uuid, jsonb, integer, timestamptz, uuid, uuid, uuid, text)
  to service_role;

commit;
