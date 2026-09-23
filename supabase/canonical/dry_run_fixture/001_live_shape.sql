-- Disposable-only fixture for the Canonical V1 V2 dry-run.
-- This is not a production migration and must never be placed in supabase/migrations.

begin;

create table if not exists public.perfiles_usuario (
  usuario_id uuid primary key references auth.users(id) on delete cascade,
  nombre_mostrar text,
  username text,
  avatar_url text,
  actualizado_en timestamptz not null default now()
);

create table if not exists public.roles_app (
  id uuid primary key default gen_random_uuid(),
  codigo text not null unique,
  nombre text not null,
  descripcion text
);

create table if not exists public.roles_usuario (
  usuario_id uuid not null references auth.users(id) on delete cascade,
  rol_id uuid not null references public.roles_app(id) on delete cascade,
  primary key (usuario_id, rol_id)
);

create table if not exists public.usuario (
  id integer generated always as identity primary key,
  auth_user_id uuid unique,
  email varchar,
  nombre varchar,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table if not exists public.conversaciones_soporte (
  id uuid primary key default gen_random_uuid(),
  usuario_id uuid not null references auth.users(id),
  asunto text,
  creado_en timestamptz not null default now()
);

create table if not exists public.especialidades (
  id uuid primary key default gen_random_uuid(),
  nombre text not null
);

create table if not exists public.examenes (
  id uuid primary key default gen_random_uuid(),
  codigo_examen text not null unique,
  especialidad_id uuid not null references public.especialidades(id),
  comunidad_id uuid,
  titulo text not null,
  descripcion text,
  tipo_examen text not null,
  tipo_acceso text not null,
  total_preguntas integer not null default 50,
  duracion_minutos integer,
  anio integer,
  es_oficial boolean not null default false,
  activo boolean not null default false,
  publicado_en timestamptz,
  creado_por uuid references auth.users(id),
  creado_en timestamptz not null default now(),
  actualizado_en timestamptz not null default now(),
  comprable_con_tickets boolean not null default false,
  precio_tickets integer,
  dias_acceso_compra integer not null default 30,
  demo_disponible boolean not null default false,
  requiere_plan_codigo text,
  creditos_inmersivos_necesarios integer not null default 0
);

alter table public.examenes drop constraint if exists examenes_tipo_examen_check;
alter table public.examenes add constraint examenes_tipo_examen_check
  check (tipo_examen in ('rafaga', 'guiado', 'inmersivo'));
alter table public.examenes drop constraint if exists examenes_tipo_acceso_check;
alter table public.examenes add constraint examenes_tipo_acceso_check
  check (tipo_acceso in ('gratuito', 'premium'));
alter table public.examenes drop constraint if exists examenes_tipo_y_acceso_chk;
alter table public.examenes add constraint examenes_tipo_y_acceso_chk
  check ((tipo_examen = 'inmersivo' and tipo_acceso = 'premium') or
         (tipo_examen = 'rafaga' and tipo_acceso = 'gratuito'));

create table if not exists public.preguntas (
  id uuid primary key default gen_random_uuid(),
  codigo_pregunta text not null,
  titulo text not null,
  tipo_pregunta text not null,
  activa boolean not null default true
);
alter table public.preguntas drop constraint if exists preguntas_tipo_pregunta_check;
alter table public.preguntas add constraint preguntas_tipo_pregunta_check
  check (tipo_pregunta in ('rafaga', 'guiada', 'inmersiva'));

create table if not exists public.intentos_examen (
  id uuid primary key default gen_random_uuid(),
  usuario_id uuid not null references auth.users(id),
  tipo_examen_snapshot text not null
);
alter table public.intentos_examen drop constraint if exists intentos_examen_tipo_examen_snapshot_check;
alter table public.intentos_examen add constraint intentos_examen_tipo_examen_snapshot_check
  check (tipo_examen_snapshot in ('rafaga', 'guiado', 'inmersivo'));

create table if not exists public.lotes_importacion_preguntas (
  id uuid primary key default gen_random_uuid(),
  tipo_examen text not null
);
alter table public.lotes_importacion_preguntas drop constraint if exists lotes_importacion_preguntas_tipo_examen_check;
alter table public.lotes_importacion_preguntas add constraint lotes_importacion_preguntas_tipo_examen_check
  check (tipo_examen in ('rafaga', 'guiado', 'inmersivo'));

create table if not exists public.creditos_contenido_usuario (
  id uuid primary key default gen_random_uuid(),
  usuario_id uuid not null references auth.users(id),
  tipo_examen text not null
);
alter table public.creditos_contenido_usuario drop constraint if exists creditos_contenido_usuario_tipo_examen_check;
alter table public.creditos_contenido_usuario add constraint creditos_contenido_usuario_tipo_examen_check
  check (tipo_examen in ('rafaga', 'guiado', 'inmersivo'));

create table if not exists public.reglas_popup_comercial (
  id uuid primary key default gen_random_uuid(),
  tipo_examen_objetivo text,
  activo boolean not null default true
);
alter table public.reglas_popup_comercial drop constraint if exists reglas_popup_comercial_tipo_examen_objetivo_check;
alter table public.reglas_popup_comercial add constraint reglas_popup_comercial_tipo_examen_objetivo_check
  check (tipo_examen_objetivo is null or tipo_examen_objetivo in ('rafaga', 'guiado', 'inmersivo'));

create table if not exists public.preguntas_reto_diario (
  id uuid primary key default gen_random_uuid(),
  tipo_slot text not null
);
alter table public.preguntas_reto_diario drop constraint if exists preguntas_reto_diario_tipo_slot_check;
alter table public.preguntas_reto_diario add constraint preguntas_reto_diario_tipo_slot_check
  check (tipo_slot in ('inmersiva_demo', 'oposicard', 'rafaga', 'guiada'));

create table if not exists public.capacidades_especialidad (
  especialidad_id uuid primary key references public.especialidades(id) on delete cascade,
  permite_rafagas boolean not null default true,
  permite_razonados boolean not null default false,
  permite_inmersivos boolean not null default false,
  permite_demos boolean not null default true,
  permite_oposimarket boolean not null default true,
  mensaje_limitacion text,
  actualizado_en timestamptz not null default now()
);

create table if not exists public.planes_suscripcion (
  id uuid primary key default gen_random_uuid(),
  codigo text not null unique,
  nombre text not null,
  descripcion text,
  nivel integer not null,
  activo boolean not null default true,
  visible boolean not null default true,
  posicion integer not null default 1,
  creado_en timestamptz not null default now(),
  actualizado_en timestamptz not null default now()
);

create table if not exists public.beneficios_plan (
  id uuid primary key default gen_random_uuid(),
  plan_id uuid not null references public.planes_suscripcion(id) on delete cascade,
  codigo_beneficio text not null,
  valor_boolean boolean,
  valor_integer integer,
  valor_numeric numeric,
  valor_text text,
  metadatos jsonb not null default '{}'::jsonb,
  creado_en timestamptz not null default now(),
  unique (plan_id, codigo_beneficio)
);

create table if not exists public.productos_comerciales (
  id uuid primary key default gen_random_uuid(),
  codigo text not null unique,
  tipo_producto text not null,
  nombre text not null,
  descripcion text,
  precio_centimos integer,
  moneda text not null default 'EUR',
  cantidad_tickets integer,
  activo boolean not null default true,
  visible boolean not null default true,
  destacado boolean not null default false,
  etiqueta_promocional text,
  posicion integer not null default 1,
  metadatos jsonb not null default '{}'::jsonb,
  creado_en timestamptz not null default now(),
  actualizado_en timestamptz not null default now()
);

create table if not exists public.suscripciones (
  id uuid primary key default gen_random_uuid(),
  usuario_id uuid not null references auth.users(id) on delete cascade,
  proveedor text not null,
  producto_codigo text not null,
  estado text not null,
  inicio_en timestamptz not null,
  fin_en timestamptz,
  renovacion_en timestamptz,
  identificador_externo text,
  payload_validacion jsonb not null default '{}'::jsonb,
  creado_en timestamptz not null default now(),
  actualizado_en timestamptz not null default now()
);
alter table public.suscripciones add constraint suscripciones_estado_check
  check (estado in ('activa', 'pausada', 'cancelada', 'caducada', 'periodo_prueba'));
alter table public.suscripciones add constraint suscripciones_proveedor_check
  check (proveedor in ('google_play', 'app_store', 'stripe', 'manual'));

create table if not exists public.estado_premium_usuario (
  usuario_id uuid primary key references auth.users(id) on delete cascade,
  es_premium boolean not null default false,
  estado_suscripcion text not null default 'sin_suscripcion',
  producto_codigo text,
  proveedor text,
  inicio_en timestamptz,
  fin_en timestamptz,
  renovacion_en timestamptz,
  actualizado_en timestamptz not null default now()
);

create table if not exists public.atribucion_usuario (id uuid primary key default gen_random_uuid(), usuario_id uuid);
create table if not exists public.comunidades_objetivo_usuario (id uuid primary key default gen_random_uuid(), usuario_id uuid);
create table if not exists public.efectos_servicio (id uuid primary key default gen_random_uuid(), activo boolean not null default true);
create table if not exists public.estado_competitivo_usuario (usuario_id uuid primary key references auth.users(id));
create table if not exists public.estado_opositester_usuario (usuario_id uuid primary key references auth.users(id));
create table if not exists public.ligas_nivel (id uuid primary key default gen_random_uuid(), activa boolean not null default true);
create table if not exists public.perfiles_usuario_detalle (usuario_id uuid primary key references auth.users(id));
create table if not exists public.popup_usuario_historial (id uuid primary key default gen_random_uuid(), usuario_id uuid);
create table if not exists public.productos_store (id uuid primary key default gen_random_uuid(), activo boolean not null default true);
create table if not exists public.promociones (id uuid primary key default gen_random_uuid(), activo boolean not null default true, valido_desde timestamptz, valido_hasta timestamptz);
create table if not exists public.resumen_intento_etiqueta (id uuid primary key default gen_random_uuid(), intento_id uuid not null references public.intentos_examen(id));
create table if not exists public.usos_promocion_usuario (id uuid primary key default gen_random_uuid(), usuario_id uuid);
create table if not exists public.valor_usuario_resumen (id uuid primary key default gen_random_uuid(), usuario_id uuid);

create table if not exists public.opositeca_items (id uuid primary key default gen_random_uuid(), titulo text);
create table if not exists public.etiquetas_opositeca (id uuid primary key default gen_random_uuid(), nombre text);
create table if not exists public.categorias_oposired (id uuid primary key default gen_random_uuid(), nombre text);
create table if not exists public.publicaciones_oposired (id uuid primary key default gen_random_uuid(), titulo text);
create table if not exists public.comentarios_oposired (id uuid primary key default gen_random_uuid(), publicacion_id uuid);
create table if not exists public.reacciones_oposired (id uuid primary key default gen_random_uuid(), publicacion_id uuid);
create table if not exists public.preguntas_parseadas_staging (id uuid primary key default gen_random_uuid(), payload jsonb);

alter table public.atribucion_usuario enable row level security;
alter table public.beneficios_plan enable row level security;
alter table public.capacidades_especialidad enable row level security;
alter table public.comunidades_objetivo_usuario enable row level security;
alter table public.creditos_contenido_usuario enable row level security;
alter table public.efectos_servicio enable row level security;
alter table public.estado_competitivo_usuario enable row level security;
alter table public.estado_opositester_usuario enable row level security;
alter table public.ligas_nivel enable row level security;
alter table public.perfiles_usuario_detalle enable row level security;
alter table public.planes_suscripcion enable row level security;
alter table public.popup_usuario_historial enable row level security;
alter table public.preguntas_reto_diario enable row level security;
alter table public.productos_comerciales enable row level security;
alter table public.productos_store enable row level security;
alter table public.promociones enable row level security;
alter table public.reglas_popup_comercial enable row level security;
alter table public.resumen_intento_etiqueta enable row level security;
alter table public.usos_promocion_usuario enable row level security;
alter table public.valor_usuario_resumen enable row level security;

insert into public.roles_app (codigo, nombre)
values
  ('superadmin', 'Superadmin'),
  ('admin', 'Admin'),
  ('moderador', 'Moderador'),
  ('usuario', 'Usuario')
on conflict (codigo) do nothing;

create or replace function public.es_admin()
returns boolean language sql stable security definer set search_path = public
as $$
  select exists (
    select 1 from public.roles_usuario ru
    join public.roles_app ra on ra.id = ru.rol_id
    where ru.usuario_id = auth.uid()
      and ra.codigo in ('superadmin', 'admin')
  );
$$;

create or replace function public.es_moderador_o_admin()
returns boolean language sql stable security definer set search_path = public
as $$
  select exists (
    select 1 from public.roles_usuario ru
    join public.roles_app ra on ra.id = ru.rol_id
    where ru.usuario_id = auth.uid()
      and ra.codigo in ('superadmin', 'admin', 'moderador')
  );
$$;

create or replace function public.can_manage_content()
returns boolean language sql stable security definer set search_path = public
as $$ select public.es_admin(); $$;

create or replace function public.can_moderate()
returns boolean language sql stable security definer set search_path = public
as $$ select public.es_moderador_o_admin(); $$;

create or replace function public.can_access_conversation(p_conversation_id uuid)
returns boolean language sql stable security definer set search_path = public
as $$
  select public.es_moderador_o_admin() or exists (
    select 1 from public.conversaciones_soporte cs
    where cs.id = p_conversation_id and cs.usuario_id = auth.uid()
  );
$$;

create or replace function public.can_access_conversation(p_conversation_id integer)
returns boolean language sql stable security definer set search_path = public
as $$ select false; $$;

create or replace function public.can_access_exam(p_examen_id integer)
returns boolean language sql stable security definer set search_path = public
as $$ select false; $$;

create or replace function public.can_access_question(p_question_id integer)
returns boolean language sql stable security definer set search_path = public
as $$ select false; $$;

create or replace function public.has_role(p_role text)
returns boolean language sql stable security definer set search_path = public
as $$ select false; $$;

create or replace function public.has_any_role(p_roles text[])
returns boolean language sql stable security definer set search_path = public
as $$ select false; $$;

create or replace function public.current_usuario_id()
returns integer language sql stable security definer set search_path = public
as $$ select null::integer; $$;

create or replace function public.current_especialidad_id()
returns integer language sql stable security definer set search_path = public
as $$ select null::integer; $$;

create or replace function public.ensure_wallet_for_usuario()
returns void language sql security definer set search_path = public
as $$ select null::void; $$;

create or replace function public.fn_audit_row_change()
returns trigger language plpgsql security definer set search_path = public
as $$ begin return coalesce(new, old); end; $$;

create or replace function public.rpc_grant_exam_access(p_usuario_id integer, p_examen_id integer, p_tipo_acceso varchar, p_activo boolean)
returns jsonb language sql security definer set search_path = public
as $$ select jsonb_build_object('ok', false); $$;

create or replace function public.rpc_purchase_articulo(p_articulo_tienda_id integer, p_cantidad integer)
returns jsonb language sql security definer set search_path = public
as $$ select jsonb_build_object('ok', false); $$;

create or replace function public.oposibots_upsert_own_profile(p_first_name text, p_last_name text, p_username text, p_specialty text)
returns void language sql security definer set search_path = public
as $$ select null::void; $$;

create or replace function public.handle_new_auth_user()
returns trigger language plpgsql security definer set search_path = public
as $$
begin
  insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, avatar_url, actualizado_en)
  values (
    new.id,
    coalesce(new.raw_user_meta_data ->> 'nombre', new.raw_user_meta_data ->> 'name', split_part(coalesce(new.email, 'usuario'), '@', 1)),
    nullif(new.raw_user_meta_data ->> 'username', ''),
    nullif(new.raw_user_meta_data ->> 'avatar_url', ''),
    now()
  )
  on conflict (usuario_id) do update set nombre_mostrar = excluded.nombre_mostrar, actualizado_en = now();
  return new;
end;
$$;

drop trigger if exists trg_auth_user_created on auth.users;
create trigger trg_auth_user_created
after insert or update of email, raw_user_meta_data on auth.users
for each row execute function public.handle_new_auth_user();

create or replace function public.establecer_actualizado_en()
returns trigger language plpgsql set search_path = public
as $$ begin new.actualizado_en = now(); return new; end; $$;

create or replace function public.sincronizar_estado_premium_usuario()
returns trigger language plpgsql security definer set search_path = public
as $$
declare
  v_usuario_id uuid := coalesce(new.usuario_id, old.usuario_id);
  v_suscripcion record;
begin
  select s.* into v_suscripcion from public.suscripciones s
  where s.usuario_id = v_usuario_id and s.estado in ('activa', 'periodo_prueba')
    and (s.fin_en is null or s.fin_en > now())
  order by s.renovacion_en desc nulls last, s.fin_en desc nulls last limit 1;
  if v_suscripcion.usuario_id is not null then
    insert into public.estado_premium_usuario (usuario_id, es_premium, estado_suscripcion, producto_codigo, proveedor, inicio_en, fin_en, renovacion_en)
    values (v_usuario_id, true, v_suscripcion.estado, v_suscripcion.producto_codigo, v_suscripcion.proveedor, v_suscripcion.inicio_en, v_suscripcion.fin_en, v_suscripcion.renovacion_en)
    on conflict (usuario_id) do update set es_premium = true, estado_suscripcion = excluded.estado_suscripcion, producto_codigo = excluded.producto_codigo, proveedor = excluded.proveedor, inicio_en = excluded.inicio_en, fin_en = excluded.fin_en, renovacion_en = excluded.renovacion_en, actualizado_en = now();
  else
    insert into public.estado_premium_usuario (usuario_id, es_premium, estado_suscripcion)
    values (v_usuario_id, false, 'sin_suscripcion')
    on conflict (usuario_id) do update set es_premium = false, estado_suscripcion = 'sin_suscripcion', producto_codigo = null, proveedor = null, inicio_en = null, fin_en = null, renovacion_en = null, actualizado_en = now();
  end if;
  return coalesce(new, old);
end;
$$;

drop trigger if exists trg_sincronizar_estado_premium_usuario on public.suscripciones;
create trigger trg_sincronizar_estado_premium_usuario
after insert or delete or update on public.suscripciones
for each row execute function public.sincronizar_estado_premium_usuario();

drop trigger if exists trg_suscripciones_actualizado_en on public.suscripciones;
create trigger trg_suscripciones_actualizado_en
before update on public.suscripciones
for each row execute function public.establecer_actualizado_en();

insert into storage.buckets (id, name, public)
values ('exam-resources', 'exam-resources', false), ('news-assets', 'news-assets', false)
on conflict (id) do nothing;

do $$
begin
  if exists (
       select 1 from information_schema.columns
       where table_schema = 'public'
         and table_name = 'capacidades_especialidad'
         and column_name = 'permite_guiados'
     )
     or not exists (
       select 1 from information_schema.columns
       where table_schema = 'public'
         and table_name = 'capacidades_especialidad'
         and column_name = 'permite_razonados'
     ) then
    raise exception 'CANONICAL_V2_FIXTURE_CAPABILITY_COLUMN_POSTCONDITION_FAILED';
  end if;
end;
$$;

commit;
