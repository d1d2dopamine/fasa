package dev.vespian.model

import dev.vespian.db.Answer
import dev.vespian.db.Night
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

        // Asleep this fast after the screen went dark means the gate was
        // already open and the person simply had not gone to bed yet.
        const val CENSOR_BELOW_MIN = 12.0

        // Sigma on a directly measured sleep latency, in minutes.
        const val LATENCY_SIGMA_MIN = 20.0

        // Sigma on the nightly heart rate minimum as a phase marker, in hours.
        const val NADIR_SIGMA_H = 1.5

        // A single stretch of light may not move the whole cloud further than
        // this. A stuck or blinded sensor is a plausible failure; two hours of
        // real phase shift in one day is not.
        const val MAX_LIGHT_SHIFT_H = 2.0

        // Where the circadian minimum sits for a delayed phase when nothing is
        // known yet, in hours after local midnight. Objective phase markers in
        // delayed sleep phase run roughly an hour and a half later than in
        // unaffected sleepers, so starting the search at a normal 05:00 puts
        // the whole cloud in the wrong place and wastes the first nights
        // dragging it back.
        const val BLIND_NADIR_HOUR = 6.5

        // Spread of the starting guess about that minimum, in hours. Tighter
        // when the first real night is available to place it, because then it
        // is an estimate rather than a population average.
        const val SEED_SD_BLIND = 1.2
        const val SEED_SD_DATA = 0.8

        // Priors. Wide on purpose. Day one honesty beats day one confidence.
        //
        // Where this cloud starts matters more than anything else on short
        // records: a particle filter over circadian phase is only as good as
        // its initialisation until many days have accumulated. So when there is
        // already a night on disk, the starting guess is placed on it rather
        // than on a population average. [seedNadirHour] is the observed
        // circadian low in absolute local hours, taken from the nightly heart
        // rate minimum where it exists.
        fun prior(
            nowHour: Double,
            seedNadirHour: Double? = null,
            rnd: Random = Random.Default,
        ): Filter {
            val anchor = seedNadirHour
                ?: (Math.floor(nowHour / 24.0) * 24.0 + BLIND_NADIR_HOUR)
            val sd = if (seedNadirHour != null) SEED_SD_DATA else SEED_SD_BLIND
            val list = MutableList(COUNT) {
                val tau = gauss(rnd, 24.6, 0.3).coerceIn(23.8, 25.6)
                Particle(
                    tau = tau,
                    phi = anchor + tau / 4.0 + gauss(rnd, 0.0, sd),
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
                    // Clouds saved before pressure was carried between nights
                    // have seven fields; they start again from the floor.
                    sWake = if (p.length() > 7) p.getDouble(7) else Physics.L0,
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
            one.put(p.latency); one.put(p.lightGain); one.put(p.weight); one.put(p.sWake)
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
    //
    // A late onset does not always mean a late body clock. Staying on the phone
    // long past an open sleep gate produces exactly the same number, and
    // scoring that as biology inflates the estimated period. So a night where
    // sleep began within minutes of the screen going dark is not a measurement
    // of the gate at all: it only says the gate opened at or before that
    // moment. That is a censored observation, and it is scored with a one
    // sided penalty. Hypotheses with an earlier gate stay alive, which is the
    // honest answer, because the night contains no evidence against them.
    //
    // The nightly heart rate minimum is the opposite kind of evidence. It
    // arrives on the body's own schedule whatever the person chose to do, so it
    // anchors phase where behaviour cannot reach.
    fun observe(
        sleepStartHour: Double,
        sleepEndHour: Double,
        wokeAtHour: Double,
        caffeineMg: Double,
        sigmaScale: Double = 1.0,
        bedHour: Double? = null,
        hrMinHour: Double? = null,
        nadirScale: Double = 1.0,
        forcedWake: Boolean = false,
    ) {
        val duration = sleepEndHour - sleepStartHour
        if (duration <= 0.0 || duration > 16.0) return
        val sOnset = 1.2 * sigmaScale
        val sDur = 1.5
        val sLat = LATENCY_SIGMA_MIN * sigmaScale
        // A clock anchor read off a well fitted daily curve deserves more trust
        // than one read off a noisy day. The caller widens or narrows this
        // according to how well the curve actually described the readings, so a
        // day the band barely measured cannot drag phase around as hard as a
        // clean one.
        val sNadir = NADIR_SIGMA_H * sigmaScale * nadirScale

        // Minutes between the screen going dark and sleep actually starting.
        val gapMin = if (bedHour != null && bedHour <= sleepStartHour) {
            (sleepStartHour - bedHour) * 60.0
        } else {
            null
        }
        val censored = gapMin != null && gapMin < CENSOR_BELOW_MIN

        // A real stopwatch on falling asleep, but only when the person actually
        // waited for sleep. A censored night measures impatience, not the body.
        val measuredLatency = if (gapMin != null && !censored && gapMin <= 180.0) gapMin else null

        for (p in particles) {
            val predictedGate = gateFrom(p, wokeAtHour, p.sWake, caffeineMg)
            val predictedOnset = predictedGate + p.latency / 60.0
            val predictedWake = wakeFrom(p, sleepStartHour)

            var e2 = 0.0

            if (censored && bedHour != null) {
                // Only a contradiction costs anything: the gate cannot open
                // after the moment this person was already asleep.
                val over = predictedGate - bedHour
                if (over > 0.0) {
                    val e = over / sOnset
                    e2 += e * e
                }
            } else {
                val e = (predictedOnset - sleepStartHour) / sOnset
                e2 += e * e
            }

            // Duration is scored from the onset the band actually measured, so
            // an error in the predicted onset is not charged twice.
            //
            // A night ended by an alarm or by another person only says the body
            // would have slept at least this long. Hypotheses predicting a
            // later wake up are not contradicted by it, so only an earlier
            // prediction costs anything. Mirror image of the onset censoring.
            val eWake = (predictedWake - sleepEndHour) / sDur
            if (!forcedWake || eWake < 0.0) e2 += eWake * eWake

            if (measuredLatency != null) {
                val e = (p.latency - measuredLatency) / sLat
                e2 += e * e
            }

            if (hrMinHour != null) {
                val e = nadirError(p, hrMinHour) / sNadir
                e2 += e * e
            }

            p.weight *= exp(-0.5 * e2) + 1e-12
            p.sWake = pressureAfter(p, sleepStartHour, sleepEndHour)
        }
        normalize()
        if (ess() < RESAMPLE_BELOW) resample()
    }

    // Distance from an observed circadian low to the nearest one this
    // hypothesis predicts. The model's minimum sits a quarter period before phi.
    fun nadirError(p: Particle, observedHour: Double): Double {
        val nadir = p.phi - p.tau / 4.0
        val k = Math.round((observedHour - nadir) / p.tau).toDouble()
        return (nadir + k * p.tau) - observedHour
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

    // Push every hypothesis through a stretch of measured light.
    //
    // Each sample is (absolute local hour, lux) and stands for the interval up
    // to the next one. The gap is capped, so a hole in the log cannot be read
    // as hours of steady illumination.
    //
    // The shift is computed against each hypothesis' own circadian minimum,
    // which is what turns the light log into evidence: a hypothesis that puts
    // the minimum at 04:00 and one that puts it at 08:00 are moved by different
    // amounts by the very same evening, and the nights that follow then favour
    // whichever of them ends up predicting sleep correctly.
    fun applyLight(samples: List<DoubleArray>) {
        if (samples.size < 2) return
        for (p in particles) {
            val nadir = p.phi - p.tau / 4.0
            var shift = 0.0
            for (i in 0 until samples.size - 1) {
                val hour = samples[i][0]
                val dt = (samples[i + 1][0] - hour).coerceIn(0.0, 0.25)
                if (dt <= 0.0) continue
                shift += Physics.K_LIGHT * p.lightGain *
                    Physics.prc(hour - nadir) * Physics.dose(samples[i][1]) * dt
            }
            p.phi += shift.coerceIn(-MAX_LIGHT_SHIFT_H, MAX_LIGHT_SHIFT_H)
        }
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

    // Where sleep pressure actually stood at the moment this person got up.
    // A night cut short leaves it above the floor, and that leftover is what
    // carries into the next day instead of being written off.
    fun pressureAfter(p: Particle, onsetHour: Double, wakeHour: Double): Double {
        var s = Physics.H0 + Physics.AMP * Physics.circadian(onsetHour, p.phi, p.tau)
        var t = onsetHour
        while (t < wakeHour) {
            s = Physics.fall(s, STEP_H, p.tauFall)
            t += STEP_H
        }
        return s.coerceAtLeast(Physics.L0)
    }

    // Weighted mean of the pressure left above the floor at the last wake up.
    // Zero means the body finished the discharge by itself.
    fun debtPressure(): Double {
        var sum = 0.0
        var w = 0.0
        for (p in particles) {
            sum += p.weight * (p.sWake - Physics.L0)
            w += p.weight
        }
        if (w <= 0.0) return 0.0
        return (sum / w).coerceAtLeast(0.0)
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
            val g = gateFrom(p, wokeAtHour, p.sWake, caffeineMg)
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
