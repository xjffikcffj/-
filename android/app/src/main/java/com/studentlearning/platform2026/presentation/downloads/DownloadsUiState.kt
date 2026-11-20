package com.studentlearning.platform2026.presentation.downloads

import com.studentlearning.platform2026.domain.model.AppUserRole
import com.studentlearning.platform2026.domain.model.LocalDownloadedFileVersion

data class DownloadsUiState(
    val isInitialized: Boolean = true,
    val hasSavedFiles: Boolean = false,
    val savedVersions: List<LocalDownloadedFileVersion> = emptyList(),
    val userRole: AppUserRole = AppUserRole.GUEST
) {
    val canDownloadOfflineFiles: Boolean
        get() = userRole.canDownloadOfflineFiles
}
