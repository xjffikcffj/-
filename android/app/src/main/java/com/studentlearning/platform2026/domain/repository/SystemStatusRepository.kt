package com.studentlearning.platform2026.domain.repository

import com.studentlearning.platform2026.domain.model.FirebaseReadiness

interface SystemStatusRepository {
    fun getFirebaseReadiness(): FirebaseReadiness
}
