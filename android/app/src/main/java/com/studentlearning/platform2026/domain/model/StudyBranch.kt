package com.studentlearning.platform2026.domain.model

data class StudyBranch(
    val id: String,
    val name: String,
    val order: Int,
    val isPublished: Boolean,
    val referencePath: String = "branches/$id"
)
