package com.oposicontrol.admin.features.questions.presentation

import androidx.compose.runtime.Composable
import com.oposicontrol.admin.domain.repository.QuestionResourceFile
import com.oposicontrol.admin.domain.repository.QuestionResourceKind

/**
 * El selector de archivo de un recurso de pregunta.
 *
 * Propio de este módulo y no reutilizado de `features/exams` a propósito: Preguntas
 * dejó de depender de Exámenes al separarse, y volver a atarlos por un selector de
 * ficheros sería deshacerlo por la puerta de atrás. Sigue el patrón que
 * `features/imports` ya tenía resuelto — `expect` aquí, `JFileChooser` real en JVM,
 * y un no-op DECLARADO donde la pantalla no se pinta.
 *
 * [available] es lo que impide que ese no-op se convierta en una mentira: donde no
 * hay selector, el formulario lo dice y no ofrece un botón que no abre nada. Un
 * control que parece funcionar y no hace nada es exactamente el defecto que esta
 * sección tenía antes.
 *
 * Devuelve los BYTES y no una ruta porque el repositorio no sabe —ni debe saber—
 * leer del disco de cada plataforma; ver `QuestionResourceFile`.
 */
class QuestionResourceFilePicker(
    val available: Boolean,
    private val openPicker: () -> Unit,
) {
    fun open() = openPicker()
}

/**
 * [kind] elige el filtro del diálogo y el MIME que se declara: son los mismos
 * cuatro tipos de archivo que el servidor admite. La AUTORIDAD sigue siendo suya —
 * un MIME que no case con el tipo es un `QUESTION_RESOURCE_TYPE_INVALID` que él
 * redacta—; esto sólo evita ofrecer un `.docx` para un recurso de tipo `imagen`.
 */
@Composable
expect fun rememberQuestionResourceFilePicker(
    kind: QuestionResourceKind,
    onPicked: (QuestionResourceFile) -> Unit,
): QuestionResourceFilePicker
