package dev.digitalgnosis.dispatch.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import dev.digitalgnosis.dispatch.config.TokenManager
import dev.digitalgnosis.dispatch.playback.DispatchPlaybackService
import dev.digitalgnosis.dispatch.tts.ModelManager
import dev.digitalgnosis.dispatch.tts.ModelState
import dev.digitalgnosis.dispatch.tts.TtsEngine
import dev.digitalgnosis.dispatch.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    tokenManager: TokenManager,
    modelManager: ModelManager,
    ttsEngine: TtsEngine,
    viewModel: SettingsViewModel = hiltViewModel(),
    onBack: (() -> Unit)? = null,
) {
    val tokenState by tokenManager.tokenFlow.collectAsState()
    val modelState by modelManager.state.collectAsState()
    val token = tokenState ?: "Awaiting token..."

    Scaffold(
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = { Text("Settings") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.primary,
                    ),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            UpdateCard(viewModel = viewModel)
            TokenCard(token = token)
            VoiceMapCard(viewModel = viewModel)
            VoiceModelCard(state = modelState)
            VoiceSettingsCard(
                ttsEngine = ttsEngine,
                isReady = modelState is ModelState.Ready,
            )
            StreamDiagnosticCard(viewModel = viewModel)
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun UpdateCard(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val status by viewModel.updateStatus.collectAsState()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "System Update",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = status ?: "Push build from orchestrator to update",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
                Button(
                    onClick = {
                        viewModel.downloadAndInstallUpdate { file ->
                            installApk(context, file)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                ) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Update")
                }
            }
        }
    }
}

private fun installApk(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "dev.digitalgnosis.dispatch.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Timber.e(e, "Settings: install FAILED")
        Toast.makeText(context, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun TokenCard(token: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "FCM Device Token",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    text = token,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VoiceMapCard(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val voiceMapResult by viewModel.voiceMap.collectAsState()
    val loading by viewModel.isLoading.collectAsState()
    var editingDept by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val voiceMap = voiceMapResult.voiceMap
    val availableVoices = voiceMapResult.availableVoices

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Voice Assignments",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tap a department to change its voice",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (loading && voiceMap.isEmpty()) {
                Text(
                    text = "Loading voice map...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (voiceMap.isEmpty()) {
                Text(
                    text = "Could not load voice map from File Bridge",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                val sortedEntries = voiceMap.entries.sortedBy { it.key }
                sortedEntries.forEach { (dept, voice) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editingDept = dept }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = dept,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = voice,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        try {
                                            context.startForegroundService(
                                                DispatchPlaybackService.createIntent(
                                                    context = context,
                                                    text = "$dept voice test.",
                                                    voice = voice,
                                                    sender = "system",
                                                    message = "$dept voice test.",
                                                )
                                            )
                                        } catch (_: Exception) { }
                                    }
                                },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Preview $voice",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                    if (dept != sortedEntries.last().key) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                        )
                    }
                }
            }
        }
    }

    // Voice picker dialog
    editingDept?.let { dept ->
        val currentVoice = voiceMap[dept] ?: "am_michael"
        VoicePickerDialog(
            department = dept,
            currentVoice = currentVoice,
            availableVoices = availableVoices,
            onVoiceSelected = { newVoice ->
                viewModel.updateVoiceAssignment(dept, newVoice)
                editingDept = null
            },
            onDismiss = { editingDept = null },
        )
    }
}

@Composable
private fun VoicePickerDialog(
    department: String,
    currentVoice: String,
    availableVoices: List<String>,
    onVoiceSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice for $department") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                items(availableVoices) { voice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onVoiceSelected(voice) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = (voice == currentVoice),
                                onClick = { onVoiceSelected(voice) },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = voice)
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        context.startForegroundService(
                                            DispatchPlaybackService.createIntent(
                                                context = context,
                                                text = "Testing $voice voice.",
                                                voice = voice,
                                                sender = "system",
                                                message = "Testing $voice voice.",
                                            )
                                        )
                                    } catch (_: Exception) { }
                                }
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Test $voice",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun VoiceModelCard(state: ModelState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Local TTS (Piper)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))

            when (state) {
                is ModelState.Ready -> {
                    Text(
                        text = "Model ready (libritts_r-medium)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                is ModelState.Extracting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Extracting ${state.label}...",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                is ModelState.NotReady -> {
                    Text(
                        text = "Waiting for initialization...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is ModelState.Error -> {
                    Text(
                        text = "Error: ${state.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceSettingsCard(ttsEngine: TtsEngine, isReady: Boolean) {
    var speed by remember { mutableFloatStateOf(ttsEngine.speed) }
    
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Playback Speed",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${"%.1f".format(speed)}x",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(48.dp),
                )
                Slider(
                    value = speed,
                    onValueChange = { 
                        speed = it
                        ttsEngine.speed = it
                    },
                    valueRange = 0.5f..2.0f,
                    steps = 15,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StreamDiagnosticCard(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val scope = rememberCoroutineScope()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Voice Pipeline Test",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = connectionStatus ?: "Test connection to Oasis GPU server",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.testConnection() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Test Connect")
                }
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                context.startForegroundService(
                                    DispatchPlaybackService.createIntent(
                                        context = context,
                                        text = "Network pipeline test successful.",
                                        voice = "am_michael",
                                        sender = "system",
                                        message = "Network pipeline test successful.",
                                    )
                                )
                            } catch (_: Exception) { }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Test Stream")
                }
            }
        }
    }
}
