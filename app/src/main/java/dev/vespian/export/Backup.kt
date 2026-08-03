package dev.vespian.export

import android.content.Context
import dev.vespian.BuildConfig
import dev.vespian.Prefs
import dev.vespian.db.Answer
import dev.vespian.db.Db
import dev.vespian.db.HrSample
import dev.vespian.db.LightSample
import dev.vespian.db.Meta
import dev.vespian.db.ModelState
import dev.vespian.db.Night
import dev.vespian.model.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A complete copy of everything the app knows, and the way back in.
 *
 * This is not the export file. The export is written for a human reader and
 * throws away anything that would not help one: raw light is digested to a
 * daily line, the particle cloud becomes a handful of medians. That file cannot
 * rebuild the app.
 *
 * This one can. Every row of every table, plus the settings, in the order they
 * need to be written back. It is the only defence against the two ways this
 * project can lose a month of nights: a factory reset, and a new phone.
 *
 * Deliberately plain JSON rather than a copy of the database file. A database
 * file only opens in the exact schema version that wrote it; a text file can be
 * read by the next version, by a script, or by a person.
 */
object Backup {

    const val FORMAT_VERSION = 1

    private const val FORMAT = "vespian-backup"

    /** What a restore actually put back, so the screen can report facts. */
    class Report(
        val nights: Int,
        val answers: Int,
        val hr: Int,
        val light: Int,
        val meta: Int,
        val model: Boolean,
    )

    class BadFile(message: String) : Exception(message)

    fun fileName(): String = "vespian-backup-" + LocalDate.now() + ".json"

    // ---- writing ---------------------------------------------------------

    suspend fun build(context: Context): String = withContext(Dispatchers.IO) {
        val db = Db.get(context)
        val now = System.currentTimeMillis()

        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("backup_version", FORMAT_VERSION)
        root.put("app_version", BuildConfig.VERSION_NAME)
        root.put("schema_version", 4)
        root.put("created_at_utc", Instant.ofEpochMilli(now).toString())
        root.put("time_zone", ZoneId.systemDefault().id)
        root.put(
            "note",
            "Full restorable copy of a vespian install: every night, answer, " +
                "heart rate reading, light sample, model state and setting. " +
                "Restore it from Settings inside the app."
        )

        val nights = JSONArray()
        for (n in db.nights().all()) {
            val o = JSONObject()
            o.put("dateKey", n.dateKey)
            o.put("sleepStart", n.sleepStart)
            o.put("sleepEnd", n.sleepEnd)
            o.put("minutesAsleep", n.minutesAsleep)
            o.put("minutesDeep", n.minutesDeep)
            o.put("minutesRem", n.minutesRem)
            o.put("minutesAwake", n.minutesAwake)
            o.put("hrMin", n.hrMin ?: JSONObject.NULL)
            o.put("hrMinAt", n.hrMinAt ?: JSONObject.NULL)
            o.put("hrMean", n.hrMean ?: JSONObject.NULL)
            o.put("spo2Mean", n.spo2Mean?.toDouble() ?: JSONObject.NULL)
            o.put("source", n.source)
            o.put("importedAt", n.importedAt)
            nights.put(o)
        }
        root.put("nights", nights)

        val answers = JSONArray()
        for (a in db.answers().last(100_000)) {
            val o = JSONObject()
            o.put("dateKey", a.dateKey)
            o.put("mood", a.mood ?: JSONObject.NULL)
            o.put("mugs", a.mugs ?: JSONObject.NULL)
            o.put("cans", a.cans ?: JSONObject.NULL)
            o.put("alcohol", a.alcohol ?: JSONObject.NULL)
            o.put("at", a.at)
            answers.put(o)
        }
        root.put("answers", answers)

        // Heart rate and light are tens of thousands of rows. Named fields
        // would triple the file for no gain, so both are fixed length arrays
        // and the order is written down next to them.
        root.put("hr_columns", JSONArray(listOf("at", "bpm")))
        val hr = JSONArray()
        for (s in db.hr().between(0L, now)) {
            hr.put(JSONArray().put(s.at).put(s.bpm))
        }
        root.put("hr", hr)

        root.put(
            "light_columns",
            JSONArray(listOf("at", "lux", "screenOn", "kind", "screenMs", "brightness"))
        )
        val light = JSONArray()
        for (s in db.light().between(0L, now)) {
            light.put(
                JSONArray()
                    .put(s.at)
                    .put(s.lux.toDouble())
                    .put(if (s.screenOn) 1 else 0)
                    .put(s.kind)
                    .put(s.screenMs)
                    .put(s.brightness)
            )
        }
        root.put("light", light)

        val meta = JSONObject()
        for (m in db.meta().all()) meta.put(m.key, m.value)
        root.put("meta", meta)

        val state = db.model().get()
        if (state?.particles != null) {
            val o = JSONObject()
            o.put("particles", state.particles)
            o.put("updatedAt", state.updatedAt)
            root.put("model", o)
        }

        val prefs = JSONObject()
        prefs.put("onboarded", Prefs.onboarded(context))
        prefs.put("bot_lang", Prefs.botLang(context))
        prefs.put("manual_mode", Prefs.manualMode(context))
        prefs.put("mg_per_mug", Prefs.mgPerMug(context))
        prefs.put("mg_per_can", Prefs.mgPerCan(context))
        prefs.put("drink_energy", Prefs.energyOn(context))
        prefs.put("drink_alcohol", Prefs.alcoholOn(context))
        prefs.put("ask_drinks", Prefs.askDrinks(context))
        root.put("prefs", prefs)

        // No indentation here, unlike the export. This file is read by the app,
        // and pretty printing a hundred thousand rows costs memory on a phone.
        root.toString()
    }

    // ---- reading back ----------------------------------------------------

    /**
     * Writes a backup back into the database.
     *
     * Rows are merged, not wiped: every table is keyed, so a restored row
     * replaces the row with the same key and leaves the rest alone. That way
     * restoring an old copy onto a running install cannot delete the nights
     * recorded since.
     *
     * The bot token is not in the file and is never touched here.
     */
    suspend fun restore(context: Context, text: String): Report = withContext(Dispatchers.IO) {
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: throw BadFile("not a JSON file")
        if (root.optString("format") != FORMAT) {
            throw BadFile("not a vespian backup")
        }
        if (root.optInt("backup_version", 0) > FORMAT_VERSION) {
            throw BadFile("written by a newer version of the app")
        }

        val db = Db.get(context)

        val nights = root.optJSONArray("nights") ?: JSONArray()
        for (i in 0 until nights.length()) {
            val o = nights.optJSONObject(i) ?: continue
            db.nights().put(
                Night(
                    dateKey = o.optString("dateKey"),
                    sleepStart = o.optLong("sleepStart"),
                    sleepEnd = o.optLong("sleepEnd"),
                    minutesAsleep = o.optInt("minutesAsleep"),
                    minutesDeep = o.optInt("minutesDeep"),
                    minutesRem = o.optInt("minutesRem"),
                    minutesAwake = o.optInt("minutesAwake"),
                    hrMin = optInt(o, "hrMin"),
                    hrMinAt = optLong(o, "hrMinAt"),
                    hrMean = optInt(o, "hrMean"),
                    spo2Mean = optDouble(o, "spo2Mean")?.toFloat(),
                    source = o.optString("source", "mi"),
                    importedAt = o.optLong("importedAt"),
                )
            )
        }

        val answers = root.optJSONArray("answers") ?: JSONArray()
        for (i in 0 until answers.length()) {
            val o = answers.optJSONObject(i) ?: continue
            db.answers().put(
                Answer(
                    dateKey = o.optString("dateKey"),
                    mood = optInt(o, "mood"),
                    mugs = optInt(o, "mugs"),
                    at = o.optLong("at"),
                    cans = optInt(o, "cans"),
                    alcohol = optInt(o, "alcohol"),
                )
            )
        }

        val hr = root.optJSONArray("hr") ?: JSONArray()
        val hrRows = ArrayList<HrSample>(hr.length())
        for (i in 0 until hr.length()) {
            val row = hr.optJSONArray(i) ?: continue
            hrRows.add(HrSample(at = row.optLong(0), bpm = row.optInt(1)))
            // Written in blocks so a long file cannot hold the whole list in
            // memory twice.
            if (hrRows.size >= 2000) {
                db.hr().put(hrRows.toList())
                hrRows.clear()
            }
        }
        if (hrRows.isNotEmpty()) db.hr().put(hrRows.toList())

        val light = root.optJSONArray("light") ?: JSONArray()
        for (i in 0 until light.length()) {
            val row = light.optJSONArray(i) ?: continue
            db.light().put(
                LightSample(
                    at = row.optLong(0),
                    lux = row.optDouble(1, 0.0).toFloat(),
                    screenOn = row.optInt(2) == 1,
                    kind = row.optInt(3, LightSample.KIND_OK),
                    screenMs = row.optLong(4),
                    brightness = row.optInt(5, -1),
                )
            )
        }

        val meta = root.optJSONObject("meta") ?: JSONObject()
        val keys = meta.keys()
        var metaCount = 0
        while (keys.hasNext()) {
            val k = keys.next()
            db.meta().put(Meta(k, meta.optString(k)))
            metaCount++
        }

        val model = root.optJSONObject("model")
        if (model != null) {
            db.model().put(
                ModelState(
                    id = 1,
                    particles = model.optString("particles"),
                    updatedAt = model.optLong("updatedAt"),
                )
            )
        }

        val prefs = root.optJSONObject("prefs")
        if (prefs != null) {
            // Onboarding is deliberately forced on: a restore means the person
            // has used this app before, and walking them through setup again
            // would be the app forgetting on purpose.
            Prefs.setOnboarded(context, true)
            Prefs.setBotLang(context, prefs.optString("bot_lang", Prefs.botLang(context)))
            Prefs.setManualMode(context, prefs.optBoolean("manual_mode", Prefs.manualMode(context)))
            Prefs.setMgPerMug(context, prefs.optInt("mg_per_mug", Prefs.mgPerMug(context)))
            Prefs.setMgPerCan(context, prefs.optInt("mg_per_can", Prefs.mgPerCan(context)))
            Prefs.setEnergyOn(context, prefs.optBoolean("drink_energy", Prefs.energyOn(context)))
            Prefs.setAlcoholOn(context, prefs.optBoolean("drink_alcohol", Prefs.alcoholOn(context)))
            Prefs.setAskDrinks(context, prefs.optBoolean("ask_drinks", Prefs.askDrinks(context)))
        }

        // The cached cloud in memory belongs to the data that was here a moment
        // ago. Drop it, then rebuild from the restored nights.
        Engine.invalidate()
        runCatching { Engine.refit(context) }

        Report(
            nights = nights.length(),
            answers = answers.length(),
            hr = hr.length(),
            light = light.length(),
            meta = metaCount,
            model = model != null,
        )
    }

    // ---- null aware readers ----------------------------------------------
    //
    // optInt returns zero for a missing value, and zero is a real answer here.
    // These keep null meaning null.

    private fun optInt(o: JSONObject, key: String): Int? =
        if (o.isNull(key)) null else o.optInt(key)

    private fun optLong(o: JSONObject, key: String): Long? =
        if (o.isNull(key)) null else o.optLong(key)

    private fun optDouble(o: JSONObject, key: String): Double? =
        if (o.isNull(key)) null else o.optDouble(key)
}
