package com.studentlearning.platform2026.core.di

import android.content.Context
import com.studentlearning.platform2026.core.common.DefaultDispatcherProvider
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.local.PreferencesDataSource
import com.studentlearning.platform2026.data.local.ReadingProgressDataSource
import com.studentlearning.platform2026.data.pdf.AndroidPdfDocumentSessionFactory
import com.studentlearning.platform2026.data.pdf.BrokeredPdfFileSourceDataSource
import com.studentlearning.platform2026.data.pdf.HttpPdfContentFetcher
import com.studentlearning.platform2026.data.pdf.PdfContentFetcher
import com.studentlearning.platform2026.data.pdf.PdfDocumentSessionFactory
import com.studentlearning.platform2026.data.pdf.PdfFileSourceDataSource
import com.studentlearning.platform2026.data.remote.firebase.AdminCatalogRemoteDataSource
import com.studentlearning.platform2026.data.remote.firebase.AuthRemoteDataSource
import com.studentlearning.platform2026.data.remote.firebase.CatalogRemoteDataSource
import com.studentlearning.platform2026.data.remote.firebase.FirebaseAuthDataSource
import com.studentlearning.platform2026.data.remote.firebase.FirebaseClientProvider
import com.studentlearning.platform2026.data.remote.firebase.FirebaseStatusDataSource
import com.studentlearning.platform2026.data.remote.firebase.FirestoreAdminCatalogDataSource
import com.studentlearning.platform2026.data.remote.firebase.FirestoreCatalogDataSource
import com.studentlearning.platform2026.data.repository.AdminCatalogRepositoryImpl
import com.studentlearning.platform2026.data.repository.AuthRepositoryImpl
import com.studentlearning.platform2026.data.repository.CatalogRepositoryImpl
import com.studentlearning.platform2026.data.repository.PdfDocumentRepositoryImpl
import com.studentlearning.platform2026.data.repository.PreferencesRepositoryImpl
import com.studentlearning.platform2026.data.repository.ReadingProgressRepositoryImpl
import com.studentlearning.platform2026.data.repository.SystemStatusRepositoryImpl
import com.studentlearning.platform2026.domain.repository.AdminCatalogRepository
import com.studentlearning.platform2026.domain.repository.AuthRepository
import com.studentlearning.platform2026.domain.repository.CatalogRepository
import com.studentlearning.platform2026.domain.repository.PdfDocumentRepository
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

    private val firebaseStatusDataSource: FirebaseStatusDataSource by lazy {
        FirebaseStatusDataSource(firebaseClientProvider)
    }

    private val catalogRemoteDataSource: CatalogRemoteDataSource by lazy {
        FirestoreCatalogDataSource(firebaseClientProvider)
    }

    private val authRemoteDataSource: AuthRemoteDataSource by lazy {
        FirebaseAuthDataSource(firebaseClientProvider)
    }

    private val adminCatalogRemoteDataSource: AdminCatalogRemoteDataSource by lazy {
        FirestoreAdminCatalogDataSource(firebaseClientProvider)
    }

    // Secure Cloudflare R2 broker base URL remains unconfigured until backend signed-URL
    // integration; never embeds storage keys or public bucket guesses inside the APK.
    private val pdfFileSourceDataSource: PdfFileSourceDataSource by lazy {
        BrokeredPdfFileSourceDataSource(brokerEndpointBaseUrl = null)
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

    val preferencesRepository: PreferencesRepository by lazy {
        PreferencesRepositoryImpl(preferencesDataSource)
    }

    val readingProgressRepository: ReadingProgressRepository by lazy {
        ReadingProgressRepositoryImpl(
            localDataSource = readingProgressDataSource,
            dispatcherProvider = dispatcherProvider
        )
    }

    val pdfDocumentRepository: PdfDocumentRepository by lazy {
        PdfDocumentRepositoryImpl(
            sourceDataSource = pdfFileSourceDataSource,
            contentFetcher = pdfContentFetcher,
            sessionFactory = pdfSessionFactory,
            dispatcherProvider = dispatcherProvider
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

    val authRepository: AuthRepository by lazy {
        AuthRepositoryImpl(
            remoteDataSource = authRemoteDataSource,
            dispatcherProvider = dispatcherProvider
        )
    }

    val adminCatalogRepository: AdminCatalogRepository by lazy {
        AdminCatalogRepositoryImpl(
            authRepository = authRepository,
            remoteDataSource = adminCatalogRemoteDataSource,
            dispatcherProvider = dispatcherProvider
        )
    }
}
