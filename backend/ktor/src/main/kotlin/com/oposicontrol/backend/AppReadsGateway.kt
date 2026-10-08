package com.oposicontrol.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

// =====================================================================
// Pasarela de las lecturas de consumidor de FA15 (D7-05, D5-01, D5-16): SOLO lecturas PostgREST con la clave de servicio
// sobre tablas MAIN existentes (`compras_usuario`, `suscripciones`, `categorias_oposired`, `estado_opositester_usuario`).
// Sin escritura, sin RPC. Ninguna regla de negocio (eso es de [AppReadsService]).
//
// - EL USUARIO ES SIEMPRE EL `sub` DEL JWT: toda consulta de datos propios lleva `usuario_id=eq.<sub>` (uuid ya validado).
//   El catalogo de categorias es el mismo para todos y no lleva usuario.
// - Columnas EXPLICITAS: nunca `payload_validacion`, `identificador_transaccion`, `identificador_externo` ni `proveedor`.
// - El unico literal del cliente que llega a una ruta es el cursor, ya validado por [KeysetPosition].
// =====================================================================

/** Puerto bloqueante hacia Supabase (PostgREST). */
internal fun interface AppReadsUpstream {
    fun get(path: String): String
}

@Serializable
internal data class PurchaseRow(
    val id: String,
    @SerialName("producto_codigo") val productoCodigo: String,
    @SerialName("tipo_producto") val tipoProducto: String,
    val estado: String,
    @SerialName("precio_centimos") val precioCentimos: Int? = null,
    val moneda: String? = null,
    @SerialName("creado_en") val creadoEn: String,
)

@Serializable
internal data class SubscriptionRow(
    val id: String,
    @SerialName("producto_codigo") val productoCodigo: String,
    val estado: String,
    @SerialName("inicio_en") val inicioEn: String,
    @SerialName("fin_en") val finEn: String? = null,
)

@Serializable
internal data class ForumCategoryRow(
    val id: String,
    val nombre: String,
    val slug: String,
    val descripcion: String? = null,
    val posicion: Int,
)

@Serializable
internal data class OposiTesterRow(
    @SerialName("reportes_enviados") val reportesEnviados: Int = 0,
    @SerialName("reportes_validados") val reportesValidados: Int = 0,
    @SerialName("tickets_ganados_reportes") val ticketsGanados: Int = 0,
)

internal class AppReadsGateway(
    private val upstream: AppReadsUpstream,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private inline fun <reified T> decodeList(body: String): List<T> =
        json.decodeFromString(ListSerializer(serializer<T>()), body)

    /** D7-05: compras del usuario, `creado_en DESC, id DESC`, tras [after]. Pide [fetchLimit] (= limit + 1). */
    fun purchases(userId: String, after: KeysetPosition?, fetchLimit: Int): List<PurchaseRow> {
        require(userId.isUuid()) { "userId" }
        val query = StringBuilder("/rest/v1/compras_usuario?select=$PURCHASE_COLUMNS&usuario_id=eq.$userId")
        if (after != null) query.append("&or=(").append(keysetOr("creado_en", after)).append(")")
        query.append("&order=creado_en.desc,id.desc&limit=").append(fetchLimit)
        return decodeList(upstream.get(query.toString()))
    }

    /** D7-05: suscripciones del usuario, `inicio_en DESC, id DESC`, tras [after]. Pide [fetchLimit] (= limit + 1). */
    fun subscriptions(userId: String, after: KeysetPosition?, fetchLimit: Int): List<SubscriptionRow> {
        require(userId.isUuid()) { "userId" }
        val query = StringBuilder("/rest/v1/suscripciones?select=$SUBSCRIPTION_COLUMNS&usuario_id=eq.$userId")
        if (after != null) query.append("&or=(").append(keysetOr("inicio_en", after)).append(")")
        query.append("&order=inicio_en.desc,id.desc&limit=").append(fetchLimit)
        return decodeList(upstream.get(query.toString()))
    }

    /** D5-01: categorias activas, `posicion, nombre, id`. Pide [fetchLimit] filas como mucho. */
    fun forumCategories(fetchLimit: Int): List<ForumCategoryRow> =
        decodeList(
            upstream.get(
                "/rest/v1/categorias_oposired?select=id,nombre,slug,descripcion,posicion&activa=eq.true" +
                    "&order=posicion.asc,nombre.asc,id.asc&limit=$fetchLimit",
            ),
        )

    /** D5-16: contadores del usuario; sin fila = `null` (aun no ha enviado reportes). `nivel_opositester` NO se pide (DEC-028). */
    fun opositester(userId: String): OposiTesterRow? {
        require(userId.isUuid()) { "userId" }
        return decodeList<OposiTesterRow>(
            upstream.get(
                "/rest/v1/estado_opositester_usuario?select=reportes_enviados,reportes_validados,tickets_ganados_reportes" +
                    "&usuario_id=eq.$userId&limit=1",
            ),
        ).firstOrNull()
    }

    /** Keyset descendente `(col, id) < (X, Y)`. [after] ya esta validado; [column] es un literal del codigo. */
    private fun keysetOr(column: String, after: KeysetPosition): String {
        val ts = encodeQuery(after.timestamp)
        return "$column.lt.$ts,and($column.eq.$ts,id.lt.${after.id})"
    }

    companion object {
        internal const val PURCHASE_COLUMNS = "id,producto_codigo,tipo_producto,estado,precio_centimos,moneda,creado_en"
        internal const val SUBSCRIPTION_COLUMNS = "id,producto_codigo,estado,inicio_en,fin_en"
    }
}
