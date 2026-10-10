package dev.ahmed.spotter.engine.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TabDao {
    @Insert
    suspend fun insert(entity: TabEntity): Long

    @Update
    suspend fun update(entity: TabEntity)

    @Query("SELECT * FROM browser_tabs ORDER BY position ASC, id ASC")
    suspend fun getAll(): List<TabEntity>

    @Query("SELECT * FROM browser_tabs ORDER BY position ASC, id ASC")
    fun observeAll(): Flow<List<TabEntity>>

    @Query("SELECT * FROM browser_tabs WHERE id = :id")
    suspend fun get(id: Long): TabEntity?

    @Query("SELECT COALESCE(MAX(position), -1) FROM browser_tabs")
    suspend fun maxPosition(): Int

    @Query("DELETE FROM browser_tabs WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface BookmarkDao {
    @Insert
    suspend fun insert(entity: BookmarkEntity): Long

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")
    suspend fun byUrl(url: String): BookmarkEntity?

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteUrl(url: String)
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entity: HistoryEntity): Long

    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntity>>

    @Query("DELETE FROM history WHERE visitedAt < :before")
    suspend fun prune(before: Long)

    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY visitedAt DESC LIMIT :keep)")
    suspend fun keepLatest(keep: Int)
}

@Dao
interface SnifferCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SnifferCacheEntity>)

    @Query("SELECT * FROM sniffer_cache WHERE tabId = :tabId")
    suspend fun forTab(tabId: Long): List<SnifferCacheEntity>

    @Query("DELETE FROM sniffer_cache WHERE tabId = :tabId")
    suspend fun clearTab(tabId: Long)

    @Query("DELETE FROM sniffer_cache")
    suspend fun clearAll()
}
