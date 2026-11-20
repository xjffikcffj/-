package com.studentlearning.platform2026.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LocalPdfStorageManagerConcurrencyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `barrier synchronized createTempPartFile and cleanupOrphanedTempFiles never delete active parts`() {
        val storageRoot = tempFolder.newFolder("part_barrier_root")
        val storageManager = LocalPdfStorageManager(storageRoot = storageRoot)

        // Pre-create a valid completed version that cleanup must never touch.
        val existingTemp = storageManager.createTempPartFile("file-completed", "v0").apply {
            writeBytes("%PDF-1.4\ncompleted-baseline".toByteArray())
        }
        val completedFile = storageManager.commitVerifiedTempFile(existingTemp, "file-completed", "v0")
        storageManager.releaseTempFile(existingTemp)
        assertTrue(completedFile.exists())

        val tempDir = File(storageRoot, LocalPdfStorageManager.TEMP_DIR_NAME).apply { mkdirs() }
        val executor = Executors.newFixedThreadPool(3)
        val retainedParts = mutableListOf<File>()
        val rounds = 40

        try {
            for (round in 0 until rounds) {
                val orphanPart = File(tempDir, "orphan_round_$round.part").apply {
                    writeText("%PDF-dead-process-orphan-$round")
                }
                val startBarrier = CyclicBarrier(3)
                val roundDone = CountDownLatch(3)
                val errorRef = AtomicReference<Throwable?>(null)
                val partARef = AtomicReference<File?>(null)
                val partBRef = AtomicReference<File?>(null)

                executor.execute {
                    try {
                        startBarrier.await(5, TimeUnit.SECONDS)
                        val file = storageManager.createTempPartFile("file-alpha", "v$round")
                        file.writeBytes("%PDF-1.4\nalpha-$round".toByteArray())
                        partARef.set(file)
                    } catch (t: Throwable) {
                        errorRef.compareAndSet(null, t)
                    } finally {
                        roundDone.countDown()
                    }
                }

                executor.execute {
                    try {
                        startBarrier.await(5, TimeUnit.SECONDS)
                        val file = storageManager.createTempPartFile("file-beta", "v$round")
                        file.writeBytes("%PDF-1.4\nbeta-$round".toByteArray())
                        partBRef.set(file)
                    } catch (t: Throwable) {
                        errorRef.compareAndSet(null, t)
                    } finally {
                        roundDone.countDown()
                    }
                }

                executor.execute {
                    try {
                        startBarrier.await(5, TimeUnit.SECONDS)
                        storageManager.cleanupOrphanedTempFiles()
                    } catch (t: Throwable) {
                        errorRef.compareAndSet(null, t)
                    } finally {
                        roundDone.countDown()
                    }
                }

                assertTrue("Timed out waiting for barrier round $round", roundDone.await(10, TimeUnit.SECONDS))
                errorRef.get()?.let { throw AssertionError("Worker failed in round $round", it) }

                val partA = requireNotNull(partARef.get())
                val partB = requireNotNull(partBRef.get())
                assertTrue("Active .part A deleted during round $round", partA.exists())
                assertTrue("Active .part B deleted during round $round", partB.exists())
                assertFalse("Orphan .part survived round $round", orphanPart.exists())
                assertTrue("Completed document.pdf must stay intact", completedFile.exists())

                retainedParts += partA
                retainedParts += partB
            }

            // Second cleanup while all created parts remain active/registered must preserve every one.
            storageManager.cleanupOrphanedTempFiles()
            retainedParts.forEach { part ->
                assertTrue("Still-registered .part ${part.name} was deleted prematurely", part.exists())
            }

            // Once released, cleanup must delete the orphaned uncommitted .part files.
            retainedParts.forEach { storageManager.releaseTempFile(it) }
            storageManager.cleanupOrphanedTempFiles()
            retainedParts.forEach { part ->
                assertFalse("Released uncommitted .part ${part.name} was not cleaned up", part.exists())
            }
            assertTrue(storageManager.listAllTempFiles().isEmpty())
            assertTrue(completedFile.exists())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `active commit tmp file is protected during cleanup and lock does not block concurrent cleanup`() {
        val storageRoot = tempFolder.newFolder("commit_overlap_root")
        val commitTmpActiveLatch = CountDownLatch(1)
        val allowCommitFinishLatch = CountDownLatch(1)
        val hookInvoked = AtomicBoolean(false)

        val storageManager = LocalPdfStorageManager(
            storageRoot = storageRoot,
            tempToTargetRename = { _, _ -> false },
            onCommitTempFileCreated = {
                if (hookInvoked.compareAndSet(false, true)) {
                    commitTmpActiveLatch.countDown()
                    check(allowCommitFinishLatch.await(10, TimeUnit.SECONDS)) {
                        "Timed out waiting for allowCommitFinishLatch"
                    }
                }
            }
        )

        // Seed completed v1 first (using fallback path with first-call hook bypassed after releasing latch? Wait: seed before setting hook OR pre-seed v1 separately? Wait: hook fires once for v2 if we create v1 with a baseline manager!).
        val baselineManager = LocalPdfStorageManager(storageRoot = storageRoot)
        val tempV1 = baselineManager.createTempPartFile("file-1", "v1").apply {
            writeBytes("%PDF-1.4\ncompleted-v1".toByteArray())
        }
        val completedV1 = baselineManager.commitVerifiedTempFile(tempV1, "file-1", "v1")
        baselineManager.releaseTempFile(tempV1)

        // Plant both an orphaned `.part` and an orphaned `.commit_orphan.tmp` inside v2's target dir.
        val targetV2 = storageManager.resolveCompletedFile("file-1", "v2")
        val v2Dir = requireNotNull(targetV2.parentFile).apply { mkdirs() }
        val orphanedCommitTmp = File(v2Dir, ".commit_999999_deadbeef.tmp").apply {
            writeText("orphan-commit-leftover")
        }
        val tempDir = File(storageRoot, LocalPdfStorageManager.TEMP_DIR_NAME).apply { mkdirs() }
        val orphanedPart = File(tempDir, "deadbeef_0001.part").apply {
            writeText("%PDF-orphan-part")
        }

        val activeTempV2 = storageManager.createTempPartFile("file-1", "v2").apply {
            writeBytes("%PDF-1.4\nverified-v2-payload".toByteArray())
        }

        val executor = Executors.newSingleThreadExecutor()
        val commitError = AtomicReference<Throwable?>(null)
        val commitResult = AtomicReference<File?>(null)
        val commitDone = CountDownLatch(1)

        try {
            executor.execute {
                try {
                    commitResult.set(storageManager.commitVerifiedTempFile(activeTempV2, "file-1", "v2"))
                } catch (t: Throwable) {
                    commitError.set(t)
                } finally {
                    storageManager.releaseTempFile(activeTempV2)
                    commitDone.countDown()
                }
            }

            // Wait deterministically until sibling `.commit_*.tmp` is created, registered, and written.
            assertTrue(commitTmpActiveLatch.await(5, TimeUnit.SECONDS))

            val commitFilesBeforeCleanup = v2Dir.listFiles { file ->
                file.name.startsWith(".commit_") && file.name.endsWith(".tmp")
            }.orEmpty()
            assertEquals(2, commitFilesBeforeCleanup.size)
            val activeCommitFile = commitFilesBeforeCleanup.first { it.absolutePath != orphanedCommitTmp.absolutePath }
            assertTrue(activeCommitFile.exists())

            // Run cleanup concurrently while commit thread is paused holding active `.commit_*.tmp`.
            // This proves both non-blocking cleanup execution and active commit protection.
            storageManager.cleanupOrphanedTempFiles()

            assertFalse("Orphan .part should be deleted", orphanedPart.exists())
            assertFalse("Orphan .commit_*.tmp should be deleted", orphanedCommitTmp.exists())
            assertTrue("Active .commit_*.tmp must NOT be deleted mid-commit", activeCommitFile.exists())
            assertTrue("Active .part must NOT be deleted mid-commit", activeTempV2.exists())
            assertTrue("Completed v1 document.pdf must stay intact", completedV1.exists())

            allowCommitFinishLatch.countDown()
            assertTrue(commitDone.await(5, TimeUnit.SECONDS))
            commitError.get()?.let { throw AssertionError("Commit failed", it) }

            val committedV2 = requireNotNull(commitResult.get())
            assertTrue(committedV2.exists())
            assertTrue(committedV2.readBytes().contentEquals("%PDF-1.4\nverified-v2-payload".toByteArray()))
            assertTrue(completedV1.exists())

            // No leftover `.commit_*.tmp` after commit finishes.
            val remainingCommitTmp = v2Dir.listFiles { file ->
                file.name.startsWith(".commit_") && file.name.endsWith(".tmp")
            }.orEmpty()
            assertTrue(remainingCommitTmp.isEmpty())
        } finally {
            allowCommitFinishLatch.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `barrier synchronized commitVerifiedTempFile fallback and cleanup never corrupt active commits`() {
        val storageRoot = tempFolder.newFolder("commit_barrier_root")
        val storageManager = LocalPdfStorageManager(
            storageRoot = storageRoot,
            tempToTargetRename = { _, _ -> false }
        )

        val rounds = 30
        val executor = Executors.newFixedThreadPool(2)

        try {
            for (round in 0 until rounds) {
                val versionId = "ver-$round"
                val payload = "%PDF-1.4\ncommit-barrier-$round".toByteArray()
                val tempPart = storageManager.createTempPartFile("file-barrier", versionId).apply {
                    writeBytes(payload)
                }
                val versionDir = requireNotNull(
                    storageManager.resolveCompletedFile("file-barrier", versionId).parentFile
                ).apply { mkdirs() }
                val orphanCommit = File(versionDir, ".commit_stale_$round.tmp").apply {
                    writeText("stale-orphan-$round")
                }

                val startBarrier = CyclicBarrier(2)
                val roundDone = CountDownLatch(2)
                val errorRef = AtomicReference<Throwable?>(null)
                val committedRef = AtomicReference<File?>(null)

                executor.execute {
                    try {
                        startBarrier.await(5, TimeUnit.SECONDS)
                        committedRef.set(storageManager.commitVerifiedTempFile(tempPart, "file-barrier", versionId))
                    } catch (t: Throwable) {
                        errorRef.compareAndSet(null, t)
                    } finally {
                        storageManager.releaseTempFile(tempPart)
                        roundDone.countDown()
                    }
                }

                executor.execute {
                    try {
                        startBarrier.await(5, TimeUnit.SECONDS)
                        storageManager.cleanupOrphanedTempFiles()
                    } catch (t: Throwable) {
                        errorRef.compareAndSet(null, t)
                    } finally {
                        roundDone.countDown()
                    }
                }

                assertTrue("Timed out on commit barrier round $round", roundDone.await(10, TimeUnit.SECONDS))
                errorRef.get()?.let { throw AssertionError("Commit barrier failed at round $round", it) }

                val committed = requireNotNull(committedRef.get())
                assertTrue(committed.exists())
                assertTrue(committed.readBytes().contentEquals(payload))

                storageManager.cleanupOrphanedTempFiles()
                assertFalse("Orphan commit file survived round $round", orphanCommit.exists())
                val leftoverCommits = versionDir.listFiles { file ->
                    file.name.startsWith(".commit_") && file.name.endsWith(".tmp")
                }.orEmpty()
                assertTrue("Leftover commit tmp after round $round", leftoverCommits.isEmpty())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `two concurrent downloads and failed commit exception release locks and leave no leaks`() {
        val storageRoot = tempFolder.newFolder("two_downloads_root")
        val failNextCommit = AtomicBoolean(true)
        val storageManager = LocalPdfStorageManager(
            storageRoot = storageRoot,
            tempToTargetRename = { _, _ -> false },
            onCommitTempFileCreated = {
                if (failNextCommit.compareAndSet(true, false)) {
                    throw IOException("Simulated I/O failure during commit")
                }
            }
        )

        val tempDir = File(storageRoot, LocalPdfStorageManager.TEMP_DIR_NAME).apply { mkdirs() }
        val preExistingOrphan = File(tempDir, "old_killed_worker.part").apply {
            writeText("%PDF-old-orphan")
        }

        // Download 1 and Download 2 both start concurrently.
        val download1Part = storageManager.createTempPartFile("book-1", "v1").apply {
            writeBytes("%PDF-1.4\nbook-one".toByteArray())
        }
        val download2Part = storageManager.createTempPartFile("book-2", "v1").apply {
            writeBytes("%PDF-1.4\nbook-two".toByteArray())
        }

        // Download 1 runs startup/pre-transfer cleanup -> must remove orphan ONLY, never Download 2's active part.
        storageManager.cleanupOrphanedTempFiles()
        assertFalse(preExistingOrphan.exists())
        assertTrue(download1Part.exists())
        assertTrue(download2Part.exists())

        // Download 1 fails inside commit hook -> must release both sibling tmp and tempLifecycleLock.
        var threwExpected = false
        try {
            storageManager.commitVerifiedTempFile(download1Part, "book-1", "v1")
        } catch (_: Throwable) {
            threwExpected = true
        } finally {
            storageManager.releaseTempFile(download1Part)
        }
        assertTrue(threwExpected)

        // Failed commit must NOT leave any `.commit_*.tmp` in book-1/v1 directory.
        val book1Dir = storageManager.resolveCompletedFile("book-1", "v1").parentFile
        val book1Leftovers = book1Dir?.listFiles { file ->
            file.name.startsWith(".commit_") && file.name.endsWith(".tmp")
        }.orEmpty()
        assertTrue(book1Leftovers.isEmpty())

        // Cleanup on another thread must NOT deadlock (proving lock was released on exception),
        // must clean released download1Part, and must still keep active download2Part!
        val executor = Executors.newSingleThreadExecutor()
        val cleanupFinished = CountDownLatch(1)
        val completedCount = AtomicInteger(0)
        try {
            executor.execute {
                storageManager.cleanupOrphanedTempFiles()
                completedCount.incrementAndGet()
                cleanupFinished.countDown()
            }
            assertTrue("Cleanup deadlocked after exception", cleanupFinished.await(5, TimeUnit.SECONDS))
            assertEquals(1, completedCount.get())
            assertFalse("Released failed download1 .part should be deleted", download1Part.exists())
            assertTrue("Active download2 .part must stay protected", download2Part.exists())

            // Download 2 finishes committing cleanly.
            val book2Completed = storageManager.commitVerifiedTempFile(download2Part, "book-2", "v1")
            storageManager.releaseTempFile(download2Part)
            assertTrue(book2Completed.exists())
            assertTrue(book2Completed.readBytes().contentEquals("%PDF-1.4\nbook-two".toByteArray()))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `createTempPartFile removes path from protection and rethrows original SecurityException on non IO failure`() {
        val storageRoot = tempFolder.newFolder("part_security_failure_root")
        val attemptedFileRef = AtomicReference<File?>(null)
        val expectedFailure = SecurityException("Simulated security policy rejection")

        val storageManager = LocalPdfStorageManager(
            storageRoot = storageRoot,
            createNewFileAction = { candidate ->
                // Simulate a partial/empty .part created right before a non-IOException failure throws.
                candidate.writeText("%PDF-partial-before-security-error")
                attemptedFileRef.set(candidate)
                throw expectedFailure
            }
        )

        var thrown: Throwable? = null
        try {
            storageManager.createTempPartFile("file-sec", "v1")
        } catch (t: Throwable) {
            thrown = t
        }

        assertTrue("Original SecurityException must propagate untouched", thrown === expectedFailure)
        val attemptedFile = requireNotNull(attemptedFileRef.get())
        assertTrue(attemptedFile.exists())

        // Because the failed path must NOT remain registered in activeTempPaths, cleanup must delete it
        // cleanly and acquire/release tempLifecycleLock without deadlock.
        storageManager.cleanupOrphanedTempFiles()
        assertFalse("Failed .part path remained leaked in activeTempPaths", attemptedFile.exists())
        assertTrue(storageManager.listAllTempFiles().isEmpty())
    }
}
