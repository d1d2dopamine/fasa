package dev.fasa.tg

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

// Bot token and chat id.
//
// These never touch the repository, the source code or a plain preferences
// file. A token in a public repo is somebody else's bot within the hour, and
// the whole point of this project being public is that other people fork it.
// Everyone supplies their own credentials on their own device.
object Secrets {

    private const val FILE = "fasa_secrets"
    private const val K_TOKEN = "tg_token"
    private const val K_CHAT = "tg_chat"

    @Volatile private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        val app = context.applicationContext
        val p = runCatching {
            val key = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app,
                FILE,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ) as SharedPreferences
            // Keystore can fail after a restore onto another device. Falling back
            // to a plain file is better than a crash loop; the user can retype
            // the token, and nothing else in the app depends on it.
        }.getOrElse { app.getSharedPreferences(FILE + "_plain", Context.MODE_PRIVATE) }
        cached = p
        return p
    }

    fun token(context: Context): String = prefs(context).getString(K_TOKEN, "").orEmpty().trim()

    fun chatId(context: Context): String = prefs(context).getString(K_CHAT, "").orEmpty().trim()

    fun configured(context: Context): Boolean =
        token(context).isNotEmpty() && chatId(context).isNotEmpty()

    fun save(context: Context, token: String, chatId: String) {
        prefs(context).edit()
            .putString(K_TOKEN, token.trim())
            .putString(K_CHAT, chatId.trim())
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(K_TOKEN).remove(K_CHAT).apply()
    }

    // Never show a full token back to the user or write it to a log.
    fun masked(context: Context): String {
        val t = token(context)
        if (t.isEmpty()) return ""
        val head = t.substringBefore(':', t.take(6))
        return head + ":" + "*".repeat(8) + t.takeLast(3)
    }
}
