package com.oposicontrol.backend

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets

class SupabaseV1Gateway private constructor(
    supabaseUrl: String,
    serviceRoleKey: String,
    private val json: Json,
    client: HttpClient = sharedUpstreamHttpClient,
) {
    /**
     * Migrado a [SupabaseRequestTransport] (FASE 1).
     *
     * Este gateway era el unico cuyo `request` no aceptaba `Prefer`, asi que no
     * podia hacer `count=exact` ni `return=representation`. Con el transporte
     * compartido esa limitacion desaparece sola, y ademas gana la traduccion de
     * errores de dominio que antes solo tenian dos de los trece gateways.
     */
    private val transport = SupabaseRequestTransport(
        baseUrl = supabaseUrl,
        auth = SupabaseRequestAuthPolicy(serviceRoleKey),
        client = client,
    )

    fun listEspecialidades(): List<V1EspecialidadDto> {
        return request(
            method = "GET",
            path = "/rest/v1/especialidades?select=*&activa=eq.true&order=nombre.asc,id.asc",
            body = null,
        )
            .decodeList<JsonObject>()
            .filter { row -> row["activa"]?.jsonPrimitive?.booleanOrNull ?: true }
            .mapNotNull { row -> row.toV1EspecialidadDto() }
    }

    fun listExamenes(filters: V1ExamenesFilter): List<V1ExamenDto> {
        val path = buildString {
            append("/rest/v1/examenes?select=*")
            append("&activo=eq.true")
            // Mismo filtro que la ruta autenticada: sin él, un examen sin publicar es
            // visible para cualquiera y sin sesión, justo donde un usuario logueado
            // no puede verlo.
            append("&publicado_en=not.is.null")
            filters.especialidadId?.let { append("&especialidad_id=eq.${it.toPostgrestValue()}") }
            filters.comunidadId?.let { append("&comunidad_id=eq.${it.toPostgrestValue()}") }
            filters.tipoExamen?.let { append("&tipo_examen=eq.${it.toPostgrestValue()}") }
            filters.tipoAcceso?.let { append("&tipo_acceso=eq.${it.toPostgrestValue()}") }
            append("&order=anio.desc.nullslast,titulo.asc")
        }
        return request(method = "GET", path = path, body = null)
            .decodeList<JsonObject>()
            .filter { row -> row["activo"]?.jsonPrimitive?.booleanOrNull ?: true }
            .mapNotNull { row -> row.toV1ExamenDto() }
    }

    fun getExamen(examenId: String): V1ExamenDto? {
        return request(
            method = "GET",
            path = "/rest/v1/examenes?select=*&id=eq.${examenId.toPostgrestValue()}" +
                "&activo=eq.true&publicado_en=not.is.null&limit=1",
            body = null,
        )
            .decodeList<JsonObject>()
            .firstOrNull { row -> row["activo"]?.jsonPrimitive?.booleanOrNull ?: true }
            ?.toV1ExamenDto()
    }

    /**
     * Preguntas de un examen rafaga, para consumo ANONIMO.
     *
     * ### EXAM-009
     *
     * Esta consulta no filtraba publicacion y su vecina [getExamen] si. El
     * resultado era que `GET /v1/examenes/{id}` devolvia 404 para un borrador y
     * `GET /v1/examenes/{id}/preguntas` devolvia 200 con sus enunciados y sus
     * opciones: **la API negaba que el examen existiera y a la vez entregaba su
     * contenido**. El filtro estaba escrito, con su comentario, quince lineas
     * mas arriba; solo no se copio aqui.
     *
     * El borrador se descarta **en la base**, no en memoria, y eso es la
     * garantia: filtrar despues de traer la fila deja el contenido viajando por
     * la red y depende de que nadie reordene el codigo.
     *
     * ### Por que 404 y no 403
     *
     * [V1PreguntasExamenResult.Forbidden] existe y la ruta responde 403, pero
     * seria la respuesta equivocada para un borrador: un 403 frente a un 404
     * **confirma a un anonimo que ese UUID nombra un examen real**, que es
     * justo el hecho que un borrador no debe revelar. Ademas [getExamen] ya
     * responde 404 para ese mismo examen; elegir 403 aqui mantendria la
     * contradiccion, solo que mudada del cuerpo al codigo de estado.
     *
     * El 403 se reserva para lo que ya cubre: un examen **publicado** que no es
     * rafaga o no es gratuito. Ahi la existencia ya es publica por
     * `GET /v1/examenes/{id}`, asi que no se filtra nada nuevo.
     *
     * El admin sigue viendo borradores por su propia ruta
     * (`GET /api/v1/admin/examenes/{id}/preguntas`), que es ADMIN y no filtra
     * publicacion. Las dos rutas no se mezclan a proposito: meter "si eres
     * admin" dentro de este gateway —que no recibe principal ni JWT en ninguna
     * firma— convertiria una ruta anonima en una ruta con dos comportamientos,
     * que es la clase de ambiguedad que produjo EXAM-009.
     */
    fun getPreguntasExamenRafaga(examenId: String): V1PreguntasExamenResult {
        val examen = request(
            method = "GET",
            path = "/rest/v1/examenes?select=*&id=eq.${examenId.toPostgrestValue()}" +
                "&activo=eq.true&publicado_en=not.is.null&limit=1",
            body = null,
        )
            .decodeList<JsonObject>()
            .firstOrNull()
            ?: return V1PreguntasExamenResult.NotFound
        // Redundante con el filtro de la query, y deliberadamente: si alguien
        // toca la cadena de arriba, la garantia sigue en pie en el proceso.
        // Redundancia barata en un camino de autorizacion.
        if (examen.primitiveBoolean("activo") != true) return V1PreguntasExamenResult.NotFound
        if (examen.primitiveText("tipo_examen") != "rafaga" || examen.primitiveText("tipo_acceso") != "gratuito") {
            return V1PreguntasExamenResult.Forbidden
        }

        val asociaciones = request(
            method = "GET",
            path = "/rest/v1/preguntas_examen?select=*&examen_id=eq.${examenId.toPostgrestValue()}",
            body = null,
        )
            .decodeList<JsonObject>()
            .mapIndexedNotNull { index, row ->
                val preguntaId = row.primitiveText("pregunta_id") ?: return@mapIndexedNotNull null
                V1PreguntaAsociadaRow(
                    preguntaId = preguntaId,
                    posicion = row.primitiveInt("posicion") ?: row.primitiveInt("orden") ?: index + 1,
                )
            }
            .sortedBy { it.posicion }

        if (asociaciones.isEmpty()) {
            return V1PreguntasExamenResult.Success(V1PreguntasExamenDto(examenId = examenId, preguntas = emptyList()))
        }

        val preguntaIds = asociaciones.map { it.preguntaId }.distinct()
        val preguntaIdsFilter = preguntaIds.joinToString(",") { it.toPostgrestValue() }
        val preguntasById = request(
            method = "GET",
            path = "/rest/v1/preguntas?select=*&id=in.($preguntaIdsFilter)&activa=eq.true",
            body = null,
        )
            .decodeList<JsonObject>()
            .filter { row -> row.primitiveBoolean("activa") ?: true }
            .filter { row -> row.primitiveText("tipo_pregunta").orEmpty().ifBlank { "rafaga" } == "rafaga" }
            .associateBy { row -> row.primitiveText("id").orEmpty() }

        val opcionesByPreguntaId = request(
            method = "GET",
            path = "/rest/v1/opciones_pregunta?select=*&pregunta_id=in.($preguntaIdsFilter)",
            body = null,
        )
            .decodeList<JsonObject>()
            .mapNotNull { row -> row.toV1OpcionPreguntaDto() }
            .groupBy { it.preguntaId }

        val preguntas = asociaciones.mapNotNull { asociacion ->
            val pregunta = preguntasById[asociacion.preguntaId] ?: return@mapNotNull null
            pregunta.toV1PreguntaExamenDto(
                posicion = asociacion.posicion,
                opciones = opcionesByPreguntaId[asociacion.preguntaId]
                    .orEmpty()
                    .sortedWith(compareBy<V1OpcionPreguntaInternalDto> { it.posicion }.thenBy { it.letra })
                    .map { it.toPublicDto() },
            )
        }

        return V1PreguntasExamenResult.Success(
            V1PreguntasExamenDto(
                examenId = examenId,
                preguntas = preguntas,
            ),
        )
    }

    /**
     * Se conserva como envoltorio de una linea para no tocar los cinco
     * llamadores: el transporte cambia, el contrato interno no.
     */
    private fun request(method: String, path: String, body: String?): String =
        transport.send(method = method, path = path, body = body)

    private inline fun <reified T> String.decodeList(): List<T> = json.decodeFromString(this)

    companion object {
        /**
         * Misma firma y mismo contrato de `null` que antes, para que el
         * cableado de `Application.kt` no cambie. Lo unico que cambia es QUE
         * variable de entorno se lee, y eso lo decide un solo sitio:
         * [SupabaseRequestAuthPolicy.secretFromEnvironment] prefiere
         * `SUPABASE_SECRET_KEY` y cae a `SUPABASE_SERVICE_ROLE_KEY`.
         */
        fun fromEnvironment(json: Json): SupabaseV1Gateway? {
            val url = System.getenv("SUPABASE_URL")?.trimEnd('/').orEmpty()
            val secretKey = SupabaseRequestAuthPolicy.secretFromEnvironment()
            return if (url.isBlank() || secretKey.isBlank()) null else SupabaseV1Gateway(url, secretKey, json)
        }

        /**
         * Solo para tests.
         *
         * Este gateway no tenia costura y **por eso EXAM-009 llevaba abierto**:
         * no habia forma de preguntarle "¿y si el examen es un borrador?".
         * Un defecto de autorizacion que ninguna prueba podia alcanzar no es
         * mala suerte, es una consecuencia del constructor privado sin
         * alternativa.
         */
        internal fun forTesting(
            supabaseUrl: String,
            serviceRoleKey: String,
            json: Json,
            client: HttpClient,
        ): SupabaseV1Gateway = SupabaseV1Gateway(supabaseUrl, serviceRoleKey, json, client)
    }
}

data class V1ExamenesFilter(
    val especialidadId: String? = null,
    val comunidadId: String? = null,
    val tipoExamen: String? = null,
    val tipoAcceso: String? = null,
)

@Serializable
data class V1EspecialidadDto(
    val id: String,
    val nombre: String,
    val slug: String,
)

@Serializable
data class V1ExamenDto(
    val id: String,
    val codigoExamen: String,
    val especialidadId: String,
    val comunidadId: String? = null,
    val titulo: String,
    val descripcion: String,
    val tipoExamen: String,
    val tipoAcceso: String,
    val totalPreguntas: Int,
    val duracionMinutos: Int? = null,
    val anio: Int? = null,
    val esOficial: Boolean,
    val publicadoEn: String? = null,
)

sealed interface V1PreguntasExamenResult {
    data class Success(val data: V1PreguntasExamenDto) : V1PreguntasExamenResult
    data object NotFound : V1PreguntasExamenResult
    data object Forbidden : V1PreguntasExamenResult
}

@Serializable
data class V1PreguntasExamenDto(
    val examenId: String,
    val preguntas: List<V1PreguntaExamenDto>,
)

@Serializable
data class V1PreguntaExamenDto(
    val id: String,
    val codigoPregunta: String,
    val titulo: String,
    val tipoPregunta: String,
    val posicion: Int,
    val opciones: List<V1OpcionPreguntaDto>,
)

@Serializable
data class V1OpcionPreguntaDto(
    val id: String,
    val letra: String,
    val texto: String,
)

private data class V1PreguntaAsociadaRow(
    val preguntaId: String,
    val posicion: Int,
)

private data class V1OpcionPreguntaInternalDto(
    val preguntaId: String,
    val id: String,
    val letra: String,
    val texto: String,
    val posicion: Int,
) {
    fun toPublicDto(): V1OpcionPreguntaDto {
        return V1OpcionPreguntaDto(
            id = id,
            letra = letra,
            texto = texto,
        )
    }
}

private fun JsonObject.toV1EspecialidadDto(): V1EspecialidadDto? {
    val id = primitiveText("id") ?: return null
    val nombre = primitiveText("nombre")?.takeIf { it.isNotBlank() } ?: return null
    val slug = primitiveText("slug").orEmpty()
    return V1EspecialidadDto(
        id = id,
        nombre = nombre,
        slug = slug,
    )
}

private fun JsonObject.toV1ExamenDto(): V1ExamenDto? {
    val id = primitiveText("id") ?: return null
    val titulo = primitiveText("titulo")?.takeIf { it.isNotBlank() } ?: return null
    return V1ExamenDto(
        id = id,
        codigoExamen = primitiveText("codigo_examen").orEmpty(),
        especialidadId = primitiveText("especialidad_id").orEmpty(),
        comunidadId = primitiveText("comunidad_id"),
        titulo = titulo,
        descripcion = primitiveText("descripcion").orEmpty(),
        tipoExamen = primitiveText("tipo_examen").orEmpty(),
        tipoAcceso = primitiveText("tipo_acceso").orEmpty(),
        totalPreguntas = primitiveInt("total_preguntas") ?: 0,
        duracionMinutos = primitiveInt("duracion_minutos"),
        anio = primitiveInt("anio"),
        esOficial = primitiveBoolean("es_oficial") ?: false,
        publicadoEn = primitiveText("publicado_en"),
    )
}

private fun JsonObject.toV1PreguntaExamenDto(
    posicion: Int,
    opciones: List<V1OpcionPreguntaDto>,
): V1PreguntaExamenDto? {
    val id = primitiveText("id") ?: return null
    return V1PreguntaExamenDto(
        id = id,
        codigoPregunta = primitiveText("codigo_pregunta").orEmpty(),
        titulo = primitiveText("titulo").orEmpty(),
        tipoPregunta = primitiveText("tipo_pregunta").orEmpty().ifBlank { "rafaga" },
        posicion = posicion,
        opciones = opciones,
    )
}

private fun JsonObject.toV1OpcionPreguntaDto(): V1OpcionPreguntaInternalDto? {
    val preguntaId = primitiveText("pregunta_id") ?: return null
    val id = primitiveText("id") ?: return null
    return V1OpcionPreguntaInternalDto(
        preguntaId = preguntaId,
        id = id,
        letra = primitiveText("letra").orEmpty(),
        texto = primitiveText("texto_opcion") ?: primitiveText("texto") ?: primitiveText("contenido").orEmpty(),
        posicion = primitiveInt("posicion") ?: primitiveInt("orden") ?: 0,
    )
}

private fun JsonObject.primitiveText(key: String): String? {
    return this[key]?.jsonPrimitive?.contentOrNull
}

private fun JsonObject.primitiveInt(key: String): Int? {
    return this[key]?.jsonPrimitive?.intOrNull
}

private fun JsonObject.primitiveBoolean(key: String): Boolean? {
    return this[key]?.jsonPrimitive?.booleanOrNull
}

private fun String.toPostgrestValue(): String {
    return URLEncoder.encode(this, StandardCharsets.UTF_8.toString())
}
