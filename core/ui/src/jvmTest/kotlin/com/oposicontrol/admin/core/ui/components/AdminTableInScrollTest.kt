package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import kotlin.test.Test

/** Una tabla con filas dentro de una pagina que ya desplaza no puede tumbar la pantalla (Competicion lo hacia). */
@OptIn(ExperimentalTestApi::class)
class AdminTableInScrollTest {
    @Test
    fun `a table with rows renders inside a vertically scrolling page`() = runDesktopComposeUiTest(width = 800, height = 400) {
        setContent {
            AdminTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AdminTable(
                        columns = listOf(AdminTableColumn("Liga", 1f), AdminTableColumn("Mínimo", 1f)),
                        rows = List(40) { index -> AdminTableRow(key = "liga-$index", cells = listOf("Liga $index", "$index")) },
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithText("Liga 0").assertExists()
        onNodeWithText("Liga 39").assertExists()
    }
}
