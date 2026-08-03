package dev.vespian.export

import android.content.Context
import dev.vespian.db.Db
import dev.vespian.db.Forced
import dev.vespian.db.Night
import dev.vespian.model.Engine
import dev.vespian.model.Physics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

// One self describing JSON file, in English, meant to be handed to a doctor or
// pasted into a language model.
//
// Everything a reader needs to judge the data is inside the file itself: which
// device produced it, through which pipeline, what the known limitations are.
// A bare table of numbers invites confident wrong conclusions.
object Export {

    const val FORMAT_VERSION = 3

    // Thirty days of five minute bins is about eight thousand rows. Longer than
    // that and the file stops being something a person can open.
    private const val HR_EXPORT_DAYS = 30L

    fun fileName(): String = "vespian-export-" + LocalDate.now() + ".json"

    suspend fun build(context: Context): String = withContext(Dispatchers.IO) {
        val db = Db.get(context)
        val zone = ZoneId.systemDefault()
        val offset = Engine.offsetHours(zone)

        val nights = db.nights().all()
        val answers = db.answers().last(10_000).associateBy { it.dateKey }
        // Without this flag a reader cannot tell a night the body ended from a
        // night an alarm ended, and the two mean opposite things.
        val forced = runCatching { Forced.all(context) }.getOrDefault(emptySet())

        val root = JSONObject()
        root.put("format", "vespian-sleep-export")
        root.put("format_version", FORMAT_VERSION)
        root.put("generated_at_utc", Instant.now().toString())
        root.put("time_zone", zone.id)
        root.put("utc_offset_hours", offset)

        root.put("source", source())
        root.put("reading_notes", notes())
        root.put("subject", subject())

        val arr = JSONArray()
        for (n in nights) arr.put(
            nightJson(
                n,
                zone,
                answers[n.dateKey]?.mood,
                answers[n.dateKey]?.mugs,
                forced.contains(n.dateKey),
                answers[n.dateKey]?.cans,
                answers[n.dateKey]?.alcohol,
            )
        )
        root.put("nights", arr)

        val ans = JSONArray()
        for (a in answers.values.sortedBy { it.dateKey }) {
            val o = JSONObject()
            o.put("date", a.dateKey)
            o.put("morning_wellbeing_1_to_5", a.mood ?: JSONObject.NULL)
            o.put("coffee_mugs_previous_day", a.mugs ?: JSONObject.NULL)
            o.put("energy_drink_cans_previous_day", a.cans ?: JSONObject.NULL)
            o.put("alcohol_standard_drinks_previous_day", a.alcohol ?: JSONObject.NULL)
            o.put("mug_volume_ml", 430)
            o.put("caffeine_mg_per_mug", Prefs.mgPerMug(context))
            o.put("caffeine_mg_per_can", Prefs.mgPerCan(context))
            o.put("answered_at_utc", Instant.ofEpochMilli(a.at).toString())
            ans.put(o)
        }
        root.put("self_reports", ans)

        root.put("heart_rate", heartRate(context, zone))
        root.put("light_daily", lightDaily(context, zone))
        root.put("model", modelJson(context))
        root.put("summary", summary(nights, offset, answers.mapValues { it.value.mugs ?: 0 }))

        root.toString(2)
    }

    // ---- header sections -------------------------------------------------

    private fun source(): JSONObject {
        val o = JSONObject()
        o.put("application", "vespian")
        o.put("application_url", "https://github.com/d1d2dopamine/vespian")
        o.put("platform", "Android")
        o.put("wearable", "Xiaomi Smart Band 9 Active")
        o.put("wearable_vendor_app", "Mi Fitness")
        o.put("pipeline", "Xiaomi Smart Band 9 Active -> Mi Fitness -> Android Health Connect -> vespian local database")
        o.put("sensors_available", JSONArray(listOf("optical heart rate", "accelerometer", "ambient light (phone)")))
        o.put("sensors_absent", JSONArray(listOf("SpO2 (this band model has no blood oxygen sensor)")))
        return o
    }

    private fun notes(): JSONArray = JSONArray(
        listOf(
            "This file was produced by vespian, a personal circadian tracking app. It is raw observational data, not a medical record.",
            "Sleep stages come from a consumer wrist tracker using heart rate and motion. Wrist actigraphy agrees with polysomnography on total sleep time reasonably well, but stage boundaries (deep, REM, light) are estimates and should not be read as clinical staging.",
            "Sleep onset is the moment the tracker judged sleep began, not the moment the subject went to bed. The gap between the two is not measured here.",
            "Naps and sleep episodes shorter than 120 minutes are stored but excluded from the model.",
            "All timestamps are given twice: local wall clock and UTC. Local time is the subject's own time zone at the moment of recording.",
            "Gaps in the record usually mean the band was not worn or was not synchronised, not that the subject was awake.",
            "Ambient light is sampled by the phone, not the band, and only reflects light reaching the phone.",
            "Self reported wellbeing is a 1 to 5 scale collected once each morning: 1 = wrecked, 3 = normal, 5 = excellent.",
        )
    )

    private fun subject(): JSONObject {
        val o = JSONObject()
        o.put("reported_conditions", JSONArray(listOf("ADHD", "Delayed Sleep Phase Syndrome (self reported)")))
        o.put(
            "context",
            "Bed and wake times are expected to be irregular and to drift later over time. " +
                "Irregularity here is the phenomenon under study, not non compliance."
        )
        return o
    }

    // ---- nights ----------------------------------------------------------

    private fun nightJson(
        n: Night,
        zone: ZoneId,
        mood: Int?,
        mugs: Int?,
        forcedWake: Boolean,
        cans: Int?,
        alcohol: Int?,
    ): JSONObject {
        val o = JSONObject()
        o.put("date", n.dateKey)
        o.put("sleep_start_local", local(n.sleepStart, zone))
        o.put("sleep_start_utc", Instant.ofEpochMilli(n.sleepStart).toString())
        o.put("sleep_end_local", local(n.sleepEnd, zone))
        o.put("sleep_end_utc", Instant.ofEpochMilli(n.sleepEnd).toString())
        o.put("time_in_bed_minutes", ((n.sleepEnd - n.sleepStart) / 60000L).toInt())
        o.put("asleep_minutes", n.minutesAsleep)
        o.put("deep_minutes", n.minutesDeep)
        o.put("rem_minutes", n.minutesRem)
        o.put(
            "light_minutes",
            (n.minutesAsleep - n.minutesDeep - n.minutesRem).coerceAtLeast(0)
        )
        o.put("awake_minutes", n.minutesAwake)
        val eff = if (n.sleepEnd > n.sleepStart)
            n.minutesAsleep * 60000.0 / (n.sleepEnd - n.sleepStart) else 0.0
        o.put("sleep_efficiency", round2(eff))
        o.put("heart_rate_min_bpm", n.hrMin ?: JSONObject.NULL)
        o.put(
            "heart_rate_min_at_local",
            n.hrMinAt?.let { local(it, zone) } ?: JSONObject.NULL
        )
        o.put("heart_rate_mean_bpm", n.hrMean ?: JSONObject.NULL)
        o.put("spo2_mean_percent", n.spo2Mean ?: JSONObject.NULL)
        o.put("morning_wellbeing_1_to_5", mood ?: JSONObject.NULL)
        o.put("coffee_mugs_previous_day", mugs ?: JSONObject.NULL)
        o.put("energy_drink_cans_previous_day", cans ?: JSONObject.NULL)
        o.put("alcohol_standard_drinks_previous_day", alcohol ?: JSONObject.NULL)
        o.put("wake_was_forced", forcedWake)
        o.put("source", n.source)
        return o
    }

    // ---- heart rate ------------------------------------------------------

    // The raw pulse series, five minute bins, thirty days. This is the series
    // the circadian anchor is fitted to, so leaving it out would make the model
    // section impossible to check.
    private suspend fun heartRate(context: Context, zone: ZoneId): JSONObject {
        val db = Db.get(context)
        val now = System.currentTimeMillis()
        val from = now - HR_EXPORT_DAYS * 24 * 3600 * 1000L
        val samples = runCatching { db.hr().between(from, now) }.getOrDefault(emptyList())
        val o = JSONObject()
        o.put("bin_minutes", 5)
        o.put("window_days", HR_EXPORT_DAYS)
        o.put("samples", samples.size)
        val arr = JSONArray()
        for (s in samples) {
            val j = JSONObject()
            j.put("local", local(s.at, zone))
            j.put("bpm", s.bpm)
            arr.put(j)
        }
        o.put("series", arr)
        return o
    }

    // ---- light -----------------------------------------------------------

    // Raw light is 288 samples a day and useless to a human reader. A daily
    // digest keeps the clinically interesting part: how much bright light the
    // subject actually saw, and when.
    private suspend fun lightDaily(context: Context, zone: ZoneId): JSONArray {
        val db = Db.get(context)
        val now = System.currentTimeMillis()
        val from = now - 90L * 24 * 3600 * 1000
        val samples = runCatching { db.light().between(from, now) }.getOrDefault(emptyList())
        val out = JSONArray()
        if (samples.isEmpty()) return out

        samples.groupBy {
            Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate().toString()
        }.toSortedMap().forEach { (date, list) ->
            // A reading taken with the phone in a pocket is not a reading of
            // the room, and a window where the sensor said nothing is not a
            // dark room either. Counting either of them as measured light is
            // how the log ends up claiming a day that never happened, so the
            // statistics below are computed from trusted readings only and the
            // rejected ones are reported separately rather than hidden.
            val trusted = list.filter { it.kind == LightSample.KIND_OK }
            val o = JSONObject()
            o.put("date", date)
            o.put("samples", list.size)
            o.put("samples_trusted", trusted.size)
            o.put("samples_occluded", list.count { it.kind == LightSample.KIND_OCCLUDED })
            o.put("samples_missing", list.count { it.kind == LightSample.KIND_GAP })

            // Phone use, which the light sensor cannot see. Screen light at the
            // eye is far too weak to move the body clock, so this is behaviour,
            // not light exposure, and it is reported as its own quantity.
            o.put("screen_minutes", (list.sumOf { it.screenMs } / 60000L).toInt())
            val brightness = list.map { it.brightness }.filter { it >= 0 }.sorted()
            o.put(
                "screen_brightness_median",
                if (brightness.isEmpty()) JSONObject.NULL else brightness[brightness.size / 2]
            )

            if (trusted.isEmpty()) {
                out.put(o)
                return@forEach
            }

            val lux = trusted.map { it.lux }.sorted()
            o.put("peak_lux", lux.last().toInt())
            o.put("median_lux", lux[lux.size / 2].toInt())
            // Each sample stands for one five minute window. Three thresholds
            // instead of one: a thousand lux is outdoor daylight and indoors
            // it is simply never reached, so on its own it reports zero every
            // day and says nothing about the light this person actually lived
            // in. A hundred is ordinary room light, five hundred is a bright
            // room or a window seat.
            o.put("minutes_above_1000_lux", trusted.count { it.lux >= 1000f } * 5)
            o.put("minutes_above_500_lux", trusted.count { it.lux >= 500f } * 5)
            o.put("minutes_above_100_lux", trusted.count { it.lux >= 100f } * 5)
            val bright = trusted.filter { it.lux >= 500f }.minByOrNull { it.at }
            o.put(
                "first_bright_light_local",
                bright?.let { local(it.at, zone) } ?: JSONObject.NULL
            )
            out.put(o)
        }
        return out
    }

    // ---- model -----------------------------------------------------------

    private suspend fun modelJson(context: Context): JSONObject {
        val o = JSONObject()
        o.put(
            "description",
            "Two process model of sleep regulation (Borbely) fitted with a sequential Monte Carlo " +
                "particle filter. Values are posterior medians over 2000 hypotheses."
        )
        val f = runCatching { Engine.load(context) }.getOrNull() ?: return o
        fun med(sel: (dev.vespian.model.Particle) -> Double): Double {
            val v = f.particles.map(sel).sorted()
            return round2(v[v.size / 2])
        }
        o.put("intrinsic_period_hours", med { it.tau })
        o.put("drift_minutes_per_day", round2((med { it.tau } - 24.0) * 60.0))
        o.put("sleep_pressure_rise_time_constant_hours", med { it.tauRise })
        o.put("sleep_pressure_fall_time_constant_hours", med { it.tauFall })
        o.put("sleep_latency_minutes", med { it.latency })
        o.put("particles", f.particles.size)
        o.put("effective_sample_size", f.ess().roundToInt())
        return o
    }

    // ---- summary ---------------------------------------------------------

    private fun summary(
        nights: List<Night>,
        offset: Double,
        mugsByDate: Map<String, Int>,
    ): JSONObject {
        val o = JSONObject()
        val real = nights.filter { it.sleepEnd - it.sleepStart >= 2 * 3600_000L }
        o.put("nights_recorded", nights.size)
        o.put("nights_used_by_model", real.size)
        if (real.isEmpty()) return o

        o.put("first_night", real.first().dateKey)
        o.put("last_night", real.last().dateKey)

        val onsets = real.map { clock(it.sleepStart, offset) }
        val wakes = real.map { clock(it.sleepEnd, offset) }
        val durations = real.map { it.minutesAsleep.toDouble() }

        o.put("mean_sleep_onset_local_hour", round2(circMean(onsets)))
        o.put("sleep_onset_sd_hours", round2(circSd(onsets)))
        o.put("mean_wake_local_hour", round2(circMean(wakes)))
        o.put("wake_sd_hours", round2(circSd(wakes)))
        o.put("mean_asleep_minutes", round2(durations.average()))
        o.put("asleep_minutes_sd", round2(sd(durations)))

        val deep = real.filter { it.minutesAsleep > 0 }
            .map { it.minutesDeep * 100.0 / it.minutesAsleep }
        if (deep.isNotEmpty()) o.put("mean_deep_sleep_percent", round2(deep.average()))
        val rem = real.filter { it.minutesAsleep > 0 }
            .map { it.minutesRem * 100.0 / it.minutesAsleep }
        if (rem.isNotEmpty()) o.put("mean_rem_sleep_percent", round2(rem.average()))

        // Onset by caffeine dose. Not a controlled experiment, but the single
        // most useful cross tabulation in the whole file.
        val byMugs = JSONArray()
        real.groupBy { mugsByDate[it.dateKey] }
            .filterKeys { it != null }
            .toSortedMap(compareBy { it })
            .forEach { (mugs, list) ->
                val j = JSONObject()
                j.put("coffee_mugs", mugs)
                j.put("nights", list.size)
                j.put(
                    "mean_sleep_onset_local_hour",
                    round2(circMean(list.map { clock(it.sleepStart, offset) }))
                )
                j.put("mean_asleep_minutes", round2(list.map { it.minutesAsleep.toDouble() }.average()))
                byMugs.put(j)
            }
        if (byMugs.length() > 0) o.put("onset_by_caffeine", byMugs)

        // Observed drift: how far the onset moved per day across the record.
        if (real.size >= 3) {
            val firstH = Engine.hourOf(real.first().sleepStart, offset)
            val lastH = Engine.hourOf(real.last().sleepStart, offset)
            val days = (lastH - firstH) / 24.0
            if (days > 1.0) {
                var shift = (lastH - firstH) - Math.round((lastH - firstH) / 24.0) * 24.0
                if (abs(shift) > 12.0) shift -= Math.signum(shift) * 24.0
                o.put("observed_onset_shift_minutes_per_day", round2(shift * 60.0 / days))
            }
        }
        return o
    }

    // ---- helpers ---------------------------------------------------------

    private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    private fun local(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(FMT)

    // Clock hour in 0..24, local.
    private fun clock(millis: Long, offset: Double): Double {
        var h = Engine.hourOf(millis, offset) % 24.0
        if (h < 0) h += 24.0
        return h
    }

    // Clock times wrap, so a plain average of 23:30 and 00:30 would give noon.
    private fun circMean(hours: List<Double>): Double {
        var x = 0.0
        var y = 0.0
        for (h in hours) {
            val a = h / 24.0 * 2 * Math.PI
            x += Math.cos(a)
            y += Math.sin(a)
        }
        var mean = Math.atan2(y / hours.size, x / hours.size) / (2 * Math.PI) * 24.0
        if (mean < 0) mean += 24.0
        return mean
    }

    private fun circSd(hours: List<Double>): Double {
        var x = 0.0
        var y = 0.0
        for (h in hours) {
            val a = h / 24.0 * 2 * Math.PI
            x += Math.cos(a)
            y += Math.sin(a)
        }
        val r = sqrt(x * x + y * y) / hours.size
        if (r >= 1.0) return 0.0
        return sqrt(-2.0 * Math.log(r)) / (2 * Math.PI) * 24.0
    }

    private fun sd(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val m = values.average()
        return sqrt(values.sumOf { (it - m) * (it - m) } / (values.size - 1))
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
