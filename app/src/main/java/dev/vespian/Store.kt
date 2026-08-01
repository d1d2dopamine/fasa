package dev.vespian

import android.content.Context
import dev.vespian.db.Db
import dev.vespian.db.Meta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Settings that must outlive a reinstall live in the database, next to the
// data they describe. SharedPreferences stays only as a synchronous cache,
// because the UI cannot wait for a coroutine while it draws a frame.
object Store {

    const val K_LANG = "bot_lang"
    const val K_MODE = "manual_mode"

    suspend fun saveLang(context: Context, tag: String) = withContext(Dispatchers.IO) {
        Prefs.setBotLang(context, tag)
        Db.get(context).meta().put(Meta(K_LANG, tag))
    }

    suspend fun saveMode(context: Context, manual: Boolean) = withContext(Dispatchers.IO) {
        Prefs.setManualMode(context, manual)
        Db.get(context).meta().put(Meta(K_MODE, if (manual) "1" else "0"))
    }

    // Cache empty, database intact: put the answers back where the UI reads
    // them. Runs on every cold start and costs one query.
    suspend fun restore(context: Context) = withContext(Dispatchers.IO) {
        val meta = Db.get(context).meta()
        if (Prefs.botLang(context).isEmpty()) {
            val tag = meta.get(K_LANG).orEmpty()
            if (tag.isNotEmpty()) Prefs.setBotLang(context, tag)
        }
        if (!Prefs.manualMode(context) && meta.get(K_MODE) == "1") {
            Prefs.setManualMode(context, true)
        }
    }
}
