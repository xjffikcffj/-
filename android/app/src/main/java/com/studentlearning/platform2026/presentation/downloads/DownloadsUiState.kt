package com.studentlearning.platform2026.presentation.downloads

import com.studentlearning.platform2026.domain.model.AppUserRole

data class DownloadsUiState(
    val isInitialized: Boolean = true,
    val hasSavedFiles: Boolean = false,
    val userRole: AppUserRole = AppUserRole.GUEST
) {
    val canDownloadOfflineFiles: Boolean
        get() = userRole.canDownloadOfflineFiles
}
