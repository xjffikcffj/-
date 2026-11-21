package com.studentlearning.platform2026.data.remote.backend

import com.studentlearning.platform2026.data.remote.firebase.AuthTokenProvider
import com.studentlearning.platform2026.data.remote.firebase.CatalogRemoteDataSource
import com.studentlearning.platform2026.domain.model.ArchiveStatus
import com.studentlearning.platform2026.domain.model.CatalogException
import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudySection
import com.studentlearning.platform2026.domain.model.StudySubject
import kotlinx.coroutines.CancellationException
import java.io.IOException

internal class BackendCatalogDataSource(
    private val httpClient: BackendHttpClient,
    private val authTokenProvider: AuthTokenProvider? = null
) : CatalogRemoteDataSource {

    override suspend fun fetchPublishedBranches(): List<StudyBranch> {
        val root = executeCatalogHttpCall { token ->
            httpClient.getJsonObject("catalog/branches", bearerToken = token)
        }
        return root.objectListOrEmpty("items").mapNotNull { item ->
            val id = item.stringOrNull("id") ?: return@mapNotNull null
            val name = item.stringOrNull("name") ?: return@mapNotNull null
            StudyBranch(
                id = id,
                name = name,
                order = item.intOrNull("order") ?: Int.MAX_VALUE,
                isPublished = true,
                referencePath = item.stringOrNull("referencePath") ?: "branches/$id"
            )
        }
    }

    override suspend fun fetchPublishedSubjects(branchId: String): List<StudySubject> {
        val cleanBranchId = branchId.trim()
        if (cleanBranchId.isEmpty()) return emptyList()
        val encodedBranchId = BackendJson.encodePathSegment(cleanBranchId)
        val root = executeCatalogHttpCall { token ->
            httpClient.getJsonObject("catalog/branches/$encodedBranchId/subjects", bearerToken = token)
        }
        return root.objectListOrEmpty("items").mapNotNull { item ->
            val id = item.stringOrNull("id") ?: return@mapNotNull null
            val name = item.stringOrNull("name") ?: return@mapNotNull null
            val itemBranchId = item.stringOrNull("branchId") ?: cleanBranchId
            StudySubject(
                id = id,
                branchId = itemBranchId,
                name = name,
                order = item.intOrNull("order") ?: Int.MAX_VALUE,
                isPublished = true,
                branchReferencePath = item.stringOrNull("branchReferencePath") ?: "branches/$itemBranchId"
            )
        }
    }

    override suspend fun fetchPublishedSections(subjectId: String): List<StudySection> {
        val cleanSubjectId = subjectId.trim()
        if (cleanSubjectId.isEmpty()) return emptyList()
        val encodedSubjectId = BackendJson.encodePathSegment(cleanSubjectId)
        val root = executeCatalogHttpCall { token ->
            httpClient.getJsonObject("catalog/subjects/$encodedSubjectId/sections", bearerToken = token)
        }
        return root.objectListOrEmpty("items").mapNotNull { item ->
            val id = item.stringOrNull("id") ?: return@mapNotNull null
            val name = item.stringOrNull("name") ?: return@mapNotNull null
            val itemSubjectId = item.stringOrNull("subjectId") ?: cleanSubjectId
            StudySection(
                id = id,
                subjectId = itemSubjectId,
                name = name,
                order = item.intOrNull("order") ?: Int.MAX_VALUE,
                isPublished = true,
                subjectReferencePath = item.stringOrNull("subjectReferencePath") ?: "subjects/$itemSubjectId"
            )
        }
    }

    override suspend fun fetchPublishedStudyFiles(sectionId: String): List<StudyFileMetadata> {
        val cleanSectionId = sectionId.trim()
        if (cleanSectionId.isEmpty()) return emptyList()
        val encodedSectionId = BackendJson.encodePathSegment(cleanSectionId)
        val root = executeCatalogHttpCall { token ->
            httpClient.getJsonObject("catalog/sections/$encodedSectionId/study-files", bearerToken = token)
        }
        return root.objectListOrEmpty("items").mapNotNull { item ->
            val id = item.stringOrNull("id") ?: return@mapNotNull null
            val title = item.stringOrNull("title") ?: return@mapNotNull null
            val archiveStatus = when (item.stringOrNull("archiveStatus")?.trim()?.uppercase()) {
                "UNARCHIVED" -> ArchiveStatus.UNARCHIVED
                else -> return@mapNotNull null
            }
            val activeVersionId = item.stringOrNull("activeVersionId") ?: return@mapNotNull null
            val activeVersionNumber = item.intOrNull("activeVersionNumber")?.takeIf { it >= 1 }
                ?: return@mapNotNull null
            val fileSizeBytes = item.longOrNull("fileSizeBytes")?.takeIf { it > 0L }
            val checksumSha256 = item.stringOrNull("checksumSha256")?.lowercase()
            val itemSectionId = item.stringOrNull("sectionId") ?: cleanSectionId
            StudyFileMetadata(
                id = id,
                sectionId = itemSectionId,
                sectionReferencePath = "sections/$itemSectionId",
                title = title,
                description = item.stringOrNull("description"),
                order = item.intOrNull("order") ?: Int.MAX_VALUE,
                isPublished = true,
                archiveStatus = archiveStatus,
                mimeType = item.stringOrNull("mimeType") ?: StudyFileMetadata.MIME_TYPE_PDF,
                storageProvider = StudyFileMetadata.STORAGE_PROVIDER_CLOUDFLARE_R2,
                activeVersionId = activeVersionId,
                activeVersionNumber = activeVersionNumber,
                activeVersionLabel = item.stringOrNull("activeVersionLabel"),
                activeVersionPublishedAtEpochMillis = item.longOrNull("activeVersionPublishedAtEpochMillis"),
                activeVersionFileSizeBytes = fileSizeBytes,
                activeVersionChecksumSha256 = checksumSha256
            )
        }
    }

    private suspend fun executeCatalogHttpCall(
        call: suspend (token: String?) -> Map<String, Any?>
    ): Map<String, Any?> {
        if (!httpClient.isConfigured) {
            throw CatalogException(
                reason = CatalogFailureReason.FIREBASE_NOT_CONFIGURED,
                message = "Backend catalog endpoint URL is not configured yet."
            )
        }
        val optionalToken = try {
            authTokenProvider?.getIdTokenOrNull(forceRefresh = false)
        } catch (_: CancellationException) {
            throw CancellationException("Catalog request cancelled.")
        } catch (_: Throwable) {
            null
        }
        return try {
            call(optionalToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (notConfigured: BackendNotConfiguredException) {
            throw CatalogException(
                reason = CatalogFailureReason.FIREBASE_NOT_CONFIGURED,
                message = notConfigured.message,
                cause = notConfigured
            )
        } catch (httpError: BackendHttpException) {
            val reason = when (httpError.statusCode) {
                401, 403 -> CatalogFailureReason.PERMISSION_DENIED
                404 -> CatalogFailureReason.UNKNOWN
                else -> CatalogFailureReason.UNKNOWN
            }
            throw CatalogException(
                reason = reason,
                message = httpError.message,
                cause = httpError
            )
        } catch (io: IOException) {
            throw CatalogException(
                reason = CatalogFailureReason.NETWORK_UNAVAILABLE,
                message = io.localizedMessage ?: "Network error fetching catalog from backend.",
                cause = io
            )
        } catch (throwable: Throwable) {
            throw CatalogException(
                reason = CatalogFailureReason.UNKNOWN,
                message = throwable.localizedMessage ?: "Unexpected error fetching catalog from backend.",
                cause = throwable
            )
        }
    }
}
