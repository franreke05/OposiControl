# Clasificacion de DTOs duplicados (backend <-> cliente admin / OposiBot)

Estado: clasificacion, **sin unificar nada** (no hay modulo de contrato compartido ni se fusionan clases). Verificacion de 2026-10-01
sobre 32 pares con el mismo nombre (31 backend OC <-> cliente admin OC, 1 backend OC <-> OposiBot), comparados campo a campo:
**0 pares con deriva hoy**. 25 son duplicacion semantica real (mismo cable, riesgo de deriva futuro, hoy sincronizados) y 7 son
separacion legitima (las tablas listan las clases DTO concretas: 26 y 6; la septima separacion es la regla general de abajo). Ningun par se recomienda para un modulo compartido: los DTOs privados del cliente son la frontera de lectura
(decodifican el cable y mapean a dominio con `toDomain()`), y compartirlos arrastraria serializadores y detalles de Supabase al
modulo KMP `data`.

`DtoClassificationDocTest` comprueba que cada DTO de backend de las tablas existe todavia en `backend/ktor/src/main`; un DTO
renombrado o retirado obliga a revisar este documento.

## REAL_SEMANTIC_DUPLICATION (misma forma de cable; hoy sin deriva)

El cliente anade valores por defecto que el backend no tiene: es tolerancia de lectura, no deriva.

| DTO backend | Contraparte cliente | Clase | Nota |
| --- | --- | --- | --- |
| `AdminAuditoriaDto` | `data/.../remote/api/AuditApi.kt` | REAL_SEMANTIC_DUPLICATION | Tambien en OpenAPI (`/api/v1/admin/auditoria`). |
| `AdminAuditoriasResponse` | `AuditApi.kt` | REAL_SEMANTIC_DUPLICATION | |
| `AdminEspecialidadDto` | `KtorAdminV1ContentRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | El cliente pone valor por defecto a `nombre`. |
| `AdminEspecialidadResponse` | `KtorAdminV1ContentRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | |
| `AdminEspecialidadesResponse` | `KtorAdminV1ContentRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | |
| `AdminEspecialidadCapacidadesDto` | `KtorAdminV1ContentRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | 9 campos iguales. |
| `AdminEspecialidadCapacidadesResponse` | `KtorAdminV1ContentRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | |
| `AdminExamenDto` | `KtorAdminV1ContentRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | 20 campos iguales; defaults de cliente en `especialidadId`/`titulo`. |
| `AdminExamenResponse` | `KtorAdminV1ContentRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | |
| `AdminExamenesResponse` | `KtorAdminV1ContentRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | |
| `AdminPreguntaDto` | `KtorAdminV1ContentRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | |
| `AdminPreguntaResponse` | `KtorAdminV1ContentRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | |
| `AdminPreguntasResponse` | `KtorAdminV1ContentRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | |
| `AdminOpcionDto` | `KtorAdminV1ContentRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | |
| `AdminRecursoResponse` | `KtorAdminV1ContentRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | |
| `AdminRecursosResponse` | `KtorAdminV1ContentRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | |
| `AdminLoteDto` | `KtorAdminV1ContentRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | |
| `AdminLotesResponse` | `KtorAdminV1ContentRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | |
| `AdminLoteResponse` | `KtorAdminV1ContentRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | |
| `AdminStagingItemDto` | `KtorAdminV1ContentRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | 19 campos iguales. |
| `AdminStagingResponse` | `KtorAdminV1ContentRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | |
| `AdminImportDuplicadoDto` | `KtorAdminV1ContentRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | |
| `GrantTicketsResponse` | `KtorModerationRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | El cliente pone valor por defecto a `saldo`. |
| `NewsAssetUploadDto` | `KtorNewsRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | 3 campos iguales. |
| `NoticiaDto` | `KtorNewsRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | 11 campos iguales. |
| `NoticiaLayerDto` | `KtorNewsRepositoryImpl.kt` | REAL_SEMANTIC_DUPLICATION | **Unico par con modo de fallo latente plausible**: 17 campos iguales; el backend tiene defaults en `nombre/tipo/contenido/x/y/width/height/opacity/visible/orden` y el cliente ninguno, de modo que una respuesta que omita un campo con default (`encodeDefaults=false`) romperia la decodificacion del cliente. Sin deriva hoy. |

## LEGITIMATE_SEPARATION (no se toca)

| DTO backend | Contraparte | Clase | Motivo |
| --- | --- | --- | --- |
| `AccountDeletionRequest` | OposiBot `ProfileApi.kt` | LEGITIMATE_SEPARATION | En el backend es `data class` con `confirmation` obligatorio; en OposiBot deliberadamente NO es `data class` para que `toString` no filtre `currentPassword`, y `confirmation` es una constante calculada (`ELIMINAR`). El OpenAPI (`profiles.yaml`) exige `confirmation`. |
| `AdminRecursoDto` | `KtorAdminV1ContentRepositoryImpl.kt` | LEGITIMATE_SEPARATION | El cliente omite `rutaStorage`, `mimeType` y `urlAcceso` (subconjunto por diseno: 8 de 11 campos). |
| `AdminImportResultadoDto` | `KtorAdminV1ContentRepositoryImpl.kt` | LEGITIMATE_SEPARATION | Todo con defaults en el cliente (tolerancia a backends antiguos o parciales). |
| `AdminLoteImportResponse` | `KtorAdminV1ContentRepositoryImpl.kt` | LEGITIMATE_SEPARATION | `resultado` no nulo en backend, nulo en cliente (tolerancia). |
| `AdminLoteResumenDto` | `KtorAdminV1ContentRepositoryImpl.kt` | LEGITIMATE_SEPARATION | `Int = d` en backend, `Int? = d` en cliente (tolerancia a null). |
| `AdminRolesV1Response` | `RolesApi.kt` | LEGITIMATE_SEPARATION | Solo el DTO del elemento cambia de nombre por lado (`AdminV1RoleDto` / `AdminRoleV1Dto`): separacion de nombres, mismos campos. |

Regla general: los `private data class` del cliente son la frontera de lectura prevista; no se comparten con el backend.

## Proteccion existente y huecos (sin trabajo nuevo en este cambio)

- OpenAPI (`openapi/catalog/catalog.yaml`, `root.yaml`) describe las superficies admin con OTROS nombres de esquema (`AdminExamen`,
  `AdminPregunta`, `AdminLote`, `AdminStagingItem`...). `OpenApiContractTest` y `Slice1ContractConformanceTest` comprueban rutas y
  contratos de perfil, **no** la equivalencia de campos entre los DTOs Kotlin y los esquemas, y nada compara DTO de backend con DTO de
  cliente. Las noticias (`/noticias*`, `NoticiaDto`) no estan en el OpenAPI.
- Los tests del cliente (`KtorAdminV1ContentRepositoryImplTest`) usan JSON escrito a mano: no detectan deriva del backend.
- La duplicacion mayor esta DENTRO del backend (fila de BD vs cable vs legacy-vs-canonico: `ComunidadRow`/`AdminComunidadDto`/
  `V1ComunidadRow`, `EtiquetaDto`/`TagDto`/`TagRow`, ...). Parte desaparece al retirar rutas legacy (bloqueado por telemetria, ver
  `docs/integration/api-redesign/LEGACY_ROUTE_REGISTER.md`); los pares `AdminXxxDto`/`XxxRow` son capas intencionadas.

## Seguimiento propuesto (NO implementado aqui; requiere decision, el plan validado no lo exige para cerrar la clasificacion)

1. Noticias: fixture JSON dorado (`NoticiaDto`/`NoticiaLayerDto`/`NewsAssetUploadDto`) serializado con el `Json` de produccion y
   decodificado por una prueba del cliente; decidir los defaults de `NoticiaLayerDto` del cliente (o documentar que el backend siempre
   envia todos los campos).
2. Grupo de contenido admin: una prueba de backend que compare los nombres de campo de cada DTO con las propiedades del esquema
   OpenAPI correspondiente mediante un mapa explicito que liste todos los pares y las diferencias intencionadas (p. ej. `AdminRecursoDto`).
