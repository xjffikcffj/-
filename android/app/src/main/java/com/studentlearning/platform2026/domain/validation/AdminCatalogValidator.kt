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

data class ValidatedStudyFileVersionCreateInput(
    val customVersionId: String?,
    val fileId: String,
    val versionNumber: Int,
    val versionLabel: String,
    val versionNotes: String?
)

data class ValidatedStudyFileVersionUpdateInput(
    val fileId: String,
    val versionId: String,
    val versionLabel: String,
    val versionNotes: String?
)

object AdminCatalogValidator {

    private const val MAX_NAME_LENGTH = 120
    private const val MAX_VERSION_LABEL_LENGTH = 64
    private const val MAX_VERSION_NOTES_LENGTH = 500
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

    fun validateStudyFileVersionCreate(
        fileId: String,
        versionNumber: Int,
        versionLabel: String,
        versionNotes: String?,
        customVersionId: String? = null
    ): ValidatedStudyFileVersionCreateInput {
        val normalizedFileId = validateRequiredDocumentId(fileId)
        val normalizedCustomId = normalizeAndValidateCustomId(customVersionId)
        if (versionNumber < 1) {
            throw validationException(AdminValidationError.INVALID_VERSION_NUMBER)
        }
        val normalizedLabel = normalizeAndValidateVersionLabel(versionLabel)
        val normalizedNotes = normalizeAndValidateVersionNotes(versionNotes)

        return ValidatedStudyFileVersionCreateInput(
            customVersionId = normalizedCustomId,
            fileId = normalizedFileId,
            versionNumber = versionNumber,
            versionLabel = normalizedLabel,
            versionNotes = normalizedNotes
        )
    }

    fun validateStudyFileVersionUpdate(
        fileId: String,
        versionId: String,
        versionLabel: String,
        versionNotes: String?
    ): ValidatedStudyFileVersionUpdateInput {
        val normalizedFileId = validateRequiredDocumentId(fileId)
        val normalizedVersionId = validateRequiredDocumentId(versionId)
        val normalizedLabel = normalizeAndValidateVersionLabel(versionLabel)
        val normalizedNotes = normalizeAndValidateVersionNotes(versionNotes)

        return ValidatedStudyFileVersionUpdateInput(
            fileId = normalizedFileId,
            versionId = normalizedVersionId,
            versionLabel = normalizedLabel,
            versionNotes = normalizedNotes
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

    private fun normalizeAndValidateVersionLabel(label: String): String {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) {
            throw validationException(AdminValidationError.EMPTY_VERSION_LABEL)
        }
        if (trimmed.length > MAX_VERSION_LABEL_LENGTH) {
            throw validationException(AdminValidationError.VERSION_LABEL_TOO_LONG)
        }
        return trimmed
    }

    private fun normalizeAndValidateVersionNotes(notes: String?): String? {
        val trimmed = notes?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.length > MAX_VERSION_NOTES_LENGTH) {
            throw validationException(AdminValidationError.VERSION_NOTES_TOO_LONG)
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
