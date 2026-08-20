# Database Map

## Evidencia disponible

No hay conexión Supabase callable en esta sesión; no se consultaron tablas, filas, vistas, policies o buckets actuales. Todo lo siguiente es código/SQL/documentación.

## Familias de tablas detectadas

| Familia | Ejemplos | Estado |
|---|---|---|
| V1 actual documentada | `especialidades`, `examenes`, `preguntas`, `opciones_pregunta`, `soluciones_pregunta`, `preguntas_examen`, `recursos_pregunta`, `perfiles_usuario`, `roles_usuario` | DOCUMENTED_ONLY |
| Importación | `lotes_importacion_preguntas`, `preguntas_parseadas_staging` | DOCUMENTED_ONLY; code routes exist |
| App | `intentos_examen`, `respuestas_intento`, `estadisticas_usuario`, `retos_diarios`, `feedback_pregunta`, `eventos_app` | code expects; runtime NOT_VERIFIED |
| Admin legacy | `usuario`, `app_role`, `user_role`, `examen`, `pregunta`, `recurso_pregunta`, `noticia`, `articulo_tienda`, support/tickets | LEGACY/DOCUMENTED_ONLY |
| News/editor | `noticia_layer`, `tipo_noticia` | PARTIAL/documented |
| Commerce | `oferta_tienda`, `recurso_temporal_tienda`, `campana_rafaga` y variantes inglesas | PARTIAL/documented |
| Search | `tag`, `entity_tag`, `search_index` | code + SQL docs; runtime unknown |

## Rows / views / materialized views

Rows, views y materialized views: `NOT_VERIFIED`. No se observó código explícito de views materializadas.

## Storage

SQL documental define bucket `exam-resources` y policies; código sube portadas, recursos y assets. Buckets actuales y reglas efectivas: `NOT_VERIFIED`.
