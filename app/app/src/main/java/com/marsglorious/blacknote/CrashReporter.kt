package com.marsglorious.blacknote

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes any uncaught exception (and any explicitly reported error) to disk so we can
 * diagnose crashes without adb/logcat. Two copies are written:
 *   1. <filesDir>/crash.log       (internal — survives even if external is unavailable)
 *   2. <externalFilesDir>/crash.log (user-visible at
 *      /Android/data/com.marsglorious.blacknote/files/crash.log via any file manager)
 */
object CrashReporter {
    private const val TAG = "BlackNoteCrash"
    private const val FILENAME = "crash.log"
    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { writeReport(ctx, t.name, e, fatal = true) } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
    }

    fun report(ctx: Context, where: String, e: Throwable) {
        try { writeReport(ctx, where, e, fatal = false) } catch (_: Throwable) {}
        Log.e(TAG, "[$where] ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** Returns the contents of the most recent crash log, or null if none exists. */
    fun lastReport(ctx: Context): String? {
        val f = internalFile(ctx)
        return if (f.exists() && f.length() > 0) runCatching { f.readText() }.getOrNull() else null
    }

    fun clear(ctx: Context) {
        runCatching { internalFile(ctx).delete() }
        runCatching { externalFile(ctx)?.delete() }
    }

    private fun internalFile(ctx: Context) = File(ctx.filesDir, FILENAME)
    private fun externalFile(ctx: Context) = ctx.getExternalFilesDir(null)?.let { File(it, FILENAME) }

    private fun writeReport(ctx: Context, where: String, e: Throwable, fatal: Boolean) {
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("---- BlackNote ${if (fatal) "FATAL" else "soft"} @ ${ts.format(Date())} ----")
            pw.println("where: $where")
            pw.println("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, sdk ${android.os.Build.VERSION.SDK_INT}, abi ${android.os.Build.SUPPORTED_ABIS.joinToString(",")}")
            pw.println("error : ${e.javaClass.name}: ${e.message}")
            pw.println()
            e.printStackTrace(pw)
            var cause: Throwable? = e.cause
            while (cause != null) {
                pw.println("--- caused by ---")
                cause.printStackTrace(pw)
                cause = cause.cause
            }
        }
        val text = sw.toString()
        runCatching { internalFile(ctx).writeText(text) }
        runCatching { externalFile(ctx)?.writeText(text) }
        runCatching { writePublicDownload(ctx, text) }
        Log.e(TAG, text)
    }

    /** Publishes to /sdcard/Download/blacknote-crash.txt so Termux (or any app) can read it. */
    private fun writePublicDownload(ctx: Context, text: String) {
        val name = "blacknote-crash.txt"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            // Delete prior copy
            runCatching {
                resolver.delete(collection, "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(name))
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(collection, values) ?: return
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, name).writeText(text)
        }
    }
}
