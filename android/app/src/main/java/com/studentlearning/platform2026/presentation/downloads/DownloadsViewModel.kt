package com.studentlearning.platform2026.presentation.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.studentlearning.platform2026.domain.model.AppUserRole
import com.studentlearning.platform2026.domain.repository.AuthRepository
import com.studentlearning.platform2026.domain.repository.OfflineDownloadsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(
    authRepository: AuthRepository? = null,
    private val offlineDownloadsRepository: OfflineDownloadsRepository? = null
) : ViewModel() {

    private val roleFlow = authRepository?.sessionFlow
        ?.map { it.userRole }
        ?: flowOf(AppUserRole.GUEST)

    private val savedVersionsFlow = offlineDownloadsRepository?.observeAllSavedVersions()
        ?.map { versions -> versions.sortedByDescending { it.downloadedAtEpochMillis } }
        ?: flowOf(emptyList())

    val uiState: StateFlow<DownloadsUiState> = if (authRepository == null && offlineDownloadsRepository == null) {
        MutableStateFlow(DownloadsUiState(userRole = AppUserRole.GUEST)).asStateFlow()
    } else {
        combine(
            roleFlow,
            savedVersionsFlow
        ) { role, savedVersions ->
            DownloadsUiState(
                isInitialized = true,
                hasSavedFiles = savedVersions.isNotEmpty(),
                savedVersions = savedVersions,
                userRole = role
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = DownloadsUiState(
                isInitialized = true,
                hasSavedFiles = false,
                savedVersions = emptyList(),
                userRole = authRepository?.sessionFlow?.value?.userRole ?: AppUserRole.GUEST
            )
        )
    }

    fun deleteLocalVersion(fileId: String, versionId: String) {
        val repository = offlineDownloadsRepository ?: return
        viewModelScope.launch {
            repository.deleteLocalVersion(fileId = fileId, versionId = versionId)
        }
    }

    companion object {
        fun provideFactory(
            authRepository: AuthRepository? = null,
            offlineDownloadsRepository: OfflineDownloadsRepository? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DownloadsViewModel(
                    authRepository = authRepository,
                    offlineDownloadsRepository = offlineDownloadsRepository
                ) as T
            }
        }
    }
}
