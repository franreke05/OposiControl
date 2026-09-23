# Role Sync Guard Candidate V1

Candidate de migración para el P0 confirmado de Usuarios V2: la sincronización
completa de roles puede dejar el sistema **sin ningún superadministrador**, y
puede dejar a un usuario administrativo **sin ningún rol**.

## El defecto

`public.admin_sync_roles(uuid, uuid[])`, tal y como está documentada en
`docs/supabase/20260624_admin_atomic_operations.sql:3-19`, no comprueba nada:

```sql
delete from public.roles_usuario where usuario_id = p_usuario_id;
insert into public.roles_usuario (usuario_id, rol_id)
select p_usuario_id, role_id
from (select distinct unnest(coalesce(p_role_ids, array[]::uuid[])) as role_id) roles;
```

Un array vacío o nulo borra todos los roles del usuario. Nada impide que el
último superadministrador se retire el rol a sí mismo, ni que otro administrador
se lo retire. El panel no tendría después ninguna forma de devolverlo.

El único llamador desde este repositorio es `AdminV1Gateway.syncRoles`
(`AdminV1Routes.kt:2874`), servido por `POST /api/v1/admin/usuarios/{id}/roles`
(`AdminV1Routes.kt:478`). **La RPC es alcanzable directamente con la clave de
`service_role` sin pasar por Ktor**, y por eso una comprobación en Ktor no
cierra el agujero: la garantía tiene que vivir dentro de la operación.

## Status: `APPLY_PENDING_APPROVAL`

**No aplicado en ningún sitio — candidate local.** `MAIN_WRITES: NONE`. Esta
sesión no tiene credenciales de Supabase ni acceso a la base de datos: el
esquema se ancla en `supabase/canonical/dry_run_fixture/001_live_shape.sql:14-25`
y en los DTO Kotlin que ya leen y escriben esas tablas, no en un volcado real.

Antes de aplicarlo en cualquier sitio, incluida una rama no-MAIN:

1. Confirmar contra un volcado real que `roles_app.codigo` contiene exactamente
   `superadmin`, `admin`, `moderador`, `usuario`, y que no existe un segundo rol
   con privilegio administrativo fuera de `('superadmin', 'admin')`. Comprobar
   también **las mayúsculas y los espacios reales** de esos valores: esta guarda
   los normaliza, pero los helpers de RLS `es_admin()` y `es_moderador_o_admin()`
   siguen comparando literal, así que tras aplicar convivirían dos nociones de
   «administrador» si algún código estuviera escrito de otra forma.
2. Confirmar que no hay ya en producción un `admin_sync_roles` con otra firma
   distinta de `(uuid, uuid[])`.
3. Decidir si algún proceso legítimo necesita dejar a un administrador sin roles.
   Hoy no se conoce ninguno.
4. Asumir el cambio de modelo de privilegios: la función pasa de
   `security invoker` / `search_path = public` a `security definer` /
   `search_path = ''`. `create or replace` **conserva el propietario**, así que
   tras aplicar se ejecutará con los privilegios de quien la creó originalmente y
   no con los de quien llama. Verificar quién es y que tenga `SELECT` sobre
   `auth.users`.

   **Si le falta ese `SELECT`, el síntoma NO es `ROLE_SYNC_INVALID_USER`.**
   Postgres levanta `permission denied for table users` (SQLSTATE 42501), que no
   está en `SUPABASE_DOMAIN_ERRORS`, así que `mapSupabaseDomainError` devuelve
   null y el operador ve un **500 genérico** «Error al consultar el servicio de
   datos», no un 422. Falla cerrado, pero quien siga este runbook durante una
   incidencia estaría buscando la señal equivocada. `ROLE_SYNC_INVALID_USER` sólo
   aparece en el caso distinto de que el propietario SÍ pueda leer pero RLS
   filtre la fila.

5. El ACL previo lo comprueba **la propia migración**: `001` abre con un bloque
   `do $$` que lee el `proacl` de la función existente y **aborta con
   `ROLE_SYNC_GUARD_UNEXPECTED_ACL`** si aparece cualquier grantee fuera de
   `{propietario, anon, authenticated, service_role}`. Ya no depende de que
   alguien mire el volcado. Para inspeccionarlo por adelantado:
   `select proacl from pg_proc where oid = 'public.admin_sync_roles(uuid,uuid[])'::regprocedure;`
   `create or replace` **conserva el ACL**, y el `revoke` de este candidate sólo
   nombra `public`, `anon` y `authenticated`. Cualquier otro rol con `EXECUTE`
   sobre la función antigua —que era `security invoker`— lo conserva, y a partir
   de aplicar la ejecutaría con privilegios de **definer** sobre `roles_usuario`.
   Si el volcado muestra algún grantee fuera de esos tres, revocarlo antes.

6. Revisar la intercalación de la base de datos. La normalización usa
   `collate "C"` para no depender de ella; si hubiera códigos de rol no ASCII,
   comprobar que `admin_role_code_canonical` los trata como se espera.

## Las dos invariantes

**A — un usuario administrativo no puede quedarse sin roles.** «Administrativo»
es tener `superadmin` o `admin`, que es lo que `CanonicalAuthorization.hasAdmin`
considera acceso al panel. No se confunde «último rol del usuario» con «último
rol privilegiado»: a un usuario corriente sí se le pueden retirar todos sus
roles. Rechazo: `ROLE_SYNC_EMPTY_ROLES`.

**B — ninguna operación puede provocar `superadmins = 0`.** Se compara el estado
**antes** y **después** dentro de la misma transacción. Pasar de `superadmin` a
`admin` con otro superadministrador vivo es legítimo y no se bloquea. Lo
prohibido es causar la transición de ≥1 a 0. Rechazo:
`ROLE_SYNC_LAST_SUPERADMIN`.

Además, un rol que no existe en `roles_app` se rechaza con nombre propio
(`ROLE_SYNC_UNKNOWN_ROLE`) en vez de dejar que salte la violación de clave
ajena, que llegaría al cliente como un error opaco de base de datos.

## Alcance real de la protección

Esta función protege a **quien la llama**. La misma clave de `service_role` que
alcanza la RPC alcanza también la tabla: un `DELETE`/`INSERT` directo sobre
`public.roles_usuario` por PostgREST no toma el cerrojo ni pasa por estas
comprobaciones. El agujero se estrecha mucho —el panel y cualquier consumidor que
use la RPC quedan cubiertos— pero no se cierra del todo.

Una invariante de base de datos completa exigiría un `constraint trigger`
diferido sobre `roles_usuario`, que es un cambio mayor y de otro alcance. Queda
dicho aquí para que nadie lea «serializa toda sincronización de roles» y entienda
más de lo que hay.

## Visibilidad bajo RLS y propiedad de la función

Al pasar de `security invoker` a `security definer`, la función deja de correr
como quien llama y pasa a correr como **su propietario**, que `create or replace`
conserva y que esta migración no elige. `public.roles_usuario` tiene RLS activa
en MAIN (`supabase/migrations/20260803102048_fix_rls_auth_initplan.sql`).

- **`RLS_VISIBILITY_MODEL`** — dentro de la función `auth.uid()` es NULL y
  `es_admin()` es falso, así que la política `roles_usuario_select_propio` no
  selecciona ninguna fila para un propietario no exento.
- **`OWNER_MODEL`** — exento es un superusuario o un rol con `BYPASSRLS`. Es el
  caso normal en Supabase, pero el paquete **no puede comprobarlo antes de
  aplicarse** y no debe suponerlo.
- **`FAIL_CLOSED_GUARANTEE`** — la función declara `set row_security = off`. Eso
  **no concede acceso**: hace que PostgreSQL levante *«la consulta sería afectada
  por la política de seguridad de registros»* en lugar de devolver un conjunto
  parcial en silencio.

Por qué importa, medido y no razonado. En PostgreSQL 16.2 real
(`backend/dbtest`, `RoleSyncRlsFailOpenTest`), con un propietario sin `BYPASSRLS`
al que se le permite escribir pero cuya lectura filtra la política:

| Sin `row_security = off` | Con `row_security = off` |
|---|---|
| Los dos recuentos leen **0** | La primera lectura **aborta** |
| El `delete` tampoco ve filas — las políticas `select` se aplican al borrado | No se escribe nada |
| El `insert` sí entra | — |
| Retirar `superadmin` deja al usuario en `[admin, superadmin]` y **devuelve éxito** | La operación falla en alto |
| Las dos invariantes, **mudas** | Estado intacto |

Es decir: sin esta línea, revocar un privilegio no hacía nada y nadie se
enteraba. No es el `42501` ruidoso que temíamos; era un fallo **abierto** y
silencioso.

## Atomicidad y concurrencia

La garantía **no** depende de dos consultas independientes. Está dentro de la
misma función plpgsql, es decir, dentro de la misma transacción:

1. `pg_advisory_xact_lock(hashtextextended('admin-role-sync', 0))` serializa las
   sincronizaciones **que pasan por esta función**, y lo hace antes de la primera
   lectura. Sin él, dos transacciones que degraden a superadministradores
   distintos no se ven entre sí: cada una cuenta un superadministrador restante
   —el que la otra está quitando— y al confirmar quedan cero. Un cerrojo de fila
   no sirve porque la invariante es global, no por fila.
2. El recuento posterior se hace **después** del `delete`/`insert`, así que mira
   el estado resultante real y no una predicción.
3. El `raise exception` deshace la operación entera. Es la diferencia entre
   comprobar y garantizar.

## Errores

Los códigos viajan desnudos desde SQL y los traduce `BackendErrors.kt`
(`SUPABASE_DOMAIN_ERRORS`), que es el mecanismo ya establecido en esta casa:

| Código | HTTP | Mensaje al operador |
|---|---|---|
| `ROLE_SYNC_LAST_SUPERADMIN` | 409 | No puedes retirar este rol porque debe existir al menos un superadministrador. |
| `ROLE_SYNC_EMPTY_ROLES` | 422 | El usuario debe conservar al menos un rol. |
| `ROLE_SYNC_UNKNOWN_ROLE` | 422 | Alguno de los roles seleccionados ya no existe. |
| `ROLE_SYNC_INVALID_USER` | 422 | El usuario indicado no es válido. |

No se devuelve SQL, ni nombres de tabla, ni stacktrace, ni respuestas de
PostgREST, ni UUID innecesarios.

## Ficheros

- `001_admin_sync_roles_guard.sql`: el preflight del ACL —que **aborta** la
  migración si hereda un grantee inesperado—, el canonicalizador
  `admin_role_code_canonical` y la RPC con guarda, en el estilo de
  `product_metrics_candidate_v1/010_admin_grant_tickets.sql`.
- `002_rollback_admin_sync_roles.sql`: la reversión. Devuelve `admin_sync_roles`
  a su cuerpo original sin comprobaciones y a `security invoker`. **Ejecutarlo
  reabre la vulnerabilidad que este candidate cierra**, y su cabecera lo dice sin
  rodeos. No restaura el ACL previo —lo delega en el operador— y deja vivo el
  canonicalizador, que es inocuo porque no lo usa nadie más tras revertir.
- `tests/verify_candidate.ps1`: verificador de **paquete**. No conecta con
  Supabase y **no demuestra ninguna invariante**. Comprueba integridad del
  paquete, forma transaccional, presencia de los bloques de preflight y de la
  postcondición, presencia del rollback, el estado declarado, que no se haya
  colado una migración del candidate en `supabase/migrations`, que el SQL
  ejecutable sea ASCII, y que la suite semántica siga existiendo. Él mismo
  imprime su `PROVES:` y su `DOES_NOT_PROVE:`.

## Dónde vive la garantía

Durante cinco revisiones este candidate se verificó leyendo el **texto** del SQL
con expresiones regulares, y cada revisión independiente encontró otra forma
sintáctica de dejar la guarda muerta con todo en verde: `raise notice` en lugar
de `raise exception`, un `case when false` de señuelo, un
`exception when others then null` que se tragaba el aborto dejando confirmada la
escritura, una condición imposible añadida al `if`, una segunda definición en
MAYÚSCULAS que pisaba a la guardada, y un abre-bloque de comentario dentro de un
`--` que hacía que el propio limpiador se comiera el neutralizador. El problema
no era que faltara una expresión regular: era que la autoridad estaba en el sitio
equivocado.

| | Autoridad | Qué demuestra |
|---|---|---|
| `tests/verify_candidate.ps1` | paquete | integridad, forma, presencia, estado declarado |
| `RoleSyncInvariantsTest` (`:backend:ktor`) | espejo y deriva | que la especificación en Kotlin dice lo que creemos, y avisa si el SQL revisado cambia |
| **`:backend:dbtest`** | **comportamiento** | **las invariantes** |

`:backend:dbtest` levanta un PostgreSQL **real y desechable** (binarios propios,
sin Docker ni daemon, base nueva por escenario, destruida con la JVM), instala el
contrato mínimo de Supabase —`auth.users`, `roles_app`, `roles_usuario`,
`auth.uid()`, `es_admin()`, la RLS equivalente y la función legacy—, aplica
**este mismo fichero** leído del repositorio (nunca una copia en un literal de
test) y comprueba el **estado de las tablas**. Da igual cómo se sabotee la
sintaxis: si deja pasar al último superadministrador, el escenario se pone rojo.

## Qué NO entra en este candidate

No se toca el llamador: `AdminV1Gateway.syncRoles` sigue invocando
`admin_sync_roles` con la misma firma, así que aplicar esta migración no exige
ningún cambio de código simultáneo. Lo que sí se ha añadido en el repositorio,
por separado y ya activo, es la traducción de los cuatro códigos a errores
humanos tipados y el espejo de las invariantes en `RoleSyncInvariants.kt`, que
los tests atacan sin base de datos.

## Límite de seguridad

Escrituras en Supabase de producción: `NONE`. Este candidate no se ha ejecutado
contra ninguna base de datos, local ni remota. No se ha aplicado ninguna
migración, ni se ha ejecutado SQL sobre MAIN.
