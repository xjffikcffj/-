package com.studentlearning.platform2026.domain.model

enum class StudyNotificationType {
    NEW_FILE,
    NEW_VERSION,
    IMPORTANT_UPDATE;

    companion object {
        fun fromWireValue(raw: String?): StudyNotificationType {
            return when (raw?.trim()?.uppercase()) {
                "NEW_FILE" -> NEW_FILE
                "NEW_VERSION" -> NEW_VERSION
                else -> IMPORTANT_UPDATE
            }
        }
    }
}

data class LocalNotificationItem(
    val id: String,
    val type: StudyNotificationType,
    val title: String,
    val body: String,
    val fileId: String? = null,
    val versionId: String? = null,
    val versionLabel: String? = null,
    val receivedAtEpochMillis: Long,
    val isRead: Boolean = false
)
