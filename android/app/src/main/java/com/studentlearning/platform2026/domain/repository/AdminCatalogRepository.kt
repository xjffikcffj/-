package com.studentlearning.platform2026.domain.repository

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudySection
import com.studentlearning.platform2026.domain.model.StudySubject

interface AdminCatalogRepository {
    suspend fun getAllBranches(): AppResult<List<StudyBranch>>
    suspend fun createBranch(
        customId: String? = null,
        name: String,
        order: Int,
        isPublished: Boolean
    ): AppResult<StudyBranch>
    suspend fun updateBranch(
        branchId: String,
        name: String,
        order: Int
    ): AppResult<Unit>
    suspend fun setBranchPublished(
        branchId: String,
        isPublished: Boolean
    ): AppResult<Unit>

    suspend fun getAllSubjects(branchId: String): AppResult<List<StudySubject>>
    suspend fun createSubject(
        customId: String? = null,
        branchId: String,
        name: String,
        order: Int,
        isPublished: Boolean
    ): AppResult<StudySubject>
    suspend fun updateSubject(
        subjectId: String,
        name: String,
        order: Int
    ): AppResult<Unit>
    suspend fun setSubjectPublished(
        subjectId: String,
        isPublished: Boolean
    ): AppResult<Unit>

    suspend fun getAllSections(subjectId: String): AppResult<List<StudySection>>
    suspend fun createSection(
        customId: String? = null,
        subjectId: String,
        name: String,
        order: Int,
        isPublished: Boolean
    ): AppResult<StudySection>
    suspend fun updateSection(
        sectionId: String,
        name: String,
        order: Int
    ): AppResult<Unit>
    suspend fun setSectionPublished(
        sectionId: String,
        isPublished: Boolean
    ): AppResult<Unit>

    suspend fun getStudyFilesForSection(sectionId: String): AppResult<List<StudyFileMetadata>>
    suspend fun createStudyFileMetadata(
        customId: String? = null,
        sectionId: String,
        title: String,
        description: String?,
        order: Int,
        isPublished: Boolean
    ): AppResult<StudyFileMetadata>
    suspend fun updateStudyFileMetadata(
        fileId: String,
        sectionId: String,
        title: String,
        description: String?,
        order: Int
    ): AppResult<Unit>
    suspend fun setStudyFilePublished(
        fileId: String,
        isPublished: Boolean
    ): AppResult<Unit>
    suspend fun setStudyFileArchived(
        fileId: String,
        isArchived: Boolean
    ): AppResult<Unit>

    suspend fun reorderBranches(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int
    ): AppResult<Unit>
    suspend fun reorderSubjects(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int
    ): AppResult<Unit>
    suspend fun reorderSections(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int
    ): AppResult<Unit>
    suspend fun reorderStudyFiles(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int
    ): AppResult<Unit>
}
