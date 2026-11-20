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
}
