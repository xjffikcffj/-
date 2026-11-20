package com.studentlearning.platform2026.data.repository

import com.studentlearning.platform2026.data.remote.firebase.FirebaseConfigurationChecker
import com.studentlearning.platform2026.data.remote.firebase.FirebaseStatusDataSource
import com.studentlearning.platform2026.domain.model.FirebaseConfigurationStatus
import com.studentlearning.platform2026.domain.model.FirebaseProjectTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemStatusRepositoryImplTest {

    @Test
    fun `returns awaiting file status when firebase is not configured`() {
        val checker = object : FirebaseConfigurationChecker {
            override fun isFirebaseConfigured(): Boolean = false
            override fun getConfiguredProjectId(): String? = null
        }
        val repository = SystemStatusRepositoryImpl(FirebaseStatusDataSource(checker))

        val readiness = repository.getFirebaseReadiness()

        assertEquals(FirebaseConfigurationStatus.AWAITING_GOOGLE_SERVICES_FILE, readiness.status)
        assertFalse(readiness.isLocallyConfigured)
        assertNull(readiness.configuredProjectId)
        assertEquals(FirebaseProjectTarget.TARGET_PROJECT_ID, readiness.projectTarget.projectId)
        assertEquals(FirebaseProjectTarget.TARGET_PROJECT_NUMBER, readiness.projectTarget.projectNumber)
        assertEquals(FirebaseProjectTarget.TARGET_FIRESTORE_DATABASE, readiness.projectTarget.firestoreDatabaseId)
        assertEquals(FirebaseProjectTarget.TARGET_FIRESTORE_LOCATION, readiness.projectTarget.firestoreLocation)
    }

    @Test
    fun `returns locally configured status when target project matches`() {
        val checker = object : FirebaseConfigurationChecker {
            override fun isFirebaseConfigured(): Boolean = true
            override fun getConfiguredProjectId(): String = FirebaseProjectTarget.TARGET_PROJECT_ID
        }
        val repository = SystemStatusRepositoryImpl(FirebaseStatusDataSource(checker))

        val readiness = repository.getFirebaseReadiness()

        assertEquals(FirebaseConfigurationStatus.CONFIGURED_LOCALLY, readiness.status)
        assertTrue(readiness.isLocallyConfigured)
        assertEquals(FirebaseProjectTarget.TARGET_PROJECT_ID, readiness.configuredProjectId)
    }

    @Test
    fun `returns project mismatch status when configured project id differs`() {
        val checker = object : FirebaseConfigurationChecker {
            override fun isFirebaseConfigured(): Boolean = false
            override fun getConfiguredProjectId(): String = "some-other-project"
        }
        val repository = SystemStatusRepositoryImpl(FirebaseStatusDataSource(checker))

        val readiness = repository.getFirebaseReadiness()

        assertEquals(FirebaseConfigurationStatus.PROJECT_MISMATCH, readiness.status)
        assertFalse(readiness.isLocallyConfigured)
        assertEquals("some-other-project", readiness.configuredProjectId)
    }
}
