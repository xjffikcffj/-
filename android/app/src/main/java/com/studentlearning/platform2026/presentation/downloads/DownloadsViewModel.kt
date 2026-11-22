package com.studentlearning.platform2026.presentation.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.studentlearning.platform2026.domain.model.AppUserRole
import com.studentlearning.platform2026.domain.model.LocalFileOrderingHelper
import com.studentlearning.platform2026.domain.repository.AuthRepository
import com.studentlearning.platform2026.domain.repository.LocalFileOrganizationRepository
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
    private val offlineDownloadsRepository: OfflineDownloadsRepository? = null,
    private val localFileOrganizationRepository: LocalFileOrganizationRepository? = null
) : ViewModel() {

    private val roleFlow = authRepository?.sessionFlow
        ?.map { it.userRole }
        ?: flowOf(AppUserRole.GUEST)

    private val savedVersionsFlow = offlineDownloadsRepository?.observeAllSavedVersions()
        ?.map { versions -> versions.sortedByDescending { it.downloadedAtEpochMillis } }
        ?: flowOf(emptyList())

    private val favoritesFlow = localFileOrganizationRepository?.observeFavorites()
        ?: flowOf(emptyList())

    private val savedCustomOrderFlow = localFileOrganizationRepository?.observeCustomOrder(
        LocalFileOrganizationRepository.SCOPE_DOWNLOADS
    ) ?: flowOf(emptyList())

    private val isReorderModeFlow = MutableStateFlow(false)
    private val draftOrderedKeysFlow = MutableStateFlow<List<String>>(emptyList())

    private val reorderControlFlow = combine(
        savedCustomOrderFlow,
        isReorderModeFlow,
        draftOrderedKeysFlow
    ) { savedOrder, isReorderMode, draftKeys ->
        Triple(savedOrder, isReorderMode, draftKeys)
    }

    val uiState: StateFlow<DownloadsUiState> = if (
        authRepository == null &&
        offlineDownloadsRepository == null &&
        localFileOrganizationRepository == null
    ) {
        MutableStateFlow(DownloadsUiState(userRole = AppUserRole.GUEST)).asStateFlow()
    } else {
        combine(
            roleFlow,
            savedVersionsFlow,
            favoritesFlow,
            reorderControlFlow
        ) { role, savedVersions, favorites, reorderControl ->
            val (savedOrder, isReorderMode, draftKeys) = reorderControl
            val activeKeys = if (isReorderMode) draftKeys else savedOrder
            val orderedVersions = LocalFileOrderingHelper.applyLocalFileOrder(
                items = savedVersions,
                idSelector = { savedVersionOrderKey(it.fileId, it.versionId) },
                orderedIds = activeKeys
            )
            DownloadsUiState(
                isInitialized = true,
                hasSavedFiles = orderedVersions.isNotEmpty(),
                savedVersions = orderedVersions,
                favoriteFiles = favorites,
                isReorderMode = isReorderMode,
                hasCustomOrder = savedOrder.isNotEmpty(),
                userRole = role
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = DownloadsUiState(
                isInitialized = true,
                hasSavedFiles = false,
                savedVersions = emptyList(),
                favoriteFiles = emptyList(),
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

    fun removeFavorite(fileId: String) {
        val orgRepo = localFileOrganizationRepository ?: return
        viewModelScope.launch {
            orgRepo.removeFavorite(fileId = fileId)
        }
    }

    fun enterReorderMode() {
        val versions = uiState.value.savedVersions
        if (versions.size <= 1 || localFileOrganizationRepository == null) return
        draftOrderedKeysFlow.value = versions.map { savedVersionOrderKey(it.fileId, it.versionId) }
        isReorderModeFlow.value = true
    }

    fun moveSavedVersionInDraftOrder(fileId: String, versionId: String, moveUp: Boolean) {
        if (!isReorderModeFlow.value) return
        val key = savedVersionOrderKey(fileId, versionId)
        draftOrderedKeysFlow.value = LocalFileOrderingHelper.moveItemInOrderedIds(
            orderedIds = draftOrderedKeysFlow.value,
            targetId = key,
            moveUp = moveUp
        )
    }

    fun saveReorder() {
        val orgRepo = localFileOrganizationRepository ?: return
        if (!isReorderModeFlow.value) return
        val orderedKeys = draftOrderedKeysFlow.value
        viewModelScope.launch {
            orgRepo.saveCustomOrder(
                scopeKey = LocalFileOrganizationRepository.SCOPE_DOWNLOADS,
                orderedFileIds = orderedKeys
            )
            isReorderModeFlow.value = false
            draftOrderedKeysFlow.value = emptyList()
        }
    }

    fun cancelReorder() {
        isReorderModeFlow.value = false
        draftOrderedKeysFlow.value = emptyList()
    }

    fun resetCustomOrder() {
        val orgRepo = localFileOrganizationRepository ?: return
        viewModelScope.launch {
            orgRepo.clearCustomOrder(LocalFileOrganizationRepository.SCOPE_DOWNLOADS)
            isReorderModeFlow.value = false
            draftOrderedKeysFlow.value = emptyList()
        }
    }

    companion object {
        fun savedVersionOrderKey(fileId: String, versionId: String): String =
            "${fileId.trim()}::${versionId.trim()}"

        fun provideFactory(
            authRepository: AuthRepository? = null,
            offlineDownloadsRepository: OfflineDownloadsRepository? = null,
            localFileOrganizationRepository: LocalFileOrganizationRepository? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DownloadsViewModel(
                    authRepository = authRepository,
                    offlineDownloadsRepository = offlineDownloadsRepository,
                    localFileOrganizationRepository = localFileOrganizationRepository
                ) as T
            }
        }
    }
}
