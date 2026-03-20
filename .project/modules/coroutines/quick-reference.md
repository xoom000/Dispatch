# Kotlin Coroutines — Quick Reference

**What it is:** Kotlin's first-class concurrency framework. Coroutines are light-weight threads that suspend instead of block, enabling sequential-style async code without callback hell. Used throughout Android for ViewModel work, IO, and reactive UI state.

**Context7 Library ID:** `/kotlin/kotlinx.coroutines`
**Version:** `1.8.1`

---

## Gradle Dependencies

```kotlin
// build.gradle.kts (app)
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // For viewModelScope
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    // For lifecycleScope / repeatOnLifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // For collectAsState in Compose
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
}
```

---

## Dispatchers

| Dispatcher | Thread pool | Use when |
|---|---|---|
| `Dispatchers.Main` | Android main thread | UI updates, LiveData/StateFlow updates, View interaction |
| `Dispatchers.IO` | Elastic pool (up to 64 threads) | Network calls, file/database read-write, any blocking I/O |
| `Dispatchers.Default` | CPU-count threads | JSON parsing, sorting, image processing, heavy computation |
| `Dispatchers.Unconfined` | Caller's thread; resumes on whatever thread suspends | Testing, special cases — avoid in production Android code |

```kotlin
import kotlinx.coroutines.*

fun main() = runBlocking {
    launch(Dispatchers.Default) {
        // CPU work — e.g. parsing, sorting
        println("Default: ${Thread.currentThread().name}")
    }

    launch(Dispatchers.IO) {
        // Blocking I/O — network, disk, DB
        println("IO: ${Thread.currentThread().name}")
    }

    // Unconfined: starts on caller thread, resumes on whichever thread
    // the first suspension point runs on — generally avoid in Android UI code
    launch(Dispatchers.Unconfined) {
        println("Unconfined before: ${Thread.currentThread().name}")
        delay(100)
        println("Unconfined after:  ${Thread.currentThread().name}") // different thread!
    }
}
```

---

## CoroutineScope Patterns

### viewModelScope — preferred in ViewModel

Cancelled automatically when the ViewModel is cleared. Never leaks.

```kotlin
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MyViewModel : ViewModel() {

    fun loadData() {
        viewModelScope.launch {
            // Already on Main; safe to update UI state directly after withContext
            val result = withContext(Dispatchers.IO) {
                fetchFromNetwork() // blocking call isolated to IO thread
            }
            _uiState.value = result // back on Main
        }
    }
}
```

### lifecycleScope — preferred in Fragment / Activity

Cancelled when the lifecycle owner is destroyed.

```kotlin
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MyFragment : Fragment() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // repeatOnLifecycle restarts the block on STARTED and cancels on STOPPED.
        // Prevents collecting a Flow when the UI is off-screen.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    render(state)
                }
            }
        }
    }
}
```

### MainScope — for non-Lifecycle classes (e.g. custom View, Service)

Must be cancelled manually.

```kotlin
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MyCustomView(context: Context) : View(context) {
    private val scope = MainScope()

    fun startWork() {
        scope.launch { /* ... */ }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel() // prevent leaks
    }
}
```

### Custom scope — explicit job control

```kotlin
import kotlinx.coroutines.*

class MyRepository {
    // SupervisorJob: child failures don't cancel siblings or the parent
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun startBackgroundSync() {
        scope.launch {
            while (true) {
                syncOnce()
                delay(30_000L)
            }
        }
    }

    fun close() {
        scope.cancel()
    }
}
```

---

## Flow — Cold Streams

A `Flow` does nothing until it is collected. Each collector gets its own independent execution.

### Builder + collect

```kotlin
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*

fun numbersFlow(): Flow<Int> = flow {
    println("Flow started") // only runs when collected
    for (i in 1..5) {
        delay(100L)
        emit(i)
    }
}

fun main() = runBlocking {
    numbersFlow().collect { value ->
        println("Collected: $value")
    }
}
```

### map / filter / onEach / catch

```kotlin
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*

fun main() = runBlocking {
    flow {
        for (i in 1..6) emit(i)
    }
        .filter { it % 2 == 0 }          // 2, 4, 6
        .map { it * it }                  // 4, 16, 36
        .onEach { println("About to emit: $it") } // side-effect without consuming
        .catch { e -> println("Error: $e") }       // catches upstream exceptions
        .collect { println("Got: $it") }
}
```

### flowOn — move upstream work off Main

```kotlin
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*

fun heavyFlow(): Flow<String> = flow {
    repeat(10) { i ->
        val result = doHeavyWork(i) // runs on IO, not on Main
        emit(result)
    }
}.flowOn(Dispatchers.IO) // applies to everything ABOVE this operator

// In ViewModel:
viewModelScope.launch {
    heavyFlow().collect { value ->
        _uiState.value = value // collect runs on Main (viewModelScope's dispatcher)
    }
}
```

---

## StateFlow — Hot, Single-Value State

`StateFlow` always holds exactly one value and replays it to new collectors. The canonical ViewModel → UI state container.

### Declare and update in ViewModel

```kotlin
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiState(val loading: Boolean = false, val items: List<String> = emptyList())

class MyViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow() // expose read-only

    fun loadItems() {
        viewModelScope.launch {
            _uiState.value = UiState(loading = true)
            val items = fetchItems() // suspend fun
            _uiState.value = UiState(loading = false, items = items)
        }
    }

    // For complex state, prefer update{} — it is thread-safe (atomic CAS)
    fun addItem(item: String) {
        _uiState.update { current -> current.copy(items = current.items + item) }
    }
}
```

### collectAsState in Compose

```kotlin
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
fun MyScreen(viewModel: MyViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.loading) {
        CircularProgressIndicator()
    } else {
        LazyColumn {
            items(uiState.items) { item -> Text(item) }
        }
    }
}
```

---

## SharedFlow — Hot, Event Broadcasting

`SharedFlow` has no fixed current value. It broadcasts emissions to all active collectors. Useful for one-shot events (navigation, snackbars) and pub/sub patterns.

```kotlin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// replay = 0: new collectors miss past events (event bus behavior)
// replay = 1: new collectors receive the last event (similar to StateFlow but nullable)
private val _events = MutableSharedFlow<UiEvent>(replay = 0)
val events: SharedFlow<UiEvent> = _events.asSharedFlow()

// Emitting
viewModelScope.launch {
    _events.emit(UiEvent.ShowSnackbar("Saved!"))
}

// Collecting in Fragment (inside repeatOnLifecycle)
viewLifecycleOwner.lifecycleScope.launch {
    viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.ShowSnackbar -> showSnackbar(event.message)
                is UiEvent.Navigate     -> navigate(event.route)
            }
        }
    }
}
```

---

## async / await — Concurrent Operations

Use `async` when you need a result from concurrent work. Both tasks start immediately and run in parallel; `await()` suspends until the result is ready.

```kotlin
import kotlinx.coroutines.*

suspend fun loadDashboard(): DashboardData = coroutineScope {
    // Both network calls start at the same time
    val userDeferred    = async(Dispatchers.IO) { fetchUser() }
    val metricsDeferred = async(Dispatchers.IO) { fetchMetrics() }

    // Suspends here until both complete; total time ≈ max(t1, t2), not t1 + t2
    DashboardData(
        user    = userDeferred.await(),
        metrics = metricsDeferred.await()
    )
}
```

> Use `coroutineScope { }` (not `GlobalScope`) so that if either `async` throws, the other is cancelled and the exception propagates correctly.

---

## withContext — Thread Switching

`withContext` suspends the current coroutine, runs the block on a different dispatcher, then resumes on the original dispatcher. Cleaner than `launch` + callback for switching once.

```kotlin
import kotlinx.coroutines.*

// In a ViewModel (already on Main via viewModelScope)
suspend fun saveAndRefresh(data: Data) {
    // Switch to IO, do work, come back to Main automatically
    withContext(Dispatchers.IO) {
        database.save(data)      // blocking DB call
        prefs.write(data.prefs)  // blocking file write
    }
    // Back on Main here — safe to update UI state
    _uiState.value = UiState.Success

    withContext(Dispatchers.Default) {
        val processed = heavyTransform(data) // CPU work
        _result.value = processed
    }
    // Back on Main again
}
```

---

## Key Gotchas

### 1. Collecting a Flow in the wrong scope

```kotlin
// BAD — lifecycleScope.launch alone keeps collecting even when the UI is STOPPED
lifecycleScope.launch {
    viewModel.flow.collect { render(it) } // collects in background, wasted work + potential crash
}

// GOOD — repeatOnLifecycle stops collection when STOPPED, restarts when STARTED
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.flow.collect { render(it) }
    }
}
```

### 2. Forgetting flowOn — blocking Main

```kotlin
// BAD — the flow builder runs on whatever dispatcher collect() is called from
fun loadFromDisk(): Flow<Item> = flow {
    emit(database.query()) // blocking call — if collected on Main, freezes UI
}

// GOOD — shift upstream execution off Main
fun loadFromDisk(): Flow<Item> = flow {
    emit(database.query())
}.flowOn(Dispatchers.IO) // database.query() runs on IO; collect still on Main
```

### 3. Coroutine cancellation is cooperative

```kotlin
// BAD — tight loop that never checks for cancellation
suspend fun neverCancels() {
    while (true) {
        var sum = 0L
        for (i in 0..Long.MAX_VALUE) sum += i // CancellationException never thrown
    }
}

// GOOD — yield() or isActive check gives the coroutine a chance to cancel
suspend fun cooperativeCpu() {
    while (isActive) {      // isActive = false once cancelled
        doChunkOfWork()
        yield()             // also suspends and checks cancellation
    }
}
```

### 4. Leaking coroutines with GlobalScope

```kotlin
// BAD — GlobalScope lives for the app process; never tied to a lifecycle
GlobalScope.launch {
    viewModel.items.collect { render(it) } // ViewModel may be dead, but this runs forever
}

// GOOD — always use a scope tied to the component's lifetime
viewModelScope.launch { /* automatically cancelled with ViewModel */ }
lifecycleScope.launch { /* automatically cancelled when Activity/Fragment is destroyed */ }
```

### 5. Exception handling: async vs launch

```kotlin
// launch — exceptions propagate up immediately to the CoroutineExceptionHandler or crash
viewModelScope.launch {
    throw RuntimeException("boom") // propagates to viewModelScope's parent handler
}

// async — exceptions are stored in the Deferred and thrown only on .await()
val deferred = viewModelScope.async {
    throw RuntimeException("boom") // silent until...
}
deferred.await() // ...thrown HERE — must be wrapped in try/catch

// Safe pattern for async inside coroutineScope:
try {
    coroutineScope {
        val a = async { riskyOp() }
        a.await()
    }
} catch (e: Exception) {
    handleError(e)
}
```

### 6. Structured concurrency: don't break the parent-child chain

```kotlin
// BAD — launching into a foreign scope breaks structured concurrency;
// the new coroutine won't be cancelled when the ViewModel is cleared
class MyViewModel : ViewModel() {
    fun doWork() {
        CoroutineScope(Dispatchers.IO).launch { /* orphaned! */ }
    }
}

// GOOD — always launch into the component's own scope
class MyViewModel : ViewModel() {
    fun doWork() {
        viewModelScope.launch(Dispatchers.IO) { /* bound to ViewModel lifecycle */ }
    }
}
```
