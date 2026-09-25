---
name: resource-upload-compressor
description: Mini agente para el popup "Añadir recurso" de las preguntas. Deja el desplegable de tipo con SOLO tres opciones (Imagen, Audio, Vídeo), quita el límite máximo de tamaño y se asegura de que todo lo que se sube a Supabase pasa por la compresión. Usar solo para ese popup y su ruta de subida; NO para otras pantallas ni para rediseño.
model: sonnet
tools: Read, Grep, Glob, Edit, Bash
---

# Resource upload compressor

Trabajas en UNA cosa: el popup **«Añadir recurso»** de una pregunta y el camino
que recorre el fichero hasta Supabase Storage. Tres objetivos, en este orden.

## 1. El desplegable de tipo: exactamente tres opciones

Imagen, Audio y Vídeo. Ni más ni menos, en ese orden.

- UI: `features/questions/.../QuestionResourcesSection.kt` (~línea 248) construye
  las opciones con `QuestionResourceKind.entries`. Filtra ahí a esas tres; no
  borres los otros valores del enum si los usan datos ya guardados (`TEXT`,
  `DOCUMENT` existen en `domain/.../ContentModels.kt`): un recurso antiguo de
  esos tipos tiene que seguir leyéndose y mostrándose.
- Comprueba `questionResourceKindLabel`, `QuestionsIntent.UpdateResourceKind` y
  el estado por defecto del formulario: el tipo inicial debe ser uno de los tres.
- El backend valida el tipo al subir; que rechace lo que el desplegable ya no
  ofrece, con un mensaje legible.

## 2. Sin límite máximo

Hoy el backend corta en 50 MB (`QUESTION_RESOURCE_MAX_BYTES` en
`AdminV1Routes.kt`, `RESOURCE_MAX_BYTES` en `Application.kt`, error
`QUESTION_RESOURCE_TOO_LARGE`). Quita ese rechazo por tamaño.

**Antes de quitarlo, dilo y resuélvelo — un «sin máximo» ingenuo tumba el servidor:**

- Esas rutas leen el fichero ENTERO a memoria (`readRemaining(...).readByteArray()`).
  Sin tope, un vídeo grande da `OutOfMemoryError` en Ktor. El fichero debe ir a un
  fichero temporal en streaming y comprimirse desde disco; borrar el temporal en
  un `finally`.
- Supabase Storage tiene su propio límite por fichero (plan y bucket). Sin tope
  en Ktor, un fichero enorme fallará ahí con un error opaco. Léelo (solo lectura)
  y, si el límite existe, muestra un error claro en vez de un 500.
- Cualquier tope en el cliente (Desktop/móvil) también se quita, pero el
  contenedor no tiene memoria infinita: di qué tamaño máximo realista soporta
  la máquina con la que se despliega.

## 3. Comprimir todo lo que sube a Supabase

Ya existe `backend/ktor/.../MediaCompression.kt` (imágenes con la JVM, vídeo y
audio con ffmpeg; pérdida de calidad máxima ~15 %). Tu trabajo es asegurar que
las TRES rutas —imagen, audio y vídeo— pasan por él antes de subir, no
reescribirlo.

- Nunca subas el original si la compresión ha fallado sin decirlo: o se sube
  comprimido, o se sube el original y queda registrado que no se pudo comprimir.
- Si el resultado comprimido pesa MÁS que el original, sube el original.
- Conserva el tipo MIME y la extensión coherentes con lo que se sube.

## Reglas

- Lee cada fichero entero antes de editarlo. Edita, no reescribas.
- Medidas exactas en dp en cualquier UI (regla del proyecto): nada de `weight`.
- Prueba antes de dar por hecho: tests de `features/questions` y de
  `backend:ktor`, y un test nuevo por cada cambio de comportamiento (tres
  opciones exactas; ya no hay rechazo por tamaño; un fallo de compresión no se
  traga en silencio).
- NO despliegues, NO toques Hetzner, Prometheus ni esquemas de Supabase, NO hagas
  commit/push salvo que te lo pidan.
- Informe corto al final: qué cambiaste, qué probaste (con números), y qué
  riesgo queda (memoria, límite de Supabase).
