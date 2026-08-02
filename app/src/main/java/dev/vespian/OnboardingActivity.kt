package dev.vespian

import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationManagerCompat
import androidx.core.os.LocaleListCompat
import androidx.health.connect.client.PermissionController
import dev.vespian.health.HealthRepo
import dev.vespian.tg.Lang
import dev.vespian.ui.VespianTheme
import dev.vespian.work.Scheduler

/**
 * First run, four screens, one decision on each.
 *
 * The order is deliberate. Language first, because everything after it is read
 * rather than tapped. Then the mode, because the mode decides which permissions
 * are even asked for. Then the permissions themselves. Then a short look at
 * what Health Connect actually holds, so the first thing the app ever says is
 * whether the chain from the band works at all.
 *
 * Manual mode exists for a phone with no band. It costs accuracy and the screen
 * that offers it says so, but it must stay cheap to live with: two taps a day
 * and nothing else.
 */
class OnboardingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VespianTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Onboarding(
                        onSwitchLanguage = { tag ->
                            Prefs.setBotLang(this, tag)
                            AppCompatDelegate.setApplicationLocales(
                                LocaleListCompat.forLanguageTags(tag)
                            )
                        },
                        onDone = {
                            Prefs.setOnboarded(this, true)
                            Scheduler.start(applicationContext)
                            startActivity(Intent(this, MainActivity::class.java))
                            finish()
                        },
                    )
                }
            }
        }
    }
}

private const val STEP_WELCOME = 0
private const val STEP_MODE = 1
private const val STEP_PERMS = 2
private const val STEP_SOURCE = 3

@Composable
private fun Onboarding(
    onSwitchLanguage: (String) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(STEP_WELCOME) }
    var manual by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        when (step) {
            STEP_WELCOME -> Welcome(
                onSwitchLanguage = onSwitchLanguage,
                onNext = {
                    // An untouched toggle still counts as an answer, otherwise
                    // the bot would ask the same question all over again.
                    if (Prefs.botLang(context).isEmpty()) {
                        Prefs.setBotLang(context, Lang.DEFAULT)
                    }
                    step = STEP_MODE
                },
            )

            STEP_MODE -> ModeChoice(
                onSwitchLanguage = onSwitchLanguage,
                onPick = { pickedManual ->
                    manual = pickedManual
                    Prefs.setManualMode(context, pickedManual)
                    step = STEP_PERMS
                },
            )

            STEP_PERMS -> Permissions(
                manual = manual,
                onNext = { step = STEP_SOURCE },
            )

            else -> SourceCheck(manual = manual, onDone = onDone)
        }
    }
}

// ---------------------------------------------------------------- screen one

@Composable
private fun Welcome(
    onSwitchLanguage: (String) -> Unit,
    onNext: () -> Unit,
) {
    LanguageBar(onSwitchLanguage)
    Spacer(Modifier.height(8.dp))
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Duck(Modifier.size(180.dp))
    }
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.ob_welcome_title),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.ob_welcome_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(28.dp))
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ob_start))
    }
}

/**
 * The animated welcome duck.
 *
 * The platform has decoded animated images since API 28 and the app already
 * requires 28, so this needs no image library at all. If a device ever refuses
 * the file the view simply stays empty and the rest of the screen still works.
 */
@Composable
private fun Duck(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ImageView(ctx).apply {
                val drawable = runCatching {
                    ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(ctx.resources, R.raw.duck)
                    )
                }.getOrNull()
                setImageDrawable(drawable)
                if (drawable is AnimatedImageDrawable) {
                    drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    drawable.start()
                }
            }
        },
    )
}

/**
 * Two letters in the corner, and nothing else anywhere in the app.
 *
 * Changing the locale restarts this activity, which is why the control only
 * appears on the first two screens: at that point there is no granted
 * permission or half filled form to lose.
 */
@Composable
private fun LanguageBar(onSwitchLanguage: (String) -> Unit) {
    val context = LocalContext.current
    val current = Prefs.botLang(context).ifEmpty { Lang.DEFAULT }
    val other = if (current == "ru") "en" else "ru"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        OutlinedButton(onClick = { onSwitchLanguage(other) }) {
            Text(other.uppercase())
        }
    }
}

// ---------------------------------------------------------------- screen two

@Composable
private fun ModeChoice(
    onSwitchLanguage: (String) -> Unit,
    onPick: (Boolean) -> Unit,
) {
    LanguageBar(onSwitchLanguage)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.ob_mode_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    Spacer(Modifier.height(16.dp))

    ChoiceCard(
        title = stringResource(R.string.ob_mode_auto),
        body = stringResource(R.string.ob_mode_auto_body),
        action = stringResource(R.string.ob_mode_pick),
        onAction = { onPick(false) },
    )
    ChoiceCard(
        title = stringResource(R.string.ob_mode_manual),
        body = stringResource(R.string.ob_mode_manual_body),
        action = stringResource(R.string.ob_mode_pick),
        onAction = { onPick(true) },
    )

    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.ob_mode_switch_later),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ChoiceCard(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

// -------------------------------------------------------------- screen three

@Composable
private fun Permissions(manual: Boolean, onNext: () -> Unit) {
    val context = LocalContext.current

    var healthGranted by remember { mutableStateOf(false) }
    var hcStatus by remember { mutableStateOf(HealthRepo.status(context)) }
    var notifGranted by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    var batteryFree by remember { mutableStateOf(batteryExempt(context)) }

    val healthLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        healthGranted = granted.containsAll(HealthRepo.CORE)
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifGranted = granted
    }

    // Neither the battery dialog nor the store reports anything back, so the
    // state is simply re-read when the user comes back from them.
    val returnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryFree = batteryExempt(context)
        hcStatus = HealthRepo.status(context)
    }

    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.ob_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(
            if (manual) R.string.ob_subtitle_manual else R.string.ob_subtitle
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))

    if (!manual) {
        if (hcStatus == HealthRepo.Status.OK) {
            Step(
                icon = Icons.Filled.HealthAndSafety,
                title = stringResource(R.string.ob_health),
                body = stringResource(R.string.ob_health_body),
                done = healthGranted,
                action = stringResource(R.string.ob_grant),
                onAction = { healthLauncher.launch(HealthRepo.ALL) },
            )
        } else {
            Step(
                icon = Icons.Filled.HealthAndSafety,
                title = stringResource(R.string.ob_health),
                body = stringResource(
                    if (hcStatus == HealthRepo.Status.NEEDS_UPDATE) {
                        R.string.ob_hc_update
                    } else {
                        R.string.ob_hc_missing
                    }
                ),
                done = false,
                action = stringResource(R.string.ob_hc_install),
                onAction = { openHealthConnectInStore(context, returnLauncher::launch) },
            )
        }
    }

    Step(
        icon = Icons.Filled.Notifications,
        title = stringResource(R.string.ob_notif),
        body = stringResource(R.string.ob_notif_body),
        done = notifGranted,
        action = stringResource(R.string.ob_grant),
        onAction = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }
        },
    )

    // The chat runs inside the foreground service in both modes, so the battery
    // exemption is not a hands free luxury. Without it the bot answers late in
    // manual mode too.
    Step(
        icon = Icons.Filled.BatteryFull,
        title = stringResource(R.string.ob_battery),
        body = stringResource(R.string.ob_battery_body),
        done = batteryFree,
        action = stringResource(R.string.ob_allow),
        onAction = {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + context.packageName))
            runCatching { returnLauncher.launch(intent) }.onFailure {
                // Some skins hide the direct dialog. The list screen works.
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    )
                }
            }
        },
    )

    // realme and other ColorOS skins keep autostart in their own settings.
    // There is no intent for it, so this step can only point the way.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    stringResource(R.string.ob_autostart),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.ob_autostart_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:" + context.packageName))
                    )
                }
            }) {
                Text(stringResource(R.string.ob_open_settings))
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Button(onClick = onNext) {
            Text(stringResource(R.string.ob_next))
        }
    }
    Spacer(Modifier.height(24.dp))
}

private fun openHealthConnectInStore(context: Context, launch: (Intent) -> Unit) {
    val id = "com.google.android.apps.healthdata"
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + id))
    val web = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/apps/details?id=" + id)
    )
    runCatching { launch(market) }.onFailure {
        runCatching { launch(web) }.onFailure {
            runCatching { context.startActivity(web) }
        }
    }
}

// --------------------------------------------------------------- screen four

/**
 * The last screen names whatever wrote the data, so the very first thing the
 * app reports is whether the chain band to Mi Fitness to Health Connect to here
 * actually carries anything. A fresh install often has nothing yet, and that
 * has to read as normal rather than as a failure.
 */
@Composable
private fun SourceCheck(manual: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    var checking by remember { mutableStateOf(!manual) }
    var source by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(manual) {
        if (manual) return@LaunchedEffect
        runCatching { HealthRepo.sync(context) }
        source = runCatching { HealthRepo.source(context) }.getOrNull()
        checking = false
    }

    Spacer(Modifier.height(24.dp))
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Duck(Modifier.size(140.dp))
    }
    Spacer(Modifier.height(16.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.ob_source_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            val line = when {
                manual -> stringResource(R.string.ob_source_manual)
                checking -> stringResource(R.string.ob_source_checking)
                source != null -> stringResource(R.string.ob_source_found, source.orEmpty())
                else -> stringResource(R.string.ob_source_empty)
            }
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(24.dp))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ob_done))
    }
    Spacer(Modifier.height(24.dp))
}

// --------------------------------------------------------------------- parts

private fun batteryExempt(context: Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java) ?: return false
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

@Composable
private fun Step(
    icon: ImageVector,
    title: String,
    body: String,
    done: Boolean,
    action: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Icon(
                    if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!done) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}
