package com.studentlearning.platform2026.data.download

import com.studentlearning.platform2026.domain.model.DownloadException
import com.studentlearning.platform2026.domain.model.DownloadFailureReason
import com.studentlearning.platform2026.domain.model.LocalDownloadedFileVersion
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class VerifiedPdfFileResult(
    val fileSizeBytes: Long,
    val sha256Hex: String
)

/**
 * Manages version-isolated offline PDF storage inside private app-internal storage ([storageRoot]).
 *
 * Layout:
 * - Completed verified PDFs: `<storageRoot>/haqibati_offline_pdfs/<hex(fileId)>/<hex(versionId)>/document.pdf`
 * - In-progress temp files: `<storageRoot>/haqibati_offline_tmp/<hex(fileId)>_<hex(versionId)>_<uuid>.part`
 *
 * Because paths use hex-encoded `(fileId, versionId)`:
 * - Older downloaded versions are NEVER overwritten when a newer version is downloaded.
 * - Distinct files with identical Arabic titles never collide.
 * - Partial `.part` files live outside the completed directory and are never treated as valid offline copies.
 */
class LocalPdfStorageManager(
    private val storageRoot: File,
    private val usableSpaceProvider: (File) -> Long = { root ->
        if (!root.exists()) root.mkdirs()
        root.usableSpace
    },
    private val tempToTargetRename: (File, File) -> Boolean = { tempFile, targetFile ->
        tempFile.renameTo(targetFile)
    },
    private val onCommitTempFileCreated: (() -> Unit)? = null,
    private val createNewFileAction: (File) -> Boolean = { file ->
        file.createNewFile()
    }
) {
    private val completedRootDir: File
        get() = File(storageRoot, COMPLETED_DIR_NAME)

    private val tempRootDir: File
        get() = File(storageRoot, TEMP_DIR_NAME)

    private val tempLifecycleLock = ReentrantLock()
    private val activeTempPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val activeCommitPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun resolveCompletedFile(fileId: String, versionId: String): File {
        val fileDir = File(completedRootDir, encodeHex(fileId))
        val versionDir = File(fileDir, encodeHex(versionId))
        return File(versionDir, DOCUMENT_FILE_NAME)
    }

    fun createTempPartFile(fileId: String, versionId: String): File {
        return tempLifecycleLock.withLock {
            if (!tempRootDir.exists() && !tempRootDir.mkdirs()) {
                throw DownloadException(
                    reason = DownloadFailureReason.STORAGE_WRITE_ERROR,
                    message = "Unable to create temporary download directory."
                )
            }
            val token = UUID.randomUUID().toString().replace("-", "").take(12)
            val fileName = "${encodeHex(fileId)}_${encodeHex(versionId)}_$token.part"
            val file = File(tempRootDir, fileName)
            activeTempPaths.add(file.absolutePath)
            var created = false
            try {
                if (!file.exists() && !createNewFileAction(file)) {
                    throw DownloadException(
                        reason = DownloadFailureReason.STORAGE_WRITE_ERROR,
                        message = "Unable to initialize temporary download file."
                    )
                }
                created = true
            } catch (io: IOException) {
                val reason = if (isOutOfSpaceError(io)) {
                    DownloadFailureReason.INSUFFICIENT_STORAGE
                } else {
                    DownloadFailureReason.STORAGE_WRITE_ERROR
                }
                throw DownloadException(
                    reason = reason,
                    message = io.localizedMessage ?: "Failed to create temporary download file.",
                    cause = io
                )
            } finally {
                if (!created) {
                    activeTempPaths.remove(file.absolutePath)
                }
            }
            file
        }
    }

    fun releaseTempFile(file: File?) {
        if (file == null) return
        tempLifecycleLock.withLock {
            activeTempPaths.remove(file.absolutePath)
        }
    }

    fun ensureSufficientStorage(requiredBytes: Long?) {
        val targetBytes = when {
            requiredBytes != null && requiredBytes > 0L -> requiredBytes + SAFETY_BUFFER_BYTES
            else -> MINIMUM_FREE_SPACE_BYTES
        }
        val available = runCatching { usableSpaceProvider(storageRoot) }.getOrElse { 0L }
        if (available < targetBytes) {
            throw DownloadException(
                reason = DownloadFailureReason.INSUFFICIENT_STORAGE,
                message = "Insufficient device storage space ($available bytes available, $targetBytes required)."
            )
        }
    }

    fun verifyDownloadedTempFile(
        tempFile: File,
        expectedByteCount: Long? = null,
        expectedSha256: String? = null
    ): VerifiedPdfFileResult {
        if (!tempFile.exists() || !tempFile.isFile || !tempFile.canRead()) {
            throw DownloadException(
                reason = DownloadFailureReason.INTEGRITY_CHECK_FAILED,
                message = "Downloaded temporary file is missing or unreadable."
            )
        }

        val actualLength = tempFile.length()
        if (actualLength <= 0L) {
            throw DownloadException(
                reason = DownloadFailureReason.INTEGRITY_CHECK_FAILED,
                message = "Downloaded file is empty."
            )
        }

        if (expectedByteCount != null && expectedByteCount > 0L && actualLength != expectedByteCount) {
            throw DownloadException(
                reason = DownloadFailureReason.INTEGRITY_CHECK_FAILED,
                message = "Downloaded file size mismatch (expected $expectedByteCount, got $actualLength)."
            )
        }

        if (!hasValidPdfHeader(tempFile)) {
            throw DownloadException(
                reason = DownloadFailureReason.INTEGRITY_CHECK_FAILED,
                message = "Downloaded file does not contain a valid PDF header."
            )
        }

        val actualSha256 = try {
            computeSha256(tempFile)
        } catch (io: IOException) {
            throw DownloadException(
                reason = DownloadFailureReason.STORAGE_WRITE_ERROR,
                message = io.localizedMessage ?: "Failed to read file for checksum verification.",
                cause = io
            )
        }

        val normalizedExpectedSha = expectedSha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (normalizedExpectedSha != null && actualSha256 != normalizedExpectedSha) {
            throw DownloadException(
                reason = DownloadFailureReason.INTEGRITY_CHECK_FAILED,
                message = "Downloaded file SHA-256 checksum verification failed."
            )
        }

        return VerifiedPdfFileResult(
            fileSizeBytes = actualLength,
            sha256Hex = actualSha256
        )
    }

    fun commitVerifiedTempFile(
        tempFile: File,
        fileId: String,
        versionId: String
    ): File {
        val targetFile = resolveCompletedFile(fileId = fileId, versionId = versionId)
        val parentDir = targetFile.parentFile
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            throw DownloadException(
                reason = DownloadFailureReason.STORAGE_WRITE_ERROR,
                message = "Failed to create versioned offline directory."
            )
        }

        if (targetFile.exists() && !targetFile.delete()) {
            throw DownloadException(
                reason = DownloadFailureReason.STORAGE_WRITE_ERROR,
                message = "Failed to replace unverified target path."
            )
        }

        if (tempToTargetRename(tempFile, targetFile)) {
            return targetFile
        }

        // Fallback atomic sibling copy + rename if filesystem rename across folders is disallowed.
        // Register + create siblingTmp under tempLifecycleLock so cleanupOrphanedTempFiles() can
        // never observe the file before it enters activeCommitPaths, while releasing the lock
        // before streaming/copying so concurrent downloads & cleanups are never blocked.
        val siblingTmp = tempLifecycleLock.withLock {
            if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                throw DownloadException(
                    reason = DownloadFailureReason.STORAGE_WRITE_ERROR,
                    message = "Failed to create versioned offline directory."
                )
            }
            val commitToken = UUID.randomUUID().toString().replace("-", "").take(8)
            val candidate = File(parentDir, ".commit_${System.nanoTime()}_$commitToken.tmp")
            activeCommitPaths.add(candidate.absolutePath)
            try {
                if (!candidate.exists() && !candidate.createNewFile()) {
                    throw DownloadException(
                        reason = DownloadFailureReason.STORAGE_WRITE_ERROR,
                        message = "Failed to initialize commit staging file."
                    )
                }
            } catch (downloadError: DownloadException) {
                activeCommitPaths.remove(candidate.absolutePath)
                throw downloadError
            } catch (io: IOException) {
                activeCommitPaths.remove(candidate.absolutePath)
                val reason = if (isOutOfSpaceError(io)) {
                    DownloadFailureReason.INSUFFICIENT_STORAGE
                } else {
                    DownloadFailureReason.STORAGE_WRITE_ERROR
                }
                throw DownloadException(
                    reason = reason,
                    message = io.localizedMessage ?: "Failed creating commit staging file.",
                    cause = io
                )
            }
            candidate
        }

        try {
            tempFile.inputStream().use { input ->
                siblingTmp.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            onCommitTempFileCreated?.invoke()
            if (!siblingTmp.renameTo(targetFile)) {
                throw DownloadException(
                    reason = DownloadFailureReason.STORAGE_WRITE_ERROR,
                    message = "Failed to commit downloaded PDF into versioned storage."
                )
            }
            tempFile.delete()
            return targetFile
        } catch (downloadError: DownloadException) {
            throw downloadError
        } catch (io: IOException) {
            val reason = if (isOutOfSpaceError(io)) {
                DownloadFailureReason.INSUFFICIENT_STORAGE
            } else {
                DownloadFailureReason.STORAGE_WRITE_ERROR
            }
            throw DownloadException(
                reason = reason,
                message = io.localizedMessage ?: "Failed writing PDF to offline storage.",
                cause = io
            )
        } finally {
            tempLifecycleLock.withLock {
                runCatching {
                    if (siblingTmp.exists()) {
                        siblingTmp.delete()
                    }
                }
                activeCommitPaths.remove(siblingTmp.absolutePath)
            }
        }
    }

    fun isStoredFileValid(record: LocalDownloadedFileVersion): Boolean {
        val file = File(record.localFilePath)
        if (!file.exists() || !file.isFile || !file.canRead()) return false
        if (record.fileSizeBytes <= 0L || file.length() != record.fileSizeBytes) return false
        return hasValidPdfHeader(file)
    }

    fun deleteTempFileQuietly(file: File?) {
        if (file == null) return
        tempLifecycleLock.withLock {
            activeTempPaths.remove(file.absolutePath)
            runCatching {
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    fun deleteCompletedVersionFile(fileId: String, versionId: String): Boolean {
        val file = resolveCompletedFile(fileId = fileId, versionId = versionId)
        val deleted = if (file.exists()) file.delete() else true
        // Clean empty version directory if empty, keeping sibling versions untouched.
        file.parentFile?.let { versionDir ->
            if (versionDir.isDirectory && versionDir.list().isNullOrEmpty()) {
                runCatching { versionDir.delete() }
            }
        }
        return deleted
    }

    fun cleanupOrphanedTempFiles(activeTempPaths: Set<String> = emptySet()) {
        tempLifecycleLock.withLock {
            val protectedTempPaths = this.activeTempPaths.toSet() + activeTempPaths
            if (tempRootDir.exists() && tempRootDir.isDirectory) {
                tempRootDir.listFiles()?.forEach { candidate ->
                    if (candidate.isFile &&
                        candidate.name.endsWith(".part") &&
                        !protectedTempPaths.contains(candidate.absolutePath)
                    ) {
                        runCatching { candidate.delete() }
                    }
                }
            }

            // Also sweep any orphaned `.commit_*.tmp` left behind if process died mid-commit,
            // without touching active commit temp files or committed `document.pdf` files.
            val protectedCommitPaths = this.activeCommitPaths.toSet()
            if (completedRootDir.exists() && completedRootDir.isDirectory) {
                completedRootDir.listFiles()?.forEach { fileDir ->
                    if (fileDir.isDirectory) {
                        fileDir.listFiles()?.forEach { versionDir ->
                            if (versionDir.isDirectory) {
                                versionDir.listFiles()?.forEach { candidate ->
                                    if (candidate.isFile &&
                                        candidate.name.startsWith(".commit_") &&
                                        candidate.name.endsWith(".tmp") &&
                                        !protectedCommitPaths.contains(candidate.absolutePath)
                                    ) {
                                        runCatching { candidate.delete() }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun listAllTempFiles(): List<File> = tempLifecycleLock.withLock {
        if (!tempRootDir.exists() || !tempRootDir.isDirectory) return@withLock emptyList()
        tempRootDir.listFiles()?.filter { it.isFile }.orEmpty()
    }

    fun hasValidPdfHeader(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < PDF_MAGIC_BYTES.size) return false
        return try {
            FileInputStream(file).use { stream ->
                val header = ByteArray(PDF_MAGIC_BYTES.size)
                val read = stream.read(header)
                read == PDF_MAGIC_BYTES.size && header.contentEquals(PDF_MAGIC_BYTES)
            }
        } catch (_: IOException) {
            false
        }
    }

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(IO_BUFFER_BYTES)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun encodeHex(value: String): String {
        return value.trim()
            .toByteArray(Charsets.UTF_8)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    companion object {
        const val COMPLETED_DIR_NAME = "haqibati_offline_pdfs"
        const val TEMP_DIR_NAME = "haqibati_offline_tmp"
        const val DOCUMENT_FILE_NAME = "document.pdf"
        const val MINIMUM_FREE_SPACE_BYTES = 512L * 1024L
        const val SAFETY_BUFFER_BYTES = 256L * 1024L
        private const val IO_BUFFER_BYTES = 16 * 1024
        private val PDF_MAGIC_BYTES = "%PDF-".toByteArray(Charsets.US_ASCII)

        fun isOutOfSpaceError(throwable: Throwable?): Boolean {
            var current: Throwable? = throwable
            while (current != null) {
                val msg = current.message?.lowercase().orEmpty()
                if (msg.contains("enospc") ||
                    msg.contains("no space left") ||
                    msg.contains("not enough space") ||
                    msg.contains("disk full")
                ) {
                    return true
                }
                current = current.cause
            }
            return false
        }
    }
}
