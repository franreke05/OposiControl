package com.oposicontrol.backend

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * Rankings y ligas: superficie de SOLO LECTURA del panel admin.
 *
 * La clasificación la calcula el producto a partir de la actividad real del
 * usuario (nota media móvil sobre una ventana de preguntas). El panel la
 * consulta y la explica; **no la ajusta**. Por eso aquí no hay ni un POST, ni un
 * PATCH ni un DELETE: una posición corregida a mano dejaría de significar lo que
 * dice significar, y no habría forma de distinguir un ranking calculado de uno
 * retocado. Si un ranking está mal, se arregla el cálculo, no la fila.
 *
 * Estado real de los datos en el momento de escribir esto: `ligas_nivel` tiene
 * sus 5 niveles sembrados (dato real), mientras que `estado_competitivo_usuario`
 * está aprovisionada y vacía — nadie ha alcanzado todavía el mínimo de preguntas
 * de la primera liga. Esa diferencia se transmite tal cual: una tabla vacía se
 * dice vacía, y una tabla que no se ha podido leer se dice ilegible. Nunca se
 * rellena con ceros (misma regla que `AdminDashboardDto.unavailableMetrics`).
 */

// ---------- Regla de producto: ventana de cálculo por liga ----------

/**
 * Ventana de cálculo que le corresponde a cada liga **según la regla de
 * producto**: la liga de entrada promedia las últimas 50 preguntas y el resto
 * las últimas 100.
 *
 * Ojo: el valor que se sirve al panel es SIEMPRE el de la columna
 * `ventana_calculo` de la base de datos, no éste. Esta función existe sólo para
 * poder señalar una divergencia entre lo que la base dice y lo que el producto
 * definió — si alguien edita la tabla y deja una liga con una ventana distinta,
 * las notas medias de esa liga dejan de ser comparables con las del resto y el
 * panel debe poder avisar en vez de mostrar la cifra como si nada.
 *
 * Devuelve `null` para un código desconocido: una liga nueva no tiene regla
 * escrita aquí y no se inventa una.
 */
internal fun ventanaCalculoEsperada(codigoLiga: String): Int? = when (codigoLiga.trim().lowercase()) {
    "liga_50" -> 50
    "liga_200", "liga_500", "liga_800", "liga_1000" -> 100
    else -> null
}

// ---------- DTOs ----------

/**
 * Nivel de liga (`ligas_nivel`). Es el único dato de este módulo que hoy está
 * realmente poblado.
 *
 * `preguntasMinimas` es el umbral de entrada y `ventanaCalculo` cuántas
 * preguntas entran en la nota media móvil de esa liga. Los dos se sirven como
 * están en la base.
 */
@Serializable
data class AdminLigaNivelDto(
    val id: String = "",
    val codigo: String = "",
    val nombre: String = "",
    @SerialName("preguntas_minimas") val preguntasMinimas: Int = 0,
    @SerialName("ventana_calculo") val ventanaCalculo: Int = 0,
    val activa: Boolean = true,
    val posicion: Int = 0,
) {
    /** Ventana que la regla de producto asigna a este código, o null si no hay regla. */
    val ventanaEsperadaPorRegla: Int? get() = ventanaCalculoEsperada(codigo)

    /**
     * La ventana almacenada no coincide con la regla de producto. Con una liga
     * desconocida es `false`: sin regla escrita no hay divergencia que declarar.
     */
    val ventanaDivergeDeLaRegla: Boolean
        get() = ventanaEsperadaPorRegla?.let { it != ventanaCalculo } ?: false
}

@Serializable
data class AdminLigasNivelResponse(
    val data: List<AdminLigaNivelDto> = emptyList(),
)

/**
 * Una fila de clasificación: el estado competitivo de un usuario más el nombre
 * con el que mostrarlo.
 *
 * Todo lo numérico es nullable a propósito. Un usuario recién entrado en liga
 * puede no tener aún posición global calculada, y un `0` en ese hueco se leería
 * como "va el primero", que es exactamente lo contrario.
 *
 * `percentilGlobal` es un indicador comparativo **interno** de la app: dice
 * dónde queda este usuario frente a los demás usuarios de OposiBot. No es, ni se
 * puede presentar como, una probabilidad de obtener plaza.
 */
@Serializable
data class AdminRankingEntryDto(
    @SerialName("usuario_id") val usuarioId: String = "",
    @SerialName("nombre_mostrar") val nombreMostrar: String? = null,
    val username: String? = null,
    @SerialName("liga_nivel_id") val ligaNivelId: String? = null,
    @SerialName("liga_codigo") val ligaCodigo: String? = null,
    @SerialName("liga_nombre") val ligaNombre: String? = null,
    @SerialName("ventana_calculo") val ventanaCalculo: Int? = null,
    @SerialName("preguntas_validas_ranking") val preguntasValidasRanking: Int? = null,
    @SerialName("nota_media_actual") val notaMediaActual: Double? = null,
    @SerialName("nota_referencia_evolucion") val notaReferenciaEvolucion: Double? = null,
    @SerialName("evolucion_nota") val evolucionNota: Double? = null,
    @SerialName("preguntas_desde_ultima_evolucion") val preguntasDesdeUltimaEvolucion: Int? = null,
    @SerialName("ranking_liga_posicion") val rankingLigaPosicion: Int? = null,
    @SerialName("ranking_global_posicion") val rankingGlobalPosicion: Int? = null,
    @SerialName("percentil_global") val percentilGlobal: Double? = null,
    @SerialName("actualizado_en") val actualizadoEn: String? = null,
)

/**
 * Página de clasificación.
 *
 * `total` es nullable con el mismo criterio que [AdminUsersPage]: si el conteo
 * exacto no se puede obtener se dice que no se sabe, en lugar de mandar un 0 que
 * la UI pintaría como "no compite nadie". Con `data` vacía, `total = 0` y
 * `total = null` son dos cosas distintas y la pantalla las distingue.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdminRankingPageDto(
    @EncodeDefault val data: List<AdminRankingEntryDto> = emptyList(),
    val total: Int? = null,
    @EncodeDefault val limit: Int = AdminPageRequest.DEFAULT_LIMIT,
    @EncodeDefault val offset: Int = 0,
    /**
     * Partes de la respuesta que no se pudieron leer (ver [AdminRankingsSection]).
     * Vacío = todo lo devuelto es real.
     */
    @EncodeDefault val seccionesNoDisponibles: List<String> = emptyList(),
)

/**
 * Posición competitiva de un usuario concreto.
 *
 * `competitivo == null` significa que ese usuario **todavía no compite**: la fila
 * de `estado_competitivo_usuario` se crea al alcanzar el mínimo de preguntas de
 * la primera liga. Eso es un estado normal, no un error, y se distingue de
 * "no se pudo leer" mediante [seccionesNoDisponibles].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdminUserStandingDto(
    @SerialName("usuario_id") val usuarioId: String = "",
    @SerialName("nombre_mostrar") val nombreMostrar: String? = null,
    val username: String? = null,
    val competitivo: AdminRankingEntryDto? = null,
    val liga: AdminLigaNivelDto? = null,
    @EncodeDefault val seccionesNoDisponibles: List<String> = emptyList(),
)

@Serializable
data class AdminUserStandingResponse(
    val data: AdminUserStandingDto,
)

/** Claves de sección para los `seccionesNoDisponibles` de este módulo. */
object AdminRankingsSection {
    /** El conteo exacto de la página (cabecera `Content-Range`) no se pudo leer. */
    const val TOTAL = "total"

    /** No se pudieron resolver los nombres de los usuarios de la página. */
    const val NOMBRES = "nombres"

    /** No se pudo leer el catálogo `ligas_nivel` con el que se decoran las filas. */
    const val CATALOGO_LIGAS = "catalogoLigas"

    /** No se pudo leer `estado_competitivo_usuario` para ese usuario. */
    const val COMPETITIVO = "competitivo"

    /** No se pudo leer `perfiles_usuario` para ese usuario. */
    const val PERFIL = "perfil"
}

/**
 * Lectura honesta de una página: separa "no hay nadie" de "no lo sé".
 *
 * Existe como función pura para poder probar la semántica sin red, y para que la
 * decisión no se repita (mal) en cada pantalla.
 */
enum class AdminRankingPageState {
    /** Hay filas reales en la página. */
    CON_DATOS,

    /** Cero filas y conteo exacto confirmado a 0: la tabla está realmente vacía. */
    VACIO_REAL,

    /** Cero filas pero sin conteo fiable: no se puede afirmar que esté vacía. */
    NO_DISPONIBLE,
}

fun AdminRankingPageDto.state(): AdminRankingPageState = when {
    data.isNotEmpty() -> AdminRankingPageState.CON_DATOS
    total == null || AdminRankingsSection.TOTAL in seccionesNoDisponibles -> AdminRankingPageState.NO_DISPONIBLE
    else -> AdminRankingPageState.VACIO_REAL
}

// ---------- Construcción de filtros ----------

/**
 * Filtro `usuario_id=in.(...)` para decorar SÓLO los ids de la página actual.
 *
 * Devuelve `null` con la lista vacía, y quien llama debe entonces no hacer la
 * petición: `in.()` es sintaxis inválida, y la tentación alternativa —traerse
 * `perfiles_usuario` entero para cruzarlo en Kotlin— es justo el bug que ya se
 * corrigió una vez en este repositorio (además de estar sujeto al `max-rows`
 * silencioso de PostgREST, con lo que ni siquiera traería la tabla completa).
 */
internal fun usuarioIdInFilter(ids: Collection<String>): String? {
    val unicos = ids.filter { it.isNotBlank() }.distinct()
    if (unicos.isEmpty()) return null
    return "usuario_id=in.(${unicos.joinToString(",")})"
}

// ---------- Rutas ----------

/**
 * Superficie HTTP de rankings. Sólo GET: ver el KDoc de cabecera del fichero.
 */
fun Route.adminRankingsRoutes(gateway: AdminRankingsGateway) {
    route("/api/v1/admin/rankings") {
        // Catálogo de niveles de liga. Es el dato real de este módulo.
        get("/ligas") {
            call.respond(AdminLigasNivelResponse(gateway.listLigasNivel()))
        }

        // Clasificación global paginada.
        get("/global") {
            call.respond(gateway.globalRanking(call.rankingPage()))
        }

        // Clasificación dentro de un nivel de liga.
        get("/liga/{ligaNivelId}") {
            val ligaNivelId = call.requiredPath("ligaNivelId").validatedUuid("ligaNivelId")
            call.respond(gateway.ligaRanking(ligaNivelId, call.rankingPage()))
        }

        // Posición competitiva de un usuario concreto.
        get("/usuario/{usuarioId}") {
            val usuarioId = call.requiredPath("usuarioId").validatedUuid("usuarioId")
            call.respond(AdminUserStandingResponse(gateway.userStanding(usuarioId)))
        }

        // Búsqueda de usuarios por nombre para llegar a su ficha competitiva.
        // Vive aquí y no en usuarios porque el criterio de búsqueda de esta
        // pantalla es "encuéntrame a quien quiero mirar en el ranking".
        get("/usuarios") {
            val query = call.request.queryParameters["q"].orEmpty()
            call.respond(gateway.searchUsuarios(query, call.rankingPage()))
        }
    }
}

/**
 * Paginación saneada desde la query string. Reutiliza [AdminPageRequest], que ya
 * recorta el `limit` al tope duro: `?limit=999999` devuelve una página normal,
 * no la tabla entera.
 */
private fun io.ktor.server.application.ApplicationCall.rankingPage(): AdminPageRequest =
    AdminPageRequest.of(
        limit = request.queryParameters["limit"]?.toIntOrNull(),
        offset = request.queryParameters["offset"]?.toIntOrNull(),
    )

// ---------- Gateway Supabase ----------

/**
 * Acceso propio a Supabase para rankings.
 *
 * No comparte instancia con [AdminV1Gateway] a propósito: éste no tiene ni una
 * sola operación de escritura, y tenerlo aparte hace evidente en el código —no
 * sólo en un comentario— que por aquí no se puede tocar una clasificación.
 * El cliente HTTP sí es el compartido ([newUpstreamHttpClient]) y cada petición
 * lleva [UPSTREAM_REQUEST_TIMEOUT]: una respuesta colgada de Supabase no debe
 * bloquear el hilo indefinidamente.
 */
class AdminRankingsGateway private constructor(
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
    private val json: Json,
    private val client: HttpClient = newUpstreamHttpClient(),
) {

    /** Columnas de `estado_competitivo_usuario` que necesita el panel. */
    private val columnasCompetitivo = listOf(
        "usuario_id",
        "liga_nivel_id",
        "preguntas_validas_ranking",
        "ventana_calculo",
        "nota_media_actual",
        "nota_referencia_evolucion",
        "evolucion_nota",
        "preguntas_desde_ultima_evolucion",
        "ranking_liga_posicion",
        "ranking_global_posicion",
        "percentil_global",
        "actualizado_en",
    ).joinToString(",")

    fun listLigasNivel(): List<AdminLigaNivelDto> =
        request("/rest/v1/ligas_nivel?select=*&order=posicion.asc").decodeList()

    /**
     * Clasificación global.
     *
     * Orden `nullslast`: quien todavía no tiene posición calculada va al final,
     * no al principio — en `asc` los NULL de Postgres irían primero y parecerían
     * los mejores clasificados.
     */
    fun globalRanking(page: AdminPageRequest): AdminRankingPageDto =
        rankingPage(filtro = null, orden = "ranking_global_posicion.asc.nullslast,nota_media_actual.desc.nullslast", page = page)

    fun ligaRanking(ligaNivelId: String, page: AdminPageRequest): AdminRankingPageDto =
        rankingPage(
            filtro = "liga_nivel_id=eq.$ligaNivelId",
            orden = "ranking_liga_posicion.asc.nullslast,nota_media_actual.desc.nullslast",
            page = page,
        )

    /**
     * Ficha competitiva de un usuario.
     *
     * El perfil y el estado competitivo se piden por separado porque significan
     * cosas distintas cuando faltan: sin perfil el usuario no existe (404 del
     * llamador), sin estado competitivo el usuario existe pero aún no compite.
     */
    fun userStanding(usuarioId: String): AdminUserStandingDto {
        val noDisponibles = linkedSetOf<String>()

        val perfil = runCatching {
            request("/rest/v1/perfiles_usuario?select=usuario_id,nombre_mostrar,username&usuario_id=eq.$usuarioId&limit=1")
                .decodeList<PerfilNombreRow>()
                .firstOrNull()
        }.getOrElse {
            noDisponibles += AdminRankingsSection.PERFIL
            null
        }

        val competitivo = runCatching {
            request("/rest/v1/estado_competitivo_usuario?select=$columnasCompetitivo&usuario_id=eq.$usuarioId&limit=1")
                .decodeList<EstadoCompetitivoRow>()
                .firstOrNull()
        }.getOrElse {
            noDisponibles += AdminRankingsSection.COMPETITIVO
            null
        }

        val ligas = runCatching { ligasPorId() }.getOrElse {
            noDisponibles += AdminRankingsSection.CATALOGO_LIGAS
            emptyMap()
        }
        val liga = competitivo?.ligaNivelId?.let(ligas::get)

        return AdminUserStandingDto(
            usuarioId = usuarioId,
            nombreMostrar = perfil?.nombreMostrar,
            username = perfil?.username,
            competitivo = competitivo?.toDto(nombre = perfil, liga = liga),
            liga = liga,
            seccionesNoDisponibles = noDisponibles.toList(),
        )
    }

    /**
     * Búsqueda de usuarios por nombre o username.
     *
     * Se devuelve como página de ranking con el estado competitivo ya resuelto:
     * la pantalla busca a alguien precisamente para ver su clasificación, y
     * obligarla a una segunda petición por cada resultado no aporta nada.
     * Una búsqueda vacía devuelve la primera página de usuarios, no la tabla:
     * el `limit` sigue acotado por [AdminPageRequest].
     */
    fun searchUsuarios(query: String, page: AdminPageRequest): AdminRankingPageDto {
        val termino = query.trim()
        val filtro = if (termino.isBlank()) null else "or=(${perfilSearchFilter(termino)})"
        val noDisponibles = linkedSetOf<String>()

        val perfiles = request(
            buildString {
                append("/rest/v1/perfiles_usuario?select=usuario_id,nombre_mostrar,username")
                if (filtro != null) append("&").append(filtro)
                append("&order=nombre_mostrar.asc.nullslast")
                append("&limit=${page.limit}&offset=${page.offset}")
            },
        ).decodeList<PerfilNombreRow>()

        val total = runCatching {
            exactCount(
                buildString {
                    append("/rest/v1/perfiles_usuario?select=usuario_id")
                    if (filtro != null) append("&").append(filtro)
                },
            )
        }.getOrElse {
            noDisponibles += AdminRankingsSection.TOTAL
            null
        }

        // Estado competitivo SÓLO de los ids de esta página.
        val filtroIds = usuarioIdInFilter(perfiles.map { it.usuarioId })
        val competitivoPorUsuario = if (filtroIds == null) {
            emptyMap()
        } else {
            runCatching {
                request("/rest/v1/estado_competitivo_usuario?select=$columnasCompetitivo&$filtroIds")
                    .decodeList<EstadoCompetitivoRow>()
                    .associateBy { it.usuarioId }
            }.getOrElse {
                noDisponibles += AdminRankingsSection.COMPETITIVO
                emptyMap()
            }
        }

        val ligas = runCatching { ligasPorId() }.getOrElse {
            noDisponibles += AdminRankingsSection.CATALOGO_LIGAS
            emptyMap()
        }

        return AdminRankingPageDto(
            data = perfiles.map { perfil ->
                val estado = competitivoPorUsuario[perfil.usuarioId]
                estado?.toDto(nombre = perfil, liga = estado.ligaNivelId?.let(ligas::get))
                    // Un usuario encontrado que aún no compite sigue siendo un
                    // resultado legítimo: se devuelve con los huecos vacíos, que
                    // es distinto de no devolverlo.
                    ?: AdminRankingEntryDto(
                        usuarioId = perfil.usuarioId,
                        nombreMostrar = perfil.nombreMostrar,
                        username = perfil.username,
                    )
            },
            total = total,
            limit = page.limit,
            offset = page.offset,
            seccionesNoDisponibles = noDisponibles.toList(),
        )
    }

    /**
     * Una página de clasificación, ya decorada con nombres y liga.
     *
     * Las tres consultas que hace están acotadas: la de filas por `limit/offset`,
     * la de nombres por los ids de esa página, y la de ligas por ser un catálogo
     * de cinco filas. Ninguna crece con el número de usuarios de la plataforma.
     */
    private fun rankingPage(filtro: String?, orden: String, page: AdminPageRequest): AdminRankingPageDto {
        val noDisponibles = linkedSetOf<String>()
        val sufijoFiltro = filtro?.let { "&$it" }.orEmpty()

        val filas = request(
            "/rest/v1/estado_competitivo_usuario?select=$columnasCompetitivo$sufijoFiltro" +
                "&order=$orden&limit=${page.limit}&offset=${page.offset}",
        ).decodeList<EstadoCompetitivoRow>()

        // Conteo server-side: `Prefer: count=exact` + `Range: 0-0`. Traerse las
        // filas para contarlas daría un número recortado en silencio por el
        // `max-rows` de PostgREST en cuanto la tabla creciera.
        val total = runCatching {
            exactCount("/rest/v1/estado_competitivo_usuario?select=usuario_id$sufijoFiltro")
        }.getOrElse {
            noDisponibles += AdminRankingsSection.TOTAL
            null
        }

        val nombres = nombresDeLaPagina(filas.map { it.usuarioId }, noDisponibles)
        val ligas = runCatching { ligasPorId() }.getOrElse {
            noDisponibles += AdminRankingsSection.CATALOGO_LIGAS
            emptyMap()
        }

        return AdminRankingPageDto(
            data = filas.map { fila ->
                fila.toDto(nombre = nombres[fila.usuarioId], liga = fila.ligaNivelId?.let(ligas::get))
            },
            total = total,
            limit = page.limit,
            offset = page.offset,
            seccionesNoDisponibles = noDisponibles.toList(),
        )
    }

    /**
     * Nombres de los usuarios de ESTA página y de ninguno más.
     *
     * Con la lista vacía no se hace petición: no hay nada que decorar y `in.()`
     * sería sintaxis inválida.
     */
    private fun nombresDeLaPagina(
        usuarioIds: List<String>,
        noDisponibles: MutableSet<String>,
    ): Map<String, PerfilNombreRow> {
        val filtro = usuarioIdInFilter(usuarioIds) ?: return emptyMap()
        return runCatching {
            request("/rest/v1/perfiles_usuario?select=usuario_id,nombre_mostrar,username&$filtro")
                .decodeList<PerfilNombreRow>()
                .associateBy { it.usuarioId }
        }.getOrElse {
            // La clasificación sin nombres sigue siendo cierta; se sirve marcada
            // en vez de tirar la página entera por un fallo decorativo.
            noDisponibles += AdminRankingsSection.NOMBRES
            emptyMap()
        }
    }

    /** Catálogo de ligas indexado por id. Son cinco filas: no merece caché. */
    private fun ligasPorId(): Map<String, AdminLigaNivelDto> =
        listLigasNivel().associateBy { it.id }

    /**
     * Conteo exacto sin traer filas: `Prefer: count=exact` devuelve el total en
     * `Content-Range` y `Range: 0-0` limita el cuerpo a una fila.
     *
     * Lanza si no hay total utilizable, para que quien llama pueda marcarlo como
     * no disponible en vez de confundirlo con un cero.
     */
    private fun exactCount(path: String): Int {
        val builder = HttpRequest.newBuilder(URI.create("$supabaseUrl$path"))
            .timeout(UPSTREAM_REQUEST_TIMEOUT)
            .header("apikey", serviceRoleKey)
            .header("Authorization", "Bearer $serviceRoleKey")
            .header("Content-Type", "application/json")
            .header("Prefer", "count=exact")
            .header("Range-Unit", "items")
            .header("Range", "0-0")
        val response = try {
            client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw IllegalStateException(
                "Supabase network error on COUNT ${path.substringBefore('?')}: ${error::class.simpleName}: ${error.message}",
                error,
            )
        }
        // 206 Partial Content es la respuesta normal a un Range acotado.
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(
                "Supabase COUNT ${path.substringBefore('?')} -> ${response.statusCode()}: ${response.body().take(200)}",
            )
        }
        return parseContentRangeTotal(response.headers().firstValue("content-range").orElse(null))
            ?: throw IllegalStateException("Supabase COUNT ${path.substringBefore('?')} no devolvió Content-Range utilizable.")
    }

    /** GET y sólo GET: este gateway no escribe. */
    private fun request(path: String): String {
        val builder = HttpRequest.newBuilder(URI.create("$supabaseUrl$path"))
            .timeout(UPSTREAM_REQUEST_TIMEOUT)
            .header("apikey", serviceRoleKey)
            .header("Authorization", "Bearer $serviceRoleKey")
            .header("Content-Type", "application/json")
        val response = try {
            client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw IllegalStateException(
                "Supabase network error on GET ${path.substringBefore('?')}: ${error::class.simpleName}: ${error.message}",
                error,
            )
        }
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(
                "Supabase GET ${path.substringBefore('?')} -> ${response.statusCode()}: ${response.body().take(300)}",
            )
        }
        return response.body()
    }

    private inline fun <reified T> String.decodeList(): List<T> = json.decodeFromString(this)

    companion object {
        fun fromEnvironment(json: Json): AdminRankingsGateway? {
            val url = System.getenv("SUPABASE_URL")?.trimEnd('/').orEmpty()
            val serviceRoleKey = System.getenv("SUPABASE_SERVICE_ROLE_KEY").orEmpty()
            return if (url.isBlank() || serviceRoleKey.isBlank()) {
                null
            } else {
                AdminRankingsGateway(url, serviceRoleKey, json)
            }
        }
    }
}

/**
 * Filtro `or=(...)` de búsqueda por nombre o username.
 *
 * El término se escapa para URL y se le quitan comas y paréntesis: son los
 * separadores de la propia sintaxis de PostgREST, y colarlos permitiría alterar
 * la forma del filtro desde la caja de búsqueda.
 */
internal fun perfilSearchFilter(termino: String): String {
    val saneado = termino.trim().replace(Regex("[(),*\"]"), " ").trim()
    val codificado = URLEncoder.encode("*$saneado*", StandardCharsets.UTF_8).replace("+", "%20")
    return "nombre_mostrar.ilike.$codificado,username.ilike.$codificado"
}

// ---------- Filas crudas de Supabase ----------

@Serializable
private data class PerfilNombreRow(
    @SerialName("usuario_id") val usuarioId: String = "",
    @SerialName("nombre_mostrar") val nombreMostrar: String? = null,
    val username: String? = null,
)

@Serializable
private data class EstadoCompetitivoRow(
    @SerialName("usuario_id") val usuarioId: String = "",
    @SerialName("liga_nivel_id") val ligaNivelId: String? = null,
    @SerialName("preguntas_validas_ranking") val preguntasValidasRanking: Int? = null,
    @SerialName("ventana_calculo") val ventanaCalculo: Int? = null,
    @SerialName("nota_media_actual") val notaMediaActual: Double? = null,
    @SerialName("nota_referencia_evolucion") val notaReferenciaEvolucion: Double? = null,
    @SerialName("evolucion_nota") val evolucionNota: Double? = null,
    @SerialName("preguntas_desde_ultima_evolucion") val preguntasDesdeUltimaEvolucion: Int? = null,
    @SerialName("ranking_liga_posicion") val rankingLigaPosicion: Int? = null,
    @SerialName("ranking_global_posicion") val rankingGlobalPosicion: Int? = null,
    @SerialName("percentil_global") val percentilGlobal: Double? = null,
    @SerialName("actualizado_en") val actualizadoEn: String? = null,
)

private fun EstadoCompetitivoRow.toDto(
    nombre: PerfilNombreRow?,
    liga: AdminLigaNivelDto?,
) = AdminRankingEntryDto(
    usuarioId = usuarioId,
    nombreMostrar = nombre?.nombreMostrar,
    username = nombre?.username,
    ligaNivelId = ligaNivelId,
    ligaCodigo = liga?.codigo,
    ligaNombre = liga?.nombre,
    ventanaCalculo = ventanaCalculo,
    preguntasValidasRanking = preguntasValidasRanking,
    notaMediaActual = notaMediaActual,
    notaReferenciaEvolucion = notaReferenciaEvolucion,
    evolucionNota = evolucionNota,
    preguntasDesdeUltimaEvolucion = preguntasDesdeUltimaEvolucion,
    rankingLigaPosicion = rankingLigaPosicion,
    rankingGlobalPosicion = rankingGlobalPosicion,
    percentilGlobal = percentilGlobal,
    actualizadoEn = actualizadoEn,
)
