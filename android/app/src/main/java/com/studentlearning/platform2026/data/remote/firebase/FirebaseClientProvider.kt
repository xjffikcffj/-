package com.studentlearning.platform2026.data.remote.firebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.studentlearning.platform2026.domain.model.FirebaseProjectTarget

interface FirebaseConfigurationChecker {
    fun isFirebaseConfigured(): Boolean
    fun getConfiguredProjectId(): String?
}

interface FirestoreProvider {
    fun getFirestoreOrNull(): FirebaseFirestore?
}

interface FirebaseAuthProvider {
    fun getAuthOrNull(): FirebaseAuth?
}

/**
 * Provides safe access to Firebase SDK instances exclusively for the existing Firebase project:
 * - Project ID: student-learning-platform-2026
 * - Project Number: 48673314803
 * - Firestore Database: (default)
 * - Firestore Location: me-central1
 *
 * Requires the official google-services.json file to be placed at:
 * android/app/google-services.json
 */
class FirebaseClientProvider(
    private val context: Context
) : FirebaseConfigurationChecker, FirestoreProvider, FirebaseAuthProvider {

    override fun isFirebaseConfigured(): Boolean {
        return getConfiguredProjectId() == FirebaseProjectTarget.TARGET_PROJECT_ID
    }

    override fun getConfiguredProjectId(): String? {
        return FirebaseApp.getApps(context)
            .firstOrNull()
            ?.options
            ?.projectId
    }

    fun getFirebaseAppOrNull(): FirebaseApp? {
        return if (isFirebaseConfigured()) {
            FirebaseApp.getInstance()
        } else {
            null
        }
    }

    override fun getAuthOrNull(): FirebaseAuth? {
        val app = getFirebaseAppOrNull() ?: return null
        return FirebaseAuth.getInstance(app)
    }

    override fun getFirestoreOrNull(): FirebaseFirestore? {
        val app = getFirebaseAppOrNull() ?: return null
        return FirebaseFirestore.getInstance(app, FirebaseProjectTarget.TARGET_FIRESTORE_DATABASE)
    }
}
