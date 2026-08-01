package dev.fasa

import android.content.Context

/**
 * Tiny key value store for things the UI must read synchronously, before any
 * coroutine can run. Everything else belongs in Room.
 */
object Prefs {

    private const val FILE = "fasa_ui"
    private const val KEY_ONBOARDED = "onboarded"

    // Bot language, an empty tag means the user has not answered yet.
    private const val KEY_BOT_LANG = "bot_lang"

    // Manual mode adds the "went to bed" style buttons to the chat. Off by
    // default: the whole point of this app is that nothing has to be tapped.
    private const val KEY_MANUAL = "manual_mode"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun onboarded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONBOARDED, value).apply()
    }

    fun botLang(context: Context): String =
        prefs(context).getString(KEY_BOT_LANG, "").orEmpty()

    fun setBotLang(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_BOT_LANG, tag).apply()
    }

    fun manualMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MANUAL, false)

    fun setManualMode(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_MANUAL, value).apply()
    }
}
