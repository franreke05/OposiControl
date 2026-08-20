# OposiControl Audit

Fecha: 2026-06-24

Referencia metodologica: [DietrichGebert/ponytail](https://github.com/DietrichGebert/ponytail)

## Resumen ejecutivo

Se auditaron los 122 elementos del indice entregado, con foco en simplificacion, eliminacion de friccion CRUD y mejora de UI/UX para subir, crear, editar y eliminar contenido sin sobre-disenar.

El bloque de riesgo alto se concentra en backend, seguridad operativa, validacion de flujos de examen/noticia y paridad multiplataforma. Esos puntos se documentan, pero no se tocaron porque el alcance pedido fue corregir solo severidad media y baja.

En cambio, si se aplicaron fixes de severidad media y baja orientados a:

- hacer visibles acciones de creacion que ya existian en ViewModels pero no en UI;
- conservar feedback de exito despues de guardar;
- evitar borrados accidentales;
- mostrar feedback de guardado en mobile;
- cerrar un race real en acciones rapidas de noticias;
- recortar una pieza muerta segura.

## Resultado del trabajo aplicado

Archivos modificados: 23

Cambios efectivos aplicados:

- `CommunitiesScreen.kt`: boton de alta visible, confirmacion antes de borrar, bloqueo de acciones durante loading.
- `CommunitiesViewModel.kt`: se preserva el `actionState` de exito tras guardar y reseleccionar.
- `ExamAccessScreen.kt`: boton visible para alta.
- `ExamAccessViewModel.kt`: se preserva el exito tras guardar.
- `ContentFilePicker.android.kt`: el importador de texto pasa de `*/*` a `text/*`.
- `ExamsModels.kt`: eliminacion de wrapper muerto `parseAiken`.
- `ExamsScreen.kt`: boton visible `Nuevo examen`.
- `MobileContentScreen.kt`: feedback visual de guardado para examenes y noticias; acciones rapidas de noticias pasan a intents atomicos.
- `MobileLayeredNewsEditor.kt`: confirmacion antes de borrar noticia en mobile.
- `ModerationScreen.kt`: boton visible para alta.
- `ModerationViewModel.kt`: se preserva el exito tras guardar.
- `NewsIntent.kt`: nuevos intents atomicos `SaveDraftFor`, `PublishFor`, `ArchiveFor`.
- `NewsScreen.kt`: `Nueva noticia` visible y acciones rapidas sin race.
- `NewsViewModel.kt`: save/publish/archive por item ejecutados en una sola intencion.
- `NewsTypesScreen.kt`: boton visible para alta.
- `NewsTypesViewModel.kt`: se preserva el exito tras guardar.
- `SpecialtiesScreen.kt`: boton visible para alta.
- `SpecialtiesViewModel.kt`: se preserva el exito tras guardar.
- `StoreScreen.kt`: boton visible para alta.
- `StoreViewModel.kt`: se preserva el exito tras guardar.
- `TagsScreen.kt`: boton visible para alta.
- `TagsViewModel.kt`: se preserva el exito tras guardar.
- `UsersScreen.kt`: boton visible para alta.

## Validacion

- `git diff --check`: correcto.
- `./gradlew test`: falla por un problema previo del arbol en `:data` y estado de salidas Kotlin/Gradle, no por los cambios de esta auditoria.
- `./gradlew :features:news:compileDebugKotlinAndroid`: correcto.
- `./gradlew :features:exams:clean :features:exams:compileDebugKotlinAndroid`: correcto.

## Hallazgos altos no corregidos

### Backend y seguridad

- `AppV1Routes.kt`: el flujo de intento/respuesta no valida correctamente que la pregunta pertenezca al intento/examen ni que la opcion pertenezca a la pregunta.
- `Application.kt`: existe superficie de configuracion y bootstrapping demasiado permisiva para secretos/token administrativos.
- `AdminGateway.kt`: token administrativo/operativo demasiado expuesto para un cliente admin.
- `AdminV1Routes.kt`: varias operaciones grandes mezclan validacion, parsing, persistencia y upload en el mismo punto; hay riesgo de inconsistencias parciales.
- `SupabaseV1Gateway.kt`: divergencia entre rutas legacy y V1; algunas operaciones no persisten realmente lo que la UI cree que persisten.
- `NewsGateway.kt`: mapeo inconsistente de `publishFrom`, fondo, imagen y capas; el contrato backend/frontend hoy no es estable.
- `TagSearchGateway.kt`: busqueda y reindexado con demasiado trabajo en memoria y sin suficiente proteccion transaccional.
- `V1Routes.kt`: mezcla de responsabilidades y caminos legacy que vuelven opaco el comportamiento real de CRUD.

### Integridad funcional

- `NewsViewModel.kt` y `NewsGateway.kt`: el flujo “guardar local” y el flujo remoto no estan claramente separados a nivel semantico.
- `ExamsViewModel.kt` y backend relacionado: hay riesgo de huerfanos de recursos y de persistencias parciales en borrados/reordenaciones.
- `SettingsScreen.kt` + backend legacy: la UI expone settings que no siempre tienen persistencia real detras.

### Paridad multiplataforma

- `ContentFilePicker.ios.kt`, `NewsBackgroundImage.ios.kt`, `OrientationLock.ios.kt`, `SystemBarsControl.ios.kt`
- `ContentFilePicker.jvm.kt`, `NewsBackgroundImage.jvm.kt`, `OrientationLock.jvm.kt`, `SystemBarsControl.jvm.kt`

En iOS/JVM hay implementaciones no-op o con funcionalidad claramente recortada frente a Android. Esto no es un bug cosmetico; es deuda alta de producto.

## Auditoria por modulo

### Backend

| Archivo | Estado | Nota |
|---|---|---|
| `AdminGateway.kt` | Alto, no corregido | Exposicion de token y responsabilidades mezcladas entre acceso admin y orquestacion CRUD. |
| `AdminV1Routes.kt` | Alto, no corregido | Rutas demasiado grandes; parsing multipart, validacion y escritura viven juntas. |
| `Application.kt` | Alto, no corregido | Bootstrapping muy cargado; cuesta auditar dependencias reales y secretos. |
| `AppV1Routes.kt` | Alto, no corregido | Validacion funcional insuficiente en respuestas/intentos de examen. |
| `NewsGateway.kt` | Alto, no corregido | Contrato noticia/capas/fondo inconsistente; arrastra UX y persistencia. |
| `SupabaseV1Gateway.kt` | Alto, no corregido | Divergencias entre esquemas/legacy; parte de la UI puede “guardar” sin persistencia estable. |
| `TagSearchGateway.kt` | Alto, no corregido | Busqueda y reindexado pesados, con demasiada carga en memoria y poco aislamiento. |
| `V1Routes.kt` | Alto, no corregido | Mezcla de rutas y flujos legacy/V1; mantenimiento y trazabilidad pobres. |

### Audit

| Archivo | Estado | Nota |
|---|---|---|
| `AuditFeatureModule.kt` | Sin hallazgo relevante | Wiring simple y suficiente. |
| `AuditIntent` | Sin hallazgo relevante | Contrato pequeno, sin sobre-ingenieria. |
| `AuditScreen.kt` | Medio pendiente | Refresh no transmite sensacion real de recarga; falta mejor empty state al filtrar. |
| `AuditState` | Sin hallazgo relevante | Estado acotado y razonable. |
| `AuditViewModel` | Medio pendiente | El refresh no refleja bien una recarga real de datos. |

### Auth

| Archivo | Estado | Nota |
|---|---|---|
| `AuthFeatureModule.kt` | Sin hallazgo relevante | DI simple. |
| `AuthIntent` | Sin hallazgo relevante | Contrato correcto. |
| `AuthScreen.kt` | Medio pendiente | Submit demasiado permisivo durante loading; falta ergonomia de password. |
| `AuthState` | Sin hallazgo relevante | Estado coherente. |
| `AuthViewModel` | Medio pendiente | El error persiste demasiado mientras el usuario corrige el formulario. |

### Communities

| Archivo | Estado | Nota |
|---|---|---|
| `CommunitiesFeatureModule.kt` | Sin hallazgo relevante | Wiring correcto. |
| `CommunitiesIntent` | Sin hallazgo relevante | Contrato CRUD limpio. |
| `CommunitiesScreen.kt` | Fix aplicado | Boton crear visible, confirmacion de borrado y bloqueo en loading. |
| `CommunitiesState` | Sin hallazgo relevante | Estado suficiente. |
| `CommunitiesViewModel` | Fix aplicado | Mantiene feedback de exito tras guardar y reseleccionar. |

### Dashboard

| Archivo | Estado | Nota |
|---|---|---|
| `DashboardFeatureModule.kt` | Sin hallazgo relevante | Sin complejidad sobrante. |
| `DashboardIntent` | Sin hallazgo relevante | Contrato simple. |
| `DashboardScreen.kt` | Medio pendiente | Texto hardcoded de backend, targets pequenos y medidas menos consistentes que el resto. |
| `DashboardState` | Sin hallazgo relevante | Estado correcto. |
| `DashboardViewModel` | Sin hallazgo relevante | No aparece problema medio/bajo dominante frente a la pantalla. |

### Exam Access

| Archivo | Estado | Nota |
|---|---|---|
| `ExamAccessFeatureModule.kt` | Sin hallazgo relevante | Wiring correcto. |
| `ExamAccessIntent` | Sin hallazgo relevante | Contrato claro. |
| `ExamAccessModels.kt` | Sin hallazgo relevante | Modelos razonables. |
| `ExamAccessScreen.kt` | Fix aplicado | Boton visible para alta/creacion. |
| `ExamAccessState` | Sin hallazgo relevante | Estado acotado. |
| `ExamAccessViewModel` | Fix aplicado | Preserva el feedback de exito despues del guardado. |

### Exams

| Archivo | Estado | Nota |
|---|---|---|
| `ExamsFeatureModule.kt` | Sin hallazgo relevante | Wiring correcto. |
| `ContentFilePicker.kt` | Sin hallazgo relevante | Abstraccion pequena y util. |
| `ExamsIntent` | Sin hallazgo relevante | Contrato amplio pero acorde al modulo. |
| `ExamsModels.kt` | Fix aplicado | Eliminado wrapper muerto `parseAiken`; sigue habiendo catalogos y helpers mejorables. |
| `ExamsScreen.kt` | Fix aplicado | `Nuevo examen` ahora existe en desktop; antes el CRUD estaba incompleto en UI. |
| `ExamsState` | Sin hallazgo relevante | Estado grande pero coherente para el editor. |
| `ExamsViewModel` | Medio pendiente | Sigue muy grande y con demasiadas responsabilidades para un unico VM. |
| `MobileContentScreen.kt` | Fix aplicado | Feedback de guardado visible y acciones de noticias sin race. |
| `MobileLayeredNewsEditor.kt` | Fix aplicado | Confirmacion antes de borrar noticia en mobile. |
| `NewsBackgroundImage.kt` | Sin hallazgo relevante | Punto de extension valido por plataforma. |
| `OrientationLock.kt` | Sin hallazgo relevante | Contrato pequeno. |
| `SystemBarsControl.kt` | Sin hallazgo relevante | Contrato pequeno. |

### Exams / Android

| Archivo | Estado | Nota |
|---|---|---|
| `ContentFilePicker.android.kt` | Fix aplicado | Se restringe texto a `text/*`, reduciendo entradas absurdas o demasiado amplias. |
| `NewsBackgroundImage.android.kt` | Bajo pendiente | Riesgo de coste de decodificacion completa en imagenes grandes. |
| `OrientationLock.android.kt` | Sin hallazgo relevante | Implementacion alineada con el contrato. |
| `SystemBarsControl.android.kt` | Sin hallazgo relevante | Implementacion suficiente. |

### Exams / iOS

| Archivo | Estado | Nota |
|---|---|---|
| `ContentFilePicker.ios.kt` | Alto, no corregido | Paridad funcional insuficiente respecto a Android. |
| `NewsBackgroundImage.ios.kt` | Alto, no corregido | Paridad visual/comportamental incompleta. |
| `OrientationLock.ios.kt` | Alto, no corregido | Implementacion limitada/no-op frente a expectativa del editor. |
| `SystemBarsControl.ios.kt` | Alto, no corregido | Control de entorno visual incompleto. |

### Exams / JVM

| Archivo | Estado | Nota |
|---|---|---|
| `ContentFilePicker.jvm.kt` | Alto, no corregido | Paridad funcional insuficiente respecto a Android. |
| `NewsBackgroundImage.jvm.kt` | Alto, no corregido | Implementacion reducida/no-op. |
| `OrientationLock.jvm.kt` | Alto, no corregido | Contrato sin equivalencia real de comportamiento. |
| `SystemBarsControl.jvm.kt` | Alto, no corregido | Funcionalidad recortada/no-op. |

### Moderation

| Archivo | Estado | Nota |
|---|---|---|
| `ModerationFeatureModule.kt` | Sin hallazgo relevante | Wiring correcto. |
| `ForumModerationState.kt` | Sin hallazgo relevante | Modelo estable. |
| `ForumModerationViewModel` | Sin hallazgo relevante | Sin hallazgo medio aislado del resto. |
| `ModerationIntent` | Sin hallazgo relevante | Contrato claro. |
| `ModerationModels.kt` | Sin hallazgo relevante | Modelos simples. |
| `ModerationScreen.kt` | Fix aplicado | Boton visible para alta. |
| `ModerationState` | Sin hallazgo relevante | Estado correcto. |
| `ModerationViewModel` | Fix aplicado | Preserva feedback de exito tras guardar. |

### News

| Archivo | Estado | Nota |
|---|---|---|
| `NewsFeatureModule.kt` | Sin hallazgo relevante | Wiring correcto. |
| `NewsIntent` | Fix aplicado | Nuevas intenciones atomicas para save/publish/archive por item. |
| `NewsModels.kt` | Medio pendiente | Modelos suficientes, pero la semantica “local vs remoto” sigue siendo ambigua. |
| `NewsScreen.kt` | Fix aplicado | `Nueva noticia` visible; acciones rapidas dejan de depender de dos dispatches separados. |
| `NewsState` | Sin hallazgo relevante | Estado grande pero consistente con el editor. |
| `NewsViewModel.kt` | Fix aplicado + medio pendiente | Se cerro el race de acciones por item, pero sigue siendo un ViewModel demasiado grande y con ramas muertas/ambiguedades semanticas. |

### News Types

| Archivo | Estado | Nota |
|---|---|---|
| `NewsTypesFeatureModule.kt` | Sin hallazgo relevante | Wiring correcto. |
| `NewsTypesIntent` | Sin hallazgo relevante | Contrato simple. |
| `NewsTypesModels.kt` | Sin hallazgo relevante | Modelos pequenos. |
| `NewsTypesScreen.kt` | Fix aplicado | Boton visible para alta. |
| `NewsTypesState` | Sin hallazgo relevante | Estado correcto. |
| `NewsTypesViewModel` | Fix aplicado | Preserva feedback de exito tras guardar. |

### Roles

| Archivo | Estado | Nota |
|---|---|---|
| `RolesFeatureModule.kt` | Sin hallazgo relevante | Wiring correcto. |
| `RolesIntent` | Bajo pendiente | Hay señales de CRUD/intents sobrantes para una UI que hoy es casi read-only. |
| `RolesScreen.kt` | Bajo pendiente | El editor no aporta suficiente valor respecto al coste mental actual. |
| `RolesState` | Bajo pendiente | Parte del estado parece responder a capacidades no expuestas realmente. |
| `RolesViewModel` | Bajo pendiente | Buen candidato ponytail: adelgazar o convertir en lectura real. |

### Settings

| Archivo | Estado | Nota |
|---|---|---|
| `SettingsFeatureModule.kt` | Sin hallazgo relevante | Wiring correcto. |
| `SettingsIntent` | Sin hallazgo relevante | Contrato simple. |
| `SettingsScreen.kt` | Medio pendiente | La UI expone opciones cuya persistencia real no siempre esta garantizada. |
| `SettingsState` | Sin hallazgo relevante | Estado correcto. |
| `SettingsViewModel.kt` | Bajo pendiente | `preferFakeData` y parte del flujo legacy son deuda clara. |

### Specialties

| Archivo | Estado | Nota |
|---|---|---|
| `SpecialtiesFeatureModule.kt` | Sin hallazgo relevante | Wiring correcto. |
| `SpecialtiesIntent` | Sin hallazgo relevante | Contrato claro. |
| `SpecialtiesModels.kt` | Sin hallazgo relevante | Modelos suficientes. |
| `SpecialtiesScreen.kt` | Fix aplicado | Boton visible para alta. |
| `SpecialtiesState` | Sin hallazgo relevante | Estado correcto. |
| `SpecialtiesViewModel` | Fix aplicado | Preserva feedback de exito tras guardar. |

### Store

| Archivo | Estado | Nota |
|---|---|---|
| `StoreFeatureModule.kt` | Sin hallazgo relevante | Wiring correcto. |
| `StoreIntent` | Sin hallazgo relevante | Contrato amplio pero coherente. |
| `StoreModels.kt` | Medio pendiente | Hay normalizaciones y converters que no siempre gobiernan el guardado real. |
| `StoreScreen.kt` | Fix aplicado | Boton visible para alta; sigue pendiente trocear mejor el formulario. |
| `StoreState` | Sin hallazgo relevante | Estado grande pero entendible. |
| `StoreViewModel` | Fix aplicado + medio pendiente | Preserva feedback de exito, pero continua cargando demasiado comportamiento en un unico VM. |

### Support

| Archivo | Estado | Nota |
|---|---|---|
| `SupportFeatureModule.kt` | Sin hallazgo relevante | Wiring correcto. |
| `SupportIntent` | Sin hallazgo relevante | Contrato correcto. |
| `SupportModels.kt` | Sin hallazgo relevante | Modelos suficientes. |
| `SupportScreen.kt` | Medio pendiente | Puede disparar reenvios o refrescos repetidos con poca proteccion UX. |
| `SupportState` | Sin hallazgo relevante | Estado correcto. |
| `SupportViewModel` | Medio pendiente | Hay dobles fetch y feedback de accion fragil. |

### Tags

| Archivo | Estado | Nota |
|---|---|---|
| `TagsFeatureModule.kt` | Sin hallazgo relevante | Wiring correcto. |
| `TagsIntent` | Sin hallazgo relevante | Contrato claro. |
| `TagsScreen.kt` | Fix aplicado | Boton visible para alta. |
| `TagsState` | Sin hallazgo relevante | Estado correcto. |
| `TagsViewModel` | Fix aplicado | Preserva feedback de exito tras guardar. |

### Tickets

| Archivo | Estado | Nota |
|---|---|---|
| `TicketsFeatureModule.kt` | Sin hallazgo relevante | Wiring correcto. |
| `TicketsIntent` | Sin hallazgo relevante | Contrato correcto. |
| `TicketsModels.kt` | Medio pendiente | Filtros libres y contratos con validacion floja. |
| `TicketsScreen.kt` | Medio pendiente | UX permisiva para acciones repetidas o ajustes poco guiados. |
| `TicketsState` | Sin hallazgo relevante | Estado correcto. |
| `TicketsViewModel` | Medio pendiente | Doble fetch y acciones repetibles sin suficiente proteccion de UI. |

### Users

| Archivo | Estado | Nota |
|---|---|---|
| `UsersFeatureModule.kt` | Sin hallazgo relevante | Wiring correcto. |
| `UsersIntent` | Sin hallazgo relevante | Contrato claro. |
| `UsersScreen.kt` | Fix aplicado | Boton visible para alta; mejora el flujo CRUD aunque persisten limites backend. |
| `UsersState` | Sin hallazgo relevante | Estado correcto. |
| `UsersViewModel` | Sin hallazgo relevante | El principal riesgo aqui depende mas del backend que del VM. |

## Arquitectura general

| Tema | Estado | Recomendacion |
|---|---|---|
| `Estructura por modulos` | Aceptable con deuda | La separacion por feature esta bien, pero backend y ViewModels grandes concentran demasiada complejidad. |
| `commonMain / androidMain / iosMain` | Deuda alta de paridad | Mantener el contrato comun, pero no vender parity si iOS/JVM siguen con no-op. |
| `Screen - State - Intent - ViewModel` | Correcto con exceso puntual | El patron funciona; el problema no es el patron sino algunos ViewModels gigantes. |
| `FeatureModule` | Correcto | La DI por feature es razonable y no parece la fuente del dolor UX actual. |
| `Archivos especificos por plataforma` | Deuda alta | Necesitan objetivo funcional explicito o simplificacion de expectativas. |
| `Organizacion de paquetes` | Correcta | La navegacion es clara y ayuda a auditar. |
| `Convenciones de nombres` | Mejorable | Hay mezcla de legacy, V1, mobile content y news editor que complica la lectura. |
| `Mantenimiento` | Requiere poda | El mayor retorno vendra de adelgazar ViewModels, matar ramas legacy y consolidar contratos CRUD. |

## Lista maestra de errores ordenada de menor a mayor

Nota: esta lista recoge todos los errores y deudas identificados en esta auditoria. Incluye tanto puntos `Pendiente` como puntos `Corregido` para que quede claro que se detecto, que impacto tenia y como mejora la app.

| # | Severidad | Tipo | Estado | Error identificado | Como mejoraria la app |
|---|---|---|---|---|---|
| 1 | Baja | UI/UX | Pendiente | `RolesScreen.kt` mantiene una complejidad de editor que hoy aporta poco a una UI casi read-only. | Simplificar el flujo de roles y dejar solo lectura o un CRUD real reducira friccion mental y tiempo de aprendizaje. |
| 2 | Baja | Logica | Pendiente | `RolesState` y `RolesViewModel` conservan estado/intents que no se reflejan en una capacidad real de la pantalla. | Eliminar estado muerto y rutas especulativas dejara un modulo mas mantenible y menos propenso a regresiones. |
| 3 | Baja | Logica | Pendiente | `SettingsViewModel.kt` arrastra flags y caminos legacy como `preferFakeData`. | Podar ramas muertas simplificara soporte y evitara lecturas engañosas del comportamiento real. |
| 4 | Baja | UI/UX | Corregido | Varias pantallas CRUD tenian soporte de creacion en ViewModel pero no un boton visible para crear. | Exponer siempre la accion primaria de alta hace que crear contenido sea obvio y reduce pasos innecesarios. |
| 5 | Baja | Logica | Corregido | `ExamsModels.kt` mantenia el wrapper muerto `parseAiken`. | Eliminar codigo muerto reduce ruido y hace mas claro cual es el parser real soportado. |
| 6 | Baja | UI/UX | Pendiente | `DashboardScreen.kt` mantiene targets pequenos y mensajes demasiado hardcoded. | Estandarizar medidas y estados reales mejorara legibilidad, accesibilidad y confianza operativa. |
| 7 | Baja | UI/UX | Corregido | `ContentFilePicker.android.kt` aceptaba texto con un filtro demasiado amplio. | Restringir a `text/*` reduce errores de importacion y hace el flujo mas predecible para el usuario. |
| 8 | Media | UI/UX | Pendiente | `AuthScreen.kt` y `AuthViewModel.kt` no limpian bien la experiencia durante loading/error. | Desactivar submit en carga, limpiar errores al editar y mejorar password entry reducira frustracion en login. |
| 9 | Media | UI/UX | Pendiente | `AuditScreen.kt` y `AuditViewModel.kt` no transmiten bien recarga real ni vacio filtrado. | Un refresh honesto y empty states claros haran la auditoria interna mas util y confiable. |
| 10 | Media | UI/UX | Pendiente | `TicketsScreen.kt`, `SupportScreen.kt` y parte de `StoreScreen.kt` permiten acciones repetibles o formularios demasiado pesados. | Guiar mejor filtros, bloquear acciones repetidas y dividir formularios reducira errores operativos. |
| 11 | Media | Logica | Pendiente | `SupportViewModel.kt` y `TicketsViewModel.kt` muestran dobles fetch y feedback de accion fragil. | Centralizar refresh y blindar estados de accion evitara duplicados, reenvios y lecturas inconsistentes. |
| 12 | Media | UI/UX | Corregido | El feedback de exito se perdia tras guardar en varios modulos por la reseleccion inmediata del item. | Mantener visible el estado de exito confirma al usuario que el guardado funciono y reduce dudas o reintentos. |
| 13 | Media | UI/UX | Corregido | `CommunitiesScreen.kt` y `MobileLayeredNewsEditor.kt` permitian borrados con proteccion insuficiente. | La confirmacion antes de eliminar reduce borrados accidentales y da mas control sobre contenido sensible. |
| 14 | Media | UI/UX | Corregido | `MobileContentScreen.kt` no mostraba bien el resultado de guardar examenes/noticias en mobile. | Un feedback visible tras guardar hace que el flujo mobile sea entendible sin adivinar si hubo sincronizacion. |
| 15 | Media | Logica | Pendiente | `StoreModels.kt`, `StoreViewModel.kt`, `SettingsScreen.kt` y `NewsModels.kt` tienen semanticas ambiguas entre lo que la UI expresa y lo que realmente se guarda. | Alinear contrato UI-dominio-backend evitara falsas expectativas de guardado y estados inconsistentes. |
| 16 | Media | Arquitectura | Pendiente | `NewsViewModel.kt` y `ExamsViewModel.kt` concentran demasiadas responsabilidades en un unico punto. | Dividir por subflujos reducira complejidad, acelerara cambios futuros y bajara riesgo de regresiones UX/logicas. |
| 17 | Media | UI/UX | Pendiente | `DashboardScreen.kt`, `StoreScreen.kt`, `ExamsScreen.kt` y otros modulos siguen teniendo catalogos o campos demasiado libres para operaciones administrables. | Sustituir texto libre por selects, chips y presets reducira errores de entrada y hara el CRUD mas rapido. |
| 18 | Media | Logica | Corregido | `NewsScreen.kt` y `MobileContentScreen.kt` disparaban acciones rapidas de noticias con dos intents separados (`Select` + accion). | Convertirlas en intents atomicos evita carreras y garantiza que guardar/publicar/archivar afecte al item correcto. |
| 19 | Alta | UI/UX | Pendiente | `ContentFilePicker.ios.kt`, `NewsBackgroundImage.ios.kt`, `OrientationLock.ios.kt`, `SystemBarsControl.ios.kt` y equivalentes JVM no ofrecen paridad real con Android. | Resolver parity o declarar soporte reducido evitara una experiencia inconsistente entre plataformas. |
| 20 | Alta | Logica | Pendiente | `SettingsScreen.kt` expone opciones que a veces no tienen persistencia real en backend legacy. | O se implementa persistencia real o se retira la opcion visible; ambas decisiones mejoran confianza del admin. |
| 21 | Alta | Logica | Pendiente | `TagSearchGateway.kt` realiza demasiado trabajo en memoria y con poca proteccion transaccional. | Rehacer busqueda/reindexado alrededor de indices y operaciones mas seguras hara la app mas estable y escalable. |
| 22 | Alta | Logica | Pendiente | `NewsGateway.kt` mantiene un contrato inconsistente para `publishFrom`, fondo, imagen y capas. | Unificar el modelo de noticia reducira bugs de publicacion, edicion y previsualizacion. |
| 23 | Alta | Logica | Pendiente | `SupabaseV1Gateway.kt` y `V1Routes.kt` conservan mezcla de comportamientos legacy y V1 que opacan el CRUD real. | Consolidar una sola verdad de persistencia reducira sorpresas y simplificara soporte. |
| 24 | Alta | Logica | Pendiente | `AdminV1Routes.kt` mezcla parsing, validacion, uploads y persistencia en bloques demasiado grandes. | Separar responsabilidades y asegurar operaciones atomicas reducira inconsistencias y mejorara testabilidad. |
| 25 | Alta | Seguridad/Logica | Pendiente | `AdminGateway.kt` y `Application.kt` exponen demasiado riesgo alrededor de tokens/secrets administrativos. | Reforzar manejo de secretos y reducir exposicion endurecera la app sin tocar la UX visible. |
| 26 | Critica | Logica | Pendiente | `AppV1Routes.kt` no valida de forma suficiente la relacion intento -> examen -> pregunta -> opcion al responder. | Corregir esta integridad protege el nucleo funcional del producto y evita resultados invalidos o manipulables. |

## Recomendaciones prioritarias para la siguiente iteracion

1. Separar claramente “borrador local” de “guardado remoto” en noticias y store.
2. Reducir tamaño de `NewsViewModel.kt` y `ExamsViewModel.kt` antes de seguir metiendo UX encima.
3. Unificar catalogos cerrados de estado/tipo/especialidad en selects consistentes y no como texto libre.
4. Resolver la persistencia real de settings legacy o retirar la ilusion de guardado.
5. Normalizar backend de noticias y tags antes de invertir mas en polish visual.
6. Decidir estrategia real de iOS/JVM: parity funcional o soporte oficialmente reducido.

## Apéndice de cobertura total

Leyenda:

- `Fix aplicado`
- `Alto, no corregido`
- `Medio pendiente`
- `Bajo pendiente`
- `Sin hallazgo relevante`

### Checklist del indice

| Archivo | Estado | Seccion |
|---|---|---|
| `AdminGateway.kt` | Alto, no corregido | Backend |
| `AdminV1Routes.kt` | Alto, no corregido | Backend |
| `Application.kt` | Alto, no corregido | Backend |
| `AppV1Routes.kt` | Alto, no corregido | Backend |
| `NewsGateway.kt` | Alto, no corregido | Backend |
| `SupabaseV1Gateway.kt` | Alto, no corregido | Backend |
| `TagSearchGateway.kt` | Alto, no corregido | Backend |
| `V1Routes.kt` | Alto, no corregido | Backend |
| `AuditFeatureModule.kt` | Sin hallazgo relevante | Audit |
| `AuditIntent` | Sin hallazgo relevante | Audit |
| `AuditScreen.kt` | Medio pendiente | Audit |
| `AuditState` | Sin hallazgo relevante | Audit |
| `AuditViewModel` | Medio pendiente | Audit |
| `AuthFeatureModule.kt` | Sin hallazgo relevante | Auth |
| `AuthIntent` | Sin hallazgo relevante | Auth |
| `AuthScreen.kt` | Medio pendiente | Auth |
| `AuthState` | Sin hallazgo relevante | Auth |
| `AuthViewModel` | Medio pendiente | Auth |
| `CommunitiesFeatureModule.kt` | Sin hallazgo relevante | Communities |
| `CommunitiesIntent` | Sin hallazgo relevante | Communities |
| `CommunitiesScreen.kt` | Fix aplicado | Communities |
| `CommunitiesState` | Sin hallazgo relevante | Communities |
| `CommunitiesViewModel` | Fix aplicado | Communities |
| `DashboardFeatureModule.kt` | Sin hallazgo relevante | Dashboard |
| `DashboardIntent` | Sin hallazgo relevante | Dashboard |
| `DashboardScreen.kt` | Medio pendiente | Dashboard |
| `DashboardState` | Sin hallazgo relevante | Dashboard |
| `DashboardViewModel` | Sin hallazgo relevante | Dashboard |
| `ExamAccessFeatureModule.kt` | Sin hallazgo relevante | Exam Access |
| `ExamAccessIntent` | Sin hallazgo relevante | Exam Access |
| `ExamAccessModels.kt` | Sin hallazgo relevante | Exam Access |
| `ExamAccessScreen.kt` | Fix aplicado | Exam Access |
| `ExamAccessState` | Sin hallazgo relevante | Exam Access |
| `ExamAccessViewModel` | Fix aplicado | Exam Access |
| `ExamsFeatureModule.kt` | Sin hallazgo relevante | Exams |
| `ContentFilePicker.kt` | Sin hallazgo relevante | Exams |
| `ExamsIntent` | Sin hallazgo relevante | Exams |
| `ExamsModels.kt` | Fix aplicado | Exams |
| `ExamsScreen.kt` | Fix aplicado | Exams |
| `ExamsState` | Sin hallazgo relevante | Exams |
| `ExamsViewModel` | Medio pendiente | Exams |
| `MobileContentScreen.kt` | Fix aplicado | Exams |
| `MobileLayeredNewsEditor.kt` | Fix aplicado | Exams |
| `NewsBackgroundImage.kt` | Sin hallazgo relevante | Exams |
| `OrientationLock.kt` | Sin hallazgo relevante | Exams |
| `SystemBarsControl.kt` | Sin hallazgo relevante | Exams |
| `ContentFilePicker.android.kt` | Fix aplicado | Exams / Android |
| `NewsBackgroundImage.android.kt` | Bajo pendiente | Exams / Android |
| `OrientationLock.android.kt` | Sin hallazgo relevante | Exams / Android |
| `SystemBarsControl.android.kt` | Sin hallazgo relevante | Exams / Android |
| `ContentFilePicker.ios.kt` | Alto, no corregido | Exams / iOS |
| `NewsBackgroundImage.ios.kt` | Alto, no corregido | Exams / iOS |
| `OrientationLock.ios.kt` | Alto, no corregido | Exams / iOS |
| `SystemBarsControl.ios.kt` | Alto, no corregido | Exams / iOS |
| `ContentFilePicker.jvm.kt` | Alto, no corregido | Exams / JVM |
| `NewsBackgroundImage.jvm.kt` | Alto, no corregido | Exams / JVM |
| `OrientationLock.jvm.kt` | Alto, no corregido | Exams / JVM |
| `SystemBarsControl.jvm.kt` | Alto, no corregido | Exams / JVM |
| `ModerationFeatureModule.kt` | Sin hallazgo relevante | Moderation |
| `ForumModerationState.kt` | Sin hallazgo relevante | Moderation |
| `ForumModerationViewModel` | Sin hallazgo relevante | Moderation |
| `ModerationIntent` | Sin hallazgo relevante | Moderation |
| `ModerationModels.kt` | Sin hallazgo relevante | Moderation |
| `ModerationScreen.kt` | Fix aplicado | Moderation |
| `ModerationState` | Sin hallazgo relevante | Moderation |
| `ModerationViewModel` | Fix aplicado | Moderation |
| `NewsFeatureModule.kt` | Sin hallazgo relevante | News |
| `NewsIntent` | Fix aplicado | News |
| `NewsModels.kt` | Medio pendiente | News |
| `NewsScreen.kt` | Fix aplicado | News |
| `NewsState` | Sin hallazgo relevante | News |
| `NewsViewModel.kt` | Fix aplicado + medio pendiente | News |
| `NewsTypesFeatureModule.kt` | Sin hallazgo relevante | News Types |
| `NewsTypesIntent` | Sin hallazgo relevante | News Types |
| `NewsTypesModels.kt` | Sin hallazgo relevante | News Types |
| `NewsTypesScreen.kt` | Fix aplicado | News Types |
| `NewsTypesState` | Sin hallazgo relevante | News Types |
| `NewsTypesViewModel` | Fix aplicado | News Types |
| `RolesFeatureModule.kt` | Sin hallazgo relevante | Roles |
| `RolesIntent` | Bajo pendiente | Roles |
| `RolesScreen.kt` | Bajo pendiente | Roles |
| `RolesState` | Bajo pendiente | Roles |
| `RolesViewModel` | Bajo pendiente | Roles |
| `SettingsFeatureModule.kt` | Sin hallazgo relevante | Settings |
| `SettingsIntent` | Sin hallazgo relevante | Settings |
| `SettingsScreen.kt` | Medio pendiente | Settings |
| `SettingsState` | Sin hallazgo relevante | Settings |
| `SettingsViewModel.kt` | Bajo pendiente | Settings |
| `SpecialtiesFeatureModule.kt` | Sin hallazgo relevante | Specialties |
| `SpecialtiesIntent` | Sin hallazgo relevante | Specialties |
| `SpecialtiesModels.kt` | Sin hallazgo relevante | Specialties |
| `SpecialtiesScreen.kt` | Fix aplicado | Specialties |
| `SpecialtiesState` | Sin hallazgo relevante | Specialties |
| `SpecialtiesViewModel` | Fix aplicado | Specialties |
| `StoreFeatureModule.kt` | Sin hallazgo relevante | Store |
| `StoreIntent` | Sin hallazgo relevante | Store |
| `StoreModels.kt` | Medio pendiente | Store |
| `StoreScreen.kt` | Fix aplicado | Store |
| `StoreState` | Sin hallazgo relevante | Store |
| `StoreViewModel` | Fix aplicado + medio pendiente | Store |
| `SupportFeatureModule.kt` | Sin hallazgo relevante | Support |
| `SupportIntent` | Sin hallazgo relevante | Support |
| `SupportModels.kt` | Sin hallazgo relevante | Support |
| `SupportScreen.kt` | Medio pendiente | Support |
| `SupportState` | Sin hallazgo relevante | Support |
| `SupportViewModel` | Medio pendiente | Support |
| `TagsFeatureModule.kt` | Sin hallazgo relevante | Tags |
| `TagsIntent` | Sin hallazgo relevante | Tags |
| `TagsScreen.kt` | Fix aplicado | Tags |
| `TagsState` | Sin hallazgo relevante | Tags |
| `TagsViewModel` | Fix aplicado | Tags |
| `TicketsFeatureModule.kt` | Sin hallazgo relevante | Tickets |
| `TicketsIntent` | Sin hallazgo relevante | Tickets |
| `TicketsModels.kt` | Medio pendiente | Tickets |
| `TicketsScreen.kt` | Medio pendiente | Tickets |
| `TicketsState` | Sin hallazgo relevante | Tickets |
| `TicketsViewModel` | Medio pendiente | Tickets |
| `UsersFeatureModule.kt` | Sin hallazgo relevante | Users |
| `UsersIntent` | Sin hallazgo relevante | Users |
| `UsersScreen.kt` | Fix aplicado | Users |
| `UsersState` | Sin hallazgo relevante | Users |
| `UsersViewModel` | Sin hallazgo relevante | Users |
| `Estructura por modulos` | Sin hallazgo relevante | Arquitectura general |
| `Separacion commonMain / androidMain / iosMain` | Alto, no corregido | Arquitectura general |
| `Patron Screen - State - Intent - ViewModel` | Medio pendiente | Arquitectura general |
| `Inyeccion de dependencias por FeatureModule` | Sin hallazgo relevante | Arquitectura general |
| `Archivos especificos por plataforma` | Alto, no corregido | Arquitectura general |
| `Organizacion de paquetes` | Sin hallazgo relevante | Arquitectura general |
| `Convenciones de nombres` | Bajo pendiente | Arquitectura general |
| `Recomendaciones de mantenimiento` | Medio pendiente | Arquitectura general |
