package com.studentlearning.platform2026.domain.validation

import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminValidationError
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AuthValidatorTest {

    @Test
    fun `validateStudentSignUp accepts trimmed valid email and matching strong password`() {
        val (email, password) = AuthValidator.validateStudentSignUp(
            email = "  student@example.com  ",
            password = "Study2026Password",
            confirmPassword = "Study2026Password"
        )
        assertEquals("student@example.com", email)
        assertEquals("Study2026Password", password)
    }

    @Test
    fun `validateStudentSignUp rejects invalid email`() {
        try {
            AuthValidator.validateStudentSignUp("not-an-email", "Study2026", "Study2026")
            fail("Expected invalid email error")
        } catch (ex: AdminException) {
            assertEquals(AdminFailureReason.VALIDATION_ERROR, ex.reason)
            assertEquals(AdminValidationError.INVALID_EMAIL, ex.validationError)
        }
    }

    @Test
    fun `validateStudentSignUp rejects short password, weak password, and mismatched confirmation`() {
        try {
            AuthValidator.validateStudentSignUp("student@example.com", "Abc123", "Abc123")
            fail("Expected PASSWORD_TOO_SHORT")
        } catch (ex: AdminException) {
            assertEquals(AdminValidationError.PASSWORD_TOO_SHORT, ex.validationError)
        }

        try {
            AuthValidator.validateStudentSignUp("student@example.com", "OnlyLettersPass", "OnlyLettersPass")
            fail("Expected PASSWORD_NEEDS_LETTER_AND_DIGIT")
        } catch (ex: AdminException) {
            assertEquals(AdminValidationError.PASSWORD_NEEDS_LETTER_AND_DIGIT, ex.validationError)
        }

        try {
            AuthValidator.validateStudentSignUp("student@example.com", "Study2026", "Study2027")
            fail("Expected PASSWORDS_DO_NOT_MATCH")
        } catch (ex: AdminException) {
            assertEquals(AdminValidationError.PASSWORDS_DO_NOT_MATCH, ex.validationError)
        }
    }

    @Test
    fun `validatePasswordReset validates email format`() {
        assertEquals("user@example.com", AuthValidator.validatePasswordReset("  user@example.com "))

        try {
            AuthValidator.validatePasswordReset(" ")
            fail("Expected EMPTY_EMAIL")
        } catch (ex: AdminException) {
            assertEquals(AdminValidationError.EMPTY_EMAIL, ex.validationError)
        }
    }
}
