package com.oposicontrol.admin.features.controlv1.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.domain.model.ForumContentType
import com.oposicontrol.admin.domain.model.ForumQueueItem
import com.oposicontrol.admin.domain.model.ForumQueuePage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Lo que ve el rol `moderador` (decisión del propietario 2026-10-20): [ForumModerationQueueScreen] con el
 * [ForumQueueViewModel] REAL. La cola sí; canales, bloqueos y límites (solo admin) no se ofrecen, y la pantalla no llama a
 * ninguna ruta de configuración.
 */
@OptIn(ExperimentalTestApi::class)
class ForumModerationQueueUiTest {
    private object Unconfined : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private val pending = ForumQueueItem(
        type = ForumContentType.POST, id = "p1", postId = null, channel = "Dudas", title = "Duda sobre el tema 3",
        excerpt = "Una duda sobre el tema 3 del temario", state = "pendiente_moderacion", authorHandle = "ana",
        createdAt = "2026-10-20T09:00:00Z", hasImage = false, pendingReports = 0, reportReasons = emptyList(),
    )

    private fun screen(repo: FakeControlV1Repository, block: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 1366, height = 1200) {
            val vm = ForumQueueViewModel(Unconfined, repo)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                    AdminTheme(darkTheme = false) { ForumModerationQueueScreen(queueViewModel = vm) }
                }
            }
            waitForIdle()
            block()
        }

    @Test
    fun theModeratorSeesTheQueueAndNothingOfTheForumConfiguration() {
        val repo = FakeControlV1Repository().apply { queue = ForumQueuePage(listOf(pending), null) }
        screen(repo) {
            onNodeWithText("Duda sobre el tema 3").assertExists()
            // Las etiquetas reales de las pestañas de ForumOpsScreen (ForumOpsTab) que el moderador no debe ver.
            for (adminOnly in listOf("Cola", "Canales", "Bloqueos y silencios", "Límites", "Límites y tamaños del foro")) {
                onNodeWithText(adminOnly).assertDoesNotExist()
            }
        }
        assertTrue(repo.calls.any { it.startsWith("forumQueue:publicacion:pendiente_moderacion") }, repo.calls.toString())
        val config = repo.calls.filter { it.startsWith("forumChannels") || it.startsWith("forumBlockList") || it.startsWith("getConfig") }
        assertTrue(config.isEmpty(), "la pantalla del moderador no pide configuración: $config")
    }
}
