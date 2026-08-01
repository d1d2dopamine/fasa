package dev.fasa

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import dev.fasa.db.Db
import dev.fasa.health.HealthRepo
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private var lastMessage: String? = null

    private val ask = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        lastMessage = getString(
            if (granted.containsAll(HealthRepo.CORE)) R.string.perms_ok
            else R.string.perms_partial
        )
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(56, 120, 56, 56)
        }

        status = TextView(this).apply {
            textSize = 16f
            setLineSpacing(0f, 1.3f)
        }

        val perms = Button(this).apply { setText(R.string.btn_permissions) }
        val sync = Button(this).apply { setText(R.string.btn_sync) }
        val settings = Button(this).apply { setText(R.string.btn_settings) }

        root.addView(status)
        root.addView(perms)
        root.addView(sync)
        root.addView(settings)
        setContentView(root)

        perms.setOnClickListener { ask.launch(HealthRepo.ALL) }

        settings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        sync.setOnClickListener {
            sync.isEnabled = false
            status.setText(R.string.reading)
            lifecycleScope.launch {
                lastMessage = when (val r = HealthRepo.sync(this@MainActivity)) {
                    is HealthRepo.Result.Ok ->
                        getString(R.string.sync_result, r.sessions, r.added)
                    is HealthRepo.Result.Blocked -> getString(
                        when (r.reason) {
                            HealthRepo.Reason.NO_HEALTH_CONNECT -> R.string.blocked_no_hc
                            HealthRepo.Reason.NEEDS_UPDATE -> R.string.blocked_update
                            HealthRepo.Reason.NO_PERMISSION -> R.string.blocked_perms
                        }
                    )
                    is HealthRepo.Result.Failed -> getString(R.string.error_prefix, r.error)
                }
                sync.isEnabled = true
                refresh()
            }
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val db = Db.get(this@MainActivity)
            val count = db.nights().count()
            val lastEnd = db.nights().lastSleepEnd()
            val fmt = SimpleDateFormat("d MMMM, HH:mm", Locale.getDefault())

            val granted = HealthRepo.grantedSet(this@MainActivity)
            val yes = getString(R.string.state_granted)
            val no = getString(R.string.state_denied)

            status.text = buildString {
                appendLine(
                    getString(R.string.hc_label) + ": " + getString(
                        when (HealthRepo.status(this@MainActivity)) {
                            HealthRepo.Status.OK -> R.string.hc_ok
                            HealthRepo.Status.NEEDS_UPDATE -> R.string.hc_update
                            HealthRepo.Status.NOT_INSTALLED -> R.string.hc_missing
                        }
                    )
                )
                appendLine(
                    getString(R.string.perm_core) + ": " +
                        if (granted.containsAll(HealthRepo.CORE)) yes else no
                )
                appendLine(
                    getString(R.string.perm_extra) + ": " +
                        if (granted.containsAll(HealthRepo.EXTRA)) yes else no
                )
                appendLine()
                appendLine(getString(R.string.nights_count, count))
                appendLine(
                    getString(
                        R.string.last_wake,
                        lastEnd?.let { fmt.format(Date(it)) } ?: getString(R.string.none)
                    )
                )
                lastMessage?.let {
                    appendLine()
                    appendLine(it)
                }
            }
        }
    }
}
