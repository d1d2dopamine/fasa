package dev.fasa

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.fasa.db.Db
import dev.fasa.db.Meta
import dev.fasa.health.HealthRepo
import dev.fasa.export.Export
import dev.fasa.model.Band
import dev.fasa.model.Delay
import dev.fasa.model.Engine
import dev.fasa.model.Filter
import dev.fasa.model.Forecast
import dev.fasa.ui.DayRing
import dev.fasa.ui.DriftChart
import dev.fasa.ui.Histogram
import dev.fasa.ui.FasaTheme
import dev.fasa.ui.RingArc
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // First run goes through the permission walkthrough instead. Without
        // those grants nothing can be collected in the background.
        if (!Prefs.onboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContent {
            FasaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppScreen()
                }
            }
        }
    }
}

private enum class Tab { TODAY, DRIFT, MODEL, DATA }

private fun hhmm(hour: Double): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(Engine.millisOf(hour)))

private fun dayTime(millis: Long): String =
    SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(millis))

private fun nowHour(): Double = Engine.hourOf(System.currentTimeMillis())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(Tab.TODAY) }
    var refresh by remember { mutableIntStateOf(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    }) {
                        Icon(Icons.Filled.Settings, contentDescription = null)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavItem(tab, Tab.TODAY, Icons.Filled.Bedtime, R.string.tab_today) { tab = it }
                NavItem(tab, Tab.DRIFT, Icons.Filled.Timeline, R.string.tab_drift) { tab = it }
                NavItem(tab, Tab.MODEL, Icons.Filled.Science, R.string.tab_model) { tab = it }
                NavItem(tab, Tab.DATA, Icons.Filled.MonitorHeart, R.string.tab_data) { tab = it }
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            when (tab) {
                Tab.TODAY -> TodayTab(refresh) { refresh++ }
                Tab.DRIFT -> DriftTab(refresh)
                Tab.MODEL -> ModelTab(refresh) { refresh++ }
                Tab.DATA -> DataTab(refresh) { refresh++ }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    current: Tab,
    target: Tab,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelRes: Int,
    onSelect: (Tab) -> Unit,
) {
    NavigationBarItem(
        selected = current == target,
        onClick = { onSelect(target) },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(stringResource(labelRes)) },
    )
}

// ---- shared pieces -------------------------------------------------------

@Composable
private fun Loading() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// A band never prints a number for its own confidence. Below half certainty a
// single time would be a lie, so only the range is shown.
@Composable
private fun BandRow(color: Color, labelRes: Int, band: Band) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .let { it },
        ) {
            Surface(color = color, modifier = Modifier.fillMaxSize(), shape = CircleShape) {}
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Label(stringResource(labelRes))
            Text(
                text = if (band.confidence >= 0.5) hhmm(band.median)
                else stringResource(R.string.f_range, hhmm(band.low), hhmm(band.high)),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (band.confidence >= 0.5) {
                Text(
                    text = stringResource(R.string.f_range, hhmm(band.low), hhmm(band.high)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---- today ---------------------------------------------------------------

@Composable
private fun TodayTab(refresh: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var forecast by remember { mutableStateOf<Forecast?>(null) }
    var delay by remember { mutableStateOf<Delay.Info?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        failed = false
        forecast = null
        val f = runCatching { Engine.forecast(context) }.getOrNull()
        if (f == null) failed = true else forecast = f
        delay = runCatching { Delay.estimate(context) }.getOrNull()
    }

    if (failed) {
        SectionCard { Text(stringResource(R.string.f_error)) }
        return
    }
    val f = forecast ?: run {
        Loading()
        return
    }

    val gateColor = MaterialTheme.colorScheme.primary
    val onsetColor = MaterialTheme.colorScheme.secondary
    val wakeColor = MaterialTheme.colorScheme.tertiary

    Spacer(Modifier.height(8.dp))

    DayRing(
        nowHour = nowHour(),
        arcs = listOf(
            RingArc(f.gate.low, f.gate.median, f.gate.high, f.gate.confidence, gateColor, 0.dp),
            RingArc(f.onset.low, f.onset.median, f.onset.high, f.onset.confidence, onsetColor, 22.dp),
            RingArc(f.wake.low, f.wake.median, f.wake.high, f.wake.confidence, wakeColor, 44.dp),
        ),
        trackColor = MaterialTheme.colorScheme.outlineVariant,
        tickColor = MaterialTheme.colorScheme.outlineVariant,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        nowColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Label(stringResource(R.string.f_onset))
            Text(
                text = if (f.onset.confidence >= 0.5) hhmm(f.onset.median)
                else stringResource(R.string.f_range, hhmm(f.onset.low), hhmm(f.onset.high)),
                style = MaterialTheme.typography.displayLarge,
                textAlign = TextAlign.Center,
            )
        }
    }

    if (f.nights == 0) {
        SectionCard {
            Text(
                stringResource(R.string.f_cold),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    SectionCard {
        BandRow(gateColor, R.string.f_gate, f.gate)
        BandRow(onsetColor, R.string.f_onset, f.onset)
        BandRow(wakeColor, R.string.f_wake, f.wake)
    }

    // Physiology versus habit. The window is when the body is ready; the second
    // line is when this particular person actually puts the phone down.
    SectionCard {
        val d = delay
        val real = Delay.applied(d, f.gate.median)
        if (d != null && real != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Smartphone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.size(12.dp))
                Column {
                    Label(stringResource(R.string.f_habit))
                    Text(hhmm(real), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        stringResource(
                            R.string.f_habit_delay,
                            minutesOf(d.median),
                            d.nights,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Text(
                stringResource(R.string.f_habit_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    SectionCard {
        if (f.reverseAlarm != null) {
            BandRow(MaterialTheme.colorScheme.primary, R.string.f_reverse, f.reverseAlarm)
        } else {
            Text(
                stringResource(R.string.f_no_alarm),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { pickAlarm(context, scope, onChanged) }) {
            Icon(Icons.Filled.Alarm, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.btn_alarm))
        }
    }

    if (f.caffeineNow >= 1.0) {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.LocalCafe,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(R.string.f_caffeine, f.caffeineNow),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun pickAlarm(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onChanged: () -> Unit,
) {
    val cal = Calendar.getInstance()
    TimePickerDialog(
        context,
        { _, h, m ->
            scope.launch {
                Db.get(context).meta().put(
                    Meta(Engine.KEY_ALARM, String.format(Locale.US, "%02d:%02d", h, m))
                )
                onChanged()
            }
        },
        cal.get(Calendar.HOUR_OF_DAY),
        cal.get(Calendar.MINUTE),
        true,
    ).show()
}

// ---- drift ---------------------------------------------------------------

@Composable
private fun DriftTab(refresh: Int) {
    val context = LocalContext.current
    var forecast by remember { mutableStateOf<Forecast?>(null) }

    LaunchedEffect(refresh) {
        forecast = runCatching { Engine.forecast(context) }.getOrNull()
    }

    val f = forecast ?: run {
        Loading()
        return
    }

    val minutes = (f.driftPerDay * 60.0).roundToInt()
    val human = if (abs(minutes) >= 60) {
        stringResource(R.string.f_drift_hm, minutes / 60, abs(minutes % 60))
    } else {
        stringResource(R.string.f_drift_m, minutes)
    }

    Spacer(Modifier.height(8.dp))

    SectionCard {
        Label(stringResource(R.string.tab_drift))
        Text(human, style = MaterialTheme.typography.displayLarge)
        Text(
            stringResource(R.string.f_drift_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val points = (0..7).map { f.gate.median + f.driftPerDay * it }

    SectionCard {
        Label(stringResource(R.string.f_drift_chart))
        Spacer(Modifier.height(12.dp))
        DriftChart(
            hours = points,
            lineColor = MaterialTheme.colorScheme.primary,
            fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            gridColor = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
        )
    }

    SectionCard {
        Label(stringResource(R.string.f_week))
        for (day in 1..7) {
            val shifted = f.gate.median + f.driftPerDay * day
            InfoRow(
                stringResource(R.string.f_week_day, day),
                dayTime(Engine.millisOf(shifted)),
            )
        }
    }
}

// ---- model ---------------------------------------------------------------

@Composable
private fun ModelTab(refresh: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf<Filter?>(null) }
    var nights by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        filter = runCatching { Engine.load(context) }.getOrNull()
        nights = runCatching { Db.get(context).nights().count() }.getOrDefault(0)
    }

    val f = filter ?: run {
        Loading()
        return
    }

    fun median(pick: (dev.fasa.model.Particle) -> Double): Double =
        f.particles.map(pick).sorted()[f.particles.size / 2]

    Spacer(Modifier.height(8.dp))

    SectionCard {
        Label(stringResource(R.string.f_hist_tau))
        Spacer(Modifier.height(12.dp))
        Histogram(
            values = f.particles.map { it.tau },
            bins = 28,
            barColor = MaterialTheme.colorScheme.primary,
            baseColor = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.f_model_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionCard {
        InfoRow(stringResource(R.string.f_nights, nights), "")
        InfoRow(stringResource(R.string.tab_model), stringResource(R.string.f_tau, median { it.tau }))
        InfoRow("", stringResource(R.string.f_latency, median { it.latency }))
        InfoRow("", stringResource(R.string.f_rise, median { it.tauRise }))
        InfoRow("", stringResource(R.string.f_fall, median { it.tauFall }))
        InfoRow(
            "",
            stringResource(R.string.f_particles, f.particles.size, f.ess().roundToInt()),
        )
    }

}

// ---- data ----------------------------------------------------------------

@Composable
private fun DataTab(refresh: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var hcState by remember { mutableIntStateOf(R.string.hc_missing) }
    var core by remember { mutableStateOf(false) }
    var extra by remember { mutableStateOf(false) }
    var nights by remember { mutableIntStateOf(0) }
    var lastWake by remember { mutableStateOf<Long?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    val exportError = stringResource(R.string.export_error)
    val exportDone = stringResource(R.string.export_done)

    // The system file picker. No storage permission is involved: the user picks
    // the destination, the app only gets a one shot stream to it.
    val saveFile = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts
            .CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exporting = true
        scope.launch {
            val ok = runCatching {
                val json = Export.build(context)
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                } ?: error("no stream")
            }.isSuccess
            notice = if (ok) exportDone else exportError
            exporting = false
        }
    }

    val okText = stringResource(R.string.perms_ok)
    val partialText = stringResource(R.string.perms_partial)

    val ask = rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController
            .createRequestPermissionResultContract()
    ) { granted ->
        notice = if (granted.containsAll(HealthRepo.CORE)) okText else partialText
        onChanged()
    }

    LaunchedEffect(refresh) {
        loaded = false
        val db = Db.get(context)
        nights = runCatching { db.nights().count() }.getOrDefault(0)
        lastWake = runCatching { db.nights().lastSleepEnd() }.getOrNull()
        val granted = runCatching { HealthRepo.grantedSet(context) }.getOrDefault(emptySet())
        core = granted.containsAll(HealthRepo.CORE)
        extra = granted.containsAll(HealthRepo.EXTRA)
        hcState = when (HealthRepo.status(context)) {
            HealthRepo.Status.OK -> R.string.hc_ok
            HealthRepo.Status.NEEDS_UPDATE -> R.string.hc_update
            HealthRepo.Status.NOT_INSTALLED -> R.string.hc_missing
        }
        loaded = true
    }

    if (!loaded) {
        Loading()
        return
    }

    val yes = stringResource(R.string.state_granted)
    val no = stringResource(R.string.state_denied)

    Spacer(Modifier.height(8.dp))

    SectionCard {
        InfoRow(stringResource(R.string.hc_label), stringResource(hcState))
        InfoRow(stringResource(R.string.perm_core), if (core) yes else no)
        InfoRow(stringResource(R.string.perm_extra), if (extra) yes else no)
        InfoRow(stringResource(R.string.nights_count, nights), "")
        InfoRow(
            stringResource(R.string.last_wake, "").trim(),
            lastWake?.let { dayTime(it) } ?: stringResource(R.string.none),
        )
    }

    notice?.let {
        SectionCard { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }

    SectionCard {
        // Nothing to ask for means no button. A dead control that always
        // reports success is just noise on the screen.
        if (core && extra) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    stringResource(R.string.perms_all_ok),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { ask.launch(HealthRepo.ALL) },
            ) {
                Icon(Icons.Filled.Key, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.btn_permissions))
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                scope.launch {
                    notice = when (val r = HealthRepo.sync(context)) {
                        is HealthRepo.Result.Ok ->
                            context.getString(R.string.sync_result, r.sessions, r.added)
                        is HealthRepo.Result.Blocked -> context.getString(
                            when (r.reason) {
                                HealthRepo.Reason.NO_HEALTH_CONNECT -> R.string.blocked_no_hc
                                HealthRepo.Reason.NEEDS_UPDATE -> R.string.blocked_update
                                HealthRepo.Reason.NO_PERMISSION -> R.string.blocked_perms
                            }
                        )
                        is HealthRepo.Result.Failed ->
                            context.getString(R.string.error_prefix, r.error)
                    }
                    // New nights change the model, so refit before showing anything.
                    runCatching { Engine.refit(context) }
                    busy = false
                    onChanged()
                }
            },
        ) {
            Icon(Icons.Filled.Sync, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.btn_sync))
        }
    }

    // Everything the database knows, in one self describing English file, for a
    // doctor or a language model to read.
    SectionCard {
        Text(
            stringResource(R.string.export_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            enabled = !exporting,
            modifier = Modifier.fillMaxWidth(),
            onClick = { saveFile.launch(Export.fileName()) },
        ) {
            Icon(Icons.Filled.Download, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.btn_export))
        }
        if (exporting) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.export_running),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// Signed minutes, for "+1 h 40 m later than the body wanted".
private fun minutesOf(hours: Double): String {
    val total = Math.round(hours * 60.0).toInt()
    val sign = if (total < 0) "-" else "+"
    val abs = kotlin.math.abs(total)
    return if (abs >= 60) "$sign${abs / 60} h ${abs % 60} m" else "$sign$abs m"
}
