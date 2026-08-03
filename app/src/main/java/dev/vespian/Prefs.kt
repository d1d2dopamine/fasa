package dev.vespian

import android.content.Context

/**
 * Tiny key value store for things the UI must read synchronously, before any
 * coroutine can run. Everything else belongs in Room.
 */
object Prefs {

    private const val FILE = "vespian_ui"
    private const val KEY_ONBOARDED = "onboarded"

    // Bot language, an empty tag means the user has not answered yet.
    private const val KEY_BOT_LANG = "bot_lang"

    // Manual mode adds the "went to bed" style buttons to the chat. Off by
    // default: the whole point of this app is that nothing has to be tapped.
    private const val KEY_MANUAL = "manual_mode"

    // Caffeine in one mug of whatever the user actually drinks. The default is
    // instant coffee in a 430 ml mug. Brewed coffee is closer to 190.
    private const val KEY_MG_PER_MUG = "mg_per_mug"
    const val MG_PER_MUG_DEFAULT = 130
    const val MG_PER_MUG_MIN = 20
    const val MG_PER_MUG_MAX = 400

    // Other drinks. Off by default and invisible until switched on, because an
    // interface full of drinks nobody has is an interface nobody keeps using.
    //
    // There is no third caffeinated switch on purpose. Every caffeinated drink
    // ends up as milligrams in the same total, so more buttons would add
    // tapping without adding anything the model can learn from.
    private const val KEY_DRINK_ENERGY = "drink_energy"
    private const val KEY_DRINK_ALCOHOL = "drink_alcohol"

    // Caffeine in one can of energy drink. A half litre can sits near this.
    private const val KEY_MG_PER_CAN = "mg_per_can"
    const val MG_PER_CAN_DEFAULT = 160
    const val MG_PER_CAN_MIN = 20
    const val MG_PER_CAN_MAX = 400

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

    fun mgPerMug(context: Context): Int =
        prefs(context).getInt(KEY_MG_PER_MUG, MG_PER_MUG_DEFAULT)

    fun setMgPerMug(context: Context, value: Int) {
        val clamped = value.coerceIn(MG_PER_MUG_MIN, MG_PER_MUG_MAX)
        prefs(context).edit().putInt(KEY_MG_PER_MUG, clamped).apply()
    }

    fun energyOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DRINK_ENERGY, false)

    fun setEnergyOn(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_DRINK_ENERGY, value).apply()
    }

    fun alcoholOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DRINK_ALCOHOL, false)

    fun setAlcoholOn(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_DRINK_ALCOHOL, value).apply()
    }

    fun mgPerCan(context: Context): Int =
        prefs(context).getInt(KEY_MG_PER_CAN, MG_PER_CAN_DEFAULT)

    fun setMgPerCan(context: Context, value: Int) {
        val clamped = value.coerceIn(MG_PER_CAN_MIN, MG_PER_CAN_MAX)
        prefs(context).edit().putInt(KEY_MG_PER_CAN, clamped).apply()
    }
}
