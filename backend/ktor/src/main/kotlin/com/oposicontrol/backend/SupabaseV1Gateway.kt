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
            append(publishedByNow())
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
                "&activo=eq.true${publishedByNow()}&limit=1",
            body = null,
        )
            .decodeList<JsonObject>()
            .firstOrNull { row -> row["activo"]?.jsonPrimitive?.booleanOrNull ?: true }
            ?.toV1ExamenDto()
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
