package com.studentlearning.platform2026.domain.model

data class StudySection(
    val id: String,
    val subjectId: String,
    val name: String,
    val order: Int,
    val isPublished: Boolean,
    val subjectReferencePath: String = "subjects/$subjectId"
)
