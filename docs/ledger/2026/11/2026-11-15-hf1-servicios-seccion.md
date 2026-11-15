# HF1 — Control no puede guardar servicios del Market, ni configuración V1 ni razonados enriquecidos (2026-11-15)

Rama local `hf/servicios-seccion` desde `origin/develop` (`39b5dd85`, servidor 1.3 en producción). Sin push. MAIN/VPS sin tocar. Solo base de datos: **no hace falta imagen Ktor nueva**.

## Síntoma (producción)

Control > Catálogo > Servicios: crear o editar un servicio falla. Log de Ktor:

```
Supabase POST /rest/v1/servicios -> 403: {"code":"42501","message":"permission denied for function servicio_seccion_por_tipo"}
```

## Causa raíz (confirmada en el ensayo sobre la forma de MAIN de hoy)

- `20261114400000_v1_servicios_catalogo` (F28) añadió el disparador `trg_servicios_seccion` (BEFORE INSERT OR UPDATE en `public.servicios`). Su función `private.tg_servicios_seccion()` era **SECURITY INVOKER** y, cuando `NEW.seccion` es NULL, llama a `private.servicio_seccion_por_tipo(text)`.
- El helper se cerró con `revoke all ... from public, anon, authenticated`. `service_role` nunca tuvo un EXECUTE explícito: solo el de PUBLIC, que ese revoke quitó.
- El gateway admin de Ktor (`AdminV1Routes.upsertService`) escribe por PostgREST con la clave de servicio (`SupabaseRequestAuthPolicy` / `supabaseElevatedAuth`), es decir, como `service_role`. Hace un upsert (`Prefer: resolution=merge-duplicates`, INSERT ... ON CONFLICT DO UPDATE) y manda `seccion` como null explícito cuando el campo está vacío (`marketUpsertBody(..., "seccion", ...)`). El disparador corre el helper como `service_role`, y eso da el 42501.
- `service_role` sí tiene USAGE en `private` en MAIN: lo concede `20260928100000` (línea 251, que está en el historial de MAIN). Por eso el error nombra la función y no el esquema; sin USAGE el mensaje sería `permission denied for schema private`. La captura de MAIN del 2026-10-14 no trae los ACL de esquemas, así que el ensayo, el gate y el dbtest reponen ese USAGE antes de probar. Comprobado: sin reponerlo, el ensayo da `permission denied for schema private`.
- El gate PG17 y `backend/dbtest` escribían `public.servicios` como superusuario, así que ninguno de los dos lo vio.

## Hallazgo del barrido: el mismo defecto en tres disparadores más (en producción desde la ventana V1 del 2026-10-22)

La consulta de barrido, ejecutada sobre la forma de MAIN de hoy en el ensayo y en dbtest, encuentra el mismo defecto fuera de la ventana 1.3. Son funciones de disparador SECURITY INVOKER que llaman a helpers privados revocados de PUBLIC:

| Migración (en MAIN) | Disparador | Llama a | Efecto en producción (Ktor escribe como `service_role`) |
|---|---|---|---|
| 20261022101500 | `trg_configuracion_v1_bounds` en `public.configuracion_v1` (BEFORE INSERT OR UPDATE) | `private.config_v1_range(text)`, en **cada** escritura | Control > Configuración (`AdminControlConfig`, PATCH `/rest/v1/configuracion_v1`) falla con 42501. Comprobado en el ensayo: `permission denied for function config_v1_range` |
| 20261022102100 | `trg_soluciones_markup` en `public.soluciones_pregunta` | `private.markup_valid` / `private.markup_plain`, cuando `razonamiento_rico` no es null | guardar desde Control una solución con razonado enriquecido falla con 42501 (dbtest) |
| 20261022102100 | `trg_staging_markup` en `public.preguntas_parseadas_staging` | ídem | importar filas con razonado enriquecido (POST/PATCH de staging) falla con 42501 |

Por eso se arreglan en la misma migración y de la misma forma. **Decisión que toma el coordinador o el propietario:** si se prefiere limitar HF1 a servicios, basta con quitar esos tres bloques. No se recomienda, porque son fallos vivos de la misma clase.

## Arreglo (migración `20261115000000_v1_servicios_seccion_permiso`)

- Las cuatro funciones de disparador (`private.tg_servicios_seccion`, `private.tg_configuracion_v1_bounds`, `private.tg_soluciones_markup` y `private.tg_staging_markup`) pasan a **SECURITY DEFINER**. El dueño no cambia (el de la migración, como todas las funciones definer de las ventanas), mantienen `set search_path = ''` y **el mismo cuerpo**: comprobado con diff contra sus migraciones, y todos los nombres ya iban cualificados. Una función de disparador no necesita EXECUTE del rol que escribe, así que siguen revocadas a public, anon y authenticated.
- Los helpers (`servicio_seccion_por_tipo`, `config_v1_range`, `markup_valid` y `markup_plain`) **no se abren**: anon y authenticated siguen sin EXECUTE (VERIFY), y tampoco se conceden a service_role.
- VERIFY (6 filas a 0): las 4 son prosecdef; las 4 tienen search_path vacío; el dueño puede ejecutar los 4 helpers; ninguna es ejecutable por clientes; ningún helper es ejecutable por clientes; y los 4 disparadores siguen colgados de su función y habilitados.
- ROLLBACK: las cuatro funciones de sus migraciones, tal cual (invoker), verificado con diff. Devuelve los bugs, y está probado.
- Manifiesto `ops/deploy/v1/main-migrations-2026-11-hf1.manifest`: `20261115000000 7ca602cfccf0 20261114500000 apply`.
- **Encadenamiento**: las migraciones que vengan después (SR / F31: `20261115100000` y `20261115100100`) deben tener como predecesora `20261115000000`, no `20261114500000`.

## Barrido obligatorio (las 11 migraciones de la ventana 1.3)

Funciones de disparador creadas o reemplazadas en `20261109100100` .. `20261114500000`:

| Migración | Función de disparador | SECURITY DEFINER | Llama a funciones | Resultado |
|---|---|---|---|---|
| 20261109100100 | `public.validar_y_calcular_respuesta_intento()` | no | ninguna (solo lee tablas) | OK |
| 20261111100000 | `private.tg_demo_set_fijo_preguntas_examen()` | sí | `private.demo_set_fijar` | OK |
| 20261111100000 | `private.tg_demo_set_fijo_preguntas()` | sí | `private.demo_set_fijar` | OK |
| 20261111100000 | `private.tg_demo_set_fijo_examenes()` | sí | `private.demo_set_fijar` | OK |
| 20261114300000 | `private.tg_reto_answer_counts()` | sí | `private.app_count_answer` | OK |
| 20261114300000 | `public.preparar_intento_examen()` | no | ninguna (solo lee tablas) | OK |
| 20261114400000 | `private.tg_servicios_seccion()` | **no** | `private.servicio_seccion_por_tipo` (revocada) | **BUG**, lo arregla HF1 |

`20261109100200`, `20261114100000`, `20261114200000`, `20261114200100`, `20261114400100`, `20261114400200` y `20261114500000` no crean disparadores. En la ventana 1.3, el único afectado es **`trg_servicios_seccion`**. Fuera de la ventana, la consulta encontró los tres disparadores de la sección anterior. Las funciones no-disparador sin definer que llaman a helpers privados (por ejemplo `private.servicio_comprable`) solo las invocan RPC SECURITY DEFINER, así que no les afecta.

## Consulta de barrido para MAIN (solo lectura)

Recorre los disparadores de `public.*` cuya función NO es SECURITY DEFINER y busca en su `prosrc` llamadas (cualificadas o no) a funciones de `public` / `private` que `service_role` no puede ejecutar, o a las que no llega porque le falta USAGE en el esquema. Es una lista para revisar: está hecha por texto, así que puede dar algún falso positivo (una columna que se llame igual que una función) y no ve SQL dinámico. Con el arreglo aplicado debe salir vacía. El ensayo la ejecuta tal cual, copiada de entre las marcas:

```sql
-- SWEEP-BEGIN
with trg as (
  select t.tgrelid::regclass::text as tabla, t.tgname::text as disparador, p.oid::regprocedure::text as funcion, p.prosrc
    from pg_trigger t
    join pg_proc p on p.oid = t.tgfoid
   where not t.tgisinternal
     and t.tgrelid in (select c.oid from pg_class c where c.relnamespace = 'public'::regnamespace)
     and not p.prosecdef
),
refs as (
  select distinct trg.tabla, trg.disparador, trg.funcion, lower(m[1]) as esquema, lower(m[2]) as nombre
    from trg, lateral regexp_matches(trg.prosrc, '(?:\m(public|private)\.)?\m([a-z_][a-z0-9_]*)\s*\(', 'gi') as m
)
select r.tabla, r.disparador, r.funcion, q.oid::regprocedure::text as llama_a,
       has_function_privilege('service_role', q.oid, 'execute') as service_role_execute,
       has_schema_privilege('service_role', q.pronamespace, 'usage') as service_role_usage
  from refs r
  join pg_proc q on q.proname = r.nombre
                and q.pronamespace in ('public'::regnamespace, 'private'::regnamespace)
                and (r.esquema is null or q.pronamespace = r.esquema::regnamespace)
 where not has_function_privilege('service_role', q.oid, 'execute')
    or not has_schema_privilege('service_role', q.pronamespace, 'usage')
 order by 1, 2, 4;
-- SWEEP-END
```

Resultado en la base del ensayo (forma de MAIN de hoy). ANTES, 6 filas (columnas: tabla | disparador | función | llama a | service_role_execute | service_role_usage):

```
configuracion_v1|trg_configuracion_v1_bounds|private.tg_configuracion_v1_bounds()|private.config_v1_range(text)|f|t
preguntas_parseadas_staging|trg_staging_markup|private.tg_staging_markup()|private.markup_plain(jsonb)|f|t
preguntas_parseadas_staging|trg_staging_markup|private.tg_staging_markup()|private.markup_valid(jsonb)|f|t
servicios|trg_servicios_seccion|private.tg_servicios_seccion()|private.servicio_seccion_por_tipo(text)|f|t
soluciones_pregunta|trg_soluciones_markup|private.tg_soluciones_markup()|private.markup_plain(jsonb)|f|t
soluciones_pregunta|trg_soluciones_markup|private.tg_soluciones_markup()|private.markup_valid(jsonb)|f|t
```

DESPUÉS: 0 filas. La misma lista sale en `backend/dbtest`, que construye el esquema V1 desde la forma generada.

## Pruebas

- **Ensayo** `docs/ledger/2026/11/rehearse-hf1-2026-11.sh`. Punto de partida: la captura de MAIN + 57 migraciones posteriores aplicadas y registradas (última `20261114500000`) + el USAGE de `private` repuesto. Resultado: `HF1-2026-11-REHEARSAL OK`.
  - ANTES, como `service_role`: `42501 permission denied for function servicio_seccion_por_tipo`, el mismo mensaje del log de Ktor. El PATCH de configuración da `42501 permission denied for function config_v1_range`.
  - Negativas del script: versión fuera del manifiesto, predecesora ausente.
  - Aplicada con `apply-main-migration.sh` y el manifiesto HF1 (VERIFY = 0, 6 filas, registrada). Re-aplicarla = STOP.
  - DESPUÉS: pasa la matriz de escrituras de Control (panel viejo sin seccion/visible/posicion; upsert con seccion null en la rama insert y en la rama ON CONFLICT; seccion explícita; update a null; update de otro campo) y pasa el PATCH de configuración. Los clientes siguen sin EXECUTE sobre los 4 helpers y las 4 funciones. Barrido: 0 filas.
  - Huella de catálogo: solo cambia prosecdef de las 4 funciones (mismo md5 del cuerpo, search_path y ACL). ROLLBACK: huella igual al punto de partida y vuelven los 42501. Re-aplicar: huella igual a la primera aplicación.
- **Gate PG17** `scripts/db-pg17-gate.sh`: `db-pg17-gate OK` (56 pendientes, VERIFY = 0, assertions, rollback/re-aplicación y carreras). El bloque nuevo de `assertions.sql` escribe `servicios` y `configuracion_v1` como `service_role` (los límites siguen rechazando), comprueba que las 4 funciones son definer y que los helpers están cerrados, y pasa un barrido simplificado que debe salir vacío.
- **Control negativo del gate** `scripts/db-pg17-gate.test.sh`, escenario (i), nuevo: quitar `security definer` hace FALLAR el gate por el VERIFY de `20261115000000` (`hf1_trigger_function_not_security_definer = 4`). Se ejecutó ese escenario solo, con las mismas funciones `fresh` / `expect_fail` del script, y pasa. El script completo (unas 16 ejecuciones del gate) no se ha corrido aquí.
- **backend/dbtest completo** (`:backend:dbtest:test`): 66 clases, 434 pruebas, 0 fallos. Entre ellas, `V1ServiciosSeccionPermisoMigrationTest` (5 pruebas, como `service_role`: los 42501 sin la migración en servicios, configuración y markup; la matriz de escrituras; que los validadores de configuración y de markup se alcanzan y siguen rechazando; el barrido del acta antes y después; VERIFY, ROLLBACK y re-aplicación). Además pasan `V1ServiciosCatalogoMigrationTest` (sus `dependientes` incluyen ahora HF1), `V1ServiciosCanjeMigrationTest`, `V1ConsumiblesEfectosMigrationTest` y `V1RankingLotesShapeMigrationTest`.

## Despliegue (propietario)

1. Solo lectura, antes: la consulta de barrido sobre MAIN. Debe dar exactamente las 6 filas del ensayo (4 disparadores); si da otras, se para y se revisan.
2. `MANIFEST=ops/deploy/v1/main-migrations-2026-11-hf1.manifest bash ops/deploy/v1/apply-main-migration.sh supabase/migrations/20261115000000_v1_servicios_seccion_permiso.sql`, tecleando la versión. Debe acabar en "aplicada, VERIFY = 0, registrada".
3. Volver a pasar la consulta de barrido: 0 filas. Después, en Control: guardar un servicio con la sección vacía (debe quedar con la sección de su tipo) y guardar un valor de Configuración.
4. Sin imagen nueva ni reinicio de Ktor.
