package com.studentlearning.platform2026.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppResultTest {

    @Test
    fun `onSuccess invokes callback only for Success`() {
        var capturedValue: String? = null
        var errorInvoked = false

        AppResult.Success("حقيبتي")
            .onSuccess { capturedValue = it }
            .onError { errorInvoked = true }

        assertEquals("حقيبتي", capturedValue)
        assertFalse(errorInvoked)
    }

    @Test
    fun `onError invokes callback only for Error`() {
        var successInvoked = false
        var capturedMessage: String? = null

        AppResult.Error("حدث خطأ")
            .onSuccess { successInvoked = true }
            .onError { capturedMessage = it.message }

        assertFalse(successInvoked)
        assertEquals("حدث خطأ", capturedMessage)
    }

    @Test
    fun `Loading invokes neither success nor error`() {
        var callbackInvoked = false

        AppResult.Loading
            .onSuccess { callbackInvoked = true }
            .onError { callbackInvoked = true }

        assertTrue(!callbackInvoked)
    }
}
