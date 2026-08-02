package dev.vespian.tg

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

    // Uploading a file over a phone connection is not a twenty second job.
    private const val UPLOAD_TIMEOUT_MS = 90_000

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
        replyKeyboard: JSONArray? = null,
    ): Reply {
        val body = JSONObject()
        body.put("chat_id", chatId)
        body.put("text", text)
        if (keyboard != null) {
            body.put("reply_markup", JSONObject().put("inline_keyboard", keyboard))
        } else if (replyKeyboard != null) {
            // The persistent keyboard sits under the input field, so the common
            // actions are one tap away without remembering any command.
            body.put(
                "reply_markup",
                JSONObject()
                    .put("keyboard", replyKeyboard)
                    .put("resize_keyboard", true)
                    .put("is_persistent", true),
            )
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

    // One row of the persistent keyboard. These buttons send their own label
    // back as an ordinary message, so the label is the protocol.
    fun textRow(vararg labels: String): JSONArray {
        val row = JSONArray()
        for (l in labels) row.put(JSONObject().put("text", l))
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

    // An export is a few hundred kilobytes of JSON. As a message it would be
    // chopped into forty pieces, so it goes as a file. Telegram accepts files
    // only as multipart/form-data, which the JSON helper above cannot express,
    // so this one writes the request body by hand.
    suspend fun sendDocument(
        token: String,
        chatId: String,
        fileName: String,
        caption: String,
        content: ByteArray,
    ): Reply = withContext(Dispatchers.IO) {
        if (token.isEmpty()) return@withContext Reply.Fail(0, "no token")
        val boundary = "vespian" + System.currentTimeMillis()
        val crlf = "\r\n"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(BASE + token + "/sendDocument").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = UPLOAD_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
            }
            conn.outputStream.use { out ->
                fun write(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                fun field(name: String, value: String) {
                    write("--" + boundary + crlf)
                    write("Content-Disposition: form-data; name=\"" + name + "\"" + crlf + crlf)
                    write(value)
                    write(crlf)
                }
                field("chat_id", chatId)
                if (caption.isNotEmpty()) field("caption", caption)
                write("--" + boundary + crlf)
                write(
                    "Content-Disposition: form-data; name=\"document\"; filename=\"" +
                        fileName + "\"" + crlf
                )
                write("Content-Type: application/json" + crlf + crlf)
                out.write(content)
                write(crlf)
                write("--" + boundary + "--" + crlf)
                out.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: return@withContext Reply.Fail(code, "bad response")
            if (json.optBoolean("ok", false)) {
                Reply.Ok(json.optJSONObject("result") ?: JSONObject())
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

    suspend fun getMe(token: String): Reply = call(token, "getMe", JSONObject())

    // Registers the slash commands, which is what fills the hint list Telegram
    // shows while typing. BotFather does the same thing by hand; doing it from
    // the app means the list can never drift from the code.
    suspend fun setMyCommands(
        token: String,
        commands: List<Pair<String, String>>,
    ): Reply {
        val arr = JSONArray()
        for ((name, description) in commands) {
            arr.put(JSONObject().put("command", name).put("description", description))
        }
        return call(token, "setMyCommands", JSONObject().put("commands", arr))
    }
}
