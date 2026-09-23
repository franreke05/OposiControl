-- Q1 · codigo_pregunta lo genera la base: PREG-000001 (mínimo 6 dígitos, sin truncar), inmutable.
-- OQ-3: huecos aceptados (un INSERT que aborta, o que choca en ON CONFLICT DO NOTHING, consume número).
set local lock_timeout = '5s';
lock table public.preguntas in share row exclusive mode;   -- C: cierra la carrera entre el setval y la creación del trigger

do $pre$
begin
  if not exists (select 1 from pg_constraint
                  where conrelid = 'public.preguntas'::regclass
                    and conname = 'preguntas_codigo_pregunta_key' and contype = 'u') then
    raise exception 'PREFLIGHT_FAILED: falta UNIQUE (codigo_pregunta)';
  end if;
  if to_regclass('private.preguntas_codigo_pregunta_seq') is not null then
    raise exception 'PREFLIGHT_FAILED: la secuencia ya existe';
  end if;
  if exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
              where n.nspname = 'private' and p.proname = 'tg_preguntas_codigo_pregunta') then
    raise exception 'PREFLIGHT_FAILED: la función ya existe';
  end if;
  if exists (select 1 from pg_trigger
              where tgrelid = 'public.preguntas'::regclass and not tgisinternal
                and tgname in ('trg_preguntas_codigo_pregunta_insert', 'trg_preguntas_codigo_pregunta_update')) then
    raise exception 'PREFLIGHT_FAILED: los triggers ya existen';
  end if;
  if exists (select 1 from public.preguntas where codigo_pregunta ~ '^PREG-[0-9]{19,}$') then
    raise exception 'PREFLIGHT_FAILED: hay códigos PREG-n que no caben en bigint';
  end if;
end
$pre$;

create sequence private.preguntas_codigo_pregunta_seq
  as bigint minvalue 1 start with 1 increment by 1 no cycle cache 1;

-- Detrás del mayor PREG-n existente (hoy ninguno ⇒ el siguiente nextval es 1).
select setval(
  'private.preguntas_codigo_pregunta_seq',
  greatest(coalesce((select max(substring(codigo_pregunta from '^PREG-([0-9]{1,18})$')::bigint) from public.preguntas), 0), 1),
  coalesce((select max(substring(codigo_pregunta from '^PREG-([0-9]{1,18})$')::bigint) from public.preguntas), 0) > 0
);

create function private.tg_preguntas_codigo_pregunta()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_n bigint;
begin
  if tg_op = 'INSERT' then
    -- Siempre la base: cualquier código recibido (cliente, RPC, importación) se ignora.
    v_n := nextval('private.preguntas_codigo_pregunta_seq');
    new.codigo_pregunta := 'PREG-' || lpad(v_n::text, greatest(6, length(v_n::text)), '0');
    return new;
  end if;
  -- UPDATE OF codigo_pregunta: reenviar el mismo valor vale; cambiarlo, no.
  if new.codigo_pregunta is distinct from old.codigo_pregunta then
    raise exception 'QUESTION_CODE_IMMUTABLE'
      using hint = 'codigo_pregunta lo genera la base y no cambia al editar';
  end if;
  return new;
end;
$$;

revoke all on function private.tg_preguntas_codigo_pregunta() from public, anon, authenticated;

create trigger trg_preguntas_codigo_pregunta_insert
  before insert on public.preguntas
  for each row execute function private.tg_preguntas_codigo_pregunta();

create trigger trg_preguntas_codigo_pregunta_update
  before update of codigo_pregunta on public.preguntas
  for each row execute function private.tg_preguntas_codigo_pregunta();

comment on sequence private.preguntas_codigo_pregunta_seq is
  'Numerador de preguntas.codigo_pregunta (PREG-000001). Huecos aceptados (OQ-3). Sin OWNED BY: distinto esquema que la tabla.';
comment on function private.tg_preguntas_codigo_pregunta() is
  'BEFORE INSERT: asigna SIEMPRE codigo_pregunta (ignora el recibido). BEFORE UPDATE OF codigo_pregunta: QUESTION_CODE_IMMUTABLE. '
  'INSERT ... ON CONFLICT (codigo_pregunta) nunca hace match (el código es siempre nuevo). '
  'INSERT ... ON CONFLICT (id) DO UPDATE SET codigo_pregunta = excluded.codigo_pregunta lanza QUESTION_CODE_IMMUTABLE: los upserts deben excluir la columna. '
  'Cargas data-only con triggers activos regenerarían códigos: usar session_replication_role = replica.';

do $post$
begin
  if (select count(*) from pg_trigger
       where tgrelid = 'public.preguntas'::regclass and not tgisinternal
         and tgname in ('trg_preguntas_codigo_pregunta_insert', 'trg_preguntas_codigo_pregunta_update')) <> 2 then
    raise exception 'POSTCHECK_FAILED: faltan triggers';
  end if;
  if (select p.prosecdef from pg_proc p where p.oid = 'private.tg_preguntas_codigo_pregunta()'::regprocedure) is distinct from true then
    raise exception 'POSTCHECK_FAILED: la función no es SECURITY DEFINER';
  end if;
  if has_function_privilege('anon', 'private.tg_preguntas_codigo_pregunta()', 'EXECUTE')
     or has_function_privilege('authenticated', 'private.tg_preguntas_codigo_pregunta()', 'EXECUTE') then
    raise exception 'POSTCHECK_FAILED: ACL de la función abierta';
  end if;
  if not exists (select 1 from pg_sequences
                  where schemaname = 'private' and sequencename = 'preguntas_codigo_pregunta_seq' and data_type = 'bigint'::regtype) then
    raise exception 'POSTCHECK_FAILED: secuencia ausente';
  end if;
  if exists (select 1 from public.preguntas where codigo_pregunta is null or codigo_pregunta = '') then
    raise exception 'POSTCHECK_FAILED: filas sin código';
  end if;
end
$post$;
