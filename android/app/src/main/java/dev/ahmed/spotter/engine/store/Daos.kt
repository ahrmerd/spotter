package dev.ahmed.spotter.engine.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    @Update
    suspend fun update(entity: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    suspend fun getAll(): List<DownloadEntity>

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PartDao {
    @Insert
    suspend fun insertAll(parts: List<PartEntity>)

    @Query("SELECT * FROM parts WHERE downloadId = :downloadId ORDER BY startByte ASC")
    suspend fun getForDownload(downloadId: Long): List<PartEntity>

    @Query("DELETE FROM parts WHERE downloadId = :downloadId")
    suspend fun deleteForDownload(downloadId: Long)
}

@Dao
interface SettingDao {
    @Query("SELECT * FROM settings WHERE `key` = :key")
    suspend fun get(key: String): SettingEntity?

    @Query("SELECT * FROM settings")
    suspend fun getAll(): List<SettingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SettingEntity)
}
