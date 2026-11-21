package com.studentlearning.platform2026.data.remote.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.studentlearning.platform2026.data.remote.firebase.FirestoreCatalogSchema.Collections
import com.studentlearning.platform2026.data.remote.firebase.FirestoreCatalogSchema.Fields
import com.studentlearning.platform2026.data.remote.firebase.FirestoreCatalogSchema.Subcollections
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminValidationError
import com.studentlearning.platform2026.domain.model.ArchiveStatus
import com.studentlearning.platform2026.domain.repository.VerifiedPdfBinaryProof
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreAdminCatalogDataSourceTest {

    private val nullFirestoreProvider = object : FirestoreProvider {
        override fun getFirestoreOrNull(): FirebaseFirestore? = null
    }

    @Test
    fun `setStudyFileArchived rejects missing doc and UNSPECIFIED archive states inside transaction with 0 writes`() =
        runTest {
            val client = FakeAdminCatalogFirestoreClient().apply {
                putDoc(
                    path = "${Collections.STUDY_FILES}/file-missing-field",
                    data = mapOf(
                        Fields.TITLE to "ملف قديم بلا حقل",
                        Fields.IS_PUBLISHED to false
                    )
                )
                putDoc(
                    path = "${Collections.STUDY_FILES}/file-null-field",
                    data = mapOf(
                        Fields.TITLE to "ملف بقيمة فارغة",
                        Fields.IS_PUBLISHED to false,
                        Fields.IS_ARCHIVED to null
                    )
                )
                putDoc(
                    path = "${Collections.STUDY_FILES}/file-string-field",
                    data = mapOf(
                        Fields.TITLE to "ملف بقيمة نصية",
                        Fields.IS_PUBLISHED to false,
                        Fields.IS_ARCHIVED to "false"
                    )
                )
            }
            val dataSource = FirestoreAdminCatalogDataSource(nullFirestoreProvider, client)

            val missingEx = assertAdminThrows {
                dataSource.setStudyFileArchived("file-nonexistent", true, "admin-1")
            }
            assertEquals(AdminFailureReason.NOT_FOUND, missingEx.reason)

            for (docId in listOf("file-missing-field", "file-null-field", "file-string-field")) {
                for (targetArchived in listOf(true, false)) {
                    val ex = assertAdminThrows {
                        dataSource.setStudyFileArchived(docId, targetArchived, "admin-1")
                    }
                    assertEquals(AdminFailureReason.VALIDATION_ERROR, ex.reason)
                    assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, ex.validationError)
                }
            }

            assertEquals(0, client.setCalls.size)
            assertEquals(0, client.updateCalls.size)
        }

    @Test
    fun `setStudyFileArchived allows explicit boolean toggle and forces isPublished false atomically`() =
        runTest {
            val client = FakeAdminCatalogFirestoreClient().apply {
                putDoc(
                    path = "${Collections.STUDY_FILES}/file-live",
                    data = mapOf(
                        Fields.TITLE to "ملزمة الفيزياء",
                        Fields.IS_PUBLISHED to true,
                        Fields.IS_ARCHIVED to false
                    )
                )
            }
            val dataSource = FirestoreAdminCatalogDataSource(nullFirestoreProvider, client)

            dataSource.setStudyFileArchived("file-live", isArchived = true, actorUid = "admin-1")
            assertEquals(1, client.updateCalls.size)
            assertEquals(true, client.docs["${Collections.STUDY_FILES}/file-live"]?.get(Fields.IS_ARCHIVED))
            assertEquals(false, client.docs["${Collections.STUDY_FILES}/file-live"]?.get(Fields.IS_PUBLISHED))

            dataSource.setStudyFileArchived("file-live", isArchived = false, actorUid = "admin-1")
            assertEquals(2, client.updateCalls.size)
            assertEquals(false, client.docs["${Collections.STUDY_FILES}/file-live"]?.get(Fields.IS_ARCHIVED))
            assertEquals(false, client.docs["${Collections.STUDY_FILES}/file-live"]?.get(Fields.IS_PUBLISHED))
        }

    @Test
    fun `setStudyFilePublished enforces fail-closed archive guards on parent and active version while allowing unpublish`() =
        runTest {
            val filePath = "${Collections.STUDY_FILES}/file-1"
            val versionPath = "$filePath/${Subcollections.VERSIONS}/v1"
            val client = FakeAdminCatalogFirestoreClient()
            val dataSource = FirestoreAdminCatalogDataSource(nullFirestoreProvider, client)

            // 1. Parent UNSPECIFIED -> publish rejected with 0 writes.
            client.putDoc(
                path = filePath,
                data = mapOf(
                    Fields.IS_PUBLISHED to false,
                    Fields.IS_ARCHIVED to "false",
                    Fields.ACTIVE_VERSION_ID to "v1",
                    Fields.ACTIVE_VERSION_NUMBER to 1
                )
            )
            client.putDoc(
                path = versionPath,
                data = verifiedVersionData(filePath, isArchived = false)
            )
            val parentUnspecEx = assertAdminThrows {
                dataSource.setStudyFilePublished("file-1", isPublished = true, actorUid = "admin-1")
            }
            assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, parentUnspecEx.validationError)
            assertEquals(0, client.updateCalls.size)

            // 2. Parent explicit UNARCHIVED, active version UNSPECIFIED -> publish rejected with 0 writes.
            client.putDoc(
                path = filePath,
                data = mapOf(
                    Fields.IS_PUBLISHED to false,
                    Fields.IS_ARCHIVED to false,
                    Fields.ACTIVE_VERSION_ID to "v1",
                    Fields.ACTIVE_VERSION_NUMBER to 1
                )
            )
            client.putDoc(
                path = versionPath,
                data = verifiedVersionData(filePath, isArchived = null)
            )
            val versionUnspecEx = assertAdminThrows {
                dataSource.setStudyFilePublished("file-1", isPublished = true, actorUid = "admin-1")
            }
            assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, versionUnspecEx.validationError)
            assertEquals(0, client.updateCalls.size)

            // 3. Already-published UNSPECIFIED parent can be unpublished cleanly.
            client.putDoc(
                path = filePath,
                data = mapOf(
                    Fields.IS_PUBLISHED to true,
                    Fields.ACTIVE_VERSION_ID to "v1",
                    Fields.ACTIVE_VERSION_NUMBER to 1
                )
            )
            dataSource.setStudyFilePublished("file-1", isPublished = false, actorUid = "admin-1")
            assertEquals(1, client.updateCalls.size)
            assertEquals(false, client.docs[filePath]?.get(Fields.IS_PUBLISHED))

            // 4. Both parent & active verified version explicitly UNARCHIVED -> publish succeeds.
            client.putDoc(
                path = filePath,
                data = mapOf(
                    Fields.IS_PUBLISHED to false,
                    Fields.IS_ARCHIVED to false,
                    Fields.ACTIVE_VERSION_ID to "v1",
                    Fields.ACTIVE_VERSION_NUMBER to 1
                )
            )
            client.putDoc(
                path = versionPath,
                data = verifiedVersionData(filePath, isArchived = false)
            )
            dataSource.setStudyFilePublished("file-1", isPublished = true, actorUid = "admin-1")
            assertEquals(2, client.updateCalls.size)
            assertEquals(true, client.docs[filePath]?.get(Fields.IS_PUBLISHED))
        }

    @Test
    fun `createStudyFileVersion rejects UNSPECIFIED and ARCHIVED parents and writes UNARCHIVED draft on valid parent`() =
        runTest {
            val filePath = "${Collections.STUDY_FILES}/file-v"
            val client = FakeAdminCatalogFirestoreClient()
            val dataSource = FirestoreAdminCatalogDataSource(nullFirestoreProvider, client)

            // Missing parent -> NOT_FOUND, 0 writes.
            val missingEx = assertAdminThrows {
                dataSource.createStudyFileVersion("file-v", 1, "1.0", null, false, "admin-1", "v1")
            }
            assertEquals(AdminFailureReason.NOT_FOUND, missingEx.reason)
            assertEquals(0, client.setCalls.size)

            // UNSPECIFIED parent -> ARCHIVE_STATE_UNSPECIFIED, 0 writes.
            client.putDoc(
                path = filePath,
                data = mapOf(Fields.IS_PUBLISHED to false)
            )
            val unspecEx = assertAdminThrows {
                dataSource.createStudyFileVersion("file-v", 1, "1.0", null, false, "admin-1", "v1")
            }
            assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, unspecEx.validationError)
            assertEquals(0, client.setCalls.size)

            // ARCHIVED parent -> FILE_ARCHIVED_CANNOT_PUBLISH_VERSION, 0 writes.
            client.putDoc(
                path = filePath,
                data = mapOf(Fields.IS_PUBLISHED to false, Fields.IS_ARCHIVED to true)
            )
            val archivedEx = assertAdminThrows {
                dataSource.createStudyFileVersion("file-v", 1, "1.0", null, false, "admin-1", "v1")
            }
            assertEquals(AdminValidationError.FILE_ARCHIVED_CANNOT_PUBLISH_VERSION, archivedEx.validationError)
            assertEquals(0, client.setCalls.size)

            // UNARCHIVED parent -> creates version draft with isArchived = false & registers version number.
            client.putDoc(
                path = filePath,
                data = mapOf(Fields.IS_PUBLISHED to false, Fields.IS_ARCHIVED to false)
            )
            val created = dataSource.createStudyFileVersion(
                fileId = "file-v",
                versionNumber = 1,
                versionLabel = "1.0",
                versionNotes = "أول إصدار",
                publishAsActive = false,
                actorUid = "admin-1",
                customVersionId = "v1"
            )
            assertEquals(ArchiveStatus.UNARCHIVED, created.archiveStatus)
            assertFalse(created.isPublished)
            assertEquals(1, client.setCalls.size)
            assertEquals(false, client.docs["$filePath/${Subcollections.VERSIONS}/v1"]?.get(Fields.IS_ARCHIVED))
            assertTrue(
                ((client.docs[filePath]?.get(Fields.REGISTERED_VERSION_NUMBERS) as? List<*>) ?: emptyList<Any>())
                    .contains(1)
            )

            // Duplicate versionNumber & publishAsActive = true must reject with 0 additional writes.
            val dupNumEx = assertAdminThrows {
                dataSource.createStudyFileVersion("file-v", 1, "1.1", null, false, "admin-1", "v2")
            }
            assertEquals(AdminValidationError.INVALID_VERSION_NUMBER, dupNumEx.validationError)
            val activeEx = assertAdminThrows {
                dataSource.createStudyFileVersion("file-v", 2, "2.0", null, true, "admin-1", "v2")
            }
            assertEquals(AdminValidationError.VERSION_PDF_BINARY_MISSING, activeEx.validationError)
            assertEquals(1, client.setCalls.size)
            assertEquals(1, client.updateCalls.size)
        }

    @Test
    fun `CancellationException inside transaction propagates without being wrapped`() = runTest {
        val client = FakeAdminCatalogFirestoreClient().apply {
            throwCancellation = true
        }
        val dataSource = FirestoreAdminCatalogDataSource(nullFirestoreProvider, client)

        var caughtCancellation = false
        try {
            dataSource.setStudyFileArchived("file-1", isArchived = true, actorUid = "admin-1")
        } catch (_: CancellationException) {
            caughtCancellation = true
        }
        assertTrue(caughtCancellation)
    }

    @Test
    fun `publishStudyFileVersion enforces parent and target archive guards proof matching and atomic version switch`() =
        runTest {
            val fileId = "file-pub"
            val filePath = "${Collections.STUDY_FILES}/$fileId"
            val versionsPath = "$filePath/${Subcollections.VERSIONS}"
            val sha1 = "1".repeat(64)
            val sha2 = "2".repeat(64)
            val proofV1 = VerifiedPdfBinaryProof(
                fileId = fileId,
                versionId = "v1",
                r2ObjectKey = "study-files/$fileId/versions/v1/doc.pdf",
                fileSizeBytes = 4096L,
                checksumSha256 = sha1
            )
            val proofV2 = VerifiedPdfBinaryProof(
                fileId = fileId,
                versionId = "v2",
                r2ObjectKey = "study-files/$fileId/versions/v2/doc.pdf",
                fileSizeBytes = 8192L,
                checksumSha256 = sha2
            )

            // 1. Missing parent file -> NOT_FOUND, 0 writes.
            run {
                val client = FakeAdminCatalogFirestoreClient()
                val ds = FirestoreAdminCatalogDataSource(nullFirestoreProvider, client)
                val ex = assertAdminThrows {
                    ds.publishStudyFileVersion(fileId, "v1", "admin-1", proofV1)
                }
                assertEquals(AdminFailureReason.NOT_FOUND, ex.reason)
                assertEquals(0, client.updateCalls.size)
            }

            // 2. Parent UNSPECIFIED (missing, null, string) -> ARCHIVE_STATE_UNSPECIFIED, 0 writes.
            for (badArchive in listOf(null, "false")) {
                val client = FakeAdminCatalogFirestoreClient().apply {
                    putDoc(
                        path = filePath,
                        data = buildMap {
                            put(Fields.IS_PUBLISHED, false)
                            if (badArchive != null) put(Fields.IS_ARCHIVED, badArchive)
                        }
                    )
                    putDoc("$versionsPath/v1", verifiedVersionData(filePath, isArchived = false, checksum = sha1))
                }
                val ds = FirestoreAdminCatalogDataSource(nullFirestoreProvider, client)
                val ex = assertAdminThrows {
                    ds.publishStudyFileVersion(fileId, "v1", "admin-1", proofV1)
                }
                assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, ex.validationError)
                assertEquals(0, client.updateCalls.size)
            }

            // 3. Parent ARCHIVED -> FILE_ARCHIVED_CANNOT_PUBLISH_VERSION, 0 writes.
            run {
                val client = FakeAdminCatalogFirestoreClient().apply {
                    putDoc(filePath, mapOf(Fields.IS_PUBLISHED to false, Fields.IS_ARCHIVED to true))
                    putDoc("$versionsPath/v1", verifiedVersionData(filePath, isArchived = false, checksum = sha1))
                }
                val ds = FirestoreAdminCatalogDataSource(nullFirestoreProvider, client)
                val ex = assertAdminThrows {
                    ds.publishStudyFileVersion(fileId, "v1", "admin-1", proofV1)
                }
                assertEquals(AdminValidationError.FILE_ARCHIVED_CANNOT_PUBLISH_VERSION, ex.validationError)
                assertEquals(0, client.updateCalls.size)
            }

            // 4. Target version missing -> NOT_FOUND, 0 writes.
            run {
                val client = FakeAdminCatalogFirestoreClient().apply {
                    putDoc(filePath, mapOf(Fields.IS_PUBLISHED to false, Fields.IS_ARCHIVED to false))
                }
                val ds = FirestoreAdminCatalogDataSource(nullFirestoreProvider, client)
                val ex = assertAdminThrows {
                    ds.publishStudyFileVersion(fileId, "v1", "admin-1", proofV1)
                }
                assertEquals(AdminFailureReason.NOT_FOUND, ex.reason)
                assertEquals(0, client.updateCalls.size)
            }

            // 4b. Target version linked to another parent file -> EMPTY_PARENT_REFERENCE, 0 writes.
            run {
                val otherFilePath = "${Collections.STUDY_FILES}/other-file"
                val client = FakeAdminCatalogFirestoreClient().apply {
                    putDoc(filePath, mapOf(Fields.IS_PUBLISHED to false, Fields.IS_ARCHIVED to false))
                    putDoc("$versionsPath/v1", verifiedVersionData(otherFilePath, isArchived = false, checksum = sha1))
                }
                val ds = FirestoreAdminCatalogDataSource(nullFirestoreProvider, client)
                val ex = assertAdminThrows {
                    ds.publishStudyFileVersion(fileId, "v1", "admin-1", proofV1)
                }
                assertEquals(AdminValidationError.EMPTY_PARENT_REFERENCE, ex.validationError)
                assertEquals(0, client.updateCalls.size)
            }

            // 5. Target version UNSPECIFIED -> ARCHIVE_STATE_UNSPECIFIED, 0 writes.
            run {
                val client = FakeAdminCatalogFirestoreClient().apply {
                    putDoc(filePath, mapOf(Fields.IS_PUBLISHED to false, Fields.IS_ARCHIVED to false))
                    putDoc("$versionsPath/v1", verifiedVersionData(filePath, isArchived = null, checksum = sha1))
                }
                val ds = FirestoreAdminCatalogDataSource(nullFirestoreProvider, client)
                val ex = assertAdminThrows {
                    ds.publishStudyFileVersion(fileId, "v1", "admin-1", proofV1)
                }
                assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, ex.validationError)
                assertEquals(0, client.updateCalls.size)
            }

            // 6. Target version ARCHIVED -> VERSION_ARCHIVED_CANNOT_PUBLISH, 0 writes.
            run {
                val client = FakeAdminCatalogFirestoreClient().apply {
                    putDoc(filePath, mapOf(Fields.IS_PUBLISHED to false, Fields.IS_ARCHIVED to false))
                    putDoc("$versionsPath/v1", verifiedVersionData(filePath, isArchived = true, checksum = sha1))
                }
                val ds = FirestoreAdminCatalogDataSource(nullFirestoreProvider, client)
                val ex = assertAdminThrows {
                    ds.publishStudyFileVersion(fileId, "v1", "admin-1", proofV1)
                }
                assertEquals(AdminValidationError.VERSION_ARCHIVED_CANNOT_PUBLISH, ex.validationError)
                assertEquals(0, client.updateCalls.size)
            }

            // 7. Missing/mismatched R2 proof (size or sha256 mismatch) -> VERSION_PDF_BINARY_MISSING, 0 writes.
            run {
                val client = FakeAdminCatalogFirestoreClient().apply {
                    putDoc(filePath, mapOf(Fields.IS_PUBLISHED to false, Fields.IS_ARCHIVED to false))
                    putDoc("$versionsPath/v1", verifiedVersionData(filePath, isArchived = false, checksum = sha1))
                }
                val ds = FirestoreAdminCatalogDataSource(nullFirestoreProvider, client)
                val sizeMismatchEx = assertAdminThrows {
                    ds.publishStudyFileVersion(fileId, "v1", "admin-1", proofV1.copy(fileSizeBytes = 9999L))
                }
                assertEquals(AdminValidationError.VERSION_PDF_BINARY_MISSING, sizeMismatchEx.validationError)

                val shaMismatchEx = assertAdminThrows {
                    ds.publishStudyFileVersion(fileId, "v1", "admin-1", proofV1.copy(checksumSha256 = "f".repeat(64)))
                }
                assertEquals(AdminValidationError.VERSION_PDF_BINARY_MISSING, shaMismatchEx.validationError)
                assertEquals(0, client.updateCalls.size)
            }

            // 8. Parent activeVersionId pointing to deleted doc does not fail; valid v2 publishes cleanly.
            run {
                val client = FakeAdminCatalogFirestoreClient().apply {
                    putDoc(
                        filePath,
                        mapOf(
                            Fields.IS_PUBLISHED to false,
                            Fields.IS_ARCHIVED to false,
                            Fields.ACTIVE_VERSION_ID to "v_deleted",
                            Fields.ACTIVE_VERSION_NUMBER to 9
                        )
                    )
                    putDoc(
                        "$versionsPath/v2",
                        verifiedVersionData(
                            filePath = filePath,
                            isArchived = false,
                            isPublished = false,
                            versionId = "v2",
                            versionNumber = 2,
                            versionLabel = "2.0",
                            fileSizeBytes = 8192L,
                            checksum = sha2
                        )
                    )
                }
                val ds = FirestoreAdminCatalogDataSource(nullFirestoreProvider, client)
                ds.publishStudyFileVersion(fileId, "v2", "admin-1", proofV2)
                assertEquals(true, client.docs["$versionsPath/v2"]?.get(Fields.IS_PUBLISHED))
                assertEquals("v2", client.docs[filePath]?.get(Fields.ACTIVE_VERSION_ID))
                assertEquals(2, client.docs[filePath]?.get(Fields.ACTIVE_VERSION_NUMBER))
            }

            // 9. Switching active published version unpublishes v1, publishes v2, updates parent pointers, preserves all version docs & keys.
            run {
                val client = FakeAdminCatalogFirestoreClient().apply {
                    putDoc(
                        filePath,
                        mapOf(
                            Fields.IS_PUBLISHED to true,
                            Fields.IS_ARCHIVED to false,
                            Fields.ACTIVE_VERSION_ID to "v1",
                            Fields.ACTIVE_VERSION_NUMBER to 1,
                            Fields.ACTIVE_VERSION_LABEL to "1.0"
                        )
                    )
                    putDoc(
                        "$versionsPath/v1",
                        verifiedVersionData(
                            filePath = filePath,
                            isArchived = false,
                            isPublished = true,
                            versionId = "v1",
                            versionNumber = 1,
                            versionLabel = "1.0",
                            fileSizeBytes = 4096L,
                            checksum = sha1
                        )
                    )
                    putDoc(
                        "$versionsPath/v2",
                        verifiedVersionData(
                            filePath = filePath,
                            isArchived = false,
                            isPublished = false,
                            versionId = "v2",
                            versionNumber = 2,
                            versionLabel = "2.0",
                            fileSizeBytes = 8192L,
                            checksum = sha2
                        )
                    )
                }
                val ds = FirestoreAdminCatalogDataSource(nullFirestoreProvider, client)
                ds.publishStudyFileVersion(fileId, "v2", "admin-2", proofV2)

                assertEquals(false, client.docs["$versionsPath/v1"]?.get(Fields.IS_PUBLISHED))
                assertEquals(true, client.docs["$versionsPath/v2"]?.get(Fields.IS_PUBLISHED))
                assertEquals("study-files/$fileId/versions/v1/doc.pdf", client.docs["$versionsPath/v1"]?.get(Fields.R2_OBJECT_KEY))
                assertEquals("study-files/$fileId/versions/v2/doc.pdf", client.docs["$versionsPath/v2"]?.get(Fields.R2_OBJECT_KEY))
                assertEquals("v2", client.docs[filePath]?.get(Fields.ACTIVE_VERSION_ID))
                assertEquals(2, client.docs[filePath]?.get(Fields.ACTIVE_VERSION_NUMBER))
                assertEquals("2.0", client.docs[filePath]?.get(Fields.ACTIVE_VERSION_LABEL))
                assertEquals(true, client.docs[filePath]?.get(Fields.IS_PUBLISHED))
            }
        }

    @Test
    fun `updateStudyFileMetadata verifies doc existence and section reference while preserving versions and archive state`() =
        runTest {
            val sectionRef = FirestoreReferenceTarget(Collections.SECTIONS, "sec-1", "${Collections.SECTIONS}/sec-1")
            val wrongSectionRef = FirestoreReferenceTarget(Collections.SECTIONS, "sec-other", "${Collections.SECTIONS}/sec-other")
            val filePath = "${Collections.STUDY_FILES}/file-meta"
            val client = FakeAdminCatalogFirestoreClient()
            val dataSource = FirestoreAdminCatalogDataSource(nullFirestoreProvider, client)

            // 1. Missing doc -> NOT_FOUND, 0 writes.
            val missingEx = assertAdminThrows {
                dataSource.updateStudyFileMetadata("file-meta", "sec-1", "عنوان", null, 1, "admin-1")
            }
            assertEquals(AdminFailureReason.NOT_FOUND, missingEx.reason)
            assertEquals(0, client.updateCalls.size)

            // 2. Wrong sectionId reference -> EMPTY_PARENT_REFERENCE, 0 writes.
            client.putDoc(
                filePath,
                mapOf(
                    Fields.SECTION_ID to wrongSectionRef,
                    Fields.TITLE to "قديم",
                    Fields.ORDER to 1
                )
            )
            val wrongSectionEx = assertAdminThrows {
                dataSource.updateStudyFileMetadata("file-meta", "sec-1", "عنوان", null, 2, "admin-1")
            }
            assertEquals(AdminValidationError.EMPTY_PARENT_REFERENCE, wrongSectionEx.validationError)
            assertEquals(0, client.updateCalls.size)

            // 3. Valid section reference updates metadata only and preserves activeVersionId, isArchived, isPublished.
            client.putDoc(
                filePath,
                mapOf(
                    Fields.SECTION_ID to sectionRef,
                    Fields.TITLE to "قديم",
                    Fields.DESCRIPTION to "وصف قديم",
                    Fields.ORDER to 1,
                    Fields.IS_PUBLISHED to true,
                    Fields.IS_ARCHIVED to false,
                    Fields.ACTIVE_VERSION_ID to "v1",
                    Fields.ACTIVE_VERSION_NUMBER to 1,
                    Fields.ACTIVE_VERSION_LABEL to "1.0"
                )
            )
            dataSource.updateStudyFileMetadata(
                fileId = "file-meta",
                sectionId = "sec-1",
                title = "عنوان محدث",
                description = "وصف جديد",
                order = 5,
                actorUid = "admin-2"
            )
            assertEquals(1, client.updateCalls.size)
            val updated = client.docs.getValue(filePath)
            assertEquals("عنوان محدث", updated[Fields.TITLE])
            assertEquals("وصف جديد", updated[Fields.DESCRIPTION])
            assertEquals(5, updated[Fields.ORDER])
            assertEquals("admin-2", updated[Fields.UPDATED_BY_UID])
            assertEquals("v1", updated[Fields.ACTIVE_VERSION_ID])
            assertEquals(1, updated[Fields.ACTIVE_VERSION_NUMBER])
            assertEquals(true, updated[Fields.IS_PUBLISHED])
            assertEquals(false, updated[Fields.IS_ARCHIVED])
        }

    private fun verifiedVersionData(
        filePath: String,
        isArchived: Any?,
        isPublished: Boolean = true,
        versionId: String = "v1",
        versionNumber: Int = 1,
        versionLabel: String = "1.0",
        fileSizeBytes: Long = 4096L,
        checksum: String = "a".repeat(64)
    ): Map<String, Any?> = buildMap {
        val fileId = filePath.substringAfterLast('/')
        put(Fields.FILE_ID, FirestoreReferenceTarget(Collections.STUDY_FILES, fileId, filePath))
        put(Fields.VERSION_NUMBER, versionNumber)
        put(Fields.VERSION_LABEL, versionLabel)
        put(Fields.R2_OBJECT_KEY, "study-files/$fileId/versions/$versionId/doc.pdf")
        put(Fields.FILE_SIZE_BYTES, fileSizeBytes)
        put(Fields.CHECKSUM_SHA256, checksum)
        put(Fields.IS_PUBLISHED, isPublished)
        if (isArchived != null) {
            put(Fields.IS_ARCHIVED, isArchived)
        }
    }

    private suspend fun assertAdminThrows(block: suspend () -> Unit): AdminException {
        var caught: Throwable? = null
        try {
            block()
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue("Expected AdminException but got ${caught?.javaClass?.simpleName}", caught is AdminException)
        return caught as AdminException
    }

    private class FakeAdminCatalogFirestoreClient : AdminCatalogFirestoreClient {
        val docs = linkedMapOf<String, MutableMap<String, Any?>>()
        val setCalls = mutableListOf<Pair<String, Map<String, Any?>>>()
        val updateCalls = mutableListOf<Pair<String, Map<String, Any?>>>()
        var throwCancellation: Boolean = false
        private var nextAutoId = 1

        fun putDoc(path: String, data: Map<String, Any?>) {
            docs[path] = data.toMutableMap()
        }

        override fun serverTimestamp(): Any = "SERVER_TIMESTAMP"

        override fun documentRef(docPath: String): Any {
            val normalized = docPath.trim('/')
            val parts = normalized.split('/')
            val docId = parts.lastOrNull().orEmpty()
            val collectionId = if (parts.size >= 2) parts[parts.size - 2] else ""
            return FirestoreReferenceTarget(
                collectionId = collectionId,
                documentId = docId,
                path = normalized
            )
        }

        override fun newDocumentId(collectionPath: String): String = "auto_${nextAutoId++}"

        override suspend fun listCollectionDocPaths(collectionPath: String): List<String> {
            val prefix = "$collectionPath/"
            return docs.keys.filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
        }

        override suspend fun <T> runTransaction(block: (AdminCatalogTransactionScope) -> T): T {
            if (throwCancellation) {
                throw CancellationException("cancelled inside test transaction")
            }
            val stagedDocs = docs.mapValues { (_, v) -> v.toMutableMap() }.toMutableMap()
            val stagedSets = mutableListOf<Pair<String, Map<String, Any?>>>()
            val stagedUpdates = mutableListOf<Pair<String, Map<String, Any?>>>()
            val scope = object : AdminCatalogTransactionScope {
                override fun get(docPath: String): CatalogDocSnapshot {
                    val data = stagedDocs[docPath]
                    return CatalogDocSnapshot(
                        id = docPath.substringAfterLast('/'),
                        exists = data != null,
                        data = data?.toMap() ?: emptyMap(),
                        path = docPath
                    )
                }

                override fun set(docPath: String, payload: Map<String, Any?>) {
                    stagedSets.add(docPath to payload)
                    stagedDocs[docPath] = payload.toMutableMap()
                }

                override fun update(docPath: String, payload: Map<String, Any?>) {
                    stagedUpdates.add(docPath to payload)
                    val target = stagedDocs.getOrPut(docPath) { mutableMapOf() }
                    target.putAll(payload)
                }
            }
            val result = block(scope)
            docs.clear()
            docs.putAll(stagedDocs)
            setCalls.addAll(stagedSets)
            updateCalls.addAll(stagedUpdates)
            return result
        }
    }
}
