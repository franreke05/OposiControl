-- ROLE SYNC GUARD V1 — INVARIANTES ADMINISTRATIVAS DENTRO DE LA OPERACIÓN
-- ATÓMICA QUE MODIFICA ROLES.
--
-- STATUS: CANDIDATE / LOCAL ONLY. NOT APPLIED TO MAIN. APPLY_PENDING_APPROVAL.
--
-- Qué arregla (defecto confirmado, no hipotético).
--   `public.admin_sync_roles` existe hoy en `docs/supabase/20260624_admin_atomic_operations.sql`
--   y su cuerpo entero es:
--
--       delete from public.roles_usuario where usuario_id = p_usuario_id;
--       insert into public.roles_usuario (usuario_id, rol_id)
--       select p_usuario_id, role_id
--       from (select distinct unnest(coalesce(p_role_ids, array[]::uuid[])) as role_id) roles;
--
--   No comprueba nada. Un array vacío o nulo BORRA todos los roles del usuario
--   y no inserta ninguno. Nada impide que el último superadministrador se
--   retire su propio rol —ni que otro administrador se lo retire—, dejando el
--   sistema sin ningún superadministrador y sin forma de recuperarlo desde el
--   panel. El único llamador es `AdminV1Gateway.syncRoles`
--   (`AdminV1Routes.kt:2874`), servido por `POST /api/v1/admin/usuarios/{id}/roles`
--   (`AdminV1Routes.kt:478`), pero la RPC es alcanzable con la clave de
--   `service_role` sin pasar por Ktor: por eso la garantía va aquí y no allí.
--
-- Alcance real de la protección (leer antes de confiar en ella).
--   Esta función protege a quien la llama. La misma clave de `service_role` que
--   alcanza la RPC alcanza también la tabla: un `DELETE`/`INSERT` directo sobre
--   `public.roles_usuario` por PostgREST NO toma el cerrojo ni pasa por estas
--   comprobaciones. El agujero se estrecha mucho —el panel y todo lo que use la
--   RPC quedan cubiertos— pero no se cierra del todo. Una invariante de base de
--   datos de verdad exigiría un `constraint trigger` diferido sobre
--   `roles_usuario`, que es un cambio mayor y de otro alcance.
--
-- Anclaje del esquema.
--   `public.roles_app (id uuid pk, codigo text not null unique, nombre text not
--   null, descripcion text)` y `public.roles_usuario (usuario_id uuid, rol_id
--   uuid, primary key (usuario_id, rol_id))`, según
--   `supabase/canonical/dry_run_fixture/001_live_shape.sql:14-25`. NO hay
--   columna de estado/activo en `roles_usuario`: «superadministradores activos»
--   es exactamente «filas que unen con `roles_app.codigo = 'superadmin'`».
--   `perfiles_usuario.es_invitado` NO aparece en ningún SQL del repositorio y no
--   interviene en estas invariantes: no se inventa aquí una relación que el
--   modelo no tiene.
--
--   Los códigos canónicos son los de `CanonicalAuthorization.kt`
--   (`backend/ktor`): 'superadmin', 'admin', 'moderador', 'usuario'. Aquí se
--   escriben como literales porque esta función no puede importar Kotlin; el
--   test `RoleSyncInvariantsTest` fija el espejo.
--
--   Se comparan NORMALIZADOS a través de `public.admin_role_code_canonical`,
--   igual que `CanonicalAuthorization.hasAdmin`. No es cosmético: `roles_app.codigo`
--   es `unique` y el unique de Postgres distingue mayúsculas, así que 'SuperAdmin'
--   y 'superadmin' pueden coexistir como filas legales. Comparando literal, a un
--   rol escrito 'SuperAdmin' Ktor le daría acceso al panel y esta guarda no lo
--   contaría como superadministrador: el último superadministrador del sistema
--   sería retirable sin que saltara nada. Es justo la invariante que este
--   fichero existe para garantizar.
--
--   Una versión anterior usaba `lower(btrim(x, E' \t\r\n'))` y AFIRMABA ser
--   equivalente a `trim()` de Kotlin. Era falso: `btrim` con esa lista recorta
--   cuatro caracteres y `Char.isWhitespace()` recorta una veintena. Quedaban
--   fuera U+000B, U+000C, U+001C-001F, U+00A0, U+1680, U+2000-200A, U+2007,
--   U+2028, U+2029, U+202F, U+205F y U+3000, así que un código como
--   "superadmin" seguido de un U+000C seguia siendo administrativo para Ktor e
--   esta guarda. La normalización vive ahora en una sola función, y es el
--   contrato que los verificadores comprueban por comparación.
--
-- Antes de aplicar esto en cualquier sitio (incluida una rama no-MAIN):
--   1. Confirmar contra un volcado real que `roles_app.codigo` contiene
--      exactamente esos códigos y que no hay un segundo rol con privilegio
--      administrativo fuera de ('superadmin', 'admin').
--   2. Confirmar que no existe ya un `admin_sync_roles` con otra firma.
--   3. Decidir si algún proceso legítimo necesita dejar a un administrador sin
--      roles (hoy no se conoce ninguno).
--   4. Asumir el cambio de modelo de privilegios: la función pasa de
--      `security invoker` / `search_path = public` a `security definer` /
--      `search_path = ''`. `create or replace` CONSERVA el propietario, así que
--      tras aplicar se ejecutará con los privilegios de quien la creó
--      originalmente y no con los de quien llama. Verificar quién es y que tenga
--      `SELECT` sobre `auth.users`. Si le falta, el síntoma NO es
--      `ROLE_SYNC_INVALID_USER`: es `permission denied for table users` (42501),
--      que nadie mapea, así que el operador ve un 500 genérico.
--   5. Volcar el ACL ANTES de reemplazar:
--      `select proacl from pg_proc where oid = 'public.admin_sync_roles(uuid,uuid[])'::regprocedure;`
--      `create or replace` conserva el ACL y el `revoke` de abajo sólo nombra
--      public/anon/authenticated. Cualquier otro grantee de la función antigua
--      —que era `security invoker`— la ejecutaría a partir de ahora como
--      DEFINER. Revocarlo explícitamente antes de aplicar.
--   6. Revisar la intercalación de la base de datos. La normalización usa
--      `collate "C"` justamente para no depender de ella.
--
-- Estilo: calcado de `product_metrics_candidate_v1/010_admin_grant_tickets.sql`,
-- que es la RPC con guarda ya aprobada de esta casa.

begin;

-- Preflight del ACL: OBLIGA, no sugiere.
--
-- `create or replace` conserva el ACL de la función anterior, que era
-- `security invoker`. Cualquier rol que tuviera EXECUTE sobre ella pasaría a
-- ejecutarla como DEFINER sobre `roles_usuario` en cuanto se aplique esto. El
-- README pedía volcar `proacl` a mano y mirarlo; pedirle a un operador que mire
-- no es un control. Esto aborta la migración entera si aparece un grantee fuera
-- del conjunto esperado, y deja su nombre en el mensaje para que se resuelva.
do $$
declare
  v_oid oid := to_regprocedure('public.admin_sync_roles(uuid,uuid[])');
  v_acl aclitem[];
  v_owner text;
  v_intrusos text;
begin
  if v_oid is null then
    return; -- instalación limpia: no hay ACL previo que heredar.
  end if;
  select proacl, pg_get_userbyid(proowner) into v_acl, v_owner
  from pg_proc where oid = v_oid;
  if v_acl is null then
    return; -- ACL por defecto: sólo el propietario.
  end if;
  select string_agg(distinct pg_get_userbyid(a.grantee), ', ') into v_intrusos
  from (select (aclexplode(v_acl)).*) a
  where a.grantee <> 0
    and pg_get_userbyid(a.grantee) not in (v_owner, 'anon', 'authenticated', 'service_role');
  if v_intrusos is not null then
    raise exception 'ROLE_SYNC_GUARD_UNEXPECTED_ACL: %', v_intrusos;
  end if;
end;
$$;

-- Normalización canónica de un código de rol.
--
-- Existe como función propia por dos razones. La comparación tiene que ser
-- IDÉNTICA en los tres sitios donde decide una invariante, y con la expresión
-- repetida a mano bastaba con olvidarla en uno: quitar el recorte sólo de los
-- dos recuentos de superadmins dejaba la guarda muerta y los verificadores en
-- verde. Y así el contrato es verificable de verdad — se comprueba que los tres
-- usos llaman aquí, no que cierta subcadena aparezca en el fichero.
--
-- El conjunto de caracteres es el que recorta `trim()` de Kotlin, es decir
-- `Char.isWhitespace()` = `Character.isWhitespace(c) || Character.isSpaceChar(c)`:
-- U+0009-000D, U+001C-001F, U+0020, U+00A0, U+1680, U+2000-200A, U+2028, U+2029,
-- U+202F, U+205F y U+3000.
--
-- `collate "C"` en el `lower` tampoco es cosmético: el `lower()` por defecto
-- depende de la intercalación, y bajo una turca `lower('SUPERADMIN')` da
-- 'superadmın' con i sin punto, que no casa. Kotlin usa `lowercase()` sin locale.
create or replace function public.admin_role_code_canonical(p_codigo text)
returns text
language sql
immutable
set search_path = ''
as $$
  select lower(
    regexp_replace(
      coalesce(p_codigo, ''),
      '^[\u0009-\u000D\u001C-\u001F\u0020\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]+|[\u0009-\u000D\u001C-\u001F\u0020\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]+$',
      '',
      'g'
    ) collate "C"
  );
$$;

create or replace function public.admin_sync_roles(
  p_usuario_id uuid,
  p_role_ids uuid[]
)
returns void
language plpgsql
security definer
-- `volatile` explícito, no por defecto: es lo que obliga a que cada consulta de
-- dentro tome instantánea nueva DESPUÉS del cerrojo bajo READ COMMITTED.
-- Marcarla `stable` reinstauraría en silencio el TOCTOU que el cerrojo cierra, y
-- ningún test fallaría. Bajo REPEATABLE READ o SERIALIZABLE la instantánea se
-- fija antes de conceder el cerrojo: esta garantía aplica en READ COMMITTED, que
-- es lo que usa PostgREST.
volatile
set search_path = ''
-- FALLO CERRADO FRENTE A RLS. No es cosmético y no es un bypass.
--
-- `public.roles_usuario` tiene RLS activa en MAIN
-- (`supabase/migrations/20260803102048_fix_rls_auth_initplan.sql`). Al pasar de
-- `security invoker` a `security definer` esta función deja de correr como quien
-- llama y pasa a correr como SU PROPIETARIO, que `create or replace` conserva y
-- que esta migración no elige. Si ese propietario no está exento de RLS, las
-- políticas se le aplican, y dentro de la función `auth.uid()` es NULL y
-- `es_admin()` es falso: no ve NINGUNA fila de `roles_usuario`.
--
-- Medido en PostgreSQL 16.2 real (`backend/dbtest`, RoleSyncRlsFailOpenTest) con
-- un propietario sin BYPASSRLS al que se le permite escribir pero cuya lectura
-- filtra la política: los dos recuentos leen 0, el `delete` tampoco ve filas
-- —las políticas `select` se aplican también al borrado—, el `insert` entra, y
-- la función CONFIRMA. Retirar 'superadmin' a un usuario le dejaba
-- `[admin, superadmin]` y devolvía éxito. Las dos invariantes, mudas.
--
-- `row_security = off` NO concede acceso a nada: lo que hace es que PostgreSQL
-- levante «la consulta sería afectada por la política de seguridad de
-- registros» en vez de devolver un conjunto parcial en silencio. Un propietario
-- exento (superusuario o BYPASSRLS, que es el caso normal) no nota la
-- diferencia; uno que no lo esté hace que la sincronización falle en alto y a la
-- vista, en lugar de contar mal. Verificado en el mismo harness.
set row_security = off
as $$
declare
  v_role_ids uuid[] := coalesce(p_role_ids, array[]::uuid[]);
  v_desconocidos integer;
  v_era_administrativo boolean;
  v_superadmins_antes integer;
  v_superadmins_despues integer;
begin
  if p_usuario_id is null then
    raise exception 'ROLE_SYNC_INVALID_USER';
  end if;

  -- Serializa las sincronizaciones de roles que pasan por esta función, antes de
  -- leer nada.
  --
  -- La invariante del último superadministrador es GLOBAL, no por fila, así que
  -- un cerrojo de fila no la protege: dos transacciones concurrentes que
  -- degraden cada una a un superadministrador distinto no se ven entre sí, cada
  -- una cuenta un superadministrador restante —el que la otra está quitando— y
  -- al confirmar quedan cero. Este cerrojo consultivo de transacción es lo que
  -- impide ese entrelazado. Se toma ANTES de la primera lectura: tomarlo después
  -- dejaría exactamente la misma ventana.
  --
  -- Lo que NO cubre: una escritura directa sobre `roles_usuario` que no llame a
  -- esta función. Ver «Alcance real de la protección» en la cabecera.
  perform pg_advisory_xact_lock(hashtextextended('admin-role-sync', 0));

  -- Usuario inexistente con nombre propio, igual que `010_admin_grant_tickets`.
  -- Sin esto, un uuid que no existe y un array vacío «tienen éxito» sin hacer
  -- nada, y con un array no vacío salta la violación de clave ajena cruda, que
  -- llega al cliente como un 500 genérico.
  --
  -- Va DESPUÉS del cerrojo a propósito: es una lectura de base de datos, y el
  -- cerrojo promete tomarse antes de cualquier lectura. Ponerla delante no
  -- rompía ninguna invariante —no alimenta ni A ni B— pero volvía falso ese
  -- comentario, y un comentario falso sobre concurrencia es peor que ninguno.
  if not exists (select 1 from auth.users u where u.id = p_usuario_id) then
    raise exception 'ROLE_SYNC_INVALID_USER';
  end if;

  -- Rol inexistente: con nombre propio, en vez de dejar que salte la violación
  -- de clave ajena, que llega al cliente como un error opaco de base de datos.
  select count(*) into v_desconocidos
  from unnest(v_role_ids) as solicitado(rol_id)
  where not exists (
    select 1 from public.roles_app r where r.id = solicitado.rol_id
  );
  if v_desconocidos > 0 then
    raise exception 'ROLE_SYNC_UNKNOWN_ROLE';
  end if;

  -- INVARIANTE A — un usuario ADMINISTRATIVO no puede quedarse sin roles.
  --
  -- «Administrativo» es tener 'superadmin' o 'admin', que es lo que
  -- `CanonicalAuthorization.hasAdmin` considera acceso al panel. Deliberadamente
  -- NO se confunde «último rol del usuario» con «último rol privilegiado»: a un
  -- usuario corriente sí se le pueden retirar todos sus roles, porque eso no
  -- deja ninguna puerta administrativa a medio cerrar.
  select exists (
    select 1
    from public.roles_usuario ru
    join public.roles_app r on r.id = ru.rol_id
    where ru.usuario_id = p_usuario_id
      and public.admin_role_code_canonical(r.codigo) in ('superadmin', 'admin')
  ) into v_era_administrativo;

  if v_era_administrativo and cardinality(v_role_ids) = 0 then
    raise exception 'ROLE_SYNC_EMPTY_ROLES';
  end if;

  select count(*) into v_superadmins_antes
  from public.roles_usuario ru
  join public.roles_app r on r.id = ru.rol_id
  where public.admin_role_code_canonical(r.codigo) = 'superadmin';

  delete from public.roles_usuario where usuario_id = p_usuario_id;

  insert into public.roles_usuario (usuario_id, rol_id)
  select distinct p_usuario_id, asignado.rol_id
  from unnest(v_role_ids) as asignado(rol_id);

  -- INVARIANTE B — ninguna operación puede PROVOCAR que queden 0
  -- superadministradores.
  --
  -- Se cuenta DESPUÉS de escribir y dentro de la misma transacción: así la
  -- comprobación mira el estado resultante real, no una predicción, y el
  -- `raise` deshace el `delete`/`insert` entero. Es la diferencia entre
  -- comprobar y garantizar. Mover este recuento por encima del `delete` rompe
  -- la invariante entera; `RoleSyncInvariantsTest` afirma el orden.
  --
  -- Se compara «antes > 0 y después = 0», no «después = 0» a secas: si un
  -- sistema ya estuviera sin superadministradores, la segunda forma bloquearía
  -- cualquier edición de roles —incluida la que crea el primero— y dejaría el
  -- panel inservible. Lo prohibido es causar la transición, no heredarla.
  --
  -- Pasar de 'superadmin' a 'admin' con otro superadministrador vivo sigue
  -- siendo legítimo y pasa por aquí sin ruido.
  select count(*) into v_superadmins_despues
  from public.roles_usuario ru
  join public.roles_app r on r.id = ru.rol_id
  where public.admin_role_code_canonical(r.codigo) = 'superadmin';

  if v_superadmins_antes > 0 and v_superadmins_despues = 0 then
    raise exception 'ROLE_SYNC_LAST_SUPERADMIN';
  end if;
end;
$$;

-- El helper NO se revoca de PUBLIC, y es deliberado.
--
-- `admin_sync_roles` es `security definer` y se ejecuta como SU propietario, que
-- `create or replace` conserva y que puede ser distinto del rol que aplica esta
-- migración —y por tanto del propietario del helper recién creado—. Revocarlo de
-- PUBLIC dejaría al propietario de la guarda sin EXECUTE sobre él, y TODA
-- sincronización de roles caería con `permission denied for function`
-- (42501) → 500 genérico. El helper es `immutable`, recibe texto, devuelve texto
-- y no toca ninguna tabla: dejarlo ejecutable no concede acceso a nada.
revoke execute on function public.admin_sync_roles(uuid, uuid[])
  from public, anon, authenticated;
grant execute on function public.admin_sync_roles(uuid, uuid[])
  to service_role;

-- Postcondición: un `commit` limpio no prueba que la función quedara instalada.
-- Mismo patrón que `docs/supabase/20260624_admin_atomic_operations.sql:294-306`.
do $$
begin
  if to_regprocedure('public.admin_sync_roles(uuid,uuid[])') is null
    or to_regprocedure('public.admin_role_code_canonical(text)') is null then
    raise exception 'ROLE_SYNC_GUARD_NOT_INSTALLED';
  end if;
end;
$$;

commit;
