package com.example.ecgmonitor.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "ecg_sessions")
data class EcgSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val durationSeconds: Int,
    val sampleRateHz: Int = 125,
    val samples: ByteArray,
    val heartRateBpm: Int?,
    val note: String = ""
)

@Dao
interface EcgSessionDao {
    @Insert suspend fun insert(session: EcgSession)
    @Query("SELECT * FROM ecg_sessions ORDER BY startedAt DESC") fun observeAll(): Flow<List<EcgSession>>
}

@Database(entities = [EcgSession::class], version = 1, exportSchema = false)
abstract class EcgDatabase : RoomDatabase() {
    abstract fun sessionDao(): EcgSessionDao
    companion object {
        fun create(context: Context) = Room.databaseBuilder(
            context.applicationContext, EcgDatabase::class.java, "ecg-monitor.db"
        ).build()
    }
}
