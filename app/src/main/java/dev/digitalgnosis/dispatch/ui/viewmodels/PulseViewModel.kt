package dev.digitalgnosis.dispatch.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.digitalgnosis.dispatch.data.PulseChannel
import dev.digitalgnosis.dispatch.data.PulsePost
import dev.digitalgnosis.dispatch.data.PulseRepository
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
class PulseViewModel @Inject constructor(
    private val pulseRepository: PulseRepository
) : ViewModel() {

    private val _posts = MutableStateFlow<List<PulsePost>>(emptyList())
    val posts: StateFlow<List<PulsePost>> = _posts.asStateFlow()

    private val _totalPosts = MutableStateFlow(0)
    val totalPosts: StateFlow<Int> = _totalPosts.asStateFlow()

    private val _channels = MutableStateFlow<List<PulseChannel>>(emptyList())
    val channels: StateFlow<List<PulseChannel>> = _channels.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var currentChannel: String? = null

    init {
        refreshAll()
    }

    fun refreshAll() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val channelsJob = launch {
                    val result = withContext(Dispatchers.IO) {
                        pulseRepository.fetchPulseChannels()
                    }
                    _channels.value = result.channels.filter { it.postCount > 0 }
                }
                val postsJob = launch {
                    val result = withContext(Dispatchers.IO) {
                        if (currentChannel == null) {
                            pulseRepository.fetchPulseFeed(hours = 72, limit = 80)
                        } else {
                            pulseRepository.fetchPulseChannel(currentChannel!!, hours = 72, limit = 80)
                        }
                    }
                    _posts.value = result.posts
                    _totalPosts.value = result.total
                }
                channelsJob.join()
                postsJob.join()
            } catch (e: Exception) {
                Timber.e(e, "PulseViewModel: refresh failed")
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setChannel(channelName: String?) {
        currentChannel = channelName
        refreshPosts()
    }

    fun refreshPosts() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    if (currentChannel == null) {
                        pulseRepository.fetchPulseFeed(hours = 72, limit = 80)
                    } else {
                        pulseRepository.fetchPulseChannel(currentChannel!!, hours = 72, limit = 80)
                    }
                }
                _posts.value = result.posts
                _totalPosts.value = result.total
                Timber.d("PulseVM: refreshPosts — %d posts (channel=%s)", result.posts.size, currentChannel)
            } catch (e: Exception) {
                Timber.e(e, "PulseVM: refreshPosts failed (channel=%s)", currentChannel)
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }
}
