package com.studentlearning.platform2026.domain.model

enum class AdminValidationError {
    EMPTY_EMAIL,
    INVALID_EMAIL,
    EMPTY_PASSWORD,
    PASSWORD_TOO_SHORT,
    PASSWORD_NEEDS_LETTER_AND_DIGIT,
    EMPTY_CONFIRM_PASSWORD,
    PASSWORDS_DO_NOT_MATCH,
    EMPTY_NAME,
    NAME_TOO_LONG,
    NEGATIVE_ORDER,
    INVALID_DOCUMENT_ID,
    EMPTY_PARENT_REFERENCE,
    INVALID_VERSION_NUMBER,
    EMPTY_VERSION_LABEL,
    VERSION_LABEL_TOO_LONG,
    VERSION_NOTES_TOO_LONG
}

enum class AdminFailureReason {
    FIREBASE_NOT_CONFIGURED,
    UNAUTHENTICATED,
    INSUFFICIENT_ROLE,
    SESSION_VERIFICATION_REQUIRED,
    PERMISSION_DENIED,
    INVALID_CREDENTIALS,
    EMAIL_ALREADY_IN_USE,
    WEAK_PASSWORD,
    ACCOUNT_DISABLED,
    ACCOUNT_NOT_FOUND,
    TOO_MANY_REQUESTS,
    NETWORK_UNAVAILABLE,
    VALIDATION_ERROR,
    NOT_FOUND,
    UNKNOWN
}

class AdminException(
    val reason: AdminFailureReason,
    val validationError: AdminValidationError? = null,
    override val message: String? = null,
    override val cause: Throwable? = null
) : Exception(message, cause)
