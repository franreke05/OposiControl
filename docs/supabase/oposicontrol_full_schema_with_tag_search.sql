-- OposiControl schema base + busqueda global por etiquetas automaticas.
-- Este archivo conserva el esquema operativo indicado y anade tag/entity_tag/search_index.

create table if not exists public.app_role (
  id integer generated always as identity primary key,
  name character varying not null unique,
  description text,
  created_at timestamp with time zone not null default now()
);

create table if not exists public.especialidad (
  id integer generated always as identity primary key,
  nombre character varying not null,
  slug character varying not null unique,
  activa boolean not null default true,
  created_at timestamp with time zone not null default now(),
  updated_at timestamp with time zone not null default now(),
  deleted_at timestamp with time zone
);

create table if not exists public.usuario (
  id integer generated always as identity primary key,
  auth_user_id uuid unique,
  email character varying,
  nombre character varying,
  especialidad_id integer references public.especialidad(id),
  created_at timestamp with time zone not null default now(),
  updated_at timestamp with time zone not null default now(),
  deleted_at timestamp with time zone
);

create table if not exists public.user_role (
  id integer generated always as identity primary key,
  usuario_id integer not null references public.usuario(id),
  role_id integer not null references public.app_role(id),
  created_at timestamp with time zone not null default now()
);

create table if not exists public.examen (
  id integer generated always as identity primary key,
  titulo character varying not null,
  precio_tickets integer not null default 0,
  especialidad_id integer not null references public.especialidad(id),
  estado character varying not null default 'draft' check (estado in ('draft', 'published', 'archived')),
  publicar_desde timestamp with time zone,
  published_at timestamp with time zone,
  created_at timestamp with time zone not null default now(),
  updated_at timestamp with time zone not null default now(),
  deleted_at timestamp with time zone
);

create table if not exists public.acceso_examen_usuario (
  id integer generated always as identity primary key,
  usuario_id integer not null references public.usuario(id),
  examen_id integer not null references public.examen(id),
  tipo_acceso character varying not null,
  activo boolean not null default true,
  granted_by uuid,
  created_at timestamp with time zone not null default now(),
  updated_at timestamp with time zone not null default now()
);

create table if not exists public.tipo_noticia (
  id integer generated always as identity primary key,
  nombre character varying not null,
  slug character varying not null unique,
  activo boolean not null default true,
  created_at timestamp with time zone not null default now(),
  updated_at timestamp with time zone not null default now(),
  deleted_at timestamp with time zone
);

create table if not exists public.noticia (
  id integer generated always as identity primary key,
  titulo character varying not null,
  tipo_fondo character varying not null check (tipo_fondo in ('A', 'B', 'C')),
  especialidad_id integer not null references public.especialidad(id),
  texto text not null,
  tipo_noticia_id integer not null references public.tipo_noticia(id),
  imagen_path character varying,
  estado character varying not null default 'draft' check (estado in ('draft', 'scheduled', 'published', 'archived')),
  publicar_desde timestamp with time zone,
  published_at timestamp with time zone,
  created_by uuid references auth.users(id),
  created_at timestamp with time zone not null default now(),
  updated_at timestamp with time zone not null default now(),
  deleted_at timestamp with time zone
);

create table if not exists public.pregunta (
  id integer generated always as identity primary key,
  examen_id integer not null references public.examen(id),
  enunciado text not null,
  orden integer not null default 0,
  created_at timestamp with time zone not null default now(),
  updated_at timestamp with time zone not null default now(),
  deleted_at timestamp with time zone
);

create table if not exists public.recurso_pregunta (
  id integer generated always as identity primary key,
  pregunta_id integer not null references public.pregunta(id),
  tipo_recurso character varying not null check (tipo_recurso in ('imagen', 'audio', 'video', 'documento')),
  url character varying not null,
  created_at timestamp with time zone not null default now(),
  updated_at timestamp with time zone not null default now(),
  deleted_at timestamp with time zone
);

create table if not exists public.articulo_tienda (
  id integer generated always as identity primary key,
  nombre character varying not null,
  tipo_articulo character varying not null check (tipo_articulo in ('protector', 'racha', 'consumible', 'potenciador')),
  precio_tickets integer not null default 0,
  activo boolean not null default true,
  created_at timestamp with time zone not null default now(),
  updated_at timestamp with time zone not null default now(),
  deleted_at timestamp with time zone
);

create table if not exists public.cartera_tickets (
  usuario_id integer primary key references public.usuario(id),
  saldo numeric not null default 0,
  created_at timestamp with time zone not null default now(),
  updated_at timestamp with time zone not null default now()
);

create table if not exists public.compra_usuario (
  id integer generated always as identity primary key,
  usuario_id integer not null references public.usuario(id),
  articulo_tienda_id integer not null references public.articulo_tienda(id),
  cantidad integer not null check (cantidad > 0),
  precio_unitario numeric not null,
  total numeric not null,
  created_at timestamp with time zone not null default now()
);

create table if not exists public.inventario_usuario (
  id integer generated always as identity primary key,
  usuario_id integer not null references public.usuario(id),
  articulo_tienda_id integer not null references public.articulo_tienda(id),
  cantidad integer not null default 0 check (cantidad >= 0),
  created_at timestamp with time zone not null default now(),
  updated_at timestamp with time zone not null default now()
);

create table if not exists public.transaccion_ticket (
  id integer generated always as identity primary key,
  usuario_id integer not null references public.usuario(id),
  tipo character varying not null check (tipo in ('credito', 'debito')),
  monto numeric not null check (monto > 0),
  razon character varying not null,
  referencia_tipo character varying,
  referencia_id integer,
  created_at timestamp with time zone not null default now()
);

create table if not exists public.support_conversation (
  id integer generated always as identity primary key,
  student_usuario_id integer not null references public.usuario(id),
  assigned_usuario_id integer references public.usuario(id),
  estado character varying not null default 'open' check (estado in ('open', 'pending', 'resolved', 'closed')),
  titulo character varying,
  created_at timestamp with time zone not null default now(),
  updated_at timestamp with time zone not null default now(),
  closed_at timestamp with time zone
);

create table if not exists public.support_message (
  id integer generated always as identity primary key,
  conversation_id integer not null references public.support_conversation(id),
  sender_usuario_id integer not null references public.usuario(id),
  body text,
  attachment_path character varying,
  internal_note boolean not null default false,
  created_at timestamp with time zone not null default now()
);

create table if not exists public.support_participant (
  id integer generated always as identity primary key,
  conversation_id integer not null references public.support_conversation(id),
  usuario_id integer not null references public.usuario(id),
  role_in_conversation character varying not null check (role_in_conversation in ('student', 'admin', 'moderator')),
  created_at timestamp with time zone not null default now()
);

create table if not exists public.moderation_action (
  id integer generated always as identity primary key,
  target_usuario_id integer not null references public.usuario(id),
  actor_usuario_id integer not null references public.usuario(id),
  conversation_id integer references public.support_conversation(id),
  action_type character varying not null check (action_type in ('warning', 'mute', 'close_conversation', 'ban', 'internal_note')),
  reason text not null,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamp with time zone not null default now()
);

create table if not exists public.audit_log (
  id bigint generated always as identity primary key,
  actor_auth_user_id uuid,
  actor_usuario_id integer references public.usuario(id),
  action character varying not null,
  resource character varying not null,
  resource_id character varying,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamp with time zone not null default now()
);

create table if not exists public.tag (
  id integer generated always as identity primary key,
  nombre character varying not null,
  slug character varying not null unique,
  origen character varying not null default 'auto',
  created_at timestamp with time zone not null default now()
);

create table if not exists public.search_index (
  id bigint generated always as identity primary key,
  entity_type character varying not null,
  entity_id character varying not null,
  section character varying not null,
  title character varying not null,
  summary text not null default '',
  searchable_text text not null default '',
  updated_at timestamp with time zone not null default now(),
  constraint search_index_entity_unique unique (entity_type, entity_id)
);

create table if not exists public.entity_tag (
  id bigint generated always as identity primary key,
  tag_id integer not null references public.tag(id) on delete cascade,
  entity_type character varying not null,
  entity_id character varying not null,
  score numeric not null default 1,
  created_at timestamp with time zone not null default now(),
  constraint entity_tag_unique unique (tag_id, entity_type, entity_id)
);

create index if not exists idx_noticia_deleted_at on public.noticia (deleted_at);
create index if not exists idx_examen_especialidad on public.examen (especialidad_id);
create index if not exists idx_pregunta_examen on public.pregunta (examen_id, orden);
create index if not exists idx_recurso_pregunta on public.recurso_pregunta (pregunta_id);
create index if not exists idx_tag_slug on public.tag (slug);
create index if not exists idx_entity_tag_entity on public.entity_tag (entity_type, entity_id);
create index if not exists idx_entity_tag_tag_type on public.entity_tag (tag_id, entity_type);
create index if not exists idx_search_index_section on public.search_index (section);
create index if not exists idx_search_index_text_gin
  on public.search_index
  using gin (to_tsvector('simple', searchable_text));
