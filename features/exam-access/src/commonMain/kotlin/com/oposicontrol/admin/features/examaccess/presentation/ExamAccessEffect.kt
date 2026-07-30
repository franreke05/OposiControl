package com.oposicontrol.admin.features.examaccess.presentation

/** One-shot access operation outcomes consumed by the route. */
sealed interface ExamAccessEffect {
    data class Saved(val id: ExamAccessId) : ExamAccessEffect
}
