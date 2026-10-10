package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.UserCompetitiveState
import com.oposicontrol.admin.domain.model.UserDetail
import com.oposicontrol.admin.domain.model.UserDetailSection
import com.oposicontrol.admin.domain.model.UserEconomy
import com.oposicontrol.admin.domain.model.UserProfile
import com.oposicontrol.admin.domain.model.UserStreak
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * La capa de presentación de la ficha de Usuarios que no necesita Compose.
 *
 * Lo que más importa aquí es la banda de métricas: una sección sin fila y una que no
 * se pudo leer son cosas distintas, y ninguna se sustituye por un cero.
 */
class UserVisualModelsTest {

    private fun detail(
        competitive: UserCompetitiveState? = null,
        streak: UserStreak? = null,
        economy: UserEconomy? = null,
        unavailable: Set<String> = emptySet(),
    ) = UserDetail(
        profile = UserProfile(userId = "u-1", displayName = "Ana García", username = "ana.garcia"),
        competitive = competitive,
        streak = streak,
        economy = economy,
        unavailableSections = unavailable,
    )

    private fun List<UserKpi>.byId(id: String) = first { it.id == id }

    @Test
    fun `las iniciales salen de dos palabras, de una, o de la cuenta cuando no hay nombre`() {
        assertEquals("AG", userInitials("Ana García", "ana.garcia"))
        assertEquals("MP", userInitials("Marta Ruiz Peña", "mruiz"))
        assertEquals("JA", userInitials("Javier", "jortega"))
        assertEquals("I4", userInitials("", "invitado_4f"), "sin nombre, las de la cuenta")
        assertEquals("?", userInitials("  ", ""))
    }

    @Test
    fun `el color del avatar es estable y no depende de nada mas que del id`() {
        assertEquals(userAvatarRole("u-1"), userAvatarRole("u-1"))
        val distintos = (1..40).map { userAvatarRole("usuario-$it") }.toSet()
        assertTrue(distintos.size > 1, "una lista de usuarios no puede salir toda del mismo color")
    }

    @Test
    fun `los miles se separan con punto y los negativos conservan el signo`() {
        assertEquals("0", formatThousands(0))
        assertEquals("999", formatThousands(999))
        assertEquals("2.450", formatThousands(2450))
        assertEquals("1.234.567", formatThousands(1_234_567))
        assertEquals("-1.200", formatThousands(-1200))
    }

    @Test
    fun `con la ficha completa cada metrica lleva su dato`() {
        val kpis = userKpis(
            UiAsync.Success(
                detail(
                    competitive = UserCompetitiveState(leagueName = "Liga Plata", globalRank = 348, leagueRank = 12),
                    streak = UserStreak(currentDays = 9, bestDays = 23),
                    economy = UserEconomy(ticketBalance = 2450, subscriptionStatus = "activa", productCode = "premium_anual"),
                ),
            ),
        )

        assertEquals(listOf("liga", "ranking", "racha", "oposi-tickets", "suscripcion"), kpis.map { it.id })
        assertTrue(kpis.all { it.state == UserKpiState.DATA })
        assertEquals("Liga Plata", kpis.byId("liga").value)
        assertEquals("#348", kpis.byId("ranking").value)
        assertEquals("En liga #12", kpis.byId("ranking").caption)
        assertEquals("9 días", kpis.byId("racha").value)
        assertEquals("Mejor: 23 días", kpis.byId("racha").caption)
        assertEquals("2.450 OT", kpis.byId("oposi-tickets").value)
        assertEquals("premium_anual", kpis.byId("suscripcion").value)
        // El estado se dice con mayúscula inicial, como en la pestaña Economía: es el mismo dato.
        assertEquals("Activa", kpis.byId("suscripcion").caption)
    }

    @Test
    fun `una seccion sin fila es NO_DATA y una que no se pudo leer es NO_DISPONIBLE, nunca un cero`() {
        val kpis = userKpis(
            UiAsync.Success(
                detail(
                    competitive = null,
                    streak = UserStreak(currentDays = 0, bestDays = 0),
                    economy = null,
                    unavailable = setOf(UserDetailSection.STREAK),
                ),
            ),
        )

        // Sin fila de competición: no hay dato.
        assertEquals(UserKpiState.NO_DATA, kpis.byId("liga").state)
        assertEquals(UserKpiState.NO_DATA, kpis.byId("ranking").state)
        // La racha existe como fila pero su sección se marcó como no legible: manda el fallo.
        assertEquals(UserKpiState.UNAVAILABLE, kpis.byId("racha").state)
        // Sin cartera: ni saldo ni plan.
        assertEquals(UserKpiState.NO_DATA, kpis.byId("oposi-tickets").state)
        assertEquals(UserKpiState.NO_DATA, kpis.byId("suscripcion").state)
        assertTrue(kpis.filter { it.state != UserKpiState.DATA }.all { it.value.isEmpty() }, "ningún valor inventado")
    }

    @Test
    fun `una fila de competicion sin liga ni posicion tampoco inventa nada`() {
        val kpis = userKpis(UiAsync.Success(detail(competitive = UserCompetitiveState())))

        assertEquals(UserKpiState.NO_DATA, kpis.byId("liga").state)
        assertEquals(UserKpiState.NO_DATA, kpis.byId("ranking").state)
    }

    @Test
    fun `sin plan la suscripcion se llama gratuito, como en la pestana de economia`() {
        val kpis = userKpis(UiAsync.Success(detail(economy = UserEconomy(ticketBalance = 10))))

        assertEquals("gratuito", kpis.byId("suscripcion").value)
        assertEquals("10 OT", kpis.byId("oposi-tickets").value)
    }

    @Test
    fun `mientras la ficha carga, falla o no existe las cinco metricas lo dicen por igual`() {
        val cargando = userKpis(UiAsync.Loading)
        val caida = userKpis(UiAsync.Error(AppError.Network("caido")))
        val vacia = userKpis(UiAsync.Empty)

        assertEquals(5, cargando.size)
        assertTrue(cargando.all { it.state == UserKpiState.LOADING })
        assertTrue(caida.all { it.state == UserKpiState.UNAVAILABLE })
        assertTrue(vacia.all { it.state == UserKpiState.NO_DATA })
        assertEquals(UserKpiState.LOADING, userKpis(UiAsync.Idle).first().state)
    }

    @Test
    fun `un lector de pantalla oye el motivo, no un valor vacio`() {
        val kpi = { state: UserKpiState -> UserKpi("x", "Racha", com.oposicontrol.admin.core.ui.components.AppIcon.PERFORMANCE, com.oposicontrol.admin.core.ui.components.SemanticRole.DANGER, state, value = "9 días", caption = "Mejor: 23 días") }

        assertEquals("9 días, Mejor: 23 días", kpiSpokenValue(kpi(UserKpiState.DATA)))
        assertEquals("sin dato", kpiSpokenValue(kpi(UserKpiState.NO_DATA)))
        assertEquals("no disponible", kpiSpokenValue(kpi(UserKpiState.UNAVAILABLE)))
        assertNotEquals(kpiSpokenValue(kpi(UserKpiState.NO_DATA)), kpiSpokenValue(kpi(UserKpiState.UNAVAILABLE)))
    }
}
