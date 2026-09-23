package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reglas que gobiernan la superficie de rankings, probadas sin red.
 *
 * Son cuatro invariantes de producto: la clasificación no se edita, un hueco se
 * dice en vez de rellenarse con ceros, una página no puede convertirse en una
 * descarga de la tabla, y la ventana de cálculo de cada liga es la que producto
 * definió.
 */
class AdminRankingsTest {

    // ---------- Ventana de cálculo por liga ----------

    @Test
    fun theEntryLeagueAveragesFiftyQuestionsAndEveryOtherLeagueOneHundred() {
        // Las cinco ligas sembradas en `ligas_nivel`.
        assertEquals(50, ventanaCalculoEsperada("liga_50"))
        assertEquals(100, ventanaCalculoEsperada("liga_200"))
        assertEquals(100, ventanaCalculoEsperada("liga_500"))
        assertEquals(100, ventanaCalculoEsperada("liga_800"))
        assertEquals(100, ventanaCalculoEsperada("liga_1000"))
    }

    @Test
    fun anUnknownLeagueHasNoRuleInsteadOfADefault() {
        // Devolver 100 "por defecto" inventaría una regla para una liga que nadie
        // ha definido, y la divergencia dejaría de detectarse justo donde importa.
        assertNull(ventanaCalculoEsperada("liga_2000"))
        assertNull(ventanaCalculoEsperada(""))
    }

    @Test
    fun theLeagueCodeIsMatchedIgnoringCaseAndSurroundingSpace() {
        assertEquals(50, ventanaCalculoEsperada("  LIGA_50 "))
    }

    @Test
    fun aStoredWindowThatContradictsTheProductRuleIsFlagged() {
        // Si alguien edita la tabla y deja la liga 200 promediando 50 preguntas,
        // sus notas medias dejan de ser comparables con las del resto de ligas.
        val manipulada = liga(codigo = "liga_200", ventanaCalculo = 50)

        assertTrue(manipulada.ventanaDivergeDeLaRegla)
        assertEquals(100, manipulada.ventanaEsperadaPorRegla)
    }

    @Test
    fun theSeededLeaguesDoNotDivergeFromTheProductRule() {
        // Estas cinco filas son las que la base devuelve hoy (verificado en vivo).
        val sembradas = listOf(
            liga("liga_50", preguntasMinimas = 50, ventanaCalculo = 50, posicion = 1),
            liga("liga_200", preguntasMinimas = 200, ventanaCalculo = 100, posicion = 2),
            liga("liga_500", preguntasMinimas = 500, ventanaCalculo = 100, posicion = 3),
            liga("liga_800", preguntasMinimas = 800, ventanaCalculo = 100, posicion = 4),
            liga("liga_1000", preguntasMinimas = 1000, ventanaCalculo = 100, posicion = 5),
        )

        assertTrue(sembradas.none { it.ventanaDivergeDeLaRegla })
    }

    @Test
    fun anUnknownLeagueIsNeverReportedAsDivergent() {
        // Sin regla escrita no hay nada con lo que contradecirse: marcarla sería
        // acusar a la base de un error que sólo existe en esta tabla de reglas.
        assertFalse(liga(codigo = "liga_2000", ventanaCalculo = 250).ventanaDivergeDeLaRegla)
    }

    // ---------- Paginación acotada ----------

    @Test
    fun anAbsurdLimitCannotPullTheWholeTableThroughTheBackend() {
        // `?limit=999999` es la forma más directa de convertir un listado en una
        // descarga completa; el tope duro lo convierte en una página normal.
        val page = AdminPageRequest.of(999_999, 0)

        assertEquals(AdminPageRequest.MAX_LIMIT, page.limit)
        assertTrue(AdminPageRequest.MAX_LIMIT <= 200)
    }

    @Test
    fun thePageEchoesTheClampedValuesNotTheRequestedOnes() {
        // La UI pagina con lo que el backend le devuelve. Si le devolviese el
        // 999999 pedido, calcularía el número de páginas sobre una mentira.
        val saneada = AdminPageRequest.of(999_999, -40)
        val respuesta = AdminRankingPageDto(limit = saneada.limit, offset = saneada.offset, total = 0)

        assertEquals(AdminPageRequest.MAX_LIMIT, respuesta.limit)
        assertEquals(0, respuesta.offset)
    }

    @Test
    fun anUnparseableLimitFallsBackToTheDefaultPage() {
        // `?limit=abc` llega como null desde `toIntOrNull()`.
        assertEquals(AdminPageRequest.DEFAULT_LIMIT, AdminPageRequest.of(null, null).limit)
        assertEquals(0, AdminPageRequest.of(null, null).offset)
    }

    // ---------- Vacío real vs no disponible ----------

    @Test
    fun anEmptyTableWithAConfirmedCountReadsAsGenuinelyEmpty() {
        // Estado real hoy: `estado_competitivo_usuario` está aprovisionada y
        // vacía, y Supabase responde `Content-Range: */0`.
        val total = parseContentRangeTotal("*/0")
        val page = AdminRankingPageDto(data = emptyList(), total = total)

        assertEquals(0, total)
        assertEquals(AdminRankingPageState.VACIO_REAL, page.state())
    }

    @Test
    fun anEmptyPageWithoutAUsableCountIsNotCalledEmpty() {
        // Sin conteo fiable no se puede afirmar que no compita nadie: decirlo
        // sería inventar el dato que precisamente no se ha podido leer.
        val page = AdminRankingPageDto(
            data = emptyList(),
            total = parseContentRangeTotal(null),
            seccionesNoDisponibles = listOf(AdminRankingsSection.TOTAL),
        )

        assertNull(page.total)
        assertEquals(AdminRankingPageState.NO_DISPONIBLE, page.state())
    }

    @Test
    fun aStaleZeroIsStillTreatedAsUnavailableWhenTheSectionFailed() {
        // Defensa en profundidad: si alguien rellenase `total = 0` mientras marca
        // la sección como ilegible, gana la marca, no el número.
        val page = AdminRankingPageDto(
            data = emptyList(),
            total = 0,
            seccionesNoDisponibles = listOf(AdminRankingsSection.TOTAL),
        )

        assertEquals(AdminRankingPageState.NO_DISPONIBLE, page.state())
    }

    @Test
    fun aPageWithRowsIsContentEvenIfTheCountCouldNotBeRead() {
        // Las filas devueltas son ciertas aunque el total no se sepa; tirar la
        // página entera por un conteo fallido escondería datos reales.
        val page = AdminRankingPageDto(
            data = listOf(AdminRankingEntryDto(usuarioId = "b69164ad-1851-4dea-ab2a-572be7cef33f")),
            total = null,
            seccionesNoDisponibles = listOf(AdminRankingsSection.TOTAL),
        )

        assertEquals(AdminRankingPageState.CON_DATOS, page.state())
    }

    @Test
    fun aMissingNameNeverBecomesAMissingRow() {
        // Un fallo al decorar nombres marca la sección, pero la clasificación
        // sigue siendo correcta y se sirve.
        val page = AdminRankingPageDto(
            data = listOf(AdminRankingEntryDto(usuarioId = "b69164ad-1851-4dea-ab2a-572be7cef33f")),
            total = 1,
            seccionesNoDisponibles = listOf(AdminRankingsSection.NOMBRES),
        )

        assertEquals(AdminRankingPageState.CON_DATOS, page.state())
        assertEquals(1, page.data.size)
        assertNull(page.data.single().nombreMostrar)
    }

    @Test
    fun aUserWithoutACompetitiveRowIsNotAnError() {
        // "Todavía no compite" es un estado normal: la fila se crea al alcanzar el
        // mínimo de preguntas de la primera liga.
        val standing = AdminUserStandingDto(usuarioId = "b69164ad-1851-4dea-ab2a-572be7cef33f")

        assertNull(standing.competitivo)
        assertTrue(standing.seccionesNoDisponibles.isEmpty())
    }

    @Test
    fun aUserWhoseCompetitiveRowCouldNotBeReadIsMarked() {
        val standing = AdminUserStandingDto(
            usuarioId = "b69164ad-1851-4dea-ab2a-572be7cef33f",
            seccionesNoDisponibles = listOf(AdminRankingsSection.COMPETITIVO),
        )

        assertNull(standing.competitivo)
        assertTrue(AdminRankingsSection.COMPETITIVO in standing.seccionesNoDisponibles)
    }

    // ---------- Decoración acotada a la página ----------

    @Test
    fun namesAreResolvedOnlyForTheIdsOfTheCurrentPage() {
        val filtro = usuarioIdInFilter(
            listOf("b69164ad-1851-4dea-ab2a-572be7cef33f", "52405025-45e3-41ab-9d45-d80ec42ed98e"),
        )

        assertEquals(
            "usuario_id=in.(b69164ad-1851-4dea-ab2a-572be7cef33f,52405025-45e3-41ab-9d45-d80ec42ed98e)",
            filtro,
        )
    }

    @Test
    fun anEmptyPageIssuesNoEnrichmentQueryAtAll() {
        // `in.()` es sintaxis inválida, y la alternativa de traerse
        // `perfiles_usuario` entero para cruzarlo en Kotlin es el bug que ya se
        // corrigió una vez aquí. Null obliga a no preguntar.
        assertNull(usuarioIdInFilter(emptyList()))
        assertNull(usuarioIdInFilter(listOf("", "   ")))
    }

    @Test
    fun repeatedIdsAreRequestedOnce() {
        assertEquals(
            "usuario_id=in.(b69164ad-1851-4dea-ab2a-572be7cef33f)",
            usuarioIdInFilter(
                listOf(
                    "b69164ad-1851-4dea-ab2a-572be7cef33f",
                    "b69164ad-1851-4dea-ab2a-572be7cef33f",
                ),
            ),
        )
    }

    // ---------- Búsqueda ----------

    @Test
    fun aSearchTermCannotReshapeTheFilterItTravelsIn() {
        // La coma y el paréntesis son los separadores de `or=(...)`; si pasaran
        // tal cual, la caja de búsqueda podría añadir condiciones al filtro.
        val filtro = perfilSearchFilter("premium),usuario_id.eq.(x")

        assertFalse(filtro.contains(","+"usuario_id"))
        assertEquals(1, filtro.count { it == ',' }) // sólo la coma que separa las dos condiciones
        assertFalse(filtro.contains("("))
        assertFalse(filtro.contains(")"))
    }

    @Test
    fun aSearchTermMatchesAnywhereInTheNameOrUsername() {
        // Comodines a ambos lados: buscar "premium" debe encontrar "Usuario
        // Premium Test", no sólo lo que empiece por "premium".
        val filtro = perfilSearchFilter("premium")

        assertEquals("nombre_mostrar.ilike.*premium*,username.ilike.*premium*", filtro)
    }

    @Test
    fun aSearchTermWithSpacesIsUrlEncodedNotBroken() {
        // Un espacio crudo rompería la query string; `+` lo interpretaría PostgREST
        // como un carácter literal distinto del espacio.
        val filtro = perfilSearchFilter("usuario premium")

        assertTrue(filtro.contains("%20"))
        assertFalse(filtro.contains("+"))
    }

    // ---------- Sólo lectura ----------

    @Test
    fun theRankingsGatewayExposesNoWriteOperation() {
        // Regla de producto: la clasificación la calcula el producto y el panel
        // sólo la consulta. Este test falla en el momento en que alguien añada un
        // método de escritura al gateway, que es la única puerta por la que una
        // posición podría retocarse a mano desde el panel.
        val verbosDeEscritura = listOf(
            "create", "update", "delete", "save", "upsert", "insert",
            "patch", "post", "put", "set", "adjust", "ajustar", "asignar",
            "recalcular", "reset", "remove",
        )
        val infractores = AdminRankingsGateway::class.java.declaredMethods
            .map { it.name }
            .filter { name -> verbosDeEscritura.any { name.startsWith(it, ignoreCase = true) } }

        assertEquals(
            emptyList(),
            infractores,
            "Rankings es de solo lectura: el gateway no puede exponer operaciones de escritura.",
        )
    }

    private fun liga(
        codigo: String,
        preguntasMinimas: Int = 50,
        ventanaCalculo: Int = 50,
        posicion: Int = 1,
    ) = AdminLigaNivelDto(
        id = "52405025-45e3-41ab-9d45-d80ec42ed98e",
        codigo = codigo,
        nombre = codigo.replace("liga_", "Liga "),
        preguntasMinimas = preguntasMinimas,
        ventanaCalculo = ventanaCalculo,
        activa = true,
        posicion = posicion,
    )
}
