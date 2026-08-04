package dev.vespian

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.vespian.db.Answer
import dev.vespian.db.Db
import dev.vespian.db.Forced
import dev.vespian.db.Sip
import dev.vespian.db.Meta
import dev.vespian.health.HealthRepo
import dev.vespian.export.Export
import dev.vespian.model.Band
import dev.vespian.model.Calib
import dev.vespian.model.Delay
import dev.vespian.model.Drinks
import dev.vespian.model.Engine
import dev.vespian.model.Filter
import dev.vespian.model.Forecast
import dev.vespian.model.Physics
import dev.vespian.model.PredLog
import dev.vespian.tg.Commands
import dev.vespian.ui.DayRing
import dev.vespian.ui.DriftChart
import dev.vespian.ui.Histogram
import dev.vespian.ui.HrChart
import dev.vespian.ui.LightDay
import dev.vespian.ui.NightsChart
import dev.vespian.ui.VespianTheme
import dev.vespian.ui.RingArc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            VespianTheme {
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

private enum class Tab { TODAY, DRIFT, PULSE, MODEL, DATA, SETTINGS }

/**
 * What every tab had on screen the last time it was open.
 *
 * Compose throws away the state of a tab the moment another one is selected,
 * so without this every switch went back to an empty screen and a loading
 * circle while the model was recomputed. The cache is deliberately plain: it
 * is a snapshot for drawing, never a source of truth. Every tab still reloads
 * in the background and overwrites what is here.
 */
private object UiCache {
    var forecast: Forecast? = null
    var delay: Delay.Info? = null
    var debt: Int? = null
    var latency: Double? = null
    var obs: IntArray? = null
    var filter: Filter? = null
    var nights: Int = 0
    var history: List<PredLog.Row>? = null
    var light: List<Double>? = null
    var lightSamples: Int = 0
    var lightPeak: Float = 0f
    var lightBright: Int = 0
    var pulseDays: Int = 1
    var pulse: Pulse? = null
}

/** Nights drawn in the history chart. Two weeks, as asked. */
private const val HISTORY_NIGHTS = 14

/** One day of light, one bar per hour. */
private const val LIGHT_HOURS = 24

private const val HOUR_MS = 60 * 60 * 1000L

/** Bright enough to matter for the clock. Daylight starts around here. */
private const val BRIGHT_LUX = 1000f

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
            // No settings button here any more: settings are a tab of their
            // own now, and two ways into the same screen is one too many.
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
        bottomBar = {
            NavigationBar {
                NavItem(tab, Tab.TODAY, Icons.Filled.Bedtime, R.string.tab_today) { tab = it }
                NavItem(tab, Tab.DRIFT, Icons.Filled.Timeline, R.string.tab_drift) { tab = it }
                NavItem(tab, Tab.PULSE, Icons.Filled.Favorite, R.string.tab_pulse) { tab = it }
                NavItem(tab, Tab.MODEL, Icons.Filled.Science, R.string.tab_model) { tab = it }
                NavItem(tab, Tab.DATA, Icons.Filled.MonitorHeart, R.string.tab_data) { tab = it }
                NavItem(tab, Tab.SETTINGS, Icons.Filled.Tune, R.string.tab_settings) { tab = it }
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
                Tab.PULSE -> PulseTab(refresh)
                Tab.MODEL -> ModelTab(refresh) { refresh++ }
                Tab.DATA -> DataTab(refresh) { refresh++ }
                Tab.SETTINGS -> SettingsScreen(onBack = null)
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
        // One line, always. A label that wraps makes its own cell taller and
        // pushes every other cell down, which is what made the bar look
        // crooked once a fifth tab appeared.
        label = {
            Text(
                text = stringResource(labelRes),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
        },
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
//
// The certainty passed in is the calibrated one, measured against how often the
// published window actually contained the night. A band that merely agrees with
// itself does not earn the right to print a single time.
@Composable
private fun BandRow(color: Color, labelRes: Int, band: Band, confidence: Double) {
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
                text = if (confidence >= 0.5) hhmm(band.median)
                else stringResource(R.string.f_range, hhmm(band.low), hhmm(band.high)),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (confidence >= 0.5) {
                Text(
                    text = stringResource(R.string.f_range, hhmm(band.low), hhmm(band.high)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The same explanation the bot gives for /why, on the screen where the numbers
 * actually live. Collapsed by default: it is a reason, not a headline.
 *
 * Every line here is built from the model that produced the bands above. No
 * line is printed unless the quantity behind it exists.
 */
@Composable
private fun WhyCard(f: Forecast, latency: Double?, obs: IntArray?) {
    var open by remember { mutableStateOf(false) }

    SectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Label(stringResource(R.string.f_why))
            }
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!open) return@SectionCard

        Spacer(Modifier.height(12.dp))
        val body = MaterialTheme.typography.bodyMedium
        val dim = MaterialTheme.colorScheme.onSurfaceVariant

        Text(
            stringResource(R.string.tgb_why_head, hhmm(f.gate.median), hhmm(f.onset.median)),
            style = body,
        )
        Spacer(Modifier.height(10.dp))
        latency?.let {
            Text(stringResource(R.string.tgb_why_latency, it.roundToInt()), style = body, color = dim)
        }
        Text(stringResource(R.string.tgb_why_wake, hhmm(f.wake.median)), style = body, color = dim)
        Text(
            stringResource(R.string.tgb_why_drift, (f.driftPerDay * 60.0).roundToInt()),
            style = body,
            color = dim,
        )
        val mg = f.caffeineNow.roundToInt()
        Text(
            if (mg > 0) stringResource(R.string.tgb_why_caffeine, mg)
            else stringResource(R.string.tgb_why_caffeine_none),
            style = body,
            color = dim,
        )

        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(
                R.string.tgb_why_spread,
                hhmm(f.onset.low),
                hhmm(f.onset.high),
                (f.onset.width * 60.0).roundToInt(),
            ),
            style = body,
        )
        // Measured certainty, not the band's opinion of itself. How much to
        // trust the window is exactly the question the record answers.
        val certainty = f.calib?.confidence(f.onset) ?: f.onset.confidence
        val trust = when {
            certainty >= 0.66 -> R.string.tgb_why_trust_high
            certainty >= 0.33 -> R.string.tgb_why_trust_mid
            else -> R.string.tgb_why_trust_low
        }
        Text(stringResource(trust, f.nights), style = body, color = dim)

        // Where the evidence came from. A night spent on the phone past an
        // open gate only bounds the answer from above, and saying so out loud
        // is the difference between a model and a fortune teller.
        val o = obs
        if (o != null && o.size >= 3 && (o[0] + o[1] + o[2]) > 0) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.tgb_why_basis, o[0], o[1]), style = body, color = dim)
            if (o[2] > 0) {
                Text(stringResource(R.string.tgb_why_anchor, o[2]), style = body, color = dim)
            }
        }
    }
}

// ---- today ---------------------------------------------------------------

@Composable
private fun TodayTab(refresh: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Seeded from the last visit. Switching tabs must not throw the screen
    // away and rebuild it from an empty state.
    var forecast by remember { mutableStateOf(UiCache.forecast) }
    var delay by remember { mutableStateOf(UiCache.delay) }
    var debt by remember { mutableStateOf(UiCache.debt) }
    var latency by remember { mutableStateOf(UiCache.latency) }
    var obs by remember { mutableStateOf(UiCache.obs) }
    var failed by remember { mutableStateOf(false) }
    // Whether today is still a blank day, which is the only case where this
    // screen has anything to ask for.
    var unanswered by remember { mutableStateOf(false) }
    // Everything below the first card is reference material. Folded away by
    // default: a screen that opens with eleven cards is a screen that gets
    // read once and then scrolled past forever.
    var details by remember { mutableStateOf(false) }
    // Minutes of daytime sleep today. Shown because a person who napped and
    // then sees a later window deserves to know which of the two is the reason.
    var napMinutes by remember { mutableIntStateOf(0) }
    // Whole days since the band last reported a night. Missing days no longer
    // break the model, but the person still deserves to be told that tonight is
    // carried forward rather than measured.
    var staleDays by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        failed = false
        val f = runCatching { Engine.forecast(context) }.getOrNull()
        if (f == null) failed = true else { forecast = f; UiCache.forecast = f }
        latency = runCatching {
            val values = Engine.load(context).particles.map { it.latency }.sorted()
            values[values.size / 2]
        }.getOrNull()
        UiCache.latency = latency
        obs = runCatching { Engine.obsStats(context) }.getOrNull()
        UiCache.obs = obs
        delay = runCatching { Delay.estimate(context) }.getOrNull()
        UiCache.delay = delay
        // Pressure that had not reached the floor when the night ended. Shown
        // here so the number does not live only inside a chat command.
        debt = runCatching {
            Math.round(Engine.load(context).debtBandMinutes().median).toInt()
        }.getOrNull()
        UiCache.debt = debt
        staleDays = runCatching {
            withContext(Dispatchers.IO) {
                val last = Db.get(context).nights().lastSleepEnd() ?: 0L
                if (last <= 0L) 0
                else ((System.currentTimeMillis() - last) / 86_400_000L).toInt()
            }
        }.getOrDefault(0)
        napMinutes = runCatching {
            withContext(Dispatchers.IO) {
                Db.get(context).naps()
                    .between(Drinks.dayStart(), System.currentTimeMillis() + 1)
                    .sumOf { it.minutes }
            }
        }.getOrDefault(0)
        unanswered = runCatching {
            !Drinks.anyToday(context) && !Drinks.settled(context)
        }.getOrDefault(false)
    }

    if (failed && forecast == null) {
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

    // Every certainty on this screen is the measured one where a measurement
    // exists. Falling back to the band's own opinion only happens on a forecast
    // that has not been through the record yet.
    val score = f.calib
    fun certainty(band: Band): Double = score?.confidence(band) ?: band.confidence

    Spacer(Modifier.height(8.dp))

    DayRing(
        nowHour = nowHour(),
        arcs = listOf(
            RingArc(f.gate.low, f.gate.median, f.gate.high, certainty(f.gate), gateColor, 0.dp),
            RingArc(f.onset.low, f.onset.median, f.onset.high, certainty(f.onset), onsetColor, 22.dp),
            RingArc(f.wake.low, f.wake.median, f.wake.high, certainty(f.wake), wakeColor, 44.dp),
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
                text = if (certainty(f.onset) >= 0.5) hhmm(f.onset.median)
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

    // One thing, now.
    //
    // The rest of this screen answers questions. This card answers the only
    // question that gets asked at eleven at night, which is "so what do I do".
    // It says one thing, it says why in one line, and it is allowed to say that
    // there is nothing to do, because most hours of most days there is not and
    // an app that always demands something is an app that gets uninstalled.
    val nowH = nowHour()
    val toGate = f.gate.median - nowH
    val inWindow = nowH >= f.onset.low - 0.25 && nowH <= f.wake.low
    SectionCard {
        val action: Int
        val why: String
        when {
            // Awake inside the window. Nothing else on the screen matters.
            inWindow -> {
                action = R.string.now_sleep
                why = stringResource(R.string.now_sleep_why, hhmm(f.onset.high))
            }
            // Close enough that light now still moves tonight.
            toGate in 0.0..2.0 -> {
                action = R.string.now_dim
                why = stringResource(R.string.now_dim_why, hhmm(f.gate.median))
            }
            // Hours to go, and today has nothing on it. This is the moment the
            // one question is cheap to answer, so this is when it is asked for.
            unanswered -> {
                action = R.string.now_answer
                why = stringResource(R.string.now_answer_why)
            }
            else -> {
                action = R.string.now_wait
                why = stringResource(R.string.now_wait_why, hhmm(f.gate.median))
            }
        }
        Text(
            stringResource(action),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            why,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    TextButton(onClick = { details = !details }) {
        Text(stringResource(if (details) R.string.now_less else R.string.now_more))
    }

    if (details) {
    SectionCard {
        BandRow(gateColor, R.string.f_gate, f.gate, certainty(f.gate))
        BandRow(onsetColor, R.string.f_onset, f.onset, certainty(f.onset))
        BandRow(wakeColor, R.string.f_wake, f.wake, certainty(f.wake))
    }

    TrackRecordCard(f)

    WhyCard(f, latency, obs)

    debt?.let { minutes ->
        if (f.nights > 0) {
            SectionCard {
                Label(stringResource(R.string.f_debt_title))
                Text(
                    text = if (minutes < 20) stringResource(R.string.f_debt_none)
                    else stringResource(R.string.f_debt, minutes),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
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

    LogCard(refresh, onChanged)

    SectionCard {
        val reverse = f.reverseAlarm
        if (reverse != null) {
            BandRow(
                MaterialTheme.colorScheme.primary,
                R.string.f_reverse,
                reverse,
                certainty(reverse),
            )
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

    if (staleDays >= 2) {
        SectionCard {
            Text(
                stringResource(R.string.f_stale, staleDays),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (napMinutes > 0) {
        SectionCard {
            Text(
                stringResource(R.string.f_nap, napMinutes),
                style = MaterialTheme.typography.bodyMedium,
            )
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
}

// Coffee and wellbeing entered straight in the app. The bot asks the same two
// questions, but the model must not depend on having a network connection.
@Composable
private fun LogCard(refresh: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mugs by remember { mutableIntStateOf(0) }
    var cans by remember { mutableIntStateOf(0) }
    var doses by remember { mutableIntStateOf(0) }
    var mood by remember { mutableStateOf<Int?>(null) }
    // The drink logged by the last tap, so "actually, an hour ago" has
    // something to point at.
    var lastSip by remember { mutableStateOf<Sip?>(null) }
    // Switched off by default. A row that is never used is a row that makes
    // the whole card feel like a chore.
    val energyOn = remember { Prefs.energyOn(context) }
    val alcoholOn = remember { Prefs.alcoholOn(context) }
    var forced by remember { mutableStateOf(false) }
    var forcedKey by remember { mutableStateOf("") }
    // The two fallbacks for a day nobody logged: one question in the evening,
    // and the habit. At most one of them is ever on screen, because two ways of
    // asking the same thing is not twice the answers, it is half the trust.
    var askLate by remember { mutableStateOf(false) }
    var habit by remember { mutableStateOf<Drinks.Habit?>(null) }

    LaunchedEffect(refresh) {
        val a = withContext(Dispatchers.IO) {
            Db.get(context).answers().byDate(Commands.dayKey())
        }
        mugs = a?.mugs ?: 0
        cans = a?.cans ?: 0
        doses = a?.alcohol ?: 0
        mood = a?.mood
        val key = Forced.currentKey(context)
        forcedKey = key
        forced = Forced.has(context, key)
        val guessed = runCatching { Drinks.habit(context) }.getOrNull()
        habit = if (guessed != null && Drinks.shouldOfferHabit(context, guessed)) guessed else null
        askLate = habit == null && Drinks.shouldAskLate(context)
    }

    SectionCard {
        Label(stringResource(R.string.log_title))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                val next = mugs + 1
                mugs = next
                scope.launch {
                    saveAnswer(context, next, null)
                    lastSip = logSip(context, Sip.KIND_COFFEE)
                    onChanged()
                }
            }) {
                Icon(Icons.Filled.LocalCafe, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.log_coffee))
            }
            Spacer(Modifier.size(12.dp))
            Text(
                stringResource(R.string.log_coffee_n, mugs),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (mugs > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    val next = mugs - 1
                    mugs = next
                    scope.launch {
                        saveAnswer(context, next, null)
                        dropLastSip(context, Sip.KIND_COFFEE)
                        lastSip = null
                        onChanged()
                    }
                }) {
                    Text(stringResource(R.string.log_undo))
                }
                TextButton(onClick = {
                    mugs = 0
                    lastSip = null
                    scope.launch {
                        clearDrink(context, coffee = true)
                        clearSipsToday(context, Sip.KIND_COFFEE)
                        onChanged()
                    }
                }) {
                    Text(stringResource(R.string.log_reset))
                }
            }
        }

        // Energy drinks and alcohol, if they were switched on. Same shape as
        // coffee: one button adds one, and nothing ever asks for a volume or a
        // dose in milligrams. What a can is worth is a number set once.
        if (energyOn) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    val next = cans + 1
                    cans = next
                    scope.launch {
                        saveAnswer(context, null, null, cans = next)
                        lastSip = logSip(context, Sip.KIND_CAN)
                        onChanged()
                    }
                }) {
                    Text(stringResource(R.string.log_energy))
                }
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(R.string.log_energy_n, cans),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (cans > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val next = cans - 1
                        cans = next
                        scope.launch {
                            saveAnswer(context, null, null, cans = next)
                            dropLastSip(context, Sip.KIND_CAN)
                            lastSip = null
                            onChanged()
                        }
                    }) {
                        Text(stringResource(R.string.log_undo))
                    }
                    TextButton(onClick = {
                        cans = 0
                        lastSip = null
                        scope.launch {
                            clearDrink(context, cans = true)
                            clearSipsToday(context, Sip.KIND_CAN)
                            onChanged()
                        }
                    }) {
                        Text(stringResource(R.string.log_reset))
                    }
                }
            }
        }

        if (alcoholOn) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    val next = doses + 1
                    doses = next
                    scope.launch {
                        saveAnswer(context, null, null, alcohol = next)
                        lastSip = logSip(context, Sip.KIND_ALCOHOL)
                        onChanged()
                    }
                }) {
                    Text(stringResource(R.string.log_alcohol))
                }
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(R.string.log_alcohol_n, doses),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.log_alcohol_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (doses > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val next = doses - 1
                        doses = next
                        scope.launch {
                            saveAnswer(context, null, null, alcohol = next)
                            dropLastSip(context, Sip.KIND_ALCOHOL)
                            lastSip = null
                            onChanged()
                        }
                    }) {
                        Text(stringResource(R.string.log_undo))
                    }
                    TextButton(onClick = {
                        doses = 0
                        lastSip = null
                        scope.launch {
                            clearDrink(context, alcohol = true)
                            clearSipsToday(context, Sip.KIND_ALCOHOL)
                            onChanged()
                        }
                    }) {
                        Text(stringResource(R.string.log_reset))
                    }
                }
            }
        }
        // When it actually happened.
        //
        // The tap is the time, and this row is the entire correction interface:
        // four buttons, one tap, no clock face and no typing. Anything that asks
        // for a time properly will simply stop being used, and a drink that goes
        // unlogged is worse for the model than one logged an hour off.
        //
        // Only appears after a drink was just logged, and never nags: ignoring
        // it means "just now", which is true most of the time.
        val pending = lastSip
        if (pending != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.log_when),
                style = MaterialTheme.typography.bodyMedium,
            )
            val agoMinutes = ((pending.loggedAt - pending.at) / 60_000L).toInt()
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Label, how long ago, and how vague that answer is. "Two hours
                // or more" is deliberately the widest: it is the honest shape of
                // a memory that has already faded.
                val options = listOf(
                    Triple(R.string.log_when_now, 0, 0),
                    Triple(R.string.log_when_30, 30, 15),
                    Triple(R.string.log_when_60, 60, 20),
                    Triple(R.string.log_when_120, 150, 60),
                )
                for ((labelRes, minutesAgo, slack) in options) {
                    val chosen = agoMinutes == minutesAgo
                    TextButton(onClick = {
                        scope.launch {
                            lastSip = moveSip(context, pending, minutesAgo, slack)
                            onChanged()
                        }
                    }) {
                        Text(
                            stringResource(labelRes),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (chosen) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = if (pending.slackMinutes <= 0) {
                    stringResource(R.string.log_when_at, hhmmOf(pending.at))
                } else {
                    stringResource(
                        R.string.log_when_about,
                        hhmmOf(pending.at),
                        pending.slackMinutes,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Nothing logged today and the usual time has gone past: offer the
        // habit instead of asking. A guess presented as a guess, with its own
        // spread as its slack, and one tap to reject it.
        val guess = habit
        if (guess != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.log_habit, hhmmOf(guess.atToday())),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    val next = mugs + 1
                    mugs = next
                    habit = null
                    scope.launch {
                        saveAnswer(context, next, null)
                        Drinks.logHabit(context, guess)
                        onChanged()
                    }
                }) {
                    Text(stringResource(R.string.log_habit_yes, hhmmOf(guess.atToday())))
                }
                TextButton(onClick = {
                    habit = null
                    scope.launch {
                        Drinks.markSettled(context)
                        onChanged()
                    }
                }) {
                    Text(stringResource(R.string.log_habit_no))
                }
            }
            Text(
                stringResource(R.string.log_habit_hint, guess.days),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // No habit to lean on yet, so the evening asks the one question whose
        // answer changes tonight: was there anything after four. Once a day,
        // and only on a day that is otherwise empty.
        if (askLate) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.log_late),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    val next = mugs + 1
                    mugs = next
                    askLate = false
                    scope.launch {
                        saveAnswer(context, next, null)
                        Drinks.logLate(context)
                        onChanged()
                    }
                }) {
                    Text(stringResource(R.string.log_late_yes))
                }
                TextButton(onClick = {
                    askLate = false
                    scope.launch {
                        Drinks.markSettled(context)
                        onChanged()
                    }
                }) {
                    Text(stringResource(R.string.log_late_no))
                }
            }
            Text(
                stringResource(R.string.log_late_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.log_mood),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (value in 1..5) {
                if (mood == value) {
                    Button(
                        onClick = {},
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(value.toString())
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            mood = value
                            scope.launch {
                                saveAnswer(context, null, value)
                                onChanged()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(value.toString())
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val picked = mood
        Text(
            text = if (picked == null) stringResource(R.string.log_mood_none)
            else stringResource(R.string.log_mood_set, stringResource(moodRes(picked))),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        // Applied on the first tap, undone on the second. No confirmation: this
        // is pressed by someone who has just been woken up, and a dialog every
        // morning costs more than the rare mis-tap it would prevent.
        if (forced) {
            Button(onClick = {
                forced = false
                scope.launch {
                    Forced.set(context, forcedKey, false)
                    Engine.invalidate()
                    onChanged()
                }
            }) {
                Icon(Icons.Filled.Alarm, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.log_forced))
            }
            TextButton(onClick = {
                forced = false
                scope.launch {
                    Forced.set(context, forcedKey, false)
                    Engine.invalidate()
                    onChanged()
                }
            }) {
                Text(stringResource(R.string.log_undo))
            }
        } else {
            OutlinedButton(onClick = {
                forced = true
                scope.launch {
                    Forced.set(context, forcedKey, true)
                    Engine.invalidate()
                    onChanged()
                }
            }) {
                Icon(Icons.Filled.Alarm, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.log_forced))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (forced) stringResource(R.string.log_forced_on)
            else stringResource(R.string.log_forced_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.log_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun moodRes(value: Int): Int = when (value) {
    1 -> R.string.tg_mood_1
    2 -> R.string.tg_mood_2
    3 -> R.string.tg_mood_3
    4 -> R.string.tg_mood_4
    else -> R.string.tg_mood_5
}

// ---- drinks with times -----------------------------------------------------

// Clock time of a moment, for showing back what was just recorded.
private fun hhmmOf(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

// Where the logging day starts: four in the morning, the same boundary the bot
// and the model use. A cup at one in the morning belongs to the evening it was
// drunk in, not to the date the clock had just rolled over to.
private fun logDayStart(): Long {
    val cal = Calendar.getInstance()
    if (cal.get(Calendar.HOUR_OF_DAY) < 4) cal.add(Calendar.DAY_OF_YEAR, -1)
    cal.set(Calendar.HOUR_OF_DAY, 4)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

// One drink, timed by the tap that recorded it. No slack: pressing the button
// while drinking is the one case where the app does know the minute.
private suspend fun logSip(context: Context, kind: Int): Sip? =
    withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val sip = Sip(at = now, loggedAt = now, kind = kind, slackMinutes = 0)
        val id = runCatching { Db.get(context).sips().put(sip) }.getOrNull()
            ?: return@withContext null
        Engine.invalidate()
        sip.copy(id = id)
    }

// Move a just logged drink back in time, and record how vague that is.
//
// The correction is always measured from when the button was pressed, never
// from the current time, so tapping "an hour ago" twice cannot walk a drink
// steadily into the past.
private suspend fun moveSip(context: Context, sip: Sip, minutesAgo: Int, slack: Int): Sip =
    withContext(Dispatchers.IO) {
        val moved = sip.copy(
            at = sip.loggedAt - minutesAgo * 60_000L,
            slackMinutes = slack,
        )
        runCatching { Db.get(context).sips().update(moved) }
        Engine.invalidate()
        Commands.refitSoon(context, force = true)
        moved
    }

// Undo pairs with the count: the drink the last tap added is the drink removed.
private suspend fun dropLastSip(context: Context, kind: Int) {
    withContext(Dispatchers.IO) {
        val dao = Db.get(context).sips()
        runCatching { dao.lastLogged(kind)?.let { dao.delete(it) } }
        Engine.invalidate()
    }
}

private suspend fun clearSipsToday(context: Context, kind: Int) {
    withContext(Dispatchers.IO) {
        runCatching { Db.get(context).sips().clearSince(logDayStart(), kind) }
        Engine.invalidate()
        Commands.refitSoon(context, force = true)
    }
}

// A null argument means "leave that field as it is", so a mug never wipes the
// morning answer and the morning answer never wipes the mugs.
private suspend fun saveAnswer(
    context: Context,
    mugs: Int?,
    mood: Int?,
    cans: Int? = null,
    alcohol: Int? = null,
) {
    withContext(Dispatchers.IO) {
        val db = Db.get(context)
        val date = Commands.dayKey()
        val existing = db.answers().byDate(date)
        db.answers().put(
            Answer(
                dateKey = date,
                mood = mood ?: existing?.mood,
                mugs = mugs ?: existing?.mugs,
                at = System.currentTimeMillis(),
                cans = cans ?: existing?.cans,
                alcohol = alcohol ?: existing?.alcohol,
            )
        )
        Commands.refitSoon(context)
    }
}

private suspend fun clearDrink(
    context: Context,
    coffee: Boolean = false,
    cans: Boolean = false,
    alcohol: Boolean = false,
) {
    withContext(Dispatchers.IO) {
        val db = Db.get(context)
        val date = Commands.dayKey()
        val existing = db.answers().byDate(date) ?: return@withContext
        db.answers().put(
            existing.copy(
                mugs = if (coffee) null else existing.mugs,
                cans = if (cans) null else existing.cans,
                alcohol = if (alcohol) null else existing.alcohol,
                at = System.currentTimeMillis(),
            )
        )
        Engine.invalidate()
        Commands.refitSoon(context, force = true)
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
    var forecast by remember { mutableStateOf(UiCache.forecast) }
    var history by remember { mutableStateOf(UiCache.history) }

    LaunchedEffect(refresh) {
        runCatching { Engine.forecast(context) }.getOrNull()?.let {
            forecast = it
            UiCache.forecast = it
        }
        history = runCatching { PredLog.history(context, HISTORY_NIGHTS) }.getOrNull()
        UiCache.history = history
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

    HistoryCard(history)

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

/**
 * Two weeks of nights, each next to the band that was promised for it.
 *
 * A forecast that is never written down cannot be wrong, so the app started
 * saving its evening band only from the version that shipped this card.
 * Nights recorded before that have no band and are drawn plain. They are not
 * counted in the score, because scoring a promise nobody made would be a lie.
 */
@Composable
private fun HistoryCard(rows: List<PredLog.Row>?) {
    SectionCard {
        Label(stringResource(R.string.f_hist))
        Spacer(Modifier.height(12.dp))

        if (rows.isNullOrEmpty()) {
            Text(
                stringResource(R.string.f_hist_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        NightsChart(
            actual = rows.map { it.actual },
            predLow = rows.map { it.pred?.low },
            predHigh = rows.map { it.pred?.high },
            hitColor = MaterialTheme.colorScheme.primary,
            missColor = MaterialTheme.colorScheme.error,
            plainColor = MaterialTheme.colorScheme.onSurfaceVariant,
            bandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
            gridColor = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp),
        )

        Spacer(Modifier.height(12.dp))

        val graded = rows.count { it.hit != null }
        if (graded == 0) {
            Text(
                stringResource(R.string.f_hist_wait, rows.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val hits = rows.count { it.hit == true }
            InfoRow(
                stringResource(R.string.f_hist_score),
                stringResource(R.string.f_hist_score_v, hits, graded),
            )
            PredLog.typicalMiss(rows)?.let {
                InfoRow(stringResource(R.string.f_hist_miss), stringResource(R.string.f_hist_miss_v, it))
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.f_hist_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * How well the window has actually been working.
 *
 * The point of this card is that the percentage above it is now checkable. It
 * states how many nights were graded, how many landed inside the published
 * window, how far off the middle typically was, and whether the app has widened
 * or narrowed its windows in response. While there is too little to judge, it
 * says so instead of showing a confident number.
 */
@Composable
private fun TrackRecordCard(f: Forecast) {
    val s = f.calib ?: return

    SectionCard {
        Label(stringResource(R.string.f_cal_title))

        val percent = (s.confidence(f.onset) * 100.0).roundToInt()
        InfoRow(
            stringResource(R.string.f_cal_conf),
            stringResource(R.string.f_cal_conf_v, percent),
        )

        if (s.graded == 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.f_cal_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        InfoRow(
            stringResource(R.string.f_hist_score),
            stringResource(R.string.f_hist_score_v, s.hits, s.graded),
        )
        s.typicalMiss?.let {
            InfoRow(stringResource(R.string.f_hist_miss), stringResource(R.string.f_hist_miss_v, it))
        }

        Spacer(Modifier.height(8.dp))

        val note = when {
            !s.measured -> stringResource(R.string.f_cal_prelim, s.graded, Calib.MIN_GRADED)
            s.direction > 0 -> stringResource(R.string.f_cal_wide)
            s.direction < 0 -> stringResource(R.string.f_cal_tight)
            else -> stringResource(R.string.f_cal_ok)
        }
        Text(
            note,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.f_cal_hint, (Calib.TARGET * 100).roundToInt()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- pulse ---------------------------------------------------------------

/**
 * Points drawn per period.
 *
 * The number stays the same for every range on purpose. Ninety days of raw
 * readings is tens of thousands of points on a screen a few hundred pixels
 * wide, which draws slowly and reads as noise. Forty eight slices means a day
 * is shown in half hours and three months in roughly two day steps.
 */
private const val PULSE_SLOTS = 48

private const val PULSE_DAY_MS = 24 * HOUR_MS

/** One period of heart rate, already reduced to what the chart can draw. */
private class Pulse(
    val avg: List<Double?>,
    val low: List<Double?>,
    val high: List<Double?>,
    val min: Int,
    val mean: Int,
    val max: Int,
    val count: Int,
)

/**
 * Reads the period and folds it into slices.
 *
 * A slice with no readings stays null rather than becoming a zero, so a day the
 * band was not worn leaves a hole in the line instead of a dive to the floor.
 */
private suspend fun readPulse(context: Context, days: Int): Pulse =
    withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val from = now - days * PULSE_DAY_MS
        val rows = runCatching { Db.get(context).hr().between(from, now) }
            .getOrDefault(emptyList())

        val sum = DoubleArray(PULSE_SLOTS)
        val seen = IntArray(PULSE_SLOTS)
        val lo = DoubleArray(PULSE_SLOTS)
        val hi = DoubleArray(PULSE_SLOTS)
        val width = ((now - from) / PULSE_SLOTS).coerceAtLeast(1L)

        var total = 0.0
        var lowest = Double.MAX_VALUE
        var highest = -Double.MAX_VALUE

        rows.forEach { row ->
            val bpm = row.bpm.toDouble()
            val i = ((row.at - from) / width).toInt().coerceIn(0, PULSE_SLOTS - 1)
            if (seen[i] == 0) {
                lo[i] = bpm
                hi[i] = bpm
            } else {
                if (bpm < lo[i]) lo[i] = bpm
                if (bpm > hi[i]) hi[i] = bpm
            }
            sum[i] += bpm
            seen[i]++
            total += bpm
            if (bpm < lowest) lowest = bpm
            if (bpm > highest) highest = bpm
        }

        val slots = 0 until PULSE_SLOTS
        Pulse(
            avg = slots.map { if (seen[it] == 0) null else sum[it] / seen[it] },
            low = slots.map { if (seen[it] == 0) null else lo[it] },
            high = slots.map { if (seen[it] == 0) null else hi[it] },
            min = if (rows.isEmpty()) 0 else lowest.roundToInt(),
            mean = if (rows.isEmpty()) 0 else (total / rows.size).roundToInt(),
            max = if (rows.isEmpty()) 0 else highest.roundToInt(),
            count = rows.size,
        )
    }

@Composable
private fun PulseTab(refresh: Int) {
    val context = LocalContext.current
    var days by remember { mutableIntStateOf(UiCache.pulseDays) }
    var data by remember { mutableStateOf(UiCache.pulse) }

    // Reloads on a range change as well as on a refresh: the chosen range is
    // the whole question this tab answers.
    LaunchedEffect(refresh, days) {
        val fresh = readPulse(context, days)
        data = fresh
        UiCache.pulse = fresh
        UiCache.pulseDays = days
    }

    Spacer(Modifier.height(8.dp))

    SectionCard {
        Label(stringResource(R.string.p_title))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PulseRange(days, 1, R.string.p_1d) { days = it }
            PulseRange(days, 7, R.string.p_7d) { days = it }
            PulseRange(days, 30, R.string.p_30d) { days = it }
            PulseRange(days, 90, R.string.p_90d) { days = it }
        }
    }

    val p = data ?: run {
        Loading()
        return
    }

    if (p.count == 0) {
        SectionCard {
            Text(
                stringResource(R.string.p_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    SectionCard {
        HrChart(
            avg = p.avg,
            low = p.low,
            high = p.high,
            lineColor = MaterialTheme.colorScheme.primary,
            bandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
            gridColor = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp),
        )

        Spacer(Modifier.height(12.dp))

        InfoRow(stringResource(R.string.p_low), stringResource(R.string.p_bpm, p.min))
        InfoRow(stringResource(R.string.p_avg), stringResource(R.string.p_bpm, p.mean))
        InfoRow(stringResource(R.string.p_high), stringResource(R.string.p_bpm, p.max))
        InfoRow(stringResource(R.string.p_count), stringResource(R.string.p_count_value, p.count))

        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.p_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PulseRange(current: Int, target: Int, labelRes: Int, onSelect: (Int) -> Unit) {
    val pad = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    if (current == target) {
        Button(onClick = { onSelect(target) }, contentPadding = pad) {
            Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge)
        }
    } else {
        OutlinedButton(onClick = { onSelect(target) }, contentPadding = pad) {
            Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ---- model ---------------------------------------------------------------

@Composable
private fun ModelTab(refresh: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(UiCache.filter) }
    var nights by remember { mutableIntStateOf(UiCache.nights) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        runCatching { Engine.load(context) }.getOrNull()?.let {
            filter = it
            UiCache.filter = it
        }
        nights = runCatching { Db.get(context).nights().count() }.getOrDefault(nights)
        UiCache.nights = nights
    }

    val f = filter ?: run {
        Loading()
        return
    }

    fun median(pick: (dev.vespian.model.Particle) -> Double): Double =
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
    var lastNight by remember { mutableStateOf<dev.vespian.db.Night?>(null) }
    var light by remember { mutableStateOf(UiCache.light) }
    var lightSamples by remember { mutableIntStateOf(UiCache.lightSamples) }
    var lightPeak by remember { mutableStateOf(UiCache.lightPeak) }
    var lightBright by remember { mutableIntStateOf(UiCache.lightBright) }
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

    // Re-read while the tab is open. Background sync can land at any moment and
    // a screen that quietly shows yesterday is worse than no screen at all.
    LaunchedEffect(refresh) {
        var first = true
        while (true) {
            if (first) loaded = false
            val db = Db.get(context)
            nights = runCatching { db.nights().count() }.getOrDefault(0)
            lastWake = runCatching { db.nights().lastSleepEnd() }.getOrNull()
            lastNight = runCatching {
                db.nights().lastEnded(System.currentTimeMillis())
            }.getOrNull()
            val granted = runCatching { HealthRepo.grantedSet(context) }
                .getOrDefault(emptySet())
            core = granted.containsAll(HealthRepo.CORE)
            extra = granted.containsAll(HealthRepo.EXTRA)
            hcState = when (HealthRepo.status(context)) {
                HealthRepo.Status.OK -> R.string.hc_ok
                HealthRepo.Status.NEEDS_UPDATE -> R.string.hc_update
                HealthRepo.Status.NOT_INSTALLED -> R.string.hc_missing
            }
            // Light of the last twenty four hours, folded into hourly bars.
            // Bars are drawn by circadian dose, not raw lux: the model reads
            // light that way, and a linear axis would hide every indoor hour
            // next to one minute outdoors.
            runCatching {
                val now = System.currentTimeMillis()
                val from = now - LIGHT_HOURS * HOUR_MS
                val samples = withContext(Dispatchers.IO) {
                    db.light().between(from, now)
                }
                val sums = DoubleArray(LIGHT_HOURS)
                val counts = IntArray(LIGHT_HOURS)
                var peak = 0f
                var bright = 0
                samples.forEach { s ->
                    val idx = (((s.at - from) / HOUR_MS).toInt()).coerceIn(0, LIGHT_HOURS - 1)
                    sums[idx] = sums[idx] + Physics.dose(s.lux.toDouble())
                    counts[idx] = counts[idx] + 1
                    if (s.lux > peak) peak = s.lux
                    if (s.lux >= BRIGHT_LUX) bright++
                }
                light = (0 until LIGHT_HOURS).map { i ->
                    if (counts[i] == 0) 0.0 else sums[i] / counts[i]
                }
                lightSamples = samples.size
                lightPeak = peak
                lightBright = bright
                UiCache.light = light
                UiCache.lightSamples = lightSamples
                UiCache.lightPeak = lightPeak
                UiCache.lightBright = lightBright
            }

            loaded = true
            first = false
            kotlinx.coroutines.delay(60_000L)
        }
    }

    if (!loaded && light == null) {
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

    // The raw numbers behind every forecast. Without them a wrong prediction
    // looks like a broken model when the real cause is a night the band never
    // recorded.
    SectionCard {
        Label(stringResource(R.string.last_night))
        Spacer(Modifier.height(8.dp))
        val n = lastNight
        if (n == null) {
            Text(
                stringResource(R.string.ln_none),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            InfoRow(
                stringResource(R.string.ln_window),
                stringResource(R.string.ln_range, dayTime(n.sleepStart), dayTime(n.sleepEnd)),
            )
            InfoRow(stringResource(R.string.ln_total), durText(context, n.minutesAsleep))
            // A hand entered night has no stage breakdown. Printing zero
            // minutes there would read as a broken sensor.
            val staged = n.minutesDeep > 0 || n.minutesRem > 0
            val notMeasured = stringResource(R.string.ln_no_phases)
            InfoRow(
                stringResource(R.string.ln_deep),
                if (staged) partText(context, n.minutesDeep, n.minutesAsleep)
                else notMeasured,
            )
            InfoRow(
                stringResource(R.string.ln_rem),
                if (staged) partText(context, n.minutesRem, n.minutesAsleep)
                else notMeasured,
            )
            InfoRow(stringResource(R.string.ln_awake), durText(context, n.minutesAwake))
            val hrMin = n.hrMin
            val hrMinAt = n.hrMinAt
            InfoRow(
                stringResource(R.string.ln_hr_min),
                when {
                    hrMin == null -> stringResource(R.string.none)
                    hrMinAt == null -> stringResource(R.string.ln_bpm, hrMin)
                    else -> stringResource(R.string.ln_bpm_at, hrMin, dayTime(hrMinAt))
                },
            )
            val hrMean = n.hrMean
            InfoRow(
                stringResource(R.string.ln_hr_mean),
                if (hrMean == null) stringResource(R.string.none)
                else stringResource(R.string.ln_bpm, hrMean),
            )
            InfoRow(stringResource(R.string.ln_source), n.source)
            InfoRow(stringResource(R.string.ln_imported), dayTime(n.importedAt))
            if (staged) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.ln_pct_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // The light the phone actually saw. This is a measurement, not a model
    // output: an empty stretch means the sensor was covered or the service was
    // down, and that is exactly what the flat bars say.
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.LightMode,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(10.dp))
            Label(stringResource(R.string.d_light))
        }
        Spacer(Modifier.height(12.dp))

        val bars = light
        if (bars == null || lightSamples == 0) {
            Text(
                stringResource(R.string.d_light_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LightDay(
                doses = bars,
                barColor = MaterialTheme.colorScheme.secondary,
                dimColor = MaterialTheme.colorScheme.outlineVariant,
                baseColor = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
            )
            Spacer(Modifier.height(12.dp))
            InfoRow(
                stringResource(R.string.d_light_peak),
                stringResource(R.string.d_light_lux, lightPeak.roundToInt()),
            )
            InfoRow(
                stringResource(R.string.d_light_bright),
                stringResource(R.string.d_light_samples, lightBright),
            )
            InfoRow(
                stringResource(R.string.d_light_count),
                stringResource(R.string.d_light_samples, lightSamples),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.d_light_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

private fun durText(context: Context, minutes: Int): String =
    context.getString(R.string.ln_dur, minutes / 60, minutes % 60)

// Phase minutes mean little on their own; the share of the night is what a
// person can compare between nights.
private fun partText(context: Context, minutes: Int, total: Int): String {
    val pct = if (total > 0) Math.round(minutes * 100.0 / total).toInt() else 0
    return context.getString(R.string.ln_part, durText(context, minutes), pct)
}

// Signed minutes, for "+1 h 40 m later than the body wanted".
private fun minutesOf(hours: Double): String {
    val total = Math.round(hours * 60.0).toInt()
    val sign = if (total < 0) "-" else "+"
    val abs = kotlin.math.abs(total)
    return if (abs >= 60) "$sign${abs / 60} h ${abs % 60} m" else "$sign$abs m"
}
