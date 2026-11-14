# F29 — Avatares predefinidos (2026-11-14)

Rama local `f29/avatar-preset` desde develop `08ea7f0f`. Sin push. MAIN/VPS sin tocar.

## Decisión del propietario (plan 1.3, sección 8)

El usuario elige uno de 10 avatares predefinidos (la app trae las 10 imágenes; hoy PLACEHOLDER de formas geométricas). La subida de foto queda desactivada: la ruta se mantiene, apagada por configuración.

## Cambio

- Migración `20261114100000_v1_avatar_preset` (enmienda **FA53**):
  - `perfiles_usuario.avatar_id smallint NULL` con CHECK `1..10`.
  - `public.app_set_avatar_preset(uuid, smallint)` (SECURITY DEFINER, solo `service_role`): bloqueo de fila y cuenta activa (`private.slice1_lock_profile`), fuera de rango `INVALID_ARGUMENT`; guarda el predefinido y BORRA la foto (`avatar_url = null`), devuelve `{avatarId, previousPath, changed}` (previousPath solo si era un objeto propio).
  - `app_set_avatar` (foto) borra el predefinido; `app_clear_avatar` borra los dos. Un perfil tiene predefinido O foto.
  - `private.avatar_visible_id(avatar_id, estado_cuenta)`: solo cuenta activa. `app_ranking_me` (top3, además usuario público) y `app_foro_list/post/comments` (autor) devuelven `avatarId` junto a `avatarPath`.
- Ktor:
  - `PUT /api/v1/app/me/avatar` con `Content-Type: application/json` y cuerpo `{"avatarId": N}` → 200 `{"avatarId": N}`. N fuera de 1..10 → 422 `AVATAR_ID_INVALID` (código nuevo en `errors.yaml`); campo desconocido, no entero o cuerpo vacío → 400 `VALIDATION`.
  - Foto en bytes (`image/jpeg|png`): solo con `AVATAR_PHOTO_UPLOADS_ENABLED=true` (por defecto false → 415 `UNSUPPORTED_MEDIA_TYPE` sin leer el cuerpo). Encendida, igual que FA42 y además borra el predefinido.
  - `DELETE /api/v1/app/me/avatar` → 204, borra predefinido y foto.
  - `GET /api/v1/app/me`: `profile.avatarId` (int o ausente/null; `explicitNulls = false`), `profile.avatarUrl` igual que antes (fotos antiguas se siguen sirviendo firmadas).
  - Ranking `top3[]`: `avatarId` y `avatarUrl` siempre presentes (null explícito). Foro `autor`: `{handle, avatarUrl, avatarId}`.
- Contrato: FA53 aditiva en `profiles.yaml` (D1-14 JSON `AvatarPresetRequest`, `AvatarResponse` con `avatarId` y campos opcionales, `MeProfile.avatarId`, D1-15), `ranking.yaml`, `foro.yaml`, `errors.yaml`; manifiesto de congelación. Sin rutas nuevas. CONTRACT_FREEZE.md (repo OposiBot): PENDING_OWNER.

## Anexo al runbook (lo hace el propietario)

1. Requisito: F20 (`20261109100000`) registrada en MAIN (predecesora del manifiesto; F3 `20261108100000` va antes).
2. `MANIFEST=ops/deploy/v1/main-migrations-2026-11-f29.manifest bash ops/deploy/v1/apply-main-migration.sh supabase/migrations/20261114100000_v1_avatar_preset.sql`.
3. DESPUÉS, imagen Ktor nueva (lee `avatar_id` en `/me`: con la imagen nueva y sin la columna, PostgREST responde 400 y `/me` falla). La imagen actual con la migración aplicada sigue funcionando (no lee la columna; el ranking/foro le llegan con una clave más que ignora).
4. `AVATAR_PHOTO_UPLOADS_ENABLED` no hace falta definirla (por defecto apagada).
5. Rollback: imagen anterior y después el bloque ROLLBACK de la migración (vuelven los cuerpos de F3; se pierden los predefinidos elegidos) y `delete from supabase_migrations.schema_migrations where version = '20261114100000'`.

## Verificación

dbtest `V1AvatarPresetMigrationTest` (6) y `V1AvatarMigrationTest` (control negativo ahora también sin F29); Ktor `AvatarRoutesTest` (+6) y suite completa; gate PG17; ensayo `docs/ledger/2026/11/rehearse-f29-2026-11.sh`.
