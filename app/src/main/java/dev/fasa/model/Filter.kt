package dev.fasa.model

import dev.fasa.db.Answer
import dev.fasa.db.Night
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

// Sequential Monte Carlo over the two-process model.
//
// Why a particle filter and not a fitted curve: the parameters are not
// independent. A late sleep onset can mean a long period, a shifted phase or a
// slow pressure build up, and early on there is no way to tell which. A cloud
// of weighted hypotheses represents that ambiguity honestly instead of
// collapsing it into one confident wrong number.
//
// This is the same idea Anki uses for memory, applied to circadian timing.
class Filter(val particles: MutableList<Particle>) {

    companion object {
        const val COUNT = 2000
        const val STEP_H = 5.0 / 60.0
        const val RESAMPLE_BELOW = 500.0
        const val JITTER_PHI = 0.75

        // Priors. Wide on purpose. Day one honesty beats day one confidence.
        fun prior(nowHour: Double, rnd: Random = Random.Default): Filter {
            // Circadian minimum sits roughly 2 h before habitual wake.
            // Anchor it near 05:00 local on the current day, then let the data move it.
            val anchor = Math.floor(nowHour / 24.0) * 24.0 + 5.0
            val list = MutableList(COUNT) {
                val tau = gauss(rnd, 24.6, 0.3).coerceIn(23.8, 25.6)
                Particle(
                    tau = tau,
                    phi = anchor + tau / 4.0 + gauss(rnd, 0.0, 1.2),
                    tauRise = gauss(rnd, 18.2, 2.5).coerceIn(10.0, 30.0),
                    tauFall = gauss(rnd, 4.2, 0.8).coerceIn(2.0, 8.0),
                    latency = gauss(rnd, 25.0, 15.0).coerceIn(2.0, 120.0),
                    lightGain = gauss(rnd, 0.5, 0.3).coerceIn(0.0, 1.5),
                    weight = 1.0 / COUNT,
                )
            }
            return Filter(list)
        }

        fun gauss(rnd: Random, mean: Double, sd: Double): Double {
            var u = 0.0
            var v = 0.0
            var s = 0.0
            while (s <= 0.0 || s >= 1.0) {
                u = rnd.nextDouble() * 2.0 - 1.0
                v = rnd.nextDouble() * 2.0 - 1.0
                s = u * u + v * v
            }
            return mean + sd * u * Math.sqrt(-2.0 * Math.log(s) / s)
        }

        fun fromJson(text: String): Filter? = runCatching {
            val arr = JSONArray(text)
            val list = MutableList(arr.length()) { i ->
                val p = arr.getJSONArray(i)
                Particle(
                    tau = p.getDouble(0),
                    phi = p.getDouble(1),
                    tauRise = p.getDouble(2),
                    tauFall = p.getDouble(3),
                    latency = p.getDouble(4),
                    lightGain = p.getDouble(5),
                    weight = p.getDouble(6),
                )
            }
            if (list.isEmpty()) null else Filter(list)
        }.getOrNull()
    }

    fun toJson(): String {
        val arr = JSONArray()
        for (p in particles) {
            val one = JSONArray()
            one.put(p.tau); one.put(p.phi); one.put(p.tauRise); one.put(p.tauFall)
            one.put(p.latency); one.put(p.lightGain); one.put(p.weight)
            arr.put(one)
        }
        return arr.toString()
    }

    // ---- inference -------------------------------------------------------

    // Reweight every hypothesis by how well it predicted one real night.
    // Sleep onset carries most of the information, duration adds a little.
    // sigmaScale widens the likelihood for nights we only half trust. The first
    // stored night has no observed previous wake up, so its predicted onset is
    // built on a guess; a tripled sigma lets it nudge the cloud without letting
    // an invented number dominate everything that follows.
    fun observe(
        sleepStartHour: Double,
        sleepEndHour: Double,
        wokeAtHour: Double,
        caffeineMg: Double,
        sigmaScale: Double = 1.0,
    ) {
        val duration = sleepEndHour - sleepStartHour
        if (duration <= 0.0 || duration > 16.0) return
        val sOnset = 1.2 * sigmaScale
        val sDur = 1.5 * sigmaScale

        for (p in particles) {
            val predictedGate = gateFrom(p, wokeAtHour, Physics.L0, caffeineMg)
            val predictedOnset = predictedGate + p.latency / 60.0
            val predictedWake = wakeFrom(p, predictedOnset)

            // 1.2 h sigma on onset, 1.5 h on duration, both widened by sigmaScale.
            val eOnset = (predictedOnset - sleepStartHour) / sOnset
            val eDur = ((predictedWake - predictedOnset) - duration) / sDur
            p.weight *= exp(-0.5 * (eOnset * eOnset + eDur * eDur)) + 1e-12
        }
        normalize()
        if (ess() < RESAMPLE_BELOW) resample()
    }

    fun normalize() {
        val sum = particles.sumOf { it.weight }
        if (sum <= 0.0 || sum.isNaN()) {
            val w = 1.0 / particles.size
            particles.forEach { it.weight = w }
        } else {
            particles.forEach { it.weight /= sum }
        }
    }

    fun ess(): Double {
        val s = particles.sumOf { it.weight * it.weight }
        return if (s <= 0.0) 0.0 else 1.0 / s
    }

    // Systematic resampling plus roughening, so the cloud never collapses to a
    // single overconfident point.
    fun resample(rnd: Random = Random.Default) {
        val n = particles.size
        val step = 1.0 / n
        var u = rnd.nextDouble() * step
        var acc = 0.0
        var i = 0
        val next = ArrayList<Particle>(n)
        for (j in 0 until n) {
            while (acc < u && i < n - 1) {
                acc += particles[i].weight
                i++
            }
            val src = particles[i]
            next.add(
                src.copy(
                    tau = (src.tau + gauss(rnd, 0.0, 0.04)).coerceIn(23.8, 25.6),
                    phi = src.phi + gauss(rnd, 0.0, JITTER_PHI * 0.15),
                    tauRise = (src.tauRise + gauss(rnd, 0.0, 0.3)).coerceIn(10.0, 30.0),
                    tauFall = (src.tauFall + gauss(rnd, 0.0, 0.12)).coerceIn(2.0, 8.0),
                    latency = (src.latency + gauss(rnd, 0.0, 2.0)).coerceIn(2.0, 120.0),
                    lightGain = (src.lightGain + gauss(rnd, 0.0, 0.05)).coerceIn(0.0, 1.5),
                    weight = step,
                )
            )
            u += step
        }
        particles.clear()
        particles.addAll(next)
    }

    // Free running drift. Applied once per day with no data, so a missed night
    // widens the bands instead of silently freezing yesterday's answer.
    fun advanceDays(days: Double, rnd: Random = Random.Default) {
        if (days <= 0.0) return
        for (p in particles) {
            p.phi += (p.tau - 24.0) * days
            p.phi += gauss(rnd, 0.0, 0.2 * days)
        }
    }

    // ---- simulation ------------------------------------------------------

    // Walk pressure forward from the last wake up until it crosses the gate.
    fun gateFrom(p: Particle, wokeAtHour: Double, sAtWake: Double, caffeineMg: Double): Double {
        var s = sAtWake
        var t = wokeAtHour
        val limit = wokeAtHour + 30.0
        while (t < limit) {
            val caf = Physics.caffeine(caffeineMg, t - wokeAtHour)
            if (s >= Physics.upperThreshold(t, p, caf)) return t
            s = Physics.rise(s, STEP_H, p.tauRise)
            t += STEP_H
        }
        return limit
    }

    // Walk pressure down from sleep onset until it drops below the wake threshold.
    fun wakeFrom(p: Particle, onsetHour: Double): Double {
        var s = Physics.H0 + Physics.AMP * Physics.circadian(onsetHour, p.phi, p.tau)
        var t = onsetHour
        val limit = onsetHour + 14.0
        while (t < limit) {
            if (s <= Physics.lowerThreshold(t, p)) return t
            s = Physics.fall(s, STEP_H, p.tauFall)
            t += STEP_H
        }
        return limit
    }

    // Latest onset that still reaches [targetWake] with the body ready to wake.
    // Searched on a 15 minute grid, nearest match wins.
    fun onsetForWake(p: Particle, targetWake: Double, searchFrom: Double): Double {
        var best = searchFrom
        var bestErr = Double.MAX_VALUE
        var cand = searchFrom - 4.0
        while (cand <= searchFrom + 6.0) {
            val err = abs(wakeFrom(p, cand) - targetWake)
            if (err < bestErr) {
                bestErr = err
                best = cand
            }
            cand += 0.25
        }
        return best
    }

    // ---- forecasting -----------------------------------------------------

    fun band(values: DoubleArray): Band {
        val sorted = values.clone()
        sorted.sort()
        fun at(q: Double): Double {
            val idx = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
            return sorted[idx]
        }
        return Band(median = at(0.5), low = at(0.1), high = at(0.9))
    }

    // Reverse alarm on its own, so the engine can pick the target wake time
    // only after it knows when sleep is actually expected to start.
    fun reverseBand(targetWake: Double): Band {
        val values = DoubleArray(particles.size) { i ->
            onsetForWake(particles[i], targetWake, targetWake - 8.0)
        }
        return band(values)
    }

    // A whole day of phase drift, used to rebase the cloud after a time zone change.
    fun shiftPhase(hours: Double) {
        if (hours == 0.0) return
        for (p in particles) p.phi += hours
    }

    fun forecast(
        wokeAtHour: Double,
        caffeineMg: Double,
        nights: Int,
        targetWake: Double?,
    ): Forecast {
        val n = particles.size
        val gates = DoubleArray(n)
        val onsets = DoubleArray(n)
        val wakes = DoubleArray(n)
        val reverse = if (targetWake != null) DoubleArray(n) else null

        for (i in 0 until n) {
            val p = particles[i]
            val g = gateFrom(p, wokeAtHour, Physics.L0, caffeineMg)
            val o = g + p.latency / 60.0
            gates[i] = g
            onsets[i] = o
            wakes[i] = wakeFrom(p, o)
            if (reverse != null && targetWake != null) {
                reverse[i] = onsetForWake(p, targetWake, o)
            }
        }

        val drift = particles.map { it.tau - 24.0 }.sorted()[n / 2]

        return Forecast(
            gate = band(gates),
            onset = band(onsets),
            wake = band(wakes),
            reverseAlarm = reverse?.let { band(it) },
            driftPerDay = drift,
            nights = nights,
            caffeineNow = caffeineMg,
        )
    }
}

// Convenience wrappers used by the engine.
fun Night.startHour(offsetHours: Double): Double = sleepStart / 3_600_000.0 + offsetHours
fun Night.endHour(offsetHours: Double): Double = sleepEnd / 3_600_000.0 + offsetHours
fun Answer.mugsOrZero(): Int = mugs ?: 0
