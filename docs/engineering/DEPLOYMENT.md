# Deployment

Estado: `NOT_VERIFIED` (runbook escrito, aun no ejercitado; ningun despliegue realizado desde este repo).
El despliegue lo ejecuta un humano con el gate de produccion; nada de esto corre en CI.

## Orden obligatorio: migrar, luego desplegar

1. Aplicar migraciones de `supabase/migrations/` a la base (con backup/PITR verificado).
2. Desplegar Ktor. Sin las migraciones la pasarela responde 503 (falla cerrado), asi que
   el orden inverso rompe el trafico.
3. Comprobar que `/health` devuelve el `BUILD_SHA` esperado (ver mas abajo).

## Construir

```bash
scripts/build-backend-image.sh   # exige arbol limpio; etiqueta oposicontrol-backend:<sha12>
```

- `ops/deploy/Dockerfile.backend` copia `backend/ktor/build/install/ktor` (resultado de `installDist`);
  `.dockerignore` deja fuera todo lo demas del contexto.
- La base `eclipse-temurin:21-jre-jammy` esta fijada por digest. Para actualizarla:
  `docker buildx imagetools inspect eclipse-temurin:21-jre-jammy`, cambiar el digest y revisar.

## Desplegar

1. Registrar `OLD_SHA`/`OLD_IMAGE` del contenedor en marcha y etiquetar la imagen actual como
   `oposicontrol-backend:rollback-<fecha>`.
2. Ejecutar el contenedor con `SENTRY_RELEASE=<sha12>` (y `BUILD_SHA=<sha12>` cuando el backend
   lo exponga en `/health`), `METRICS_SCRAPE_TOKEN` y el resto de variables de `.env.example`.
3. `/health/ready` debe dar 200; `/metrics` publico debe dar 404 (`ops/caddy/metrics-block.caddy`).

## Rollback

Reejecutar el contenedor con la imagen `rollback-<fecha>`. Las migraciones de Slice 1 son de tipo
expand con bloque de rollback documentado en cada archivo; no se revierten automaticamente.

## Pendiente (registrado, no ejercitado)

- `/health` aun devuelve una cadena `build` fija; inyectar el SHA es un cambio de codigo de Ktor.
- `/health/ready` solo comprueba Supabase; una sonda de marcador de esquema requiere aprobacion
  del owner del contrato (D8-08 congelado).
- CI (`.github/workflows/ci.yml`) verifica tests, lint OpenAPI y `scripts/verify-ops-config.sh`;
  `dbtest` corre sobre PostgreSQL 16 embebido (zonky); la validacion PG17 sigue siendo el gate local Supabase.
