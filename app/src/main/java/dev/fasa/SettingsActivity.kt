package dev.fasa

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import dev.fasa.diag.SelfTest
import dev.fasa.model.Engine
import dev.fasa.tg.Bot
import dev.fasa.tg.Secrets
import dev.fasa.ui.FasaTheme
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FasaTheme {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var language by remember {
        mutableStateOf(AppCompatDelegate.getApplicationLocales().toLanguageTags())
    }
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
