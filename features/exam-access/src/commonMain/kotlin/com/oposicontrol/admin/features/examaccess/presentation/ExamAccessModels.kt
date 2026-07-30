package com.oposicontrol.admin.features.examaccess.presentation

/** Stable access identifier used by presentation contracts. */
@JvmInline
value class ExamAccessId(val value: String)

/** Stable user identifier selected by the access form. */
@JvmInline
value class UserId(val value: String)

/** Stable exam identifier selected by the access form. */
@JvmInline
value class ExamId(val value: String)

data class ExamAccessUserOption(
    val id: UserId,
    val displayName: String,
)

data class ExamAccessExamOption(
    val id: ExamId,
    val title: String,
)

data class ExamAccessItem(
    val id: ExamAccessId,
    val userId: UserId?,
    val examId: ExamId?,
    val userName: String,
    val examTitle: String,
    val accessType: String,
    val status: String,
    val version: Long,
)
