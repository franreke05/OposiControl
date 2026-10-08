package com.oposicontrol.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.time.Instant

// =====================================================================
// Pasarela de cartera (D6-01, D6-02): SOLO lecturas PostgREST con la clave de servicio sobre tablas MAIN existentes
// (`carteras_usuario`, `transacciones_cartera`, `activaciones_servicio_usuario`, `servicios`). Sin escritura, sin RPC, sin
// mutar saldos. Ninguna regla de negocio (eso es de [WalletService]).
//
// - EL USUARIO ES SIEMPRE EL `sub` DEL JWT: cada consulta lleva `usuario_id=eq.<sub>` (un uuid ya validado); no existe
//   ningun parametro de usuario en la API y el cursor no transporta el propietario.
// - Todo literal que llega del cliente (cursor, `tipo`) se valida ANTES de componer la ruta: el cursor por [KeysetPosition]
//   y el tipo por pertenecer al CHECK cerrado de [WalletTipo].
// =====================================================================

/** Puerto bloqueante hacia Supabase (PostgREST). */
internal fun interface WalletUpstream {
    fun get(path: String): String
}

// ---------- filas (snake_case, tal cual vive en la base) ----------

@Serializable
internal data class WalletBalanceRow(@SerialName("saldo_tickets") val saldoTickets: Int = 0)

/** Una fila del libro. `saldo_anterior` es NULLABLE en MAIN (filas antiguas) aunque el contrato lo exige no nulo. */
@Serializable
internal data class LedgerRow(
    val id: String,
    val tipo: String,
    val cantidad: Int,
    @SerialName("saldo_anterior") val saldoAnterior: Int? = null,
    @SerialName("saldo_resultante") val saldoResultante: Int,
    @SerialName("referencia_tipo") val referenciaTipo: String? = null,
    @SerialName("referencia_id") val referenciaId: String? = null,
    val descripcion: String? = null,
    @SerialName("creado_en") val creadoEn: String,
)

/** Solo lo necesario para agregar ganancias: nunca descripcion ni metadatos. */
@Serializable
internal data class EarningRow(
    val id: String,
    val tipo: String,
    val cantidad: Int,
    @SerialName("creado_en") val creadoEn: String,
)

@Serializable
internal data class ServiceRef(val codigo: String? = null)

@Serializable
internal data class ActivationRow(
    val id: String,
    val estado: String,
    @SerialName("activa_hasta") val activaHasta: String? = null,
    @SerialName("usos_disponibles") val usosDisponibles: Int = 0,
    @SerialName("usos_consumidos") val usosConsumidos: Int = 0,
    val servicios: ServiceRef? = null,
)

internal class WalletGateway(
    private val upstream: WalletUpstream,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private inline fun <reified T> decodeList(body: String): List<T> =
        json.decodeFromString(ListSerializer(serializer<T>()), body)

    /** Saldo del usuario; sin fila de cartera = `null` (el llamante lo trata como 0: aun no ha tenido movimientos). */
    fun balance(userId: String): WalletBalanceRow? {
        require(userId.isUuid()) { "userId" }
        return decodeList<WalletBalanceRow>(upstream.get("/rest/v1/carteras_usuario?select=saldo_tickets&usuario_id=eq.$userId&limit=1")).firstOrNull()
    }

    /**
     * Una pagina del libro del usuario, `creado_en DESC, id DESC`, con filtros opcionales. Pide [fetchLimit] (= limit + 1).
     * [tipos] (opcional) restringe a esos tipos del CHECK; cada uno ya es un literal cerrado de [WalletTipo].
     */
    fun ledgerPage(userId: String, tipo: WalletTipo?, origenTipos: Set<WalletTipo>?, after: KeysetPosition?, fetchLimit: Int): List<LedgerRow> {
        require(userId.isUuid()) { "userId" }
        val query = StringBuilder("/rest/v1/transacciones_cartera?select=$LEDGER_COLUMNS&usuario_id=eq.$userId")
        tipo?.let { query.append("&tipo=eq.").append(it.wire) }
        origenTipos?.let { set -> query.append("&tipo=in.(").append(set.joinToString(",") { it.wire }).append(")") }
        if (after != null) query.append("&or=(").append(keysetOr(after)).append(")")
        query.append("&order=creado_en.desc,id.desc&limit=").append(fetchLimit)
        return decodeList(upstream.get(query.toString()))
    }

    /** Los [limit] movimientos mas recientes (D6-01 `recientes`). */
    fun recent(userId: String, limit: Int): List<LedgerRow> = ledgerPage(userId, null, null, null, limit)

    /**
     * Una pagina de abonos (cantidad > 0) desde [since], `creado_en DESC, id DESC`, tras [after]. Pide [fetchLimit] (= tamano + 1);
     * el llamante pagina por keyset (nunca confia en el tope de filas de PostgREST).
     */
    fun creditsPage(userId: String, since: Instant, after: KeysetPosition?, fetchLimit: Int): List<EarningRow> {
        require(userId.isUuid()) { "userId" }
        val query = StringBuilder("/rest/v1/transacciones_cartera?select=id,tipo,cantidad,creado_en&usuario_id=eq.$userId")
        query.append("&cantidad=gt.0&creado_en=gte.").append(encodeQuery(since.toString()))
        if (after != null) query.append("&or=(").append(keysetOr(after)).append(")")
        query.append("&order=creado_en.desc,id.desc&limit=").append(fetchLimit)
        return decodeList(upstream.get(query.toString()))
    }

    /**
     * Consumibles: activaciones `activa` mas las `usada` (por `usado_en`) y `caducada` (por `activa_hasta`) de los ultimos
     * dias desde [recentSince]. Las `cancelada` NUNCA salen. Pide `max + 1` para detectar el desbordamiento.
     */
    fun activations(userId: String, recentSince: Instant, max: Int): List<ActivationRow> {
        require(userId.isUuid()) { "userId" }
        val since = encodeQuery(recentSince.toString())
        val query = "/rest/v1/activaciones_servicio_usuario?select=id,estado,activa_hasta,usos_disponibles,usos_consumidos,servicios(codigo)" +
            "&usuario_id=eq.$userId" +
            "&or=(estado.eq.activa,and(estado.eq.usada,usado_en.gte.$since),and(estado.eq.caducada,activa_hasta.gte.$since))" +
            "&order=activa_desde.desc,id.desc&limit=${max + 1}"
        return decodeList(upstream.get(query))
    }

    /** Keyset descendente: `(creado_en, id) < (X, Y)` como `or(creado_en.lt.X, and(creado_en.eq.X, id.lt.Y))`. [after] ya esta validado. */
    private fun keysetOr(after: KeysetPosition): String {
        val ts = encodeQuery(after.timestamp)
        return "creado_en.lt.$ts,and(creado_en.eq.$ts,id.lt.${after.id})"
    }

    companion object {
        /** Solo columnas del libro que salen por el cable: nunca `metadatos`, `idempotency_key` ni `compra_id`. */
        internal const val LEDGER_COLUMNS = "id,tipo,cantidad,saldo_anterior,saldo_resultante,referencia_tipo,referencia_id,descripcion,creado_en"
    }
}
