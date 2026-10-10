package dev.ahmed.spotter.engine.store

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [DownloadEntity::class, PartEntity::class, SettingEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SpotterDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun partDao(): PartDao
    abstract fun settingDao(): SettingDao
}
