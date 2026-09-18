package com.oposicontrol.backend

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * HASHIRA C — verificacion independiente de la FASE 2.
 *
 * `AdminDashboardOverviewConcurrencyTest` fija QUE corre a la vez. Este fichero
 * fija **CUANTO sale por el cable**, que es la otra mitad del contrato que la
 * paralelizacion pone en riesgo y que ningun caso existente afirma: el control
 * del caso de solape se conforma con `recorded.size >= 15`, un umbral que
 * tolera cuatro peticiones perdidas sin decir nada.
 *
 * El KDoc de `overview` declara dos cosas que NO se hacen, y las dos son
 * exactamente las optimizaciones que un refactor futuro intentara:
 *
 * 1. **No cachear**, ni entre franjas ni entre peticiones, porque `generatedAt`
 *    significa «cuando compuso Ktor este bloque».
 * 2. **No compartir** resultados entre las cinco ramas concurrentes.
 *
 * Las dos son invisibles en el DTO: un panel servido desde cache o con franjas
 * que comparten lecturas se ve EXACTAMENTE igual. Lo unico que las distingue es
 * lo que sale por el cable, y por eso se afirma aqui y no sobre el resultado.
 *
 * FASE 2 (PC-01/02/03/05/06/11): el numero sigue siendo 19 con OTRA composicion
 * —desaparecen el total de examenes con archivados y `rachas_usuario`; entran dos
 * RPC—, asi que contar ya no basta: [theFanOutIsExactlyTheseNineteenReads] fija el
 * conjunto de lecturas, metodo y query incluidos.
 */
class IndependentDashboardFanoutContractTest {

    /**
     * 1 (SYSTEM) + 4 (ATTENTION) + 5 (PULSE) + 4 (CONTENT) + 1 (ACTIVITY) +
     * 4 (ECONOMY). Es la misma cifra que el KDoc de `overview` usa para
     * justificar la paralelizacion; si deja de ser cierta, el razonamiento del
     * «peor caso de ~380 s de hilo» tampoco lo es.
     */
    private val expectedUpstreamCalls = 19

    /**
     * Las 19 lecturas de un refresco completo, con metodo, path y query. Los
     * instantes se normalizan a `<t>`: cambian en cada llamada y no son lo que
     * este caso fija. Las dos ventanas de vencimiento (7 y 30 dias) se normalizan a
     * la misma cadena y por eso aparecen dos veces.
     */
    private val expectedReads = listOf(
        // SYSTEM
        "GET /rest/v1/especialidades?select=id",
        // ATTENTION
        "GET /rest/v1/reportes_contenido?select=id&estado=eq.pendiente",
        "GET /rest/v1/feedback_pregunta?select=id&estado=eq.pendiente",
        "GET /rest/v1/examenes?select=id&activo=eq.true&publicado_en=is.null",
        "GET /rest/v1/suscripciones?select=id&estado=eq.activa&fin_en=gte.<t>&fin_en=lt.<t>",
        // PULSE
        "POST /rest/v1/rpc/admin_dashboard_active_users?",
        "GET /rest/v1/intentos_examen?select=id&iniciado_en=gte.<t>",
        "GET /rest/v1/examenes?select=id&publicado_en=not.is.null&activo=eq.true",
        "GET /rest/v1/respuestas_intento?select=id",
        "GET /rest/v1/respuestas_intento?select=id&es_correcta=eq.false",
        // CONTENT
        "GET /rest/v1/examenes?select=id&activo=eq.true",
        "GET /rest/v1/preguntas?select=id&activa=eq.true",
        "GET /rest/v1/recursos_pregunta?select=id&activo=eq.true",
        "GET /rest/v1/lotes_importacion_preguntas?select=id",
        // ACTIVITY
        "GET /rest/v1/auditoria_log?select=id,accion,tabla_afectada,registro_id,creado_en&order=creado_en.desc&limit=8",
        // ECONOMY
        "GET /rest/v1/suscripciones?select=id&estado=eq.activa&or=(fin_en.is.null,fin_en.gt.%22<t>%22)",
        "GET /rest/v1/suscripciones?select=id&estado=eq.activa&fin_en=gte.<t>&fin_en=lt.<t>",
        "GET /rest/v1/productos_comerciales?select=id&activo=eq.true",
        "POST /rest/v1/rpc/admin_dashboard_economy_totals?",
    )

    @Test
    fun aFullOverviewFansOutExactlyNineteenUpstreamCalls() {
        val recorded = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(recorded)

        runBlocking { gateway.overview() }

        assertEquals(
            expectedUpstreamCalls,
            recorded.size,
            "el abanico del dashboard cambio. Si BAJO, alguien dedujo o cacheo y con ello acoplo " +
                "franjas que tienen que degradar por separado; si SUBIO, hay lecturas nuevas sin " +
                "declarar. Salieron: ${recorded.describe()}",
        )
    }

    /**
     * El conjunto EXACTO de lecturas, no sólo cuántas.
     *
     * Contar ya no distingue nada: PC-01 quitó una lectura y PC-11 añadió otra, y
     * el total sigue en 19. Cambiar un filtro por otro —volver a `examenes` sin
     * `activo`, leer `rachas_usuario` en vez de la RPC— dejaria un test que solo
     * cuenta en verde. Este compara la lista ordenada de metodo + path + query.
     */
    @Test
    fun theFanOutIsExactlyTheseNineteenReads() {
        val recorded = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(recorded)

        runBlocking { gateway.overview() }

        assertEquals(
            expectedReads.sorted(),
            recorded.map { it.normalized() }.sorted(),
            "las lecturas del dashboard cambiaron. Salieron: ${recorded.describe()}",
        )
    }

    /**
     * Dos refrescos seguidos sobre la MISMA instancia de gateway tienen que
     * costar el doble.
     *
     * Un memo entre llamadas es el atajo mas tentador porque no cambia ni un
     * campo del DTO: haria que `generatedAt` dijera «ahora» sobre cifras de
     * hace un minuto, y la honestidad temporal del panel cuelga entera de ese
     * campo. El gateway es un singleton de proceso (`fromEnvironment`), asi que
     * el escenario no es hipotetico: son los refrescos de 60 s del panel.
     */
    @Test
    fun aSecondOverviewOnTheSameGatewayPaysForItselfAgain() {
        val recorded = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(recorded)

        runBlocking { gateway.overview() }
        runBlocking { gateway.overview() }

        assertEquals(
            expectedUpstreamCalls * 2,
            recorded.size,
            "el segundo refresco reutilizo lecturas del primero: hay cache entre peticiones y " +
                "`generatedAt` estaria mintiendo. ${recorded.describe()}",
        )
    }

    // -----------------------------------------------------------------

    /**
     * `METODO path?query` con los instantes normalizados. `rawQuery` y no la query
     * decodificada: las comillas codificadas de PC-03 (`%22`) son parte del contrato.
     */
    private fun HttpRequest.normalized(): String =
        "${method()} ${uri().rawPath}?${uri().rawQuery.orEmpty()}"
            .replace(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z"""), "<t>")

    private fun List<HttpRequest>.describe(): String =
        "(${size}) " + joinToString(", ") { "${it.uri().rawPath}?${it.uri().rawQuery.orEmpty()}" }

    private fun gatewayWith(recorded: MutableList<HttpRequest>): AdminDashboardOverviewGateway =
        AdminDashboardOverviewGateway(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient(recorded::add) { uri ->
                if (uri.contains("/rest/v1/auditoria_log")) {
                    V1StubResponse(200, "[]")
                } else {
                    V1StubResponse(206, "[]", contentRange = "*/0")
                }
            },
        )
}
