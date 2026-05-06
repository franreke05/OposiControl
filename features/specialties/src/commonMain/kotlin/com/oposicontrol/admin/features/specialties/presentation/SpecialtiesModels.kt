package com.oposicontrol.admin.features.specialties.presentation

data class SpecialtyItem(
    val id: String,
    val name: String,
    val slug: String,
    val status: String,
    val linkedExams: Int,
)
