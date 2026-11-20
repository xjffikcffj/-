package com.studentlearning.platform2026.domain.validation

import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminValidationError

data class ValidatedCatalogEntry(
    val customId: String?,
    val name: String,
    val order: Int
)

data class ValidatedStudyFileCreateInput(
    val customId: String?,
    val sectionId: String,
    val title: String,
    val description: String?,
    val order: Int
)

data class ValidatedStudyFileUpdateInput(
    val fileId: String,
    val sectionId: String,
    val title: String,
    val description: String?,
    val order: Int
)

object AdminCatalogValidator {

    private const val MAX_NAME_LENGTH = 120
    private val DOCUMENT_ID_REGEX = Regex("^[a-zA-Z0-9_-]{2,64}$")

    fun validateSignIn(email: String, password: String): Pair<String, String> =
        AuthValidator.validateSignIn(email, password)

    fun validateCatalogEntry(
        customId: String? = null,
        name: String,
        order: Int
    ): ValidatedCatalogEntry {
        val normalizedId = normalizeAndValidateCustomId(customId)
        val normalizedName = normalizeAndValidateName(name)
        if (order < 0) {
            throw validationException(AdminValidationError.NEGATIVE_ORDER)
        }
        return ValidatedCatalogEntry(
            customId = normalizedId,
            name = normalizedName,
            order = order
        )
    }

    fun validateParentId(parentId: String): String {
        val trimmed = parentId.trim()
        if (trimmed.isEmpty()) {
            throw validationException(AdminValidationError.EMPTY_PARENT_REFERENCE)
        }
        return trimmed
    }

    fun validateRequiredDocumentId(documentId: String): String {
        val trimmed = documentId.trim()
        if (trimmed.isEmpty()) {
            throw validationException(AdminValidationError.INVALID_DOCUMENT_ID)
        }
        return trimmed
    }

    fun validateNonNegativeOrder(order: Int): Int {
        if (order < 0) {
            throw validationException(AdminValidationError.NEGATIVE_ORDER)
        }
        return order
    }

    fun validateStudyFileCreate(
        customId: String? = null,
        sectionId: String,
        title: String,
        description: String?,
        order: Int
    ): ValidatedStudyFileCreateInput {
        val normalizedSectionId = validateParentId(sectionId)
        val normalizedCustomId = normalizeAndValidateCustomId(customId)
        val normalizedTitle = normalizeAndValidateName(title)
        validateNonNegativeOrder(order)

        return ValidatedStudyFileCreateInput(
            customId = normalizedCustomId,
            sectionId = normalizedSectionId,
            title = normalizedTitle,
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            order = order
        )
    }

    fun validateStudyFileUpdate(
        fileId: String,
        sectionId: String,
        title: String,
        description: String?,
        order: Int
    ): ValidatedStudyFileUpdateInput {
        val normalizedFileId = validateRequiredDocumentId(fileId)
        val normalizedSectionId = validateParentId(sectionId)
        val normalizedTitle = normalizeAndValidateName(title)
        validateNonNegativeOrder(order)

        return ValidatedStudyFileUpdateInput(
            fileId = normalizedFileId,
            sectionId = normalizedSectionId,
            title = normalizedTitle,
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            order = order
        )
    }

    private fun normalizeAndValidateName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            throw validationException(AdminValidationError.EMPTY_NAME)
        }
        if (trimmed.length > MAX_NAME_LENGTH) {
            throw validationException(AdminValidationError.NAME_TOO_LONG)
        }
        return trimmed
    }

    private fun normalizeAndValidateCustomId(customId: String?): String? {
        val trimmed = customId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!DOCUMENT_ID_REGEX.matches(trimmed)) {
            throw validationException(AdminValidationError.INVALID_DOCUMENT_ID)
        }
        return trimmed
    }

    private fun validationException(error: AdminValidationError): AdminException {
        return AdminException(
            reason = AdminFailureReason.VALIDATION_ERROR,
            validationError = error
        )
    }
}
