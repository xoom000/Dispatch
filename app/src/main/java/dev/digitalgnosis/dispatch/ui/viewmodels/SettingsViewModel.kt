package dev.digitalgnosis.dispatch.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.digitalgnosis.dispatch.data.ConfigRepository
import dev.digitalgnosis.dispatch.data.VoiceMapResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

import dev.digitalgnosis.dispatch.network.FileTransferClient
import dev.digitalgnosis.dispatch.config.TailscaleConfig
import java.io.File

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
    private val fileTransferClient: FileTransferClient
) : ViewModel() {

    private val _voiceMap = MutableStateFlow(VoiceMapResult())
    val voiceMap: StateFlow<VoiceMapResult> = _voiceMap.asStateFlow()

    private val _connectionStatus = MutableStateFlow<String?>(null)
    val connectionStatus: StateFlow<String?> = _connectionStatus.asStateFlow()

    private val _updateStatus = MutableStateFlow<String?>(null)
    val updateStatus: StateFlow<String?> = _updateStatus.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        refreshVoiceMap()
    }

    fun refreshVoiceMap() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    configRepository.fetchVoiceMap()
                }
                _voiceMap.value = result
            } catch (e: Exception) {
                Timber.e(e, "SettingsViewModel: refreshVoiceMap failed")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateVoiceAssignment(department: String, voice: String) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                configRepository.updateVoiceAssignment(department, voice)
            }
            if (success) {
                refreshVoiceMap()
            }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                configRepository.testConnection()
            }
            _connectionStatus.value = result
        }
    }

    fun downloadAndInstallUpdate(onReadyToInstall: (File) -> Unit) {
        viewModelScope.launch {
            _updateStatus.value = "Downloading update..."
            val apkUrl = "${TailscaleConfig.FILE_BRIDGE_SERVER}/files/dispatch-latest.apk"
            Timber.i("SettingsVM: starting update download from %s", apkUrl)
            
            val file = withContext(Dispatchers.IO) {
                fileTransferClient.downloadToInternalFile(apkUrl, "dispatch-update.apk")
            }
            
            if (file != null && file.exists()) {
                Timber.i("SettingsVM: download successful, file size = %d", file.length())
                _updateStatus.value = "Update ready"
                onReadyToInstall(file)
            } else {
                Timber.w("SettingsVM: download failed (file is null or doesn't exist)")
                _updateStatus.value = "Download failed — build maybe didn't finish?"
            }
        }
    }
}
