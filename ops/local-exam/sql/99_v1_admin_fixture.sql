-- V1 admin path for local end-to-end checks (LOCAL ONLY). One admin user so the CONTROL profile (second Ktor, port ktorControl, stack-config.mjs) can be called with a real JWT:
-- "an exam without community is rejected", "an unknown community is rejected", "publishing needs a community". JWT: .local/tokens.json key "ADMIN".
-- The admin role is the canonical `admin` code in roles_app (CanonicalAuthorization.adminRoleCodes); roles_usuario links the user to it. Idempotent.
set session_replication_role = replica;
insert into auth.users (id, email, aud, role, raw_user_meta_data, created_at, updated_at)
values ('3a000000-0000-4000-8000-00000000003a', 'admin@exam.local', 'authenticated', 'authenticated', '{}'::jsonb, now(), now())
on conflict (id) do nothing;
set session_replication_role = origin;

insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta)
values ('3a000000-0000-4000-8000-00000000003a', 'Admin local', 'admin_local', true, 'activa')
on conflict (usuario_id) do nothing;

insert into public.roles_app (id, codigo, nombre)
select '3a0a0000-0000-4000-8000-000000000001', 'admin', 'Administrador'
where not exists (select 1 from public.roles_app where codigo = 'admin');

insert into public.roles_usuario (usuario_id, rol_id)
select '3a000000-0000-4000-8000-00000000003a', r.id from public.roles_app r where r.codigo = 'admin'
on conflict do nothing;
