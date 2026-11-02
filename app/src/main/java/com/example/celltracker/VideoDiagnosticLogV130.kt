package com.example.celltracker

import android.content.Context
import android.util.Log

/** Optional video engineering diagnostics. Disabled on new and upgraded installs unless user opts in.
 * Normal MP4, mapping CSV/SRT, annotations and reports are never gated by this switch.
 */
internal object VideoDiagnosticLogV130 {
    private const val PREFS = "celltracker_settings"
    private const val KEY = "video_diagnostic_logs_enabled"
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }
    fun event(context: Context, message: String) {
        if (enabled(context)) Log.d("CTVideoDiagnostic", message)
    }
}
