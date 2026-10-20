# SEC-V1-01 · Checkpoint de MAIN (GO-1, 2026-10-19, solo lectura)

Proyecto `ndiooyyqtaeysnedywer`. Consultas ejecutadas tal cual: `supabase/canonical/sec_v1_01_main_intervention/01_preflight_readonly.sql`, `02_checkpoint_and_compat_readonly.sql` y `list_edge_functions`. Solo metadatos de catálogo y recuentos: sin secretos ni datos personales. **La migración NO se ha aplicado.**

## Preflight

70/70 filas `ok = true`. `admins_total` = 2 y `admins_without_active_account` = 0, así que ningún admin pierde privilegios por la nueva regla de `es_admin()`. `es_admin()` tiene el cuerpo MAIN esperado y `perfiles_usuario_update_propio` la forma esperada.

## Edge Functions

Ninguna (`{"functions":[]}`): no hay impacto.

## Estado exacto que restaura el bloque ROLLBACK

ACL (14 tablas, todas iguales):
`{postgres=arwdDxtm/postgres,anon=rm/postgres,authenticated=arwdm/postgres,service_role=arwdDxtm/postgres}` en examenes, preguntas, opciones_pregunta, soluciones_pregunta, preguntas_examen, recursos_pregunta, especialidades, comunidades_autonomas, etiquetas, etiquetas_pregunta, lotes_importacion_preguntas, noticia, tipo_noticia y perfiles_usuario.

Políticas `*_admin_all` (13): `* permissive=true roles={authenticated} using=es_admin() check=es_admin()`.
`perfiles_usuario_update_propio`: `w permissive=true roles={authenticated} using=((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin()) check=((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin())`.

`es_admin()`:
```sql
CREATE OR REPLACE FUNCTION public.es_admin()
 RETURNS boolean
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
  select exists (
    select 1
    from public.roles_usuario ru
    join public.roles_app ra on ra.id = ru.rol_id
    where ru.usuario_id = auth.uid()
      and ra.codigo in ('superadmin', 'admin')
  );
$function$
-- acl: {postgres=X/postgres,service_role=X/postgres,authenticated=X/postgres}
```

## Dependencias de `es_admin()` en MAIN (afectadas solo para admins no activos)

- Función: `public.can_manage_content()` (SECURITY DEFINER, EXECUTE para authenticated).
- 64 políticas de `public` y 15 de `storage.objects`. Lista completa en la salida de la consulta 02; las relevantes están en los hallazgos de abajo.

## Hallazgos nuevos del checkpoint

| ID | Sev | Hallazgo | Estado |
|---|---|---|---|
| SEC-V1-01c | P1 | 15 políticas de `storage.objects` dan INSERT/UPDATE/DELETE (y SELECT) con `es_admin()` en los buckets `exam-resources`, `news-assets` (público), `question-resources` y `recursos_pregunta`. Un JWT de admin escribe o borra objetos **directamente en Storage**, sin pasar por Ktor ni por su re-codificación y validación de MIME. Ktor usa `service_role` para Storage. | LIVE_VERIFIED (política); la migración 1023 no lo cierra, solo para admins bloqueados |
| SEC-V1-01b | P1/P2 (sin verificar grants) | Más políticas de escritura con `es_admin()` fuera de las 13: `etiquetas_opositeca_admin_all`, `invitaciones_referido_admin_all`, `lotes_actualizacion_ranking_*`, `opositeca_items_admin_all`, `preguntas_parseadas_admin_al*`, y `*_todo_propio` / `*_update_propio` con rama admin en `configuraciones_usuario`, `preguntas_favoritas_usuario`, `registros_reto_diario`, `sesiones_estudio`, `tokens_dispositivo`, `cola_repaso_usuario`, `recomendaciones_usuario`, más el INSERT de `abandonos_examen` y `uso_recursos_pregunta`. Que sea explotable depende de que `authenticated` tenga INSERT/UPDATE/DELETE en esas tablas | Requiere otra lectura de catálogo (GO) |

## RB-17 · Grants de las tablas con políticas de escritura que dependen de `es_admin()` (lectura de catálogo con GO, 2026-10-19)

`authenticated` tiene INSERT, UPDATE y DELETE en las 28 tablas (RLS activo en todas, `anon` sin escritura). Además de las 13 de la migración 1023 y de `perfiles_usuario`:

| Tabla | Política | Tipo | Impacto si un JWT de admin escribe directo |
|---|---|---|---|
| opositeca_items | opositeca_items_admin_all | ALL, `es_admin()` | contenido de la Opositeca sin Ktor ni auditoría |
| etiquetas_opositeca | etiquetas_opositeca_admin_all | ALL | ídem |
| invitaciones_referido | invitaciones_referido_admin_all | ALL | referidos y recompensas manipulables |
| lotes_actualizacion_ranking | lotes_actualizacion_ranking_staff_all | ALL | lotes del job de ranking |
| preguntas_parseadas_staging | preguntas_parseadas_admin_all | ALL | staging de importación antes de publicar |
| tokens_dispositivo | tokens_dispositivo_todo_propio | ALL, propio OR admin | registrar un dispositivo en la cuenta de otro (sus notificaciones push) |
| registros_reto_diario | registros_reto_todo_propio | ALL, propio OR admin | registros del reto diario de otros |
| sesiones_estudio | sesiones_estudio_todo_propio | ALL, propio OR admin | datos de estudio de otros |
| configuraciones_usuario | configuraciones_usuario_todo_propio | ALL, propio OR admin | ajustes de otros |
| preguntas_favoritas_usuario | favoritas_todo_propio | ALL, propio OR admin | favoritas de otros |
| cola_repaso_usuario | cola_repaso_update_propio | UPDATE, propio OR admin | cola de repaso de otros |
| recomendaciones_usuario | recomendaciones_update_propio | UPDATE, propio OR admin | recomendaciones de otros |
| abandonos_examen | abandonos_examen_insert_propio | INSERT, propio OR admin | telemetría falsa a nombre de otros |
| uso_recursos_pregunta | uso_recursos_insert_propio | INSERT, propio OR admin | ídem |

Nota para OposiBot (dueño de los datos de usuario): con las políticas `*_todo_propio`, el propio usuario también escribe sus filas directamente por PostgREST (por ejemplo `registros_reto_diario`). Hay que comprobar que ninguna recompensa se decida leyendo filas que el cliente puede escribir. HYPOTHESIS: puede que lo cubra `20261022101200` en v1-server.
