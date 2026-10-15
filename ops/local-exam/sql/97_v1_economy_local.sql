-- V1 economy, LOCAL ONLY (never a migration, never MAIN). Applied by up.mjs after the V1 migrations 20261018100100..300. Idempotent.
--
-- 1. The sandbox payment provider exists ONLY in the local stack: the production CHECK on compras_usuario.proveedor
--    (google_play, app_store, stripe, manual) is widened here with 'sandbox' and nowhere else (the PG17 gate verifies that no migration does it).
-- 2. Older local users have a profile but no wallet row (the row is created with the profile since 20261018100100): give them the empty wallet
--    a new user gets, so the local stack matches what production does for new accounts. Zero-balance rows are allowed by the snapshot guard.
alter table public.compras_usuario drop constraint if exists compras_usuario_proveedor_check;
alter table public.compras_usuario add constraint compras_usuario_proveedor_check
  check ((proveedor = any (array['google_play'::text, 'app_store'::text, 'stripe'::text, 'manual'::text, 'sandbox'::text])));

insert into public.carteras_usuario (usuario_id, saldo_tickets)
select p.usuario_id, 0 from public.perfiles_usuario p
where not exists (select 1 from public.carteras_usuario c where c.usuario_id = p.usuario_id)
on conflict (usuario_id) do nothing;
