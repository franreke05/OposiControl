package com.oposicontrol.admin.features.audit.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.domain.model.AuditDetail
import com.oposicontrol.admin.domain.model.AuditField
import com.oposicontrol.admin.domain.model.AuditFilter
import com.oposicontrol.admin.domain.model.AuditMovement
import com.oposicontrol.admin.domain.model.AuditPage
import com.oposicontrol.admin.domain.repository.AuditRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Auditoria con el [AuditViewModel] REAL contra un [AuditRepository] falso (el banco visual solo pinta estados fabricados):
 * que la lista dice que, quien y cuando; que el filtro de accion viaja al servidor; que abrir un movimiento trae su antes y
 * despues; y que un 403 se ve como un fallo, no como un registro vacio.
 */
@OptIn(ExperimentalTestApi::class)
class AuditScreenUiTest {
    private object Unconfined : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private val zone = AuditFixtures.ZONE

    private val rows = listOf(
        AuditMovement("m1", "actor-ana", "Ana García", "cuenta_dada_de_baja", "perfiles_usuario", "r-1", "2026-10-13T12:34:56+00:00"),
        AuditMovement("m2", "actor-marta", "Marta Ruiz Peña", "roles_sincronizados", "roles_usuario", "r-2", "2026-10-12T09:15:00+00:00"),
    )

    private val detailM2 = AuditDetail(
        id = "m2",
        before = listOf(AuditField("estado_cuenta", "activa")),
        after = listOf(AuditField("estado_cuenta", "bloqueada")),
    )

    private class FakeAudit(
        var movementsResult: AppResult<AuditPage>,
        val details: Map<String, AuditDetail> = emptyMap(),
    ) : AuditRepository {
        val fetches = mutableListOf<AuditFilter>()
        val detailFetches = mutableListOf<String>()
        override suspend fun fetchMovements(filter: AuditFilter, cursor: String?): AppResult<AuditPage> {
            fetches += filter
            return movementsResult
        }
        override suspend fun fetchDetail(id: String): AppResult<AuditDetail> {
            detailFetches += id
            return details[id]?.let { AppResult.Success(it) } ?: AppResult.Failure(AppError.NotFound("sin detalle"))
        }
    }

    /** Como [AuditScreen] (estado del VM + `dispatch`), con la zona fija para que las horas no dependan del equipo. */
    private fun audit(repo: FakeAudit, block: ComposeUiTest.(AuditViewModel) -> Unit) =
        runDesktopComposeUiTest(width = 1920, height = 1080) {
            val vm = AuditViewModel(Unconfined, repo, NoOpAnalyticsTracker, zone)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                    AdminTheme(darkTheme = false) {
                        val state by vm.state.collectAsState()
                        AuditContent(state = state, onIntent = vm::dispatch, zone = zone)
                    }
                }
            }
            waitForIdle()
            block(vm)
        }

    private fun ComposeUiTest.count(text: String): Int = onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun theListShowsActionActorAndDate() {
        val repo = FakeAudit(AppResult.Success(AuditPage(rows, nextCursor = null)))
        audit(repo) {
            rows.forEach { row ->
                assertTrue(count(auditActionLabel(row.action)) >= 1, "Falta la accion de ${row.id}")
                assertTrue(count(row.actorName!!) >= 1, "Falta quien hizo ${row.id}")
                onNodeWithText(formatAuditMoment(row.occurredAt, zone, withSeconds = false)).assertExists()
            }
            onNodeWithText("2 movimientos cargados. No hay más.").assertExists()
        }
        assertEquals(listOf(AuditFilter()), repo.fetches)
    }

    @Test
    fun filteringByActionAsksTheServerWithThatFilter() {
        val repo = FakeAudit(AppResult.Success(AuditPage(rows, nextCursor = null)))
        audit(repo) { vm ->
            onNodeWithText("Todas las acciones").performClick()
            waitForIdle()
            onNodeWithText("Cuenta reactivada").performClick()
            waitForIdle()
            assertEquals("cuenta_reactivada", vm.state.value.actionFilter)
            onNodeWithText("Quitar filtros").assertExists()
        }
        assertEquals(listOf(AuditFilter(), AuditFilter(action = "cuenta_reactivada")), repo.fetches)
    }

    @Test
    fun openingAMovementShowsItsDetailAndWhatChanged() {
        val repo = FakeAudit(AppResult.Success(AuditPage(rows, nextCursor = null)), details = mapOf("m2" to detailM2))
        audit(repo) {
            // La ficha abre con el mas reciente; se abre el segundo desde el catalogo.
            onAllNodesWithText(auditActionLabel("roles_sincronizados")).onFirst().performClick()
            waitForIdle()
            onNodeWithText("Detalle del movimiento").assertExists()
            onNodeWithText("Marta Ruiz Peña · ${formatAuditMoment(rows[1].occurredAt, zone)}").assertExists()
            onNodeWithText("Registro r-2").assertExists()
            assertTrue(repo.detailFetches.isEmpty(), "El antes y el despues se piden bajo demanda, no al abrir")

            onNodeWithText("Ver qué cambió").performClick()
            waitForIdle()
            onNodeWithText(auditChangeRows(detailM2.before, detailM2.after).single().text).assertExists()
        }
        assertEquals(listOf("m2"), repo.detailFetches)
    }

    @Test
    fun anAuthorizationFailureIsAnErrorNotAnEmptyLog() {
        val repo = FakeAudit(AppResult.Failure(AppError.Authorization("No tienes permisos para acceder al panel de administración.")))
        audit(repo) {
            onNodeWithText("No hemos podido cargar los datos").assertExists()
            onNodeWithText("Tu usuario no tiene permiso para ver esto.").assertExists()
            onNodeWithText("Todavía no hay movimientos").assertDoesNotExist()
            onNodeWithText("Sin movimientos que enseñar").assertExists()
            // Un fallo de permisos no ofrece «Reintentar»: repetirlo daria lo mismo.
            onNodeWithText("Reintentar").assertDoesNotExist()
        }
    }
}
