package com.oposicontrol.admin.data.inventory

/**
 * Reglas fijadas de la completitud endurecida (R5-HARDEN, INV-NETWORK-COMPLETE). SOLO PRUEBAS. Cada entrada es
 * fichero + patron/metodo + justificacion; una entrada sin uso, con cuenta distinta o sin justificacion FALLA.
 */
private const val DATA_MAIN = "data/src/commonMain/kotlin/com/oposicontrol/admin/data"
private const val API = "$DATA_MAIN/remote/api"
private const val REPO = "$DATA_MAIN/repository"

/** Ninguna exclusion descuenta una llamada con receptor (`(?<![.\w])`): un `client.get<X>(` nuevo siempre cuenta. */
internal val SITE_EXCLUSIONS: List<SiteExclusion> = listOf(
    SiteExclusion(
        "$REPO/KtorAdminV1ContentRepositoryImpl.kt", """(?<![.\w])put\((key|"\w+"), JsonPrimitive\(""", 21,
        "JsonObjectBuilder.put de toPatchBody/toExamPatchBody/toRafagaPatchBody: construye el cuerpo, no es una peticion",
    ),
    SiteExclusion(
        "$REPO/KtorDashboardOverviewRepositoryImpl.kt", """(?<![.\w])put\(DashboardBlock\.""", 6,
        "MutableMap.put de los bloques del informe: no es una peticion",
    ),
    SiteExclusion(
        "$REPO/KtorDashboardOverviewRepositoryImpl.kt", """fun <reified R> get\(""", 1,
        "definicion del ayudante privado get; su unico egreso es el client.get interior (el sitio contado)",
    ),
    SiteExclusion(
        "$REPO/KtorDashboardOverviewRepositoryImpl.kt", """(?<![.\w])get<DashboardOverviewDto>\(""", 1,
        "invocacion del ayudante privado get desde getOverview (punto de entrada con fila)",
    ),
    SiteExclusion(
        "$REPO/KtorMarketCatalogRepositoryImpl.kt", """fun <reified R> post\(""", 1,
        "definicion del ayudante privado post; su unico egreso es el client.post interior (el sitio contado)",
    ),
    SiteExclusion(
        "$REPO/KtorMarketCatalogRepositoryImpl.kt", """(?<![.\w])post<\w+Response>\(""", 4,
        "invocaciones del ayudante privado post desde los 4 save*; cada save* es un punto de entrada con fila",
    ),
    SiteExclusion(
        "$REPO/KtorProductAnalyticsRepositoryImpl.kt", """fun <reified R> get\(""", 1,
        "definicion del ayudante privado get; su unico egreso es el client.get interior (el sitio contado)",
    ),
    SiteExclusion(
        "$REPO/KtorProductAnalyticsRepositoryImpl.kt", """(?<![.\w])get<\w+AnalyticsDto>\(""", 6,
        "invocaciones del ayudante privado get en las 6 ramas de getSection (un solo punto de entrada)",
    ),
    SiteExclusion(
        "$REPO/KtorRankingsRepositoryImpl.kt", """fun <reified R> get\(""", 1,
        "definicion del ayudante privado get; su unico egreso es el client.get interior (el sitio contado)",
    ),
    SiteExclusion(
        "$REPO/KtorRankingsRepositoryImpl.kt", """(?<![.\w])get<\w+>\(""", 5,
        "invocaciones del ayudante privado get desde los 5 metodos publicos (cada uno es un punto de entrada)",
    ),
    SiteExclusion(
        "$REPO/KtorSystemStatusRepositoryImpl.kt", """(?<![.\w])get\(\)""", 5,
        "get() de Koin en ktorSystemStatusRepository (4) y su mencion en el KDoc (1): un get de Ktor siempre lleva URL",
    ),
)

/**
 * Metodos publicos SIN HTTP (fichero -> metodo -> por que). No llevan fila porque no envian nada (una fila sin llamada falla).
 */
internal val NON_HTTP_MEMBERS: Map<String, Map<String, String>> = mapOf(
    "$API/RolesApi.kt" to mapOf("upsertRole" to "lanza error(): roles de solo lectura en V1, no envia peticion"),
    "$API/UserDetailApi.kt" to mapOf("toDomain" to "mapeador DTO -> dominio (fachada UserDetailApiKt)"),
    "$API/UsersApi.kt" to mapOf(
        "deleteUser" to "lanza error(): no hay borrado de usuarios desde KMP, no envia peticion",
        "collectAllUsers" to "paginador puro: recibe la funcion de pagina (el client.get de getUsers); no toca el cliente",
        "toUserDto" to "mapeador DTO -> DTO (fachada UsersApiKt)",
        "toUserDtos" to "mapeador DTO -> DTO (fachada UsersApiKt)",
    ),
    "$REPO/KtorAdminV1ContentRepositoryImpl.kt" to mapOf(
        "observeAllExams" to "StateFlow local", "observeExams" to "StateFlow local",
        "observeRafagas" to "StateFlow local", "observeSpecialties" to "StateFlow local",
        "toPatchBody" to "constructor del cuerpo JSON (fachada)",
    ),
    "$REPO/KtorCommerceRepositoryImpl.kt" to mapOf(
        "observeBurstCampaigns" to "StateFlow local", "observeLimitedResources" to "StateFlow local",
        "observeStoreItems" to "StateFlow local", "observeStoreOffers" to "StateFlow local",
    ),
    "$REPO/KtorCommunitiesRepositoryImpl.kt" to mapOf("observeCommunities" to "StateFlow local"),
    "$REPO/KtorDashboardOverviewRepositoryImpl.kt" to mapOf("toDomain" to "mapeador DTO -> dominio (fachada)"),
    "$REPO/KtorMarketCatalogRepositoryImpl.kt" to mapOf("requireSuccess" to "extension de HttpResponse: lee el estado de una respuesta ya recibida"),
    "$REPO/KtorNewsRepositoryImpl.kt" to mapOf("observeNews" to "StateFlow local"),
    "$REPO/KtorRankingsRepositoryImpl.kt" to mapOf(
        "requireSuccess" to "extension de HttpResponse: lee el estado de una respuesta ya recibida",
        // nombre sobrecargado: cada firma se justifica por separado (una sobrecarga nueva no queda cubierta por el nombre)
        "toDomain(LeagueLevelDto)" to "mapeador DTO -> dominio (fachada)",
        "toDomain(RankingEntryDto)" to "mapeador DTO -> dominio (fachada)",
        "toDomain(RankingPageDto)" to "mapeador DTO -> dominio (fachada)",
        "toDomain(UserStandingDto)" to "mapeador DTO -> dominio (fachada)",
    ),
    "$REPO/KtorSystemStatusRepositoryImpl.kt" to mapOf(
        "ktorSystemStatusRepository" to "factoria Koin: construye el repositorio, no envia peticion",
        "toDomain" to "mapeador DTO -> dominio (fachada)",
        "toSystemStatusLevel" to "mapeador de texto -> nivel (fachada)",
    ),
    "$REPO/KtorTagsRepositoryImpl.kt" to mapOf("observeTags" to "StateFlow local"),
)

/**
 * Ficheros de produccion (fuera de `backend/`) que nombran `HttpClient` o importan `io.ktor.client.request|statement` pero NO
 * son clientes HTTPCALL. Deben tener 0 sitios de egreso; cualquier otro fichero que toque el cliente tiene que estar en el tripwire.
 */
internal val HTTP_REFERENCE_NON_CLIENTS: Map<String, String> = mapOf(
    "core/network/src/commonMain/kotlin/com/oposicontrol/admin/core/network/ApiClientFactory.kt" to
        "unica construccion de HttpClient (fijada por EGRESS_CTOR en scripts/verify-no-direct-supabase.sh); no envia peticiones",
    "$API/AdminApiAuth.kt" to "adminAuthHeaders: firma un HttpRequestBuilder ya creado por un sitio contado; no envia peticiones",
    "$API/AdminSessionRefresh.kt" to
        "interceptor HttpSend: re-ejecuta una peticion ya creada por un sitio contado (401/caducidad); no crea peticiones",
)
