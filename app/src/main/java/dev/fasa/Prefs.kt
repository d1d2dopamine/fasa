package dev.fasa

import android.content.Context

/**
 * Tiny key value store for things the UI must read synchronously, before any
 * coroutine can run. Everything else belongs in Room.
 */
object Prefs {

    private const val FILE = "fasa_ui"
    private const val KEY_ONBOARDED = "onboarded"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun onboarded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONBOARDED, value).apply()
    }
}
