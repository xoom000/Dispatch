# Dispatch — Full Gap Analysis vs Reference Apps

**Date:** 2026-03-20
**Sources:** Bitwarden Android audit, Audio reference repos (5), Dispatch playback audit
**Status:** COMPLETE — every gap identified, prioritized, and mapped

---

## Architecture Gaps (from Bitwarden)

### GAP-A1: No BaseViewModel / Unidirectional Data Flow
**What Bitwarden does:** BaseViewModel<S, E, A> enforces state only mutates synchronously inside handleAction. Async work posts Actions through a Channel queue. State, Events, and Actions are fully typed per screen.
**What Dispatch does:** ViewModels use MutableStateFlow directly. State can be mutated from any coroutine on any thread. No action channel, no event channel.
**Impact:** Race conditions, untraceable state bugs, impossible to audit state transitions.
**Fix:** Create BaseViewModel<S, E, A> in a base/ package. Migrate ViewModels one at a time. Each ViewModel gets a State data class, an Action sealed class, and an Event sealed class.

### GAP-A2: No Interface + Impl Pattern for DI
**What Bitwarden does:** Every data layer class has an interface and an Impl. DI binds the interface. Mocking is trivial everywhere.
**What Dispatch does:** Concrete classes injected directly. Can't mock without PowerMock hacks.
**Impact:** Testing is nearly impossible at the data layer. Tight coupling everywhere.
**Fix:** For each Repository/DataSource, extract an interface. Bind interface in Hilt module. Inject interface, not impl.

### GAP-A3: No NetworkResult Sealed Type
**What Bitwarden does:** NetworkResult<T> wraps all Retrofit responses. No exceptions thrown anywhere in the network layer. Errors are domain-typed.
**What Dispatch does:** Throws exceptions. Try/catch blocks scattered throughout ViewModels.
**Impact:** Crash-prone networking. Error handling is inconsistent and easy to miss.
**Fix:** Create NetworkResult<T> sealed class (Success, Error, NetworkError). Create Retrofit CallAdapter. Wrap all API service calls.

### GAP-A4: No Typed Navigation Routes
**What Bitwarden does:** @Serializable route data classes. NavGraphBuilder.xDestination() and NavController.navigateToX() extension functions per screen. No raw strings.
**What Dispatch does:** Raw string routes in NavHost. Arguments passed as path segments.
**Impact:** Typo bugs, no compile-time safety, refactoring is dangerous.
**Fix:** Create *Navigation.kt file per screen with typed Route, destination extension, navigate extension. Migrate NavHost.

### GAP-A5: No State-Based Root Navigation
**What Bitwarden does:** RootNavViewModel watches auth state + special circumstances → computes RootNavState → drives top-level navigation. Data layer drives the app flow.
**What Dispatch does:** Manual navigation calls from UI. No centralized auth/state-driven routing.
**Impact:** Can navigate to screens in wrong auth state. No automatic redirect on logout/lock.
**Fix:** Create RootNavViewModel. Watch session state + auth state. Compute RootNavState sealed class. Drive NavHost from it.

### GAP-A6: No SavedStateHandle for Process Death
**What Bitwarden does:** All ViewModel state is @Parcelize and survives process death via SavedStateHandle.
**What Dispatch does:** State dies on process death. Background the app, come back, state is gone.
**Impact:** Lost state when Android kills the app in background (common on Pixel 9 with aggressive battery management).
**Fix:** Make State data classes @Parcelize. Init ViewModel state from SavedStateHandle. Save on every state change.

---

## Security Gaps (from Bitwarden)

### GAP-S1: No Keyboard Incognito Mode
**What Bitwarden does:** IME_FLAG_NO_PERSONALIZED_LEARNING on every text field via InterceptPlatformTextInput wrapper. Keyboard doesn't learn from keystrokes.
**What Dispatch does:** Nothing. Gboard learns everything typed in Dispatch including cmail content, API keys, department names.
**Impact:** Sensitive company data leaks into keyboard suggestion database and potentially to Google servers.
**Fix:** Create IncognitoInput composable wrapper. Apply to all text input in the app.

### GAP-S2: No Screen Capture Prevention
**What Bitwarden does:** FLAG_SECURE applied reactively based on user setting. Prevents screenshots, screen recording, and Recent Apps thumbnails.
**What Dispatch does:** Nothing. Anyone can screenshot Dispatch conversations.
**Impact:** Sensitive DG communications visible in screenshots and Recent Apps.
**Fix:** Add FLAG_SECURE to MainActivity window. Make it a toggleable setting.

### GAP-S3: No Tap-Jacking Prevention
**What Bitwarden does:** filterTouchesWhenObscured = true on the window decorView.
**What Dispatch does:** Nothing.
**Impact:** Malicious overlay apps could intercept touches.
**Fix:** One line in MainActivity: window.decorView.filterTouchesWhenObscured = true

### GAP-S4: No Android Backup Disabled
**What Bitwarden does:** allowBackup="false" + explicit backup exclusion rules.
**What Dispatch does:** Default (backup allowed). Cmail content, API keys, session data could be backed up to Google Drive.
**Impact:** Sensitive data in cloud backups.
**Fix:** Set allowBackup="false" in AndroidManifest.xml. Add data_extraction_rules.xml.

### GAP-S5: No Auth Token Proactive Refresh
**What Bitwarden does:** Interceptor checks token expiry before each request. Refreshes if within 5-minute window. Synchronized to prevent races.
**What Dispatch does:** FileBridgeAuthInterceptor adds a static API key. No token rotation, no refresh.
**Impact:** If we ever move to token-based auth, we need this pattern.
**Fix:** Low priority now (API key auth is simpler). Document for future.

### GAP-S6: Exported Service Without Permission Protection
**What Bitwarden does:** Services protected with signature-level permissions and knownSigner certificates.
**What Dispatch does:** DispatchPlaybackService is exported="true" with no permission protection. Any app can send intents to start audio playback.
**Impact:** Malicious app could trigger audio playback or inject content.
**Fix:** Add a signature-level permission to protect the service, or set exported="false" if external access isn't needed.

---

## UI/Design System Gaps (from Bitwarden)

### GAP-U1: No Custom Design System
**What Bitwarden does:** BitwardenColorScheme with semantic tokens (text.primary, background.secondary, stroke.divider, etc.). 30+ prefixed components. Full theme object.
**What Dispatch does:** Material3 defaults with some custom colors in Color.kt. No semantic token layer. Components use MaterialTheme directly.
**Impact:** Inconsistent styling. Theme changes require touching every screen. No component library.
**Fix:** Create DgColorScheme with semantic categories. Create DgTheme composable. Build DgButton, DgCard, DgTextField, etc. Migrate screens.

### GAP-U2: No CompositionLocal for Activity Managers
**What Bitwarden does:** LocalBiometricsManager, LocalPermissionsManager, etc. provided via CompositionLocal. Clean separation from DI graph.
**What Dispatch does:** Activity context passed manually or accessed via LocalContext.current with casting.
**Impact:** Fragile, hard to test, easy to get wrong lifecycle.
**Fix:** Create LocalManagerProvider composable in MainActivity. Provide managers via CompositionLocal.

### GAP-U3: No Adaptive Layout (Tablet/Foldable)
**What Bitwarden does:** NavigationRail for tablets alongside BottomAppBar for phones.
**What Dispatch does:** BottomAppBar only. No tablet consideration.
**Impact:** Low priority (Nigel uses Pixel 9 phone) but matters for future.
**Fix:** Add WindowSizeClass check. Switch to NavigationRail on expanded width.

---

## Audio/Playback Gaps (from Audio Reference Repos)

### GAP-P1: No Hilt DI Module for Playback Chain ✅ PARTIALLY DONE
**What SimpleMediaPlayer does:** AudioAttributes → ExoPlayer → MediaSession → ServiceHandler as a clean Hilt DI chain.
**What Dispatch does:** Creates everything manually in onCreate(). Sprint 1 extracted classes but didn't wire Hilt.
**Fix:** Create DispatchServiceModule.kt with @Provides for each component.

### GAP-P2: No SharedFlow Event Bus ✅ PARTIALLY DONE
**What KotlinAudio does:** MutableSharedFlow(replay=1) for every player event. 9-state enum.
**What Dispatch does:** PlaybackStateHolder still uses old pattern. Sprint 1 didn't replace it.
**Fix:** Create DispatchPlayerEventBus with SharedFlow per event. Replace PlaybackStateHolder.

### GAP-P3: No Clean Queue API
**What KotlinAudio does:** QueuedAudioPlayer with add(), remove() (descending sort!), move(), jumpToItem().
**What Dispatch does:** messageQueue is a mutable list managed inline. Sprint 1 extracted PlayerEventListener but didn't build a proper queue manager.
**Fix:** Create DispatchQueueManager wrapping ExoPlayer's playlist with the KotlinAudio API.

### GAP-P4: No MediaBrowser in ViewModel
**What Gramophone does:** AndroidViewModel holds MediaBrowser.buildAsync(). Lifecycle-safe connect/disconnect. UI never touches ExoPlayer directly.
**What Dispatch does:** UI accesses playback via PlaybackStateHolder singleton. No MediaBrowser.
**Fix:** Create DispatchPlayerViewModel with MediaBrowser. Connect in onStart(), disconnect in onStop().

### GAP-P5: No Playback Resumption
**What Media3 demo does:** onPlaybackResumption() reads DataStore for last-played position. Resumes on BT headset connect or reboot.
**What Dispatch does:** No resumption. BT headset connect after kill = nothing happens.
**Fix:** Create DispatchLastPlayedManager. Persist last message context. Implement onPlaybackResumption().

### GAP-P6: No Trusted/Untrusted Controller Split
**What Media3 demo does:** onConnect() grants full commands to same-app controllers, restricted commands to external (Android Auto, Wear).
**What Dispatch does:** No onConnect() callback. All controllers get everything.
**Fix:** Create DispatchSessionCallback with trusted/untrusted permission split.

### GAP-P7: No Adaptive TTS Buffer (On-Device Path)
**What NekoSpeak does:** Mutex-guarded engine with adaptive token batching. First sentence flushes immediately. Subsequent sentences batch up to MAX_TOKENS. Never splits mid-sentence.
**What Dispatch does:** TtsEngine calls speakBlocking() synchronously. No batching, no adaptive buffer.
**Fix:** Adopt NekoSpeak's generate() pattern for on-device Piper/Kokoro fallback path.

### GAP-P8: No Audio Focus Management on All Paths ✅ DONE (Sprint 1 killed AudioStreamClient)

---

## Testing Gaps (from Bitwarden)

### GAP-T1: No Unit Tests on ViewModels
**What Bitwarden does:** Every ViewModel has a test file. BaseViewModelTest with MainDispatcherExtension. Turbine for Flow testing.
**What Dispatch does:** One test file (ChatViewModelTest.kt). No base test class. No systematic testing.
**Impact:** No confidence in refactoring. Bugs found by Nigel, not by tests.
**Fix:** Create BaseViewModelTest. Add MainDispatcherExtension. Start with the highest-traffic ViewModels.

### GAP-T2: No Compose Screen Tests
**What Bitwarden does:** BaseComposeTest with createComposeRule. MockNavHostController for navigation testing.
**What Dispatch does:** Zero compose tests.
**Fix:** Create BaseComposeTest. Start with MessagesScreen.

### GAP-T3: No MockK / Turbine Setup
**What Bitwarden does:** MockK for mocking, Turbine for Flow testing, both in every test.
**What Dispatch does:** Not in dependencies.
**Fix:** Add MockK + Turbine to test dependencies. Create test fixtures module pattern.

### GAP-T4: Zero Tolerance Detekt (maxIssues: 0)
**What Bitwarden does:** Detekt configured with maxIssues: 0. Build fails on any issue.
**What Dispatch does:** Detekt wired but maxIssues not set to 0. Issues are warnings, not errors.
**Fix:** Fix remaining detekt findings, then set maxIssues: 0.

---

## Build System Gaps (from Bitwarden)

### GAP-B1: No Multi-Module Architecture
**What Bitwarden does:** 10 modules. Network isolated. UI isolated. Core has no Android deps.
**What Dispatch does:** Single :app module. Everything in one place.
**Impact:** Build times scale linearly. Can't test layers independently. Can't swap implementations.
**Fix:** Long-term. Extract :network, :data, :ui modules. Start with :network (most isolated).

### GAP-B2: No Product Flavors
**What Bitwarden does:** standard (Play) and fdroid (no Google) flavors.
**What Dispatch does:** Single flavor.
**Impact:** Low priority (DG only). But useful if we ever open-source or distribute outside Play.
**Fix:** Low priority. Note for future.

### GAP-B3: No Test Parallelism Configuration
**What Bitwarden does:** maxParallelForks, forkEvery, maxHeapSize configured for fast test runs.
**What Dispatch does:** Default settings.
**Fix:** Add test parallelism config to root build.gradle.kts when we have enough tests to matter.

---

## Infrastructure Gaps (Existing Sprint List)

### GAP-I1: Systemd Watchdog Broken
Service file watchdog disabled due to native module crash. Need pip install systemd-python then re-enable.

### GAP-I2: File Bridge Service File Sync
Project copy may diverge from deployed copy on pop-os.

---

## Summary: Priority Order for Sprint 2

### IMMEDIATE (Ship today — high impact, low effort)
1. GAP-S3: Tap-jacking prevention (1 line)
2. GAP-S4: Disable Android backup (1 line + XML)
3. GAP-S6: Protect exported PlaybackService
4. GAP-S2: FLAG_SECURE for screen capture prevention
5. GAP-S1: Keyboard incognito mode

### HIGH (Next sprint — foundational architecture)
6. GAP-A1: BaseViewModel with UDF
7. GAP-A3: NetworkResult sealed type
8. GAP-P1: Hilt DI module for playback
9. GAP-P2: SharedFlow event bus replacing PlaybackStateHolder
10. GAP-P3: Clean queue API

### MEDIUM (Following sprints — quality + features)
11. GAP-A2: Interface + Impl pattern for DI
12. GAP-A4: Typed navigation routes
13. GAP-U1: Custom design system
14. GAP-P4: MediaBrowser in ViewModel
15. GAP-P5: Playback resumption
16. GAP-P6: Trusted/untrusted controller split
17. GAP-T1: ViewModel unit tests
18. GAP-T3: MockK + Turbine setup

### LONG-TERM (Maturity — do when stable)
19. GAP-A5: State-based root navigation
20. GAP-A6: SavedStateHandle for process death
21. GAP-U2: CompositionLocal managers
22. GAP-U3: Adaptive layout
23. GAP-P7: Adaptive TTS buffer
24. GAP-T2: Compose screen tests
25. GAP-T4: Zero tolerance detekt
26. GAP-B1: Multi-module architecture
27. GAP-B2: Product flavors
28. GAP-B3: Test parallelism

---

**Total gaps identified: 28**
**Already addressed by Sprint 1: 1 (GAP-P8)**
**Remaining: 27**
