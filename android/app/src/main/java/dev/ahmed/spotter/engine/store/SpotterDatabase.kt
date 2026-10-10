package dev.ahmed.spotter.engine.store

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DownloadEntity::class,
        PartEntity::class,
        SettingEntity::class,
        TabEntity::class,
        BookmarkEntity::class,
        HistoryEntity::class,
        SnifferCacheEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class SpotterDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun partDao(): PartDao
    abstract fun settingDao(): SettingDao
    abstract fun tabDao(): TabDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun snifferCacheDao(): SnifferCacheDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `browser_tabs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `url` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `desktop` INTEGER NOT NULL,
                        `position` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )""".trimIndent(),
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `bookmarks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `url` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )""".trimIndent(),
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `url` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `visitedAt` INTEGER NOT NULL
                    )""".trimIndent(),
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `sniffer_cache` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `tabId` INTEGER NOT NULL,
                        `mediaUrl` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `size` INTEGER,
                        `headersJson` TEXT NOT NULL,
                        `pageUrl` TEXT NOT NULL,
                        `pageTitle` TEXT NOT NULL,
                        `suggestedName` TEXT NOT NULL,
                        `detectedAt` INTEGER NOT NULL,
                        `fromPlay` INTEGER NOT NULL
                    )""".trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sniffer_cache_tabId_mediaUrl` ON `sniffer_cache` (`tabId`, `mediaUrl`)",
                )
            }
        }
    }
}
