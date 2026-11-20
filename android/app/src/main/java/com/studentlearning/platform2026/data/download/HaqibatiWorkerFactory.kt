package com.studentlearning.platform2026.data.download

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.studentlearning.platform2026.core.di.AppContainer

class HaqibatiWorkerFactory(
    private val appContainer: AppContainer
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (workerClassName) {
            PdfDownloadWorker::class.java.name -> PdfDownloadWorker(
                appContext = appContext,
                workerParams = workerParameters,
                authRepository = appContainer.authRepository,
                executionEngine = appContainer.downloadExecutionEngine
            )
            else -> null
        }
    }
}
