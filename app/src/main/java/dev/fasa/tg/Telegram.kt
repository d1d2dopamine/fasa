package dev.fasa.tg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

// Thin wrapper over the Telegram Bot API.
//
// No SDK and no server. The Bot API is plain HTTPS with JSON bodies, and
// getUpdates long polling means we never need a public address. A webhook
// would need one, which is exactly the dependency this project refuses.
object Telegram {

    private const val BASE = "https://api.telegram.org/bot"
    private const val TIMEOUT_MS = 20_000

    // Telegram caps a held connection at fifty seconds and answers the instant
    // an update arrives, so this is the longest useful wait.
    const val LONG_POLL_SEC = 50

    sealed interface Reply {
        data class Ok(val result: JSONObject) : Reply
        data class Fail(val code: Int, val message: String) : Reply
    }

    suspend fun call(
        token: String,
        method: String,
        body: JSONObject,
        readTimeoutMs: Int = TIMEOUT_MS,
    ): Reply =
        withContext(Dispatchers.IO) {
            if (token.isEmpty()) return@withContext Reply.Fail(0, "no token")
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(BASE + token + "/" + method).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = readTimeoutMs
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                    ?: return@withContext Reply.Fail(code, "bad response")

                if (json.optBoolean("ok", false)) {
                    val r = json.opt("result")
                    val wrapped = when (r) {
                        is JSONObject -> r
                        is JSONArray -> JSONObject().put("array", r)
                        else -> JSONObject()
                    }
                    Reply.Ok(wrapped)
                } else {
                    Reply.Fail(
                        json.optInt("error_code", code),
                        json.optString("description", "unknown error"),
                    )
                }
            } catch (e: Exception) {
                Reply.Fail(-1, e.message ?: e.javaClass.simpleName)
            } finally {
                conn?.disconnect()
            }
        }

    suspend fun sendMessage(
        token: String,
        chatId: String,
        text: String,
        keyboard: JSONArray? = null,
    ): Reply {
        val body = JSONObject()
        body.put("chat_id", chatId)
        body.put("text", text)
        if (keyboard != null) {
            body.put("reply_markup", JSONObject().put("inline_keyboard", keyboard))
        }
        return call(token, "sendMessage", body)
    }

    // One row of inline buttons. data is what comes back in the callback.
    fun row(vararg pairs: Pair<String, String>): JSONArray {
        val row = JSONArray()
        for ((label, data) in pairs) {
            row.put(JSONObject().put("text", label).put("callback_data", data))
        }
        return row
    }

    fun keyboard(vararg rows: JSONArray): JSONArray {
        val k = JSONArray()
        for (r in rows) k.put(r)
        return k
    }

    // [timeoutSec] > 0 turns this into a held connection: Telegram keeps it
    // open until something happens, then replies immediately. That is how a
    // button tap can be handled in a second without a server and without
    // hammering the network the rest of the time.
    suspend fun getUpdates(
        token: String,
        offset: Long,
        timeoutSec: Int = 0,
    ): Pair<List<JSONObject>, Reply.Fail?> {
        val body = JSONObject()
        body.put("offset", offset)
        body.put("timeout", timeoutSec)
        body.put("allowed_updates", JSONArray(listOf("callback_query", "message")))
        // The socket must outlive the server side wait, otherwise every long
        // poll ends in a read timeout and the loop spins.
        val read = if (timeoutSec > 0) timeoutSec * 1000 + 15_000 else TIMEOUT_MS
        return when (val r = call(token, "getUpdates", body, read)) {
            is Reply.Fail -> emptyList<JSONObject>() to r
            is Reply.Ok -> {
                val arr = r.result.optJSONArray("array") ?: JSONArray()
                val list = ArrayList<JSONObject>(arr.length())
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
                list to null
            }
        }
    }

    // Stops the spinner on the tapped button. Telegram nags if this is skipped.
    suspend fun answerCallback(token: String, callbackId: String, text: String): Reply =
        call(
            token,
            "answerCallbackQuery",
            JSONObject().put("callback_query_id", callbackId).put("text", text),
        )

    suspend fun editText(token: String, chatId: String, messageId: Int, text: String): Reply =
        call(
            token,
            "editMessageText",
            JSONObject()
                .put("chat_id", chatId)
                .put("message_id", messageId)
                .put("text", text)
                .put("reply_markup", JSONObject().put("inline_keyboard", JSONArray())),
        )

    suspend fun getMe(token: String): Reply = call(token, "getMe", JSONObject())
}
