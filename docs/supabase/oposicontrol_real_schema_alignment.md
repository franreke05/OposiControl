# Alineacion Supabase real - OposiControl

Fecha: 2026-05-01

## Resultado de la revision

El SQL real recibido cubre la base de usuarios, roles, examenes, preguntas, recursos simples, noticias base, tipos de noticia, tienda basica, tickets, compras, inventario, accesos, soporte y auditoria.

Antes de conectar la app, habia rutas de cliente que apuntaban a tablas antiguas en ingles:

- `specialties`, `exams`, `exam_questions`, `question_resources`.
- `store_items`, `store_offers`, `limited_resources`, `burst_campaigns`.

Se han alineado las rutas principales de la app a:

- `especialidad`
- `examen`
- `pregunta`
- `recurso_pregunta`
- `noticia`
- `articulo_tienda`

## Bloque listo para conectar

- Especialidades: lee/escribe `especialidad`.
- Examenes: lee/escribe/soft delete `examen`.
- Preguntas: lee/escribe/importa/soft delete `pregunta`.
- Recursos de pregunta: lee/escribe `recurso_pregunta` usando `tipo_recurso` y `url`.
- Noticias base: lee/escribe/soft delete `noticia`.
- Tienda base: lee/escribe `articulo_tienda`.

## Huecos reales del SQL recibido

### 1. Opciones/respuestas de preguntas

El SQL recibido tiene `pregunta`, pero no tiene una tabla para opciones A/B/C/D ni respuesta correcta. Sin esta tabla, importar 50 preguntas tipo test no puede persistir las respuestas.

Necesario: `opcion_pregunta`.

### 2. Editor visual de noticias

El SQL recibido tiene `noticia`, pero no tiene tabla de capas. El editor visual necesita guardar:

- capa de texto/imagen
- posicion x/y
- width/height
- opacidad
- orden visual
- visibilidad

Necesario: `noticia_layer`.

### 3. Tienda profesional

El SQL recibido tiene `articulo_tienda`, compras e inventario, pero no tiene:

- ofertas programadas
- recursos temporales
- rafagas/campanas

Necesario:

- `oferta_tienda`
- `recurso_temporal_tienda`
- `campana_rafaga`

### 4. Recursos sin enlazar

`recurso_pregunta.url` permite enlazar recursos, pero no diferencia claramente `storage_path`, `public_url`, `mime_type` o estado. La auditoria puede funcionar cruzando bucket `exam-resources` contra `recurso_pregunta.url`, pero sera mas limitada que con columnas separadas.

## Decision aplicada en codigo

- No se mete logica Supabase en ViewModels.
- La UI sigue usando Domain -> Repository -> UseCase -> ViewModel -> Compose UI.
- Los IDs `int4` de Supabase se convierten a `String` solo en dominio para no rehacer pantallas.
- Las tablas opcionales de tienda avanzada se leen de forma tolerante: si aun no existen, la tienda base no rompe.
- Las capas de noticias se leen/guardan de forma tolerante: si `noticia_layer` aun no existe, la noticia base no rompe, pero el canvas no quedara persistido profesionalmente hasta aplicar la migracion.

## Recomendacion antes de conectar produccion

Aplicar `docs/supabase/oposicontrol_required_content_extensions.sql`.

Sin esa migracion, la conexion base puede arrancar, pero estas partes quedarian incompletas:

- importacion real con respuestas correctas
- editor visual de noticias persistente
- ofertas
- recursos por tiempo limitado
- rafagas/campanas
