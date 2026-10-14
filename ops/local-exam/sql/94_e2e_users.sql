-- E5-B03 (LOCAL ONLY). Usuarios PROPIOS del e2e: XA 1a000000-…-1a y XB 1b000000-…-1b (JWT "XA"/"XB" en .local/tokens.json).
-- e2e.mjs abre, abandona y agota limites SOLO con ellos; A, B y P quedan para los recorridos de la app (el e2e solo los lee).
-- Idempotente (on conflict do nothing). Sin cartera, sin premium.
set session_replication_role = replica;

insert into auth.users (id, email, aud, role, raw_user_meta_data, created_at, updated_at)
values ('1a000000-0000-4000-8000-00000000001a', 'xa@exam.local', 'authenticated', 'authenticated', '{}'::jsonb, now(), now()),
       ('1b000000-0000-4000-8000-00000000001b', 'xb@exam.local', 'authenticated', 'authenticated', '{}'::jsonb, now(), now())
on conflict (id) do nothing;

insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta)
values ('1a000000-0000-4000-8000-00000000001a', 'E2E XA', 'e2e_xa', true, 'activa'),
       ('1b000000-0000-4000-8000-00000000001b', 'E2E XB', 'e2e_xb', true, 'activa')
on conflict (usuario_id) do nothing;

set session_replication_role = origin;
