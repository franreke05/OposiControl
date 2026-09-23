# Revisión read-only Kotlin/KMP — `core/common`, `core/network`, `core/database`, `core/testing` + 8 features pequeñas

91/91 ficheros leídos (core/common 22, core/network 9, core/database 6, core/testing 1, features/auth 6, features/settings 13, features/audit 5, features/roles 5, features/analytics 5, features/rankings 9, features/tags 5, features/communities 5).

---

## TIER 1 — valor alto, riesgo casi nulo

### 1.1 · Tres `Regex` recompiladas en cada error de red

**APLICADO.** `NetworkResultAdapter.kt:54,116,138` — subidas al `private companion object` como `BACKEND_STATUS_PATTERN`, `BACKEND_MESSAGE_PATTERN`, `BACKEND_ERROR_CODE_PATTERN`, junto al ya existente `REFERENCE_PATTERN`. Cubierto por `NetworkResultAdapterTest` (18 tests).

### 1.2 · `AppUiState.kt` entero es código muerto (39 líneas)

**APLICADO.** Borrado `core/common/.../presentation/AppUiState.kt` (`AppUiPhase`, `UiErrorCategory`, `UiError`). Verificado por grep: cero imports, cero usos cualificados en todo el repo. El proyecto resuelve esto con `UiAsync`+`AppError`, que sí tienen 33 consumidores.

### 1.3 · `AppResult.map` y `AppResult.onFailure` sin ningún call site

**Parcialmente aplicado y superado por eventos**: `onFailure` (líneas 25-28) **borrado** (seguía sin uso). `map` **NO se borró** — H13 del informe `02-domain-data.md`, aplicado en la misma sesión, le creó dos call sites reales en `ContentImportUseCases.kt`. El hallazgo original quedó obsoleto por el propio trabajo de esta sesión; se documenta aquí para que no se re-proponga su borrado.
**Contraste importante que sigue vigente**: `onSuccess` y `getOrNull` SÍ tienen uso real (`AuthRepositoryImpl.kt`, `ContentDashboardRepositoryImpl.kt`) — no tocar.

### 1.4 · `DispatcherProvider.io` y `DispatcherProvider.default` no los consume nadie

**NO aplicado.** `DispatcherProvider.kt:8-9,14-15` y `TestDispatcherProvider.kt:12-13`. Único uso real de la abstracción: `MviViewModel.kt:18` (`dispatcherProvider.main`). Riesgo muy bajo borrarlos, pero son punto de extensión: si mañana entra I/O real haría falta reintroducirlos. **No se aplicó** para no reducir superficie de una abstracción que puede necesitarse pronto; decisión de arquitectura, no de limpieza.

### 1.5 · `ApiClientFactory.appConfig` es un campo que nunca se lee

**NO aplicado** — ver 2.1 (la opción correcta no es borrarlo, es usarlo).

---

## TIER 2 — valor alto, riesgo bajo-medio (ninguno aplicado — todos piden test nuevo o cambian comportamiento)

### 2.1 · `LogLevel.HEADERS` formatea cada petición aunque el logger sea `NoOpLogger`

`ApiClientFactory.kt:49-62`: con `enableLogs=false` (producción), Ktor sigue construyendo el volcado completo de método+URL+cabeceras por petición y respuesta, y el resultado se tira en `Unit`. Arreglo propuesto: `level = if (appConfig.featureFlags.enableLogs) LogLevel.HEADERS else LogLevel.NONE`. **No aplicado**: el propio revisor dice "merece un test que fije `enableLogs=false ⇒ el Logger no recibe nada`" — no hay uno hoy.

### 2.2 · `features/roles`: la mitad de la máquina de escritura es inalcanzable

`RolesIntent.Save/UpdateName/UpdatePermissions` sin ningún dispatch en todo el repo (verificado con grep). `RolesState.editorName/editorPermissions/actionState` se escriben y nadie los lee. Efecto colateral: la pantalla de roles no reporta analítica nunca (`trackScreen("roles")` sólo vive dentro de `save()`). **No aplicado — decisión de producto explícita**: ¿es de solo lectura a propósito (como dice `RolesScreen.kt:71`) o falta cablear el control? No se puede decidir desde una revisión de optimización.

### 2.3 · Campos de estado que solo se escriben

`TagsState.editorOrigin` (cero lecturas) y `AuthState.currentSession` (cero lecturas, pero cambia identidad de instancias emitidas por el `StateFlow` — quitar el campo podría hacer desaparecer emisiones hoy indistinguibles). **No aplicado**: `editorOrigin` sería seguro pero de valor marginal por sí solo; `currentSession` explícitamente "no del todo seguro sin test nuevo".

### 2.4 · `AuditScreen` filtra y reconstruye la tabla en cada recomposición

`AuditScreen.kt:56-81`: `filter` + `map` + `joinToString` de metadatos en el cuerpo del composable, sin `remember`. **No aplicado** — `features/audit` no tiene ningún test; el revisor pide red antes de tocar aunque la transformación sea mecánica. Caso análogo menor: `RolesScreen.kt:34` usa `remember { mutableStateOf("") }` en vez de `rememberSaveable` para la búsqueda (se pierde al recrear config) — no aplicado.

---

## TIER 3 — valor medio, riesgo bajo (ninguno aplicado)

- **3.1** `TagsViewModel` y `CommunitiesViewModel` son casi el mismo ViewModel (`select`/`publish`/`save`/`deleteSelected` calcados), PERO con diferencias reales que ya han divergido: `publish()` filtra por campos distintos, `CommunitiesIntent.CreateNew` tiene tres valores iniciales distintos para `editorSortOrder` (`allItems.size.toString()` / `"0"` en `deleteSelected()` / `"0"` por defecto), `CommunitiesViewModel.save()` valida antes de `Loading`. **No aplicado**: sin tests de caracterización previos (ninguno de los dos módulos tiene tests), fusionar es alto riesgo.
- **3.2** `formatScore`/`formatSignedScore` (`RankingsFormat.kt:32-55`) comparten cola idéntica de signo/redondeo. Bien cubierto por 4 tests. **No aplicado** — bajo volumen, quedó detrás de otras prioridades.
- **3.3** `SystemStatusViewModel.toUiState` boxea una `List` de 8 elementos sólo para un `any { it == null }` (`SystemStatusViewModel.kt:68-77`). **No aplicado** — riesgo de olvidar uno de los ocho campos al reescribir.
- **3.4** `currentState()` leído N veces dentro de una misma construcción (`RolesViewModel.kt:67-70`, `CommunitiesViewModel.kt:110-125`, `SettingsViewModel.kt:63-67`, `TagsViewModel.kt:102-107`, `AuthViewModel.kt:98-105`). Riesgo bajo pero real (coherencia entre lecturas si hay corrutinas concurrentes). **No aplicado.**

---

## REGISTRAR, NO TOCAR

### 4.1 · `MviViewModel.clear()` no lo llama nadie — fuga de corrutinas real en ViewModels `factory {}`

`MviViewModel.kt:17-39`: `CoroutineScope(dispatcherProvider.main + job)` + `clear()` sin ningún call site en todo el repo. Los ViewModels inyectados con `factory {}` (analytics, audit, rankings, roles, auth, tags, communities, system-status) se recrean en cada `koinInject()` y su `collectLatest` sigue vivo indefinidamente. `SettingsViewModel` es `single {}`, no fuga. **No es oportunidad de optimización: es un fallo de ciclo de vida real.** Fuera de alcance de esta revisión; requiere decisión de arquitectura (atar el scope al ciclo de vida de Compose, o llamar `clear()` explícitamente).

### 4.2 · `AuditViewModel` formatea la fecha distinto que `AuditScreen`, y su versión tiene un fallo latente

`AuditViewModel.kt:36` vs `AuditScreen.kt:76`: la diferencia es `removeSuffix("Z")`. Si el instante cae en un segundo exacto sin parte fraccionaria, `AuditViewModel` produce `"-09-23 10:11:12Z"` en vez de `"6-09-23 10:11:12"` (desplazamiento de `takeLast(16)`). Ocurre ~1 de cada 1000 refrescos en plataformas con reloj de milisegundo. **Bug real, no limpieza** — corregir con su propio test, fuera de este mandato.

### 4.3 · `FieldLabel`: un canónico público y siete copias privadas — NO intercambiables

Canónico `core/ui/.../FormField.kt:226` envuelve el `Text` en un `Row` con `defaultMinSize(minHeight = Sizing.iconButtonSmall)` (~10dp más de alto). Copias privadas en `AuthScreen.kt:220`, `SettingsScreen.kt:369`, `TagsScreen.kt:130` (+4 más fuera de este lote: exam-access, moderation, support, tickets) son un `Text` desnudo. **No sustituir**: movería layout, contra la regla de medidas exactas de AGENTS.md.

### 4.4 · `SettingsFeedback` vs `FormFeedback`: móvil enseña el mensaje crudo, escritorio el humano

`SettingsScreen.kt:301-308` (móvil) pinta `state.error.message`; la ruta de escritorio del mismo fichero usa el canónico `FormFeedback` (`:361`) que pinta `state.error.humanMessage()`. Unificar cambiaría el texto que ve el operador en móvil (probablemente a mejor, pero es decisión de producto).

### 4.5 · `SectionCard` vs `SettingsSectionCard`: mismo paquete, medidas distintas

`SystemStatusScreen.kt:269-293` y `SettingsScreen.kt:162-187`, misma estructura, difieren en 4 medidas (20dp/18dp, 8dp/7dp, etc.). Parecen la misma función; no lo son. Fusionar es diseño, no limpieza.

### 4.6 · `NO_DATA_TEXT`/`NO_DATA_LABEL` — mismo literal, dos constantes, dos módulos

`RankingsFormat.kt:15` y `ProductAnalyticsLabels.kt:54`, ambos `"NO_DATA"`, cada uno con su batería de tests fijando el literal. Unificar exige subir a un módulo común nuevo. Coste mayor que el beneficio.

### 4.7 · `NotConnectedSystemStatusRepository` no lo instancia nadie — a propósito

`features/settings/.../data/NotConnectedSystemStatusRepository.kt:7`. NO es código muerto: es la única forma honesta de decir "aquí no hay observabilidad" en un despliegue sin backend, documentado en el propio módulo.

### 4.8 · `CommunitiesViewModel.save()` llama `Clock.System.now()` dos veces

`CommunitiesViewModel.kt:124-125`: `createdAt`/`updatedAt` difieren en nanosegundos para una comunidad nueva. Unificar cambiaría el dato enviado — decisión de producto, no de refactor.

### 4.9 · `BoxWithConstraints` en `SettingsScreen.kt:62`

Sostiene el conmutador móvil/escritorio en 720dp. Anotado para trazabilidad de la regla de AGENTS.md; sin evidencia de alternativa equivalente.

---

## Cierre

`FILES_IN_SCOPE = 91`, `FILES_READ = 91`. Ninguno sin leer.
