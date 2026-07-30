# Registro de decisiones

Fecha de corte: 2026-07-29
Estados: `ACEPTADA`, `PARCIAL`, `BLOQUEADA`, `CONGELADA`, `SUPERADA`.

Este archivo registra decisiones de arquitectura y alcance. El estado de
implementación se mantiene por separado en
[`IMPLEMENTATION_STATUS_2026-07-29.md`](IMPLEMENTATION_STATUS_2026-07-29.md).

## D-001 — Noticias queda fuera de esta entrega

- **Decisión:** congelar creación, editor, endpoints, SQL y métricas específicas
  de Noticias. Solo se permiten comprobaciones de regresión por cambios
  compartidos.
- **Fuente:** plan aprobado, sección «Resumen y alcance» y protocolo común.
- **Impacto:** `features/news`, `features/news-types`, `NewsGateway.kt` y
  `MobileLayeredNewsEditor.kt` no se editan en esta quincena.
- **Responsable:** coordinador; Enciclopedia vigila el límite.
- **Estado:** `CONGELADA`.
- **Compatibilidad:** se exige comportamiento previo idéntico; la creación de
  Noticias tendrá un plan posterior con los seis participantes.

## D-002 — Esquema canónico singular con ID entero

- **Decisión:** `especialidad`, `examen`, `pregunta`, `opcion_pregunta`,
  `recurso_pregunta` y el resto del modelo singular con ID entero son la fuente
  de verdad.
- **Fuente:** plan aprobado; migración
  `20260729000100_admin_core_schema.sql`; documento de alineamiento real.
- **Impacto:** contratos, FKs, RPC, rutas y selectores deben transportar IDs
  estables; las escrituras nuevas no usarán el esquema plural/UUID.
- **Responsable:** Backend con revisión del coordinador.
- **Estado:** `ACEPTADA`, implementación `PARCIAL` mientras conviven rutas
  heredadas.
- **Compatibilidad:** el esquema plural/UUID solo puede conservarse como
  adaptador de lectura durante una versión si hay consumidores confirmados; no
  se elimina sin inventario y backup.

## D-003 — Supabase Auth directo; negocio exclusivamente por Ktor

- **Decisión:** el cliente usa Supabase directamente solo para login, refresh y
  logout. Todo dato administrativo o de negocio pasa por Caddy/Ktor.
- **Fuente:** plan aprobado; `AppConfig`, `AuthRepositoryImpl`,
  `BackendConfiguration`, `BackendSecurity`.
- **Impacto:** desaparecen el token backend estático y secretos del APK; Ktor
  valida JWKS, claims y sesión activa.
- **Responsable:** Backend y coordinador.
- **Estado:** `PARCIAL`; Auth y protección Ktor existen, pero varios repositorios
  siguen usando contratos heredados y el corte debe probarse extremo a extremo.
- **Compatibilidad:** `/content`, `/admin` y `/v1` son puentes temporales
  autenticados. No son autorización para añadir nuevas escrituras heredadas.

## D-004 — Actor del JWT y RBAC efectivo

- **Decisión:** el actor se deriva de `sub`; nunca se acepta desde el cuerpo.
  Toda ruta exige un permiso de `app_permission`.
- **Fuente:** plan aprobado; migración
  `20260729000200_admin_rbac_rls.sql`; `BackendSecurity.kt`;
  `RouteAuthorization.kt`.
- **Impacto:** permisos separados para lectura, escritura y publicación; las
  auditorías/RPC usan `auth.uid()`.
- **Responsable:** Backend; Enciclopedia valida la matriz.
- **Estado:** `PARCIAL`; la capa Ktor exige permisos, pero las policies de
  Data API deben impedir que un cliente con `content.write` publique
  directamente saltándose `content.publish`.
- **Compatibilidad:** los códigos de permiso sembrados son contrato estable;
  cambios requieren seed/migración y actualización conjunta de DTO, rutas y UI.

## D-005 — Modelo único de exámenes

- **Decisión:** tipos `inmersivo` y `rafaga`; acceso `gratuito` y `premium`;
  estados `draft`, `published`, `archived`. Ráfaga es un subtipo de `examen`;
  `campana_rafaga` es promoción comercial independiente.
- **Fuente:** plan aprobado; constraints de migración `001`; migración `007`;
  `ContentContracts.kt`.
- **Impacto:** una sola red de preguntas, opciones, recursos, publicación y
  acceso.
- **Responsable:** Backend, Lógica y UI/UX.
- **Estado:** `ACEPTADA`, migración e integración `PARCIAL`.
- **Compatibilidad:** la vista «Ráfagas» puede mantenerse como filtro. Las
  tablas antiguas permanecen temporalmente para rollback/lectura, sin nuevas
  escrituras.

## D-006 — Cuatro opciones normalizadas y recursos privados

- **Decisión:** cada pregunta persistida tiene cuatro opciones normalizadas,
  exactamente una correcta; se elimina la codificación mágica en `enunciado`.
  Los recursos premium se guardan en bucket privado y se entregan mediante URL
  firmada tras autorización.
- **Fuente:** plan aprobado; migraciones `004`, `005` y `006`;
  `ContentContracts.kt`.
- **Impacto:** guardar pregunta/opciones e importar debe ser atómico; máximo de
  recurso 50 MB, MIME validado y compensación Storage/DB.
- **Responsable:** Backend.
- **Estado:** `PARCIAL`; importación es RPC atómica y Storage está definido,
  pero el camino activo debe demostrar que no usa serialización mágica ni
  escrituras multipaso, y falta un contrato de subida/compensación completo.
- **Compatibilidad:** `legacy_payload` sirve para backfill/diagnóstico, no como
  modelo de escritura.

## D-007 — Idempotencia durable y concurrencia optimista

- **Decisión:** comandos sensibles usan `Idempotency-Key`, hash de petición y
  respuesta durable; los `PATCH` incluyen versión esperada y devuelven `409`
  ante conflicto.
- **Fuente:** plan aprobado; migraciones `003` y `006`;
  `AdminApiHeaders`; `CanonicalAdminGateway`.
- **Impacto:** tickets, accesos, roles, importación, reordenación y comercio
  resisten reintentos y concurrencia sin duplicar efectos.
- **Responsable:** Backend y coordinador.
- **Estado:** `PARCIAL`; RPC operativas e importación existen y los PATCH
  filtran por versión. Los upserts de comercio no se consideran cerrados hasta
  sustituir la caché de proceso por persistencia/RPC y superar pruebas.
- **Compatibilidad:** reutilizar la misma clave con otro cuerpo debe producir
  `409`; una reinicialización del proceso no puede perder el resultado.

## D-008 — Contrato HTTP compartido desacoplado del dominio

- **Decisión:** `core/api` es el módulo de DTO serializables; `domain` no depende
  del transporte. API base `/api/admin/v1`, errores normalizados y páginas por
  cursor.
- **Fuente:** plan aprobado; `settings.gradle.kts`; archivos de `core/api`.
- **Impacto:** cliente y servidor comparten nombres/rutas sin filtrar DTO al
  dominio.
- **Responsable:** coordinador y Backend.
- **Estado:** `PARCIAL`; el módulo compila y varias rutas lo usan, pero faltan
  rutas canónicas de usuarios, roles, permisos, auditoría, preferencias,
  dashboard y búsqueda; los listados deben aplicar 50 por defecto y 100 máximo.
- **Compatibilidad:** los nombres materializados
  (`PublicationStatus`, `ApiErrorResponse`, `AdminPrincipalResponse`,
  `PermissionResponse`) se mantienen; cualquier renombrado requiere migración.

## D-009 — MVI por pantalla y ámbitos explícitos

- **Decisión:** `Route` conecta VM/efectos y `ScreenContent` es puro; `UiState`
  inmutable, `Action` tipada, `Effect` por `Channel(BUFFERED)`, intents
  serializados y submits sin doble envío.
- **Fuente:** plan aprobado; skills
  `compose-state-holder-ui-split` y `kotlin-flow-state-event-modeling`;
  `MviViewModel.kt`.
- **Impacto:** búsquedas latest-wins, resultados obsoletos ignorados y efectos
  de una sola entrega.
- **Responsable:** Lógica, emparejado con UI/UX.
- **Estado:** `PARCIAL`; Auth, Especialidades, Accesos y Tickets tienen Effect;
  el resto necesita revisión explícita. Los ámbitos DI aún contradicen Auth
  app-scope y Exámenes workspace-scope.
- **Compatibilidad:** IDs son la identidad; nombres/títulos solo presentación.

## D-010 — Diseño glass con medidas exactas

- **Decisión:** conservar paleta/glass y usar el sistema exacto de medidas y
  breakpoints acordado; `BoxWithConstraints` únicamente en la raíz.
- **Fuente:** plan aprobado; `AGENTS.md`; `AdminTheme`, `AppLayoutMetrics`,
  `UiSpacing` y componentes de `core/ui`.
- **Impacto:** pantallas accesibles y coherentes en Android/JVM sin colores
  morados accidentales.
- **Responsable:** UI/UX.
- **Estado:** `PARCIAL`; tokens y primitivas están materializados, pero quedan
  usos no raíz de `BoxWithConstraints`, weights en UI no-News y falta evidencia
  de toda la matriz de tamaños/font scale.
- **Compatibilidad:** reorganizar y rediseñar está permitido; la paleta y el
  tono glass no cambian.

## D-011 — Observabilidad estructurada y segura

- **Decisión:** propagar `correlationId` por operación y `requestId` por intento,
  usar tags `OC-*` y una allowlist estricta.
- **Fuente:** plan aprobado; `core/common/.../logging`;
  `docs/observability/logging-runbook.md`.
- **Impacto:** trazabilidad cliente → Ktor → Supabase sin secretos ni PII; un
  solo stacktrace por error.
- **Responsable:** Logs, con integración Backend/Lógica.
- **Estado:** `PARCIAL`; logger, redactor, sinks, memoria de test y scripts
  existen. Ktor no propaga todavía `correlationId` a Supabase y falta
  instrumentación de todas las pantallas/repositorios.
- **Compatibilidad:** release solo WARN/ERROR; la verbosidad ampliada se limita
  a builds dev.

## D-012 — Despliegue VPS detrás de Caddy

- **Decisión:** Caddy es la única entrada pública; Ktor queda en red interna.
  Secretos externos, contenedores endurecidos e imágenes inmutables.
- **Fuente:** plan aprobado; `deploy/`; runbook de producción.
- **Impacto:** HTTPS, límite 55 MB, timeouts compatibles con 120 s, cabeceras de
  seguridad y `/health/live` como única ruta pública.
- **Responsable:** Backend y coordinador.
- **Estado:** `PARCIAL`; el Caddyfile valida localmente y Ktor no publica puerto,
  pero faltan perfiles staging/producción, pin por digest, build Docker y
  despliegue real.
- **Compatibilidad:** rollback de aplicación por imagen; migraciones aditivas,
  sin borrar columnas/tablas durante rollback.
