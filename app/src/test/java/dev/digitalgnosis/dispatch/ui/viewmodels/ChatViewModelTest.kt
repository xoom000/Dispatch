package dev.digitalgnosis.dispatch.ui.viewmodels

import app.cash.turbine.test
import dev.digitalgnosis.dispatch.data.MessageRepository
import dev.digitalgnosis.dispatch.data.SyncManager
import dev.digitalgnosis.dispatch.data.ThreadDao
import dev.digitalgnosis.dispatch.data.ThreadInfo
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var threadDao: ThreadDao
    private lateinit var syncManager: SyncManager
    private lateinit var messageRepository: MessageRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        threadDao = mockk(relaxed = true)
        syncManager = mockk(relaxed = true)
        messageRepository = mockk(relaxed = true)

        // Default: empty thread list, no refresh events
        every { threadDao.getAllThreadsFlow() } returns flowOf(emptyList())
        every { messageRepository.threadRefreshEvents } returns MutableSharedFlow()
        coEvery { syncManager.syncThreads() } just Runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = ChatViewModel(threadDao, syncManager, messageRepository)

    @Test
    fun `initial state is empty thread list`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        assertTrue(vm.threads.value.isEmpty())
    }

    @Test
    fun `refresh triggers sync`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.refresh()
        advanceUntilIdle()

        // isRefreshing resets after sync completes
        assertFalse(vm.isRefreshing.value)
        // At least 2 calls: init + manual refresh
        coVerify(atLeast = 2) { syncManager.syncThreads() }
    }

    @Test
    fun `selectThread updates selectedThread state`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        assertNull(vm.selectedThread.value)

        val thread = ThreadInfo(
            threadId = "test-123",
            subject = "Test Thread",
            participants = listOf("engineering", "nigel"),
            messageCount = 3,
            createdAt = "2026-01-01",
            lastActivity = "2026-01-02"
        )
        vm.selectThread(thread)
        assertEquals("test-123", vm.selectedThread.value?.threadId)

        vm.selectThread(null)
        assertNull(vm.selectedThread.value)
    }

    @Test
    fun `sync failure does not crash — isRefreshing resets`() = runTest {
        coEvery { syncManager.syncThreads() } throws RuntimeException("Network error")

        val vm = createViewModel()
        advanceUntilIdle()

        // Init triggers refresh which throws — should be caught
        assertFalse(vm.isRefreshing.value)
    }

    @Test
    fun `thread refresh event triggers sync with debounce`() = runTest {
        val refreshFlow = MutableSharedFlow<String>()
        every { messageRepository.threadRefreshEvents } returns refreshFlow

        val vm = createViewModel()
        advanceUntilIdle()

        // Clear the init sync call count
        clearMocks(syncManager, answers = false)
        coEvery { syncManager.syncThreads() } just Runs

        // Emit a refresh event
        refreshFlow.emit("thread-abc")
        advanceTimeBy(600) // Past the 500ms debounce
        advanceUntilIdle()

        coVerify(atLeast = 1) { syncManager.syncThreads() }
    }
}
