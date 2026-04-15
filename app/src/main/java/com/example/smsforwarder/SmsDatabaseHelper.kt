package com.example.smsforwarder

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SmsDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "sms_forwarder.db"
        private const val DATABASE_VERSION = 2  // Увеличили версию для добавления новой таблицы

        // Таблица для SMS сообщений
        private const val TABLE_SMS = "sms_messages"
        private const val COLUMN_ID = "id"
        private const val COLUMN_SENDER = "sender"
        private const val COLUMN_MESSAGE = "message"
        private const val COLUMN_TIMESTAMP = "timestamp"
        private const val COLUMN_IS_READ = "is_read"
        private const val COLUMN_IS_FORWARDED = "is_forwarded"

        // Таблица для очереди отправки
        private const val TABLE_QUEUE = "send_queue"
        private const val QUEUE_COLUMN_ID = "id"
        private const val QUEUE_COLUMN_SMS_ID = "sms_id"
        private const val QUEUE_COLUMN_SENDER = "sender"
        private const val QUEUE_COLUMN_MESSAGE = "message"
        private const val QUEUE_COLUMN_TIMESTAMP = "timestamp"
        private const val QUEUE_COLUMN_RETRY_COUNT = "retry_count"
        private const val QUEUE_COLUMN_CREATED_AT = "created_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Таблица SMS
        val createSmsTable = """
            CREATE TABLE $TABLE_SMS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_SENDER TEXT NOT NULL,
                $COLUMN_MESSAGE TEXT NOT NULL,
                $COLUMN_TIMESTAMP LONG NOT NULL,
                $COLUMN_IS_READ INTEGER DEFAULT 0,
                $COLUMN_IS_FORWARDED INTEGER DEFAULT 0
            )
        """.trimIndent()
        db.execSQL(createSmsTable)

        // Таблица очереди
        val createQueueTable = """
            CREATE TABLE $TABLE_QUEUE (
                $QUEUE_COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $QUEUE_COLUMN_SMS_ID INTEGER,
                $QUEUE_COLUMN_SENDER TEXT NOT NULL,
                $QUEUE_COLUMN_MESSAGE TEXT NOT NULL,
                $QUEUE_COLUMN_TIMESTAMP LONG NOT NULL,
                $QUEUE_COLUMN_RETRY_COUNT INTEGER DEFAULT 0,
                $QUEUE_COLUMN_CREATED_AT LONG NOT NULL
            )
        """.trimIndent()
        db.execSQL(createQueueTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Добавляем таблицу очереди при обновлении
            val createQueueTable = """
                CREATE TABLE IF NOT EXISTS $TABLE_QUEUE (
                    $QUEUE_COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $QUEUE_COLUMN_SMS_ID INTEGER,
                    $QUEUE_COLUMN_SENDER TEXT NOT NULL,
                    $QUEUE_COLUMN_MESSAGE TEXT NOT NULL,
                    $QUEUE_COLUMN_TIMESTAMP LONG NOT NULL,
                    $QUEUE_COLUMN_RETRY_COUNT INTEGER DEFAULT 0,
                    $QUEUE_COLUMN_CREATED_AT LONG NOT NULL
                )
            """.trimIndent()
            db.execSQL(createQueueTable)
        }
    }

    // ============ Методы для SMS ============

    fun saveSms(sender: String, message: String, timestamp: Long): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_SENDER, sender)
            put(COLUMN_MESSAGE, message)
            put(COLUMN_TIMESTAMP, timestamp)
            put(COLUMN_IS_READ, 0)
            put(COLUMN_IS_FORWARDED, 0)
        }
        return db.insert(TABLE_SMS, null, values)
    }

    fun markAsForwarded(id: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_IS_FORWARDED, 1)
        }
        db.update(TABLE_SMS, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }

    fun getAllSms(): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SMS,
            null,
            null,
            null,
            null,
            null,
            "$COLUMN_TIMESTAMP DESC"
        )

        while (cursor.moveToNext()) {
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID))
            val sender = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SENDER))
            val message = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MESSAGE))
            val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP))
            val isRead = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_IS_READ)) == 1
            val isForwarded = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_IS_FORWARDED)) == 1

            messages.add(SmsMessage(id, sender, message, timestamp, isRead, isForwarded))
        }
        cursor.close()
        return messages
    }

    fun getUnreadCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_SMS WHERE $COLUMN_IS_READ = 0", null)
        cursor.moveToFirst()
        val count = cursor.getInt(0)
        cursor.close()
        return count
    }

    fun markAsRead(id: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_IS_READ, 1)
        }
        db.update(TABLE_SMS, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }

    // ============ Методы для очереди ============

    fun addToQueue(smsId: Long, sender: String, message: String, timestamp: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(QUEUE_COLUMN_SMS_ID, smsId)
            put(QUEUE_COLUMN_SENDER, sender)
            put(QUEUE_COLUMN_MESSAGE, message)
            put(QUEUE_COLUMN_TIMESTAMP, timestamp)
            put(QUEUE_COLUMN_CREATED_AT, System.currentTimeMillis())
            put(QUEUE_COLUMN_RETRY_COUNT, 0)
        }
        db.insert(TABLE_QUEUE, null, values)
    }

    fun getPendingMessages(): List<QueueMessage> {
        val messages = mutableListOf<QueueMessage>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_QUEUE,
            null,
            null,
            null,
            null,
            null,
            "$QUEUE_COLUMN_CREATED_AT ASC"
        )

        while (cursor.moveToNext()) {
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(QUEUE_COLUMN_ID))
            val smsId = cursor.getLong(cursor.getColumnIndexOrThrow(QUEUE_COLUMN_SMS_ID))
            val sender = cursor.getString(cursor.getColumnIndexOrThrow(QUEUE_COLUMN_SENDER))
            val message = cursor.getString(cursor.getColumnIndexOrThrow(QUEUE_COLUMN_MESSAGE))
            val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(QUEUE_COLUMN_TIMESTAMP))
            val retryCount = cursor.getInt(cursor.getColumnIndexOrThrow(QUEUE_COLUMN_RETRY_COUNT))
            val createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(QUEUE_COLUMN_CREATED_AT))

            messages.add(QueueMessage(id, smsId, sender, message, timestamp, retryCount, createdAt))
        }
        cursor.close()
        return messages
    }

    fun removeFromQueue(queueId: Long) {
        val db = writableDatabase
        db.delete(TABLE_QUEUE, "$QUEUE_COLUMN_ID = ?", arrayOf(queueId.toString()))
    }

    fun incrementRetryCount(queueId: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(QUEUE_COLUMN_RETRY_COUNT, "retry_count + 1")
        }
        db.update(TABLE_QUEUE, values, "$QUEUE_COLUMN_ID = ?", arrayOf(queueId.toString()))
    }

    fun getQueueCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_QUEUE", null)
        cursor.moveToFirst()
        val count = cursor.getInt(0)
        cursor.close()
        return count
    }

    fun clearQueue() {
        val db = writableDatabase
        db.delete(TABLE_QUEUE, null, null)
    }
}

// ===== КЛАССЫ ДАННЫХ =====
data class SmsMessage(
    val id: Long,
    val sender: String,
    val message: String,
    val timestamp: Long,
    val isRead: Boolean,
    val isForwarded: Boolean
)

data class QueueMessage(
    val id: Long,
    val smsId: Long,
    val sender: String,
    val message: String,
    val timestamp: Long,
    val retryCount: Int,
    val createdAt: Long
)