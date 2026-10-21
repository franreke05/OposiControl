-- LOCAL ONLY. auth.uid()/auth.role()/auth.email() in the MAIN shape (claims-aware), as GoTrue's own migration leaves them in MAIN.
-- The local stack never runs GoTrue's migrations (host stub; only `up.mjs --real-auth` would), so the image keeps the legacy bodies that read
-- only `request.jwt.claim.sub`. PostgREST v12+ sets only `request.jwt.claims` (JSON): auth.uid() was NULL for every PostgREST client request
-- and RLS such as es_admin() denied silently (J18 FA36b: admin PATCH -> 200 with 0 rows). Idempotent; applied as supabase_admin by up.mjs.
create or replace function auth.uid() returns uuid language sql stable as $$
  select coalesce(
    nullif(current_setting('request.jwt.claim.sub', true), ''),
    (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub')
  )::uuid
$$;

create or replace function auth.role() returns text language sql stable as $$
  select coalesce(
    nullif(current_setting('request.jwt.claim.role', true), ''),
    (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'role')
  )::text
$$;

create or replace function auth.email() returns text language sql stable as $$
  select coalesce(
    nullif(current_setting('request.jwt.claim.email', true), ''),
    (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'email')
  )::text
$$;
