package com.example.runtrail.data

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.util.Properties

/** Time and size of the last backup export/import, stored in a private properties file. */
class BackupInfo(private val context: Context) {

    data class Entry(val at: Long, val count: Int)

    val lastExport: Entry? get() = read(KEY_EXPORT_AT, KEY_EXPORT_COUNT)
    val lastImport: Entry? get() = read(KEY_IMPORT_AT, KEY_IMPORT_COUNT)

    fun recordExport(count: Int, at: Long = System.currentTimeMillis()) = write(KEY_EXPORT_AT, KEY_EXPORT_COUNT, at, count)
    fun recordImport(count: Int, at: Long = System.currentTimeMillis()) = write(KEY_IMPORT_AT, KEY_IMPORT_COUNT, at, count)

    private fun load(): Properties {
        val props = Properties()
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) FileInputStream(file).use { props.load(it) }
        return props
    }

    private fun read(atKey: String, countKey: String): Entry? {
        val p = load()
        val at = p.getProperty(atKey)?.toLongOrNull() ?: return null
        return Entry(at, p.getProperty(countKey)?.toIntOrNull() ?: 0)
    }

    @Synchronized
    private fun write(atKey: String, countKey: String, at: Long, count: Int) {
        val p = load()
        p.setProperty(atKey, at.toString())
        p.setProperty(countKey, count.toString())
        context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).use { p.store(it, "RunTrail backup log") }
    }

    companion object {
        const val FILE_NAME = "backup_info.properties"
        private const val KEY_EXPORT_AT = "last_export_at"
        private const val KEY_EXPORT_COUNT = "last_export_count"
        private const val KEY_IMPORT_AT = "last_import_at"
        private const val KEY_IMPORT_COUNT = "last_import_count"
    }
}
