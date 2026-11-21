package com.pocketgateway.app.core.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TABLE_AUTO_TASKS = "auto_tasks"
private const val TABLE_META = "meta"
private const val DB_NAME = "usage_db"
private const val DB_VERSION = 1

/**
 * 用量记录数据库（原生 SQLite，无 Room）。
 *
 * 表结构：
 *   auto_tasks(id, channel_id, channel_name, task_type, success, message, created_at)
 *   meta(key, value)
 *
 * 所有读写均在 [Dispatchers.IO] 上执行，避免阻塞主线程。
 */
class UsageDatabase(context: Context) {

    private val helper = Helper(context.applicationContext)

    // ---- 对外写入 / 查询 ----

    /** 记录一次自动任务结果。 */
    suspend fun recordAutoTask(
        channelId: String,
        channelName: String,
        taskType: String,
        success: Boolean,
        message: String?,
    ) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("channel_id", channelId)
            put("channel_name", channelName)
            put("task_type", taskType)
            put("success", if (success) 1 else 0)
            put("message", message ?: "")
            put("created_at", System.currentTimeMillis())
        }
        helper.writableDatabase.insert(TABLE_AUTO_TASKS, null, values)
        Unit
    }

    /** 最近 N 条任务记录（按时间倒序）。 */
    suspend fun recentTasks(limit: Int = 50): List<TaskRecord> = withContext(Dispatchers.IO) {
        val list = mutableListOf<TaskRecord>()
        helper.readableDatabase.query(
            TABLE_AUTO_TASKS,
            arrayOf("id", "channel_id", "channel_name", "task_type", "success", "message", "created_at"),
            null, null, null, null, "created_at DESC", limit.toString(),
        ).use { c ->
            while (c.moveToNext()) {
                list += TaskRecord(
                    id = c.getLong(0),
                    channelId = c.getString(1),
                    channelName = c.getString(2),
                    taskType = c.getString(3),
                    success = c.getInt(4) == 1,
                    message = c.getString(5),
                    createdAt = c.getLong(6),
                )
            }
        }
        list
    }

    /** 按渠道统计总任务数、成功数、成功率；key 为渠道 ID。 */
    suspend fun statsByChannel(): Map<String, ChannelStats> = withContext(Dispatchers.IO) {
        val map = linkedMapOf<String, ChannelStats>()
        helper.readableDatabase.rawQuery(
            """
            SELECT channel_id, channel_name, COUNT(*) AS total,
                   SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS success
            FROM $TABLE_AUTO_TASKS
            GROUP BY channel_id
            ORDER BY total DESC
            """.trimIndent(),
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val channelId = c.getString(0)
                val channelName = c.getString(1) ?: ""
                val total = c.getInt(2)
                val success = c.getInt(3)
                val rate = if (total == 0) 0f else success.toFloat() / total.toFloat()
                map[channelId] = ChannelStats(channelId, channelName, total, success, rate)
            }
        }
        map
    }

    /** 按任务类型统计（每日签到 / 任务领取 / 自动完成…）。 */
    suspend fun statsByType(): List<TaskTypeStats> = withContext(Dispatchers.IO) {
        val list = mutableListOf<TaskTypeStats>()
        helper.readableDatabase.rawQuery(
            """
            SELECT task_type, COUNT(*) AS total,
                   SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS success
            FROM $TABLE_AUTO_TASKS
            GROUP BY task_type
            ORDER BY total DESC
            """.trimIndent(),
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val taskType = c.getString(0) ?: ""
                val total = c.getInt(1)
                val success = c.getInt(2)
                list += TaskTypeStats(taskType, total, success)
            }
        }
        list
    }

    /** 清理早于 [days] 天的记录，返回删除条数。 */
    suspend fun pruneOld(days: Int = 30): Int = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        helper.writableDatabase.delete(TABLE_AUTO_TASKS, "created_at < ?", arrayOf(cutoff.toString()))
    }

    // ---- 元数据 ----

    fun getMeta(key: String): String? = helper.readableDatabase.query(
        TABLE_META, arrayOf("value"), "key=?", arrayOf(key), null, null, null,
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun setMeta(key: String, value: String) {
        val cv = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        helper.writableDatabase.insertWithOnConflict(
            TABLE_META, null, cv, SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun close() = helper.close()

    // ---- 数据模型 ----

    data class TaskRecord(
        val id: Long,
        val channelId: String,
        val channelName: String,
        val taskType: String,
        val success: Boolean,
        val message: String?,
        val createdAt: Long,
    )

    data class ChannelStats(
        val channelId: String,
        val channelName: String,
        val total: Int,
        val success: Int,
        val rate: Float,
    )

    data class TaskTypeStats(
        val taskType: String,
        val total: Int,
        val success: Int,
    ) {
        val rate: Float get() = if (total == 0) 0f else success.toFloat() / total.toFloat()
    }

    // ---- 建表 ----

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_AUTO_TASKS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    channel_id TEXT NOT NULL,
                    channel_name TEXT NOT NULL,
                    task_type TEXT NOT NULL,
                    success INTEGER NOT NULL,
                    message TEXT,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_created ON $TABLE_AUTO_TASKS(created_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_channel ON $TABLE_AUTO_TASKS(channel_id)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_META (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 结构简单，升级时重建即可（历史统计可丢弃）。
            db.execSQL("DROP TABLE IF EXISTS $TABLE_AUTO_TASKS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_META")
            onCreate(db)
        }
    }
}
