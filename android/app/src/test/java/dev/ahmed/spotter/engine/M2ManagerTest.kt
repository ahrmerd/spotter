package dev.ahmed.spotter.engine

import dev.ahmed.spotter.engine.model.DownloadState
import dev.ahmed.spotter.engine.model.EngineSettings
import dev.ahmed.spotter.engine.net.HttpClients
import dev.ahmed.spotter.engine.net.NetworkPolicy
import dev.ahmed.spotter.engine.queue.DownloadManager
import dev.ahmed.spotter.engine.store.InMemoryDownloadStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MutableNetworkPolicy : NetworkPolicy {
    @Volatile var online: Boolean = true
    @Volatile var wifi: Boolean = true
    override fun canDownload(): Boolean = wifi
    override fun isOnline(): Boolean = online
}

class M2ManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: RangeTestServer
    private lateinit var store: InMemoryDownloadStore
    private lateinit var supervisor: Job
    private lateinit var scope: CoroutineScope
    private lateinit var dir: File
    private lateinit var policy: MutableNetworkPolicy
    private lateinit var manager: DownloadManager

    @Before
    fun setUp() {
        server = RangeTestServer()
        store = InMemoryDownloadStore()
        supervisor = SupervisorJob()
        scope = CoroutineScope(supervisor + Dispatchers.IO)
        dir = tmp.newFolder("out")
        policy = MutableNetworkPolicy()
        manager = newManager()
    }

    @After
    fun tearDown() = runBlocking {
        manager.shutdown()
        supervisor.cancelAndJoin()
        server.close()
    }

    private fun newManager(): DownloadManager =
        DownloadManager(
            store = store,
            outputDir = dir,
            scope = scope,
            client = HttpClients.create(readTimeoutSeconds = 15, writeTimeoutSeconds = 15),
            networkPolicy = policy,
        )

    @Test
    fun wifiOnlyDropYieldsQueuedThenResumes() = runBlocking {
        val body = randomBytes(512 * 1024, seed = 21)
        server.body = body
        server.throttleBytesPerSec = 40 * 1024
        manager.setSettings(EngineSettings(wifiOnly = true, connectionsPerFile = 1))
        val id = manager.enqueue(server.url("/wifi.bin"))
        store.waitFor(id, 15_000) { it.state == DownloadState.RUNNING && it.downloaded > 0 }
        policy.wifi = false
        manager.notifyNetworkChanged()
        val parked = store.waitFor(id, 15_000) { it.state == DownloadState.QUEUED || it.state == DownloadState.PAUSED }
        assertEquals("Wi-Fi drop must yield QUEUED, not user PAUSED", DownloadState.QUEUED, parked.state)
        policy.wifi = true
        manager.notifyNetworkChanged()
        val done = store.waitTerminal(id, 60_000)
        assertEquals(DownloadState.DONE, done.state)
        assertEquals(sha256(body), sha256(File(done.outputPath)))
    }

    @Test
    fun airplaneYieldsQueuedThenResumesWithoutWifiOnly() = runBlocking {
        val body = randomBytes(256 * 1024, seed = 22)
        server.body = body
        server.throttleBytesPerSec = 30 * 1024
        val id = manager.enqueue(server.url("/air.bin"))
        store.waitFor(id, 15_000) { it.state == DownloadState.RUNNING && it.downloaded > 0 }
        policy.online = false
        manager.notifyNetworkChanged()
        val parked = store.waitFor(id, 15_000) { it.state == DownloadState.QUEUED || it.state == DownloadState.PAUSED }
        assertEquals(DownloadState.QUEUED, parked.state)
        policy.online = true
        manager.notifyNetworkChanged()
        val done = store.waitTerminal(id, 60_000)
        assertEquals(DownloadState.DONE, done.state)
        assertEquals(sha256(body), sha256(File(done.outputPath)))
    }

    @Test
    fun pauseAllPausesQueuedAndRunning() = runBlocking {
        val body = randomBytes(256 * 1024, seed = 23)
        server.body = body
        server.throttleBytesPerSec = 20 * 1024
        manager.setSettings(EngineSettings(maxConcurrentDownloads = 1, connectionsPerFile = 1))
        val a = manager.enqueue(server.url("/a.bin"), filename = "a.bin")
        val b = manager.enqueue(server.url("/b.bin"), filename = "b.bin")
        store.waitFor(a, 15_000) { it.state == DownloadState.RUNNING }
        store.waitFor(b, 5_000) { it.state == DownloadState.QUEUED }
        manager.pauseAll()
        store.waitFor(a, 15_000) { it.state == DownloadState.PAUSED }
        store.waitFor(b, 5_000) { it.state == DownloadState.PAUSED }
        Unit
    }

    @Test
    fun cancelRemovesRowAndPartial() = runBlocking {
        val body = randomBytes(512 * 1024, seed = 24)
        server.body = body
        server.throttleBytesPerSec = 20 * 1024
        val id = manager.enqueue(server.url("/c.bin"), filename = "c.bin")
        val running = store.waitFor(id, 15_000) { it.state == DownloadState.RUNNING && it.downloaded > 0 }
        val path = running.outputPath
        manager.cancel(id)
        assertNull(store.get(id))
        if (path.isNotEmpty()) {
            assertTrue(!File(path).exists() || File(path).length() == 0L)
            assertTrue(!File("$path.part").exists())
        }
    }

    @Test
    fun resumeOnBootPersists() = runBlocking {
        manager.setSettings(EngineSettings(resumeOnBoot = true, wifiOnly = true))
        val loaded = store.loadSettings()
        assertTrue(loaded.resumeOnBoot)
        assertTrue(loaded.wifiOnly)
        manager.setSettings(loaded.copy(resumeOnBoot = false))
        assertEquals(false, store.loadSettings().resumeOnBoot)
    }

    @Test
    fun m5SettingsSurviveReload() = runBlocking {
        val saved = EngineSettings(
            maxConcurrentDownloads = 2,
            connectionsPerFile = 6,
            maxTotalConnections = 12,
            speedLimitBps = 512 * 1024,
            wifiOnly = true,
            resumeOnBoot = true,
            defaultHlsQuality = "720",
            notificationsEnabled = false,
            theme = EngineSettings.THEME_LIGHT,
            saveFolderUri = "content://tree/primary:Movies",
            batteryPromptDone = true,
        )
        manager.setSettings(saved)
        val loaded = store.loadSettings()
        assertEquals(saved, loaded)
        manager.setSettings(loaded.copy(theme = EngineSettings.THEME_DARK, defaultHlsQuality = "1080"))
        val again = store.loadSettings()
        assertEquals(EngineSettings.THEME_DARK, again.theme)
        assertEquals("1080", again.defaultHlsQuality)
        assertEquals(512 * 1024L, again.speedLimitBps)
        assertEquals(false, again.notificationsEnabled)
    }

    @Test
    fun parkRunningLeavesQueuedNotPaused() = runBlocking {
        val body = randomBytes(256 * 1024, seed = 25)
        server.body = body
        server.throttleBytesPerSec = 20 * 1024
        val id = manager.enqueue(server.url("/park.bin"))
        store.waitFor(id, 15_000) { it.state == DownloadState.RUNNING }
        manager.parkRunning()
        val rec = store.get(id)!!
        assertTrue("system park must not mark user PAUSED, was ${rec.state}", rec.state != DownloadState.PAUSED)
        val done = store.waitTerminal(id, 60_000)
        assertEquals(DownloadState.DONE, done.state)
        assertEquals(sha256(body), sha256(File(done.outputPath)))
    }

    @Test
    fun retryRequeuesError() = runBlocking {
        store.insert(
            dev.ahmed.spotter.engine.model.DownloadRecord(
                url = "http://127.0.0.1/x",
                filename = "x.bin",
                state = DownloadState.ERROR,
                error = "boom",
                createdAt = 1,
            ),
        )
        val rec = store.getAll().single()
        manager.retry(rec.id)
        val after = store.waitFor(rec.id, 5_000) { it.state != DownloadState.ERROR || it.error != "boom" }
        assertTrue(after.state != DownloadState.ERROR || after.error != "boom")
        manager.pause(after.id)
    }
}
