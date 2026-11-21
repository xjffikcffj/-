package com.studentlearning.platform2026.domain.validation

import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminValidationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class AdminCatalogValidatorTest {

    @Test
    fun `validateCatalogEntry trims name and accepts optional valid custom ID`() {
        val result = AdminCatalogValidator.validateCatalogEntry(
            customId = " scientific_branch-1 ",
            name = "  السادس العلمي  ",
            order = 1
        )

        assertEquals("scientific_branch-1", result.customId)
        assertEquals("السادس العلمي", result.name)
        assertEquals(1, result.order)
    }

    @Test
    fun `validateCatalogEntry allows blank customId as null and rejects invalid IDs or negative order`() {
        val autoId = AdminCatalogValidator.validateCatalogEntry(customId = "   ", name = "الفيزياء", order = 0)
        assertNull(autoId.customId)

        try {
            AdminCatalogValidator.validateCatalogEntry(customId = "bad/path", name = "الفيزياء", order = 1)
            fail("Expected invalid document ID")
        } catch (ex: AdminException) {
            assertEquals(AdminFailureReason.VALIDATION_ERROR, ex.reason)
            assertEquals(AdminValidationError.INVALID_DOCUMENT_ID, ex.validationError)
        }

        try {
            AdminCatalogValidator.validateCatalogEntry(customId = null, name = "الفيزياء", order = -1)
            fail("Expected negative order error")
        } catch (ex: AdminException) {
            assertEquals(AdminValidationError.NEGATIVE_ORDER, ex.validationError)
        }
    }

    @Test
    fun `validateStudyFileCreate trims title and normalizes optional fields without version inputs`() {
        val valid = AdminCatalogValidator.validateStudyFileCreate(
            customId = "ch1-summary",
            sectionId = "sec_1",
            title = "  ملخص الفصل الأول  ",
            description = "  ",
            order = 1
        )
        assertEquals("ch1-summary", valid.customId)
        assertEquals("sec_1", valid.sectionId)
        assertEquals("ملخص الفصل الأول", valid.title)
        assertNull(valid.description)
        assertEquals(1, valid.order)
    }

    @Test
    fun `validateStudyFileUpdate validates fileId and sectionId independently`() {
        val valid = AdminCatalogValidator.validateStudyFileUpdate(
            fileId = "file_1",
            sectionId = "sec_1",
            title = "  ملخص محدث  ",
            description = "وصف",
            order = 2
        )
        assertEquals("file_1", valid.fileId)
        assertEquals("sec_1", valid.sectionId)
        assertEquals("ملخص محدث", valid.title)

        // Even when fileId is valid, a blank sectionId must fail with EMPTY_PARENT_REFERENCE.
        try {
            AdminCatalogValidator.validateStudyFileUpdate(
                fileId = "file_1",
                sectionId = "   ",
                title = "ملف",
                description = null,
                order = 1
            )
            fail("Expected empty parent reference for blank sectionId")
        } catch (ex: AdminException) {
            assertEquals(AdminValidationError.EMPTY_PARENT_REFERENCE, ex.validationError)
        }

        try {
            AdminCatalogValidator.validateStudyFileUpdate(
                fileId = "  ",
                sectionId = "sec_1",
                title = "ملف",
                description = null,
                order = 1
            )
            fail("Expected invalid document ID for blank fileId")
        } catch (ex: AdminException) {
            assertEquals(AdminValidationError.INVALID_DOCUMENT_ID, ex.validationError)
        }
    }

    @Test
    fun `validateStudyFileVersionCreate trims fields enforces 64 char label limit and positive versionNumber`() {
        val valid64 = "v".repeat(64)
        val valid = AdminCatalogValidator.validateStudyFileVersionCreate(
            fileId = " file-1 ",
            versionNumber = 2,
            versionLabel = " $valid64 ",
            versionNotes = "   ",
            customVersionId = " ver_2 "
        )
        assertEquals("file-1", valid.fileId)
        assertEquals("ver_2", valid.customVersionId)
        assertEquals(2, valid.versionNumber)
        assertEquals(valid64, valid.versionLabel)
        assertNull(valid.versionNotes)

        try {
            AdminCatalogValidator.validateStudyFileVersionCreate(
                fileId = "file-1",
                versionNumber = 0,
                versionLabel = "1.0",
                versionNotes = null
            )
            fail("Expected invalid version number")
        } catch (ex: AdminException) {
            assertEquals(AdminValidationError.INVALID_VERSION_NUMBER, ex.validationError)
        }

        try {
            AdminCatalogValidator.validateStudyFileVersionCreate(
                fileId = "file-1",
                versionNumber = 1,
                versionLabel = "   ",
                versionNotes = null
            )
            fail("Expected empty version label")
        } catch (ex: AdminException) {
            assertEquals(AdminValidationError.EMPTY_VERSION_LABEL, ex.validationError)
        }

        try {
            AdminCatalogValidator.validateStudyFileVersionCreate(
                fileId = "file-1",
                versionNumber = 1,
                versionLabel = "x".repeat(65),
                versionNotes = null
            )
            fail("Expected version label too long")
        } catch (ex: AdminException) {
            assertEquals(AdminValidationError.VERSION_LABEL_TOO_LONG, ex.validationError)
        }

        try {
            AdminCatalogValidator.validateStudyFileVersionCreate(
                fileId = "file-1",
                versionNumber = 1,
                versionLabel = "1.0",
                versionNotes = "n".repeat(501)
            )
            fail("Expected version notes too long")
        } catch (ex: AdminException) {
            assertEquals(AdminValidationError.VERSION_NOTES_TOO_LONG, ex.validationError)
        }
    }

    @Test
    fun `validateStudyFileVersionUpdate validates fileId versionId and metadata limits`() {
        val updated = AdminCatalogValidator.validateStudyFileVersionUpdate(
            fileId = " file-1 ",
            versionId = " ver-1 ",
            versionLabel = " 2026 المنقحة ",
            versionNotes = " تصحيح الأخطاء المطبعية "
        )
        assertEquals("file-1", updated.fileId)
        assertEquals("ver-1", updated.versionId)
        assertEquals("2026 المنقحة", updated.versionLabel)
        assertEquals("تصحيح الأخطاء المطبعية", updated.versionNotes)

        try {
            AdminCatalogValidator.validateStudyFileVersionUpdate(
                fileId = "file-1",
                versionId = "   ",
                versionLabel = "2.0",
                versionNotes = null
            )
            fail("Expected invalid version document id")
        } catch (ex: AdminException) {
            assertEquals(AdminValidationError.INVALID_DOCUMENT_ID, ex.validationError)
        }
    }

    @Test
    fun `validateStudyFileCreate and Update allow 1000-char description and reject longer`() {
        val exact1000 = "a".repeat(1000)
        val created = AdminCatalogValidator.validateStudyFileCreate(
            customId = "f-1000",
            sectionId = "sec-1",
            title = "ملزمة",
            description = " $exact1000 ",
            order = 1
        )
        assertEquals(exact1000, created.description)

        val updated = AdminCatalogValidator.validateStudyFileUpdate(
            fileId = "f-1000",
            sectionId = "sec-1",
            title = "ملزمة",
            description = exact1000,
            order = 1
        )
        assertEquals(exact1000, updated.description)

        val tooLong = "b".repeat(1001)
        try {
            AdminCatalogValidator.validateStudyFileCreate(
                customId = null,
                sectionId = "sec-1",
                title = "ملزمة",
                description = tooLong,
                order = 1
            )
            fail("Expected DESCRIPTION_TOO_LONG on create")
        } catch (ex: AdminException) {
            assertEquals(AdminValidationError.DESCRIPTION_TOO_LONG, ex.validationError)
        }

        try {
            AdminCatalogValidator.validateStudyFileUpdate(
                fileId = "f-1000",
                sectionId = "sec-1",
                title = "ملزمة",
                description = tooLong,
                order = 1
            )
            fail("Expected DESCRIPTION_TOO_LONG on update")
        } catch (ex: AdminException) {
            assertEquals(AdminValidationError.DESCRIPTION_TOO_LONG, ex.validationError)
        }
    }
}
