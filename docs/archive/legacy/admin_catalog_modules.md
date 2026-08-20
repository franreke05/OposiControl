# Modulos de catalogo admin (KMP + Ktor)

Guia tecnica para ampliar el panel admin sin rehacer la arquitectura. Documenta
el slice **Etiquetas + Comunidades** (2026-06-16) y, sobre todo, el **patron
repetible** para cualquier CRUD de catalogo nuevo.

## Principio de arquitectura

La app KMP **nunca** habla con Supabase para escribir. Todo pasa por la API Ktor:

```
Compose UI (feature) -> ViewModel (MVI) -> UseCase -> Repository (interface, domain)
   -> KtorXxxRepositoryImpl (data) --HTTP--> Ktor /content/* (auth bearer)
       -> SupabaseContentGateway --PostgREST--> Supabase (service role key)
```

La `service role key` vive solo en el backend (variable de entorno). El cliente
solo conoce `baseUrl` + un token bearer.

## Capas y donde vive cada cosa

| Capa | Modulo | Ejemplo del slice |
| --- | --- | --- |
| Modelo dominio | `:domain` `model/` | `Tag`, `Community` |
| Validacion | `:domain` `validation/` | `EtiquetaValidator`, `ComunidadValidator`, `ValidationResult` |
| Contrato repo | `:domain` `repository/` | `TagsRepository`, `CommunitiesRepository` |
| Use cases | `:domain` `usecase/<x>/` | `Observe/Refresh/Upsert/Delete` |
| Impl repo (HTTP) | `:data` `repository/` | `KtorTagsRepositoryImpl`, `KtorCommunitiesRepositoryImpl` |
| DI data | `:data` `di/DataModule.kt` | `single<TagsRepository> { ... }` |
| Feature MVI + UI | `:features:<x>` | `:features:tags`, `:features:communities` |
| DI feature | `:features:<x>/di` | `tagsFeatureModule`, `communitiesFeatureModule` |
| Backend gateway | `:backend:ktor` `Application.kt` (`SupabaseContentGateway`) | `listEtiquetas/saveEtiqueta/...` |
| Backend rutas | `:backend:ktor` `Application.kt` (`route("/content")`) | `route("/etiquetas")`, `route("/comunidades")` |

## Receta: anadir un nuevo CRUD de catalogo (p. ej. "Convocatorias")

1. **Dominio** (`:domain`):
   - `model/Convocatoria.kt` (data class, ids como `String`).
   - `validation/CatalogValidators.kt`: anade `ConvocatoriaValidator` con `validate { require(...) { FieldError(...) } }`.
   - `repository/ConvocatoriasRepository.kt`: `observe/refresh/upsert/delete`.
   - `usecase/convocatorias/ConvocatoriaUseCases.kt`: 4 use cases; el Upsert llama al validador.
2. **Data** (`:data`):
   - `repository/KtorConvocatoriasRepositoryImpl.kt` (copia `KtorTagsRepositoryImpl`; cambia ruta y DTO).
   - Registra el `single<ConvocatoriasRepository>` en `di/DataModule.kt`.
3. **Backend** (`:backend:ktor` `Application.kt`):
   - DTO `ConvocatoriaDto` + `ConvocatoriaRow` + mappers `toDto/toRow`.
   - Metodos en `SupabaseContentGateway`: `listConvocatorias/saveConvocatoria/deleteConvocatoria`.
   - Bloque `route("/convocatorias") { get/post/put/delete }` dentro de `authenticate("gateway-auth") { route("/content") { ... } }`.
4. **Feature** (`:features:convocatorias`):
   - `build.gradle.kts` (copia el de `:features:tags`).
   - `presentation/`: `Intent`, `State`, `ViewModel` (extiende `MviViewModel`), `Screen` (usa componentes de `:core:ui`: `CrudSplitLayout`, `AdminTable`, `FormSection`, `GlassTextField`...).
   - `di/ConvocatoriasFeatureModule.kt`.
5. **Registro** (composeApp + settings):
   - `settings.gradle.kts`: `include(":features:convocatorias")`.
   - `composeApp/build.gradle.kts`: `implementation(projects.features.convocatorias)`.
   - `app/bootstrap/AppModules.kt`: anade `convocatoriasFeatureModule`.
   - `app/navigation/AppDestination.kt`: nuevo valor en `enum AdminSection` (aparece solo en el sidebar).
   - `app/root/AppRoot.kt`: nuevo `case` en `adminSectionRoute` (el `when` es exhaustivo: obliga).
   - `app/root/GlobalTagSearch.kt`: anade el caso en `searchSectionKey()` (otro `when` exhaustivo) y su inverso.

> Atajo mental: el compilador te guia. Anadir un valor a `AdminSection` rompe los
> dos `when` exhaustivos hasta que cubras la seccion nueva.

## Endpoints que el cliente consume (slice actual)

Todos bajo el backend Ktor, autenticados con `Authorization: Bearer <token>`.

| Metodo | Ruta | Uso |
| --- | --- | --- |
| GET | `/content/etiquetas` | listar etiquetas |
| POST | `/content/etiquetas` | crear etiqueta |
| PUT | `/content/etiquetas` | actualizar etiqueta (id en el body) |
| DELETE | `/content/etiquetas/{etiquetaId}` | borrar etiqueta |
| GET | `/content/comunidades` | listar comunidades |
| POST | `/content/comunidades` | crear comunidad |
| PUT | `/content/comunidades` | actualizar comunidad (id en el body) |
| DELETE | `/content/comunidades/{comunidadId}` | borrar comunidad |

> NOTA de convencion: el spec objetivo describe `/api/v1/admin/*`. El backend
> real hoy expone lecturas publicas en `/v1/*` y escrituras admin en `/content/*`.
> Estos modulos siguen la convencion **real** existente para no introducir una
> tercera. Renombrar todo a `/api/v1/admin/*` es una tarea transversal aparte.

## Esquema real Supabase (IMPORTANTE)

El SQL de `docs/supabase/*.sql` describe un esquema en **singular** con ids
`integer` que **NO coincide** con la base desplegada. La base real usa:

- Nombres de tabla en **plural espanol**: `especialidades`, `examenes`,
  `preguntas`, `etiquetas`, `comunidades_autonomas`, ...
- **PK `uuid`** (no integer identity).
- Columnas en espanol: `nombre`, `slug`, `codigo`, `descripcion`, `activa`,
  `posicion` (no `orden`), `creado_en`, `actualizado_en`.

Por eso el gateway de lectura `SupabaseV1Gateway` usa plural y funciona, mientras
el gateway legacy `SupabaseContentGateway` (singular) estaba roto contra la base
real. Las tablas `etiquetas` y `comunidades_autonomas` **ya existen** (no hay que
crearlas). Verificado CRUD completo (POST/GET/PUT/DELETE) el 2026-06-16.

Columnas reales:
- `etiquetas`: id(uuid), nombre, slug, descripcion, color_hex, activa, creado_en, actualizado_en.
- `comunidades_autonomas`: id(uuid), codigo, nombre, slug, activa, posicion, creado_en, actualizado_en.

## TODOs para conectar/produccion

- [ ] **Etiquetas**: el editor solo gestiona nombre + slug. La tabla real tiene
      ademas `descripcion`, `color_hex`, `activa`: anadirlos al modelo/DTO/UI si
      se quieren gestionar.
- [ ] **Auth real**: hoy `gateway-auth` valida un token bearer compartido
      (`OPOSICONTROL_BACKEND_TOKEN`), no el JWT por-usuario de Supabase ni roles
      finos. Falta validar el JWT de Supabase Auth y mapear permisos por rol
      (ver `AuthorizationService` + `Role/Permission` en `:domain`).
- [ ] **Auditoria**: registrar en `audit_log` cada upsert/delete de catalogo
      (el backend ya tiene `adminGateway` con auditoria para otras entidades).
- [ ] **Slug unico**: el backend deriva slug del nombre si va vacio; conviene
      validar colision de slug y devolver 409 legible.
- [ ] **Tests**: anadir `EtiquetaValidator`/`ComunidadValidator` unit tests y
      `TagsViewModelTest`/`CommunitiesViewModelTest` (ver `SpecialtiesViewModelTest`
      como plantilla).
- [ ] **Borrado logico**: para catalogos enlazados a contenido publicado, preferir
      `activa=false` sobre delete fisico (Comunidades ya tiene `activa`).
