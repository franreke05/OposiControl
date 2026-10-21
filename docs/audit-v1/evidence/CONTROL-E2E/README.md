# Control E2E: 12 recorridos del panel (2026-10-20)

## Qué se probó y cómo

- **Capa de datos → Ktor real → PG17 real** (`ControlJourneysE2ETest`, rama del panel `feature/december-v1-control-panel` @ `c655116`, `data/src/jvmTest/.../e2e/`). Son los repositorios reales del panel (`KtorControlV1RepositoryImpl`, `KtorAdminV1ContentRepositoryImpl`, `KtorAdminOperationsRepositoryImpl`, `KtorCommercialCatalogRepositoryImpl`, `AuditRepositoryImpl`, `KtorSystemStatusRepositoryImpl`) con el cliente HTTP de producción (`ApiClientFactory`) y una sesión con un JWT ES256 local. Cada recorrido comprueba la fila en la base y la de `auditoria_log` (con el actor sacado del JWT), además de los errores y de la matriz de roles.
- **No es una prueba de interfaz.** Las pruebas de interfaz (Compose desktop) se cuentan aparte en CURRENT_STATE.
- Servidor: árbol E7 `cb50ee3` (develop + panel + servidor Bot V1, el plan de integración v2) copiado con `git archive` y con la corrección de especialidades aplicada. Ktor `SERVER_PROFILE=control` en :18190 y `bot` en :18189 (lado usuario: pedido sandbox, webhook firmado, publicación en el foro).
- Stack **aislado** `oposi-control-e2e`: copia de `ops/local-exam` con proyecto Compose, puertos (56321 pasarela, 56432 PG17, 19999 stub de GoTrue), red y volúmenes propios (`ctl_e2e_pg`, `ctl_e2e_storage`). Imagen PG17 `supabase/postgres 17.6.1.171`, la misma del gate. Datos sintéticos; sin MAIN; el QA de Bot (`oposi-exam-local`) no se tocó. Diferencias exactas con `cb50ee3`: `stack-copy-vs-cb50ee3.diff`.
- Para reproducirlo: `CONTROL_E2E_LOCAL=<.local del stack> ./gradlew :data:jvmTest --tests "*.ControlJourneysE2ETest"`. Sin la variable, cada prueba imprime `E2E SKIPPED` y no hace nada (comprobado con `--rerun`: 12 SKIPPED, 0 RUN).

## Resultado: 12/12 ejecutados y en verde (`ControlJourneysE2ETest-2026-10-20.xml`, 12 × `E2E RUN`)

| # | Recorrido | Éxito verificado en la base y la auditoría | Errores y roles verificados |
|---|---|---|---|
| 1 | Acceso y permisos | admin y superadmin: sesión con sus roles | admin bloqueado 403, usuario 403, moderador 403, sin token 401; el bloqueado no crea examen (sin fila nueva) |
| 2 | Gestión de exámenes | fila con comunidad, especialidad y borrador; `examen_creado` con actor = JWT; la edición persiste y queda auditada (2 filas) | comunidad inexistente rechazada, sin fila |
| 3 | Publicación y estados | publicar (`publicado_en` sellado, `examen_publicado`), despublicar (`examen_despublicado`), archivar (`activo=false`, `examen_archivado`) | publicar sin preguntas rechazado (sigue en borrador); el usuario no publica |
| 4 | Anuladas y reserva | `preguntas_examen.anulada` + motivo + auditoría; `es_reserva`/`orden_reserva` | reserva con orden 0 rechazada sin cambios; el usuario no anula |
| 5 | Penalizaciones y reglas | `regla_anuladas`, `penalizacion_num/den`, auditoría `examen_reglas*` y lectura de la auditoría de reglas | denominador 0 rechazado, sin cambios |
| 6 | Importación | lote → 3 preguntas vinculadas, `total_preguntas`, `lote_importado` | el usuario no importa; los duplicados entre exámenes se detectan (`import_duplicates_unresolved`) |
| 7 | Precios y packs | `precio_tickets`/`comprable`/`dias` + `examen_economia_modificada`; pack `productos_comerciales` (`pack_tickets`) + `producto_comercial_guardado` | precio negativo, código duplicado y usuario: rechazados |
| 8 | Pedidos | pedido sandbox con importe erróneo → `en_revision` (Bot); contador, lista y detalle en Control; nota (`pedido_nota_interna`); acreditar → `pagada` y +50 OT una sola vez (`pedido_resuelto`); repetir = `REPLAY` | el usuario no resuelve |
| 9 | Reembolsos | `payment.refunded` sobre un pedido acreditado → `reembolsada` y −50 OT, sin `refund_case` (automático, Bot); el panel lo enseña; reembolsar otra vez = `REPLAY` | `order_not_credited` sobre un pedido nunca acreditado; `order_not_in_review` |
| 10 | Accesos | conceder → `contenido_desbloqueado_usuario` vigente + `acceso_examen_concedido`; retirar → no vigente + `acceso_examen_retirado` | solo sobre exámenes publicados; el usuario no se autoconcede |
| 11 | Moderación del foro | canal creado desde el panel (`foro_canal_creado`); el post premoderado entra en la cola; ocultar → `oculta` + auditoría; bloqueo de patrón añadir/quitar | ocultar sin motivo rechazado; el usuario no modera |
| 12 | Auditoría | el movimiento aparece filtrado por acción con su actor; detalle legible | el usuario no lee la auditoría |

## Hallazgos de este ciclo

- **CP-E2E-01 (P2, CORREGIDO en `c655116`).** `GET /api/v1/admin/especialidades` devolvía 502 en cuanto una especialidad tenía `descripcion` NULL (la columna es nullable y el DTO exigía String). El panel daba por fallido el guardado de un examen que el servidor YA había creado, así que reintentar producía duplicados. Ahora se lee con `NullTextAsEmptySerializer`; el contrato hacia el panel no cambia. Prueba: `AdminEspecialidadNullDescriptionTest` 2/2. NOT_VERIFIED: si alguna especialidad de MAIN tiene NULL (no estaba en el alcance de lectura autorizado).
- **CP-E2E-02 → CONFIRMED_PRODUCT_DECISION (propietario, 2026-10-20) e IMPLEMENTADO en local.** El moderador entra en el panel **solo para moderar el foro**. En Ktor (`AdminAuthorizationGuard`), la lista cerrada `MODERATOR_ADMIN_PATHS` (`/sesion`, `/foro/cola`, `/foro/moderar`, `GET /foro/restricciones`) abre esas rutas al rol `moderador` con cuenta activa (`hasActiveModeratorRole`). Canales, bloqueos, levantar restricciones (la RPC exige admin en SQL) y todo lo demás siguen siendo solo de admin. En el panel, `panelAccessFor(GET /sesion)` muestra al moderador un panel reducido con solo la cola (`ForumModerationQueueScreen`); un 403 en la sesión se ve como «Sin acceso al panel» con el texto del servidor. Pruebas: `AdminModeratorForumAccessTest` 5/5 (decisión pura y plugin sobre rutas reales), `PanelAccessTest` 4/4, `ForumModerationQueueUiTest` 1/1 y E2E J01/J11 con el moderador real: modera, la auditoría lo registra como actor, y canales, bloqueos, pedidos y exámenes le devuelven 403.
- **CP-E2E-03 → CONFIRMED_PRODUCT_DECISION (propietario, 2026-10-20) e IMPLEMENTADO en local:** anular una pregunta oficial exige el motivo del tribunal. Son tres entradas en el servidor, todas con 422 `ANNULMENT_REASON_REQUIRED`: (1) `PUT preguntas-oficiales` (`OfficialQuestionFlags.parse`); (2) aprobar en la revisión una fila de staging anulada sin motivo (`updateStaging`); (3) publicar un lote con filas anuladas sin motivo (`importarLote`, antes de llamar a la RPC y diciendo las posiciones). El lote se puede seguir creando con anuladas sin motivo, porque el parser las detecta aunque el PDF no diga por qué. En el panel, `OfficialQuestionValidator` pide el motivo en el campo y el preflight de importación bloquea con `ANNULMENT_REASON_MISSING`. La RPC SQL (`admin_set_exam_question_flags`, E7) no se ha tocado, porque Ktor es su único llamador. Pruebas: `AdminAnnulmentReasonRequiredTest` 4/4, `AdminControlOfficialQuestionsTest` 13/13 (fixtures con motivo), VM y preflight de importación, y E2E J04 (sin motivo da 422 y la base no cambia).
- **CP-E2E-04 (Info, ya conocido).** Los packs de Market (`paquetes_opositickets`) no están en ningún DDL del repo y la app no los lee (PRODUCT_DECISIONS_PENDING §15.1). J07 prueba la vía canónica (`productos_comerciales`).
- **CP-E2E-05 (P3, herramientas de Bot).** `ops/local-exam` en `cb50ee3` no levanta desde cero: falla el orden de las migraciones (100300 y 100900 antes de sus overlays), el seed multifila del libro (WALLET_INCONSISTENT) y 102200 no es idempotente. Además, varios scripts fijan el contenedor `oposi-exam-local-db-1` y los puertos 8089/8090/9999, así que una copia con otro nombre de proyecto seguiría escribiendo en el QA de Bot. Comunicado a Bot el 2026-10-20; arreglado solo en la copia aislada.
- La inventaria previa daba «`updateExamen` sin auditoría» como hueco: **falso**, la edición deja filas de auditoría (J02).

## Latencias (P3)

- `latency-run2.txt` (válida): 100 lecturas secuenciales por operación y 50 escrituras auditadas contra :18190, sin builds propios en marcha, 80 exámenes. **Lecturas: p50 de 11 a 34 ms y p95 de 17 a 67 ms** (la peor es `GET /pedidos?estado=en_revision`, con p95 de 67 ms). **Escrituras: `PUT reglas-puntuacion` p95 63 ms y `POST pedidos/{id}/nota` p95 55 ms.** No hay ningún cuello demostrado, así que no se optimiza nada. Scripts: `latency.mjs` y `writes.mjs`.
- `latency-run1-noisy.txt` (descartada): con dos builds de Gradle en paralelo; el p95 de 237 ms de `GET /examenes` era ruido (39 ms en reposo).
- Límite: 1 operador y una base local con volumen sintético pequeño; no mide la latencia de red a Supabase cloud ni la concurrencia.
- El árbol E7 `cb50ee3` es anterior al límite de mutaciones admin de develop (`a301ea7`/`2cab55b`): 100 escrituras en menos de un minuto no dieron 429. La integración debe llevar develop actual (plan v2); el límite ya se verificó con Ktor real en SEC-V1-02.
- FK `productos_comerciales_plan_fk` sin índice: el seq scan tarda 1,4 ms con 5.016 filas (unas 300 veces el catálogo). **No se propone índice.**

## Pruebas de interfaz (panel `95b5b1e`)

17 pruebas de interfaz de escritorio con las pantallas y los ViewModels REALES y repositorios falsos (no hay red): `AuthLoginUiTest` 4, `ExamPublicationFlowUiTest` 4, `OrdersResolveUiTest` 5 (el primer jvmTest de control-v1) y `AuditScreenUiTest` 4. Corrección encontrada: un login con 403 (no admin o admin bloqueado) enseñaba un texto genérico; ahora muestra el del servidor. Ejecuciones: auth 42/0, exams 334/0, control-v1 36/0, audit 35/0.

## Interfaz contra el backend real (panel `a06601a`), 2026-10-20

`ControlPanelUiE2ETest` (composeApp jvmTest) monta **la app entera**: el grafo Koin de producción, AppRoot, pantallas, ViewModels y repositorios reales. Funciona contra el stack aislado: login real por la pantalla de acceso (GoTrue local del stack, contraseñas de usuarios sintéticos solo en `.local/`), Ktor `control` real y PG17. Solo se sustituye la base local de escritorio por una en memoria, así que `%APPDATA%\OposiControl` no se toca. **8/8 en verde** (`ControlPanelUiE2ETest-2026-10-20.xml`, capturas en `ui-shots/`):

| Flujo | Qué se hace en la interfaz | Qué se comprueba en PG17 |
|---|---|---|
| Login admin | email y contraseña → «Entrar» → Dashboard con datos reales | — |
| Contraseña incorrecta | se queda en el login con el error | no entra |
| Admin bloqueado | «Sin acceso al panel» + cerrar sesión, sin menú | — |
| Moderador | panel reducido «Moderación del foro», sin menú de admin ni canales | — |
| Publicar | Exámenes → buscar → abrir → Publicación → Publicar ahora → confirmar | `publicado_en` + `examen_publicado` con actor admin |
| Pedido en revisión | Catálogo → Pedidos → abrir → Acreditar + motivo → Resolver → confirmar | `pagada`, +50 OT una vez, `pedido_resuelto` con actor admin |
| Auditoría | Sistema → pestaña Auditoría → movimientos con «Admin local» | — |
| Moderador rechaza | cola → Rechazar → motivo → confirmar | `oculta` + auditoría con el MODERADOR como actor |

Durante las pruebas de interfaz salieron:
- **El dashboard «Usuarios activos: No se pudo leer» era un hueco del stack, no del panel.** Las RPC del dashboard (20260917084214/090229, aplicadas en MAIN) no estaban en el subconjunto de forma del stack. Ya están aplicadas al stack y añadidas a su `up.mjs`.
- **Tres escrituras sin auditoría, corregidas:** `examen_actualizado`, `lote_creado` y `staging_revisado`. Las pruebas son `AdminImportAndExamEditAuditTest` 3/3 y E2E J02/J06.
- **Falsa alarma revisada:** los `examen_reglas_puntuacion_*` al crear un examen oficial son el valor por defecto documentado (penalización racional), no una sobrescritura al editar.

Develop: el gate PG17 falla en el paso de manifiesto por DEBT-156b (preexistente: las `*_security_*` están fuera de `PENDING_MIGRATIONS.txt` por diseño). En una copia que solo excluye esas migraciones del paso de manifiesto, el resto del gate da `db-pg17-gate OK` (`develop-pg17-gate-copy-tail.txt`). El script compartido no se ha tocado porque Bot lo modifica en su rama. `verify-ops-config.sh`: OK.
(Superado el mismo día: DEBT-156b se corrigió de acuerdo con Bot en `a8928f8` y el gate de develop pasa en verde, ver `dcb279e`.)

## Interfaz contra el backend real: recorridos 2, 4, 5, 6, 7 y 10 (panel `d277992`), 2026-10-21

**15/15 en verde** (`ControlPanelUiE2ETest-2026-10-21.xml`). Los 8 flujos anteriores más 7 nuevos, con la app entera contra el stack aislado. Los E2E de datos siguen en **12/12** (`ControlJourneysE2ETest-2026-10-21.xml`). La suite se ejecutó varias veces seguidas sobre los mismos datos; cada prueba reutiliza el examen que renombró en una pasada anterior.

| # | Flujo | Qué se hace en la interfaz | Qué se comprueba en PG17 |
|---|---|---|---|
| 2 | Editar examen | Exámenes → buscar → abrir → cambiar el título → «Guardar cambios» | título nuevo y una fila `examen_actualizado` más, con actor admin |
| 7 | Precio | pestaña «Acceso y monetización» → Premium → «Poner un precio» → 90 → guardar | `tipo_acceso = premium`, `precio_tickets = 90` |
| 5 | Reglas de puntuación | Configuración → apagar la penalización racional → fracción 1/3 → «Guardar reglas» | `false/1/3` y al menos 2 filas `examen_reglas*` |
| 4 | Anular con motivo | Configuración → «Anuladas y reservas» → pregunta → «Anulada por el tribunal» → motivo → «Guardar marcas» | `anulada = true` y el motivo exacto |
| 4 | Anular sin reservas suficientes | igual, en un examen con 1 anulada y 1 reserva | la interfaz muestra el rechazo del servidor y la pregunta sigue sin anular |
| 6 | Importar PDF | Importación → examen destino → «Seleccionar PDF» (`rafaga_valid.pdf`) → «Subir y analizar» → vincular duplicados → «Publicar lote» | lote creado (`lote_creado`), preguntas en el examen y `lote_importado` con actor admin |
| 10 | Conceder acceso | Usuarios → Accesos → «Conceder acceso» → buscar `q1_saldo_alto` → elegir examen → conceder | una concesión vigente más y `acceso_examen_concedido` con actor admin |

Capturas en `ui-shots/` (`ui-edit-exam-saved`, `ui-price-saved`, `ui-grading-form`, `ui-annul-saved`, `ui-annul-rejected-final`, `ui-import-*`, `ui-access-*`).

Lo que salió al probar con la interfaz:
- **CP-E2E-06 (P2, CORREGIDO).** Guardar las reglas de puntuación de un examen con la regla de anuladas derivada (null) daba 400 «El cuerpo de la petición no es válido». El `Json` del cliente (`explicitNulls = false`) omitía `reglaAnuladas` y el servidor exige la clave. Ahora el cuerpo viaja como `JsonObject` con el null explícito. Prueba de regresión con el `Json` de producción (`KtorControlV1RepositoryImplTest`, 12/12). Era la única ruta del servidor que exige la presencia de una clave anulable.
- **CP-E2E-07 (P3, CORREGIDO).** En el espacio de trabajo del examen, las secciones de reglas y de anuladas/reservas se dibujaban unas encima de otras (`AsyncStatePane` pinta en un `Box`), y el «Denominador» quedaba en vertical. Se corrigió con una `Column` y campos de 240 dp.
- **CP-E2E-08 (P3, CORREGIDO).** Los textos del motivo de anulación seguían diciendo «Opcional» (espacio de trabajo) y «(opcional)» (revisión de importación) después de la decisión del 2026-10-20. Ahora dicen «Obligatorio».
- **CP-E2E-09 (P2, CORREGIDO, petición del propietario 2026-10-21).** En las preguntas inmersivas no se podía comprobar qué archivo se había subido. Cada recurso muestra ahora su formato (JPEG, MP3, MP4…) y un botón «Abrir» que entrega la URL firmada al navegador o reproductor del sistema. Solo se abre una URL del Storage propio (`MediaUrlPolicy.isOpenableStorageObject`: mismo host, ruta `object/sign` u `object/public`, sin traversal); la política de carga dentro de la app no cambia. Pruebas: `MediaUrlPolicyTest` (2 nuevas; core:common 15/0) y `QuestionsPresentationTest` (1 nueva; questions 85/0). PENDIENTE (decisión): el nombre original del archivo no se guarda en ningún sitio (el servidor nombra el objeto con el id del recurso); mostrarlo exige una columna nueva.
- **Comportamiento correcto confirmado:** al reimportar el mismo PDF, la revisión marca las 3 filas como duplicadas y no deja publicar hasta vincularlas u omitirlas.
- **Herramienta de pruebas:** el SQL de los E2E pasa por JDBC al puerto del stack aislado. Con `docker exec`, Windows abría una ventana de consola en cada consulta.
- **Entorno:** el 2026-10-21 Docker se reinició (exit 255 en todos los contenedores). El stack aislado se volvió a levantar arrancando solo sus contenedores (los volúmenes estaban intactos). Los JWT sintéticos caducados se regeneraron con la misma clave local. El QA de Bot no se tocó.
