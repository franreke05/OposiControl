# Plan de despliegue V1 a PRODUCCIÓN — backend OposiBots (Bot + Control)

Estado: **PLAN, NO EJECUTADO.** Rama `docs/v1-production-deploy-plan`, rebasada sobre `origin/develop` `bb33d36b`. Redactado el 2026-10-21 y actualizado el 2026-10-22.

**Auditoría de OposiControl sobre `ffcff773`: NO APTO.** Esta versión corrige cada punto (sección 15).
- **P0:** criterio de carga CERT-LOAD-1000 en F0 y en GO-6; cambio de Caddy en F2.
- **P2:** F3.4 con 40 migraciones; F0.3 con el comando exacto; volcado delta obligatorio; R-4 declarado sin ensayar (D-12); manifiesto version → blob → predecesora en el script; recaptura obligatoria de MAIN.
- **P3:** TRUNCATE en el preflight; actor del relleno validado; deshacer en un fichero propio; textos desfasados.

**Cambio del 2026-10-22.** OposiControl informa de que, con GO del propietario, ya están aplicadas en MAIN con VERIFY = 0 y **fuera del historial** `supabase_migrations` (se ejecutaron como SQL suelto):

- `20261023100000` (SEC-V1-01);
- `20261023100300` (RB-18);
- `20261023100400`;
- `20261023100500` (RB-17, parte de Control).

**RB-16 se hizo A MANO.** El propietario borró las 14 políticas en Storage > Policies, porque `20261023100100` se detuvo en su PREFLIGHT sin cambiar nada. `20261023100100` **no se ejecuta nunca**: no existe `private.sec_v1_storage_admin_policy_backup` y su PREFLIGHT fallaría cerrado.

`service_role` conserva I/U/D en todas las tablas. Las tablas, filas y pasos de abajo ya reflejan este estado.
Nada de este documento se ha ejecutado contra producción: ni SSH al VPS `oposibots-prod-01`, ni llamadas a `api.oposibots.es`, Cloudflare o al proyecto Supabase MAIN (`ndiooyyqtaeysnedywer`). El único trabajo hecho es local: el ensayo de migraciones (sección 5) en un contenedor PG17 desechable.

**Quién decide:** OposiControl audita este plan; después, el **propietario** aprueba y ejecuta cada paso de producción. Ningún paso de producción lo ejecuta Claude.

Staging fue **cancelado por el propietario**: se despliega directamente en producción. Este plan compensa esa ausencia con:

- el ensayo local completo (sección 5);
- un backup con restauración probada (fase F3);
- las comprobaciones posteriores contra GoTrue y Storage gestionados (sección 8), que es lo único que staging habría probado y aquí no se ha probado.

## 0. Leyenda

| Marca | Significado |
|---|---|
| **P** | Lo ejecuta el propietario (SSH, editor SQL o CLI de Supabase, ficheros de entorno del servidor). |
| **C** | Lo hace Claude u OposiControl, siempre en local, sobre el repo o sobre salidas que el propietario le pega. |
| `RO` | Solo lectura. No cambia nada. |
| `ESCRITURA` | Cambia algo, pero tiene vuelta atrás probada. |
| `IRREVERSIBLE` | No tiene vuelta atrás sin perder datos, o solo se arregla hacia delante. |
| **GO-n** | Aprobación explícita del propietario, que se anota con la hora en el registro de la ventana (sección 11). |
| **NO-GO** | Se para el despliegue. Cada fase lo define al final. |

Todos los comandos se escriben en **Git Bash** en la máquina del propietario, desde la raíz de un checkout limpio de OposiControl en el SHA que se despliega, o en la shell SSH del servidor (está indicado en cada uno).

Variables de la sesión (P, en su terminal; no se guardan en ningún fichero):

```bash
export OPOSI_SSH_HOST='opsadmin@49.12.232.217'          # valor documentado en RELEASE_RUNBOOK.md; revisar
export OPOSI_API_URL='https://api.oposibots.es'
export MAIN_DB_URL='postgresql://postgres.ndiooyyqtaeysnedywer:<CONTRASEÑA>@<HOST-DEL-SESSION-POOLER>:5432/postgres?sslmode=require'
# MAIN_DB_URL: Supabase > Project Settings > Database > Connection string > "Session pooler". Solo en la terminal del propietario.
```

## 1. Mapeo de los requisitos de OposiControl

| # | Requisito | Dónde se cumple | Estado |
|---|---|---|---|
| R1 | Ensayo completo de todas las migraciones sobre una copia con la forma de MAIN: PREFLIGHT, aplicar, VERIFY = 0, ROLLBACK, volver a aplicar, en el orden real e incluyendo CAND-04 | Sección 5 y `docs/audit-v1/evidence/V1-DEPLOY-REHEARSAL/` | **HECHO EN LOCAL**: el 2026-10-21 (43/43, 5.2) y el 2026-10-22 con el estado de MAIN de ese día y las ramas nuevas (40/40, ROLLBACK idéntico sin el fichero de restauración, 5.5). Límites en 5.4. El ensayo sobre datos reales se repite en F3.4, sobre la restauración del backup. |
| R2 | Backup verificado con prueba de restauración | Fase F3 | PLAN. Necesita GO-3 y una decisión de coste (sección 12). |
| R3 | Qué no se ha probado nunca contra GoTrue y Storage gestionados, y comprobaciones exactas tras el despliegue | Sección 8 | PLAN. Las comprobaciones se hacen en F6. |
| R4 | Rollback por fases con un punto de no retorno explícito | Sección 7 (PNR = R-3) | PLAN |
| R5 | Pagos: G1 y D8 apagados, crédito de tienda apagado, solo sandbox, verificado por configuración | Sección 9, F5.2 y F6.6 | PLAN |
| + | Preflight de catálogo de solo lectura en MAIN (has_table_privilege I/U/D de anon/authenticated + relrowsecurity) sobre las tablas pedidas, más el preflight propio de SEC-V1-01 | F1.2 y F1.4: `docs/engineering/v1-deploy/01_catalog_preflight_readonly.sql` y `supabase/canonical/sec_v1_01_main_intervention/01_*.sql`, `02_*.sql` | **Ensayado en local** (5.3). En MAIN, pendiente de GO-1. |
| + | Capacidad del VPS con umbrales de aborto | F2.1 | PLAN |
| + | Ventana de mantenimiento e impacto en usuarios (clientes viejos frente a la API nueva) | Sección 10 y F4.0 | PLAN |
| + | Humo tras el despliegue: health, `/metrics` 404 pública, recorrido de examen con cuenta sintética, IDOR | F6 | PLAN |
| + | Periodo de observación y alertas | F7 | PLAN |
| + | Dependencia del release del cliente | Sección 13 | **BLOQUEANTE** del release de la app; no del despliegue del servidor. |

## 2. Qué se despliega

- **Servidor:** una imagen `oposicontrol-backend:<sha12>` construida **solo** con `bash scripts/build-backend-image.sh` desde un checkout limpio del SHA aprobado: `origin/develop` (hoy `bb33d36b`) con las ramas de D-8 fusionadas. Si cambia algún blob de migración, se rehacen la sección 4, el manifiesto y el ensayo. Corre en tres contenedores: `bot-1` y `bot-2` (`SERVER_PROFILE=bot`) y `control`.
- **Base de datos MAIN:** las **40** migraciones de la ventana de la sección 4, en ese orden; 4 se comprueban sin re-aplicar y 1 no se ejecuta nunca.
- **No se despliega:** cliente OposiBot (sección 13), panel OposiControl (se distribuye aparte, después del backend: MASTER4-API-005), cambios de Caddy (las rutas de webhooks de tienda solo hacen falta con G1/D8; con los pagos apagados es más seguro dejarlas en deny-by-default, que responde 404), Cloudflare.

MAIN, además, desde el 2026-10-22: #37, #40, #41 y #42 aplicadas y RB-16 hecho a mano (cabecera). Producción hoy (inventario de solo lectura del 2026-10-14, `DEPLOYMENT_PLAN.md` 10.1): `oposibots-prod-01` (Ubuntu, 4 vCPU, 7,7 GB), Caddy en el host, `bot-1`/`bot-2`/`control` con `oposicontrol-backend:cfa2e14afc8e`, Valkey, Prometheus, Grafana y Alertmanager. MAIN tiene aplicadas las migraciones hasta `20261015100000`. Si después se aplicaron `20261020100000`/`20261021100000` (DEBT-156b, a mano), **no consta**: lo resuelve F1.1.

> **2026-10-22, hotfix de control (GO del propietario, desplegado por OposiControl):** solo `oposibots-ktor-control-1` corre `oposicontrol-backend:d922357e84d3` (rama `hotfix/control-tags-cfa2e14` sobre `cfa2e14a`: etiquetas y popup de recursos, sin migraciones, el marcador RESERVA bloqueado con aviso); bot-1 y bot-2 siguen en `cfa2e14afc8e`. `compose.env` sigue con el `BACKEND_IMAGE` viejo: un `compose up` del stack entero devolveria control a `cfa2e14`. Consecuencias para la ventana: (1) F0 recaptura la imagen de CADA contenedor (`docker inspect`), no la de `compose.env`; (2) el rollback de control vuelve a `d922357e84d3`, no a `cfa2e14afc8e` (las dos imagenes se conservan en el host); (3) nada de `compose up` del stack completo antes de F5.

## 3. Orden imagen ↔ migraciones (la regla)

1. **Todas las migraciones de la sección 4 van ANTES de arrancar la imagen V1.** Motivos:
   - Sin `app_server_now()` (`20261022100500`), las lecturas de acceso del Ktor V1 responden 503 (falla cerrado).
   - Sin `20261020100000`, el registro responde 503.
   - Sin el resto, las rutas V1 responden 503 o PGRST202.
2. **`20261022100700_v1_admin_grant` nunca sin el código `admin_grant_exam_access` del Ktor.** Añade el trigger `GIFT_UNLIMITED_FORBIDDEN` y cambia la firma de la RPC. El control VIEJO (`cfa2e14`) inserta directo en `contenido_desbloqueado_usuario` sin `valido_hasta` y recibiría 23514. Por eso los tres Ktor viejos se **paran antes** de migrar (F4.1) y el siguiente arranque ya es la imagen V1.
3. La imagen VIEJA deja de ser compatible a partir de `20261022100300`: retira las escrituras de `service_role` en el libro y la cartera. Además, `20261022100600` y `20261022101000` borran la firma vieja de `app_my_exams_collection` y `20261022101600` borra una columna y una vista. Consecuencia: **la imagen vieja solo puede volver mientras se deshacen TODAS las migraciones** (rollback R-2, sección 7). Regla heredada que sigue vigente: la imagen anterior a `20260930100800` no se rearranca nunca.
4. Las `_security_` de DEBT-156b (`20261023100000..100600`) no dependen del Ktor (Ktor escribe con `service_role`). Se aplican en la misma ventana, por versión. **CAND-04:** `20261023100200` va después de `20261022102600`, porque lee `compras_usuario.entorno`. El orden numérico ya lo cumple.
5. `20261023100100` (RB-16) **NO se ejecuta**. En MAIN se detuvo en su PREFLIGHT, porque `postgres` no pertenece a `supabase_storage_admin`; el ensayo local de 5.2 ya lo predijo. RB-16 está **HECHO A MANO**: el propietario borró las 14 políticas en Storage > Policies el 2026-10-22. Comprobación (F1.2 y F4.4, `RO`): `storage_escritura_admin = 0` en `01_catalog_preflight_readonly.sql`. `apply-main-migration.sh` se niega a ejecutar 100100.
6. `20261023100000`, `100300`, `100400` y `100500` **ya están en MAIN**. No se re-aplican: sus PREFLIGHT aceptan el estado aplicado y serían un no-op, pero se saltan igualmente. El script se niega a aplicarlas. En F1 solo se comprueba su VERIFY. Registrarlas en el historial es la decisión D-10.

## 4. Migraciones y orden exacto

Fuente: `supabase/baseline/PENDING_MIGRATIONS.txt` (34 V1) más las `_security_` gestionadas fuera de PENDING por DEBT-156b. La columna `blob` es `git hash-object` del fichero. `apply-main-migration.sh` la imprime antes de pedir confirmación y tiene que coincidir.

Columnas:

- **#**: orden de aplicación.
- **Clase**: `E` (expand, compatible con la imagen vieja), `C` (contract, rompe la imagen vieja), `H` (hardening de privilegios de clientes, independiente del Ktor).
- **Datos**: preflight de datos que exige aprobación (F1.3).

| # | Migración | blob | Clase | Datos / notas |
|---|---|---|---|---|
| 1 | `20261020100000_security_access_request_email_verified` | `95678b9244b7` | E | Solo si F1.1 dice que falta (F1 2026-10-22: falta). Con bloque ROLLBACK y en el gate PG17 (ventana, `fix/v1-security-pending-gate`). La imagen V1 la necesita (registro). |
| 2 | `20261021100000_security_audit_log_append_only` | `cc57f43697f8` | H | Solo si falta (F1 2026-10-22: falta). Con bloque ROLLBACK, EXECUTE revocado a anon/authenticated y en el gate PG17: `auditoria_log` pasa a solo-añadir para todos los roles. |
| 3 | `20261022100000_slice3_attempt_content_revisions` | `972f494b2799` | E | Intentos abiertos duplicados = 0 |
| 4 | `20261022100100_security_exam_publication_time` | `21d685eaac7c` | H | — |
| 5 | `20261022100200_v1_exam_community_required` | `74bd03a7c5bd` | C | Exámenes sin comunidad = 0 (2026-10-19: 0 de 3). Añade `SET NOT NULL`. |
| 6 | `20261022100300_v1_wallet_ledger_hardening` | `0c6fd1fdc430` | **C** | Carteras sin `legacy_opening` y libro descuadrado: **aprobación D-2**. Retira las escrituras de service_role. |
| 7 | `20261022100400_v1_payment_orders` | `265c89810ca5` | E | Compras duplicadas = 0 |
| 8 | `20261022100500_v1_exam_redeem` | `a4e2aa572bf8` | E | `app_server_now()`. Accesos de compra duplicados = 0. |
| 9 | `20261022100600_v1_specialty_immutable` | `bf7eda9c2f4f` | C | Borra la firma vieja de `app_my_exams_collection` |
| 10 | `20261022100700_v1_admin_grant` | `1c947a08c76d` | C | Exige el código `admin_grant_exam_access` (ya está en la imagen V1) |
| 11 | `20261022100800_v1_saved_items` | `a6cda2ab93bc` | E | — |
| 12 | `20261022100900_v1_plan_access_v2` | `863b9e12a42e` | E | — |
| 13 | `20261022101000_v1_demo_mode` | `3f614032342b` | C | Redefine `app_my_exams_collection` |
| 14 | `20261022101100_v1_attempt_limiter` | `9101379f10d4` | E | — |
| 15 | `20261022101150_v1_plan_catalog` | `1db4668a4202` | E | — |
| 16 | `20261022101200_v1_daily_challenge` | `f2e1dc5a4172` → `111a2fe76fd8`† | H | Retira grants de cliente sobre objetos de MAIN |
| 17 | `20261022101300_v1_official_rules` | `4b4b9c18a9b3` | E | — |
| 18 | `20261022101500_v1_config_bounds` | `1da70fd8733e` | E | — |
| 19 | `20261022101600_v1_stats` | `30d1abd936c0` → `d7a5862322f0`† | C | Borra la columna `ventana_tiempo_medio_ms` y la vista `ranking_ventanas_rapidas` |
| 20 | `20261022101700_v1_ranking` | `55bc11ddf21d` | E | — |
| 21 | `20261022101800_v1_referral` | `9a4467accee4` | E | Trigger en `auth.users` (sección 8, G-4) |
| 22 | `20261022101900_v1_foro` | `bdd639676d1d` → `d054c44b6b26`† | H | Publicaciones y comentarios = 0 (2026-10-19) |
| 23 | `20261022102000_v1_import_reserves` | `3b25340b7136` | E | Duplicados de staging (solo informa) |
| 24 | `20261022102100_v1_razonado_markup` | `91e321a19616` | E | — |
| 25 | `20261022102200_v1_auth_code_binding` | `3358285c2072` | E | Va con PKCE (`AUTH_PKCE_MODE=required`) |
| 26 | `20261022102300_v1_control_rpcs` | `88be74916ee8` | E | — |
| 27 | `20261022102400_v1_refund_order_fix` | `13196f58478e` | E | — |
| 28 | `20261022102500_v1_auth_binding_hardening` | `258d248e4cea` | E | — |
| 29 | `20261022102600_v1_store_payments` | `025272639da2` | E | Suscripciones duplicadas = 0. Añade `compras_usuario.entorno`. |
| 30 | `20261022102700_v1_user_tables_client_write_revoke` | `067370515d81` | H | — |
| 31 | `20261022102800_v1_store_payments_fixes` | `cb44292c117a` | E | — |
| 32 | `20261022102900_v1_store_payments_concurrency` | `88fed1554118` | E | — |
| 33 | `20261022103000_v1_store_payments_round3` | `399ac948772c` | E | — |
| 34 | `20261022103100_v1_store_payments_round4` | `103dc7dde657` | E | — |
| 35 | `20261022103200_v1_store_payments_round5` | `df9798446385` | E | — |
| 36 | `20261022103300_v1_store_payments_round6` | `953f8e469b52` | E | — |
| 36a | `20261022103400_v1_exam_penalty_official_default` | `5884d25a407a` | E? | NUEVA. Rama `feature/v1-exam-penalty-scope` `17b99dbe`, en `integration/v1-apt-five`. Clase por confirmar con Bot. Ensayada el 2026-10-22 (5.5). **Justo después: paso F4.3b, relleno de la penalización racional (D-11 DECIDIDA)** |
| 36b | `20261022103500_v1_daily_challenge_dst` | `6a1c95defc8b` | E? | NUEVA. Rama `fix/v1-daily-challenge-dst` `6904357c`, en `integration/v1-apt-five`. Reto diario con cambio de hora (DST). Ensayada el 2026-10-22 (5.5) |
| 37 | `20261023100000_security_content_admin_write_revoke` (SEC-V1-01 / RB-01) | `d96765e28acc` | H | **YA APLICADA EN MAIN (2026-10-22, VERIFY 0, fuera del historial). SE SALTA.** F1: `--verify-only` = 0 |
| 38 | `20261023100100_security_storage_admin_write_revoke` (RB-16) | `fcea91b64077` | H | **NO SE EJECUTA NUNCA.** RB-16 HECHO A MANO el 2026-10-22 (14 políticas borradas en Storage > Policies). Comprobación: `storage_escritura_admin = 0` |
| 39 | `20261023100200_security_revenue_excludes_sandbox` (PAY-F3, **CAND-04**) | `879bc8f2ecc4` | H | Después de #29 |
| 40 | `20261023100300_security_moderator_requires_active_account` (RB-18) | `294080b01de0` | H | **YA APLICADA EN MAIN (2026-10-22). SE SALTA.** |
| 41 | `20261023100400_security_noticia_trigger_search_path` | `0bb99d024e9c` | H | **YA APLICADA EN MAIN (2026-10-22). SE SALTA.** |
| 42 | `20261023100500_security_admin_tables_write_revoke` (RB-17, parte de Control) | `3aba58fecce9` | H | **YA APLICADA EN MAIN (2026-10-22). SE SALTA.** RB-17: fix aplicado (parte de Control); la parte de Bot va en #30 |
| 43 | `20261023100600_security_plans_store_client_write_revoke` (SEC-BOT-P3) | `f68f2a28f544` | H | Rama `feature/v1-plans-store-grants-revoke` `4b4acf81` (sin fusionar en develop). Sobre la forma de MAIN es un **no-op que fija el estado** (5.3). Ensayado el blob con commit el 2026-10-22 (5.5) |

† Blob con `fix/v1-rollback-blocks-main-acl` `0fe19e65`: solo cambian los bloques ROLLBACK (comentados), que ahora restauran el ACL y las políticas exactas de MAIN. El SQL que se aplica no cambia. Se despliega el blob que esté en el SHA aprobado, y `apply-main-migration.sh` lo imprime.

**Manifiesto:** `ops/deploy/v1/main-migrations.manifest` (version → blob → predecesora → clase). `apply-main-migration.sh` lo hace cumplir **antes de conectar** a la base: se niega si la versión no está en el manifiesto, si el blob no es el ensayado, si la clase no admite el modo, o (al aplicar) si la predecesora no está ya en `supabase_migrations`. Probado en 5.6.

**Pendientes de aplicar en la ventana: hasta 40** (las 2 LEGACY si F1.1 dice que faltan, 34 de PENDING, #36a, #36b, #39 y #43). **Se saltan:** #37, #40, #41 y #42 (ya en MAIN). **Nunca:** #38. #39 (`20261023100200`) sigue pendiente y va después de #29 (CAND-04).

Todas llevan su propio `begin; … commit;` y `lock_timeout` (1-5 s). Un fallo deshace solo esa migración.

## 5. Ensayo local (requisito R1)

### 5.1 Qué se ensayó

Script: `docs/audit-v1/evidence/V1-DEPLOY-REHEARSAL/rehearse-v1-deploy.sh`.

Entorno:

- Contenedor propio `v1-deploy-rehearsal-pg17`: imagen Supabase PostgreSQL 17.6.1.171 fijada por el mismo digest que el gate, 1 GB y sin puerto publicado. Se borra por nombre al terminar. No usa `oposi-exam-local` ni `oposi-control-e2e`.
- `postgres` **no** es superusuario, igual que en el editor SQL de MAIN.

Forma de MAIN:

- **Completa**, no el subconjunto del gate: la captura de catálogo de solo lectura de MAIN del 2026-10-14 (`~/.oposibots-gate/baseline-20261014/baseline_main.sql`, sha256 `07acead5…`; 95 tablas `public`, sin filas).
- Las 2 vistas de la captura del 2026-09-29, porque la de 10-14 no las trae.
- La forma GoTrue de `auth.users` (`supabase/baseline/main_shape_auth_users.sql`).
- El stub de `storage.objects` del expediente SEC-V1-01: 14 políticas de escritura y 3 de lectura con `es_admin()`/`can_manage_content()`.

Datos: una **semilla sintética** (`seed_synthetic.sql`) para pasar por los caminos de datos:

- 4 usuarios y 1 superadmin;
- una cartera con saldo y sin libro, una con libro que cuadra y una con libro descuadrado;
- una compra pagada y una suscripción.

Pasos:

1. PREFLIGHT de solo lectura con los **mismos ficheros** que ejecutará el propietario en MAIN (F1.2-F1.4) y la auditoría de comunidad.
2. Las 43 migraciones del ensayo del 2026-10-21 (el conjunto de entonces, antes de que se aplicaran las 4 de seguridad en MAIN), una a una y en ese orden, con su VERIFY justo después de cada una. RB-16 se intenta primero sin pertenencia a `supabase_storage_admin` (debe detenerse sin cambiar nada) y después con ella.
3. Comprobaciones de cartera y de catálogo, y `assertions.sql` del gate.
4. ROLLBACK de las 41 que lo tienen, en orden inverso. Las dos LEGACY son forward-only y quedan aplicadas.
5. La huella del catálogo debe ser igual que tras las LEGACY.
6. Volver a aplicar las 41 con VERIFY = 0. La huella debe ser igual que en la primera aplicación y no deben duplicarse las aperturas.

### 5.2 Resultado

Ejecución válida del 2026-10-21: `rc=0`, `V1-DEPLOY-REHEARSAL OK`. Detalle en `docs/audit-v1/evidence/V1-DEPLOY-REHEARSAL/README.md` y `run-2026-10-21/`.

- **Forma:** la de MAIN carga con 0 errores (95 tablas, 132 políticas).
- **Preflight de solo lectura:** limpio. SEC-V1-01 sin filas `false`; ningún `BLOQUEA` distinto de 0.
- **Orden y CAND-04:** 43 migraciones en el orden de la sección 4; `20261023100200` (posición 39) va después de `20261022102600` (29).
- **RB-16 sin pertenencia:** se detiene en `STORAGE_ADMIN_WRITE_PREFLIGHT` sin cambiar nada. Con pertenencia, aplica.
- **Primera aplicación:** **43/43, VERIFY = 0** (354 filas).
- **Cartera:** 2 `legacy_opening` (60 OT), reconcile = 0, saldos sin cambios.
- **ROLLBACK (41, inverso):** **HALLAZGO**. Los bloques de `101900`, `101600` y `101200` no devuelven la forma de MAIN: 39 líneas; el foro queda con `grant all` a `anon`. Con `R2_post_rollback_restore_main_acl.sql`, el catálogo queda **idéntico** a MAIN. Ver la sección 7.
- **Re-aplicación:** 41/41, VERIFY = 0, catálogo idéntico al de la primera aplicación, sin aperturas duplicadas.
- **`assertions.sql` del gate:** falla en su primer ASSERT, porque espera una base vacía y aquí hay una semilla. Informativo.
- **`apply-main-migration.sh`, probado en un contenedor local** (`apply-main-migration-selftest.log`):
  - una versión mal tecleada no toca nada;
  - aplica y registra;
  - se niega a re-aplicar;
  - un PREFLIGHT fallido no registra nada.

### 5.3 Valores de referencia para MAIN (forma de la captura de 2026-10-14)

Preflight de catálogo (F1.2), ANTES de desplegar. Con `t` en I/U/D de `authenticated`:

- `etiquetas_opositeca`, `invitaciones_referido`, `opositeca_items` y `preguntas_parseadas_staging`, cada una con su `*_admin_all`;
- `lotes_actualizacion_ranking`.

`anon` no tiene ninguna. `suscripciones`, `beneficios_plan` y `capacidades_especialidad` tienen **I/U/D = false** para los dos roles: en la captura de MAIN solo tienen SELECT. Por eso `20261023100600` es un no-op de fijación. Si F1.2 da `t` en alguna, hay **deriva** desde el 2026-10-14 y se anota. La migración la cierra igual.

Storage:

- `postgres` no es miembro del dueño de `storage.objects` (esperado en MAIN, NOT_VERIFIED).
- 14 políticas de escritura y 3 de lectura con `es_admin()` (14 confirmado en MAIN por catálogo el 2026-10-20).

DESPUÉS: todo `false` salvo `authenticated` INSERT en `invitaciones_referido`, que se mantiene a propósito (`invitaciones_referido_insert_propias`). Además, 0 políticas `*_admin_all` y 0 políticas de escritura de storage con `es_admin` (si RB-16 se aplicó).

### 5.5 Re-ensayo con el estado de MAIN del 2026-10-22 y las ramas nuevas — HECHO, `rc=0`

El ensayo de 5.2 (2026-10-21) es anterior a cuatro cambios:

- las 4 migraciones de seguridad ya aplicadas en MAIN;
- RB-16 hecho a mano;
- `20261022103400` y `20261022103500`;
- la versión con commit de `20261023100600` y la corrección de los ROLLBACK (`0fe19e65`).

El script ya admite ese punto de partida:

- `PREAPPLIED_ON_MAIN`: las carga como estado inicial, comprueba su VERIFY = 0 y las deja fuera del orden y del ROLLBACK;
- `NEVER_RUN`: las salta siempre;
- `MANUAL_STORAGE_DROP=1`: borra las políticas de escritura de admin de `storage.objects`, como hizo el propietario.

**Resultado del 2026-10-22** (el segundo intento, cuando Docker Desktop volvió con aprobación del propietario; evidencia en `docs/audit-v1/evidence/V1-DEPLOY-REHEARSAL/run-2026-10-22/`):

- **Árbol:** develop `666d0a57` + 103400 (`74554ccd`), 103500 (`6904357c`), 100600 (`4b4acf81`) y los ROLLBACK de `0fe19e65`.
- **Punto de partida:** las 4 ya aplicadas con VERIFY = 0 y las 14 políticas de storage borradas.
- **Orden y CAND-04:** **40** migraciones; 100200 en la posición 39, después de 102600 (29).
- **Primera aplicación:** **40/40, VERIFY = 0** (353 filas).
- **Cartera:** 2 aperturas (60 OT), reconcile 0.
- **ROLLBACK de 38:** catálogo **idéntico a MAIN sin el fichero de restauración**, así que `0fe19e65` queda probado.
- **Re-aplicación:** VERIFY = 0 y catálogo idéntico.
- **`apply-main-migration.sh`, contra un contenedor con el estado de hoy:** `--verify-only` = 0 en las 4 aplicadas; `--record-only` registra sin ejecutar y es idempotente; se niega a re-aplicar las 4, a registrar 100200 y a ejecutar 100100. El VERIFY de 100100 no vale para RB-16, porque falta su tabla de copia; se usa `storage_escritura_admin = 0`.

Vale para el SHA que contenga **exactamente** esos blobs. Si D-8 fusiona otra versión, se repite. Comando (C, local), sobre un árbol que contenga el SHA aprobado o las ramas de D-8:

```bash
PREAPPLIED_ON_MAIN="20261023100000 20261023100300 20261023100400 20261023100500" NEVER_RUN="20261023100100" MANUAL_STORAGE_DROP=1 \
EXTRA_MIGRATIONS="supabase/migrations/20261022103400_v1_exam_penalty_official_default.sql supabase/migrations/20261022103500_v1_daily_challenge_dst.sql" \
bash docs/audit-v1/evidence/V1-DEPLOY-REHEARSAL/rehearse-v1-deploy.sh /tmp/v1-rehearsal-2026-10-22
```

(`EXTRA_MIGRATIONS` solo hace falta mientras 103400 y 103500 no estén en `PENDING_MIGRATIONS.txt`.) Con `0fe19e65` en el árbol, el log debe decir `ROLLBACK inverso de N migraciones: catalogo == estado tras las 2 LEGACY` **sin** usar el fichero de restauración. F0.3 se cumple con esta ejecución para esos blobs. Sobre el SHA final se repite junto con el gate.

### 5.4 Qué NO puede demostrar este ensayo

- **Datos reales.** La semilla es sintética. Los PREFLIGHT de datos y el relleno `legacy_opening` sobre las filas de MAIN se prueban en F3.4, sobre la restauración del backup fresco, y se aprueban con F1.3.
- **Deriva posterior al 2026-10-14.** La forma es de esa fecha. F1.1-F1.2 detectan diferencias de historial y de privilegios. Un cambio de cuerpo de una función hecho en el panel de Supabase solo lo vería una nueva captura.
- **Huella sin cuerpos de función.** Es la misma limitación que el gate: compara nombres, flags, ACL y políticas, no el texto de las funciones.
- **Roles y propiedad reales.** El rol del editor SQL de MAIN y su pertenencia a `supabase_storage_admin` se miden en F1.2. En el ensayo, la pertenencia se concedió a mano.
- **Plataforma gestionada.** GoTrue (versión, triggers propios, plantillas), Storage API, PostgREST y su caché de esquema, Realtime y Edge Functions. Ver la sección 8.
- **Bloqueos y tráfico concurrente.** MAIN tiene poco tráfico y la ventana para los Ktor (F4.1), pero los `lock_timeout` de 1-5 s solo se prueban en MAIN.
- **Tiempos.** Los tiempos de 5.2 son de un contenedor local casi vacío más el coste de `docker exec`. En MAIN dependen de la red hasta el pooler.
- **Carreras de dos conexiones.** Las cubre el gate del repo (`db-pg17-gate.sh`, verde en develop `d9d4d28d`, sin cambios de migraciones V1 desde entonces). Las carreras de tienda R2.1 y R3.2 son **inestables bajo carga** (se arreglan en otra rama). No forman parte de este ensayo.

## 6. Fases

Cada fase termina en una decisión GO/NO-GO. Si sale NO-GO **antes de F4**, no hay nada que deshacer.

### F0 · Requisitos y aprobaciones previas (P, `RO`)

1. Decisiones de la sección 12 tomadas por escrito (D-1 … D-9).
2. SHA de despliegue fijado: `git -C <repo> rev-parse origin/develop`, más la lista de ramas sin fusionar que el propietario da por fuera.
3. Gate local verde **sobre ese SHA** (C, local; GitHub Actions sigue **desactivado** y no se reactiva):
   ```bash
   bash scripts/db-pg17-gate.sh && bash scripts/db-pg17-gate.test.sh
   bash -c 'L=/c/Users/franr/AppData/Local/OposiBotGradleMutex; until mkdir "$L" 2>/dev/null; do sleep 15; done; trap "rmdir $L" EXIT; cd <WT> && LOCALAPPDATA="C:\\Users\\franr\\AppData\\Local\\OposiBotEnv-deployplan" ./gradlew.bat :backend:ktor:test :backend:dbtest:test --console=plain'
   bash scripts/verify-ops-config.sh && bash scripts/verify-migrations-manifest.sh
   # el comando EXACTO de la seccion 5.5 (estado de MAIN de 2026-10-22), sobre el arbol del SHA aprobado:
   PREAPPLIED_ON_MAIN="20261023100000 20261023100300 20261023100400 20261023100500" NEVER_RUN="20261023100100" MANUAL_STORAGE_DROP=1 \
   bash docs/audit-v1/evidence/V1-DEPLOY-REHEARSAL/rehearse-v1-deploy.sh /tmp/v1-rehearsal
   # (+ EXTRA_MIGRATIONS="... 20261022103400 ... 20261022103500 ..." solo si aun no estan en PENDING_MIGRATIONS.txt)
   bash docs/audit-v1/evidence/V1-DEPLOY-REHEARSAL/run-2026-10-22b/window-selftest.sh <arbol del SHA> <boot>   # la ventana completa con el script y el manifiesto (5.6)
   ```
   Esperado: `V1-DEPLOY-REHEARSAL OK` y `WINDOW-SELFTEST OK`, y el manifiesto con los blobs del SHA (`git hash-object`).
   Si una carrera de tienda (R2.1/R3.2) falla de forma intermitente, se repite y se registran las dos ejecuciones.
4. RELEASE_BLOCKERS P1 que afectan al **servidor**, cada uno cerrado o aceptado por escrito (D-9):
   - RB-06 (reembolsos apagados);
   - RB-07 (sin proveedor real: V1 sin compra con dinero);
   - RB-09 (este plan);
   - RB-10 (`.env` local con service_role de MAIN);
   - RB-12 (runtime);
   - RB-13 (barrido IDOR completo).
5. **Criterio de carga (P0 de OposiControl, D-13).** CERT-LOAD-1000 superado **después de cerrar sus P0**, o **aceptación escrita del propietario** de no cumplirlo. Informe: `C:\Users\franr\OneDrive\Desktop\OposiBot\docs\ledger\2026\10\CERT-LOAD-1000-2026-10-21.md`, veredicto NOT READY, en local. Sus P0:
   - **Control de admisión en Ktor** (503 `SERVER_BUSY` + `Retry-After` y plazo por petición): `fix/v1-overload-admission-control` `261df4b2`, sin fusionar (D-8).
   - **Caddy sin expulsión pasiva** del pool bot (`max_fails`/`fail_duration`): misma rama. En producción se aplica en F2.2b.
   - **Reto diario sin cerrojo global** en la primera apertura del día: `20261022103500` (`fix/v1-daily-challenge-dst` `6904357c`).
   - **Arranques simultáneos:** el cliente añade *jitter* y *backoff* (no hay cliente candidato, sección 13). Además, el propietario decide si se acepta un `iniciado_en` fantasma en exámenes oficiales con tiempo.

   «Superado» significa repetir la prueba del §7 del informe tras esos cierres: 100 → 250 → 500 → 1000, con abortos si hay más de 1 % de 5xx, p95 > 2 s o algún "no upstreams available". Esa prueba necesita su propio GO y no toca datos de MAIN.

**NO-GO si:** falta una decisión D-x, el gate no está verde, el SHA no es el ensayado, o **no se cumple el criterio de carga del punto 5** (ni prueba superada ni aceptación escrita).

### F1 · Preflight de solo lectura en MAIN (P en el editor SQL de MAIN, `RO`, **GO-1**)

Guardar cada salida completa fuera del repo (`~/.oposibots-gate/v1-deploy/<fecha>/`) y pasársela a C/OposiControl.

1. **Historial:** `docs/engineering/v1-deploy/00_history_readonly.sql`.
   - Esperado: 77 versiones (o 79 si ya se aplicaron 20261020/21), la última `20261015100000` (o `20261021100000`) y ninguna versión `>= 20261016` fuera de la sección 4.
   - #37, #40, #41 y #42 se aplicaron el 2026-10-22 como SQL suelto (100300, 100400 y 100500 con el MCP). Según OposiControl **no** están en el historial. Si F1.1 muestra filas del 2026-10-22 con otra versión o nombre (el MCP puede registrar con su propia marca de tiempo), son esas y **no son deriva**: se anotan, y D-10 decide si además se registra la versión del repo.
   - Si `20261020100000`/`20261021100000` ya están, se quitan las filas 1-2 de la sección 4.
   - Si su huella física existe pero la versión no está registrada, solo se registra con el `insert` de F4.3, sin re-aplicar.
   - Además, leer las columnas del historial: `select column_name from information_schema.columns where table_schema = 'supabase_migrations' and table_name = 'schema_migrations';` (deben existir `version` y `name`).
2. **Catálogo:** `docs/engineering/v1-deploy/01_catalog_preflight_readonly.sql`. Se compara con 5.3. Se anota:
   - **RB-16 (hecho a mano):** `storage_escritura_admin` tiene que dar **0** (`storage_lectura_admin` = 3). Si da algo distinto de 0, NO-GO: alguien ha vuelto a crear una política de escritura de admin. `puede_cambiar_politicas_storage` es solo informativo, porque 100100 no se ejecuta;
   - después de 100500, que ya está en MAIN, las 4 tablas de RB-17 dan `false` en I/U/D, salvo `authenticated` INSERT en `invitaciones_referido`;
   - el número de Edge Functions (panel > Edge Functions; esperado 0).
3. **Datos (agregados; leen filas, por lo que necesitan su propio GO-1b):** `docs/engineering/v1-deploy/02_data_preflight_readonly.sql`.
   - Toda fila `BLOQUEA` debe dar 0 (`superadmins_activos` debe ser ≥ 1).
   - Las filas `INFORMA + APROBACION` las aprueba el propietario por escrito (D-2).
4. **Seguridad ya aplicada el 2026-10-22** (#37, #40, #41, #42): su VERIFY de solo lectura tiene que dar 0. El preflight de SEC-V1-01 ya no aplica, porque supone el estado previo. La foto de catálogo anterior es el checkpoint de las 07:13:07 UTC (huella `7c8fbdd6…`, fuera del repo).
   ```bash
   for v in 20261023100000 20261023100300 20261023100400 20261023100500; do bash ops/deploy/v1/apply-main-migration.sh --verify-only supabase/migrations/${v}_*.sql; done 2>&1 | tee verify-seguridad-aplicada.txt
   grep -c 'VERIFY = 0' verify-seguridad-aplicada.txt     # = 4
   ```
5. **Comunidad:** `scripts/db-exam-community-audit.sql`. `without_community` debe ser 0.
6. **VERIFY previo de las `_security_` todavía pendientes** (#39 y #43; foto antes de aplicar; valores distintos de 0 son normales aquí):
   ```bash
   for v in 20261023100200 20261023100600; do
     bash ops/deploy/v1/apply-main-migration.sh --verify-only supabase/migrations/${v}_*.sql
   done 2>&1 | tee verify-antes.txt
   ```
   (Corre en local con la imagen PG17 ya descargada y lee MAIN por `MAIN_DB_URL`. Solo hace `begin read only`.) Antes de aplicar, un VERIFY puede dar error porque nombra objetos que la migración todavía no ha creado (en el ensayo pasó con 20261020100000). Eso es normal; lo que importa es la foto de las que sí se ejecutan.

**NO-GO si:**

- hay deriva de historial no explicada;
- alguna fila `BLOQUEA` es distinta de 0;
- alguna de las 4 ya aplicadas no da VERIFY = 0, o `storage_escritura_admin` ≠ 0;
- hay Edge Functions que escriben tablas de contenido;
- no hay ningún superadmin activo.

### F2 · Servidor e imagen (P por SSH `RO`; build en local)

1. **Capacidad del VPS** (SSH, `RO`):
   ```bash
   ssh "$OPOSI_SSH_HOST" 'nproc; free -m; df -h / /var/lib/docker; uptime; docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}"; docker ps --format "{{.Names}} {{.Image}} {{.Status}}"; docker image ls oposicontrol-backend --format "{{.Tag}} {{.Size}} {{.CreatedAt}}"'
   ```

   | Medida | Umbral de ABORTO | Por qué |
   |---|---|---|
   | `nproc` | < 4 | La suma de topes es de unos 6,25 CPU (PRODUCTION_RUNBOOK) |
   | `free -m`, columna `available` | < 1500 MB | El despliegue rodante arranca una JVM nueva (768 MB de tope) antes de dar por sana la réplica |
   | `df -h /` y `/var/lib/docker`, libre | < 5 GB (recomendado ≥ 10 GB) | `docker load` de la imagen más las capas, y logs |
   | `uptime`, carga de 1 min | > `nproc` sostenido | Ya está saturado |
   | `docker stats`, MemPerc de algún Ktor | > 90 % | Presión de heap previa: investigar antes |
   | `docker ps` | algún contenedor `unhealthy` o reiniciándose | No se despliega sobre un sistema roto |

   Además, `docker image prune` **no** se ejecuta: `cfa2e14afc8e` (y la etiqueta `rollback-*`) tienen que seguir en el host.
2. **Script del servidor** (`RO`):
   ```bash
   ssh "$OPOSI_SSH_HOST" 'cat ~/redeploy-rolling.sh'
   ```
   Pegar la salida a OposiControl. Hay que confirmar tres cosas:
   - qué fichero de compose y qué `compose.env` usa;
   - que arranca un servicio que esté **parado** (F4.1 los para);
   - que vuelve a la imagen anterior si uno no queda sano.

   Si no arranca servicios parados, F5.3 usa el camino manual que se indica allí.
2b. **Caddy del host: sin expulsión pasiva del pool bot** (P0 de CERT-LOAD-1000; `ESCRITURA`, **GO-2b**; P por SSH con sudo).
   Cambio de `fix/v1-overload-admission-control` (`ops/caddy/Caddyfile.hostcaddy`). En el `reverse_proxy` del pool `@bot`:
   - fuera `fail_duration` y `max_fails`;
   - `health_interval 5s` y `health_timeout 4s`;
   - `dial_timeout 1s`.

   Es compatible con la imagen vieja (solo cambia la salud), así que va **antes** de la ventana.
   ```bash
   ssh "$OPOSI_SSH_HOST" 'sudo cp -a /etc/caddy/Caddyfile /etc/caddy/Caddyfile.bak-$(date +%Y%m%d%H%M) && sudo grep -n -E "max_fails|fail_duration|health_interval|health_timeout|dial_timeout|lb_try_duration" /etc/caddy/Caddyfile'   # RO + copia
   git -C <repo> show fix/v1-overload-admission-control:ops/caddy/Caddyfile.hostcaddy > /tmp/Caddyfile.hostcaddy.v1
   diff <(ssh "$OPOSI_SSH_HOST" 'sudo cat /etc/caddy/Caddyfile') /tmp/Caddyfile.hostcaddy.v1   # RO: C revisa que SOLO cambian esas lineas
   # El propietario edita /etc/caddy/Caddyfile (sudo) con ese cambio y nada mas, y despues:
   ssh "$OPOSI_SSH_HOST" 'sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile && sudo systemctl reload caddy && sudo grep -c -E "max_fails|fail_duration" /etc/caddy/Caddyfile'   # el ultimo numero debe ser 0
   curl -s -o /dev/null -w '%{http_code}\n' "$OPOSI_API_URL/health/live"   # 200
   ```
   **Rollback:** `sudo cp -a /etc/caddy/Caddyfile.bak-<fecha> /etc/caddy/Caddyfile && sudo systemctl reload caddy`. El fichero real nunca se ha leído (necesita sudo; inventario 10.1); por eso el `diff` va primero.
3. **Entorno** (`RO`; solo NOMBRES, nunca valores):
   ```bash
   ssh "$OPOSI_SSH_HOST" 'cd ~/oposibots-stack/env && for f in backend.common.env backend.bot.env backend.control.env compose.env; do echo "== $f"; grep -oE "^[A-Z_]+=" "$f" | sort; done'
   ```
   C lo compara con `ops/deploy/backend.{common,bot,control}.env.example` del SHA. Variables **nuevas y obligatorias** de la imagen V1 (sin ellas el contenedor no arranca):
   - **bot:** `AUTH_RATE_LIMIT_MODE=valkey`, `AUTH_PKCE_MODE=required`;
   - **control:** `ADMIN_RATE_LIMIT_MODE=valkey`, `ADMIN_RATE_LIMIT_VALKEY_URL`, `ADMIN_RATE_LIMIT_HMAC_SECRET` (≥ 32 caracteres, distinto del de bot).

   Los valores de pagos se fijan en la sección 9. `PAYMENTS_SANDBOX_SECRET` **no** debe aparecer en ningún fichero.
4. **Construir la imagen** (P o C en local, `RO` para producción):
   ```bash
   git fetch origin && git switch --detach <SHA> && git status --porcelain   # vacio
   bash ops/deploy/release.sh build                                         # usa scripts/build-backend-image.sh y compara el ktor.jar
   docker run --rm --entrypoint sh oposicontrol-backend:<sha12> -c 'sha256sum /app/lib/ktor.jar'      # = sha256sum backend/ktor/build/install/ktor/lib/ktor.jar
   unzip -l backend/ktor/build/install/ktor/lib/ktor.jar | grep -c -E 'StorePaymentDecisions|ServerClock'  # > 0: el jar (identico al de la imagen) trae codigo V1
   docker inspect oposicontrol-backend:<sha12> --format '{{index .Config.Labels "org.opencontainers.image.revision"}}'   # = SHA completo
   docker run --rm --entrypoint sh oposicontrol-backend:<sha12> -c 'test "$(id -un)" = app && ! touch /app/x 2>/dev/null && touch /tmp/x' && echo OK-usuario
   ```
   Se anota el sha256 del jar. La comprobación de clases se hace sobre el jar local, que es idéntico al de la imagen (`release.sh build` compara los dos hashes). `StorePaymentDecisions` y `ServerClock` solo existen en el código V1, así que tiene que dar > 0. **Nunca** se usa `docker build` a mano (incidente del jar viejo, 2026-10-09/10).

   `release.sh preflight` exige estar en la rama `develop`. Si se despliega un SHA distinto de la punta de develop, se usa `RELEASE_BRANCH` o se hace el build desde develop en ese SHA.
5. **Cargar la imagen en el servidor SIN arrancarla** (`ESCRITURA` inocua: solo añade una imagen):
   ```bash
   docker save oposicontrol-backend:<sha12> | gzip | ssh "$OPOSI_SSH_HOST" 'gunzip | docker load'
   ssh "$OPOSI_SSH_HOST" 'docker run --rm --entrypoint sh oposicontrol-backend:<sha12> -c "sha256sum /app/lib/ktor.jar"'   # = el jar anotado
   ```

**NO-GO si:** se pasa algún umbral, `redeploy-rolling.sh` no se entiende, falta una variable obligatoria o el jar no coincide.

### F3 · Backup fresco y prueba de restauración (requisito R2; P, **GO-3**)

1. **Plan y backups de Supabase** (`RO`, panel): en Database > Backups, anotar si hay **PITR** y la fecha del último backup diario. Pro trae backups diarios con 7 días de retención. PITR es un complemento de pago (D-3).
2. **Volcado lógico justo antes de la ventana** (`RO` sobre MAIN). Es el método ya probado el 2026-10-08 (`BACKUP_EVIDENCE.txt`: CLI 2.120.0, restauración verificada):
   ```bash
   REPO=$(pwd); BK=~/.oposibots-gate/backups/main-ndiooyyqtaeysnedywer-$(date -u +%Y%m%dT%H%M%SZ); mkdir -p "$BK" && cd "$BK"
   npx supabase@2.120.0 login            # sesion del propietario
   npx supabase@2.120.0 link --project-ref ndiooyyqtaeysnedywer
   npx supabase@2.120.0 db dump --linked --role-only -f roles.sql
   npx supabase@2.120.0 db dump --linked -f schema.sql
   npx supabase@2.120.0 db dump --linked --data-only --use-copy -f data.sql
   # Lo que el CLI no vuelca (historial de migraciones, triggers de auth.users, politicas de storage.objects), con la imagen PG17 local:
   dk() { MSYS_NO_PATHCONV=1 docker run --rm -i -e MAIN_DB_URL -v "$BK:/bk" public.ecr.aws/supabase/postgres:17.6.1.171 "$@"; }
   dk sh -c 'pg_dump "$MAIN_DB_URL" -Fc --schema=supabase_migrations -f /bk/migrations_history.dump'
   dk sh -c 'psql "$MAIN_DB_URL" -X -At -f -' < "$REPO"/docs/engineering/v1-deploy/03_backup_auth_triggers_readonly.sql > "$BK/auth_triggers.sql"
   dk sh -c 'psql "$MAIN_DB_URL" -X -A -F "|" -f -' < "$REPO"/docs/engineering/v1-deploy/04_backup_storage_policies_readonly.sql > "$BK/storage_policies.txt"
   sha256sum * > SHA256SUMS; ls -l
   ```
   El fichero `storage_policies.txt` sirve para comprobar y reconstruir a mano. Es el respaldo del ROLLBACK de #38, que ya guarda su propia copia. Los **bytes** de Storage no están en un volcado de base de datos. Las migraciones no los tocan.
3. **Prueba de restauración** (D-3 elige una):
   - **(a) Local, sin coste (recomendada):** stack local de Supabase con el CLI (`npx supabase@2.120.0 start` en un directorio vacío; puertos propios, nunca los stacks compartidos). Se restaura `roles.sql`, después `schema.sql` y después `data.sql` con `psql -v ON_ERROR_STOP=0`, y luego `auth_triggers.sql` (como `supabase_admin`); las políticas de storage se comparan con `storage_policies.txt`. Es el procedimiento del 2026-10-08. Criterios:
     - 0 errores en roles y esquema;
     - recuentos por tabla iguales a los de MAIN: el propietario saca `select relname, n_live_tup from pg_stat_user_tables` en MAIN (`RO`) y lo mismo en la copia;
     - 90 o más tablas públicas.
   - **(b) Proyecto Supabase nuevo y temporal:** desde un backup diario (panel > Backups > restore to new project, si el plan lo ofrece) o restaurando el volcado. Prueba también GoTrue y Storage gestionados. Coste: el cómputo del proyecto nuevo se factura por horas mientras existe; el propietario consulta el precio en Billing antes de crearlo y lo borra el mismo día (D-3).
4. **Ensayo sobre datos reales:** en la copia restaurada (a) o (b) se ejecuta la **ventana completa** con `apply-main-migration.sh` apuntando a la copia (`MAIN_DB_URL` de la copia):
   - las **40** del manifiesto, en su orden, con F4.3b después de 103400;
   - #37, #40, #41 y #42 **solo con `--verify-only`**, que tiene que dar 0 (si D-10 lo aprueba, también `--record-only`);
   - **100100 fuera**.

   Después se ejecutan y después `02_data_preflight_readonly.sql`, el VERIFY de todas y la comprobación de cartera de 5.2: reconcile = 0 filas, saldos sin cambios y número de `legacy_opening` = el aprobado en D-2. Así queda probado lo que el ensayo sintético no cubre.
5. Anotar en el registro: ruta del backup, sha256 y hora UTC del volcado. **Esa hora es el punto de recuperación de R-4.**
6. **Recaptura del catálogo de MAIN (OBLIGATORIA antes de GO-4; `RO`).** La forma ensayada es la captura del 2026-10-14 más los cambios conocidos del 2026-10-22. Esto demuestra que MAIN no ha derivado.
   1. P ejecuta `scripts/db-main-catalog-capture.sql` en el editor SQL de MAIN. Es una transacción de solo lectura: lee `pg_catalog`, sin filas ni secretos. Guarda el resultado como JSON (`[{seccion, sentencias, sql}, …]`) en `~/.oposibots-gate/baseline-<fecha>/capture.json`.
   2. C ensambla y vuelve a ensayar con la captura nueva como punto de partida. Las 4 de seguridad ya van dentro de la captura, así que no se pre-aplican. El stub de storage se recorta igual:
      ```bash
      node scripts/db-main-catalog-assemble.mjs ~/.oposibots-gate/baseline-<fecha>/capture.json > ~/.oposibots-gate/baseline-<fecha>/baseline_main.sql
      MAIN_BASELINE=~/.oposibots-gate/baseline-<fecha>/baseline_main.sql MAIN_BASELINE_SHA=$(sha256sum ~/.oposibots-gate/baseline-<fecha>/baseline_main.sql | cut -c1-64) \
      PREAPPLIED_ON_MAIN="" NEVER_RUN="20261023100000 20261023100100 20261023100300 20261023100400 20261023100500" MANUAL_STORAGE_DROP=1 \
      bash docs/audit-v1/evidence/V1-DEPLOY-REHEARSAL/rehearse-v1-deploy.sh /tmp/v1-rehearsal-recapture
      diff docs/audit-v1/evidence/V1-DEPLOY-REHEARSAL/run-2026-10-22b/fingerprint_start.txt /tmp/v1-rehearsal-recapture/fingerprint_start.txt
      ```
   3. Criterio:
      - el ensayo sobre la recaptura sale `OK`;
      - el `diff` del punto de partida está **vacío** o tiene cada línea explicada y aceptada por escrito (por ejemplo, 20261020/21 si ya estaban aplicadas: en ese caso también van en `NEVER_RUN`).

      Una diferencia sin explicar es **NO-GO**: hay que volver a ensayar sobre la forma nueva. La recaptura también renueva `MAIN_MIGRATION_HISTORY.txt` (versión y nombre, `RO`).

**NO-GO si:** el volcado falla, la restauración no cuadra, el ensayo sobre la copia no da VERIFY = 0 en todas, o la recaptura (F3.6) muestra deriva sin explicar.

### F4 · Ventana de mantenimiento y migraciones (P, `ESCRITURA`, **GO-4**)

Duración estimada: 45-60 min desde que se paran los Ktor hasta el humo. El tope antes de decidir es de **30 min** desde F4.1 (regla de roll-forward con ventana máxima de `DEPLOYMENT.md`). A los 15 min se decide por escrito entre seguir hacia delante o R-2.

0. **Aviso** (si D-6 lo pide): sin app publicada en las tiendas (sección 10), afecta a cuentas de prueba y a los admins del panel. Hora UTC de inicio anotada.
1. **Parar los tres Ktor viejos** (`ESCRITURA`; Caddy responde 502 en toda la API):
   ```bash
   ssh "$OPOSI_SSH_HOST" 'docker ps --format "{{.Names}} {{.Image}}" | tee ~/v1-deploy-ps-antes.txt; cp -a ~/oposibots-stack/env ~/oposibots-stack/env.bak-$(date +%Y%m%d%H%M)'
   ssh "$OPOSI_SSH_HOST" 'docker stop oposibots-ktor-bot-1-1 oposibots-ktor-bot-2-1 oposibots-ktor-control-1'   # nombres de DEPLOYMENT_PLAN 10.1; confirmar con el ps anterior
   curl -s -o /dev/null -w '%{http_code}\n' "$OPOSI_API_URL/health/live"    # 502 (esperado durante la ventana)
   ```
2. **Volcado delta OBLIGATORIO** (`RO`; con los Ktor ya parados no entra ninguna escritura de la app). Se repite F3.2 completo (roles, schema, data y extras) en un directorio nuevo con su `SHA256SUMS`. **Su hora es el punto de recuperación (RPO) de R-4**, en lugar de la de F3.5. Sin este volcado no se aplica nada (NO-GO).
3. **Migraciones, UNA a UNA, en el orden de la sección 4** (`ESCRITURA`; cada una en su transacción):
   ```bash
   bash ops/deploy/v1/apply-main-migration.sh supabase/migrations/20261022100000_slice3_attempt_content_revisions.sql
   # ... repetir con cada fichero de la tabla de la seccion 4, en orden; #43 desde la ruta acordada (D-8)
   ```
   Para cada migración, el script:
   - comprueba, **sin conectar**, que la versión está en el manifiesto, que el blob es el ensayado y que la clase admite el modo;
   - comprueba que la versión no está ya en el historial y que su **predecesora** del manifiesto sí lo está;
   - pide teclear la versión;
   - aplica con `ON_ERROR_STOP`;
   - exige VERIFY = 0;
   - registra la versión en `supabase_migrations.schema_migrations`.

   El propietario anota la hora de cada una.

   Si una migración ya está aplicada (huella física) pero no registrada, **no se re-aplica**. Es el caso de #37, #40, #41 y #42, y quizá de #1 y #2. Si D-10 lo aprueba, se registran sin ejecutarlas:
   ```bash
   bash ops/deploy/v1/apply-main-migration.sh --record-only supabase/migrations/20261023100000_security_content_admin_write_revoke.sql   # exige VERIFY = 0 y teclear la version
   ```
   El script solo acepta `--record-only` para las 4 versiones de la lista `APPLIED_OUTSIDE_HISTORY`. Para #1 y #2, si F1.1 muestra su huella física pero no la versión, se registran a mano con `insert into supabase_migrations.schema_migrations (version, name) values ('<v>', '<nombre>');` después de comprobar su `--verify-only`. #38 (100100) **no se registra ni se ejecuta**: el script se niega.

   Ante un fallo:
   - **PREFLIGHT** (`*_PREFLIGHT`): la migración no cambió nada. No se sigue con las siguientes que dependan de ella. Se decide R-1 o R-2.
   - **`lock_timeout`**: se reintenta una vez pasado 1 minuto. Si vuelve a fallar, NO-GO.
   - #37, #38, #40, #41 y #42 no se ejecutan en esta ventana: el script se niega a aplicarlos.
3b. **Relleno de la penalización racional en los exámenes OFICIALES existentes** (D-11, **DECIDIDA** por el propietario el 2026-10-22).
   - **Cuándo:** justo después de #36a (`20261022103400`) y antes de #36b. La columna `penalizacion_racional` la crea #17 (`20261022101300`), que en MAIN aún no existe, así que solo puede ir dentro de la ventana.
   - **Qué hace:** aplica el UPDATE comentado en la cabecera de 103400 (`feature/v1-exam-penalty-scope` `74554ccd`).
   - **Efectos aceptados:** los intentos ya congelados no cambian; solo se penalizan los intentos nuevos. Las notas de antes y de después **no son comparables** (aviso de Arturo, aceptado por el propietario).

   Pasos:
   1. **Recuento** (`RO`, editor SQL): `docs/engineering/v1-deploy/05_penalty_backfill_count_readonly.sql`. Anotar `oficiales`, `oficiales_ya_con_racional` y `candidatos` (= N).
   2. **UPDATE** (`ESCRITURA`, GO-4b): `docs/engineering/v1-deploy/06_penalty_backfill_apply.sql`, después de sustituir `<ADMIN_UUID>` (actor de auditoría, el admin del propietario) y `<N>`. Todo va en una transacción:
      - si se ejecuta sin sustituir los marcadores, falla sin cambiar nada;
      - si `<ADMIN_UUID>` no es un admin o superadmin con la cuenta **activa**, se detiene (`PENALTY_BACKFILL_ACTOR_NOT_ACTIVE_ADMIN`) y no cambia nada;
      - si el número de filas actualizadas no es exactamente N, se deshace todo (`PENALTY_BACKFILL_COUNT_MISMATCH`);
      - los exámenes publicados se re-validan al COMMIT con `trg_examenes_official_rules`.
   3. **Post-check** (`RO`): `07_penalty_backfill_postcheck_readonly.sql`. Esperado:
      - `candidatos_restantes = 0`;
      - `oficiales_con_racional` = `oficiales_ya_con_racional` + N;
      - `auditoria_reglas_backfill = N` (una fila de `auditoria_log` por examen, con el actor y el motivo).

   **Ensayado el 2026-10-22** (`docs/audit-v1/evidence/V1-DEPLOY-REHEARSAL/run-2026-10-22/penalty-backfill.log`). Montaje: dos exámenes oficiales heredados (uno publicado con 3 preguntas y un borrador) y las 37 migraciones del orden real hasta 103400. Resultados:
   - recuento: 2 candidatos;
   - sin marcadores: error y nada cambia;
   - con N = 3: `PENALTY_BACKFILL_COUNT_MISMATCH` y nada cambia;
   - con N = 2: 2 actualizados;
   - post-check: 0 restantes, 2 con racional y 2 filas de auditoría con el actor y el motivo;
   - una segunda ejecución con N = 0 es un no-op.

   **Deshacer** (fichero propio, `ESCRITURA` con GO): `docs/engineering/v1-deploy/08_penalty_backfill_undo.sql`.
   - Vuelve a `false` solo los exámenes que cambió el relleno: los identifica `auditoria_log` por su motivo.
   - Exige un actor activo y exactamente N filas; si no, se deshace todo.
   - En R-2 no hace falta, porque el ROLLBACK de 101300 borra la columna.

   Ensayado en la ventana completa (5.6): actor no admin rechazado, relleno, deshacer y rehacer.
4. **Comprobación tras migrar** (`RO`): se repiten F1.1 (deben salir las nuevas versiones) y F1.2 (valores DESPUÉS de 5.3), y todos los VERIFY:
   ```bash
   for f in $(ls supabase/migrations/2026102[0-3]*.sql | grep -v 20261023100100); do bash ops/deploy/v1/apply-main-migration.sh --verify-only "$f"; done 2>&1 | tee verify-despues.txt
   grep -c 'VERIFY = 0' verify-despues.txt     # = todas las de la seccion 4 salvo #38 (las ya aplicadas incluidas)
   ```
   Más el estado de la cartera (`RO`, editor SQL):
   ```sql
   select (select count(*) from public.app_wallet_reconcile(null)) as reconcile_debe_ser_0,
          (select count(*) from public.transacciones_cartera where referencia_tipo = 'legacy_opening') as aperturas_debe_ser_D2;
   ```

**NO-GO si:** alguna VERIFY ≠ 0, `storage_escritura_admin` ≠ 0, el reconcile no da 0 o las aperturas no coinciden con lo aprobado en D-2. En ese caso, sección 7.

### F5 · Entorno e imagen V1 (P, `ESCRITURA`, **GO-5**)

1. **Entorno:** el propietario edita `~/oposibots-stack/env/*.env` según F2.3: añadir las obligatorias y los valores de la sección 9. Con la copia de F4.1 hecha.
2. **Comprobación de configuración** antes de arrancar (`RO`; imprime solo nombres o valores de interruptores, nunca secretos):
   ```bash
   ssh "$OPOSI_SSH_HOST" 'cd ~/oposibots-stack/env && grep -hE "^(APP_ENV|AUTH_PKCE_MODE|AUTH_RATE_LIMIT_MODE|ADMIN_RATE_LIMIT_MODE|PAYMENTS_STORE_LIVE_CREDIT_ENABLED|PAYMENTS_STORE_SUBSCRIPTIONS_ENABLED|PAYMENTS_STORE_GOOGLE_PLAY_ENABLED|PAYMENTS_STORE_APP_STORE_ENABLED|PAYMENTS_STORE_SANDBOX|PAYMENTS_REFUNDS_ENABLED)=" backend.common.env backend.bot.env backend.control.env; grep -l "^PAYMENTS_SANDBOX_SECRET=" *.env && echo "ERROR: PAYMENTS_SANDBOX_SECRET presente" || echo "ok: sin PAYMENTS_SANDBOX_SECRET"'
   ```
   Esperado: lo de la sección 9, exactamente.
3. **Arranque de la imagen V1** (rodante, con health):
   ```bash
   ssh "$OPOSI_SSH_HOST" 'bash ~/redeploy-rolling.sh oposicontrol-backend:<sha12>'
   ```
   Si F2.2 dijo que el script no arranca servicios parados, se hace a mano por servicio, uno cada vez, esperando `healthy`. Primero se pone `BACKEND_IMAGE=oposicontrol-backend:<sha12>` en `compose.env` **y `CONTROL_IMAGE=` vacío** (o `CONTROL_IMAGE=oposicontrol-backend:<sha12>`): desde el hotfix de control, `compose.production.yaml` usa `ktor-control: image: ${CONTROL_IMAGE:-${BACKEND_IMAGE}}`, y con `CONTROL_IMAGE` aún en `d922357e84d3` control NO pasaría a V1 (y `redeploy-rolling.sh` tampoco lo cambiaría). Comprobación: `docker inspect -f "{{.Config.Image}}" oposibots-ktor-control-1` = la imagen V1, y después:
   ```bash
   C='docker compose -p oposibots --env-file ~/oposibots-stack/env/compose.env -f <compose.production.yaml del servidor> -f <compose.hostcaddy.override.yaml>'   # rutas exactas: las de redeploy-rolling.sh (F2.2)
   $C up -d --no-deps ktor-control && $C ps ktor-control
   $C up -d --no-deps ktor-bot-1 && $C ps ktor-bot-1
   $C up -d --no-deps ktor-bot-2 && $C ps ktor-bot-2
   ```
   `SENTRY_RELEASE=<sha12>` va en `backend.common.env`; el script lo fija.
4. **Versión real** (`RO`):
   ```bash
   ssh "$OPOSI_SSH_HOST" 'for c in $(docker ps --format "{{.Names}}" | grep -E "ktor"); do docker inspect $c --format "{{.Name}} {{.Config.Image}} {{index .Config.Labels \"org.opencontainers.image.revision\"}} {{.State.Health.Status}}"; docker exec $c sha256sum /app/lib/ktor.jar; done'
   ```
   Esperado: los tres con `<sha12>`, la revisión completa, `healthy` y el jar anotado en F2.4.

**NO-GO si:** algún contenedor no queda sano, sale con código 78 (configuración de pagos o sandbox) o el jar es distinto. En ese caso, sección 7.

### F6 · Humo con la API todavía en ventana (P, con apoyo de C)

1. **Salud y borde:**
   ```bash
   bash ops/deploy/release.sh verify     # /health/live 200, /health/ready 200, /metrics 404 en el borde
   for p in /metrics /admin/backend/restart /no-existe /api/v1/webhooks/store/google /api/v1/webhooks/store/apple; do printf '%s ' "$p"; curl -s -o /dev/null -w '%{http_code}\n' "$OPOSI_API_URL$p"; done   # todo 404
   ```
2. **Métricas internas** (red interna, con token; `RO`):
   ```bash
   ssh "$OPOSI_SSH_HOST" 'docker exec oposibots-prometheus-1 sh -c "wget -qO- --header=\"Authorization: Bearer \$(cat /etc/prometheus/secrets/metrics_token)\" http://ktor-bot-1:8080/metrics" | grep -E "^store_payments_enabled|^upstream_response_wire_bytes_total" | head'
   ```
   `store_payments_enabled` debe ser 0 en las dos réplicas bot. El nombre del servicio puede variar: se toma de `docker ps`.
3. **Recorrido de examen con cuenta sintética** (P, con la app de desarrollo apuntando a producción o con `curl`). Cuenta de pruebas propia que no pertenezca a una persona (D-7):
   - registro, correo real de confirmación (sección 8, G-1) y login;
   - fijar especialidad una vez (la segunda vez, 409 `SPECIALTY_ALREADY_SET`);
   - catálogo, iniciar examen, responder, finalizar y revisar;
   - cartera (`/api/v1/app/wallet`) con saldo y movimientos coherentes;
   - logout.

   Ningún 5xx.
4. **IDOR (puntual).** Con dos cuentas sintéticas A y B, B pide recursos de A y debe recibir 404/403 y nunca datos:
   - intento `GET /api/v1/app/intentos/{idA}` y sus subrutas;
   - pedido;
   - guardados;
   - canje;
   - perfil.

   Y sin token, un recurso de usuario debe dar 401. Script de referencia en local: `ops/local-exam/e2-v1-night01-idor.mjs` (está pensado para la pila local; no se lanza contra producción sin revisar sus objetivos con C). RB-13 sigue PARCIAL hasta el barrido completo (D-9).
5. **Panel de Control (admin):**
   - login de un admin activo;
   - abrir listados;
   - una mutación inocua auditada, por ejemplo editar y deshacer un borrador;
   - comprobar el 429 de `ADMIN_MUTATION_RATE_LIMIT_PER_MINUTE` **no** en producción (ya está probado en local);
   - `/health` no contiene `restartEnabled`.
6. **Pagos apagados** (requisito R5): F5.2 más F6.2. En SQL (`RO`):
   ```sql
   select (select count(*) from public.compras_usuario where entorno = 'produccion' and creado_en > '<hora F5.3>') as compras_prod_nuevas_debe_0,
          (select count(*) from public.tienda_bandeja) as bandeja_tienda_debe_0;
   ```
7. **GoTrue y Storage gestionados:** las comprobaciones G-1 … G-6 y S-1 … S-4 de la sección 8.

**GO-6 = PUNTO DE NO RETORNO.** Si todo está en verde, el propietario abre el tráfico. En realidad la API ya responde: la "apertura" consiste en avisar a los testers y admins, y empezar a aceptar escrituras de usuarios reales. Desde ese momento rige la sección 7, R-3.

**NO-GO si:**
- hay un 5xx en el recorrido, una fuga en el IDOR o una G o una S en rojo;
- `store_payments_enabled` ≠ 0;
- el admin no puede entrar;
- **no se cumple el criterio de carga de F0.5**: CERT-LOAD-1000 superado tras cerrar sus P0 (control de admisión, `max_fails` de Caddy, cerrojo del reto diario, arranques simultáneos) **o** aceptación escrita del propietario (D-13). Se comprueba otra vez aquí, porque GO-6 abre el tráfico.

En esos casos se va a la sección 7, R-2 (todavía sin datos de usuarios).

### F7 · Observación (P, con C leyendo las capturas que P le pase)

1. **Primeras 2 horas, cada 15 min:**
   - Grafana: 5xx por réplica, p95/p99, heap y GC;
   - `up{job="ktor"} = 3`;
   - en logs: `rejected by upstream`, `503`, `PGRST`, `23514`, `OC409`, `42501`.
2. **24 horas:** alertas de `ops/monitoring/prometheus/alert_rules.yml`:
   - `KtorInstanceDown`, `KtorHigh5xxRate`, `KtorHighLatencyP95/P99`, `KtorJvmHeapPressure`;
   - **`SupabaseEgressHigh` / `SupabaseEgressDailyBudgetExceeded`**: el egress cuesta dinero; la línea base es de 150 MB/día;
   - `SupabaseUnreachable`.
   **Requisito:** Alertmanager con un receptor que llegue a una persona (`alertmanager.yml.example`, `owner-email`). El inventario del 2026-10-09 decía que "no hay receptor configurado" (D-4). Hasta que lo haya, la observación es manual y no se declara "estable".
3. **72 horas:** sin alertas críticas. Se declara estable y C actualiza `docs/brain/CURRENT_STATE.md` y `RELEASE_BLOCKERS.md` con el SHA, la hora y el resultado.
4. **Sin llamadas periódicas nuevas a Supabase** en esta versión: el trabajo de reto diario corre cada 900 s bajo un cerrojo consultivo (`CHALLENGE_JOB_SECONDS`) y el de tienda está apagado. Comprobar en `upstream_response_wire_bytes_total` que el egress diario no supera la línea base anterior más un margen razonable. Si lo supera, subir `CHALLENGE_JOB_SECONDS` (decisión del propietario).

## 7. Rollback por fases y punto de no retorno (requisito R4)

| Fase | Estado | Vuelta atrás | Pérdida |
|---|---|---|---|
| **R-0** | Antes de F4.1 (todo de solo lectura, imagen cargada sin arrancar) | Nada que deshacer. Si se quiere, `docker image rm oposicontrol-backend:<sha12>` en el servidor. | Ninguna |
| **R-1** | F4.1 hecho y migraciones E/H aplicadas hasta antes de #6 (`20261022100300`) | Bloques ROLLBACK de las aplicadas, en orden inverso (ver el procedimiento debajo), y después arrancar los Ktor viejos: `docker start oposibots-ktor-bot-1-1 oposibots-ktor-bot-2-1 oposibots-ktor-control-1` (imagen `cfa2e14afc8e`). También vale arrancarlos sin deshacer, porque #1-#5 son compatibles con la imagen vieja. | Ninguna (sin tráfico) |
| **R-2** | Migraciones desde #6 aplicadas, o imagen V1 arrancada, y **sin escrituras de usuarios reales** (antes de GO-6) | 1) Parar la imagen V1. 2) ROLLBACK de las migraciones aplicadas en orden inverso, **más `R2_post_rollback_restore_main_acl.sql` justo después del de #16**: obligatorio sin `0fe19e65` y red de seguridad con ella (hallazgo de debajo). No se deshacen #37, #40, #41 ni #42 (ya estaban en MAIN antes de la ventana) ni RB-16. Las 41 están ensayadas (5.2) y así devuelven el catálogo exacto de MAIN. Las LEGACY #1-#2 se quedan (son compatibles con la imagen vieja). 3) Volver a las imágenes de ANTES de la ventana, que ya no son una sola: bot-1 y bot-2 a `cfa2e14afc8e` y **control a `d922357e84d3`** (hotfix de etiquetas, 2026-10-22), NO a `cfa2e14`. En `compose.env`: `BACKEND_IMAGE=oposicontrol-backend:cfa2e14afc8e` y `CONTROL_IMAGE=oposicontrol-backend:d922357e84d3`; arranque por servicio como en F5.3 y `docker inspect` de los tres contenedores contra lo recapturado en F0. 4) `release.sh verify`. Las filas `legacy_opening` de #6 **quedan** (son filas normales del libro; documentado en la migración). Saldos sin cambios (ensayado). | Solo lo escrito por las cuentas sintéticas de F6 |
| **R-3 · PUNTO DE NO RETORNO** | GO-6 dado: usuarios reales escriben en tablas V1 (pedidos, libro con `seq`, canjes, retos, foro, guardados) | **Solo hacia delante:** corrección en una migración NUEVA y/o una imagen nueva, con este mismo plan reducido (F2.4-F6). No se rearranca la imagen vieja: rompería las escrituras de cartera (C de #6) y "mis exámenes" (#9/#13). Los ROLLBACK de V1 borran tablas nuevas, así que hay pérdida de datos. | — |
| **R-4 · catástrofe** | Corrupción o pérdida de datos después de R-3 | **SIN ENSAYAR. Depende de PITR o de la decisión D-12.** Restaurar el MAIN *gestionado* (datos, `auth.*` con usuarios y sesiones, `storage.*` y sus bytes, `supabase_migrations`) no está ensayado. El 2026-10-08 solo se restauró en un stack LOCAL. Opciones: (1) PITR, si D-3 lo contrata: restauración de Supabase a un instante, la única que cubre auth y storage de forma nativa; (2) restaurar un backup diario de Supabase (panel > Backups), con la pérdida desde ese backup; (3) restaurar el volcado de F4.2 en MAIN o en un proyecto nuevo: procedimiento manual sin ensayar, y los bytes de Storage no van en el volcado. Decide solo el propietario. | Desde el volcado de F4.2 (o el instante de PITR) |

Procedimiento de ROLLBACK de una migración (P, `ESCRITURA`; C extrae el bloque, el propietario lo revisa y lo ejecuta):

```bash
f=supabase/migrations/<version>_<nombre>.sql
awk '/^-- ROLLBACK-END/{exit} s{sub(/^-- ?/,""); print} /^-- ROLLBACK-BEGIN/{s=1}' "$f" > /tmp/rollback-<version>.sql   # revisar el fichero
MSYS_NO_PATHCONV=1 docker run --rm -i -e MAIN_DB_URL public.ecr.aws/supabase/postgres:17.6.1.171 sh -c 'exec psql "$MAIN_DB_URL" -X -v ON_ERROR_STOP=1' < /tmp/rollback-<version>.sql
# despues, borrar su version del historial (en el editor SQL):  delete from supabase_migrations.schema_migrations where version = '<version>';
```

Casos concretos:

- **HALLAZGO DEL ENSAYO DEL 2026-10-21.** Ya está corregido en la rama `fix/v1-rollback-blocks-main-acl` `0fe19e65`.
  - **Con esa corrección en el SHA desplegado**, `R2_post_rollback_restore_main_acl.sql` pasa a ser una **red de seguridad**: es idempotente, se puede ejecutar igual y no hace daño. Probado en el re-ensayo del 2026-10-22 (5.5): con `0fe19e65`, el ROLLBACK solo ya deja el catálogo idéntico a MAIN.
  - **Sin la corrección**, sigue siendo **obligatorio** en R-2.
  - Detalle original: los bloques ROLLBACK de `20261022101900`, `20261022101600` y `20261022101200` **no devuelven la forma de MAIN**. Se escribieron contra los stubs del gate, no contra la captura real. Ver `docs/audit-v1/evidence/V1-DEPLOY-REHEARSAL/rollback_diff.txt`.
  - El más grave es el de **101900**: hace `grant all` a `anon` y `authenticated` en las 4 tablas del foro. `anon` recibiría TRUNCATE, que ignora RLS. **P1**.
  - Por eso, justo después del ROLLBACK de `20261022101200` (el último de los tres en orden inverso), se ejecuta `docs/engineering/v1-deploy/R2_post_rollback_restore_main_acl.sql`: sentencias verbatim de la captura de MAIN. Ensayado: ROLLBACK más este fichero deja el catálogo **idéntico** al de MAIN.
  - La corrección definitiva de los bloques es de Bot (propietario de esas migraciones). También hay que regenerar `main_shape_subset_extra_engagement.sql` desde la captura, para que el gate lo detecte.
- **#37, #40, #41 y #42** (aplicadas el 2026-10-22, antes de esta ventana): **R-2 no las deshace**. No dependen del Ktor y la imagen vieja las tolera (`service_role` conserva I/U/D en todas las tablas). Deshacerlas sería un expediente aparte, con GO, usando sus bloques ROLLBACK y el checkpoint de las 07:13 UTC.
- **RB-16 (#38, hecho a mano):** no tiene ROLLBACK de migración ni tabla de copia. Las definiciones están en el checkpoint de las 07:13 UTC. Recrearlas reabriría el agujero: no forma parte de ningún R-n.
- **#6:** su ROLLBACK devuelve las escrituras de `service_role` sobre el libro y la cartera. Ensayado: catálogo idéntico.

## 8. Lo que NUNCA se ha probado contra GoTrue y Storage gestionados (requisito R3)

Todo el E2E (`ops/local-exam`, `oposi-control-e2e`, `ControlJourneysE2ETest`, `ControlPanelUiE2ETest`) usa PG17, un **stub de GoTrue** (JWT ES256 con JWKS local) y Storage API v1.79 en contenedor. No se ha ejercitado contra el GoTrue ni el Storage de MAIN. Comprobaciones tras el despliegue (F6.7), con cuentas sintéticas propias:

| ID | Nunca probado contra la plataforma gestionada | Comprobación exacta en producción | Esperado |
|---|---|---|---|
| G-1 | Correo real de confirmación (plantilla "Confirm signup" con `token_hash` y `{{ .Token }}`, `DEPLOYMENT_PLAN.md` 10.3), SMTP y límites de envío | Registro desde la app de desarrollo con una cuenta nueva; abrir el enlace en Gmail Android y en Mail de iOS | Llega en < 5 min; el enlace `oposibots://` abre la app; cuenta confirmada |
| G-2 | PKCE obligatorio (`AUTH_PKCE_MODE=required`) y vínculo del código con la ÚLTIMA petición (FA39, #25/#28) contra el `/verify` real | Pedir dos correos seguidos y usar el código del primero; después el del segundo | Primero 400/401 sin sesión; segundo OK |
| G-3 | Refresh y logout con los tokens reales (duración del JWT del proyecto: UNKNOWN) | Login, refresh y logout; refresh con el token revocado | 200, 200, 204; el último da 401 |
| G-4 | Triggers de la migración #21 sobre el `auth.users` REAL (`trg_auth_user_email_verified`) y el `trg_auth_user_created` de MAIN: GoTrue escribe `email_confirmed_at`, que el ensayo solo emula | En SQL (`RO`): `select tgname, tgenabled from pg_trigger where tgrelid = 'auth.users'::regclass and not tgisinternal;` y, tras G-1, la invitación de la cuenta (si usó un código) pasa a verificada | Los dos triggers presentes y activos; sin errores en los logs de Auth (panel > Logs > Auth) |
| G-5 | Verificación del JWT del Ktor (`iss`, `aud`, ES256, JWKS de MAIN) con tokens de GoTrue real | Llamadas autenticadas de F6.3; una con un token de otro proyecto o caducado | 200 / 401 |
| G-6 | Solicitud de acceso al panel con código OTP (#1, "Magic Link" con `{{ .Token }}`) | Una solicitud nueva de prueba desde el panel | Llega el código; la verificación pasa; sin aprobación, sin sesión |
| S-1 | Políticas de `storage.objects` tras RB-16 (borradas a mano el 2026-10-22) con un JWT de admin real contra la Storage API gestionada | Con el JWT de un admin de prueba: `curl -X POST "$SUPABASE_URL/storage/v1/object/news-images/prueba-rb16.txt" -H "Authorization: Bearer <JWT admin>" -H "apikey: <anon key>" --data 'x'` | 403/400. Si da 200: el riesgo vuelve a estar abierto; se borra el objeto y es NO-GO |
| S-2 | Subida y lectura por Ktor (`service_role`) de portadas, recursos inmersivos y noticias en los buckets reales | Desde el panel: subir una portada de examen de prueba y un recurso; abrir con «Abrir» (URL firmada) | 200; la URL firmada abre; MIME correcto |
| S-3 | Lectura pública o firmada desde la app de los recursos de examen | Recorrido de F6.3 con un examen que tenga recurso | Se ve; sin 403 |
| S-4 | Imágenes del foro (#22: tabla y ruta dedicadas, recodificadas por Ktor) en el Storage real | Publicar un post con imagen desde una cuenta de prueba; retirarlo desde moderación | La imagen se sirve firmada; tras retirarla, URL caducada |

Además, nunca se ha probado contra el servicio gestionado:

- **PostgREST de MAIN** tras las 40 migraciones de la ventana. Su caché de esquema la recarga Supabase sola tras DDL. Si F6 da `PGRST202` en una RPC nueva: `notify pgrst, 'reload schema';` en el editor SQL.
- **Realtime.** No lo usa V1. Las políticas de soporte de Realtime están muertas (`MAIN-readonly-2026-10-20.md`).
- **Edge Functions.** Hay 0 (F1.2).

## 9. Pagos en esta versión (requisito R5)

Decisión que tiene que confirmar el propietario (D-1): **V1 sin compra con dinero real en este despliegue.** Valores exactos:

| Fichero | Variable | Valor | Efecto |
|---|---|---|---|
| `backend.bot.env` | `PAYMENTS_STORE_LIVE_CREDIT_ENABLED` (G1) | `false` | Nada de producción se acredita |
| `backend.bot.env` | `PAYMENTS_STORE_SUBSCRIPTIONS_ENABLED` (D8) | `false` | No se venden ni se aplican suscripciones de tienda |
| `backend.bot.env` | `PAYMENTS_STORE_GOOGLE_PLAY_ENABLED` / `PAYMENTS_STORE_APP_STORE_ENABLED` | `false` / `false` | Sin crédito de tienda: no se verifican compras; `store_payments_enabled = 0` |
| `backend.bot.env` | `PAYMENTS_STORE_SANDBOX` | `reject` | Una compra sandbox nunca acredita. `allowlist` solo cuando exista el cliente candidato y una lista de testers (D-1) |
| `backend.bot.env` | `PAYMENTS_REFUNDS_ENABLED` | `false` | El reembolso se guarda retenido (RB-06) |
| `backend.bot.env` | `PAYMENTS_STORE_CREDIT_POLICY` / `PAYMENTS_APPLE_CONSUMPTION_RESPONSES` | vacío (`sku`) / vacío (`false`) | Defectos conservadores |
| ninguno | `PAYMENTS_SANDBOX_SECRET` | **ausente** | Con valor y `APP_ENV=production`, el proceso sale con código 78 |
| `backend.common.env` | `APP_ENV` | `production` | — |

Se verifica:

- en la configuración (F5.2);
- en tiempo de ejecución, con la métrica `store_payments_enabled` = 0 (F6.2);
- en datos, con 0 compras de producción nuevas y la bandeja vacía (F6.6);
- con los webhooks de tienda en 404 en el borde: Caddy no los enruta y no se cambia en este despliegue (F6.1).

El panel de ingresos de Control excluye el sandbox (#39).

## 10. Ventana e impacto en usuarios

- **Versión publicada de la app:** en los repositorios, `androidApp/build.gradle.kts` tiene `versionCode = 1` / `versionName = "1.0"` y el objetivo es publicar en las tiendas en diciembre. No consta ninguna versión publicada en Play ni en App Store (D-6: el propietario confirma si hay builds distribuidos por pruebas internas o TestFlight). Usuarios reales en MAIN: 4 perfiles el 2026-10-08.
- **Cambios de API que rompen a un cliente anterior** (diff de `openapi/` entre `origin/main`, lo desplegado, y `develop`, más las migraciones):
  - el cambio de especialidad pasa a ser una sola vez (409 `SPECIALTY_ALREADY_SET`);
  - el login y el registro por enlace de correo exigen PKCE (`AUTH_PKCE_MODE=required`): un cliente sin verificador recibe 400;
  - "mis exámenes" y el acceso pasan al modelo V1 (plan, cupo, demo);
  - los esquemas de revisión y recursos del intento han cambiado;
  - el panel exige comunidad al crear y editar un examen.

  Un cliente de desarrollo anterior a V1 **deja de funcionar en auth**. No hay versión publicada que proteger (por confirmar en D-6).
- **Ventana:** API caída (502) desde F4.1 hasta F5.3, estimada en 30-45 min. Recomendado un horario de poco uso (D-6).

## 11. Registro de la ventana (lo rellena el propietario)

```
SHA desplegado:            jar sha256:               imagen: oposicontrol-backend:
F1 salidas guardadas en:   F3 backup (ruta, sha, hora UTC = punto de recuperacion):
GO-1   GO-1b   GO-3   GO-4 (hora parada Ktor)   GO-5   GO-6 (PNR, hora)
Migraciones: #n  hora  resultado (OK / PREFLIGHT / omitida)   ...
#37/#40/#41/#42: ya en MAIN (2026-10-22), registradas en el historial si/no (D-10)   #38: nunca (RB-16 a mano)
Incidencias:
```

## 12. Decisiones y aprobaciones del propietario

| ID | Decisión | Recomendación |
|---|---|---|
| D-1 | Pagos en este despliegue (sección 9) y RB-07 "V1 sin compra con dinero" | Todo apagado, `PAYMENTS_STORE_SANDBOX=reject` |
| D-2 | Aprobar los recuentos de F1.3: carteras que reciben `legacy_opening`, OT totales de apertura, admins/moderadores no activos | Aprobar si coinciden con la contabilidad conocida (4 carteras el 2026-10-08) |
| D-3 | PITR (complemento de pago) y método de la prueba de restauración: (a) local sin coste o (b) proyecto temporal facturado por horas | (a) local + ensayo de F3.4; PITR solo si el propietario quiere bajar R-4 de "desde el volcado" a "minutos" |
| D-4 | Receptor de Alertmanager (correo) antes de declarar estable | Configurarlo antes de GO-6 |
| D-5 | ~~RB-16~~ **RESUELTA el 2026-10-22**: borrado a mano por el propietario. Queda la comprobación `storage_escritura_admin = 0` en F1.2 y F4.4 | — |
| D-6 | Hora de la ventana, aviso a testers y admins, y confirmar que no hay builds de la app distribuidos | Hora de poco uso; aviso a los admins del panel |
| D-7 | Cuentas sintéticas de producción para el humo y el IDOR (A, B y un admin de prueba), y cómo se borran después | Correos propios del propietario con `+alias`; se borran por Ktor tras F7 |
| D-8 | Qué ramas sin fusionar entran en el SHA desplegado: `feature/v1-plans-store-grants-revoke` `4b4acf81` (100600), `feature/v1-exam-penalty-scope` `74554ccd` (103400), `fix/v1-daily-challenge-dst` `6904357c` (103500, reto sin cerrojo global), `fix/v1-rollback-blocks-main-acl` `0fe19e65`, **`fix/v1-overload-admission-control` `261df4b2`** (admisión + Caddy, P0 de carga) y **`fix/v1-gate-races-deterministic`**. Para esta última, el coordinador indicó `d8d5cda5`, pero la punta de la rama es hoy `ded1e02e` (sobre `c2a59391`, mismos mensajes) y `d8d5cda5` ya no está en ninguna rama: hay que confirmar cuál entra | Fusionarlas en develop antes de F0; regenerar el manifiesto con los blobs del SHA; re-ensayar (5.5, 5.6) y pasar el gate |
| D-12 | Método de R-4 (restaurar MAIN gestionado) | Contratar PITR (D-3) o aceptar por escrito R-4 = backup diario o volcado de F4.2 sin ensayar, con la pérdida correspondiente |
| D-13 | Criterio de carga (F0.5): repetir CERT-LOAD-1000 tras cerrar sus P0 (GO propio, §7 del informe) **o** aceptación escrita de no hacerlo | **Propietario 2026-10-22: APLAZADA** ("el 3 es para más adelante"); OposiControl la canceló hasta nuevo aviso. El despliegue sigue por la vía de aceptación escrita, que el propietario confirma en GO-6; mitigación en código: control de admisión (`fix/v1-overload-admission-control`) |
| D-11 | Relleno de la penalización racional en los exámenes oficiales que ya hay en MAIN | **DECIDIDA el 2026-10-22: SÍ.** Paso F4.3b, después de 103400. Las notas de antes y de después no son comparables (aceptado) |
| D-10 | Registrar en `supabase_migrations` las 4 aplicadas como SQL suelto (#37, #40, #41, #42) con `--record-only` | Sí, en F4.3: así el historial es verdad y `release.sh preflight` deja de listarlas. #38 no se registra |
| D-9 | Aceptación escrita de los RB P1 que siguen abiertos para el servidor (RB-06, RB-10, RB-12, RB-13 parcial, RB-16 resuelto) | Cerrar RB-10 (rotar la service_role de MAIN si estuvo en un `.env` de desarrollo) **antes** de F4 |

## 13. Dependencia del release del cliente (BLOQUEANTE de la app, NO del servidor)

- **No existe todavía un candidato único del cliente OposiBot** contra este backend: el wiring y la UI van en ramas separadas, sin runtime verificado en dispositivo físico ni en iOS (RB-12).
- El despliegue del servidor **no** publica nada en las tiendas y **puede hacerse antes**. Al revés no: una app V1 publicada contra el servidor viejo fallaría (PKCE, rutas V1).
- **Requisitos para publicar la app**, cada uno con su evidencia en `RELEASE_BLOCKERS.md`:
  - candidato único del cliente con SHA;
  - E2E en dispositivo contra producción con las comprobaciones G-1 … G-6;
  - App Links / Universal Links (residual B4 de SEC-V1-08);
  - `AUTH_OAUTH_NONCE_REQUIRED=true` cuando todas las versiones publicadas envíen el nonce;
  - y, si se vende algo, G1/D8 con credenciales de tienda, evidencia de sandbox, rutas de webhook en Caddy y PAY-F5.

## 14. Bloqueantes abiertos para ejecutar este plan

1. D-1 … D-4, D-6 … D-10, D-12 y D-13 sin decidir (D-5 y D-11 ya están decididas).
2. Ramas de D-8: penalización (17b99dbe), DST (6904357c), revoke (4b4acf81), rollback (0fe19e65) y gate-races ya están en develop (`a9fbe337`, 2026-10-22); falta la de sobrecarga (`fix/v1-overload-admission-control`, en revisión). La clase de 103400 y 103500 (E?) la confirma Bot. **Antes de GO-4, sobre el SHA FINAL de develop que se despliega** (el de la imagen V1): `bash scripts/db-pg17-gate.sh && bash scripts/db-pg17-gate.test.sh`, el ensayo 5.5 (`rehearse-v1-deploy.sh`) y `window-selftest.sh <árbol de ese SHA> <boot>`, con `ops/deploy/v1/main-migrations.manifest` regenerado desde ese árbol (`git hash-object` de cada migración). Sin los cuatro en verde sobre ESE SHA no hay GO-4.
3. No se sabe si `20261020100000`/`20261021100000` están en MAIN (F1.1).
4. ~~Pertenencia a `supabase_storage_admin`~~: resuelto. `postgres` no pertenece; RB-16 se hizo a mano y 100100 queda fuera.
5. No hay receptor de alertas (D-4).
6. `redeploy-rolling.sh` no está versionado: hay que confirmar cómo se comporta con servicios parados (F2.2).
7. Pendiente de la sección 13 (solo para la app).
8. **Hallazgo del ensayo:** los ROLLBACK de `20261022101200`, `101600` y `101900` no restauraban MAIN (sección 7). Corregido en `fix/v1-rollback-blocks-main-acl` `0fe19e65`, ya en develop (`a9fbe337`). Mientras no esté en el SHA desplegado, `R2_post_rollback_restore_main_acl.sql` es obligatorio en R-2; con la corrección queda como red de seguridad.
9. La forma de MAIN es del 2026-10-14. Si F1.1 o F1.2 muestran deriva, se recaptura el catálogo (`scripts/db-main-catalog-capture.sql`, solo lectura) y se repite el ensayo.

## 15. Auditoría de OposiControl del 2026-10-22 sobre `ffcff773` (NO APTO): qué se corrigió y dónde

| Severidad | Punto | Dónde | Estado |
|---|---|---|---|
| P0 | Criterio de GO de carga (CERT-LOAD-1000 tras sus P0, o aceptación escrita) | F0.5 y NO-GO de GO-6; D-13 | Escrito |
| P0 | Desplegar el cambio de Caddy (sin expulsión pasiva del pool bot) | F2.2b; D-8 (`fix/v1-overload-admission-control`, `fix/v1-gate-races-deterministic`) | Escrito (no ejecutado; necesita GO-2b) |
| P2 | F3.4: 40 migraciones, las 4 aplicadas solo con `--verify-only`, 100100 fuera | F3.4 | Escrito |
| P2 | F0.3 con el comando exacto de 5.5 | F0.3 | Escrito |
| P2 | Volcado delta obligatorio tras F4.1 (RPO) | F4.2; R-4 | Escrito |
| P2 | R-4: procedimiento ensayado o declarado sin ensayar con dependencia de PITR o decisión | R-4; D-12 | Declarado **sin ensayar** |
| P2 | Manifiesto version → blob → predecesora en el script, con autoprueba | `ops/deploy/v1/main-migrations.manifest`; 5.6 | Hecho y probado |
| P2 | Recaptura de MAIN obligatoria antes de GO-4, con diff contra la forma ensayada | F3.6 (`fingerprint_start.txt`) | Escrito; la huella de referencia es de 5.6 |
| P3 | El preflight comprueba TRUNCATE | `01_catalog_preflight_readonly.sql` (columna `trunc` y consulta de todo public/private) | Hecho |
| P3 | Actor del relleno validado como admin activo | `06_penalty_backfill_apply.sql` | Hecho y probado |
| P3 | Deshacer el relleno en un fichero propio | `08_penalty_backfill_undo.sql` | Hecho y probado |
| P3 | Textos desfasados (43 migraciones, base `3e5ea526`) | Cabecera, secciones 2, 5.1 y 8 | Corregido |
