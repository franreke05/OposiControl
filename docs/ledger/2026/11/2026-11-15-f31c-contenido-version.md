# F31c — Version de contenido para el refresco automatico (FA58, D2-69) (2026-11-15)

Rama local `f31/racha-calendario` (tercer commit). Sin push. MAIN/VPS sin tocar. Peticion del propietario: la app se refresca sola cuando Control publica un examen, un servicio/consumible o una noticia.

## Contrato (FA58, aditivo)

`GET /api/v1/app/contenido/version` (JWT; limite por usuario CATALOG_READ; `Cache-Control: private, no-cache`; parametros de consulta desconocidos = 400).

```json
{"examenes":"3f0c9a1b2d4e5f60","servicios":"9b1d0e7a6c5f4a3b","noticias":"0d1e2f3a4b5c6d7e","paquetes":"77a1b2c3d4e5f607"}
```

- Cada valor es un token OPACO (comparar por igualdad; nunca interpretar). Cambia cuando cambia algo VISIBLE para ese usuario en esa area y no cambia si no:
  - `examenes`: examenes activos publicados (ya, reloj de la base) de la especialidad del usuario (D2-01 / D2-13); una publicacion programada entra cuando llega su hora; cambios de titulo, precio, acceso, demo, portada, etc.
  - `servicios`: servicios del Market activos Y visibles (D6-39).
  - `noticias`: noticias de la app de su especialidad (regla de D4-18: tipo activo, `published` con fecha, o `scheduled` ya vencida).
  - `paquetes`: paquetes de tickets a la venta (D6-11).
- `ETag` fuerte (sha-256 de los cuatro, entre comillas). Con `If-None-Match: <ETag>` -> 304 sin cuerpo mientras nada cambie.
- Uso sugerido en el cliente: guardar el ultimo cuerpo/ETag; consultar al volver a primer plano y en "tirar para refrescar" (no en bucle corto: comparte el limite CATALOG_READ de 120/60 s con las lecturas de catalogo); 200 -> recargar solo las areas cuyo token cambio.
- Errores: 401, 403 (`PROFILE_MISSING`...), 429 `RATE_LIMITED`, 502 si la base devuelve un cuerpo sin los cuatro tokens, 503.

## Migracion `20261115100200_v1_contenido_version` (solo lectura)

- `public.app_contenido_version(uuid)` service_role, SECURITY DEFINER, `search_path = ''`, STABLE. Cuatro agregados (`md5` por fila de los campos visibles, concatenados en orden) sobre conjuntos pequenos; sin tabla, sin trigger, sin indice nuevo (los examenes de una especialidad usan `idx_examenes_especialidad`).
- Las tablas de noticias (`noticia`, `tipo_noticia`) existen en MAIN pero no en la base del gate PG17: la lectura de noticias es dinamica y, sin ellas, el token es el vacio.
- ROLLBACK: borra la funcion. VERIFY: 2 filas.
- Manifiesto: tercera linea de `ops/deploy/v1/main-migrations-2026-11-f31.manifest` (predecesora `20261115100100`).

## Anexo al runbook

1. Tras `20261115100100`: `MANIFEST=ops/deploy/v1/main-migrations-2026-11-f31.manifest bash ops/deploy/v1/apply-main-migration.sh supabase/migrations/20261115100200_v1_contenido_version.sql`.
2. DESPUES la imagen Ktor nueva (unica para F31, F31b y F31c).
3. Rollback: imagen anterior primero; luego el bloque ROLLBACK y borrar la version del historial.

## Verificacion

- dbtest `V1ContenidoVersionMigrationTest`: cada token solo se mueve con lo suyo (examen de su especialidad publicado, otra especialidad y programado no; programado al vencer si; precio; despublicar; servicio visible/oculto; noticia borrador/otra especialidad/futura no, publicada y programada vencida si, tipo inactivo; paquete), usuario desconocido, sin tablas de noticias, VERIFY/ROLLBACK.
- Ktor `ChallengeRoutesTest`: cuerpo, `private, no-cache`, ETag, 304 con `If-None-Match`, 200 con ETag nuevo al cambiar un area, consulta estricta, 401, 502 por cuerpo incompleto.
