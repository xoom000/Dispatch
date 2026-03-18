package dev.digitalgnosis.dispatch.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.digitalgnosis.dispatch.data.Whiteboard
import dev.digitalgnosis.dispatch.data.WhiteboardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class BoardViewModel @Inject constructor(
    private val whiteboardRepository: WhiteboardRepository
) : ViewModel() {

    private val _board = MutableStateFlow<Whiteboard?>(null)
    val board: StateFlow<Whiteboard?> = _board.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        refreshBoard()
    }

    fun refreshBoard() {
        viewModelScope.launch {
            _isLoading.value = _board.value == null
            _error.value = null
            try {
                val result = withContext(Dispatchers.IO) {
                    whiteboardRepository.fetchWhiteboard()
                }
                _board.value = result
            } catch (e: Exception) {
                Timber.e(e, "BoardViewModel: refresh failed")
                _error.value = "Couldn't load board — check connection"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
