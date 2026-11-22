package com.studentlearning.platform2026.core.di

import android.content.Context
import com.studentlearning.platform2026.BuildConfig
import com.studentlearning.platform2026.core.common.DefaultDispatcherProvider
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.download.DownloadExecutionEngine
import com.studentlearning.platform2026.data.download.DownloadStreamFetcher
import com.studentlearning.platform2026.data.download.DownloadTaskScheduler
import com.studentlearning.platform2026.data.download.HttpDownloadStreamFetcher
import com.studentlearning.platform2026.data.download.LocalPdfStorageManager
import com.studentlearning.platform2026.data.download.WorkManagerDownloadScheduler
import com.studentlearning.platform2026.data.local.LocalFileOrganizationDataSource
import com.studentlearning.platform2026.data.local.LocalNotificationDataSource
import com.studentlearning.platform2026.data.local.OfflineDownloadsDataSource
import com.studentlearning.platform2026.data.local.PreferencesDataSource
import com.studentlearning.platform2026.data.local.ReadingProgressDataSource
import com.studentlearning.platform2026.data.pdf.AndroidPdfDocumentSessionFactory
import com.studentlearning.platform2026.data.pdf.BrokeredPdfFileSourceDataSource
import com.studentlearning.platform2026.data.pdf.BrokeredPdfVersionStorageDataSource
import com.studentlearning.platform2026.data.pdf.HttpPdfContentFetcher
import com.studentlearning.platform2026.data.pdf.PdfContentFetcher
import com.studentlearning.platform2026.data.pdf.PdfDocumentSessionFactory
import com.studentlearning.platform2026.data.pdf.PdfFileSourceDataSource
import com.studentlearning.platform2026.data.remote.backend.BackendAdminVersionDataSource
import com.studentlearning.platform2026.data.remote.backend.BackendCatalogDataSource
import com.studentlearning.platform2026.data.remote.backend.BackendHttpClient
import com.studentlearning.platform2026.data.remote.backend.HybridAdminCatalogDataSource
import com.studentlearning.platform2026.data.remote.firebase.AdminCatalogRemoteDataSource
import com.studentlearning.platform2026.data.remote.firebase.AuthRemoteDataSource
import com.studentlearning.platform2026.data.remote.firebase.AuthTokenProvider
import com.studentlearning.platform2026.data.remote.firebase.CatalogRemoteDataSource
import com.studentlearning.platform2026.data.remote.firebase.FirebaseAuthDataSource
import com.studentlearning.platform2026.data.remote.firebase.FirebaseClientProvider
import com.studentlearning.platform2026.data.remote.firebase.FirebaseStatusDataSource
import com.studentlearning.platform2026.data.remote.firebase.FirestoreAdminCatalogDataSource
import com.studentlearning.platform2026.data.repository.AdminCatalogRepositoryImpl
import com.studentlearning.platform2026.data.repository.AdminGovernanceRepositoryImpl
import com.studentlearning.platform2026.data.repository.AuthRepositoryImpl
import com.studentlearning.platform2026.data.repository.CatalogRepositoryImpl
import com.studentlearning.platform2026.data.repository.LocalFileOrganizationRepositoryImpl
import com.studentlearning.platform2026.data.repository.NotificationLogRepositoryImpl
import com.studentlearning.platform2026.data.repository.OfflineDownloadsRepositoryImpl
import com.studentlearning.platform2026.data.repository.PdfDocumentRepositoryImpl
import com.studentlearning.platform2026.data.repository.PreferencesRepositoryImpl
import com.studentlearning.platform2026.data.repository.ReadingProgressRepositoryImpl
import com.studentlearning.platform2026.data.repository.SystemStatusRepositoryImpl
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.repository.AdminCatalogRepository
import com.studentlearning.platform2026.domain.repository.AdminGovernanceRepository
import com.studentlearning.platform2026.domain.repository.AuthRepository
import com.studentlearning.platform2026.domain.repository.CatalogRepository
import com.studentlearning.platform2026.domain.repository.LocalFileOrganizationRepository
import com.studentlearning.platform2026.domain.repository.NotificationLogRepository
import com.studentlearning.platform2026.domain.repository.OfflineDownloadsRepository
import com.studentlearning.platform2026.domain.repository.PdfDocumentRepository
import com.studentlearning.platform2026.domain.repository.PdfVersionStorageBroker
import com.studentlearning.platform2026.domain.repository.PreferencesRepository
import com.studentlearning.platform2026.domain.repository.ReadingProgressRepository
import com.studentlearning.platform2026.domain.repository.SystemStatusRepository
import java.io.File

class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider()

    val firebaseClientProvider: FirebaseClientProvider by lazy {
        FirebaseClientProvider(appContext)
    }

    private val preferencesDataSource: PreferencesDataSource by lazy {
        PreferencesDataSource(appContext)
    }

    private val readingProgressDataSource: ReadingProgressDataSource by lazy {
        ReadingProgressDataSource(appContext)
    }

    private val offlineDownloadsDataSource: OfflineDownloadsDataSource by lazy {
        OfflineDownloadsDataSource(appContext)
    }

    private val localFileOrganizationDataSource: LocalFileOrganizationDataSource by lazy {
        LocalFileOrganizationDataSource(appContext)
    }

    private val localNotificationDataSource: LocalNotificationDataSource by lazy {
        LocalNotificationDataSource(appContext)
    }

    private val firebaseStatusDataSource: FirebaseStatusDataSource by lazy {
        FirebaseStatusDataSource(firebaseClientProvider)
    }

    private val firebaseAuthDataSource: FirebaseAuthDataSource by lazy {
        FirebaseAuthDataSource(firebaseClientProvider)
    }

    private val authRemoteDataSource: AuthRemoteDataSource
        get() = firebaseAuthDataSource

    private val authTokenProvider: AuthTokenProvider
        get() = firebaseAuthDataSource

    private val backendBaseUrl: String? = BuildConfig.HAQIBATI_BACKEND_BASE_URL
        .trim()
        .takeIf { it.isNotEmpty() }

    private val backendHttpClient: BackendHttpClient by lazy {
        BackendHttpClient(rawBaseUrl = backendBaseUrl)
    }

    private val backendCatalogDataSource: BackendCatalogDataSource by lazy {
        BackendCatalogDataSource(
            httpClient = backendHttpClient,
            authTokenProvider = authTokenProvider
        )
    }

    private val backendAdminVersionDataSource: BackendAdminVersionDataSource by lazy {
        BackendAdminVersionDataSource(
            httpClient = backendHttpClient,
            authTokenProvider = authTokenProvider
        )
    }

    private val catalogRemoteDataSource: CatalogRemoteDataSource
        get() = backendCatalogDataSource

    private val adminCatalogRemoteDataSource: AdminCatalogRemoteDataSource by lazy {
        HybridAdminCatalogDataSource(
            firestoreDataSource = FirestoreAdminCatalogDataSource(
                firestoreProvider = firebaseClientProvider,
                actorRoleResolver = {
                    when (authRepository.sessionFlow.value.role) {
                        AdminRole.OWNER -> "owner"
                        AdminRole.ADMIN -> "admin"
                        else -> null
                    }
                }
            ),
            backendVersionDataSource = backendAdminVersionDataSource
        )
    }

    // Secure Cloudflare R2 broker base URL is supplied exclusively via HAQIBATI_BACKEND_BASE_URL
    // BuildConfig at build time; never embeds storage keys or public bucket guesses inside the APK.
    private val pdfFileSourceDataSource: PdfFileSourceDataSource by lazy {
        BrokeredPdfFileSourceDataSource(
            brokerEndpointBaseUrl = backendBaseUrl,
            authTokenProvider = authTokenProvider
        )
    }

    val pdfVersionStorageBroker: PdfVersionStorageBroker by lazy {
        BrokeredPdfVersionStorageDataSource(
            brokerEndpointBaseUrl = backendBaseUrl,
            backendUploadClient = if (backendHttpClient.isConfigured) {
                backendAdminVersionDataSource
            } else {
                null
            }
        )
    }

    private val pdfContentFetcher: PdfContentFetcher by lazy {
        HttpPdfContentFetcher(
            sessionCacheDir = File(appContext.cacheDir, "pdf_viewer_session")
        )
    }

    private val pdfSessionFactory: PdfDocumentSessionFactory by lazy {
        AndroidPdfDocumentSessionFactory(
            context = appContext,
            dispatcherProvider = dispatcherProvider
        )
    }

    private val localPdfStorageManager: LocalPdfStorageManager by lazy {
        LocalPdfStorageManager(storageRoot = appContext.filesDir)
    }

    private val downloadStreamFetcher: DownloadStreamFetcher by lazy {
        HttpDownloadStreamFetcher()
    }

    val downloadExecutionEngine: DownloadExecutionEngine by lazy {
        DownloadExecutionEngine(
            sourceDataSource = pdfFileSourceDataSource,
            streamFetcher = downloadStreamFetcher,
            storageManager = localPdfStorageManager,
            downloadsDataSource = offlineDownloadsDataSource,
            dispatcherProvider = dispatcherProvider
        )
    }

    private val downloadTaskScheduler: DownloadTaskScheduler by lazy {
        WorkManagerDownloadScheduler(context = appContext)
    }

    val preferencesRepository: PreferencesRepository by lazy {
        PreferencesRepositoryImpl(preferencesDataSource)
    }

    val readingProgressRepository: ReadingProgressRepository by lazy {
        ReadingProgressRepositoryImpl(
            localDataSource = readingProgressDataSource,
            dispatcherProvider = dispatcherProvider
        )
    }

    val authRepository: AuthRepository by lazy {
        AuthRepositoryImpl(
            remoteDataSource = authRemoteDataSource,
            dispatcherProvider = dispatcherProvider
        )
    }

    val offlineDownloadsRepository: OfflineDownloadsRepository by lazy {
        OfflineDownloadsRepositoryImpl(
            authRepository = authRepository,
            downloadsDataSource = offlineDownloadsDataSource,
            storageManager = localPdfStorageManager,
            taskScheduler = downloadTaskScheduler,
            dispatcherProvider = dispatcherProvider
        )
    }

    val pdfDocumentRepository: PdfDocumentRepository by lazy {
        PdfDocumentRepositoryImpl(
            sourceDataSource = pdfFileSourceDataSource,
            contentFetcher = pdfContentFetcher,
            sessionFactory = pdfSessionFactory,
            dispatcherProvider = dispatcherProvider,
            offlineDownloadsRepository = offlineDownloadsRepository
        )
    }

    val systemStatusRepository: SystemStatusRepository by lazy {
        SystemStatusRepositoryImpl(firebaseStatusDataSource)
    }

    val catalogRepository: CatalogRepository by lazy {
        CatalogRepositoryImpl(
            remoteDataSource = catalogRemoteDataSource,
            dispatcherProvider = dispatcherProvider
        )
    }

    val adminCatalogRepository: AdminCatalogRepository by lazy {
        AdminCatalogRepositoryImpl(
            authRepository = authRepository,
            remoteDataSource = adminCatalogRemoteDataSource,
            dispatcherProvider = dispatcherProvider,
            pdfVersionStorageBroker = pdfVersionStorageBroker
        )
    }

    val localFileOrganizationRepository: LocalFileOrganizationRepository by lazy {
        LocalFileOrganizationRepositoryImpl(
            localDataSource = localFileOrganizationDataSource,
            dispatcherProvider = dispatcherProvider
        )
    }

    val notificationLogRepository: NotificationLogRepository by lazy {
        NotificationLogRepositoryImpl(
            localDataSource = localNotificationDataSource,
            dispatcherProvider = dispatcherProvider
        )
    }

    val adminGovernanceRepository: AdminGovernanceRepository by lazy {
        AdminGovernanceRepositoryImpl(
            authRepository = authRepository,
            backendDataSource = backendAdminVersionDataSource,
            dispatcherProvider = dispatcherProvider
        )
    }
}
