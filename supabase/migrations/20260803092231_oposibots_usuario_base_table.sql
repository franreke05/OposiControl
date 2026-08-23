create table if not exists public.usuario (
  id integer generated always as identity primary key,
  auth_user_id uuid unique,
  email character varying,
  nombre character varying,
  created_at timestamp with time zone not null default now(),
  updated_at timestamp with time zone not null default now(),
  deleted_at timestamp with time zone
);
