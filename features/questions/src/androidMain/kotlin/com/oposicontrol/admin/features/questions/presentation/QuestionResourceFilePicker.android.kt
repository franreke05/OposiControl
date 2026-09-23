package com.oposicontrol.admin.features.questions.presentation

import androidx.compose.runtime.Composable
import com.oposicontrol.admin.domain.repository.QuestionResourceFile
import com.oposicontrol.admin.domain.repository.QuestionResourceKind

/**
 * Sin selector en Android.
 *
 * El banco de Preguntas es la consola de escritorio (ver el contrato de
 * `QuestionsRepository`), y montar aquí el `ActivityResultContracts` que haría falta
 * —con su dependencia de `activity-compose` y su lectura por `content://`— sería
 * escribir una capacidad que hoy nadie abre.
 *
 * `available = false` es la parte que importa: el formulario lo lee y, en vez de
 * enseñar un botón que no abre nada, dice que la subida de archivos se hace desde
 * la aplicación de escritorio. Los recursos de `texto` y `enlace` siguen
 * funcionando aquí, porque no suben nada.
 */
@Composable
actual fun rememberQuestionResourceFilePicker(
    kind: QuestionResourceKind,
    onPicked: (QuestionResourceFile) -> Unit,
): QuestionResourceFilePicker = QuestionResourceFilePicker(available = false) {}
