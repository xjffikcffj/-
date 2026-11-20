package com.studentlearning.platform2026.data.repository

import com.studentlearning.platform2026.data.remote.firebase.FirebaseStatusDataSource
import com.studentlearning.platform2026.domain.model.FirebaseReadiness
import com.studentlearning.platform2026.domain.repository.SystemStatusRepository

class SystemStatusRepositoryImpl(
    private val firebaseStatusDataSource: FirebaseStatusDataSource
) : SystemStatusRepository {

    override fun getFirebaseReadiness(): FirebaseReadiness {
        return firebaseStatusDataSource.inspectReadiness()
    }
}
