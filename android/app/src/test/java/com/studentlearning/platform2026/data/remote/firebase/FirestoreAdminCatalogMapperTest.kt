package com.studentlearning.platform2026.data.remote.firebase

import com.studentlearning.platform2026.data.remote.firebase.FirestoreCatalogSchema.Defaults
import com.studentlearning.platform2026.data.remote.firebase.FirestoreCatalogSchema.Fields
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminValidationError
import com.studentlearning.platform2026.domain.model.StudyFileVersionMetadata
import com.studentlearning.platform2026.domain.repository.VerifiedPdfBinaryProof
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FirestoreAdminCatalogMapperTest {

    @Test
    fun `mapBranchesForAdmin includes both published and unpublished branches sorted by order`() {
        val docs = listOf(
            "literary" to mapOf("name" to "السادس الادبي", "order" to 2L, "isPublished" to false),
            "scientific" to mapOf("name" to "السادس العلمي", "order" to 1L, "isPublished" to true)
        )

        val result = FirestoreAdminCatalogMapper.mapBranchesForAdmin(docs)

        assertEquals(listOf("scientific", "literary"), result.map { it.id })
        assertTrue(result[0].isPublished)
        assertFalse(result[1].isPublished)
    }

    @Test
    fun `mapSubjectsForAdmin requires DocumentReference to branches and maps unpublished subjects`() {
        val branchRef = FirestoreReferenceTarget("branches", "scientific", "branches/scientific")
        val docs = listOf(
            "physics" to mapOf("branchId" to branchRef, "name" to "الفيزياء", "order" to 2L, "isPublished" to false),
            "stringRejected" to mapOf("branchId" to "scientific", "name" to "مرفوض", "order" to 1L, "isPublished" to true)
        )

        val result = FirestoreAdminCatalogMapper.mapSubjectsForAdmin(docs, expectedBranchId = "scientific")

        assertEquals(1, result.size)
        assertEquals("physics", result[0].id)
        assertFalse(result[0].isPublished)
        assertEquals("branches/scientific", result[0].branchReferencePath)
    }

    @Test
    fun `buildSectionCreatePayload stores subjectId as reference and omits branchId completely`() {
        val subjectRef = FirestoreReferenceTarget("subjects", "physics", "subjects/physics")
        val payload = FirestoreAdminCatalogMapper.buildSectionCreatePayload(
            subjectReference = subjectRef,
            name = "الفصل الأول",
            order = 1,
            isPublished = false,
            actorUid = "owner-uid",
            timestampValue = "SERVER_TS"
        )

        assertEquals(subjectRef, payload[Fields.SUBJECT_ID])
        assertEquals("الفصل الأول", payload[Fields.NAME])
        assertEquals(1, payload[Fields.ORDER])
        assertEquals(false, payload[Fields.IS_PUBLISHED])
        assertFalse(payload.containsKey(Fields.BRANCH_ID))
    }

    @Test
    fun `buildStudyFileCreatePayload sets pointer fields to null without fake versions or R2 URLs`() {
        val sectionRef = FirestoreReferenceTarget("sections", "sec1", "sections/sec1")
        val payload = FirestoreAdminCatalogMapper.buildStudyFileCreatePayload(
            sectionReference = sectionRef,
            title = "ملخص المتسعات",
            description = "مراجعة مركزة",
            order = 1,
            isPublished = false,
            actorUid = "owner-uid",
            timestampValue = "SERVER_TS"
        )

        assertEquals(sectionRef, payload[Fields.SECTION_ID])
        assertEquals(Defaults.MIME_TYPE_PDF, payload[Fields.MIME_TYPE])
        assertEquals(Defaults.STORAGE_PROVIDER_CLOUDFLARE_R2, payload[Fields.STORAGE_PROVIDER])
        assertNull(payload[Fields.ACTIVE_VERSION_ID])
        assertNull(payload[Fields.ACTIVE_VERSION_NUMBER])
        assertNull(payload[Fields.ACTIVE_VERSION_LABEL])
        assertFalse(payload.containsKey(Fields.R2_OBJECT_KEY))
        assertFalse(payload.containsKey(Fields.FILE_SIZE_BYTES))
        assertFalse(payload.containsKey(Fields.CHECKSUM_SHA256))
        assertFalse(payload.containsKey("downloadUrl"))
        assertFalse(payload.containsKey("url"))
        assertEquals(false, payload[Fields.IS_ARCHIVED])
    }

    @Test
    fun `buildStudyFileUpdatePayload excludes active version pointer and R2 storage fields`() {
        val payload = FirestoreAdminCatalogMapper.buildStudyFileUpdatePayload(
            title = "عنوان محدث",
            description = "وصف محدث",
            order = 3,
            actorUid = "admin-uid",
            timestampValue = "SERVER_TS"
        )

        assertEquals("عنوان محدث", payload[Fields.TITLE])
        assertEquals("وصف محدث", payload[Fields.DESCRIPTION])
        assertEquals(3, payload[Fields.ORDER])
        assertFalse(payload.containsKey(Fields.ACTIVE_VERSION_ID))
        assertFalse(payload.containsKey(Fields.ACTIVE_VERSION_NUMBER))
        assertFalse(payload.containsKey(Fields.ACTIVE_VERSION_LABEL))
        assertFalse(payload.containsKey(Fields.R2_OBJECT_KEY))
        assertFalse(payload.containsKey(Fields.VERSION_NUMBER))
        assertFalse(payload.containsKey(Fields.VERSION_LABEL))
    }

    @Test
    fun `mapStudyFilesForAdmin places non-archived first and reads optional active version pointers`() {
        val sectionRef = FirestoreReferenceTarget("sections", "sec1", "sections/sec1")
        val wrongRef = FirestoreReferenceTarget("subjects", "sec1", "subjects/sec1")
        val docs = listOf(
            "archived_file" to mapOf(
                "sectionId" to sectionRef,
                "title" to "ملف قديم",
                "order" to 1L,
                "isPublished" to false,
                "isArchived" to true
            ),
            "active_file" to mapOf(
                "sectionId" to sectionRef,
                "title" to "ملف حالي",
                "order" to 2L,
                "isPublished" to true,
                "isArchived" to false,
                "activeVersionId" to "ver_2",
                "activeVersionNumber" to 2L,
                "activeVersionLabel" to "2.0"
            ),
            "rejected_wrong_ref" to mapOf(
                "sectionId" to wrongRef,
                "title" to "مرفوض",
                "order" to 0L,
                "isPublished" to true
            )
        )

        val result = FirestoreAdminCatalogMapper.mapStudyFilesForAdmin(docs, expectedSectionId = "sec1")

        assertEquals(listOf("active_file", "archived_file"), result.map { it.id })
        assertTrue(result[0].hasActiveVersion)
        assertEquals("ver_2", result[0].activeVersionId)
        assertEquals(2, result[0].activeVersionNumber)
        assertEquals("2.0", result[0].activeVersionLabel)
        assertEquals("sections/sec1", result[0].sectionReferencePath)
        assertFalse(result[1].hasActiveVersion)
        assertNull(result[1].activeVersionId)
    }

    @Test
    fun `mapStudyFileVersionsForAdmin enforces file DocumentReference and sorts descending by versionNumber`() {
        val fileRef = FirestoreReferenceTarget("study_files", "file-1", "study_files/file-1")
        val wrongRef = FirestoreReferenceTarget("study_files", "file-2", "study_files/file-2")
        val shaV1 = "1".repeat(64)
        val shaV2 = "2".repeat(64)
        val docs = listOf(
            "ver_1" to mapOf(
                Fields.FILE_ID to fileRef,
                Fields.VERSION_NUMBER to 1L,
                Fields.VERSION_LABEL to "1.0",
                Fields.IS_PUBLISHED to false,
                Fields.R2_OBJECT_KEY to "study-files/file-1/versions/ver_1/document.pdf",
                Fields.FILE_SIZE_BYTES to 10_240L,
                Fields.CHECKSUM_SHA256 to shaV1
            ),
            "ver_2" to mapOf(
                Fields.FILE_ID to fileRef,
                Fields.VERSION_NUMBER to 2L,
                Fields.VERSION_LABEL to "2.0",
                Fields.IS_PUBLISHED to true,
                Fields.IS_ARCHIVED to false,
                Fields.R2_OBJECT_KEY to "study-files/file-1/versions/ver_2/document.pdf",
                Fields.FILE_SIZE_BYTES to 20_480L,
                Fields.CHECKSUM_SHA256 to shaV2
            ),
            "ver_missing_sha" to mapOf(
                Fields.FILE_ID to fileRef,
                Fields.VERSION_NUMBER to 5L,
                Fields.VERSION_LABEL to "5.0",
                Fields.IS_PUBLISHED to false,
                Fields.R2_OBJECT_KEY to "study-files/file-1/versions/ver_missing_sha/document.pdf",
                Fields.FILE_SIZE_BYTES to 20_480L
            ),
            "string_ref_rejected" to mapOf(
                Fields.FILE_ID to "file-1",
                Fields.VERSION_NUMBER to 3L,
                Fields.VERSION_LABEL to "3.0"
            ),
            "other_file_rejected" to mapOf(
                Fields.FILE_ID to wrongRef,
                Fields.VERSION_NUMBER to 4L,
                Fields.VERSION_LABEL to "4.0"
            )
        )

        val result = FirestoreAdminCatalogMapper.mapStudyFileVersionsForAdmin(docs, expectedFileId = "file-1")

        assertEquals(listOf("ver_missing_sha", "ver_2", "ver_1"), result.map { it.id })
        assertFalse(result[0].hasUploadedPdfBinary)
        assertTrue(result[1].isPublished)
        assertTrue(result[1].hasUploadedPdfBinary)
        assertFalse(result[2].isPublished)
        assertTrue(result[2].hasUploadedPdfBinary)
    }

    @Test
    fun `hasReliableUploadedPdfProof requires valid 64-hex sha256 matching R2 key and file and version ids`() {
        val validSha = "a".repeat(64)
        assertTrue(
            FirestoreAdminCatalogMapper.hasReliableUploadedPdfProof(
                fileId = "file-1",
                versionId = "ver_2",
                r2ObjectKey = "study-files/file-1/versions/ver_2/physics.pdf",
                fileSizeBytes = 12345L,
                checksumSha256 = validSha
            )
        )
        // Missing (null) sha256 must be rejected.
        assertFalse(
            FirestoreAdminCatalogMapper.hasReliableUploadedPdfProof(
                fileId = "file-1",
                versionId = "ver_2",
                r2ObjectKey = "study-files/file-1/versions/ver_2/physics.pdf",
                fileSizeBytes = 12345L,
                checksumSha256 = null
            )
        )
        // Blank / empty sha256 must be rejected.
        assertFalse(
            FirestoreAdminCatalogMapper.hasReliableUploadedPdfProof(
                fileId = "file-1",
                versionId = "ver_2",
                r2ObjectKey = "study-files/file-1/versions/ver_2/physics.pdf",
                fileSizeBytes = 12345L,
                checksumSha256 = "   "
            )
        )
        // Invalid hex or wrong length sha256 must be rejected.
        assertFalse(
            FirestoreAdminCatalogMapper.hasReliableUploadedPdfProof(
                fileId = "file-1",
                versionId = "ver_2",
                r2ObjectKey = "study-files/file-1/versions/ver_2/document.pdf",
                fileSizeBytes = 5000L,
                checksumSha256 = "not-a-sha"
            )
        )
        assertFalse(
            FirestoreAdminCatalogMapper.hasReliableUploadedPdfProof(
                fileId = "file-1",
                versionId = "ver_2",
                r2ObjectKey = "study-files/file-1/versions/ver_2/document.pdf",
                fileSizeBytes = 5000L,
                checksumSha256 = "z".repeat(64)
            )
        )
        assertFalse(
            FirestoreAdminCatalogMapper.hasReliableUploadedPdfProof(
                fileId = "file-1",
                versionId = "ver_2",
                r2ObjectKey = null,
                fileSizeBytes = 12345L,
                checksumSha256 = validSha
            )
        )
        assertFalse(
            FirestoreAdminCatalogMapper.hasReliableUploadedPdfProof(
                fileId = "file-1",
                versionId = "ver_2",
                r2ObjectKey = "https://cdn.example.com/file.pdf",
                fileSizeBytes = 12345L,
                checksumSha256 = validSha
            )
        )
        assertFalse(
            FirestoreAdminCatalogMapper.hasReliableUploadedPdfProof(
                fileId = "file-1",
                versionId = "ver_2",
                r2ObjectKey = "study-files/file-1/versions/ver_2/document.pdf",
                fileSizeBytes = 0L,
                checksumSha256 = validSha
            )
        )
        // Mismatched versionId in R2 key must be rejected.
        assertFalse(
            FirestoreAdminCatalogMapper.hasReliableUploadedPdfProof(
                fileId = "file-1",
                versionId = "ver_2",
                r2ObjectKey = "study-files/file-1/versions/other_ver/document.pdf",
                fileSizeBytes = 5000L,
                checksumSha256 = validSha
            )
        )
        // Mismatched fileId in R2 key must be rejected.
        assertFalse(
            FirestoreAdminCatalogMapper.hasReliableUploadedPdfProof(
                fileId = "file-1",
                versionId = "ver_2",
                r2ObjectKey = "study-files/other-file/versions/ver_2/document.pdf",
                fileSizeBytes = 5000L,
                checksumSha256 = validSha
            )
        )
        // UI model check (hasUploadedPdfBinary) must stay 100% consistent with Firestore proof validation.
        val versionMissingSha = StudyFileVersionMetadata(
            id = "ver_2",
            fileId = "file-1",
            versionNumber = 2,
            versionLabel = "2.0",
            r2ObjectKey = "study-files/file-1/versions/ver_2/document.pdf",
            fileSizeBytes = 5000L,
            checksumSha256 = null
        )
        val versionWrongKey = versionMissingSha.copy(
            r2ObjectKey = "study-files/wrong-file/versions/ver_2/document.pdf",
            checksumSha256 = validSha
        )
        val validVersion = versionMissingSha.copy(checksumSha256 = validSha)
        assertFalse(versionMissingSha.hasUploadedPdfBinary)
        assertFalse(versionWrongKey.hasUploadedPdfBinary)
        assertTrue(validVersion.hasUploadedPdfBinary)
    }

    @Test
    fun `matchesVerifiedPdfBinaryProof rejects mismatched server key size or checksum`() {
        val sha = "b".repeat(64)
        val version = StudyFileVersionMetadata(
            id = "ver_2",
            fileId = "file-1",
            versionNumber = 2,
            versionLabel = "2.0",
            r2ObjectKey = "study-files/file-1/versions/ver_2/document.pdf",
            fileSizeBytes = 10_000L,
            checksumSha256 = sha
        )
        val matchingProof = VerifiedPdfBinaryProof(
            fileId = "file-1",
            versionId = "ver_2",
            r2ObjectKey = "study-files/file-1/versions/ver_2/document.pdf",
            fileSizeBytes = 10_000L,
            checksumSha256 = sha.uppercase()
        )
        assertTrue(
            FirestoreAdminCatalogMapper.matchesVerifiedPdfBinaryProof(
                version = version,
                verifiedProof = matchingProof,
                expectedFileId = "file-1"
            )
        )
        assertFalse(
            FirestoreAdminCatalogMapper.matchesVerifiedPdfBinaryProof(
                version = version,
                verifiedProof = matchingProof.copy(fileSizeBytes = 9_999L),
                expectedFileId = "file-1"
            )
        )
        assertFalse(
            FirestoreAdminCatalogMapper.matchesVerifiedPdfBinaryProof(
                version = version,
                verifiedProof = matchingProof.copy(checksumSha256 = "c".repeat(64)),
                expectedFileId = "file-1"
            )
        )
    }

    @Test
    fun `version payloads and registered version number extraction preserve R2 boundaries`() {
        val fileRef = FirestoreReferenceTarget("study_files", "file-1", "study_files/file-1")
        val createPayload = FirestoreAdminCatalogMapper.buildStudyFileVersionCreatePayload(
            fileReference = fileRef,
            versionNumber = 2,
            versionLabel = "2.0",
            versionNotes = "تحديث شامل",
            isPublished = false,
            actorUid = "owner-uid",
            timestampValue = "SERVER_TS"
        )
        assertEquals(fileRef, createPayload[Fields.FILE_ID])
        assertNull(createPayload[Fields.R2_OBJECT_KEY])
        assertNull(createPayload[Fields.FILE_SIZE_BYTES])
        assertNull(createPayload[Fields.CHECKSUM_SHA256])
        assertEquals(false, createPayload[Fields.IS_PUBLISHED])
        assertEquals(false, createPayload[Fields.IS_ARCHIVED])
        assertFalse(createPayload.containsKey("downloadUrl"))

        val pointerPayload = FirestoreAdminCatalogMapper.buildActiveVersionPointerPayload(
            versionId = "ver_2",
            versionNumber = 2,
            versionLabel = "2.0",
            publishedAtValue = "SERVER_TS",
            actorUid = "owner-uid",
            timestampValue = "SERVER_TS"
        )
        assertEquals("ver_2", pointerPayload[Fields.ACTIVE_VERSION_ID])
        assertEquals(2, pointerPayload[Fields.ACTIVE_VERSION_NUMBER])
        assertEquals("2.0", pointerPayload[Fields.ACTIVE_VERSION_LABEL])

        val numbers = FirestoreAdminCatalogMapper.extractRegisteredVersionNumbers(
            mapOf(Fields.REGISTERED_VERSION_NUMBERS to listOf(1L, 2L, 0L, "bad", 3))
        )
        assertEquals(setOf(1, 2, 3), numbers)
    }

    @Test
    fun `validateStudyFilePublishTransition rejects missing archived or versionless parent files on publish`() {
        val validSha = "d".repeat(64)
        val validActiveVersion = StudyFileVersionMetadata(
            id = "v1",
            fileId = "active-file",
            versionNumber = 1,
            versionLabel = "1.0",
            r2ObjectKey = "study-files/active-file/versions/v1/document.pdf",
            fileSizeBytes = 4096L,
            checksumSha256 = validSha,
            isPublished = true,
            isArchived = false
        )

        try {
            FirestoreAdminCatalogMapper.validateStudyFilePublishTransition(
                fileId = "missing-file",
                exists = false,
                isArchived = false,
                targetPublished = true
            )
            fail("Expected AdminException NOT_FOUND")
        } catch (ex: AdminException) {
            assertEquals(AdminFailureReason.NOT_FOUND, ex.reason)
        }

        try {
            FirestoreAdminCatalogMapper.validateStudyFilePublishTransition(
                fileId = "archived-file",
                exists = true,
                isArchived = true,
                targetPublished = true,
                activeVersionId = "v1",
                activeVersion = validActiveVersion.copy(fileId = "archived-file")
            )
            fail("Expected AdminException FILE_ARCHIVED_CANNOT_PUBLISH_VERSION")
        } catch (ex: AdminException) {
            assertEquals(AdminFailureReason.VALIDATION_ERROR, ex.reason)
            assertEquals(AdminValidationError.FILE_ARCHIVED_CANNOT_PUBLISH_VERSION, ex.validationError)
        }

        // Parent file with activeVersionId == null must be blocked on publish.
        try {
            FirestoreAdminCatalogMapper.validateStudyFilePublishTransition(
                fileId = "active-file",
                exists = true,
                isArchived = false,
                targetPublished = true,
                activeVersionId = null,
                activeVersion = null
            )
            fail("Expected AdminException STUDY_FILE_NO_ACTIVE_VERSION")
        } catch (ex: AdminException) {
            assertEquals(AdminValidationError.STUDY_FILE_NO_ACTIVE_VERSION, ex.validationError)
        }

        // Parent file whose activeVersionId points to an unpublished version must be blocked.
        try {
            FirestoreAdminCatalogMapper.validateStudyFilePublishTransition(
                fileId = "active-file",
                exists = true,
                isArchived = false,
                targetPublished = true,
                activeVersionId = "v1",
                activeVersion = validActiveVersion.copy(isPublished = false)
            )
            fail("Expected AdminException STUDY_FILE_NO_ACTIVE_VERSION")
        } catch (ex: AdminException) {
            assertEquals(AdminValidationError.STUDY_FILE_NO_ACTIVE_VERSION, ex.validationError)
        }

        // Parent file whose active version lacks mandatory sha256 proof must be blocked.
        try {
            FirestoreAdminCatalogMapper.validateStudyFilePublishTransition(
                fileId = "active-file",
                exists = true,
                isArchived = false,
                targetPublished = true,
                activeVersionId = "v1",
                activeVersionNumber = 1,
                activeVersion = validActiveVersion.copy(checksumSha256 = null)
            )
            fail("Expected AdminException VERSION_PDF_BINARY_MISSING")
        } catch (ex: AdminException) {
            assertEquals(AdminValidationError.VERSION_PDF_BINARY_MISSING, ex.validationError)
        }

        // Parent file with activeVersionNumber == null must be blocked on publish.
        try {
            FirestoreAdminCatalogMapper.validateStudyFilePublishTransition(
                fileId = "active-file",
                exists = true,
                isArchived = false,
                targetPublished = true,
                activeVersionId = "v1",
                activeVersionNumber = null,
                activeVersion = validActiveVersion
            )
            fail("Expected AdminException STUDY_FILE_NO_ACTIVE_VERSION for null activeVersionNumber")
        } catch (ex: AdminException) {
            assertEquals(AdminValidationError.STUDY_FILE_NO_ACTIVE_VERSION, ex.validationError)
        }

        // Parent file whose activeVersionNumber differs from activeVersion.versionNumber must be blocked.
        try {
            FirestoreAdminCatalogMapper.validateStudyFilePublishTransition(
                fileId = "active-file",
                exists = true,
                isArchived = false,
                targetPublished = true,
                activeVersionId = "v1",
                activeVersionNumber = 2,
                activeVersion = validActiveVersion.copy(versionNumber = 1)
            )
            fail("Expected AdminException STUDY_FILE_NO_ACTIVE_VERSION for versionNumber mismatch")
        } catch (ex: AdminException) {
            assertEquals(AdminValidationError.STUDY_FILE_NO_ACTIVE_VERSION, ex.validationError)
        }

        // Unpublishing an archived or versionless file succeeds; publishing with valid active version succeeds.
        FirestoreAdminCatalogMapper.validateStudyFilePublishTransition(
            fileId = "archived-file",
            exists = true,
            isArchived = true,
            targetPublished = false
        )
        FirestoreAdminCatalogMapper.validateStudyFilePublishTransition(
            fileId = "active-file",
            exists = true,
            isArchived = false,
            targetPublished = true,
            activeVersionId = "v1",
            activeVersionNumber = 1,
            activeVersion = validActiveVersion
        )
    }

    @Test
    fun `buildArchiveStateUpdatePayload unpublishes atomically on archive and never auto-publishes on restore`() {
        val archivePayload = FirestoreAdminCatalogMapper.buildArchiveStateUpdatePayload(
            isArchived = true,
            actorUid = "owner-uid",
            timestampValue = "SERVER_TS"
        )
        assertEquals(true, archivePayload[Fields.IS_ARCHIVED])
        assertEquals(false, archivePayload[Fields.IS_PUBLISHED])
        assertEquals("owner-uid", archivePayload[Fields.UPDATED_BY_UID])

        val restorePayload = FirestoreAdminCatalogMapper.buildArchiveStateUpdatePayload(
            isArchived = false,
            actorUid = "owner-uid",
            timestampValue = "SERVER_TS"
        )
        assertEquals(false, restorePayload[Fields.IS_ARCHIVED])
        assertEquals(false, restorePayload[Fields.IS_PUBLISHED])
        assertFalse(restorePayload.containsKey(Fields.ACTIVE_VERSION_ID))
    }

    @Test
    fun `validateStudyFilePublishTransition rejects activeVersionNumber equal to zero`() {
        val activeVersion = StudyFileVersionMetadata(
            id = "v1",
            fileId = "active-file",
            versionNumber = 1,
            versionLabel = "1.0",
            r2ObjectKey = "study-files/active-file/versions/v1/document.pdf",
            fileSizeBytes = 4096L,
            checksumSha256 = "d".repeat(64),
            isPublished = true,
            isArchived = false
        )

        try {
            FirestoreAdminCatalogMapper.validateStudyFilePublishTransition(
                fileId = "active-file",
                exists = true,
                isArchived = false,
                targetPublished = true,
                activeVersionId = "v1",
                activeVersionNumber = 0,
                activeVersion = activeVersion
            )
            fail("Expected AdminException STUDY_FILE_NO_ACTIVE_VERSION for activeVersionNumber = 0")
        } catch (ex: AdminException) {
            assertEquals(AdminFailureReason.VALIDATION_ERROR, ex.reason)
            assertEquals(AdminValidationError.STUDY_FILE_NO_ACTIVE_VERSION, ex.validationError)
        }
    }

    @Test
    fun `validateStudyFilePublishTransition rejects negative activeVersionNumber`() {
        val activeVersion = StudyFileVersionMetadata(
            id = "v1",
            fileId = "active-file",
            versionNumber = 1,
            versionLabel = "1.0",
            r2ObjectKey = "study-files/active-file/versions/v1/document.pdf",
            fileSizeBytes = 4096L,
            checksumSha256 = "d".repeat(64),
            isPublished = true,
            isArchived = false
        )

        try {
            FirestoreAdminCatalogMapper.validateStudyFilePublishTransition(
                fileId = "active-file",
                exists = true,
                isArchived = false,
                targetPublished = true,
                activeVersionId = "v1",
                activeVersionNumber = -1,
                activeVersion = activeVersion
            )
            fail("Expected AdminException STUDY_FILE_NO_ACTIVE_VERSION for negative activeVersionNumber")
        } catch (ex: AdminException) {
            assertEquals(AdminFailureReason.VALIDATION_ERROR, ex.reason)
            assertEquals(AdminValidationError.STUDY_FILE_NO_ACTIVE_VERSION, ex.validationError)
        }
    }
}
