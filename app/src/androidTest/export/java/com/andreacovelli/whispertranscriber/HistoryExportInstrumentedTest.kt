package com.andreacovelli.whispertranscriber

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.RoomHistoryRepository
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.history.HistoryExportFormat
import com.andreacovelli.whispertranscriber.history.buildHistoryExport
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against the debug-signed minifiedSmoke build, with R8 enabled. */
@RunWith(AndroidJUnit4::class)
class HistoryExportInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomHistoryRepository
    private lateinit var directory: File
    private val pausePages = AtomicBoolean()
    private val pages = AtomicInteger()
    private val secondPage = CountDownLatch(1)
    private val continueExport = CountDownLatch(1)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "export-test-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCallback({ sql, _ ->
                if (pausePages.get() && sql.contains("ORDER BY timestamp DESC, id DESC LIMIT") &&
                    pages.incrementAndGet() == 2
                ) {
                    secondPage.countDown()
                    check(continueExport.await(10, TimeUnit.SECONDS))
                }
            }, Runnable::run)
            .build()
        repository = RoomHistoryRepository(db, db.transcriptionHistoryDao(), db.transcriptionJobDao())
    }

    @After
    fun tearDown() {
        continueExport.countDown()
        db.close()
        directory.deleteRecursively()
    }

    @Test
    fun jsonExport_hasExactPublicKeysAndValuesInBothEntryPoints() = runBlocking {
        val item = item(42).copy(model = "transcriber", summary = "summary", summaryModel = "summarizer")
        repository.insert(item)
        val destination = ByteArrayOutputStream()
        repository.exportHistory(HistoryExportFormat.JSON, directory) { destination }
        val expectedKeys =
            setOf("id", "timestamp", "displayName", "language", "model", "summaryModel", "text", "summary")
        listOf(
            destination.toString("UTF-8"),
            buildHistoryExport(listOf(item), HistoryExportFormat.JSON),
        ).forEach { text ->
            val record = JSONArray(text).getJSONObject(0)
            assertEquals(expectedKeys, record.keys().asSequence().toSet())
            assertEquals(42, record.getInt("id"))
            assertEquals("1970-01-01T00:00:01Z", record.getString("timestamp"))
            assertEquals(item.text, record.getString("text"))
            assertEquals("summary", record.getString("summary"))
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun export_keepsOneSnapshotDuringInsertsDeletesAndEqualTimestamps() = runBlocking {
        (1..205).forEach { repository.insert(item(it)) }
        pausePages.set(true)
        val destination = ByteArrayOutputStream()
        val export =
            async(Dispatchers.IO) { repository.exportHistory(HistoryExportFormat.JSON, directory) { destination } }
        assertTrue(secondPage.await(10, TimeUnit.SECONDS))
        val writeStarted = CountDownLatch(1)
        val mutation = async(Dispatchers.IO) {
            writeStarted.countDown()
            repository.deleteById(150)
            repository.insert(item(999))
        }
        assertTrue(writeStarted.await(10, TimeUnit.SECONDS))
        continueExport.countDown()
        export.await()
        mutation.await()
        val output = JSONArray(destination.toString("UTF-8"))
        assertEquals((205 downTo 1).toList(), (0 until output.length()).map { output.getJSONObject(it).getInt("id") })
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun destinationIsOpenedAfterTransactionAndFailuresRemoveTemporaryFiles() = runBlocking {
        repository.insert(item(1))
        try {
            repository.exportHistory(HistoryExportFormat.JSON, directory) {
                runBlocking { repository.deleteById(1) }
                throw IOException("destination unavailable")
            }
            error("Expected destination failure")
        } catch (_: IOException) {
            assertFalse(repository.hasHistory())
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun cancelledCopyAndEmptyHistoryNeverLeaveTemporaryFiles() = runBlocking {
        var opened = false
        try {
            repository.exportHistory(HistoryExportFormat.JSON, directory) {
                opened = true
                ByteArrayOutputStream()
            }
            error("Empty history must be reported")
        } catch (_: IllegalArgumentException) {
            assertFalse(opened)
        }
        repository.insert(item(1))
        var closed = false
        try {
            repository.exportHistory(HistoryExportFormat.JSON, directory) {
                object : OutputStream() {
                    override fun write(value: Int): Unit = throw CancellationException("cancel export")
                    override fun close() {
                        closed = true
                    }
                }
            }
            error("Expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(closed)
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    private fun item(id: Int) = TranscriptionHistory(
        id = id,
        timestamp = 1_000,
        text = "Transcript $id",
        language = "en",
        displayName = "audio.wav",
    )
}
