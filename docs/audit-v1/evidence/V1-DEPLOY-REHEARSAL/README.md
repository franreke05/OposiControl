# V1-DEPLOY-REHEARSAL: ensayo local de las migraciones del despliegue V1 (2026-10-21)

Requisito R1 de OposiControl, para `docs/engineering/V1_PRODUCTION_DEPLOY_PLAN.md`, sección 5. Nada aquí toca MAIN ni la red.

## Ficheros

| Fichero | Qué es |
|---|---|
| `rehearse-v1-deploy.sh` | El ensayo, reproducible. Contenedor propio `v1-deploy-rehearsal-pg17` (PG17.6.1.171 por digest, 1 GB, sin puerto), que se borra por nombre al terminar. |
| `seed_synthetic.sql` | Semilla sintética: 4 usuarios, 3 carteras en tres estados distintos del libro, una compra y una suscripción. |
| `apply-main-migration-selftest.log` | Prueba de `ops/deploy/v1/apply-main-migration.sh` contra un contenedor local. |
| `run-2026-10-21/` | Salida completa de la ejecución válida. |

Contenido de `run-2026-10-21/`:

| Fichero | Contenido |
|---|---|
| `rehearsal.log` | Resumen paso a paso |
| `order.txt` | Orden real y sha256/16 de cada fichero |
| `verify.txt` | Las 354 filas de VERIFY de la primera aplicación y de la re-aplicación |
| `verify_before.txt` | El VERIFY ANTES de cada migración |
| `pre_*.txt` | Preflight de solo lectura |
| `post_*.txt` | Catálogo y cartera después de cada fase |
| `rollback_diff.txt` | El hallazgo |
| `timings.txt` | Tiempos de aplicación |
| `run1-before-restore-script.log` | La primera ejecución, que se detuvo en el hallazgo |

## Entradas

- **Forma de MAIN completa:** la captura de catálogo de solo lectura del 2026-10-14 (`C:\Users\franr\.oposibots-gate\baseline-20261014\baseline_main.sql`, sha256 `07acead5e0598df501aabd83e1e281d8776e653bea846e98932f233021354a46`, sin filas y sin secretos; fuera del repo). Se añaden:
  - las vistas de la captura del 2026-09-29;
  - `supabase/baseline/main_shape_auth_users.sql`;
  - el stub de `storage.objects` de `supabase/canonical/sec_v1_01_main_intervention/storage_test/`.
- **Migraciones:** `origin/develop` `ac0e1670`. Son las 34 de PENDING, más `20261020100000`, `20261021100000` y `20261023100000..100500`. Se añade el borrador de `20261023100600` de `feature/v1-plans-store-grants-revoke` (sin commit; blob `b39f7cc08aff`, sha256 `63e63d89…`), pasado con `EXTRA_MIGRATIONS`.

## Resultado (ejecución válida, `rc=0`, `V1-DEPLOY-REHEARSAL OK`)

| Paso | Resultado |
|---|---|
| Arranque | PG17.6, `postgres` no es superusuario |
| Forma de MAIN | 95 tablas public, 72 funciones public, 132 políticas (public + storage). Carga con `ON_ERROR_STOP=1` y 0 errores |
| Orden real | 43 migraciones. **CAND-04**: `20261023100200` en la posición 39, después de `20261022102600` (posición 29) |
| PREFLIGHT de solo lectura | Catálogo, datos agregados, preflight y checkpoint de SEC-V1-01 (0 filas `false`), auditoría de comunidad. Ningún `BLOQUEA` distinto de 0 |
| RB-16 sin pertenencia a `supabase_storage_admin` | Se detiene en `STORAGE_ADMIN_WRITE_PREFLIGHT` y el catálogo queda idéntico |
| Primera aplicación | 43/43 aplicadas, VERIFY = 0 en todas (354 filas) |
| Cartera | 2 filas `legacy_opening` (50 + 10 = 60 OT), `app_wallet_reconcile` = 0 filas, saldos 50/30/20 sin cambios, 0 filas sin `seq` |
| `assertions.sql` del gate | Falla en su primer ASSERT: «fixture: 5 profiles». Supone una base vacía; no es un fallo de las migraciones (informativo) |
| ROLLBACK de 41 en orden inverso | **HALLAZGO**: 39 líneas de diferencia con la forma de MAIN (ver abajo) |
| ROLLBACK + `R2_post_rollback_restore_main_acl.sql` | Catálogo **idéntico** al de MAIN (con las 2 LEGACY aplicadas) |
| Datos tras el ROLLBACK | Las 2 aperturas quedan (son filas normales, como documenta la migración); saldos sin cambios |
| Re-aplicación | 41/41, VERIFY = 0, catálogo idéntico a la primera aplicación, sin aperturas duplicadas, reconcile = 0 |

Tiempos de la primera aplicación: unos 181 s en total, entre 1 y 10 s por migración. Están inflados por `docker exec` y por la carga de la máquina, que tenía en paralelo otros 9 contenedores del gate de otros agentes. No sirven para estimar MAIN.

## Hallazgo: los ROLLBACK de 101200, 101600 y 101900 no devuelven la forma de MAIN

`rollback_diff.txt` (`<` = MAIN, `>` = después de los ROLLBACK).

**20261022101900 (foro):**

- `grant all` a `anon` y `authenticated` en `publicaciones_oposired`, `comentarios_oposired`, `reacciones_oposired` y `categorias_oposired`. En MAIN, `anon` solo tiene SELECT/MAINTAIN y `authenticated` no tiene TRUNCATE.
- `anon` con TRUNCATE (que ignora RLS) podría vaciar el foro. **P1** si se ejecutara un R-2.
- Además recrea 5 políticas con `auth.uid()` en vez de `(select auth.uid())`. Misma semántica.

**20261022101200 (reto diario):**

- SELECT a `anon` en `estado_competitivo_usuario` y `estado_opositester_usuario` (en MAIN solo lo tiene `authenticated`).
- Pierde MAINTAIN en `ranking_liga` y `registros_reto_diario`.
- Cosmético: el texto de la política de `ranking_liga` y el orden del ACL de `recalcular_estadisticas_*`.

**20261022101600 (estadísticas):** crea `estadisticas_globales_pregunta_select`, una política que MAIN no tiene.

**Por qué el gate no lo ve:** su subconjunto (`main_shape_subset_extra_engagement.sql`) está escrito a mano con esa misma forma, así que el rollback vuelve a esos stubs y no a MAIN.

**Mitigación en el plan:** `docs/engineering/v1-deploy/R2_post_rollback_restore_main_acl.sql`, con sentencias verbatim de la captura, ejecutado dentro de R-2. Ensayado: huella idéntica.

**Corrección definitiva:** Bot corrige esos bloques ROLLBACK y regenera los stubs desde la captura.

## Lo que este ensayo no prueba

Ver la sección 5.4 del plan:

- datos reales;
- deriva desde el 2026-10-14;
- cuerpos de función;
- rol y propiedad reales;
- GoTrue, Storage y PostgREST gestionados;
- bloqueos con tráfico;
- carreras de dos conexiones (las cubre `db-pg17-gate.sh`).
