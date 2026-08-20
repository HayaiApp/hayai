package dev.ahmedmohamed.hayai.migration

import android.content.Context

/** Persistent hand-off between Advanced settings and the next database open. */
class LegacyMigrationRetryRequest(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun request() {
        preferences.edit().putBoolean(KEY_REQUESTED, true).apply()
    }

    fun isRequested(): Boolean = preferences.getBoolean(KEY_REQUESTED, false)

    fun clear() {
        preferences.edit().remove(KEY_REQUESTED).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "hayai_legacy_migration"
        const val KEY_REQUESTED = "retry_requested"
    }
}
