package com.github.chiamingmai.logrecorder

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.coroutineContext

/**
 * Log Recorder
 *
 * Features:
 * - Records plain text messages or JSON objects asynchronously to a log file
 * - Optionally masks sensitive fields (e.g., name, phone, email)
 * - Supports exporting log files on both pre-Android Q and Android Q+ devices
 * - Coroutine + Queue ensures sequential writes without blocking main thread
 * - Exported log files clear the current log for new entries
 */
class LogRecorder private constructor(
    private val context: Context,
    /** Directory where the log file should be exported. */
    private val exportDirectory: MediaDir = MediaDir.DOWNLOADS,
    /** The name of Log file. */
    private val logFileName: String = DEFAULT_FILE_NAME,
    /** Should log feature be enabled or not. */
    private val enabled: Boolean,
    /** Set if log file should be overwritten, i.e., delete first then create. */
    private var exportWithOverwrite: Boolean = false,
    /** Custom JSON mask rule. */
    private val customMaskRule: MutableList<JSONMaskRule> = mutableListOf()
) : Closeable {
    companion object {
        private const val TAG = "LogRecorder"
        private const val DEFAULT_FILE_NAME = "LogRecorder_Log"

        /** Date time format */
        private val dateTimeFormat =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

        private val gson: Gson = GsonBuilder().create()
    }

    enum class MediaDir(val path: String) {
        /** [Environment.DIRECTORY_DOWNLOADS] directory */
        DOWNLOADS(Environment.DIRECTORY_DOWNLOADS),

        /** [Environment.DIRECTORY_DOCUMENTS] directory */
        DOCUMENTS(Environment.DIRECTORY_DOCUMENTS)
    }

    private val logQueue: BlockingQueue<LogItem> = LinkedBlockingQueue(100)
    private val job = SupervisorJob()
    private var coroutineScope = CoroutineScope(Dispatchers.IO + job)

    private var logFile: File? = null
    private val logFileNameWithExt: String get() = "${logFileName}.txt"

    //region BufferedWriter
    private var writer: BufferedWriter? = null
    private val writerLock = Any()

    private fun getWriter(): BufferedWriter {
        if (writer == null) {
            synchronized(writerLock) {
                if (writer == null) {
                    writer = FileWriter(logFile, true).buffered()
                }
            }
        }
        return writer!!
    }
    //endregion

    private val exportFileSuccessMsg: String =
        context.getString(R.string.export_log_file_to_path, exportDirectory.path)

    private val unableToCreateLogFileMsg: String =
        context.getString(R.string.unable_to_create_log_file, exportDirectory.path)

    init {
        logFile = createLogFile()
        // Launch the log queue.
        coroutineScope.launch { processLogQueue() }
    }

    /** Create or reuse the log file inside app's private storage */
    private fun createLogFile(): File {
        var f = logFile
        if (f == null || f.exists().not()) {
            f = File(context.filesDir, logFileNameWithExt)
            f.createNewFile()
            logFile = f
        }
        return f
    }

    /**
     * Continuously consumes log items from the queue and writes them sequentially.
     * Runs until the recorder is closed.
     */
    private suspend fun processLogQueue() {
        while (coroutineContext.isActive) {
            val logItem = withContext(Dispatchers.IO) { logQueue.take() }
            writeLogToFile(logItem)
        }
    }

    private suspend fun writeLogToFile(text: String) {
        withContext(Dispatchers.IO) {
            val writer = getWriter()
            writer.append(text)
            writer.flush()
        }
    }

    /**
     * Writes a single [LogItem] to the file.
     * - [LogItem.PlainTextLog] → writes plain text
     * - [LogItem.JSONLog] → serializes JSON, masks sensitive fields, and writes it
     */
    private suspend fun writeLogToFile(logItem: LogItem) {
        val timestampStr = "${dateTimeFormat.format(logItem.timestamp)} | "

        if (logItem is LogItem.PlainTextLog) {
            try {
                writeLogToFile("${timestampStr}${logItem.text}\n")
            } catch (e: Exception) {
                Log.e(TAG, "Write log failed.", e)
                log(e)
            }
        }

        if (logItem is LogItem.JSONLog) {
            try {
                val jsonString = gson.toJson(logItem.obj)
                val jsonElement: JsonElement = JsonParser.parseString(jsonString)

                val msg = withContext(Dispatchers.Default) {
                    when {
                        jsonElement.isJsonObject -> {
                            val jsonObject = JSONObject(jsonString)
                            maskJsonRecursively(jsonObject)

                            jsonObject.toString()
                        }

                        jsonElement.isJsonArray -> {
                            val jsonArray = JSONArray(jsonString)
                            maskJsonRecursively(jsonArray)

                            jsonArray.toString()
                        }

                        else -> {
                            jsonString
                        }
                    }
                }

                writeLogToFile("${timestampStr}${msg}\n")
            } catch (e: Exception) {
                Log.e(TAG, "Write log failed.", e)
                e.printStackTrace()
                log(e)
            }
        }
    }

    /** Add a custom JSON mask rule. */
    fun addJSONMaskRule(rule: JSONMaskRule) = apply { customMaskRule.add(rule) }

    /** Remove JSON mask rule */
    fun removeMaskRule(ruleType: Class<out JSONMaskRule>) {
        customMaskRule.removeAll { it::class.java == ruleType }
    }

    /** Clear all JSON mask rules. */
    fun clearJSONMaskRules() {
        customMaskRule.clear()
    }

    /** Log a plain text message. */
    fun log(message: String) {
        if (enabled.not()) return

        logQueue.offer(LogItem.PlainTextLog(message))
    }

    /** Log an exception including stack trace details. */
    fun log(t: Throwable) {
        if (enabled.not()) return

        val stackTraceLines = t.stackTraceToString()
        val header = "${t::class.simpleName} message: ${t.message}\n"
        log(header + stackTraceLines + "\n")
    }

    //region JSON
    /** Log an object as JSON. Masks sensitive fields if enabled. */
    fun logJson(any: Any?) {
        if (enabled.not() || any == null) return

        logQueue.offer(LogItem.JSONLog(any))
    }

    /**
     * Recursively traverses JSON objects/arrays and masks sensitive values.
     * Prevents infinite loops by tracking visited objects.
     */
    private fun maskJsonRecursively(json: Any, visited: MutableSet<Any> = mutableSetOf()) {
        if (!visited.add(json)) return

        when (json) {
            is JSONObject -> {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    when (val value = json.opt(key)) {
                        is JSONObject, is JSONArray -> maskJsonRecursively(value, visited)
                        is String -> json.put(key, maskValue(key, value))
                    }
                }
            }

            is JSONArray -> {
                for (i in 0 until json.length()) {
                    when (val element = json.opt(i)) {
                        is JSONObject, is JSONArray -> maskJsonRecursively(element, visited)
                        is String -> json.put(i, maskValue("", element))
                    }
                }
            }
        }
    }

    /**
     * Replace sensitive field values with "***".
     * Currently matches keys such as "name", "phone", "email" (case-insensitive).
     */
    private fun maskValue(key: String, value: String): String {
        for (rule in customMaskRule) {
            val masked = rule.mask(key, value)
            if (masked != null) return masked
        }
        return value
    }
    //endregion

    //region Export file
    /**
     * Export the log file to public storage.
     * - Android Q and above → uses MediaStore API
     * - Below Q → uses legacy file I/O
     */
    fun exportLogFile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(context, R.string.export_file_permission_not_granted, Toast.LENGTH_LONG)
                .show()
            return
        }
        coroutineScope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (exportWithOverwrite) {
                    deleteFileFromDownloadsMediaStore()
                }
                exportLogFile29()
            } else {
                exportLegacy()
            }
        }
    }

    /**
     * Export implementation for Android Q and above using MediaStore.
     * Replaces existing file if overwrite is enabled.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun exportLogFile29() {
        try {
            // 讀取現有 Log 檔案
            val logContent = logFile?.takeIf { it.exists() }?.readText() ?: ""

            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, logFileNameWithExt)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${exportDirectory.path}/")
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw Exception(unableToCreateLogFileMsg)

            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(logContent.toByteArray())
                outputStream.flush()
            }

            // 匯出後清空 logFile
            try {
                logFile?.writeText("")
                //BufferedWriter(FileWriter(logFile, false)).use { it.write("") }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    exportFileSuccessMsg,
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            val errorMsg = context.getString(R.string.export_log_file_failed, e.message)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Export for pre-Android Q using legacy file I/O */
    private suspend fun exportLegacy() {
        try {
            val dir = Environment.getExternalStoragePublicDirectory(exportDirectory.path)
            val exportFile = File(dir, logFileNameWithExt)
            if (exportFile.exists()) {
                exportFile.delete()
            }
            exportFile.createNewFile()

            createLogFile()

            logFile?.takeIf { it.exists() }?.copyTo(exportFile, overwrite = true)

            // 匯出後清空 logFile
            try {
                logFile?.writeText("")
                //BufferedWriter(FileWriter(logFile, false)).use { it.write("") }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    exportFileSuccessMsg,
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            val errorMsg = context.getString(R.string.export_log_file_failed, e.message)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Deletes an exported log file from Downloads (Android Q+).
     * Returns true if deletion succeeded or file not found.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deleteFileFromDownloadsMediaStore() {
        try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

            val selection =
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
            val selectionArgs = arrayOf("${exportDirectory.path}/", logFileNameWithExt)

            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id =
                        cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    val uri = ContentUris.withAppendedId(collection, id)
                    resolver.delete(uri, null, null)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    //endregion

    override fun close() {
        coroutineScope.cancel()
        synchronized(writerLock) {
            writer?.close()
            writer = null
        }
    }

    /**
     * Builder for LogRecorder.
     *
     * Example:
     * ```
     *     val recorder = LogRecorder.Builder(context)
     *      .setExportDirectory(LogRecorder.MediaDir.DOWNLOADS)
     *      .setLogFileName("MyAppLog")
     *      .setLogEnabled(true)
     *      .overwriteExportFile(true)
     *      .addJSONMaskRule(MaskRules.full())
     *      .build()
     * ```
     */
    class Builder(private val context: Context) {
        private var directory: MediaDir = MediaDir.DOWNLOADS
        private var fileName: String = DEFAULT_FILE_NAME
        private var enabled: Boolean = true
        private var exportWithOverwrite: Boolean = false
        private val customMaskRule = mutableListOf<JSONMaskRule>()

        /** Set the export directory (Downloads or Documents). */
        fun setExportDirectory(directory: MediaDir) = apply { this.directory = directory }

        /** Set the log file name (without extension). */
        fun setLogFileName(name: String) = apply { this.fileName = name }

        /** Enable or disable log recording. */
        fun setLogEnabled(enabled: Boolean) = apply { this.enabled = enabled }

        /** Overwrite existing exported file if true. */
        fun overwriteExportFile(overwrite: Boolean) = apply { this.exportWithOverwrite = overwrite }

        /** Add a custom JSON mask rule. */
        fun addJSONMaskRule(rule: JSONMaskRule) = apply { customMaskRule.add(rule) }

        /** Add a collection of custom JSON mask rules. */
        fun addJSONMaskRule(rules: List<JSONMaskRule>) = apply { customMaskRule.addAll(rules) }

        /** Create a [LogRecorder] instance */
        fun build(): LogRecorder = LogRecorder(
            context = context,
            exportDirectory = directory,
            logFileName = fileName,
            enabled = enabled,
            exportWithOverwrite = exportWithOverwrite,
            customMaskRule = customMaskRule
        )
    }
}