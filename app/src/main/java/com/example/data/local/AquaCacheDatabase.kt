package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "device_action_logs")
data class DeviceActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val userId: String,
    val toolName: String,
    val status: String,
    val detail: String,
    val timestampMs: Long = System.currentTimeMillis()
)

@Dao
interface DeviceActionLogDao {
    @Query("SELECT * FROM device_action_logs WHERE userId = :userId ORDER BY timestampMs DESC LIMIT 50")
    fun observeLogsForUser(userId: String): Flow<List<DeviceActionLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(entry: DeviceActionLogEntity)

    @Query("DELETE FROM device_action_logs WHERE userId = :userId")
    suspend fun clearForUser(userId: String)
}

@Database(entities = [DeviceActionLogEntity::class], version = 1, exportSchema = false)
abstract class AquaCacheDatabase : RoomDatabase() {
    abstract fun deviceActionLogDao(): DeviceActionLogDao

    companion object {
        @Volatile
        private var INSTANCE: AquaCacheDatabase? = null

        fun getInstance(context: Context): AquaCacheDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AquaCacheDatabase::class.java,
                    "aqua_local_cache.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build().also { INSTANCE = it }
            }
        }
    }
}
