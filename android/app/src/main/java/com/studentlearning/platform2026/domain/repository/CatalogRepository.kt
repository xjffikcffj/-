package com.studentlearning.platform2026.domain.repository

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudySection
import com.studentlearning.platform2026.domain.model.StudySubject

interface CatalogRepository {
    suspend fun getPublishedBranches(): AppResult<List<StudyBranch>>
    suspend fun getPublishedSubjects(branchId: String): AppResult<List<StudySubject>>
    suspend fun getPublishedSections(subjectId: String): AppResult<List<StudySection>>
    suspend fun getPublishedStudyFiles(sectionId: String): AppResult<List<StudyFileMetadata>>
}
