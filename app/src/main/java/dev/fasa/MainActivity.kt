package dev.fasa

import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import dev.fasa.db.Db
import dev.fasa.db.Meta
import dev.fasa.health.HealthRepo
import dev.fasa.model.Band
import dev.fasa.model.Engine
import dev.fasa.model.Forecast
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class Tab { TODAY, DRIFT, MODEL, DATA }

    private lateinit var content: LinearLayout
    private var tab = Tab.TODAY
    private var notice: String? = null

    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val clockDay = SimpleDateFormat("EEE HH:mm", Locale.getDefault())

    private val ask = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        notice = getString(
            if (granted.containsAll(HealthRepo.CORE)) R.string.perms_ok
            else R.string.perms_partial
        )
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 24)
        }

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        tabs.addView(tabButton(R.string.tab_today, Tab.TODAY))
        tabs.addView(tabButton(R.string.tab_drift, Tab.DRIFT))
        tabs.addView(tabButton(R.string.tab_model, Tab.MODEL))
        tabs.addView(tabButton(R.string.tab_data, Tab.DATA))
        root.addView(tabs)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 32, 0, 0)
        }
        root.addView(ScrollView(this).apply { addView(content) })

        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun tabButton(labelRes: Int, target: Tab) = Button(this).apply {
        setText(labelRes)
        textSize = 12f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener {
            tab = target
            notice = null
            render()
        }
    }

    // ---- rendering -------------------------------------------------------

    private fun render() {
        content.removeAllViews()
        when (tab) {
            Tab.TODAY -> renderForecast(detailed = false)
            Tab.DRIFT -> renderForecast(detailed = true)
            Tab.MODEL -> renderModel()
            Tab.DATA -> renderData()
        }
    }

    private fun renderForecast(detailed: Boolean) {
        content.addView(body(getString(R.string.computing)))
        lifecycleScope.launch {
            val f = runCatching { Engine.forecast(this@MainActivity) }.getOrNull()
            content.removeAllViews()

            if (f == null) {
                content.addView(body(getString(R.string.f_error)))
                return@launch
            }

            if (f.nights == 0) {
                content.addView(callout(getString(R.string.f_cold)))
            }

            content.addView(prediction(R.string.f_gate, f.gate))
            content.addView(prediction(R.string.f_onset, f.onset))
            content.addView(prediction(R.string.f_wake, f.wake))

            if (f.reverseAlarm != null) {
                content.addView(prediction(R.string.f_reverse, f.reverseAlarm))
            } else {
                content.addView(body(getString(R.string.f_no_alarm)))
            }

            content.addView(alarmButton())

            if (f.caffeineNow >= 1.0) {
                content.addView(body(getString(R.string.f_caffeine, f.caffeineNow)))
            }

            if (detailed) renderDrift(f)
        }
    }

    private fun renderDrift(f: Forecast) {
        content.addView(header(getString(R.string.tab_drift)))

        val minutes = (f.driftPerDay * 60.0).roundToInt()
        val human = if (abs(minutes) >= 60) {
            getString(R.string.f_drift_hm, minutes / 60, abs(minutes % 60))
        } else {
            getString(R.string.f_drift_m, minutes)
        }
        content.addView(body(getString(R.string.f_drift, human)))
        content.addView(body(getString(R.string.f_drift_hint)))

        // Where the same gate lands over the coming week if nothing intervenes.
        content.addView(header(getString(R.string.f_week)))
        for (day in 1..7) {
            val shifted = f.gate.median + f.driftPerDay * day
            content.addView(
                body(
                    getString(
                        R.string.f_week_row,
                        day,
                        clockDay.format(Date(Engine.millisOf(shifted))),
                    )
                )
            )
        }
    }

    private fun renderModel() {
        content.addView(body(getString(R.string.computing)))
        lifecycleScope.launch {
            val filter = runCatching { Engine.load(this@MainActivity) }.getOrNull()
            val nights = Db.get(this@MainActivity).nights().count()
            content.removeAllViews()

            if (filter == null) {
                content.addView(body(getString(R.string.f_error)))
                return@launch
            }

            fun median(pick: (dev.fasa.model.Particle) -> Double): Double =
                filter.particles.map(pick).sorted()[filter.particles.size / 2]

            content.addView(body(getString(R.string.f_nights, nights)))
            content.addView(body(getString(R.string.f_tau, median { it.tau })))
            content.addView(body(getString(R.string.f_latency, median { it.latency })))
            content.addView(body(getString(R.string.f_rise, median { it.tauRise })))
            content.addView(body(getString(R.string.f_fall, median { it.tauFall })))
            content.addView(
                body(
                    getString(
                        R.string.f_particles,
                        filter.particles.size,
                        filter.ess().roundToInt(),
                    )
                )
            )
            content.addView(body(getString(R.string.f_model_hint)))

            content.addView(Button(this@MainActivity).apply {
                setText(R.string.btn_refit)
                setOnClickListener {
                    isEnabled = false
                    lifecycleScope.launch {
                        Engine.refit(this@MainActivity)
                        render()
                    }
                }
            })
        }
    }

    private fun renderData() {
        content.addView(body(getString(R.string.computing)))
        lifecycleScope.launch {
            val db = Db.get(this@MainActivity)
            val count = db.nights().count()
            val lastEnd = db.nights().lastSleepEnd()
            val granted = HealthRepo.grantedSet(this@MainActivity)
            val yes = getString(R.string.state_granted)
            val no = getString(R.string.state_denied)

            content.removeAllViews()
            content.addView(
                body(
                    getString(R.string.hc_label) + ": " + getString(
                        when (HealthRepo.status(this@MainActivity)) {
                            HealthRepo.Status.OK -> R.string.hc_ok
                            HealthRepo.Status.NEEDS_UPDATE -> R.string.hc_update
                            HealthRepo.Status.NOT_INSTALLED -> R.string.hc_missing
                        }
                    )
                )
            )
            content.addView(
                body(
                    getString(R.string.perm_core) + ": " +
                        if (granted.containsAll(HealthRepo.CORE)) yes else no
                )
            )
            content.addView(
                body(
                    getString(R.string.perm_extra) + ": " +
                        if (granted.containsAll(HealthRepo.EXTRA)) yes else no
                )
            )
            content.addView(body(getString(R.string.nights_count, count)))
            content.addView(
                body(
                    getString(
                        R.string.last_wake,
                        lastEnd?.let { clockDay.format(Date(it)) } ?: getString(R.string.none)
                    )
                )
            )

            notice?.let { content.addView(callout(it)) }

            content.addView(Button(this@MainActivity).apply {
                setText(R.string.btn_permissions)
                setOnClickListener { ask.launch(HealthRepo.ALL) }
            })

            content.addView(Button(this@MainActivity).apply {
                setText(R.string.btn_sync)
                setOnClickListener {
                    isEnabled = false
                    val button = this
                    lifecycleScope.launch {
                        notice = when (val r = HealthRepo.sync(this@MainActivity)) {
                            is HealthRepo.Result.Ok ->
                                getString(R.string.sync_result, r.sessions, r.added)
                            is HealthRepo.Result.Blocked -> getString(
                                when (r.reason) {
                                    HealthRepo.Reason.NO_HEALTH_CONNECT -> R.string.blocked_no_hc
                                    HealthRepo.Reason.NEEDS_UPDATE -> R.string.blocked_update
                                    HealthRepo.Reason.NO_PERMISSION -> R.string.blocked_perms
                                }
                            )
                            is HealthRepo.Result.Failed ->
                                getString(R.string.error_prefix, r.error)
                        }
                        // New nights change the model, so refit before showing anything.
                        Engine.refit(this@MainActivity)
                        button.isEnabled = true
                        render()
                    }
                }
            })

            content.addView(Button(this@MainActivity).apply {
                setText(R.string.btn_settings)
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
            })
        }
    }

    // ---- pieces ----------------------------------------------------------

    private fun alarmButton() = Button(this).apply {
        setText(R.string.btn_alarm)
        setOnClickListener {
            val cal = Calendar.getInstance()
            TimePickerDialog(
                this@MainActivity,
                { _, h, m ->
                    lifecycleScope.launch {
                        Db.get(this@MainActivity).meta().put(
                            Meta(Engine.KEY_ALARM, String.format(Locale.US, "%02d:%02d", h, m))
                        )
                        render()
                    }
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                true,
            ).show()
        }
    }

    // A prediction is never a bare time. It always carries its own uncertainty,
    // because a confident wrong bedtime is worse than an honest range.
    private fun prediction(labelRes: Int, band: Band): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 20, 0, 20)
        }

        val percent = (band.confidence * 100).roundToInt()
        val mark = when {
            percent >= 75 -> "\uD83D\uDFE2"
            percent >= 50 -> "\uD83D\uDFE1"
            else -> "\u26AA"
        }

        box.addView(TextView(this).apply {
            text = mark + "  " + getString(labelRes)
            textSize = 14f
            alpha = 0.75f
        })

        // Below 50 percent a single number would be a lie. Show the range only.
        box.addView(TextView(this).apply {
            text = if (percent >= 50) {
                clock.format(Date(Engine.millisOf(band.median)))
            } else {
                getString(
                    R.string.f_range,
                    clock.format(Date(Engine.millisOf(band.low))),
                    clock.format(Date(Engine.millisOf(band.high))),
                )
            }
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })

        box.addView(TextView(this).apply {
            text = if (percent >= 50) {
                getString(
                    R.string.f_range,
                    clock.format(Date(Engine.millisOf(band.low))),
                    clock.format(Date(Engine.millisOf(band.high))),
                ) + "  \u00B7  " + getString(R.string.f_conf, percent)
            } else {
                getString(R.string.f_conf, percent)
            }
            textSize = 13f
            alpha = 0.65f
        })

        return box
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setPadding(0, 40, 0, 12)
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setPadding(0, 8, 0, 8)
        setLineSpacing(0f, 1.25f)
    }

    private fun callout(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(28, 24, 28, 24)
        alpha = 0.85f
    }
}
