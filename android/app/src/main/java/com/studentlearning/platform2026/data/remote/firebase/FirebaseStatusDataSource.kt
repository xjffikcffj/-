package com.studentlearning.platform2026.data.remote.firebase

import com.studentlearning.platform2026.domain.model.FirebaseConfigurationStatus
import com.studentlearning.platform2026.domain.model.FirebaseProjectTarget
import com.studentlearning.platform2026.domain.model.FirebaseReadiness

class FirebaseStatusDataSource(
    private val configurationChecker: FirebaseConfigurationChecker
) {
    fun inspectReadiness(): FirebaseReadiness {
        val target = FirebaseProjectTarget()
        val configuredProjectId = configurationChecker.getConfiguredProjectId()

        val status = when {
            configuredProjectId == null -> FirebaseConfigurationStatus.AWAITING_GOOGLE_SERVICES_FILE
            configuredProjectId == target.projectId && configurationChecker.isFirebaseConfigured() ->
                FirebaseConfigurationStatus.CONFIGURED_LOCALLY
            else -> FirebaseConfigurationStatus.PROJECT_MISMATCH
        }

        return FirebaseReadiness(
            status = status,
            configuredProjectId = configuredProjectId,
            projectTarget = target
        )
    }
}
