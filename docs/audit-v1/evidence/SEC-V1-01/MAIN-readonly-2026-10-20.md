# MAIN, lectura de catálogo solo lectura (2026-10-20)

Autorización del propietario (mandato 2026-10-20): definición, propietario, permisos y dependencias de `can_access_conversation`, `can_manage_content` y `can_moderate`; clasificación de las 21 tablas con RLS sin políticas; advisors; identidad del proyecto. Sin datos personales, sin secretos, sin escrituras. Todas las consultas leen `pg_catalog`/`pg_policies` y advisors. Las `reltuples` son estimaciones del catálogo, no lecturas de filas.

Identidad: `get_project_url` → `https://ndiooyyqtaeysnedywer.supabase.co` (MAIN).

## 1. Funciones `can_*`

| Función | Tipo | Cuerpo | search_path | EXECUTE | Dependencias |
|---|---|---|---|---|---|
| `can_manage_content()` | SQL STABLE SECURITY DEFINER, dueño postgres | `select public.es_admin()` | `''` | postgres, service_role, **authenticated** | `storage.objects` `p_storage_news_images_update` / `_delete` (bucket **news-images, existe**); `can_access_exam(integer)` |
| `can_moderate()` | ídem | `select public.es_moderador_o_admin()` | `''` | postgres, service_role, authenticated | `can_access_conversation(integer)` |
| `can_access_conversation(uuid)` | ídem | `es_moderador_o_admin() or exists conversaciones_soporte (usuario_id = auth.uid())` | `''` | postgres, service_role, authenticated | ninguna política |
| `can_access_conversation(integer)` | ídem | legado: `support_conversation` / `support_participant` / `current_usuario_id()` | **`public`** | postgres, service_role (sin authenticated) | `realtime.messages` `p_realtime_support_read` / `_write` |
| `can_access_exam(integer)` | ídem (legado) | `examen` / `acceso_examen_usuario` | `public` | postgres, service_role | ninguna |

Las tablas `support_conversation` y `support_participant` **no existen**. Por eso las dos políticas de realtime de soporte fallan cerradas: `authenticated` no tiene EXECUTE y, aunque lo tuviera, la consulta referencia tablas inexistentes. Es código muerto (P3, limpieza a coordinar con Bot, sin GO).

Clasificación de riesgo:

- **`can_manage_content` → P1, dentro de RB-16.** Concede UPDATE/DELETE a un admin JWT sobre `news-images` (bucket existente). La detección de RB-16 buscaba solo `es_admin\(` y no lo veía. **Corregido localmente**: la migración 20261023100100 pasa de 12 a 14 políticas y detecta `es_admin|can_manage_content`.
- **`can_moderate` / `es_moderador_o_admin` → P1, nuevo SEC-V1-24.** `es_moderador_o_admin()` ignora `estado_cuenta`. 15 políticas públicas lo llaman, y `authenticated` tiene S/I/U/D sobre las 9 tablas implicadas (`acciones_moderacion`, `comentarios_oposired`, `publicaciones_oposired`, `reacciones_oposired`, `conversaciones_soporte`, `mensajes_soporte`, `feedback_examen`, `feedback_pregunta`, `reportes_contenido`). Un moderador o admin **bloqueado** sigue leyendo la cola de reportes y escribiendo y borrando el historial de moderación por PostgREST. **Arreglo local**: 20261023100300 (expediente propio).
- El EXECUTE de `authenticated` sobre `es_admin`, `es_moderador_o_admin` y `can_*` se mantiene, porque las políticas lo necesitan (advisor 0029, intencional). Solo devuelven un booleano del propio `auth.uid()`.
- `chat_attachments_storage_staff_*` (4 políticas con `es_moderador_o_admin`) apuntan a un bucket que no existe y son inertes. Quedan fuera de RB-16. Se reevalúan si se crea el bucket.

## 2. Las 21 tablas con RLS sin políticas

Ninguna tiene privilegios de `anon` ni de `authenticated` (`has_table_privilege` = false en las 4 operaciones). Las 5 de `private` tampoco tienen USAGE de esquema para los clientes, y su ACL es solo `postgres`. Clasificación: **21/21 INTENTIONAL_SERVER_ONLY**, ninguna MISSING_POLICY.

| Tabla | Acceso real |
|---|---|
| public.aceptaciones_legales, atribucion_usuario, comunidades_objetivo_usuario, perfiles_usuario_detalle, solicitudes_eliminacion_cuenta, preguntas_intento, idempotency_keys, eventos_app | Solo vía funciones SECURITY DEFINER (`app_*`, `idem_*`, `record_event`) o service_role |
| public.avisos_usuario, solicitudes_acceso_admin | Comentario de tabla: «Solo el backend (service_role)» |
| public.efectos_servicio, popup_usuario_historial, reglas_popup_comercial, tarifas_producto_comercial, usos_promocion_usuario, valor_usuario_resumen | Solo service_role (Ktor) |
| private.identity_backfill_report, m8_/m10_previous_definitions_20260919, preguntas_especialidad_backup_20260919, slice1_client_ddl_grants_revoked | Copias de migraciones, solo postgres |

Si en el futuro un cliente necesitase leer alguna, haría falta una política. Hoy RLS+sin grants es la postura correcta («deny by default»).

## 3. Advisors

- Seguridad: 0008 ×21 (arriba), 0011 search_path mutable ×2 (`noticia_touch_actualizado_en`, `noticia_sellar_published_at`; SECURITY INVOKER, P3, arreglo local pendiente), 0029 ×6 (intencional salvo `oposibots_upsert_own_profile`, que es de Bot y ya está revisada), contraseñas filtradas desactivado (Auth = Bot/propietario).
- Rendimiento: FK `productos_comerciales_plan_fk` sin índice (×1, se medirá en copia local), 55 índices sin uso (no se borra nada: el tráfico de MAIN es bajo y el contador no prueba nada), 15 permisivas múltiples (13 desaparecen con SEC-V1-01; `etiquetas_opositeca`, `invitaciones_referido` y `opositeca_items` = RB-17), Auth con 10 conexiones absolutas (Info, ops).

## 4. `*_admin_all` (comprobación cruzada)

En MAIN hay 17. SEC-V1-01 cubre 13. Las otras 4 (`etiquetas_opositeca`, `invitaciones_referido`, `opositeca_items`, `preguntas_parseadas_staging`) ya figuran en RB-17 (checkpoint de 2026-10-19). No hay hallazgo nuevo.
