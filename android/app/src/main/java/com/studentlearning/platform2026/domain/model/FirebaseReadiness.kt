package com.studentlearning.platform2026.domain.model

enum class FirebaseConfigurationStatus {
    AWAITING_GOOGLE_SERVICES_FILE,
    CONFIGURED_LOCALLY,
    PROJECT_MISMATCH
}

data class FirebaseProjectTarget(
    val projectId: String = TARGET_PROJECT_ID,
    val projectNumber: String = TARGET_PROJECT_NUMBER,
    val firestoreDatabaseId: String = TARGET_FIRESTORE_DATABASE,
    val firestoreLocation: String = TARGET_FIRESTORE_LOCATION,
    val authEmailPasswordEnabled: Boolean = true
) {
    companion object {
        const val TARGET_PROJECT_ID = "student-learning-platform-2026"
        const val TARGET_PROJECT_NUMBER = "48673314803"
        const val TARGET_FIRESTORE_DATABASE = "(default)"
        const val TARGET_FIRESTORE_LOCATION = "me-central1"
    }
}

data class FirebaseReadiness(
    val status: FirebaseConfigurationStatus,
    val configuredProjectId: String? = null,
    val projectTarget: FirebaseProjectTarget = FirebaseProjectTarget(),
    val expectedConfigPath: String = "android/app/google-services.json"
) {
    val isLocallyConfigured: Boolean
        get() = status == FirebaseConfigurationStatus.CONFIGURED_LOCALLY
}
