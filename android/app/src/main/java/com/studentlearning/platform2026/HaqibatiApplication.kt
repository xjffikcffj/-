package com.studentlearning.platform2026

import android.app.Application
import androidx.work.Configuration
import com.studentlearning.platform2026.core.di.AppContainer
import com.studentlearning.platform2026.data.download.HaqibatiWorkerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HaqibatiApplication : Application(), Configuration.Provider {

    val appContainer: AppContainer by lazy {
        AppContainer(this)
    }

    private val applicationScope by lazy {
        CoroutineScope(SupervisorJob() + appContainer.dispatcherProvider.io)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(HaqibatiWorkerFactory(appContainer))
            .build()

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            try {
                appContainer.offlineDownloadsRepository.cleanupOrphanedTempFiles()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Best-effort startup cleanup; never crash app startup.
            }
        }
    }
}
