package com.oposicontrol.admin.features.examaccess.presentation

data class ExamAccessItem(
    val id: String,
    val userName: String,
    val examTitle: String,
    val accessType: String,
    val status: String,
)
