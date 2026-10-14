# OPOSICONTROL — ADMIN BACKEND FULL AUDIT

Fecha: 2026-09-04  
Rama local: `codex/backend-admin-audit-20260904`  
Base: `dce182ce2a38fa57c6b44d1c071f7233a45bb380`  
Worktree: `C:\Users\franr\AndroidStudioProjects\OposiControl-codex-backend-audit`

## VERDICT

El backend Admin tiene una base útil y varias superficies canónicas reales, pero no está listo para declarar el panel «completo». La lectura de contenido, rankings, analítica, catálogos comerciales, Market y buena parte de los detalles funciona o está razonablemente cableada. Sin embargo, existen roturas verificadas en operaciones nucleares: alta/edición completa/importación de preguntas, sincronización de roles, ajuste de tickets desde el contrato principal, ocultación de contenido reportado, recursos binarios y noticias. También faltan atomicidad, actoría de auditoría, permisos por acción y paginación uniforme.

No se ha escrito en Supabase ni Storage, no se han ejecutado migraciones, no se han realizado pruebas destructivas y no se ha tocado UI/navegación ni la pantalla de Exámenes.

`SAFE_TO_START_IMPLEMENTATION: NO`

Motivo: antes de la Fase B debe aprobarse este checkpoint, el orden P0 y las decisiones de producto/esquema enumeradas abajo. Hay correcciones locales de código que son mecánicas, pero la implantación completa mezcla cambios Kotlin con RPC/migraciones que requieren gate humano.

## DOMAINS_AUDITED

Contenido; usuarios, roles, rankings y accesos; monetización, catálogo, Market y OposiTickets; OposiRed, reportes, moderación, OposiTester y soporte; seis áreas de analítica; noticias/publicación; auditoría, estado y ajustes; autenticación/autorización; errores, validación, idempotencia, transacciones, paginación, esquema, RLS, RPC y Storage.

## OPERATION COUNTS

El conteo usa capacidades atómicas solicitadas, no número de rutas. Una ruta que lee, valida, muta y ejecuta una acción especial puede aportar varias capacidades. Los grupos de solo lectura o no implementados por diseño se separan para no presentarlos como fallos.

| Classification | Count |
|---|---:|
| TOTAL_OPERATIONS | 218 |
| WORKING | 89 |
| PARTIAL | 56 |
| BROKEN | 31 |
| MISSING | 23 |
| LEGACY | 7 |
| READ_ONLY_BY_DESIGN | 9 |
| NOT_IMPLEMENTED_BY_DESIGN | 3 |

## FEATURE MATRIX

| FEATURE | READ | CREATE | UPDATE | DELETE | SPECIAL ACTIONS | BACKEND | DB | STATUS |
|---|---|---|---|---|---|---|---|---|
| Especialidades | WORKING; detalle PARTIAL | WORKING | WORKING | archivo WORKING | capacidades R/W WORKING | V1 | tablas plurales | PARTIAL |
| Comunidades autónomas | WORKING | WORKING | WORKING | WORKING | — | V1 | real | WORKING |
| Etiquetas | WORKING | WORKING | WORKING | WORKING | — | V1 | real | WORKING |
| Exámenes — solo auditoría | WORKING | WORKING | WORKING | archivo WORKING | publicar/despublicar WORKING; reordenar/portada PARTIAL | V1 | real; Storage no live-verificado | PARTIAL |
| Preguntas | WORKING | BROKEN | BROKEN con opciones | archivo WORKING | solución/opciones BROKEN; vínculo/reordenación PARTIAL | llama RPC ausentes | cuatro tablas | BROKEN |
| Recursos de pregunta | WORKING | metadato WORKING | metadato WORKING | archivo WORKING | upload binario BROKEN; orden MISSING; auditoría PARTIAL | V1 no recibe multipart | metadata real; Storage sin contrato V1 | BROKEN |
| Importación PDF | WORKING | lote/PDF WORKING | staging WORKING | descarte MISSING | publicar BROKEN; duplicados MISSING; no atómico | V1 | staging real; RPC candidata sin aplicar | BROKEN |
| Usuarios Admin | detalle WORKING; lista/búsqueda PARTIAL | fuera de alcance funcional actual | PARTIAL | NOT_IMPLEMENTED_BY_DESIGN | activar BROKEN; roles BROKEN | V1 | perfiles/roles reales; RPC ausente | BROKEN |
| Rankings | READ_ONLY_BY_DESIGN | — | — | — | ligas/global/usuario/búsqueda; consistencia PARTIAL | V1 paginado | tablas reales, algunas vacías | READ_ONLY_BY_DESIGN |
| Accesos a examen | WORKING | PARTIAL | BROKEN | revocación MISSING | caducidad/histórico MISSING | V1 | desbloqueos reales | BROKEN |
| Planes | WORKING | WORKING | WORKING | archivo lógico PARTIAL | beneficios R/W PARTIAL; relación producto WORKING | V1 | tablas reales | PARTIAL |
| Catálogo comercial | WORKING | WORKING | WORKING | baja lógica WORKING | visibilidad/destacado WORKING; tarifas PARTIAL | V1 + App V1 | canónico | PARTIAL |
| Market: packs | WORKING | WORKING | WORKING | vía activo | coste/OT WORKING | V1 | `paquetes_opositickets` | WORKING aislado |
| Market: servicios | WORKING | WORKING | WORKING | vía activo | precio OT/EUR WORKING | V1 | `servicios` | WORKING |
| Market: promociones | WORKING | WORKING | WORKING | vía activo | ventanas/código WORKING | V1 | `promociones` | WORKING |
| Market: popups | WORKING | WORKING | WORKING | vía activo | frecuencia/target WORKING | V1 | `reglas_popup_comercial` | WORKING |
| OposiTickets | wallets/ledger WORKING | ajuste canónico WORKING | — | — | conceder/retirar WORKING en backend; cliente principal BROKEN; actor/audit PARTIAL | V1 + RPC | atómico e idempotente | BROKEN E2E |
| OposiRed/Foro | READ_ONLY_BY_DESIGN; categorías PARTIAL | — | — | — | búsqueda PARTIAL; detalle/estados READ_ONLY_BY_DESIGN | V1 cursor | real | READ_ONLY_BY_DESIGN |
| Reportes | cola PARTIAL; detalle WORKING | — | estado PARTIAL | — | ocultar BROKEN; resolver/recompensar PARTIAL; idempotencia recompensa WORKING | V1 | tres escrituras no atómicas | BROKEN |
| Moderación | historial PARTIAL | acción PARTIAL | — | — | aviso BROKEN; bloqueos/restricciones MISSING; trazabilidad PARTIAL | V1 y legacy mezclados | real parcial | BROKEN |
| OposiTester | WORKING | — | resolver/descartar PARTIAL | — | vínculo pregunta WORKING; actor MISSING; timestamp PARTIAL | V1 cursor | `feedback_pregunta` | PARTIAL |
| Soporte | conversaciones/mensajes PARTIAL | respuesta PARTIAL | estado/asignación BROKEN | cierre MISSING | nota interna BROKEN; actor incorrecto | V1 | tablas reales | BROKEN |
| Analítica: producto | PARTIAL | — | — | — | métricas ausentes explícitas | V1 | lecturas reales | PARTIAL / READ_ONLY |
| Analítica: estudio | PARTIAL | — | — | — | métricas ausentes explícitas | V1 | lecturas reales | PARTIAL / READ_ONLY |
| Analítica: retención | PARTIAL | — | — | — | métricas ausentes explícitas | V1 | lecturas reales | PARTIAL / READ_ONLY |
| Analítica: comunidad | PARTIAL | — | — | — | métricas ausentes explícitas | V1 | lecturas reales | PARTIAL / READ_ONLY |
| Analítica: economía | PARTIAL | — | — | — | dinero no se inventa | V1 | read models incompletos | PARTIAL / READ_ONLY |
| Analítica: marketing | PARTIAL | — | — | — | atribución incompleta | V1 | read models incompletos | PARTIAL / READ_ONLY |
| Noticias/publicación | LEGACY | LEGACY | LEGACY | LEGACY hard delete | capas/assets/programación LEGACY; Storage BROKEN live | `/content`, no Admin V1 | tablas legacy ausentes live | BROKEN / LEGACY |
| Roles | WORKING | BROKEN | BROKEN | — | asignación BROKEN; permisos MISSING | GET V1; write incompleto | `roles_app`; RPC ausente | BROKEN |
| Auditoría | PARTIAL | automática PARTIAL | — | — | filtros MISSING; actor BROKEN; redacción PARTIAL | V1 | real | BROKEN para trazabilidad |
| Estado del sistema | Ktor/Supabase WORKING | — | — | — | Storage MISSING; otros PARTIAL; adapter cliente BROKEN | V1 | n/a | PARTIAL |
| Ajustes | local WORKING | — | local WORKING | — | servidor NOT_IMPLEMENTED_BY_DESIGN | endpoint estático sobrante | local | WORKING local |
| Autorización | autenticación admin WORKING | — | — | — | permisos por acción MISSING; token legacy BROKEN | plugin global | roles reales | BROKEN para RBAC fino |
| Modelo de errores | 400/401/403/500 WORKING | — | — | — | 404/409/422 PARTIAL; 503 MISSING; redacción PARTIAL | `StatusPages` | n/a | PARTIAL |
| Idempotencia | ticket/reward WORKING | — | — | — | cliente tickets BROKEN; accesos/import/general MISSING | desigual | parcial | BROKEN |
| Paginación | rankings/foro/tester WORKING | — | — | — | usuarios PARTIAL; grandes listas MISSING | desigual | índices parciales | PARTIAL |
| Transacciones | tickets WORKING | — | — | — | preguntas/import/reportes BROKEN; roles BROKEN; orden/acceso PARTIAL | RPC insuficientes | gaps | BROKEN |

## CONTENT

- Especialidades, comunidades y etiquetas tienen rutas V1 reales. Falta detalle dedicado de especialidad y la auditoría no cubre de forma uniforme altas/ediciones de todos los catálogos.
- `createPregunta` llama a `admin_create_question`; `replaceOpcionesAndSolucion` llama a `admin_replace_question_options`. La lista de RPC verificada en vivo no contiene ninguna. Por tanto, crear preguntas y editar sus opciones/solución está roto contra la base viva.
- La edición de pregunta reparte cambios entre `preguntas`, solución, vínculo de examen y recursos sin una transacción común.
- Reordenar preguntas ejecuta un PATCH por relación: un fallo intermedio deja orden parcial.
- El repositorio V1 de recursos guarda `localPathOrUrl` como `url_externa`; no sube bytes. Para imágenes/documentos puede persistir una ruta local que ningún consumidor remoto puede abrir. No existe reorder de recursos.
- La publicación de importación itera staging y llama al alta rota de preguntas. Además, una pregunta abarca cuatro tablas y no hay rollback global ni detección activa de duplicados. La candidata `content_import_atomicity_candidate_v1/002` es aditiva y correcta como dirección, pero sigue sin aplicar.

### EXAMS_P0 / P1 / P2

- `EXAMS_P0`: ninguno demostrado en el CRUD de la entidad examen. Sí existe un P0 transversal de preguntas que impide incorporar contenido nuevo a exámenes; no autoriza tocar la UI cerrada.
- `EXAMS_P1`: reordenación no atómica; auditoría incompleta en update/portada; bucket, políticas y mutaciones con JWT real no verificadas; creación/publicación no cubierta por E2E live no destructivo.
- `EXAMS_P2`: unificar semántica de archivo, paginación/búsqueda y eventual programación futura, solo tras decisión de producto.

## USERS

- Backend lista con `limit/offset/total`, pero `UsersApi` no envía paginación ni conserva `total`; carga una sola página y `observeUsers` convierte cualquier error en lista vacía.
- PATCH solo persiste `nombre_mostrar`; `email` e `isActive` del DTO se ignoran. Por eso activar/desactivar no funciona realmente.
- La sincronización de roles llama `admin_sync_roles`, ausente de la lista RPC live. La edición de perfil y roles tampoco sería atómica aunque existiera.
- Rankings es correctamente solo lectura. Los estados sin filas se comunican como desconocidos/no provisionados en vez de inventar datos.
- Accesos resuelve usuario y examen por texto, no por UUID estable; ignora `isActive`, fuerza `valido_hasta=null` y carece de revocación/historial. Es vulnerable a nombres ambiguos y no satisface caducidad.

## MONETIZATION

- `productos_comerciales` + `tarifas_producto_comercial` + `planes_suscripcion` + `beneficios_plan` forman el contrato comercial canónico servido a app por `/api/v1/app/commercial/catalog`.
- `paquetes_opositickets` alimenta exclusivamente `/api/v1/admin/market/packs`; no se consulta en el catálogo App V1. Mantener ambos como fuentes editables produciría drift.
- Planes, catálogo y Market tienen validaciones útiles y dinero en céntimos. Faltan borrados/archivos explícitos en varios hijos y paginación, aunque son catálogos pequeños.
- El endpoint/RPC canónico de tickets exige clave de idempotencia y es atómico. `KtorAdminOperationsRepositoryImpl` envía un DTO sin esa clave, de modo que la pantalla principal de OposiTickets recibe 422. El flujo rápido de moderación sí genera una clave estable.
- Existe un posible overload antiguo de cuatro argumentos de `admin_grant_tickets`; no se ha verificado ni eliminado.

### PACKS_OT_CANONICALITY

Decisión recomendada: `productos_comerciales` debe seguir siendo la única identidad comercial consumible; un pack OT es un producto con `tipo_producto=pack_ot`, tarifas y cantidad de tickets validada. `paquetes_opositickets` debe convertirse mediante migración aditiva en proyección/compatibilidad o quedar deprecated/read-only, nunca sincronizarse mediante dobles escrituras de aplicación. No borrar datos hasta inventariar consumidores externos. El consumo real por OposiBot no está verificado: el repositorio auditado no demuestra llamadas activas al App V1.

## COMMUNITY

- Foro/OposiRed es de supervisión y lectura por diseño; usa cursor estable. Faltan endpoint canónico de categorías y búsqueda server-side completa.
- Resolver un reporte inserta acción, opcionalmente concede tickets y después actualiza el reporte. No es atómico. Si la recompensa se confirma y el PATCH final falla, queda un reporte pendiente ya recompensado; el grant no duplica saldo, pero la acción de moderación sí puede duplicarse al reintentar.
- La acción llamada «ocultar» no modifica `publicaciones_oposired` ni `comentarios_oposired`; solo resuelve el reporte. Es una promesa funcional rota.
- `warnUser` únicamente escribe auditoría. No notifica, restringe ni bloquea al usuario.
- OposiTester actualiza estado y puede sobrescribir `descripcion` con la nota; no registra resolutor ni un timestamp de resolución específico.

## SUPPORT

- La lista construye el último mensaje cargando solo los 1000 mensajes globales más recientes; conversaciones antiguas pueden mostrar un último mensaje vacío o incorrecto.
- No hay paginación de mensajes, asignación persistente, cambio de estado, cierre/reapertura ni audit trail.
- El remitente se elige usando el texto `assignee` o, si falla, el primer perfil de la base, no el `sub` del JWT. Permite atribución falsa.
- `internal` se ignora y todos los mensajes se insertan con `es_staff=true`; una respuesta normal y una nota interna son indistinguibles.

## ANALYTICS

Las seis secciones existen y leen datos reales mediante Ktor/service role. Distinguen `0`, `null`, fallo de lectura y `notInstrumented`, lo cual es correcto. El estado global es PARTIAL porque faltan read models/contadores para varias métricas de producto, dinero, retención y atribución. No deben sustituirse por ceros ni aproximaciones. Dashboard aplica el mismo principio, pero aún marca como no disponibles usuarios activos 30 días, nota media e intentos/día.

## PUBLICATION

### NEWS_LEGACY

La superficie actual es `/content/noticias` y `/admin/news-types`. Usa tablas singulares numéricas (`noticia`, `tipo_noticia`), codifica las capas dentro de `texto` con un prefijo privado y crea en runtime un bucket público `news-assets`. La base viva observada no contiene `noticia`, por lo que la ruta devuelve 500. Tampoco hay auditoría canónica de publicación ni una política de ciclo de vida de assets.

### Propuesta aditiva NEWS V1

1. Añadir `/api/v1/admin/noticias` con GET paginado/filtrado y POST; `/noticias/{id}` con GET/PATCH y archivo lógico; acciones `/publish`, `/unpublish`, `/schedule`.
2. Añadir `/api/v1/admin/noticias/tipos` con catálogo versionado y archivo lógico.
3. Añadir `/api/v1/admin/noticias/{id}/assets` multipart y DELETE de vínculo/asset bajo política explícita.
4. Usar UUID, `estado` canónico (`draft`, `scheduled`, `published`, `archived`), `publicar_desde`, `published_at`, `version` para control optimista y tablas separadas para capas/assets. No esconder JSON estructurado dentro de `texto`.
5. Mantener adaptadores legacy durante la transición; no borrar tablas, rutas ni datos. Migrar/copiar con reconciliación y conteos antes de cambiar consumidores.
6. Resolver antes si el bucket debe ser público o privado con URLs firmadas; la elección actual pública no debe heredarse por accidente.

## SYSTEM

- Roles V1 solo lista. Crear/editar no existe y asignar depende de RPC ausente. Tampoco hay modelo de permisos por acción.
- Auditoría se escribe best-effort, traga errores y no incluye `usuario_id` del JWT. No cubre todas las escrituras sensibles y no ofrece filtros/paginación adecuados.
- Estado de sistema comprueba Ktor/Supabase y representa `unknown`, pero el cliente inyecta `NotConnectedSystemStatusRepository`; Storage y dependencias externas no se prueban.
- Ajustes deben seguir locales. El endpoint server estático no debe convertirse en preferencias remotas sin decisión explícita.

## AUTHORIZATION

- Todas las superficies montadas bajo `gateway-auth` pasan por el guard global. JWT requiere rol `admin` o `superadmin`.
- No existe autorización por acción. `moderatorOrAdmin` está definido, pero no se usa para las rutas de moderación; un moderador no puede entrar al árbol Admin V1.
- El token estático se restringe a desarrollo/test, loopback y prefijos legacy, pero esos prefijos incluyen rutas destructivas de roles/usuarios/reinicio. En un túnel local el peer puede aparentar loopback.
- El gateway token compartido está compilado en el cliente Android según el riesgo registrado. Debe retirarse antes de producción.
- `service_role` permanece en servidor y no se expone directamente al cliente, lo cual es correcto.

## AUDITABILITY

Estado BROKEN para exigencias de trazabilidad: actor nulo en la mayoría de acciones, cobertura desigual, ausencia de before/after, IP/request-id y filtros, y escritura best-effort. Debe definirse qué acciones económicas/roles/publicación fallan de forma cerrada si la auditoría no puede persistir y cuáles pueden seguir con outbox/reintento.

## IDEMPOTENCY

- WORKING: `admin_grant_tickets`; recompensa de reporte usa clave estable por reporte.
- BROKEN: adapter principal de ajuste de tickets omite la clave obligatoria.
- MISSING/PARTIAL: accesos, importaciones, acciones de moderación, publicaciones y otras escrituras sensibles no tienen clave ni tabla de resultado repetible.

## PAGINATION

WORKING en rankings, foro y OposiTester. PARTIAL en usuarios. MISSING en preguntas, tickets, reportes, soporte, auditoría y noticias. Varias lecturas tienen límites fijos (`100`, `200`, `1000`) sin cursor ni señal de truncamiento.

## ERROR_MODEL

Existe `StatusPages`, correlación por request-id y redacción parcial. 400/401/403 y varios 409/422 de dominio están modelados. Sin embargo, muchos «no encontrado» lanzan `IllegalStateException` y terminan como 500; fallos de Supabase también caen en 500 genérico en vez de 503; el cliente reduce respuestas a excepciones de texto. Falta normalizar `INVALID_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `CONFLICT`, `UNPROCESSABLE`, `INTERNAL_ERROR`, `DEPENDENCY_UNAVAILABLE` con DTO estable, detalles seguros y retryability.

## SCHEMA_GAPS

- RPC live ausentes: `admin_create_question`, `admin_replace_question_options`, `admin_sync_roles`.
- Importación atómica/duplicados sin RPC aplicada.
- Sin transacción única para resolver reporte + acción + recompensa + ocultación real.
- Recursos V1 sin asset/storage contract, ordenación y política de borrado.
- Accesos sin identificadores de entrada estables, revocación, caducidad e histórico explícitos.
- Soporte sin asignación, notas internas separadas, actor confiable y lifecycle completo.
- Roles sin permisos granulares.
- Noticias canónicas ausentes y tablas legacy ausentes live.
- 20 tablas con RLS activo pero sin policies en el último inventario live, incluidas `productos_comerciales`, `planes_suscripcion`, `beneficios_plan`, `promociones`, `reglas_popup_comercial` y otras. El backend con service role las lee, pero el contrato de acceso directo no está cerrado.
- No hay migración global única capaz de reconstruir todo el esquema; persiste drift documental/canónico.

## MIGRATIONS_REQUIRED

Orden propuesto, siempre con preflight, rollback/compensación documentada y aplicación humana:

1. `admin_content_atomic_v1`: desplegar/ajustar RPC de crear pregunta, reemplazar opciones y publicar lote con esquema live; constraints de exactamente una correcta, orden único por examen/pregunta y duplicados a revisión.
2. `admin_roles_atomic_v1`: `admin_sync_roles` transaccional, solo service role, validación de usuario/rol y protección de último superadmin.
3. `admin_moderation_resolution_v1`: RPC única e idempotente para acción, mutación real de contenido, recompensa y cierre de reporte.
4. `admin_audit_actor_v1`: actor/request-id/before-after seguro, índices y estrategia fail-closed u outbox para acciones sensibles.
5. `admin_resources_storage_v1`: tablas/constraints de assets, orden y lifecycle; buckets/policies tras decisión de privacidad.
6. `admin_access_lifecycle_v1`: grant/revoke/expiry/history con idempotency key e identificadores UUID.
7. `admin_support_workflow_v1`: assignee, estado, closed/resolved timestamps, mensajes internos y actor.
8. `admin_news_v1`: esquema UUID aditivo, tipos/capas/assets/versionado y bridge legacy.
9. `commercial_pack_convergence_v1`: convertir packs OT en productos comerciales o proyección, nunca doble write; reconciliar antes de deprecar.
10. `admin_read_models_v1`: agregados analíticos e índices/paginación faltantes.
11. `rls_policy_closure_v1`: decidir por tabla lectura directa o solo gateway; añadir policies mínimas sin abrir escrituras.

## P0

1. Restaurar creación/edición/importación de preguntas con RPC live y atomicidad; hoy el flujo principal está roto.
2. Corregir contrato de tickets para enviar idempotency key y probar retry/conflict E2E.
3. Hacer atómica e idempotente la resolución de reportes y separar «resolver» de «ocultar» real.
4. Restaurar sincronización de roles mediante RPC segura y proteger último superadmin.
5. Eliminar el token estático compartido del cliente y reducir/retirar mutaciones legacy accesibles con ese token.

## P1

Recursos binarios y orden; acceso grant/revoke/expiry; actor/cobertura de auditoría; workflow de soporte; noticias V1; activación real de usuarios; error model; paginación de listas grandes; permisos por acción; RLS sin policies; read models analíticos; conexión real de estado del sistema; verificación live no destructiva con JWT admin.

## P2

Deprecación de aliases legacy después de medir consumidores; homogeneización de archivos/bajas lógicas; limpieza documental; paginación de catálogos pequeños; programación futura de exámenes/noticias que dependa de decisiones aún no cerradas.

## PRODUCT_DECISIONS_REQUIRED

1. Confirmar `productos_comerciales` como única fuente canónica y destino de packs OT.
2. Definir tabla/DTO/consumidores y privacidad de assets para News V1.
3. Aprobar matriz rol × acción, incluido alcance real de `moderator`.
4. Definir ciclo de vida del usuario: desactivar perfil, ban, borrar Auth, anonimizar y protección de admins.
5. Definir semántica de acceso: compra/suscripción/manual, prioridad, revocación, caducidad e histórico.
6. Definir acciones reales de moderación y vocabulario de estado de publicaciones/comentarios.
7. Definir nota interna frente a respuesta visible y workflow/asignación de soporte.
8. Definir auditoría fail-closed/outbox, retención, redacción y acceso.
9. Confirmar política de duplicados de importación; recomendación actual: marcar para revisión y abortar, nunca auto-reemplazar.
10. Confirmar archivo lógico frente a borrado físico por dominio.

## TESTS AND EVIDENCE

- `:backend:ktor:test --no-daemon`: 280 tests, 0 failures, 0 errors, 0 skipped.
- `:data:jvmTest --no-daemon`: 31 tests, 0 failures, 0 errors, 0 skipped.
- Los tests son locales/mocked y no certifican Supabase live. La cobertura es fuerte en auth, validadores, analítica, rankings, paginación base y observabilidad; es insuficiente en CRUD V1 end-to-end de preguntas, recursos, usuarios, soporte, noticias, roles y transacciones multi-paso.
- Evidencia runtime documental previa: lecturas admin de dashboard/especialidades/exámenes/preguntas/reportes con JWT real 200; `/content/noticias` y tienda legacy 500 por tablas ausentes; RPC live canónicas observadas `record_answer`, `record_event`, `admin_grant_tickets`.
- No se hicieron writes, uploads, deletes, grants, role changes ni migraciones contra producción.

## CHECKPOINT

Fase A completada. La Fase B no debe comenzar hasta que el usuario apruebe explícitamente este diagnóstico, el lote P0 y las decisiones que bloquean esquema. Todo permanece local; no hay commit, push, PR ni staging.
