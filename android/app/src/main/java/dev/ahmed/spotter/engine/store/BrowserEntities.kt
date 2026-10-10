package dev.ahmed.spotter.engine.store

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "browser_tabs")
data class TabEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val desktop: Boolean,
    val position: Int,
    val createdAt: Long,
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val createdAt: Long,
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAt: Long,
)

@Entity(
    tableName = "sniffer_cache",
    indices = [Index(value = ["tabId", "mediaUrl"], unique = true)],
)
data class SnifferCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tabId: Long,
    val mediaUrl: String,
    val kind: String,
    val type: String,
    val size: Long?,
    val headersJson: String,
    val pageUrl: String,
    val pageTitle: String,
    val suggestedName: String,
    val detectedAt: Long,
    val fromPlay: Boolean,
)
