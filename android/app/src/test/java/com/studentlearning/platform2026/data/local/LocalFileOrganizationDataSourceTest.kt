package com.studentlearning.platform2026.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.studentlearning.platform2026.domain.model.ArchiveStatus
import com.studentlearning.platform2026.domain.model.LocalFileOrderingHelper
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.repository.LocalFileOrganizationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LocalFileOrganizationDataSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `favorites persist locally across store recreation without account or UID binding`() = runTest {
        val storeFile = File(tempFolder.newFolder("prefs_fav"), "local_org.preferences_pb")
        val file1 = StudyFileMetadata(
            id = "file-1",
            sectionId = "sec-1",
            title = "ملخص الفصل الأول",
            description = "ملف مهم",
            order = 1,
            isPublished = true,
            archiveStatus = ArchiveStatus.UNARCHIVED,
            activeVersionId = "v1",
            activeVersionLabel = "1.0"
        )
        val file2 = StudyFileMetadata(
            id = "file-2",
            sectionId = "sec-2",
            title = "مراجعة نهائية",
            order = 2,
            isPublished = true,
            archiveStatus = ArchiveStatus.UNARCHIVED
        )

        val scope1 = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val dataStore1 = PreferenceDataStoreFactory.create(scope = scope1, produceFile = { storeFile })
        val ds1 = LocalFileOrganizationDataSource(dataStore1) { 1_700_000_000_000L }

        ds1.addFavorite(file1)
        ds1.addFavorite(file2)
        assertEquals(setOf("file-1", "file-2"), ds1.observeFavoriteIds().first())

        ds1.removeFavorite("file-1")
        assertEquals(setOf("file-2"), ds1.observeFavoriteIds().first())
        scope1.cancel()

        // Re-open DataStore from disk (simulating app restart & device-only persistence)
        val scope2 = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val dataStore2 = PreferenceDataStoreFactory.create(scope = scope2, produceFile = { storeFile })
        val ds2 = LocalFileOrganizationDataSource(dataStore2)

        val persistedFavorites = ds2.observeFavorites().first()
        assertEquals(1, persistedFavorites.size)
        assertEquals("file-2", persistedFavorites.first().fileId)
        assertEquals("مراجعة نهائية", persistedFavorites.first().title)

        // Ensure raw stored preferences never embed user UID or email keys.
        val rawPrefsText = dataStore2.data.first().asMap().entries.joinToString("\n")
        assertFalse(rawPrefsText.contains("uid"))
        assertFalse(rawPrefsText.contains("email"))
        scope2.cancel()
    }

    @Test
    fun `custom file ordering persists per scope across restarts without mutating public order`() = runTest {
        val storeFile = File(tempFolder.newFolder("prefs_order"), "local_order.preferences_pb")
        val scopeKey = LocalFileOrganizationRepository.sectionScope("sec-math")
        val publicCatalog = listOf(
            StudyFileMetadata(id = "downloaded-1", sectionId = "sec-math", title = "A", order = 1, isPublished = true),
            StudyFileMetadata(id = "online-2", sectionId = "sec-math", title = "B", order = 2, isPublished = true),
            StudyFileMetadata(id = "downloaded-3", sectionId = "sec-math", title = "C", order = 3, isPublished = true)
        )

        val scope1 = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val ds1 = LocalFileOrganizationDataSource(
            PreferenceDataStoreFactory.create(scope = scope1, produceFile = { storeFile })
        )
        ds1.saveCustomOrder(scopeKey, listOf("online-2", "downloaded-3", "downloaded-1"))
        scope1.cancel()

        val scope2 = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val ds2 = LocalFileOrganizationDataSource(
            PreferenceDataStoreFactory.create(scope = scope2, produceFile = { storeFile })
        )
        val savedOrder = ds2.observeCustomOrder(scopeKey).first()
        assertEquals(listOf("online-2", "downloaded-3", "downloaded-1"), savedOrder)

        val personallyOrdered = LocalFileOrderingHelper.applyLocalFileOrder(
            items = publicCatalog,
            idSelector = { it.id },
            orderedIds = savedOrder
        )
        assertEquals(listOf("online-2", "downloaded-3", "downloaded-1"), personallyOrdered.map { it.id })
        // Public catalog objects retain their server order values untouched.
        assertEquals(listOf(1, 2, 3), publicCatalog.map { it.order })

        ds2.clearCustomOrder(scopeKey)
        assertTrue(ds2.observeCustomOrder(scopeKey).first().isEmpty())
        scope2.cancel()
    }
}
