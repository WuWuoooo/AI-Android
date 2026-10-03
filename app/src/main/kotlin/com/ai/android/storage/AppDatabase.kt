package com.ai.android.storage

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

/**
 * Room 数据库（骨架版：通用键值表）。
 * 后续可扩展会话表 / 消息表 / 任务表。
 */
@Entity(tableName = "kv")
data class KvEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface KvDao {

    @Query("SELECT * FROM kv WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): KvEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: KvEntity)

    @Query("SELECT * FROM kv ORDER BY updatedAt DESC")
    suspend fun all(): List<KvEntity>

    @Query("DELETE FROM kv WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Database(entities = [KvEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun kvDao(): KvDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ai_android.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
