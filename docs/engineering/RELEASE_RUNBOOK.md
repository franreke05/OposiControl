# Runbook de publicacion a produccion (develop a api.oposibots.es)

Estado: `NOT_VERIFIED` (escrito a partir del repo y de `DEPLOYMENT_PLAN.md`; `release.sh` probado solo con repos falsos y stubs, nunca contra el servidor).
Un solo punto de entrada: `ops/deploy/release.sh` (`preflight`, `build`, `ship`, `verify`). Un solo orden: este documento.
Arquitectura (sin cambios): una imagen `oposicontrol-backend:<sha12>`, tres contenedores (`bot-1`, `bot-2` con `SERVER_PROFILE=bot`, y `control`), Caddy en el host, Valkey, Prometheus/Grafana/Alertmanager y Supabase MAIN (`ndiooyyqtaeysnedywer`). Detalle de la ultima preparacion: `DEPLOYMENT_PLAN.md` secciones 8 y 10 (en especial 10.6 y 10.6.8b).

## Partes que son SOLO del propietario (Claude no las hace ni las pide)

- **Llave SSH** cargada en el agente (PowerShell: `Start-Service ssh-agent`, luego `ssh-add` de la llave `oposibots_opsadmin_ed25519`). Claude nunca la lee ni la usa.
- **Supabase**: editor SQL o CLI de MAIN para exportar el historial y aplicar migraciones. Claude no se conecta a Supabase.
- **Ficheros de entorno del servidor** (`~/oposibots-stack/env/*.env`): solo el propietario los lee, copia y edita.
- **Host y URL**: `OPOSI_SSH_HOST` (hoy `opsadmin@49.12.232.217`, documentado en `desplegar-oposicontrol.sh`) y `OPOSI_API_URL` (hoy `https://api.oposibots.es`) se definen en la terminal del propietario; no estan en el repo.
- El **GO** de cada paso marcado y la pulsacion final de `ship --i-have-the-go` con el SHA.

Quien: `P` = propietario, `C` = Claude (solo trabaja en local, sobre el repo).

## Variables de la sesion (Git Bash, propietario)

```bash
export OPOSI_SSH_HOST='opsadmin@49.12.232.217'   # valor actual; revisar si cambia el servidor
export OPOSI_API_URL='https://api.oposibots.es'
cd /c/Users/franr/AndroidStudioProjects/OposiControl && git fetch origin && git merge --ff-only origin/develop
```

## Checklist

### 1. Pre-flight local (P o C)
- Comando: `bash ops/deploy/release.sh preflight` (la lista de aplicadas se anade en el paso 3).
- Esperado: `ok:` por cada comprobacion (rama, arbol limpio, `develop == origin/develop`, sin `.env` rastreado, manifiesto de migraciones, ops/rutas) y `PREFLIGHT OK: oposicontrol-backend:<sha12>`.
- Ademas, gate verde de la rama (CI de `develop` en verde, o local: `bash scripts/release.test.sh`, `bash scripts/verify-ops-config.test.sh`, tests del backend) y anotar el SHA.
- Abortar si: cualquier `ERROR:`. Rollback: nada que revertir (solo lectura).

### 2. Copia de seguridad y punto de vuelta atras (P, por SSH)
- Comandos:
  ```bash
  ssh "$OPOSI_SSH_HOST" 'cp -a ~/oposibots-stack/env ~/oposibots-stack/env.bak-$(date +%Y%m%d%H%M)'
  ssh "$OPOSI_SSH_HOST" 'docker ps --format "{{.Names}} {{.Image}} {{.Status}}"' | tee docker-ps-antes.txt
  ```
- Esperado: `bot-1`, `bot-2`, `control` (y Valkey/monitorizacion) sanos. **Anotar la etiqueta de imagen actual de bot-1/bot-2/control: es el rollback** (referencia historica: `cfa2e14afc8e`, tambien etiquetada `rollback-20261009`).
- Abortar si: algun contenedor no esta sano antes de empezar (no desplegar sobre un sistema ya roto).
- Rollback: restaurar la copia de `env` si el paso 5 la modifica.

### 3. Migraciones pendientes en MAIN (P ejecuta, C calcula)
Regla: **una migracion ya aplicada en MAIN no se edita nunca** (ni comentarios); los cambios van en una migracion nueva hacia delante. Se aplican en orden de nombre (version).
1. P exporta el historial (solo lectura, editor SQL de MAIN) y lo guarda como `main-history.txt`:
   ```sql
   select version from supabase_migrations.schema_migrations order by version;
   ```
   Comprobacion opcional de huella del esquema: `select count(*) from supabase_migrations.schema_migrations;` (a 2026-10-09 eran 72 versiones; debe ser >= ese numero).
2. `bash ops/deploy/release.sh preflight --applied-list main-history.txt`
3. Esperado: bloque `Migraciones PENDIENTES` con los ficheros en orden. Una marcada `<-- ANTERIOR a la ultima aplicada` es una anomalia (hueco o deriva): no aplicarla sin revisarla con Claude.
- Antes de aplicar: SQL de revision de `DEPLOYMENT_PLAN.md` 10.4 si el release toca solicitudes de acceso, backup/PITR de Supabase verificado, y clase de la migracion segun `DEPLOYMENT.md` (EXPAND/CONTRACT; una CONTRACT impide volver a una imagen vieja).
- Aplicar (P): cada fichero pendiente, en orden, con el procedimiento habitual (editor SQL o `supabase db push`). Despues repetir la exportacion y el `preflight`: debe decir `Total pendientes: 0`.
- Abortar si: una migracion falla (no aplicar las siguientes; no reintentar a ciegas). Rollback: solo hacia delante (bloque ROLLBACK documentado de la migracion, si existe, con GO expreso). Las migraciones van ANTES del codigo: sin ellas el codigo nuevo responde 503.

### 4. Construir la imagen (P, Docker Desktop reanudado)
- Comando: `bash ops/deploy/release.sh build`
- Esperado: `ok: imagen oposicontrol-backend:<sha12> con jar <hash>` (construye con `scripts/build-backend-image.sh` y compara el `ktor.jar` local con el de la imagen).
- Abortar si: `ERROR` (arbol sucio, jar distinto). Rollback: ninguno (solo local).

### 5. Entorno del servidor, si el release cambia variables (P)
- Los cambios de `*.env` se comparan con `ops/deploy/backend.*.env.example` y `DEPLOYMENT_PLAN.md` 10.2. Solo el propietario edita los ficheros.
- Abortar si: falta una variable requerida (el contenedor no arrancaria sano). Rollback: restaurar `env.bak-*` del paso 2.

### 6. Dry-run del envio (P o C)
- Comando: `bash ops/deploy/release.sh ship`
- Esperado: imprime el plan (4 pasos) y `DRY-RUN: no se ha ejecutado nada`. No usa ssh ni docker.

### 7. Envio y redespliegue gradual (P, con GO)
- Requisitos: `OPOSI_SSH_HOST` definido, llave en el agente, GO expreso.
- Comando: `bash ops/deploy/release.sh ship --i-have-the-go` y teclear el SHA corto cuando lo pida.
- Esperado: carga la imagen en el servidor y lanza `~/redeploy-rolling.sh <imagen>` (bot-1, sano, bot-2, sano, control). Al final `ok: redespliegue lanzado`.
- Abortar si: SHA no coincide (no toca el servidor), sin acceso SSH, o el script del servidor informa de un contenedor no sano: el propio script vuelve a la imagen anterior; comprobar con `docker ps` y NO continuar al 8b. Rollback: ver seccion final.

### 8. Verificacion de version (P)
```bash
ssh "$OPOSI_SSH_HOST" 'docker inspect bot-1 bot-2 control --format "{{.Name}} {{.Config.Image}} {{index .Config.Labels \"org.opencontainers.image.revision\"}}"'
```
- Esperado: las tres con `oposicontrol-backend:<sha12>` y el commit completo esperado (los nombres exactos de contenedor pueden diferir: ver `docker ps`).

### 8b. Limpieza de solicitudes del flujo anterior (P, **GO EXPRESO**, solo si el release incluye ese cambio)
- Condicion: **todas** las replicas (bot-1, bot-2 y control) ya corren la imagen nueva. `<INSTANTE_DEL_DESPLIEGUE>` = `docker inspect -f '{{.State.StartedAt}}' <bot-1>`.
- SQL y consultas previas: `DEPLOYMENT_PLAN.md` seccion 10.6.8b (consulta 0 y listados primero; la limpieza es escritura y va en una transaccion).
- Esperado: filas afectadas = las del listado previo. Abortar si difieren o si falla alguna FK (la transaccion se deshace). Rollback: no hay vuelta atras de los borrados; por eso el GO y los listados previos.

### 9. Pruebas de humo posteriores (P)
1. `bash ops/deploy/release.sh verify` (con `OPOSI_API_URL`; opcional `OPOSI_PROTECTED_PATH=<ruta protegida>` que debe dar 401). Esperado: `/health/live` 200, `/health/ready` 200, `/metrics` 404 en el borde.
2. Una llamada de administrador autenticada (login en el panel y abrir un listado): 200.
3. Una llamada `/v1/auth` (por ejemplo un login de prueba, un solo intento): respuesta esperada de la API (no 5xx).
4. `DEPLOYMENT_PLAN.md` 10.7 para las pruebas de registro/solicitud, si el release las toca.
- Abortar (y revertir) si: `ready` != 200 de forma sostenida, 5xx en `/v1/auth/*`, o el admin no puede entrar.

### 10. Observacion de 24 horas (P, con apoyo de Claude leyendo capturas que P le pase)
- Grafana/Alertmanager sin alertas; sin picos de 5xx; logs sin `rejected by upstream` ni `held by a non-approved admin access request` inesperados; los tres objetivos de Prometheus `up`. Recordar el incidente de egress de la sonda de readiness (`DEPLOYMENT_PLAN.md` seccion 9).
- Cierre: C actualiza `docs/brain/CURRENT_STATE.md` con SHA, hora y resultado.

## Rollback por etiqueta de imagen anterior

Aplicable mientras ninguna migracion CONTRACT incompatible con la imagen anterior se haya aplicado (ver clase en `DEPLOYMENT.md`; las migraciones nunca se revierten con la imagen).
```bash
ssh "$OPOSI_SSH_HOST" 'docker images oposicontrol-backend --format "{{.Tag}} {{.CreatedAt}}"'      # elegir la etiqueta anotada en el paso 2
ssh "$OPOSI_SSH_HOST" 'bash ~/redeploy-rolling.sh oposicontrol-backend:<etiqueta_anterior>'
bash ops/deploy/release.sh verify
```
Esperado: las tres replicas vuelven a la etiqueta anterior y `verify` en verde. Si el entorno se cambio en el paso 5, restaurar tambien `env.bak-*`. Si hay una migracion CONTRACT aplicada, rollback de imagen NO vale: roll-forward con una correccion (ventana maxima segun `DEPLOYMENT.md`).

## Preguntas abiertas para el propietario

- Ruta exacta de la llamada de administrador y de `/v1/auth` para el humo (y una ruta protegida estable que deba dar 401 para `OPOSI_PROTECTED_PATH`).
- Nombres reales de los contenedores en el servidor y si `~/redeploy-rolling.sh` esta versionado en algun sitio (no esta en este repo; este runbook solo lo invoca).
- Si el historial de MAIN contiene versiones que no existen en `supabase/migrations` (deriva): `preflight` solo lista las del repo ausentes del historial.
- Metodo oficial de aplicar migraciones a MAIN (editor SQL o `supabase db push`) y donde se registra la hora/ventana.
