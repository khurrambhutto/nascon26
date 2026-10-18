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
class LocalDb(context: Context) : SQLiteOpenHelper(context, "league.db", null, 2) {

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
                created INTEGER NOT NULL,
                owner TEXT NOT NULL DEFAULT '')"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE pending_actions ADD COLUMN owner TEXT NOT NULL DEFAULT ''")
    }

    /** Older installs had one queue. Assign it to the signed-in account once. */
    fun adoptLegacy(owner: String) {
        writableDatabase.execSQL("UPDATE pending_actions SET owner = ? WHERE owner = ''", arrayOf(owner))
        writableDatabase.execSQL("UPDATE cache SET key = ? || '/' || key WHERE instr(key, '|') = 0", arrayOf(owner))
    }

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

    fun addAction(item: PendingAction, owner: String) {
        val values = ContentValues().apply {
            put("action_id", item.action.action_id)
            put("type", item.action.type)
            put("queued_at", item.action.queued_at)
            put("base_version", item.action.base_version)
            put("payload", AppJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), item.action.payload))
            put("label", item.label)
            put("created", System.currentTimeMillis())
            put("owner", owner)
        }
        writableDatabase.insertWithOnConflict("pending_actions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun pendingActions(owner: String): List<PendingAction> =
        readableDatabase.rawQuery(
            "SELECT action_id, type, queued_at, base_version, payload, label FROM pending_actions WHERE owner = ? ORDER BY created, rowid",
            arrayOf(owner),
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

    fun pendingOwners(): List<String> = readableDatabase.rawQuery(
        "SELECT DISTINCT owner FROM pending_actions WHERE owner != '' ORDER BY owner", null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun pendingCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM pending_actions", null).use {
        it.moveToFirst(); it.getInt(0)
    }
}
