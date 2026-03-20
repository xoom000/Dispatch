# Dispatch Project Rules

Hard rules for all Dispatch development. Violations are bugs.

---

## R1: No MutableState writes from background threads

**The crash:** `IllegalStateException: Unsupported concurrent change during composition`

**The rule:** Never write to a Compose `mutableStateOf` value from a coroutine running on `Dispatchers.IO`, `Dispatchers.Default`, or any non-Main dispatcher. Compose's snapshot system is not thread-safe for concurrent writes during recomposition.

**In ViewModels:** Use `MutableStateFlow` for all state. Collect in Compose with `collectAsStateWithLifecycle()`. StateFlow is thread-safe by design.

**In Composables:** `mutableStateOf` inside `remember` blocks is fine — composable functions always run on the main thread. The danger is when a launched coroutine inside a composable writes to state from a background dispatcher.

**If you must write MutableState from a background thread:** Use `Snapshot.withMutableSnapshot { }` to make it atomic, or switch to `withContext(Dispatchers.Main)` first.

**Enforcement:** `ComposeStateWriteGuard` in `DispatchApplication.onCreate()` crashes debug builds immediately with a descriptive message if any MutableState write happens off the main thread.

**No build-time tool catches this.** Google issue tracker has it as "Not Started" (issue #237985810). The runtime guard is our only safety net.

---

## R2: Null-check all image content URIs before loading

**The crash:** SIGSEGV (null pointer dereference) in Glide image loading thread — observed in Google Messages 2026-03-19.

**The rule:** Every content URI passed to an image loader (Coil AsyncImage, Glide) must be null-checked. Use error/fallback placeholders. Content URIs go stale when attachments are deleted, storage providers revoke access, or MMS parts expire.

**Pattern:** Use Coil's `AsyncImage` with `error` and `fallback` parameters, plus an `onError` callback for logging. Never use raw URI loads without error handling.

---

## R3: Firebase libraries need companion Gradle plugins

**The crash:** "The Crashlytics build ID is missing" — app crashes in FirebaseInitProvider before any app code runs.

**The rule:** When adding any Firebase library dependency, always check if it requires a companion Gradle plugin. Crashlytics requires: (1) `firebase-crashlytics-ktx` library, (2) `com.google.firebase.crashlytics` plugin in both root and app build.gradle.kts, (3) `google-services` plugin.

---

## R4: All File Bridge API calls must include auth interceptor

**The crash:** Silent 401 failures — token registration silently broken since hardening.

**The rule:** Any OkHttpClient that calls File Bridge must include `FileBridgeAuthInterceptor()`. No "simple" clients that skip auth. The server enforces API key auth regardless of network (Tailscale is not sufficient).

---

## R5: Two TTS paths — changes go in both

Dispatch voice delivery: `dispatch CLI → FCM → phone → Kokoro`
Streaming chat: `File Bridge → SSE → phone → Kokoro`

Any TTS pipeline change (preprocessing, formatting, voice selection) must be applied to both paths.

---

## R6: Launch crashes need logcat -b crash

App logging (Timber) isn't initialized until after `onCreate()`. If the app crashes before that point (Firebase init, Hilt injection, content provider), the only evidence is in the system crash buffer: `ssh pixel9 "logcat -d -b crash -t 50"`.
