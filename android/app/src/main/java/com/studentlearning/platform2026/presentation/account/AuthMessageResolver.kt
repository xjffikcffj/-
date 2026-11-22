package com.studentlearning.platform2026.presentation.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.studentlearning.platform2026.R
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminValidationError

@Composable
fun resolveAuthErrorText(
    reason: AdminFailureReason,
    validationError: AdminValidationError?
): String {
    if (reason == AdminFailureReason.VALIDATION_ERROR && validationError != null) {
        val validationRes = when (validationError) {
            AdminValidationError.EMPTY_EMAIL -> R.string.admin_validation_empty_email
            AdminValidationError.INVALID_EMAIL -> R.string.admin_validation_invalid_email
            AdminValidationError.EMPTY_PASSWORD -> R.string.admin_validation_empty_password
            AdminValidationError.PASSWORD_TOO_SHORT -> R.string.auth_validation_password_too_short
            AdminValidationError.PASSWORD_NEEDS_LETTER_AND_DIGIT -> R.string.auth_validation_password_weak
            AdminValidationError.EMPTY_CONFIRM_PASSWORD -> R.string.auth_validation_empty_confirm_password
            AdminValidationError.PASSWORDS_DO_NOT_MATCH -> R.string.auth_validation_passwords_mismatch
            AdminValidationError.EMPTY_NAME -> R.string.admin_validation_empty_name
            AdminValidationError.NAME_TOO_LONG -> R.string.admin_validation_name_too_long
            AdminValidationError.DESCRIPTION_TOO_LONG -> R.string.admin_validation_description_too_long
            AdminValidationError.NEGATIVE_ORDER -> R.string.admin_validation_negative_order
            AdminValidationError.INVALID_DOCUMENT_ID -> R.string.admin_validation_invalid_document_id
            AdminValidationError.EMPTY_PARENT_REFERENCE -> R.string.admin_validation_empty_parent
            AdminValidationError.INVALID_VERSION_NUMBER -> R.string.admin_validation_invalid_version_number
            AdminValidationError.EMPTY_VERSION_LABEL -> R.string.admin_validation_empty_version_label
            AdminValidationError.VERSION_LABEL_TOO_LONG -> R.string.admin_validation_version_label_too_long
            AdminValidationError.VERSION_NOTES_TOO_LONG -> R.string.admin_validation_version_notes_too_long
            AdminValidationError.FILE_ARCHIVED_CANNOT_PUBLISH_VERSION -> R.string.admin_validation_file_archived_publish
            AdminValidationError.VERSION_ARCHIVED_CANNOT_PUBLISH -> R.string.admin_validation_version_archived_publish
            AdminValidationError.ARCHIVE_STATE_UNSPECIFIED -> R.string.admin_validation_archive_state_unspecified
            AdminValidationError.VERSION_PDF_BINARY_MISSING -> R.string.admin_validation_version_pdf_missing
            AdminValidationError.VERSION_UPLOAD_INVALID_PDF -> R.string.admin_validation_upload_invalid_pdf
            AdminValidationError.VERSION_UPLOAD_SIZE_MISMATCH -> R.string.admin_validation_upload_size_mismatch
            AdminValidationError.VERSION_UPLOAD_SHA256_MISMATCH -> R.string.admin_validation_upload_sha256_mismatch
            AdminValidationError.VERSION_UPLOAD_SESSION_EXPIRED -> R.string.admin_validation_upload_session_expired
            AdminValidationError.VERSION_UPLOAD_SESSION_CONFLICT -> R.string.admin_validation_upload_session_conflict
            AdminValidationError.VERSION_UPLOAD_ETAG_MISMATCH -> R.string.admin_validation_upload_etag_mismatch
            AdminValidationError.VERSION_UPLOAD_R2_REJECTED -> R.string.admin_validation_upload_r2_rejected
            AdminValidationError.VERSION_ALREADY_PUBLISHED_IMMUTABLE -> R.string.admin_validation_version_already_published
            AdminValidationError.VERSION_ALREADY_VERIFIED_IMMUTABLE -> R.string.admin_validation_version_already_verified
            AdminValidationError.R2_STORAGE_NOT_CONFIGURED -> R.string.admin_validation_r2_not_configured
            AdminValidationError.BACKEND_REQUEST_REJECTED -> R.string.admin_validation_backend_request_rejected
            AdminValidationError.STUDY_FILE_NO_ACTIVE_VERSION -> R.string.admin_validation_study_file_no_active_version
        }
        return stringResource(validationRes)
    }

    val errorRes = when (reason) {
        AdminFailureReason.FIREBASE_NOT_CONFIGURED -> R.string.admin_error_not_configured
        AdminFailureReason.BACKEND_NOT_CONFIGURED -> R.string.admin_error_backend_not_configured
        AdminFailureReason.UNAUTHENTICATED -> R.string.admin_error_unauthenticated
        AdminFailureReason.INSUFFICIENT_ROLE -> R.string.admin_error_insufficient_role
        AdminFailureReason.SESSION_VERIFICATION_REQUIRED -> R.string.admin_error_session_verification_required
        AdminFailureReason.PERMISSION_DENIED -> R.string.admin_error_permission_denied
        AdminFailureReason.INVALID_CREDENTIALS -> R.string.admin_error_invalid_credentials
        AdminFailureReason.EMAIL_ALREADY_IN_USE -> R.string.auth_error_email_already_in_use
        AdminFailureReason.WEAK_PASSWORD -> R.string.auth_error_weak_password
        AdminFailureReason.ACCOUNT_DISABLED -> R.string.auth_error_account_disabled
        AdminFailureReason.ACCOUNT_NOT_FOUND -> R.string.auth_error_account_not_found
        AdminFailureReason.TOO_MANY_REQUESTS -> R.string.auth_error_too_many_requests
        AdminFailureReason.NETWORK_UNAVAILABLE -> R.string.admin_error_network
        AdminFailureReason.NOT_FOUND -> R.string.admin_error_not_found
        AdminFailureReason.VALIDATION_ERROR,
        AdminFailureReason.UNKNOWN -> R.string.admin_error_unknown
    }
    return stringResource(errorRes)
}
