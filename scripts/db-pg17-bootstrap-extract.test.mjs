// Prueba de db-pg17-bootstrap-extract.mjs (DB-CI-PG17) con una captura SINTETICA (sin red ni MAIN): cierre por FK, trigger y
// politica; se omiten lo que queda fuera del cierre y los ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin; los comentarios
// de la captura no se copian; una sentencia sin cerrar falla; la salida es determinista.
import test from 'node:test';
import assert from 'node:assert/strict';
import { extract, splitStatements } from './db-pg17-bootstrap-extract.mjs';

const baseline = `-- captura de PROYECTO-SECRETO-REF
SET check_function_bodies = false;
CREATE SCHEMA IF NOT EXISTS private;
CREATE TABLE public.a (id uuid NOT NULL, b_id uuid);
CREATE TABLE public.b (id uuid NOT NULL);
CREATE TABLE public.c (id uuid NOT NULL);
CREATE TABLE public.unrelated (id uuid NOT NULL);
ALTER TABLE public.a ADD CONSTRAINT a_pkey PRIMARY KEY (id);
ALTER TABLE public.a ADD CONSTRAINT a_b_fkey FOREIGN KEY (b_id) REFERENCES public.b(id);
ALTER TABLE public.unrelated ADD CONSTRAINT unrelated_pkey PRIMARY KEY (id);
CREATE INDEX idx_a_b ON public.a USING btree (b_id);
CREATE OR REPLACE FUNCTION public.touch()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
begin
  new.x := 1;
  return new;
end;
$function$;
CREATE OR REPLACE FUNCTION public.is_staff()
 RETURNS boolean
 LANGUAGE sql
AS $function$ select exists (select 1 from c); $function$;
CREATE OR REPLACE FUNCTION public.other()
 RETURNS void
 LANGUAGE sql
AS $function$ select 1; $function$;
CREATE TRIGGER trg_a_touch BEFORE UPDATE ON public.a FOR EACH ROW EXECUTE FUNCTION public.touch();
ALTER TABLE public.a ENABLE ROW LEVEL SECURITY;
CREATE POLICY a_staff ON public.a FOR SELECT TO authenticated USING (is_staff()
  OR (EXISTS ( SELECT 1
   FROM c
  WHERE (c.id = a.id))));
REVOKE ALL ON TABLE public.a FROM PUBLIC, anon, authenticated, service_role;
GRANT SELECT ON TABLE public.a TO service_role;
GRANT EXECUTE ON FUNCTION public.other() TO anon;
REVOKE ALL ON TABLE public.unrelated FROM PUBLIC, anon, authenticated, service_role;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON TABLES TO postgres, anon, authenticated, service_role;
ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON TABLES TO postgres, anon, authenticated, service_role;
COMMENT ON TABLE public.a IS 'a';
COMMENT ON TABLE public.unrelated IS 'u';
`;
const pending = `-- public.unrelated solo aparece en un comentario
begin;
alter table public.a add column z int;
commit;`;

test('cierre por FK, trigger y politica; fuera del cierre se omite', () => {
  const r = extract(baseline, pending);
  assert.deepEqual(r.tables, ['public.a', 'public.b', 'public.c']);
  assert.deepEqual(r.funcs, ['public.is_staff', 'public.touch']);
  const out = r.kept.join('\n');
  for (const s of ['CREATE TABLE public.b ', 'a_b_fkey', 'idx_a_b', 'trg_a_touch', 'CREATE POLICY a_staff', 'GRANT SELECT ON TABLE public.a',
    'ALTER DEFAULT PRIVILEGES FOR ROLE postgres', "COMMENT ON TABLE public.a IS 'a'", 'new.x := 1;']) assert.ok(out.includes(s), s);
  for (const s of ['public.unrelated', 'public.other', 'FOR ROLE supabase_admin', 'PROYECTO-SECRETO-REF']) assert.ok(!out.includes(s), s);
});

test('determinista', () => {
  assert.deepEqual(extract(baseline, pending), extract(baseline, pending));
});

test('una sentencia sin cerrar falla (nunca se trunca en silencio)', () => {
  assert.throws(() => splitStatements('CREATE OR REPLACE FUNCTION public.f()\n RETURNS int\nAS $function$ select 1;\n'), /sin cerrar/);
});
