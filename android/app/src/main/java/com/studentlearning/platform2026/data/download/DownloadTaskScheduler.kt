package com.studentlearning.platform2026.data.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import com.studentlearning.platform2026.domain.model.DownloadFailureReason
import com.studentlearning.platform2026.domain.model.DownloadStatus
import com.studentlearning.platform2026.domain.model.DownloadTaskState
import com.studentlearning.platform2026.domain.model.OfflineDownloadRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface DownloadTaskScheduler {
    fun observeTasks(): Flow<Map<Pair<String, String>, DownloadTaskState>>

    suspend fun getTaskState(fileId: String, versionId: String): DownloadTaskState?

    suspend fun enqueue(
        request: OfflineDownloadRequest,
        replaceExisting: Boolean = false
    ): DownloadTaskState

    suspend fun cancel(fileId: String, versionId: String)
}

/**
 * WorkManager-backed download scheduler ensuring downloads survive screen closure, navigation,
 * configuration changes, and app backgrounding with network constraints and unique per-version work.
 *
 * Uses non-blocking [ListenableFuture] suspension on `enqueueUniqueWork` and `cancelUniqueWork`
 * operations so Room metadata transitions finish before returning to the repository without
 * blocking caller or Main threads.
 */
class WorkManagerDownloadScheduler(
    context: Context,
    private val clockEpochMillis: () -> Long = { System.currentTimeMillis() }
) : DownloadTaskScheduler {

    private val workManager: WorkManager by lazy {
        WorkManager.getInstance(context.applicationContext)
    }

    private val directExecutor = Executor { command -> command.run() }

    override fun observeTasks(): Flow<Map<Pair<String, String>, DownloadTaskState>> {
        return workManager
            .getWorkInfosByTagFlow(TAG_OFFLINE_DOWNLOAD)
            .map { workInfos ->
                val result = LinkedHashMap<Pair<String, String>, DownloadTaskState>()
                workInfos.forEach { info ->
                    val mapped = mapWorkInfoToTaskState(info, clockEpochMillis())
                    if (mapped != null) {
                        result[mapped.fileId to mapped.versionId] = mapped
                    }
                }
                result
            }
    }

    override suspend fun getTaskState(fileId: String, versionId: String): DownloadTaskState? {
        if (fileId.isBlank() || versionId.isBlank()) return null
        val infos = try {
            workManager.getWorkInfosForUniqueWork(uniqueWorkName(fileId, versionId))
                .awaitResult(directExecutor)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return null
        }
        val info = infos.firstOrNull() ?: return null
        return mapWorkInfoToTaskState(info, clockEpochMillis())
    }

    override suspend fun enqueue(
        request: OfflineDownloadRequest,
        replaceExisting: Boolean
    ): DownloadTaskState {
        val workName = uniqueWorkName(request.fileId, request.versionId)
        val inputData = buildInputData(request)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<PdfDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .addTag(TAG_OFFLINE_DOWNLOAD)
            .addTag(workName)
            .addTag(encodePairTag(request.fileId, request.versionId))
            .build()

        val policy = if (replaceExisting) {
            ExistingWorkPolicy.REPLACE
        } else {
            ExistingWorkPolicy.KEEP
        }
        workManager.enqueueUniqueWork(workName, policy, workRequest)
            .result
            .awaitResult(directExecutor)

        val liveInfo = try {
            workManager.getWorkInfosForUniqueWork(workName)
                .awaitResult(directExecutor)
                .firstOrNull()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }

        val mappedLive = liveInfo?.let { info ->
            mapWorkInfoToTaskState(info, clockEpochMillis())?.let { state ->
                state.copy(
                    versionNumber = state.versionNumber ?: request.versionNumber,
                    versionLabel = state.versionLabel ?: request.versionLabel,
                    totalBytes = state.totalBytes ?: request.expectedFileSizeBytes
                )
            }
        }

        return mappedLive ?: DownloadTaskState(
            fileId = request.fileId,
            versionId = request.versionId,
            versionNumber = request.versionNumber,
            versionLabel = request.versionLabel,
            status = DownloadStatus.QUEUED,
            bytesDownloaded = 0L,
            totalBytes = request.expectedFileSizeBytes,
            progressPercent = null,
            failureReason = null,
            updatedAtEpochMillis = clockEpochMillis(),
            taskId = workRequest.id.toString()
        )
    }

    override suspend fun cancel(fileId: String, versionId: String) {
        if (fileId.isBlank() || versionId.isBlank()) return
        workManager.cancelUniqueWork(uniqueWorkName(fileId, versionId))
            .result
            .awaitResult(directExecutor)
    }

    private suspend fun <T> ListenableFuture<T>.awaitResult(executor: Executor): T {
        if (isDone) {
            return try {
                get()
            } catch (exec: ExecutionException) {
                throw (exec.cause ?: exec)
            }
        }
        return suspendCancellableCoroutine { continuation ->
            addListener(
                {
                    try {
                        val value = get()
                        if (continuation.isActive) {
                            continuation.resume(value)
                        }
                    } catch (exec: ExecutionException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(exec.cause ?: exec)
                        }
                    } catch (cancelled: CancellationException) {
                        if (continuation.isActive) {
                            continuation.cancel(cancelled)
                        }
                    } catch (throwable: Throwable) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(throwable)
                        }
                    }
                },
                executor
            )
            continuation.invokeOnCancellation {
                cancel(false)
            }
        }
    }

    companion object {
        const val TAG_OFFLINE_DOWNLOAD = "haqibati_offline_download"
        private const val TAG_PAIR_PREFIX = "pair:"

        const val KEY_FILE_ID = "file_id"
        const val KEY_VERSION_ID = "version_id"
        const val KEY_VERSION_NUMBER = "version_number"
        const val KEY_VERSION_LABEL = "version_label"
        const val KEY_FILE_TITLE = "file_title"
        const val KEY_SECTION_ID = "section_id"
        const val KEY_EXPECTED_SIZE = "expected_size_bytes"
        const val KEY_EXPECTED_SHA256 = "expected_sha256"

        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_FAILURE_REASON = "failure_reason"

        fun uniqueWorkName(fileId: String, versionId: String): String {
            return "haqibati_dl_${encodeHex(fileId)}_${encodeHex(versionId)}"
        }

        fun encodePairTag(fileId: String, versionId: String): String {
            return "$TAG_PAIR_PREFIX${encodeHex(fileId)}:${encodeHex(versionId)}"
        }

        fun decodePairFromTags(tags: Set<String>): Pair<String, String>? {
            val pairTag = tags.firstOrNull { it.startsWith(TAG_PAIR_PREFIX) } ?: return null
            val payload = pairTag.removePrefix(TAG_PAIR_PREFIX)
            val separatorIndex = payload.indexOf(':')
            if (separatorIndex <= 0 || separatorIndex >= payload.lastIndex) return null
            val fileId = decodeHex(payload.substring(0, separatorIndex)) ?: return null
            val versionId = decodeHex(payload.substring(separatorIndex + 1)) ?: return null
            return fileId to versionId
        }

        fun buildInputData(request: OfflineDownloadRequest): Data {
            val builder = Data.Builder()
                .putString(KEY_FILE_ID, request.fileId)
                .putString(KEY_VERSION_ID, request.versionId)
                .putString(KEY_FILE_TITLE, request.fileTitle)
            if (request.versionNumber != null) {
                builder.putInt(KEY_VERSION_NUMBER, request.versionNumber)
            }
            if (!request.versionLabel.isNullOrBlank()) {
                builder.putString(KEY_VERSION_LABEL, request.versionLabel)
            }
            if (!request.sectionId.isNullOrBlank()) {
                builder.putString(KEY_SECTION_ID, request.sectionId)
            }
            if (request.expectedFileSizeBytes != null && request.expectedFileSizeBytes > 0L) {
                builder.putLong(KEY_EXPECTED_SIZE, request.expectedFileSizeBytes)
            }
            if (!request.expectedChecksumSha256.isNullOrBlank()) {
                builder.putString(KEY_EXPECTED_SHA256, request.expectedChecksumSha256)
            }
            return builder.build()
        }

        fun mapWorkInfoToTaskState(
            info: WorkInfo,
            nowEpochMillis: Long
        ): DownloadTaskState? {
            val (fileId, versionId) = decodePairFromTags(info.tags) ?: return null
            val status = when (info.state) {
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.BLOCKED -> DownloadStatus.QUEUED
                WorkInfo.State.RUNNING -> DownloadStatus.DOWNLOADING
                WorkInfo.State.SUCCEEDED -> DownloadStatus.COMPLETED
                WorkInfo.State.FAILED -> DownloadStatus.FAILED
                WorkInfo.State.CANCELLED -> DownloadStatus.CANCELLED
            }

            val data = when (info.state) {
                WorkInfo.State.RUNNING -> info.progress
                WorkInfo.State.SUCCEEDED,
                WorkInfo.State.FAILED -> info.outputData
                else -> Data.EMPTY
            }

            val versionNumber = data.getInt(KEY_VERSION_NUMBER, -1).takeIf { it >= 1 }
            val versionLabel = data.getString(KEY_VERSION_LABEL)?.takeIf { it.isNotBlank() }
            val bytesDownloaded = data.getLong(KEY_BYTES_DOWNLOADED, 0L).coerceAtLeast(0L)
            val totalBytesRaw = data.getLong(KEY_TOTAL_BYTES, -1L)
            val totalBytes = totalBytesRaw.takeIf { it > 0L }
            val progressRaw = data.getInt(KEY_PROGRESS_PERCENT, -1)
            val progressPercent = progressRaw.takeIf { it in 0..100 }

            val failureReason = if (status == DownloadStatus.FAILED) {
                val rawReason = data.getString(KEY_FAILURE_REASON)
                rawReason?.let { name ->
                    runCatching { DownloadFailureReason.valueOf(name) }.getOrNull()
                } ?: DownloadFailureReason.UNKNOWN
            } else {
                null
            }

            return DownloadTaskState(
                fileId = fileId,
                versionId = versionId,
                versionNumber = versionNumber,
                versionLabel = versionLabel,
                status = status,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                progressPercent = progressPercent,
                failureReason = failureReason,
                updatedAtEpochMillis = nowEpochMillis,
                taskId = info.id.toString()
            )
        }

        private fun encodeHex(value: String): String {
            return value.trim()
                .toByteArray(Charsets.UTF_8)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        }

        private fun decodeHex(hex: String): String? {
            if (hex.isEmpty() || hex.length % 2 != 0) return null
            return try {
                val bytes = ByteArray(hex.length / 2) { index ->
                    hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                }
                String(bytes, Charsets.UTF_8)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
