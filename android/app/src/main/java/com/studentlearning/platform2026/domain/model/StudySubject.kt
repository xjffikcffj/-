package com.studentlearning.platform2026.domain.model

data class StudySubject(
    val id: String,
    val branchId: String,
    val name: String,
    val order: Int,
    val isPublished: Boolean,
    val branchReferencePath: String = "branches/$branchId"
)
