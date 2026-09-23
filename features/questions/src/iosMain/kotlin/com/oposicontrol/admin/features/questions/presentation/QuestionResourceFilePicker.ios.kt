package com.oposicontrol.admin.features.questions.presentation

import androidx.compose.runtime.Composable
import com.oposicontrol.admin.domain.repository.QuestionResourceFile
import com.oposicontrol.admin.domain.repository.QuestionResourceKind

/** Ver `QuestionResourceFilePicker.android.kt`: mismo motivo, y el formulario lo dice. */
@Composable
actual fun rememberQuestionResourceFilePicker(
    kind: QuestionResourceKind,
    onPicked: (QuestionResourceFile) -> Unit,
): QuestionResourceFilePicker = QuestionResourceFilePicker(available = false) {}
