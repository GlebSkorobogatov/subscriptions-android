package com.glebskorobogatov.subscriptiontracker.data.room

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.room.util.TableInfo
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteConnection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A real read-only SQLite handle: no immutable URI (which would ignore WAL), helper, checkpoint,
 * recovery or migration. SQLite sees committed WAL frames; a hot rollback journal requiring writes
 * must fail rather than be recovered by diagnostics. The journal lock serializes this app's openers;
 * schema/data version tokens detect external writes during metadata inspection. Android rewrites
 * SQL BEGIN into an exclusive transaction on older APIs, so diagnostics never issue BEGIN.
 */
internal object SchemaPreflight {
    @android.annotation.SuppressLint("RestrictedApi") // Pinned SQLite 2.6.1 adapter; wraps the already read-only native handle, never opens another connection.
    fun validate(context: Context, file: File, currentValidator: (SQLiteConnection) -> Unit, currentHashes: Set<String> = emptySet()) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY,
            DatabaseErrorHandler { throw StorageUnavailable(StorageFailure.CORRUPT) }).use { native ->
            check(native.isReadOnly)
            fun token(): Pair<Long, Long> {
                fun value(pragma: String) = native.rawQuery(pragma, null).use { check(it.moveToFirst()); it.getLong(0) }
                return value("PRAGMA schema_version") to value("PRAGMA data_version")
            }
            val before = token()
            run {
                native.rawQuery("PRAGMA quick_check", null).use {
                    if (!it.moveToFirst() || it.getString(0) != "ok") throw StorageUnavailable(StorageFailure.CORRUPT)
                }
                val version = native.version
                if (version !in TrackerDatabaseFactory.supportedVersions) throw StorageUnavailable(StorageFailure.INCOMPATIBLE)
                val connection = AndroidSQLiteConnection(native)
                val schema = JSONObject(context.assets.open("com.glebskorobogatov.subscriptiontracker.data.room.TrackerDatabase/$version.json")
                    .bufferedReader().use { it.readText() }).getJSONObject("database")
                if (version == TrackerDatabaseFactory.VERSION) currentValidator(connection)
                validateSnapshot(connection, schema, if (version == TrackerDatabaseFactory.VERSION) currentHashes else emptySet())
                native.rawQuery("PRAGMA foreign_key_check", null).use {
                    if (it.moveToFirst()) throw StorageUnavailable(StorageFailure.CORRUPT)
                }
                if (before != token()) throw StorageUnavailable(StorageFailure.TEMPORARY)
            }
        }
    }

    /** Shared Room metadata reader/comparator; expectations are parsed from the original snapshot,
     * never a manually maintained second schema. Also checks defaults Room ignores when unspecified.
     */
    @android.annotation.SuppressLint("RestrictedApi") // Same TableInfo contract used by the generated validator of pinned Room 2.8.3.
    private fun validateSnapshot(connection: SQLiteConnection, schema: JSONObject, extraHashes: Set<String>) {
        val expectedNames = schema.getJSONArray("entities").objects().map { it.getString("tableName") }.toSet()
        val actualNames = mutableSetOf<String>()
        connection.prepare("SELECT name FROM sqlite_master WHERE type='table'").use { statement ->
            while (statement.step()) actualNames += statement.getText(0)
        }
        if (actualNames.filterNot { it.startsWith("sqlite_") || it == "android_metadata" || it == "room_master_table" }.toSet() != expectedNames)
            incompatible()
        if ("room_master_table" !in actualNames) incompatible()
        val master = TableInfo.read(connection, "room_master_table")
        val expectedMaster = TableInfo("room_master_table", mapOf(
            "id" to TableInfo.Column("id", "INTEGER", false, 1, null, TableInfo.CREATED_FROM_DATABASE),
            "identity_hash" to TableInfo.Column("identity_hash", "TEXT", false, 0, null, TableInfo.CREATED_FROM_DATABASE)), emptySet(), emptySet())
        if (master != expectedMaster) incompatible()
        connection.prepare("SELECT identity_hash FROM room_master_table WHERE id=42").use {
            if (!it.step() || it.getText(0) !in (extraHashes + schema.getString("identityHash")) || it.step()) incompatible()
        }
        for (entity in schema.getJSONArray("entities").objects()) {
            val name = entity.getString("tableName")
            val keys = entity.getJSONObject("primaryKey").getJSONArray("columnNames").strings()
            val columns = entity.getJSONArray("fields").objects().associate { field ->
                val column = field.getString("columnName")
                column to TableInfo.Column(column, field.getString("affinity"), field.optBoolean("notNull"),
                    keys.indexOf(column) + 1, if (field.has("defaultValue") && !field.isNull("defaultValue")) field.getString("defaultValue") else null,
                    TableInfo.CREATED_FROM_DATABASE)
            }
            val foreign = (entity.optJSONArray("foreignKeys") ?: JSONArray()).objects().map { fk ->
                TableInfo.ForeignKey(fk.getString("table"), fk.getString("onDelete"), fk.getString("onUpdate"),
                    fk.getJSONArray("columns").strings(), fk.getJSONArray("referencedColumns").strings())
            }.toSet()
            val indices = (entity.optJSONArray("indices") ?: JSONArray()).objects().map { index ->
                val names = index.getJSONArray("columnNames").strings()
                val orders = index.optJSONArray("orders")?.strings().orEmpty().ifEmpty { names.map { "ASC" } }
                TableInfo.Index(index.getString("name"), index.getBoolean("unique"), names, orders)
            }.toSet()
            // TableInfo alone does not distinguish a partial index with the same columns.
            connection.prepare("PRAGMA index_list(`" + name.replace("`", "``") + "`)").use { indexList ->
                while (indexList.step()) {
                    val origin = indexList.getText(3)
                    if (origin !in setOf("c", "pk") || (origin == "c" && indexList.getLong(4) != 0L)) incompatible()
                }
            }
            connection.prepare("PRAGMA table_xinfo(`" + name.replace("`", "``") + "`)").use { info ->
                val visible = mutableSetOf<String>()
                while (info.step()) {
                    if (info.getLong(6) != 0L) incompatible() // Hidden/generated columns are absent from these snapshots.
                    visible += info.getText(1)
                }
                if (visible != columns.keys) incompatible()
            }
            val actual = TableInfo.read(connection, name)
            val expected = TableInfo(name, columns, foreign, indices)
            if (actual != expected || actual.indices == null || actual.indices!!.map { it.name }.toSet() != indices.map { it.name }.toSet() ||
                columns.any { (key, value) -> actual.columns[key]?.type?.uppercase() != value.type.uppercase() }) incompatible()
        }
    }
    private fun incompatible(): Nothing = throw StorageUnavailable(StorageFailure.INCOMPATIBLE)
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
}
