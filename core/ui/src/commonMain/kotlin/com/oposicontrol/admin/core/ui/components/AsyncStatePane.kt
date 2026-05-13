package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync

@Composable
fun <T> AsyncStatePane(
    state: UiAsync<T>,
    modifier: Modifier = Modifier,
    emptyMessage: String = "No hay datos disponibles.",
    content: @Composable (T) -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Crossfade(
            targetState = state,
            animationSpec = tween(durationMillis = 220),
            modifier = Modifier.fillMaxWidth(),
        ) { targetState ->
            when (targetState) {
                UiAsync.Empty -> EmptyStateCard(
                    title = "Sin resultados",
                    message = emptyMessage,
                )

                is UiAsync.Error -> EmptyStateCard(
                    title = "No se pudo cargar",
                    message = targetState.error.message,
                )

                UiAsync.Idle -> StateMessage(text = "Listo para cargar.")
                UiAsync.Loading -> LoadingStateCard(
                    rows = 4,
                )
                is UiAsync.Success -> content(targetState.value)
            }
        }
    }
}

@Composable
private fun StateMessage(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
