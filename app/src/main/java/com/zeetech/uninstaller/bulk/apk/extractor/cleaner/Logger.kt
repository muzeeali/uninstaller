package com.zeetech.uninstaller.bulk.apk.extractor.cleaner

/**
 * Minimal logging wrapper — disabled by default to remove debug output from release.
 * Flip ENABLE to true only when actively debugging locally.
 */
object Logger {
    private const val ENABLE = false
    fun d(tag: String, msg: String) { if (ENABLE) android.util.Log.d(tag, msg) }
    fun w(tag: String, msg: String) { if (ENABLE) android.util.Log.w(tag, msg) }
    fun e(tag: String, msg: String) { if (ENABLE) android.util.Log.e(tag, msg) }
}
