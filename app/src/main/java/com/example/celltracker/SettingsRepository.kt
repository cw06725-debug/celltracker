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
        soundOnMark = prefs.getBoolean("sound_on_mark", false)
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
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T {
        return try { if (value == null) fallback else enumValueOf<T>(value) } catch (_: Exception) { fallback }
    }
}
