-- LOCAL ONLY. idem_complete / idem_release de supabase/migrations/20260928100100_idempotency_keys.sql (en la historia de
-- MAIN, sin redefiniciones posteriores en el repo), copiadas literalmente salvo create -> create or replace (idempotente).
-- supabase/baseline/main_shape_subset.sql solo arrastra idem_begin (semilla de assertions.sql): sin estas dos, el replay
-- idempotente de D2-08 responde 409 IDEMPOTENCY_KEY_IN_PROGRESS en un stack recien creado. Pendiente: anadirlas como
-- semillas del subconjunto en la proxima regeneracion del baseline.

create or replace function public.idem_complete(
    p_record_id uuid,
    p_claim_token uuid,
    p_status integer,
    p_body jsonb,
    p_headers jsonb default '{}'::jsonb
)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    v_headers jsonb := coalesce(p_headers, '{}'::jsonb);
begin
    if p_status is null or p_status not between 200 and 299 then
        raise exception using errcode = '22023', message = 'IDEM_INVALID_STATUS';
    end if;
    if jsonb_typeof(v_headers) <> 'object'
       or exists (
           select 1 from jsonb_each(v_headers) h
           where h.key not in ('Location', 'Content-Type') or jsonb_typeof(h.value) <> 'string'
       ) then
        raise exception using errcode = '22023', message = 'IDEM_INVALID_HEADERS';
    end if;

    update public.idempotency_keys k
    set state = 'completed',
        response_status = p_status,
        response_body = p_body,
        response_headers = v_headers,
        completed_at = now(),
        locked_until = null
    where k.id = p_record_id and k.claim_token = p_claim_token and k.state = 'in_progress';

    return found;
end;
$$;

create or replace function public.idem_release(p_record_id uuid, p_claim_token uuid)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
    delete from public.idempotency_keys k
    where k.id = p_record_id and k.claim_token = p_claim_token and k.state = 'in_progress';
    return found;
end;
$$;

revoke all on function public.idem_complete(uuid, uuid, integer, jsonb, jsonb) from public, anon, authenticated;
revoke all on function public.idem_release(uuid, uuid) from public, anon, authenticated;
grant execute on function public.idem_complete(uuid, uuid, integer, jsonb, jsonb) to service_role;
grant execute on function public.idem_release(uuid, uuid) to service_role;

notify pgrst, 'reload schema';
