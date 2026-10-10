package dev.ahmed.spotter.browser

import androidx.room.withTransaction
import dev.ahmed.spotter.engine.store.BookmarkEntity
import dev.ahmed.spotter.engine.store.HistoryEntity
import dev.ahmed.spotter.engine.store.SettingEntity
import dev.ahmed.spotter.engine.store.SpotterDatabase
import dev.ahmed.spotter.engine.store.TabEntity
import kotlinx.coroutines.flow.Flow

class BrowserStore(private val db: SpotterDatabase) {
    private val tabs = db.tabDao()
    private val bookmarks = db.bookmarkDao()
    private val history = db.historyDao()
    private val sniffer = db.snifferCacheDao()
    private val settings = db.settingDao()

    fun observeTabs(): Flow<List<TabEntity>> = tabs.observeAll()
    fun observeBookmarks(): Flow<List<BookmarkEntity>> = bookmarks.observeAll()
    fun observeHistory(limit: Int = 100): Flow<List<HistoryEntity>> = history.observeRecent(limit)

    suspend fun tabs(): List<TabEntity> = tabs.getAll()

    suspend fun addTab(url: String = "", title: String = "New tab", desktop: Boolean = false): TabEntity {
        val now = System.currentTimeMillis()
        val position = tabs.maxPosition() + 1
        val id = tabs.insert(
            TabEntity(
                url = url,
                title = title,
                desktop = desktop,
                position = position,
                createdAt = now,
            ),
        )
        return tabs.get(id) ?: TabEntity(id, url, title, desktop, position, now)
    }

    suspend fun updateTab(entity: TabEntity) {
        tabs.update(entity)
    }

    suspend fun deleteTab(id: Long) {
        db.withTransaction {
            sniffer.clearTab(id)
            tabs.delete(id)
        }
    }

    suspend fun ensureTab(): TabEntity {
        val existing = tabs.getAll()
        if (existing.isNotEmpty()) return existing.first()
        return addTab()
    }

    suspend fun getActiveTabId(): Long? = settings.get(KEY_ACTIVE_TAB)?.value?.toLongOrNull()

    suspend fun setActiveTabId(id: Long) {
        settings.upsert(SettingEntity(KEY_ACTIVE_TAB, id.toString()))
    }

    suspend fun addBookmark(url: String, title: String) {
        if (url.isBlank()) return
        if (bookmarks.byUrl(url) != null) return
        bookmarks.insert(
            BookmarkEntity(url = url, title = title.ifBlank { url }, createdAt = System.currentTimeMillis()),
        )
    }

    suspend fun removeBookmark(url: String) {
        bookmarks.deleteUrl(url)
    }

    suspend fun isBookmarked(url: String): Boolean = bookmarks.byUrl(url) != null

    suspend fun addHistory(url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        history.insert(
            HistoryEntity(url = url, title = title.ifBlank { url }, visitedAt = System.currentTimeMillis()),
        )
        history.keepLatest(HISTORY_KEEP)
    }

    suspend fun saveSniffer(tabId: Long, items: List<DetectedItem>) {
        db.withTransaction {
            sniffer.clearTab(tabId)
            if (items.isNotEmpty()) {
                sniffer.upsertAll(items.map { it.toCacheEntity(tabId) })
            }
        }
    }

    suspend fun loadSniffer(tabId: Long): List<DetectedItem> =
        sniffer.forTab(tabId).map { it.toDetectedItem() }

    companion object {
        const val KEY_ACTIVE_TAB = "active_tab_id"
        const val HISTORY_KEEP = 500
    }
}
