package com.nascon.league.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** A queued offline action plus a human label for the Sync screen. */
data class PendingAction(val action: OfflineAction, val label: String)

/**
 * Local SQLite database (NFR-4).
 *  - cache: last good JSON response for each screen, keyed by request path.
 *  - pending_actions: offline scoring/roster actions waiting to sync (spec 5.14 format).
 */
class LocalDb(context: Context) : SQLiteOpenHelper(context, "league.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cache (key TEXT PRIMARY KEY, json TEXT NOT NULL, saved_at INTEGER NOT NULL)")
        db.execSQL(
            """CREATE TABLE pending_actions (
                action_id TEXT PRIMARY KEY,
                type TEXT NOT NULL,
                queued_at TEXT NOT NULL,
                base_version INTEGER NOT NULL,
                payload TEXT NOT NULL,
                label TEXT NOT NULL,
                created INTEGER NOT NULL)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun putCache(key: String, json: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("json", json)
            put("saved_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Returns (json, savedAtMillis) or null. */
    fun getCache(key: String): Pair<String, Long>? =
        readableDatabase.rawQuery("SELECT json, saved_at FROM cache WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null
        }

    fun addAction(item: PendingAction) {
        val values = ContentValues().apply {
            put("action_id", item.action.action_id)
            put("type", item.action.type)
            put("queued_at", item.action.queued_at)
            put("base_version", item.action.base_version)
            put("payload", AppJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), item.action.payload))
            put("label", item.label)
            put("created", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("pending_actions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun pendingActions(): List<PendingAction> =
        readableDatabase.rawQuery(
            "SELECT action_id, type, queued_at, base_version, payload, label FROM pending_actions ORDER BY created",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val action = OfflineAction(
                        action_id = c.getString(0),
                        type = c.getString(1),
                        queued_at = c.getString(2),
                        base_version = c.getInt(3),
                        payload = AppJson.parseToJsonElement(c.getString(4)),
                    )
                    add(PendingAction(action, c.getString(5)))
                }
            }
        }

    fun removeActions(ids: Collection<String>) {
        ids.forEach { writableDatabase.delete("pending_actions", "action_id = ?", arrayOf(it)) }
    }
}
