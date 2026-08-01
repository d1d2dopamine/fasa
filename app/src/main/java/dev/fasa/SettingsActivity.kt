package dev.fasa

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import dev.fasa.diag.SelfTest
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var report: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(56, 96, 56, 96)
        }

        // ---- Language ----
        root.addView(header(getString(R.string.settings_language)))

        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val system = RadioButton(this).apply { setText(R.string.lang_system); id = 1 }
        val english = RadioButton(this).apply { setText(R.string.lang_en); id = 2 }
        val russian = RadioButton(this).apply { setText(R.string.lang_ru); id = 3 }
        group.addView(system)
        group.addView(english)
        group.addView(russian)

        val current = AppCompatDelegate.getApplicationLocales()
        group.check(
            when (current.toLanguageTags().substringBefore("-")) {
                "en" -> 2
                "ru" -> 3
                else -> 1
            }
        )

        group.setOnCheckedChangeListener { _, checked ->
            val tags = when (checked) {
                2 -> "en"
                3 -> "ru"
                else -> ""
            }
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
        }
        root.addView(group)

        // ---- Diagnostics ----
        root.addView(header(getString(R.string.settings_diagnostics)))

        root.addView(TextView(this).apply {
            setText(R.string.selftest_hint)
            textSize = 13f
            alpha = 0.7f
        })

        val run = Button(this).apply { setText(R.string.btn_selftest) }
        root.addView(run)

        report = TextView(this).apply {
            textSize = 14f
            setLineSpacing(0f, 1.35f)
            setPadding(0, 24, 0, 0)
        }
        root.addView(report)

        run.setOnClickListener {
            run.isEnabled = false
            report.setText(R.string.selftest_running)
            lifecycleScope.launch {
                val r = SelfTest.run(this@SettingsActivity)
                report.text = buildString {
                    for (line in r.lines) {
                        appendLine(icon(line.level) + "  " + line.text)
                    }
                    appendLine()
                    appendLine(icon(r.verdict.level) + "  " + r.verdict.text)
                }
                report.setTextColor(
                    when (r.verdict.level) {
                        SelfTest.Level.OK -> Color.parseColor("#2E7D32")
                        SelfTest.Level.WARN -> Color.parseColor("#B26A00")
                        SelfTest.Level.FAIL -> Color.parseColor("#C62828")
                    }
                )
                run.isEnabled = true
            }
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun icon(level: SelfTest.Level): String = when (level) {
        SelfTest.Level.OK -> "\u2705"
        SelfTest.Level.WARN -> "\u26a0\ufe0f"
        SelfTest.Level.FAIL -> "\u274c"
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setPadding(0, 48, 0, 16)
    }
}
