package dev.digitalgnosis.dispatch.ui.viewmodels

import dev.digitalgnosis.dispatch.data.SessionInfo
import dev.digitalgnosis.dispatch.data.SessionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [ChatViewModel].
 *
 * Note: ChatViewModel.refresh() uses withContext(Dispatchers.IO) for network calls.
 * Tests that assert on IO-driven state (sessionInfoList, isRefreshing after fetch)
 * are inherently non-deterministic without injecting a test dispatcher into the ViewModel.
 * The tests here focus on synchronous state and mock interactions only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest : BaseViewModelTest() {

    private fun mockRepo() = mockk<SessionRepository>(relaxed = true)

    private fun makeListResult(vararg sessions: SessionInfo) =
        SessionRepository.SessionListResult(sessions = sessions.toList(), total = sessions.size)

    @Test
    fun `selectedSessionId is null initially`() = runTest {
        val repo = mockRepo()
        coEvery { repo.fetchForDispatch(any(), any()) } returns makeListResult()

        val vm = ChatViewModel(repo)
        assertNull(vm.selectedSessionId.value)
    }

    @Test
    fun `selectSession updates selectedSessionId`() = runTest {
        val repo = mockRepo()
        coEvery { repo.fetchForDispatch(any(), any()) } returns makeListResult()

        val vm = ChatViewModel(repo)
        vm.selectSession("session-abc")

        assertEquals("session-abc", vm.selectedSessionId.value)
    }

    @Test
    fun `selectSession with null clears selectedSessionId`() = runTest {
        val repo = mockRepo()
        coEvery { repo.fetchForDispatch(any(), any()) } returns makeListResult()

        val vm = ChatViewModel(repo)
        vm.selectSession("session-abc")
        vm.selectSession(null)

        assertNull(vm.selectedSessionId.value)
    }

    @Test
    fun `refresh triggers repository fetch`() = runTest {
        val repo = mockRepo()
        coEvery { repo.fetchForDispatch(any(), any()) } returns makeListResult()

        val vm = ChatViewModel(repo)
        advanceUntilIdle()

        vm.refresh()
        advanceUntilIdle()

        // init + explicit refresh = at least 2 calls
        coVerify(atLeast = 2) { repo.fetchForDispatch(any(), any()) }
    }

    @Test
    fun `sessionInfoList starts empty`() = runTest {
        val repo = mockRepo()
        coEvery { repo.fetchForDispatch(any(), any()) } returns makeListResult()

        val vm = ChatViewModel(repo)
        // Immediately after construction, before any coroutine runs
        assertEquals(emptyList<ChatViewModel.SessionInfo>(), vm.sessionInfoList.value)
    }
}
