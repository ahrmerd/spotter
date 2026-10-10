package dev.ahmed.spotter.engine.store

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ahmed.spotter.engine.model.DownloadRecord
import dev.ahmed.spotter.engine.model.DownloadState
import dev.ahmed.spotter.engine.model.PartRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomDownloadStoreTest {
    private lateinit var db: SpotterDatabase
    private lateinit var store: RoomDownloadStore

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, SpotterDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomDownloadStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndGet() = runBlocking {
        val id = store.insert(
            DownloadRecord(
                url = "https://example.com/a.bin",
                filename = "a.bin",
                state = DownloadState.QUEUED,
                size = 100,
                createdAt = 42,
            ),
        )
        val rec = store.get(id)!!
        assertEquals("https://example.com/a.bin", rec.url)
        assertEquals("a.bin", rec.filename)
        assertEquals(DownloadState.QUEUED, rec.state)
        assertEquals(100L, rec.size)
        assertEquals(42L, rec.createdAt)
        assertEquals(1, store.getAll().size)
    }

    @Test
    fun update() = runBlocking {
        val id = store.insert(
            DownloadRecord(url = "https://example.com/b.bin", filename = "b.bin", createdAt = 1),
        )
        val rec = store.get(id)!!
        store.update(rec.copy(state = DownloadState.RUNNING, downloaded = 50, mimeType = "video/mp4"))
        val after = store.get(id)!!
        assertEquals(DownloadState.RUNNING, after.state)
        assertEquals(50L, after.downloaded)
        assertEquals("video/mp4", after.mimeType)
    }

    @Test
    fun replacePartsIsTransactional() = runBlocking {
        val id = store.insert(
            DownloadRecord(url = "https://example.com/c.bin", filename = "c.bin", createdAt = 1),
        )
        store.replaceParts(
            id,
            listOf(
                PartRecord(downloadId = id, start = 0, current = 10, end = 99),
                PartRecord(downloadId = id, start = 100, current = 100, end = 199),
            ),
        )
        assertEquals(2, store.getParts(id).size)
        store.replaceParts(
            id,
            listOf(PartRecord(downloadId = id, start = 0, current = 50, end = 199)),
        )
        val parts = store.getParts(id)
        assertEquals(1, parts.size)
        assertEquals(0L, parts[0].start)
        assertEquals(50L, parts[0].current)
        assertEquals(199L, parts[0].end)
        store.replaceParts(id, emptyList())
        assertTrue(store.getParts(id).isEmpty())
    }

    @Test
    fun observeAllEmitsInserts() = runBlocking {
        val id = store.insert(
            DownloadRecord(url = "https://example.com/d.bin", filename = "d.bin", createdAt = 7),
        )
        val list = store.observeAll().first { it.any { rec -> rec.id == id } }
        assertEquals(1, list.size)
        assertEquals("d.bin", list[0].filename)
    }

    @Test
    fun deleteRemovesParts() = runBlocking {
        val id = store.insert(
            DownloadRecord(url = "https://example.com/e.bin", filename = "e.bin", createdAt = 1),
        )
        store.replaceParts(id, listOf(PartRecord(downloadId = id, start = 0, current = 0, end = 10)))
        store.delete(id)
        assertNull(store.get(id))
        assertTrue(store.getParts(id).isEmpty())
    }

    @Test
    fun secretHeadersAreNotWrittenToTheDatabase() = runBlocking {
        val id = store.insert(
            DownloadRecord(
                url = "https://example.com/s.bin",
                headers = mapOf("Cookie" to "sid=SECRET", "Authorization" to "Bearer SECRET", "Referer" to "https://example.com/"),
                state = DownloadState.QUEUED,
            ),
        )
        val rec = store.get(id)!!
        assertEquals(setOf("Referer"), rec.headers.keys)
        val raw = db.openHelper.writableDatabase.query("SELECT headersJson FROM downloads WHERE id=$id").use { c ->
            c.moveToFirst(); c.getString(0)
        }
        assertTrue(raw, !raw.contains("SECRET"))
    }
}
