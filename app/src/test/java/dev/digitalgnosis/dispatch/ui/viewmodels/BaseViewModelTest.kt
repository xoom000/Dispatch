package dev.digitalgnosis.dispatch.ui.viewmodels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

/**
 * Base class for ViewModel unit tests.
 *
 * Installs an [UnconfinedTestDispatcher] as the main dispatcher before each test
 * and resets it afterwards, satisfying the `Dispatchers.Main` requirement of
 * `viewModelScope` without needing an Android instrumentation environment.
 *
 * [UnconfinedTestDispatcher] is preferred over [StandardTestDispatcher] for ViewModel tests
 * because it executes coroutines eagerly (without needing advanceUntilIdle()), which means
 * state driven by viewModelScope.launch { } is immediately available after ViewModel creation.
 *
 * Usage:
 * ```kotlin
 * class MyViewModelTest : BaseViewModelTest() {
 *     @Test
 *     fun `my test`() = runTest {
 *         val vm = MyViewModel(mockDep)
 *         // State is already populated — no advanceUntilIdle() needed
 *         assertEquals(expected, vm.state.value)
 *     }
 * }
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class BaseViewModelTest {

    protected val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()

    @Before
    open fun setUpDispatcher() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    open fun tearDownDispatcher() {
        Dispatchers.resetMain()
    }
}
