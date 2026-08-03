package dev.vespian

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Tour
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import dev.vespian.db.Db
import dev.vespian.db.Meta
import dev.vespian.diag.SelfTest
import dev.vespian.model.Engine
import dev.vespian.tg.Bot
import dev.vespian.tg.Secrets
import dev.vespian.ui.VespianTheme
import dev.vespian.work.LightService
import dev.vespian.work.Watchdog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VespianTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SettingsScreen(onBack = { finish() })
                }
            }
        }
    }
}

private val OkGreen = Color(0xFF57C08B)
private val WarnAmber = Color(0xFFFFB74D)
private val FailCoral = Color(0xFFFF7B72)

private const val REPO_URL = "https://github.com/d1d2dopamine/vespian"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var language by remember {
        mutableStateOf(AppCompatDelegate.getApplicationLocales().toLanguageTags())
    }
    // Mirrors the /mode command in the chat: same flag, two places to set it.
    var manualMode by remember { mutableStateOf(Prefs.manualMode(context)) }
    var running by remember { mutableStateOf(false) }
    var refitting by remember { mutableStateOf(false) }
    var confirmRefit by remember { mutableStateOf(false) }
    var refitDone by remember { mutableStateOf(false) }
    var verdict by remember { mutableStateOf<SelfTest.Line?>(null) }
    // Loaded once from encrypted storage. The token is shown in full only while
    // it is being typed; nothing writes it to a log.
    var token by remember { mutableStateOf(Secrets.token(context)) }
    var chatId by remember { mutableStateOf(Secrets.chatId(context)) }
    var tgBusy by remember { mutableStateOf(false) }
    var tgResult by remember { mutableStateOf<String?>(null) }
    val lines: SnapshotStateList<SelfTest.Line> = remember { mutableStateListOf() }

    // Sleep block.
    var alarm by remember { mutableStateOf("") }
    var alarmNote by remember { mutableStateOf<Int?>(null) }
    var mg by remember { mutableStateOf(Prefs.mgPerMug(context).toString()) }
    var mgNote by remember { mutableStateOf<Int?>(null) }

    // Optional drinks. Everything here is off until it is switched on, and
    // switching one on adds exactly one tap counter, never a number to type in
    // the evening.
    var energyOn by remember { mutableStateOf(Prefs.energyOn(context)) }
    var alcoholOn by remember { mutableStateOf(Prefs.alcoholOn(context)) }
    var mgCan by remember { mutableStateOf(Prefs.mgPerCan(context).toString()) }
    var mgCanNote by remember { mutableStateOf<Int?>(null) }

    // About block. Nulls mean "not read yet".
    var svcSince by remember { mutableStateOf<Long?>(null) }
    var svcBeat by remember { mutableStateOf<Long?>(null) }
    var svcDown by remember { mutableStateOf<Long?>(null) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val db = Db.get(context)
        withContext(Dispatchers.IO) {
            alarm = db.meta().get(Engine.KEY_ALARM).orEmpty()
            svcSince = db.meta().get(LightService.K_SINCE)?.toLongOrNull()
            svcBeat = db.meta().get(LightService.K_BEAT)?.toLongOrNull()
            svcDown = db.meta().get(Watchdog.K_DOWN_AT)?.toLongOrNull()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.btn_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // ---- application ------------------------------------------------

            Group(stringResource(R.string.settings_group_app))

            Section(stringResource(R.string.settings_language)) {
                LanguageRow(R.string.lang_system, language.isEmpty()) {
                    language = ""
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                }
                LanguageRow(R.string.lang_en, language.startsWith("en")) {
                    language = "en"
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags("en")
                    )
                }
                LanguageRow(R.string.lang_ru, language.startsWith("ru")) {
                    language = "ru"
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags("ru")
                    )
                }
            }

            Section(stringResource(R.string.settings_mode)) {
                Text(
                    stringResource(R.string.settings_mode_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LanguageRow(R.string.tgb_mode_auto, !manualMode) {
                    manualMode = false
                    scope.launch { Store.saveMode(context, false) }
                }
                LanguageRow(R.string.tgb_mode_manual, manualMode) {
                    manualMode = true
                    scope.launch { Store.saveMode(context, true) }
                }
            }

            // The first run screens are skipped forever once the flag is set,
            // which is right for an update and wrong when someone wants to see
            // the permissions checklist again. Reopening them here touches no
            // data: clearing app storage would have wiped the whole database.
            Section(stringResource(R.string.settings_onboarding)) {
                Text(
                    stringResource(R.string.settings_onboarding_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = {
                    context.startActivity(Intent(context, OnboardingActivity::class.java))
                }) {
                    Icon(Icons.Filled.Tour, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.settings_onboarding_open))
                }
            }

            // ---- sleep --------------------------------------------------------

            Group(stringResource(R.string.settings_group_sleep))

            Section(stringResource(R.string.settings_alarm)) {
                Text(
                    stringResource(R.string.settings_alarm_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = alarm,
                    onValueChange = { alarm = it; alarmNote = null },
                    modifier = Modifier.width(160.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_alarm_field)) },
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        val text = alarm.trim()
                        if (!validHhmm(text)) {
                            alarmNote = R.string.settings_alarm_bad
                            return@Button
                        }
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                Db.get(context).meta().put(Meta(Engine.KEY_ALARM, text))
                            }
                            Engine.invalidate()
                            alarm = text
                            alarmNote = R.string.saved
                        }
                    }) { Text(stringResource(R.string.save)) }
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                Db.get(context).meta().put(Meta(Engine.KEY_ALARM, ""))
                            }
                            Engine.invalidate()
                            alarm = ""
                            alarmNote = R.string.saved
                        }
                    }) { Text(stringResource(R.string.settings_alarm_clear)) }
                }
                alarmNote?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Section(stringResource(R.string.settings_coffee)) {
                Text(
                    stringResource(R.string.settings_coffee_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = mg,
                    onValueChange = { mg = it.filter { c -> c.isDigit() }.take(3); mgNote = null },
                    modifier = Modifier.width(160.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.settings_coffee_field)) },
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        val value = mg.toIntOrNull()
                        if (value == null ||
                            value < Prefs.MG_PER_MUG_MIN ||
                            value > Prefs.MG_PER_MUG_MAX
                        ) {
                            mgNote = R.string.settings_coffee_bad
                            return@Button
                        }
                        Prefs.setMgPerMug(context, value)
                        Engine.invalidate()
                        mgNote = R.string.saved
                    }) { Text(stringResource(R.string.save)) }
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = { mg = "130"; mgNote = null }) {
                        Text(stringResource(R.string.settings_coffee_instant))
                    }
                    TextButton(onClick = { mg = "190"; mgNote = null }) {
                        Text(stringResource(R.string.settings_coffee_brewed))
                    }
                }
                mgNote?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Section(stringResource(R.string.settings_drinks)) {
                Text(
                    stringResource(R.string.settings_drinks_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.settings_drinks_energy),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = energyOn,
                        onCheckedChange = {
                            energyOn = it
                            Prefs.setEnergyOn(context, it)
                            Engine.invalidate()
                        },
                    )
                }
                if (energyOn) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = mgCan,
                        onValueChange = {
                            mgCan = it.filter { c -> c.isDigit() }.take(3)
                            mgCanNote = null
                        },
                        modifier = Modifier.width(160.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(R.string.settings_drinks_can_field)) },
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        val value = mgCan.toIntOrNull()
                        if (value == null ||
                            value < Prefs.MG_PER_CAN_MIN ||
                            value > Prefs.MG_PER_CAN_MAX
                        ) {
                            mgCanNote = R.string.settings_coffee_bad
                            return@Button
                        }
                        Prefs.setMgPerCan(context, value)
                        Engine.invalidate()
                        mgCanNote = R.string.saved
                    }) { Text(stringResource(R.string.save)) }
                    mgCanNote?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(it),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.settings_drinks_alcohol),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = alcoholOn,
                        onCheckedChange = {
                            alcoholOn = it
                            Prefs.setAlcoholOn(context, it)
                            Engine.invalidate()
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_drinks_alcohol_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- telegram -----------------------------------------------------

            Group(stringResource(R.string.settings_group_telegram))

            Section(stringResource(R.string.settings_telegram)) {
                Text(
                    stringResource(R.string.tg_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; tgResult = null },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    // Anyone holding the token owns the bot, so it is not left
                    // sitting in plain sight on a shared screen.
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.tg_token)) },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = chatId,
                    onValueChange = { chatId = it; tgResult = null },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.tg_chat)) },
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = !tgBusy,
                    onClick = {
                        tgBusy = true
                        tgResult = null
                        Secrets.save(context, token, chatId)
                        scope.launch {
                            tgResult = runCatching { Bot.test(context) }.getOrElse { it.message }
                            tgBusy = false
                        }
                    },
                ) {
                    Icon(Icons.Filled.Send, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.tg_save_test))
                }
                if (tgBusy) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.tg_testing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                tgResult?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // ---- data and diagnostics ------------------------------------------

            Group(stringResource(R.string.settings_group_data))

            Section(stringResource(R.string.settings_diagnostics)) {
                Text(
                    stringResource(R.string.selftest_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = !running,
                    onClick = {
                        running = true
                        lines.clear()
                        verdict = null
                        scope.launch {
                            val report = runCatching { SelfTest.run(context) }.getOrNull()
                            if (report != null) {
                                lines.addAll(report.lines)
                                verdict = report.verdict
                            }
                            running = false
                        }
                    },
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.btn_selftest))
                }

                if (running) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.selftest_running),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (lines.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    lines.forEach { ResultRow(it) }
                }

                verdict?.let {
                    Spacer(Modifier.height(12.dp))
                    ResultRow(it, bold = true)
                }
            }

            Section(stringResource(R.string.settings_maintenance)) {
                Text(
                    stringResource(R.string.refit_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    enabled = !refitting,
                    onClick = { confirmRefit = true },
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.btn_refit))
                }
                if (refitting) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.refit_running),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (refitDone) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.refit_done),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // ---- about ----------------------------------------------------------

            Group(stringResource(R.string.settings_group_about))

            Section(stringResource(R.string.settings_about)) {
                InfoRow(
                    stringResource(R.string.about_version),
                    BuildConfig.VERSION_NAME,
                )
                InfoRow(
                    stringResource(R.string.about_build),
                    BuildConfig.VERSION_CODE.toString(),
                )
                InfoRow(stringResource(R.string.about_commit), BuildConfig.GIT_SHA)
                InfoRow(stringResource(R.string.about_built_at), BuildConfig.BUILD_AT)
                InfoRow(stringResource(R.string.about_package), BuildConfig.APPLICATION_ID)
                InfoRow(stringResource(R.string.about_license), "MIT")
                InfoRow(stringResource(R.string.about_repo), REPO_URL)

                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.about_service),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))

                val now = System.currentTimeMillis()
                val beat = svcBeat
                val since = svcSince
                val alive = beat != null && now - beat < LightService.BEAT_STALE_MS
                val uptime = if (alive && since != null) minutesBetween(since, now) else 0L
                Text(
                    text = when {
                        beat == null -> stringResource(R.string.about_svc_never)
                        alive -> stringResource(R.string.about_svc_ok, spanText(uptime))
                        else -> stringResource(
                            R.string.about_svc_stale,
                            spanText(minutesBetween(beat, now)),
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (alive) OkGreen else WarnAmber,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = svcDown?.let {
                        stringResource(R.string.about_svc_down, stamp(it))
                    } ?: stringResource(R.string.about_svc_down_never),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = {
                    copyDetails(context, svcSince, svcBeat, svcDown)
                    copied = true
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.about_copy))
                }
                if (copied) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.about_copied),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmRefit) {
        AlertDialog(
            onDismissRequest = { confirmRefit = false },
            icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
            title = { Text(stringResource(R.string.refit_confirm_title)) },
            text = { Text(stringResource(R.string.refit_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRefit = false
                    refitting = true
                    refitDone = false
                    scope.launch {
                        runCatching { Engine.refit(context) }
                        refitting = false
                        refitDone = true
                    }
                }) {
                    Text(stringResource(R.string.refit_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRefit = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

// ---- helpers -------------------------------------------------------------

private fun validHhmm(text: String): Boolean {
    val parts = text.split(":")
    if (parts.size != 2) return false
    val h = parts[0].toIntOrNull() ?: return false
    val m = parts[1].toIntOrNull() ?: return false
    return h in 0..23 && m in 0..59
}

private fun minutesBetween(from: Long, to: Long): Long =
    ((to - from).coerceAtLeast(0L)) / 60_000L

// "3 h 40 min" without pulling in a formatting library. Minutes only under an
// hour, because "0 h 12 min" reads like a bug.
private fun spanText(minutes: Long): String {
    if (minutes < 60) return minutes.toString() + " min"
    return (minutes / 60).toString() + " h " + (minutes % 60).toString() + " min"
}

private val STAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())

private fun stamp(at: Long): String = STAMP.format(Instant.ofEpochMilli(at))

// One tap gives a support ready dump. No token, no chat id, nothing private.
private fun copyDetails(
    context: Context,
    since: Long?,
    beat: Long?,
    down: Long?,
) {
    val text = buildString {
        append("vespian ").append(BuildConfig.VERSION_NAME)
        append(" (build ").append(BuildConfig.VERSION_CODE).append(")\n")
        append("commit ").append(BuildConfig.GIT_SHA).append("\n")
        append("built ").append(BuildConfig.BUILD_AT).append("\n")
        append("package ").append(BuildConfig.APPLICATION_ID).append("\n")
        append("device ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
        append(", Android ").append(Build.VERSION.RELEASE)
        append(" (sdk ").append(Build.VERSION.SDK_INT).append(")\n")
        append("service since ").append(since?.let { stamp(it) } ?: "-").append("\n")
        append("last beat ").append(beat?.let { stamp(it) } ?: "-").append("\n")
        append("last downtime ").append(down?.let { stamp(it) } ?: "-")
    }
    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clip?.setPrimaryClip(ClipData.newPlainText("vespian", text))
}

// ---- pieces --------------------------------------------------------------

// A group is a plain label above a run of cards. It gives the screen a shape
// without adding another level of navigation.
@Composable
private fun Group(title: String) {
    Spacer(Modifier.height(14.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun LanguageRow(labelRes: Int, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.size(8.dp))
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
    }
}

// Status is carried by a real vector icon and its colour. No emoji anywhere.
@Composable
private fun ResultRow(line: SelfTest.Line, bold: Boolean = false) {
    val tint = when (line.level) {
        SelfTest.Level.OK -> OkGreen
        SelfTest.Level.WARN -> WarnAmber
        SelfTest.Level.FAIL -> FailCoral
    }
    val icon = when (line.level) {
        SelfTest.Level.OK -> Icons.Filled.CheckCircle
        SelfTest.Level.WARN -> Icons.Filled.Warning
        SelfTest.Level.FAIL -> Icons.Filled.Error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(10.dp))
        Text(
            text = line.text,
            style = if (bold) MaterialTheme.typography.titleMedium
            else MaterialTheme.typography.bodyMedium,
            color = if (bold) tint else MaterialTheme.colorScheme.onSurface,
        )
    }
}
