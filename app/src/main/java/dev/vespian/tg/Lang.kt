package dev.vespian.tg

import android.content.Context
import android.content.res.Configuration
import dev.vespian.Prefs
import java.util.Locale

// Language of the chat, kept apart from the language of the app.
//
// The phone can be in English while the chat is easier to read in Russian, and
// the bot is often the only part another person ever sees. Everything the bot
// says is resolved through a locale wrapped context, so the same string
// resources serve both surfaces and nothing is translated twice.
object Lang {

    const val DEFAULT = "en"

    // Empty means the question has not been answered yet, which is what the
    // start message is for.
    fun chosen(context: Context): Boolean = Prefs.botLang(context).isNotEmpty()

    fun tag(context: Context): String = Prefs.botLang(context).ifEmpty { DEFAULT }

    fun set(context: Context, tag: String) = Prefs.setBotLang(context, tag)

    fun ctx(context: Context): Context = ctxFor(context, tag(context))

    fun ctxFor(context: Context, tag: String): Context {
        val base = context.applicationContext
        val conf = Configuration(base.resources.configuration)
        conf.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(conf)
    }

    fun string(context: Context, res: Int, vararg args: Any): String {
        val c = ctx(context)
        return if (args.isEmpty()) c.getString(res) else c.getString(res, *args)
    }
}
