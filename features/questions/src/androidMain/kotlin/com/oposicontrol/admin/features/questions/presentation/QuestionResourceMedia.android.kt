package com.oposicontrol.admin.features.questions.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/** El panel de administración es de escritorio: aquí no hay vista previa. */
@Composable
internal actual fun QuestionResourceImagePreview(url: String, modifier: Modifier, contentScale: ContentScale) {
    Box(modifier, contentAlignment = Alignment.Center) { Text("Vista previa en escritorio") }
}

internal actual fun openQuestionResourceExternally(url: String): String? = "Abrir recursos solo está disponible en el panel de escritorio."
