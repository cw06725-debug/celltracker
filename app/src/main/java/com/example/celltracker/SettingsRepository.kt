package com.example.celltracker

import android.content.Context

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("celltracker_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        uiRefreshMs = prefs.getLong("ui_refresh_ms", 1000L),
        recordIntervalMs = prefs.getLong("record_interval_ms", 1000L),
        tapAction = enumValueOrDefault(prefs.getString("tap_action", null), MarkerAction.QUICK_MARK),
        longPressAction = enumValueOrDefault(prefs.getString("long_press_action", null), MarkerAction.MARK_WITH_SCREENSHOT),
        vibrateOnMark = prefs.getBoolean("vibrate_on_mark", true),
        toastOnMark = prefs.getBoolean("toast_on_mark", true),
        soundOnMark = prefs.getBoolean("sound_on_mark", false),
        floatingWindowEnabled = prefs.getBoolean("floating_window_enabled", true),
        floatingAutoShowDuringRecording = prefs.getBoolean("floating_auto_show", true),
        floatingOpacity = prefs.getFloat("floating_opacity", 0.80f).coerceIn(0.20f, 1.00f),
        floatingStartCompact = prefs.getBoolean("floating_start_compact", false),
        floatingRememberPosition = prefs.getBoolean("floating_remember_position", true),
        floatingCaptureScreenshotOnMark = prefs.getBoolean("floating_capture_screenshot", true),
        floatingIncludeWindowInScreenshot = prefs.getBoolean("floating_include_window_in_screenshot", true),
        floatingExpandedFields = prefs.getStringSet("floating_expanded_fields", null)?.mapNotNull { runCatching { FloatingField.valueOf(it) }.getOrNull() }?.toSet() ?: AppSettings().floatingExpandedFields,
        floatingCompactFields = prefs.getStringSet("floating_compact_fields", null)?.mapNotNull { runCatching { FloatingField.valueOf(it) }.getOrNull() }?.toSet() ?: AppSettings().floatingCompactFields,
        recordScope = enumValueOrDefault(prefs.getString("record_scope", null), RecordScope.CURRENT_SIM),
        mapDetailFields = prefs.getStringSet("map_detail_fields", null)?.mapNotNull { runCatching { MapDetailField.valueOf(it) }.getOrNull() }?.toSet()
            ?: AppSettings().mapDetailFields,
        issueTypes = prefs.getString("issue_types", null)?.split("\u001F")?.filter { it.isNotBlank() } ?: AppSettings().issueTypes
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putLong("ui_refresh_ms", settings.uiRefreshMs)
            .putLong("record_interval_ms", settings.recordIntervalMs)
            .putString("tap_action", settings.tapAction.name)
            .putString("long_press_action", settings.longPressAction.name)
            .putBoolean("vibrate_on_mark", settings.vibrateOnMark)
            .putBoolean("toast_on_mark", settings.toastOnMark)
            .putBoolean("sound_on_mark", settings.soundOnMark)
            .putBoolean("floating_window_enabled", settings.floatingWindowEnabled)
            .putBoolean("floating_auto_show", settings.floatingAutoShowDuringRecording)
            .putFloat("floating_opacity", settings.floatingOpacity.coerceIn(0.20f, 1.00f))
            .putBoolean("floating_start_compact", settings.floatingStartCompact)
            .putBoolean("floating_remember_position", settings.floatingRememberPosition)
            .putBoolean("floating_capture_screenshot", settings.floatingCaptureScreenshotOnMark)
            .putBoolean("floating_include_window_in_screenshot", settings.floatingIncludeWindowInScreenshot)
            .putStringSet("floating_expanded_fields", settings.floatingExpandedFields.map { it.name }.toSet())
            .putStringSet("floating_compact_fields", settings.floatingCompactFields.map { it.name }.toSet())
            .putString("record_scope", settings.recordScope.name)
            .putStringSet("map_detail_fields", settings.mapDetailFields.map { it.name }.toSet())
            .putString("issue_types", settings.issueTypes.joinToString("\u001F"))
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T {
        return try { if (value == null) fallback else enumValueOf<T>(value) } catch (_: Exception) { fallback }
    }
}
