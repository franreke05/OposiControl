package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Los parámetros llegan de la query string, así que llegan como los teclee quien
 * llame. Estos tests fijan que ninguno pueda convertir un listado en una descarga
 * de la tabla entera ni en una consulta sintácticamente inválida.
 */
class AdminPaginationTest {

    @Test
    fun missingParametersFallBackToTheFirstPage() {
        // Compatibilidad: quien llamaba sin paginar sigue recibiendo una página.
        val page = AdminPageRequest.of(null, null)

        assertEquals(AdminPageRequest.DEFAULT_LIMIT, page.limit)
        assertEquals(0, page.offset)
    }

    @Test
    fun explicitValuesAreRespected() {
        val page = AdminPageRequest.of(25, 50)

        assertEquals(25, page.limit)
        assertEquals(50, page.offset)
    }

    @Test
    fun anOversizedLimitIsCappedInsteadOfHonoured() {
        // Sin el tope, `?limit=1000000` arrastraría la tabla completa a memoria.
        val page = AdminPageRequest.of(1_000_000, 0)

        assertEquals(AdminPageRequest.MAX_LIMIT, page.limit)
    }

    @Test
    fun exactlyTheMaximumIsAllowed() {
        assertEquals(AdminPageRequest.MAX_LIMIT, AdminPageRequest.of(AdminPageRequest.MAX_LIMIT, 0).limit)
    }

    @Test
    fun zeroOrNegativeLimitFallsBackToTheDefault() {
        // `limit=0` devolvería una página vacía para siempre y parecería que no
        // hay usuarios; un negativo es directamente inválido para PostgREST.
        assertEquals(AdminPageRequest.DEFAULT_LIMIT, AdminPageRequest.of(0, 0).limit)
        assertEquals(AdminPageRequest.DEFAULT_LIMIT, AdminPageRequest.of(-10, 0).limit)
    }

    @Test
    fun aNegativeOffsetBecomesZero() {
        assertEquals(0, AdminPageRequest.of(20, -5).offset)
    }

    @Test
    fun theDefaultLimitStaysWithinTheHardCap() {
        // Si alguien subiese el default por encima del tope, cada petición sin
        // parámetros pediría más de lo que el tope pretende permitir.
        assertEquals(true, AdminPageRequest.DEFAULT_LIMIT <= AdminPageRequest.MAX_LIMIT)
    }

    @Test
    fun anUnknownTotalIsNullNotZero() {
        // Un 0 se pintaría como "no hay usuarios"; null dice "no lo sé".
        val page = AdminUsersPage(data = emptyList(), total = null)

        assertEquals(null, page.total)
    }
}
