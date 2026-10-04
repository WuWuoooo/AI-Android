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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

    @Query("SELECT * FROM kv WHERE `key` LIKE :prefix || '%'")
    suspend fun byPrefix(prefix: String): List<KvEntity>
}

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val payload: String,
    val projectId: String = "",
)

/**
 * ⭐ 不含 payload 的元信息投影。
 * Android 的 CursorWindow 单次读取上限约 2MB（Android 9 及以前约 1MB），
 * 当某行 payload 超过该上限时，`SELECT *` 会抛 SQLiteBlobTooBigException，
 * 导致整个对话列表加载失败。列表加载一律用本投影，不查 payload。
 */
data class ConversationMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val projectId: String,
)

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConversationEntity)

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun all(): List<ConversationEntity>

    /**
     * ⭐ 只查元信息，不查 payload（避免 CursorWindow 限制拖垮整个查询）。
     */
    @Query("SELECT id, title, createdAt, updatedAt, projectId FROM conversations ORDER BY updatedAt DESC")
    suspend fun allMeta(): List<ConversationMeta>

    /** ⭐ payload 的字符数（SQLite 的 length() 对 TEXT 返回字符数）。 */
    @Query("SELECT length(payload) FROM conversations WHERE id = :id")
    suspend fun payloadLength(id: String): Int?

    /**
     * ⭐ 分块读取 payload（按字符切片，避免单次超过 CursorWindow 上限）。
     * @param start 起始位置（SQLite 的 substr 从 1 开始计数）
     * @param len   读取字符数
     */
    @Query("SELECT substr(payload, :start, :len) FROM conversations WHERE id = :id")
    suspend fun payloadChunk(id: String, start: Int, len: Int): String?

    /** 小对话一次性读取 payload */
    @Query("SELECT payload FROM conversations WHERE id = :id")
    suspend fun payload(id: String): String?

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun get(id: String): ConversationEntity?

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(
    entities = [KvEntity::class, ConversationEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun kvDao(): KvDao
    abstract fun conversationDao(): ConversationDao

    companion object {

        /** v1 → v2：新增 conversations 表 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversations` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `payload` TEXT NOT NULL,
                        `projectId` TEXT NOT NULL DEFAULT '',
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v2 → v3：仅 Kotlin 数据类字段顺序变化（payload / projectId 互换位置），
         * SQLite 层列名、类型、约束完全相同，因此无需任何 SQL。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // no-op
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ai_android.db"
                )
                    // ⚠️ 绝不使用 fallbackToDestructiveMigration()，
                    // 否则任何 schema 变化都会清空用户数据
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}