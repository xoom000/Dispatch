package dev.digitalgnosis.dispatch.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Displays a banner when an app update is available.
 * Drop into any screen's Column to show the update UI.
 */
@Composable
fun UpdateBanner(viewModel: UpdateStateManager) {
    val updateState by viewModel.updateState.collectAsState(
        initial = viewModel.updateState.value
    )
    val context = LocalContext.current

    val prefs = remember {
        context.getSharedPreferences("update_prefs", android.content.Context.MODE_PRIVATE)
    }

    val dismissedVersion = remember {
        mutableStateOf(prefs.getString("dismissed_version", "") ?: "")
    }

    val isDismissed = when (val state = updateState) {
        is UpdateState.Available -> dismissedVersion.value == state.updateInfo.versionName
        else -> false
    }

    AnimatedVisibility(
        visible = updateState is UpdateState.Available && !isDismissed,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        when (val state = updateState) {
            is UpdateState.Available -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Update",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Update Available!",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            IconButton(
                                onClick = {
                                    prefs.edit()
                                        .putString("dismissed_version", state.updateInfo.versionName)
                                        .apply()
                                    dismissedVersion.value = state.updateInfo.versionName
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss"
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Version ${state.updateInfo.versionName}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        if (state.updateInfo.releaseNotes.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = state.updateInfo.releaseNotes.take(150) +
                                        if (state.updateInfo.releaseNotes.length > 150) "..." else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            if (viewModel.isDownloading.collectAsState(
                                    initial = viewModel.isDownloading.value
                                ).value
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Downloading...",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                Button(
                                    onClick = { viewModel.downloadAndInstall() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Download & Install")
                                }
                            }
                        }
                    }
                }
            }
            else -> {}
        }
    }
}

/**
 * State manager for app updates. Checks GitHub on init.
 */
class UpdateStateManager(
    private val context: android.content.Context,
) : ViewModel() {
    private val updateChecker = UpdateChecker(context)

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading

    init {
        checkForUpdate()
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            try {
                _updateState.value = UpdateState.Checking
                val updateInfo = updateChecker.checkForUpdate()

                _updateState.value = if (updateInfo != null) {
                    Timber.d("UpdateStateManager: update available — %s", updateInfo.versionName)
                    UpdateState.Available(updateInfo)
                } else {
                    UpdateState.UpToDate
                }
            } catch (e: Exception) {
                Timber.e(e, "UpdateStateManager: check failed")
                _updateState.value = UpdateState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun downloadAndInstall() {
        val currentState = _updateState.value
        if (currentState !is UpdateState.Available) return

        viewModelScope.launch {
            try {
                _isDownloading.value = true
                updateChecker.downloadAndInstall(currentState.updateInfo)
                _isDownloading.value = false
            } catch (e: Exception) {
                Timber.e(e, "UpdateStateManager: download failed")
                _isDownloading.value = false
                _updateState.value = UpdateState.Error(e.message ?: "Download failed")
            }
        }
    }
}

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Available(val updateInfo: UpdateInfo) : UpdateState()
    data class Error(val message: String) : UpdateState()
}
