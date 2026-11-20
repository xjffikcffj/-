package com.studentlearning.platform2026.domain.validation

import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminValidationError

object AuthValidator {

    const val MIN_PASSWORD_LENGTH = 8
    private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    fun validateSignIn(email: String, password: String): Pair<String, String> {
        val normalizedEmail = validateEmail(email)
        if (password.isBlank()) {
            throw validationException(AdminValidationError.EMPTY_PASSWORD)
        }
        return normalizedEmail to password
    }

    fun validateStudentSignUp(
        email: String,
        password: String,
        confirmPassword: String
    ): Pair<String, String> {
        val normalizedEmail = validateEmail(email)
        if (password.isBlank()) {
            throw validationException(AdminValidationError.EMPTY_PASSWORD)
        }
        if (password.length < MIN_PASSWORD_LENGTH) {
            throw validationException(AdminValidationError.PASSWORD_TOO_SHORT)
        }
        val hasLetter = password.any { it.isLetter() }
        val hasDigit = password.any { it.isDigit() }
        if (!hasLetter || !hasDigit) {
            throw validationException(AdminValidationError.PASSWORD_NEEDS_LETTER_AND_DIGIT)
        }
        if (confirmPassword.isBlank()) {
            throw validationException(AdminValidationError.EMPTY_CONFIRM_PASSWORD)
        }
        if (password != confirmPassword) {
            throw validationException(AdminValidationError.PASSWORDS_DO_NOT_MATCH)
        }
        return normalizedEmail to password
    }

    fun validatePasswordReset(email: String): String {
        return validateEmail(email)
    }

    private fun validateEmail(email: String): String {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isEmpty()) {
            throw validationException(AdminValidationError.EMPTY_EMAIL)
        }
        if (!EMAIL_REGEX.matches(trimmedEmail)) {
            throw validationException(AdminValidationError.INVALID_EMAIL)
        }
        return trimmedEmail
    }

    private fun validationException(error: AdminValidationError): AdminException {
        return AdminException(
            reason = AdminFailureReason.VALIDATION_ERROR,
            validationError = error
        )
    }
}
