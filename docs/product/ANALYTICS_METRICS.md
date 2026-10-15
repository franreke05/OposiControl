# Métricas de Analítica y Dashboard V2 — catálogo de definiciones

- **Fecha de evidencia:** 2026-10-15 (lectura estática del worktree OposiControl-E5, HEAD `c725404`).
- **Dónde se calculan:** todos los valores se calculan en el backend Ktor con service role contra Supabase (PostgREST `Prefer: count=exact` + `Range: 0-0`, o RPC). El cliente sólo mapea y formatea.
- **Runtime LIVE:** `NOT_VERIFIED`. Ningún valor de este documento se ha leído contra Supabase; las definiciones salen del código.
- **Rutas de archivo abreviadas:** `AA` = `backend/ktor/src/main/kotlin/com/oposicontrol/backend/AdminAnalytics.kt`; `ADO` = `backend/ktor/src/main/kotlin/com/oposicontrol/backend/AdminDashboardOverview.kt`.
- **STATUS:** `REAL` = existe consulta; a runtime puede llegar como NO_DATA si la lectura upstream falla (`countOrNull` → `null`, `AA:1200`, `ADO:956`) o si una tasa tiene denominador 0. `NOT_INSTRUMENTED` = el backend la declara hueco estructural (`availability.gap`), siempre sin número.
- **Ventanas:** "30d" en Analytics = `now() - 30 días` UTC truncado a segundos (`AA:1300`). "Activos 1/7/30d" de Retención usan fecha UTC del día (`AA:823`). "Histórico" = sin filtro temporal; foto del instante de la petición.

## 1. Analítica de producto (`/api/v1/admin/analytics/{seccion}`)

Pantalla: `features/analytics/.../ProductAnalyticsScreen.kt`; etiquetas: `ProductAnalyticsLabels.kt:64`. Orden de filas = orden de pantalla (`data/.../KtorProductAnalyticsRepositoryImpl.kt:318-506`); los huecos se añaden al final (`remainingGaps`).

### Cabecera de cobertura (todas las secciones)

| SCREEN | METRIC | SOURCE | QUERY | TIME RANGE | DEFINITION | STATUS |
|---|---|---|---|---|---|---|
| ANALYTICS (todas) | — "Con dato real" | Derivado en cliente | `ProductAnalyticsScreen.kt:143-148`; `domain/.../ProductAnalytics.kt:114` | Instante de la sección | Nº de métricas de la sección con status AVAILABLE, "de N métricas". No incluye tablas. | REAL (derivado) |
| ANALYTICS (todas) | — "Sin instrumentar" | Derivado en cliente | `ProductAnalyticsScreen.kt:149-154`; `ProductAnalytics.kt:117` | Instante | Nº de métricas NOT_INSTRUMENTED. | REAL (derivado) |
| ANALYTICS (todas) | — "No disponibles" | Derivado en cliente | `ProductAnalyticsScreen.kt:138,155-161` | Instante | `total - disponibles - sin instrumentar` (NO_DATA). Sólo se pinta en breakpoint ≥ standard. | REAL (derivado) |

### PRODUCTO (`AA:564`)

| SCREEN | METRIC | SOURCE | QUERY | TIME RANGE | DEFINITION | STATUS |
|---|---|---|---|---|---|---|
| PRODUCTO | `totalUsers` — Usuarios registrados | `perfiles_usuario` | `AA:570` | Histórico | Filas de perfiles. | REAL |
| PRODUCTO | `guestUsers` — Usuarios invitados | `perfiles_usuario` | `AA:574` | Histórico | `es_invitado = true`. | REAL |
| PRODUCTO | `onboardedUsers` — Onboarding completado | `perfiles_usuario` | `AA:578` | Histórico | `onboarding_completado = true`. | REAL |
| PRODUCTO | `onboardingCompletionRate` — % onboarding completado | Derivado | `AA:606-609` | Histórico | `onboardedUsers / totalUsers × 100`; NO_DATA si total = 0. | REAL |
| PRODUCTO | `totalStudySessions` — Sesiones de estudio | `sesiones_estudio` | `AA:590` | Histórico | Filas de sesiones. | REAL |
| PRODUCTO | `studySessionsLast30d` — Sesiones (30 días) | `sesiones_estudio` | `AA:594` | 30d (`iniciada_en`) | Sesiones iniciadas en 30 días. | REAL |
| PRODUCTO | `finishedStudySessions` — Sesiones terminadas | `sesiones_estudio` | `AA:598` | Histórico | `finalizada_en IS NOT NULL`. | REAL |
| PRODUCTO | `abandonedStudySessions` — Sesiones abandonadas | Derivado | `AA:602-605` | Histórico | `total - terminadas`, acotado a ≥ 0. | REAL |
| PRODUCTO | `studySessionCompletionRate` — % sesiones terminadas | Derivado | `AA:610-613` | Histórico | `terminadas / total × 100`. | REAL |
| PRODUCTO | `totalAppEvents` — Eventos de app | `eventos_app` | `AA:582` | Histórico | Filas de eventos. | REAL |
| PRODUCTO | `appEventsLast30d` — Eventos (30 días) | `eventos_app` | `AA:586` | 30d (`creado_en`) | Eventos recibidos en 30 días. | REAL |
| PRODUCTO | `eventsByName` — Eventos por nombre | `eventos_app` (falta GROUP BY) | `AA:615-619` | — | Falta la vista `v_eventos_app_por_nombre`. | NOT_INSTRUMENTED |
| PRODUCTO | `sessionsByPlatform` — Sesiones por plataforma | `sesiones_estudio` | `AA:620-624` | — | Falta GROUP BY `plataforma`. | NOT_INSTRUMENTED |
| PRODUCTO | `sessionsByAppVersion` — Sesiones por versión | `sesiones_estudio` | `AA:625-628` | — | Falta GROUP BY `version_app`. | NOT_INSTRUMENTED |
| PRODUCTO | `avgStudySessionSeconds` — Duración media de sesión | `sesiones_estudio` | `AA:629-632` | — | Falta AVG(`duracion_segundos`). | NOT_INSTRUMENTED |

### ESTUDIO (`AA:654`)

| SCREEN | METRIC | SOURCE | QUERY | TIME RANGE | DEFINITION | STATUS |
|---|---|---|---|---|---|---|
| ESTUDIO | `totalAttempts` — Intentos de examen | `intentos_examen` | `AA:660` | Histórico | Todos los intentos. | REAL |
| ESTUDIO | `attemptsCompleted` — Intentos completados | `intentos_examen` | `AA:664` | Histórico | `estado = completado`. | REAL |
| ESTUDIO | `attemptsInProgress` — Intentos en curso | `intentos_examen` | `AA:668` | Histórico | `estado = en_progreso`. | REAL |
| ESTUDIO | `attemptsOtherState` — Intentos en otro estado | Derivado | `AA:672-675` | Histórico | `total - completados - en curso`, ≥ 0. | REAL |
| ESTUDIO | `attemptsLast30d` — Intentos (30 días) | `intentos_examen` | `AA:678` | 30d (`iniciado_en`) | Intentos iniciados (sin filtrar estado). | REAL |
| ESTUDIO | `attemptsFirstTime` — Primeros intentos | `intentos_examen` | `AA:682` | Histórico | `es_primer_intento_examen = true`. | REAL |
| ESTUDIO | `attemptsRanked` — Intentos que puntúan | `intentos_examen` | `AA:686` | Histórico | `cuenta_para_ranking = true`. | REAL |
| ESTUDIO | `attemptsRafaga` — Intentos ráfaga | `intentos_examen` | `AA:695` | Histórico | `tipo_examen_snapshot = rafaga`. | REAL |
| ESTUDIO | `attemptsRazonado` — Intentos razonado | `intentos_examen` | `AA:699` | Histórico | `tipo_examen_snapshot = razonado`. | REAL |
| ESTUDIO | `attemptsInmersivo` — Intentos inmersivo | `intentos_examen` | `AA:703` | Histórico | `tipo_examen_snapshot = inmersivo`. | REAL |
| ESTUDIO | `attemptsOtherMode` — Intentos de otro modo | Derivado | `AA:705-708` | Histórico | `total - (ráfaga + razonado + inmersivo)`, ≥ 0 (alias legacy). | REAL |
| ESTUDIO | `totalAnswers` — Respuestas | `respuestas_intento` | `AA:712` | Histórico | Todas las respuestas. | REAL |
| ESTUDIO | `correctAnswers` — Respuestas correctas | `respuestas_intento` | `AA:716` | Histórico | `es_correcta = true`. | REAL |
| ESTUDIO | `wrongAnswers` — Respuestas falladas | `respuestas_intento` | `AA:720` | Histórico | `es_correcta = false`. | REAL |
| ESTUDIO | `unansweredAnswers` — Sin responder | `respuestas_intento` | `AA:724` | Histórico | `es_correcta IS NULL`. | REAL |
| ESTUDIO | `globalAccuracyRate` — % de acierto global | Derivado | `AA:726-729` | Histórico | `correctas / respuestas × 100`. | REAL |
| ESTUDIO | `globalErrorRate` — % de fallo global | Derivado | `AA:730-733` | Histórico | `falladas / respuestas × 100`. | REAL |
| ESTUDIO | `firstTimeAnswers` — Primeras respuestas | `respuestas_intento` | `AA:736` | Histórico | `es_primer_intento_pregunta = true`. | REAL |
| ESTUDIO | `randomAnswers` — Respondidas al azar | `respuestas_intento` | `AA:740` | Histórico | `respondida_al_azar = true`. | REAL |
| ESTUDIO | `changedAnswers` — Respuestas cambiadas | `respuestas_intento` | `AA:744` | Histórico | `cambio_respuesta = true`. | REAL |
| ESTUDIO | `markedForReviewAnswers` — Marcadas para repasar | `respuestas_intento` | `AA:748` | Histórico | `marcada_para_repasar = true`. | REAL |
| ESTUDIO | `avgScore` — Nota media | `intentos_examen` | `AA:767-770` | — | Falta AVG(`porcentaje_acierto`). | NOT_INSTRUMENTED |
| ESTUDIO | `avgAttemptDurationSeconds` — Duración media de intento | `intentos_examen` | `AA:771-774` | — | Falta AVG(`duracion_segundos`). | NOT_INSTRUMENTED |
| ESTUDIO | `avgAnswerTimeSeconds` — Tiempo medio por respuesta | `respuestas_intento` | `AA:775-778` | — | Falta AVG(`tiempo_respuesta_segundos`). | NOT_INSTRUMENTED |
| ESTUDIO | `attemptsPerDay` — Intentos por día | `intentos_examen` | `AA:779-783` | — | Falta la vista `v_intentos_por_dia`. | NOT_INSTRUMENTED |
| ESTUDIO | `tagWeaknessRanking` — Debilidades por etiqueta | `v_usuario_debilidades_etiqueta` | `AA:784-788` | — | Falta la vista global `v_debilidades_etiqueta_global`. | NOT_INSTRUMENTED |
| ESTUDIO (tabla) | `hardestQuestions` — Preguntas más falladas | Vista `v_preguntas_dificultad_global` | `AA:755-765` | Histórico (agregado por la vista) | Top 10 por `porcentaje_fallo` desc; columnas Pregunta, Tipo, Respuestas, Fallos, % fallo, Dificultad. | REAL |

### RETENCIÓN (`AA:821`)

| SCREEN | METRIC | SOURCE | QUERY | TIME RANGE | DEFINITION | STATUS |
|---|---|---|---|---|---|---|
| RETENCION | `activeUsers1d` — Activos hoy | `rachas_usuario` | `AA:849` | `ultima_actividad_fecha ≥ hoy` (UTC) | Una fila por usuario (PK). Nota: el Dashboard V2 dice que esta tabla no tiene escritor (`ADO:724-728`). | REAL |
| RETENCION | `activeUsers7d` — Activos (7 días) | `rachas_usuario` | `AA:853` | hoy − 6 días | Igual que arriba. | REAL |
| RETENCION | `activeUsers30d` — Activos (30 días) | `rachas_usuario` | `AA:857` | hoy − 29 días | Igual que arriba; **no** es la misma definición que `activeUsers30d` del Dashboard V2. | REAL |
| RETENCION | `usersWithStreak` — Usuarios con racha | `rachas_usuario` | `AA:827` | Foto | Filas de rachas. | REAL |
| RETENCION | `usersWithActiveStreak` — Rachas vivas | `rachas_usuario` | `AA:831` | Foto | `racha_actual_dias > 0`. | REAL |
| RETENCION | `usersStreak7Plus` — Racha de 7 días o más | `rachas_usuario` | `AA:835` | Foto | `racha_actual_dias ≥ 7`. | REAL |
| RETENCION | `usersStreak30Plus` — Racha de 30 días o más | `rachas_usuario` | `AA:839` | Foto | `racha_actual_dias ≥ 30`. | REAL |
| RETENCION | `usersWithCompetitiveState` — Usuarios en competición | `estado_competitivo_usuario` | `AA:862` | Foto | Filas de estado competitivo. | REAL |
| RETENCION | `avgCurrentStreakDays` — Racha media actual | `rachas_usuario` | `AA:889-892` | — | Falta AVG(`racha_actual_dias`). | NOT_INSTRUMENTED |
| RETENCION | `avgBestStreakDays` — Mejor racha media | `rachas_usuario` | `AA:893-896` | — | Falta AVG(`mejor_racha_dias`). | NOT_INSTRUMENTED |
| RETENCION | `avgCompetitiveScore` — Nota media competitiva | `estado_competitivo_usuario` | `AA:897-900` | — | Falta AVG(`nota_media_actual`). | NOT_INSTRUMENTED |
| RETENCION | `retentionCohorts` — Retención por cohorte | — | `AA:901-905` | — | Falta la vista `v_retencion_cohortes`. | NOT_INSTRUMENTED |
| RETENCION (tabla) | `leagueDistribution` — Reparto por liga | `ligas_nivel` + `estado_competitivo_usuario` | `AA:868-887` (catálogo `AA:871`, conteo por liga `AA:879-883`) | Foto | Una fila por liga (orden `posicion`): Liga, Preguntas mínimas, Ventana, Usuarios (`liga_nivel_id = id`). Un conteo fallido se pinta "sin dato". | REAL |

### COMUNIDAD (`AA:925`)

| SCREEN | METRIC | SOURCE | QUERY | TIME RANGE | DEFINITION | STATUS |
|---|---|---|---|---|---|---|
| COMUNIDAD | `posts` — Publicaciones | `publicaciones_oposired` | `AA:928` | Histórico | Todas las publicaciones. | REAL |
| COMUNIDAD | `postsPublished` — Publicaciones publicadas | `publicaciones_oposired` | `AA:931` | Histórico | `estado = publicado`. | REAL |
| COMUNIDAD | `postsPinned` — Publicaciones fijadas | `publicaciones_oposired` | `AA:935` | Foto | `fijada = true`. | REAL |
| COMUNIDAD | `comments` — Comentarios | `comentarios_oposired` | `AA:937` | Histórico | Todos los comentarios. | REAL |
| COMUNIDAD | `reports` — Reportes de contenido | `reportes_contenido` | `AA:938` | Histórico | Todos los reportes. | REAL |
| COMUNIDAD | `reportsPending` — Reportes pendientes | `reportes_contenido` | `AA:941` | Foto | `estado = pendiente`. | REAL |
| COMUNIDAD | `reportsReviewed` — Reportes revisados | `reportes_contenido` | `AA:948` | Histórico | `revisado_en IS NOT NULL`. | REAL |
| COMUNIDAD | `reportsRewarded` — Reportes recompensados | `reportes_contenido` | `AA:952` | Histórico | `recompensa_entregada = true`. | REAL |
| COMUNIDAD | `questionFeedback` — Feedback de preguntas | `feedback_pregunta` | `AA:956` | Histórico | Todo el feedback. | REAL |
| COMUNIDAD | `questionFeedbackPending` — Feedback pendiente | `feedback_pregunta` | `AA:960` | Foto | `estado = pendiente`. | REAL |
| COMUNIDAD | `postsByCategory` — Publicaciones por categoría | `publicaciones_oposired` | `AA:963-967` | — | Falta GROUP BY `categoria_id`. | NOT_INSTRUMENTED |
| COMUNIDAD | `topContributors` — Mayores contribuidores | publicaciones + comentarios | `AA:968-971` | — | Falta GROUP BY `usuario_id`. | NOT_INSTRUMENTED |
| COMUNIDAD | `avgCommentsPerPost` — Comentarios por publicación | `comentarios_oposired` | `AA:972-975` | — | Falta AVG sobre GROUP BY. | NOT_INSTRUMENTED |
| COMUNIDAD | `medianReportResolutionHours` — Mediana de resolución de reportes | `reportes_contenido` | `AA:976-980` | — | Falta `percentile_cont`. | NOT_INSTRUMENTED |

### ECONOMÍA (`AA:1001`)

| SCREEN | METRIC | SOURCE | QUERY | TIME RANGE | DEFINITION | STATUS |
|---|---|---|---|---|---|---|
| ECONOMIA | `grossRevenueCents` — Ingreso bruto | `compras_usuario` | `AA:1080-1084` (campo `null` en `AA:1121`) | — | Falta SUM(`precio_centimos`). Analytics **no** usa la RPC `admin_dashboard_economy_totals` que sí usa el Dashboard V2. | NOT_INSTRUMENTED |
| ECONOMIA | `avgOrderValueCents` — Ticket medio | `compras_usuario` | `AA:1085-1089` (`AA:1122`) | — | Falta SUM/COUNT. | NOT_INSTRUMENTED |
| ECONOMIA | `purchases` — Compras | `compras_usuario` | `AA:1028` | Histórico | Todas las compras (cualquier estado). | REAL |
| ECONOMIA | `subscriptions` — Suscripciones | `suscripciones` | `AA:1030` | Histórico | Todas las suscripciones. | REAL |
| ECONOMIA | `subscriptionsActive` — Suscripciones activas | `suscripciones` | `AA:1036-1039` (filtro `AA:342-343`) | Ahora | `estado = activa` y (`fin_en IS NULL` o `fin_en > ahora`). | REAL |
| ECONOMIA | `subscriptionsTrial` — En periodo de prueba | `suscripciones` | `AA:1046` | Foto | `estado = periodo_prueba`. | REAL |
| ECONOMIA | `subscriptionsPaused` — Suscripciones pausadas | `suscripciones` | `AA:1050` | Foto | `estado = pausada`. | REAL |
| ECONOMIA | `subscriptionsCancelled` — Suscripciones canceladas | `suscripciones` | `AA:1054` | Foto | `estado = cancelada`. | REAL |
| ECONOMIA | `subscriptionsExpired` — Suscripciones caducadas | `suscripciones` | `AA:1058` | Foto | `estado = caducada`. | REAL |
| ECONOMIA | `subscriptionsOtherState` — Suscripciones en otro estado | Derivado | `AA:1060-1063` (insumo `AA:1043`) | Foto | `total - (activa por estado + prueba + pausada + cancelada + caducada)`, ≥ 0. | REAL |
| ECONOMIA | `subscriptionsExpiring30d` — Vencen en 30 días | `suscripciones` | `AA:1068` | `[ahora, ahora+30d)` | `estado = activa` y `fin_en` dentro de la ventana. | REAL |
| ECONOMIA | `wallets` — Carteras | `carteras_usuario` | `AA:1006` | Foto | Filas de carteras. | REAL |
| ECONOMIA | `walletTransactions` — Movimientos de cartera | `transacciones_cartera` | `AA:1009` | Histórico | Todos los movimientos. | REAL |
| ECONOMIA | `walletCredits` — Movimientos de abono | `transacciones_cartera` | `AA:1017` | Histórico | `cantidad > 0`. | REAL |
| ECONOMIA | `walletDebits` — Movimientos de cargo | `transacciones_cartera` | `AA:1021` | Histórico | `cantidad < 0`. | REAL |
| ECONOMIA | `walletNeutralMovements` — Movimientos sin importe | `transacciones_cartera` | `AA:1025` | Histórico | `cantidad = 0`. | REAL |
| ECONOMIA | `ticketsInCirculation` — OposiTickets en circulación | `carteras_usuario` | `AA:1090-1093` (`AA:1123`) | — | Falta SUM(`saldo_tickets`). | NOT_INSTRUMENTED |
| ECONOMIA | `commercialProducts` — Productos comerciales | `productos_comerciales` | `AA:1073` | Foto | Todos los productos. | REAL |
| ECONOMIA | `commercialProductsActive` — Productos activos | `productos_comerciales` | `AA:1077` | Foto | `activo = true`. | REAL |
| ECONOMIA | `revenueByProduct` — Ingreso por producto | `compras_usuario` | `AA:1094-1097` | — | Falta GROUP BY + SUM. | NOT_INSTRUMENTED |
| ECONOMIA | `purchasesByState` — Compras por estado | `compras_usuario` | `AA:1098-1102` | — | Falta GROUP BY `estado`. | NOT_INSTRUMENTED |

### MARKETING (`AA:1132`)

| SCREEN | METRIC | SOURCE | QUERY | TIME RANGE | DEFINITION | STATUS |
|---|---|---|---|---|---|---|
| MARKETING | `attributedUsers` — Usuarios con atribución | `atribucion_usuario` | `AA:1138` | Histórico | Una fila por usuario. | REAL |
| MARKETING | `unattributedUsers` — Usuarios sin atribución | Derivado (`perfiles_usuario` `AA:1135`) | `AA:1151-1154` | Histórico | `perfiles - atribuidos`, ≥ 0. | REAL |
| MARKETING | `attributionCoverageRate` — % con atribución | Derivado | `AA:1155-1158` | Histórico | `atribuidos / perfiles × 100`. | REAL |
| MARKETING | `referredUsers` — Usuarios referidos | `atribucion_usuario` | `AA:1142` | Histórico | `referido_por IS NOT NULL`. | REAL |
| MARKETING | `usersWithReferralCode` — Con código de referido | `atribucion_usuario` | `AA:1146` | Histórico | `codigo_referido IS NOT NULL`. | REAL |
| MARKETING | `usersBySource` — Usuarios por fuente | `atribucion_usuario` | `AA:1160-1164` | — | Falta GROUP BY `fuente`. | NOT_INSTRUMENTED |
| MARKETING | `usersByChannel` — Usuarios por canal | `atribucion_usuario` | `AA:1165-1168` | — | Falta GROUP BY `canal`. | NOT_INSTRUMENTED |
| MARKETING | `usersByCampaign` — Usuarios por campaña | `atribucion_usuario` | `AA:1169-1172` | — | Falta GROUP BY `campania`. | NOT_INSTRUMENTED |
| MARKETING | `topReferrers` — Mayores referidores | `atribucion_usuario` | `AA:1173-1176` | — | Falta GROUP BY `referido_por`. | NOT_INSTRUMENTED |

## 2. Dashboard V2 de escritorio (`GET /api/v1/admin/dashboard/overview`)

Pantalla: `features/dashboard/.../DashboardV2Screen.kt`; textos: `DashboardV2Presentation.kt:182-289`; mapeo: `data/.../KtorDashboardOverviewRepositoryImpl.kt:209-334`. El overview no declara ningún hueco de métrica (`availability.gap` no se llama en `ADO`): todas las tarjetas son REAL. Las NOT_INSTRUMENTED se ocultarían (`DashboardOverview.renderable`); NO_DATA se pinta "Sin dato" y una lectura fallida "No se pudo leer".

| SCREEN | METRIC | SOURCE | QUERY | TIME RANGE | DEFINITION | STATUS |
|---|---|---|---|---|---|---|
| DASHBOARD V2 · Sistema | `ktorStatus` — "Ktor {nivel}" | Proceso Ktor | `ADO:626` | Instante | Constante OPERATIVO: si responde, está en pie. | REAL |
| DASHBOARD V2 · Sistema | `supabaseStatus` — "Supabase {nivel}" | `especialidades` (sonda de conteo) | `ADO:615-625` | Instante | Operativo si la sonda responde en < 1500 ms (`AdminMarket.kt:213`), degradado si tarda más, caído si falla. | REAL |
| DASHBOARD V2 · Sistema | `supabaseLatencyMs` — "responde en N ms" | Misma sonda | `ADO:619,639` | Instante | Tiempo de la sonda; se oculta la línea si la sonda falla. No es latencia de API (que se declara sin instrumentar en `ADO:644-660` y no se pinta). | REAL |
| DASHBOARD V2 · Sistema | `updatedAt` — "Actualizado hace …" | Reloj de Ktor | `ADO:627-634` | Instante | Momento de composición de la franja. Se refresca sola cada 60 s (`DashboardV2Screen.kt:122-127`). | REAL |
| DASHBOARD V2 · Atención | `reportsPending` — Reportes pendientes | `reportes_contenido` | `ADO:676-679` | Foto | `estado = pendiente` (mismo filtro que Analytics). | REAL |
| DASHBOARD V2 · Atención | `questionFeedbackPending` — OposiTester sin resolver | `feedback_pregunta` | `ADO:680-683` | Foto | `estado = pendiente`. | REAL |
| DASHBOARD V2 · Atención | `draftExams` — Exámenes en borrador | `examenes` | `ADO:691-694` | Foto | `activo = true` y `publicado_en IS NULL`. | REAL |
| DASHBOARD V2 · Atención | `subscriptionsExpiring7d` — Suscripciones que vencen | `suscripciones` | `ADO:700-703` | `[ahora, ahora+7d)` | `estado = activa` y `fin_en` en la ventana. | REAL |
| DASHBOARD V2 · Pulso | `activeUsers30d` — Usuarios activos | RPC `admin_dashboard_active_users(p_days=30)` | `ADO:730-733` → `ADO:779-789` | 30 días | Usuarios distintos con intento iniciado o evento de app recibido (definición de la RPC). Distinta de `activeUsers30d` de Retención. | REAL |
| DASHBOARD V2 · Pulso | `attemptsLast30d` — Intentos (30d) | `intentos_examen` | `ADO:734-737` | 30d (`iniciado_en`) | Intentos iniciados, cualquier estado. | REAL |
| DASHBOARD V2 · Pulso | `publishedExams` — Exámenes publicados | `examenes` | `ADO:738-741` | Foto | `publicado_en IS NOT NULL` y `activo = true`. | REAL |
| DASHBOARD V2 · Pulso | `globalErrorRate` — Tasa de error global | `respuestas_intento` (2 conteos) | `ADO:750-756` | Histórico | `es_correcta = false / total × 100`; NO_DATA si 0 respuestas, FAILED si falla una lectura. | REAL |
| DASHBOARD V2 · Contenido | `totalExams` — Exámenes | `examenes` | `ADO:805-808` | Foto | `activo = true` (incluye borradores). | REAL |
| DASHBOARD V2 · Contenido | `totalQuestions` — Preguntas | `preguntas` | `ADO:809-812` | Foto | `activa = true`. | REAL |
| DASHBOARD V2 · Contenido | `totalResources` — Recursos | `recursos_pregunta` | `ADO:817-820` | Foto | `activo = true`. | REAL |
| DASHBOARD V2 · Contenido | `importBatches` — Lotes de importación | `lotes_importacion_preguntas` | `ADO:821-824` | Histórico | Todos los lotes. | REAL |
| DASHBOARD V2 · Actividad | `activity` — Actividad reciente (cronología) | `auditoria_log` | `ADO:852-861` | Últimas 8 filas por `creado_en` desc | Se pintan 5 (`DashboardV2Screen.kt:1840`); filas sin `id` o fecha se cuentan en `droppedEntries` y se avisa. Si la lectura falla, falla la franja. | REAL |
| DASHBOARD V2 · Economía | `subscriptionsActive` — Suscripciones activas | `suscripciones` | `ADO:879-882` | Ahora | Mismo filtro que Analytics (`AA:342`). | REAL |
| DASHBOARD V2 · Economía | `subscriptionsExpiring30d` — Vencen en 30 días | `suscripciones` | `ADO:883-886` | `[ahora, ahora+30d)` | `estado = activa` y `fin_en` en la ventana. | REAL |
| DASHBOARD V2 · Economía | `commercialProductsActive` — Productos comerciales | `productos_comerciales` | `ADO:887-890` | Foto | `activo = true`. | REAL |
| DASHBOARD V2 · Economía | `grossRevenueCents` — Ingresos | RPC `admin_dashboard_economy_totals` | `ADO:905-912` → `ADO:938-947` | Histórico | Suma de compras `pagada` en EUR (céntimos). La RPC devuelve `NULL` → NO_DATA si alguna pagada no tiene precio o no es EUR. | REAL |
| DASHBOARD V2 · Economía | `ticketsInCirculation` — OposiTickets en circulación | Misma RPC | `ADO:913-916` | Foto | Suma de `carteras_usuario.saldo_tickets`; se pinta como número, no dinero. | REAL |

## 3. Discrepancias a tener en cuenta

- `activeUsers30d` significa cosas distintas en Retención (`rachas_usuario`, `AA:857`) y en el Dashboard V2 (RPC, `ADO:730`). El Dashboard documenta que `rachas_usuario` no tiene escritor (`ADO:724-728`), así que las métricas de Retención basadas en esa tabla pueden ser un 0 estructural.
- Ingresos y OposiTickets son NOT_INSTRUMENTED en Analytics y REAL en el Dashboard V2 (RPC `admin_dashboard_economy_totals`).
- `ticketsInCirculation` se mapea como dinero en Analytics (`KtorProductAnalyticsRepositoryImpl.kt:479`); hoy no se ve porque no está instrumentada, pero si se instrumentara se pintaría con "€".
