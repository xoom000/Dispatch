package dev.digitalgnosis.dispatch.tts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed class ModelState {
    data object NotReady : ModelState()
    data class Extracting(val label: String) : ModelState()
    data object Ready : ModelState()
    data class Error(val message: String) : ModelState()
}

@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<ModelState>(ModelState.NotReady)
    val state: StateFlow<ModelState> = _state

    private val modelDir = File(context.filesDir, "piper")
    private val espeakDir = File(modelDir, "espeak-ng-data")

    val modelPath: String get() = File(modelDir, "en_US-libritts_r-medium.onnx").absolutePath
    val tokensPath: String get() = File(modelDir, "tokens.txt").absolutePath
    val dataDir: String get() = espeakDir.absolutePath

    fun isReady(): Boolean = _state.value is ModelState.Ready

    fun initialize() {
        scope.launch {
            try {
                val startTime = System.currentTimeMillis()

                // Check if already extracted
                val modelFile = File(modelDir, "en_US-libritts_r-medium.onnx")
                if (modelFile.exists() && espeakDir.exists()) {
                    Timber.i("Piper model files present, ready (checked in %dms)",
                        System.currentTimeMillis() - startTime)
                    _state.value = ModelState.Ready
                    return@launch
                }

                // Extract all bundled assets (model + espeak-ng-data + tokens)
                _state.value = ModelState.Extracting("voice model")
                Timber.i("Extracting Piper model from assets...")
                extractAssets()

                val elapsed = System.currentTimeMillis() - startTime
                Timber.i("Piper model extraction complete in %dms", elapsed)
                _state.value = ModelState.Ready

            } catch (e: Exception) {
                Timber.e(e, "Model initialization failed")
                _state.value = ModelState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun extractAssets() {
        modelDir.mkdirs()

        val assetManager = context.assets
        val extractStart = System.currentTimeMillis()

        // Copy espeak-ng-data directory recursively
        copyAssetDir(assetManager, "piper/espeak-ng-data", espeakDir)
        Timber.d("espeak-ng-data extracted in %dms", System.currentTimeMillis() - extractStart)

        // Copy tokens.txt
        copyAssetFile(assetManager, "piper/tokens.txt", File(modelDir, "tokens.txt"))

        // Copy model.onnx (75MB — main extraction time)
        val modelStart = System.currentTimeMillis()
        copyAssetFile(assetManager, "piper/en_US-libritts_r-medium.onnx",
            File(modelDir, "en_US-libritts_r-medium.onnx"))
        Timber.d("model.onnx extracted in %dms", System.currentTimeMillis() - modelStart)

        Timber.d("Total asset extraction: %dms", System.currentTimeMillis() - extractStart)
    }

    private fun copyAssetDir(assetManager: android.content.res.AssetManager, assetPath: String, destDir: File) {
        destDir.mkdirs()
        val entries = assetManager.list(assetPath) ?: return
        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
            val childDest = File(destDir, entry)
            val subEntries = assetManager.list(childAssetPath)
            if (subEntries != null && subEntries.isNotEmpty()) {
                copyAssetDir(assetManager, childAssetPath, childDest)
            } else {
                copyAssetFile(assetManager, childAssetPath, childDest)
            }
        }
    }

    private fun copyAssetFile(assetManager: android.content.res.AssetManager, assetPath: String, destFile: File) {
        destFile.parentFile?.mkdirs()
        assetManager.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output, bufferSize = 65536)
            }
        }
    }
}
