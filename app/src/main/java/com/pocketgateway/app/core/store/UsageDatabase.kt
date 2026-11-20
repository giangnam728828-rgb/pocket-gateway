package com.pocketgateway.app.core.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 自动任务用量统计的本地存储（SQLite）。
 *
 * 记录每次自动任务的渠道、类型、成功与否及时间，供「用量」页统计。
 * 仅使用 Android 内置 SQLite，不引入额外依赖。
 */
class UsageDatabase(context: Context) {

    data class TaskRecord(
        val taskType: String,
        val channelName: String,
        val message: String?,
        val success: Boolean,
        val createdAt: Long,
    )

    data class ChannelStats(
        val channelName: String,
        val total: Int,
        val success: Int,
    ) {
        val rate: Double get() = if (total == 0) 0.0 else success.toDouble() / total
    }

    private val helper = Helper(context.applicationContext)

    /** 记录一次任务结果。 */
    fun record(
        taskType: String,
        channelId: String,
        channelName: String,
        success: Boolean,
        message: String? = null,
        createdAt: Long = System.currentTimeMillis(),
    ) {
        val values = ContentValues().apply {
            put("task_type", taskType)
            put("channel_id", channelId)
            put("channel_name", channelName)
            put("success", if (success) 1 else 0)
            put("message", message)
            put("created_at", createdAt)
        }
        helper.writableDatabase.insert("usage_records", null, values)
    }

    /** 按渠道聚合，key 为渠道 ID。 */
    fun statsByChannel(): Map<String, ChannelStats> {
        val result = linkedMapOf<String, ChannelStats>()
        helper.readableDatabase.rawQuery(
            """
            SELECT channel_id, channel_name, COUNT(*) AS total,
                   SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS success
            FROM usage_records
            GROUP BY channel_id
            """.trimIndent(),
            null,
        ).use { c ->
            while (c.moveToNext()) {
                result[c.getString(0)] = ChannelStats(
                    channelName = c.getString(1) ?: "",
                    total = c.getInt(2),
                    success = c.getInt(3),
                )
            }
        }
        return result
    }

    /** 最近任务记录（按时间倒序）。 */
    fun recentTasks(limit: Int): List<TaskRecord> {
        val result = mutableListOf<TaskRecord>()
        helper.readableDatabase.rawQuery(
            "SELECT task_type, channel_name, message, success, created_at " +
                "FROM usage_records ORDER BY created_at DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                result += TaskRecord(
                    taskType = c.getString(0),
                    channelName = c.getString(1) ?: "",
                    message = c.getString(2),
                    success = c.getInt(3) == 1,
                    createdAt = c.getLong(4),
                )
            }
        }
        return result
    }

    /** 清理早于 [days] 天的记录，返回删除条数。 */
    fun pruneOld(days: Int): Int {
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        return helper.writableDatabase.delete(
            "usage_records",
            "created_at < ?",
            arrayOf(cutoff.toString()),
        )
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "usage.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE usage_records (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_type TEXT NOT NULL,
                    channel_id TEXT NOT NULL,
                    channel_name TEXT NOT NULL,
                    success INTEGER NOT NULL,
                    message TEXT,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX idx_usage_created ON usage_records(created_at)")
            db.execSQL("CREATE INDEX idx_usage_channel ON usage_records(channel_id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 结构简单，升级时重建即可（历史统计可丢弃）。
            db.execSQL("DROP TABLE IF EXISTS usage_records")
            onCreate(db)
        }
    }
}
