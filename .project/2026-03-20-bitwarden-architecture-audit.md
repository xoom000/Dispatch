# Bitwarden Android Architecture Audit

**Date:** 2026-03-20
**Source:** `/home/xoom000/AndroidStudioProjects/bitwarden-android/`
**Version:** 2026.2.0 (versionCode 1 — their versionCode is a build artifact, not semantic)
**Purpose:** Reference document for aligning Dispatch's architecture with world-class Android patterns

---

## Table of Contents

1. [Project Structure and Module Layout](#1-project-structure-and-module-layout)
2. [Architecture Pattern](#2-architecture-pattern)
3. [Navigation](#3-navigation)
4. [Networking](#4-networking)
5. [Local Storage](#5-local-storage)
6. [Security Patterns](#6-security-patterns)
7. [UI Layer](#7-ui-layer)
8. [Background Work](#8-background-work)
9. [Testing](#9-testing)
10. [Build System](#10-build-system)
11. [Code Quality](#11-code-quality)
12. [Accessibility](#12-accessibility)
13. [Key Patterns to Emulate](#13-key-patterns-to-emulate)

---

## 1. Project Structure and Module Layout

### Gradle Modules (10 total)

```
:annotation         — Custom annotations (OmitFromCoverage, etc.)
:app                — Main password manager application
:authenticator      — Standalone TOTP authenticator application
:authenticatorbridge — IPC bridge between :app and :authenticator
:core               — Foundation: business logic, shared models, utilities
:cxf                — Credential Exchange Format (FIDO2 import/export)
:data               — Shared data layer (ConfigDiskSource, FlightRecorder, etc.)
:network            — All networking (Retrofit, OkHttp, interceptors, API services)
:testharness        — Not investigated, likely test infrastructure
:ui                 — Shared UI components, theme, base ViewModel, navigation utils
```

### Module Dependency Direction

The dependency graph is intentionally layered and mostly acyclic:

- `:app` depends on everything (`:annotation`, `:core`, `:cxf`, `:data`, `:network`, `:ui`, `:authenticatorbridge`)
- `:authenticator` is a separate application module — it does NOT depend on `:app`
- `:authenticatorbridge` is a pure communication interface, no app logic
- `:ui` depends on `:core` and `:data` but NOT on `:app`
- `:network` is isolated — no Android UI dependencies
- `:core` has zero or minimal Android framework dependencies by design (maximizes testability)
- `:data` provides shared persistence infrastructure used by both `:app` and `:authenticator`

### Source Set Layout Within :app

The `:app` module uses both build types and product flavors to create separate source sets:

- `src/main/` — All shared code across all variants
- `src/standard/` — Google Play builds: Firebase (FCM, Crashlytics), Google Billing, Play Reviews
- `src/fdroid/` — F-Droid builds: no Google Play Services, no push notifications, no crash reporting
- `src/debug/` — Debug-only overrides (debug menu enabled, verbose logging, `.dev` app ID suffix)
- `src/release/` — ProGuard rules, release-specific config
- `src/test/` — JVM unit tests
- `src/standardDebug/` — Source set that only exists for one specific variant combination

Product flavors: `standard` (default) and `fdroid`, dimension named `mode`.

Build types: `debug`, `beta` (`.beta` app ID suffix, minified, no debug menu), `release` (minified, no debug menu).

This yields variant combinations like `standardDebug`, `standardRelease`, `standardBeta`, `fdroidDebug`, `fdroidRelease`, `fdroidBeta`.

### Package Naming Convention

The app uses two distinct package namespaces reflecting the modular separation:

- `com.x8bit.bitwarden.*` — All code in the `:app` module (the original Android package name)
- `com.bitwarden.*` — All code in library modules (`:core`, `:data`, `:network`, `:ui`, `:cxf`, `:authenticatorbridge`)

Within `:app`, packages are organized by domain slice, not by layer:

```
com.x8bit.bitwarden/
    data/
        auth/           — Auth data (disk, network, SDK sources; managers; repository)
        autofill/       — Autofill service implementation
        billing/        — Billing data (standard flavor only)
        credentials/    — Passkey/FIDO2 credential provider
        platform/       — Cross-cutting data (settings, environment, push, feature flags)
        tiles/          — Quick Settings tile services
        vault/          — Vault data (ciphers, folders, sends)
    ui/
        auth/           — Auth screens (login, registration, 2FA, trusted device, etc.)
        autofill/       — Autofill UI overlays
        credentials/    — Credential provider UI
        platform/       — Platform UI (root nav, settings, splash, search, vault unlocked nav)
        tools/          — Generator and Send features
        vault/          — Vault item screens (list, add/edit, item detail, etc.)
```

---

## 2. Architecture Pattern

### Overall: MVVM with Unidirectional Data Flow (UDF)

Bitwarden implements a strict MVVM pattern where all UI state changes flow in one direction: UI sends Actions to ViewModel, ViewModel updates State, UI renders State. This is documented explicitly in `docs/ARCHITECTURE.md`.

### BaseViewModel: The Core Abstraction

All ViewModels extend `BaseViewModel<S, E, A>` where:
- `S` = State (the complete, total UI state — a single sealed/data class, always @Parcelize for process death survival)
- `E` = Event (one-shot side effects, typically navigation triggers)
- `A` = Action (things the ViewModel can handle, both user-triggered and internal)

**Critically enforced rule:** State is NEVER mutated inside a coroutine. All async work launches a coroutine, then posts an Internal action to the `actionChannel`. State changes only happen synchronously inside `handleAction`. This makes all state transitions auditable and debugable.

The `BaseViewModel` uses a `Channel<A>` (unlimited capacity) for actions — not `StateFlow` or `MutableSharedFlow`. Actions are consumed sequentially via `consumeAsFlow()`. This queue-based approach prevents race conditions.

Events use `Channel<E>` with `receiveAsFlow()` — single-consumer semantics. Only one subscriber gets each event. The `EventsEffect` composable handles this consumption with lifecycle awareness.

### Three-Tier Data Layer

**Tier 1: Data Sources** — Raw data, no business logic. Three sub-categories:
- `DiskSource` — SharedPreferences (encrypted or unencrypted) or Room
- `NetworkDataSource` / `ApiService` — Retrofit calls, wrap responses in `Result<T>`
- `SdkSource` — Calls into the native Bitwarden SDK (Rust via JNI)

Data sources NEVER throw exceptions. Network/SDK calls return `Result<T>`.

**Tier 2: Managers** — Single responsibility, mid-level orchestration. Some are pure (no dependencies), some wrap OS APIs, some coordinate between data sources. Examples: `VaultLockManager`, `BiometricsEncryptionManager`, `PushManager`, `SpecialCircumstanceManager`.

The Manager/Repository split is intentional: Managers have a narrow, discrete responsibility. Repositories are broad domain owners.

**Tier 3: Repositories** — Broad domain owners, exposed to the UI layer. They synthesize multiple sources and managers. Repository error types are domain-specific sealed classes — not `Result<T>` with raw `Throwable`.

Repositories expose continuous data via `StateFlow` using `DataState<T>` / `LocalDataState<T>` wrapper types that encode Loading/Loaded/Error states.

### Interface + Impl Pattern (Everywhere)

Every data layer class has a corresponding interface. Example: `AuthDiskSource` / `AuthDiskSourceImpl`. The interface is what's DI-bound; the Impl is never injected directly. This enforces testability — every dependency can be mocked/faked at the interface boundary.

### Dependency Injection: Dagger Hilt

- All modules annotated `@Module @InstallIn(SingletonComponent::class)` — everything is application-scoped singletons
- `@HiltViewModel` on all ViewModels
- `@HiltAndroidApp` on `BitwardenApplication`
- `@AndroidEntryPoint` on all Activities
- No ViewModel scoped DI — they depend on singletons only
- Two qualifier annotations for SharedPreferences: `@EncryptedPreferences` and `@UnencryptedPreferences`
- The `SavedStateHandle` is injected into ViewModels for safe args passing from navigation and process death persistence
- DI modules are manually written (no auto-discovery) — each module provides exactly what it exposes

### No Domain Layer

There is no explicit "domain" layer with use cases. The Repository layer plays the role that a use case layer would in Clean Architecture. This is a deliberate simplification — the full clean architecture use case layer was judged unnecessary.

---

## 3. Navigation

### Compose Navigation (Jetpack Navigation 2.9.7)

All navigation uses `androidx.navigation.compose.NavHost` with `NavController`. There is no custom navigation framework.

### Two-Level Navigation Architecture

**Level 1: State-Based Root Navigation (`RootNavScreen` / `RootNavViewModel`)**

The `RootNavViewModel` monitors:
- `AuthRepository.userStateFlow` — who's logged in, are they locked/unlocked
- `SpecialCircumstanceManager.specialCircumstanceStateFlow` — what special context launched the app (autofill, TOTP add, share, passkey, etc.)

From these two signals it computes a `RootNavState` (a sealed class with ~20 states like `Auth`, `VaultLocked`, `VaultUnlocked`, `OnboardingAccountLockSetup`, `CredentialExchangeExport`, etc.).

`RootNavScreen` watches this state and issues navigations with `popUpTo(graph.id, inclusive = false)` to clear the back stack — so the user can never back-navigate into the wrong flow.

This means: **the data layer drives the top-level app flow**. A logout anywhere in the data layer automatically causes navigation to the auth graph without the UI needing to know why or from where.

**Level 2: Event-Based Screen Navigation**

Within flows (auth graph, vault graph, settings, etc.) navigation is triggered by Events emitted from a ViewModel in response to user Actions. The `EventsEffect` composable collects events and invokes callbacks passed down from the graph (NavController extension functions).

### Navigation Implementation Pattern

Each feature has a `...Navigation.kt` file containing:
1. A `@Serializable` route data class (e.g., `data class ExampleRoute(val isToggleEnabled: Boolean)`) — used as the type-safe navigation destination
2. A `NavGraphBuilder.exampleDestination()` extension function that registers the composable in the graph
3. A `NavController.navigateToExample()` extension function with typed parameters
4. An `...Args` class that extracts route data from `SavedStateHandle` via `savedStateHandle.toRoute<ExampleRoute>()`

Consumers never touch raw String routes. The navigation file is the single source of truth for that screen's navigation contract.

### Deep Links and Special Circumstances

Deep links are not handled via standard Android deep link declarations in nav routes. Instead, intents arriving at `MainActivity.onNewIntent` are parsed in `MainViewModel.handleIntent` and converted into `SpecialCircumstance` objects stored in `SpecialCircumstanceManager`. The `RootNavViewModel` then responds to the `SpecialCircumstance` by computing the correct `RootNavState`.

This indirection means:
- Deep link parsing is fully unit-testable (no Android navigation framework involved)
- The auth state and vault lock state are always checked before acting on a deep link
- Multiple deep link types (TOTP URI, autofill selection, passwordless request, share, passkey, etc.) are handled uniformly

Supported inbound URI schemes: `otpauth://totp/...`, `bitwarden://...` (custom scheme), `https://*.bitwarden.com/redirect-connector...` (App Links). Also handles `ACTION_SEND` intents for Bitwarden Sends.

### Transition Animations

Custom transition providers are defined in `:ui`'s theme: `RootTransitionProviders` with `Enter.none`, `Enter.fadeIn`, `Enter.slideUp` and `Exit.none`, `Exit.fadeOut`, `Exit.slideDown`, `Exit.stay` variants. The root nav level uses fade transitions between most state changes, with slide up/down for modally-presented flows like `ResetPassword` and `MigrateToMyItems`.

---

## 4. Networking

### Stack: Retrofit 3 + OkHttp 5 + kotlinx.serialization

The entire network layer lives in the `:network` module. The entry point is `BitwardenServiceClient` / `BitwardenServiceClientImpl`, constructed with a `BitwardenServiceClientConfig`. The app uses a factory DSL `bitwardenServiceClient { ... }` to construct it via the DI module.

### Multiple Retrofit Instances

`RetrofitsImpl` creates 4+ distinct Retrofit instances:
- `authenticatedApiRetrofit` — for authenticated API endpoints
- `authenticatedEventsRetrofit` — for authenticated events endpoint
- `unauthenticatedApiRetrofit` — for unauthenticated API endpoints
- `unauthenticatedIdentityRetrofit` — for unauthenticated identity endpoints
- Dynamic `createStaticRetrofit(isAuthenticated, baseUrl)` — for Azure file downloads, HIBP, etc.

Authenticated Retrofit instances use an `OkHttpClient` with `AuthTokenManager` as both `Interceptor` (adds Bearer token) and `Authenticator` (handles 401s).

Serialization uses `kotlinx.serialization` JSON via Retrofit's `kotlinx.serialization` converter factory.

### Authentication / Token Refresh

`AuthTokenManager` implements `Interceptor`, `Authenticator`, and a custom `TokenProvider` interface.

**Proactive refresh (Interceptor path):** Before attaching the access token, checks if the token expires within 5 minutes. If so, synchronously calls `refreshTokenProvider.refreshAccessTokenSynchronously()`. The `@Synchronized` annotation ensures no race conditions on concurrent requests.

**Reactive refresh (Authenticator path):** On a 401 response, parses the userId from the JWT in the Authorization header, then calls the refresh provider. If refresh succeeds, retries the request with the new token. If a prior response already exists (repeated 401), passes through to avoid infinite loops.

### OkHttp Interceptors

- `HeadersInterceptor` — Adds `User-Agent`, `X-Client-Name`, `X-Client-Version`, `X-Device-Type` to every request
- `AuthTokenManager` (as Interceptor) — Adds `Authorization: Bearer <token>` to authenticated requests
- `BaseUrlInterceptor` / `BaseUrlInterceptors` — Dynamic base URL routing (API, Identity, Events endpoints can be self-hosted)
- `CookieInterceptor` — Manages session cookies for specific auth flows
- `HttpLoggingInterceptor` — Logs at BASIC level normally, BODY level only on dev builds; redacts `Authorization` header

### API Services

22+ API service interfaces in `/network/src/.../api/`. Split into `Authenticated*Api` and `Unauthenticated*Api` interfaces. Service implementations (`*ServiceImpl`) implement a corresponding `*Service` interface. Services return `NetworkResult<T>` via a custom `CallAdapter`.

`NetworkResult<T>` is a sealed type that wraps Retrofit responses and maps HTTP errors to domain error types without throwing exceptions.

### SSL / Certificate Pinning

The `RetrofitsImpl` calls `configureSsl(certificateProvider)` on the OkHttp builder. The `CertificateManager` / `CertificateProvider` interface allows the app to configure custom SSL certificates — supporting self-hosted Bitwarden instances with internal CA certificates.

The `network_security_config.xml` configures base HTTPS with cleartext blocked globally (except specific CRL/OCSP server domains), explicit trust of user-added CAs (needed for self-hosted), and explicit rules for Bitwarden domains.

---

## 5. Local Storage

### Three Storage Mechanisms

**Room Database**

Two Room databases:
- `VaultDatabase` (version 9) — Stores encrypted vault data: `CipherEntity`, `CollectionEntity`, `DomainsEntity`, `FolderEntity`, `SendEntity`. Uses `AutoMigration` for versions 6→7 and 7→8. Version 9 had manual migrations.
- `PlatformDatabase` (version 2) — Stores `OrganizationEventEntity` (for queued event logging). Uses `AutoMigration` for version 1→2.

Note: The vault data stored in Room is **already encrypted** by the Bitwarden SDK before being written. Room is just a persistence container, not the encryption layer.

**EncryptedSharedPreferences**

Used for sensitive auth credentials: access tokens, refresh tokens, biometric encryption keys, IV vectors, device keys, PIN-protected user keys.

Key details:
- Backed by `androidx.security.crypto.EncryptedSharedPreferences` using `AES256_SIV` for key encryption and `AES256_GCM` for value encryption.
- Master key uses `MasterKey.KeyScheme.AES256_GCM` backed by the Android Keystore.
- All keys are prefixed with `"bwSecureStorage:"` via `BaseEncryptedDiskSource`.
- On keystore corruption: the `PreferenceModule` catches `GeneralSecurityException` and `RuntimeException`, deletes the master key entry from KeyStore, deletes the encrypted prefs file, and recreates from scratch. This logs out all users but prevents a corrupted state.

**Unencrypted SharedPreferences**

Used for non-sensitive settings: app theme, app language, vault timeout settings, screen capture preference, autofill preferences, biometric integrity check hashes, last sync time, etc.

Keys are stored with a `BASE_KEY:` prefix pattern via `BaseDiskSource`.

### DiskSource Pattern

All disk sources extend either `BaseDiskSource` (unencrypted SharedPreferences) or `BaseEncryptedDiskSource` (both encrypted and unencrypted SharedPreferences).

Every setting that should emit updates has a `MutableSharedFlow` with `replay = 1` (using a custom `bufferedMutableSharedFlow` factory). The setting's setter emits into the flow; consumers collect from the flow. This provides reactive, Flow-based settings observation without DataStore.

User-scoped settings (vault timeout, autofill preferences, etc.) are keyed by userId: `"$BASE_KEY:$userId_settingKey"`.

### Legacy Migration

The app migrated from Xamarin/MAUI. There are `LegacySecureStorage`, `LegacySecureStorageMigrator`, and `LegacyAppCenterMigrator` classes that handle one-time migration of data from the old Xamarin encrypted storage format to the new AndroidX encrypted format. The migrators run at startup and are idempotent.

---

## 6. Security Patterns

This is the most important section for a communications app. Bitwarden's security approach is comprehensive.

### Vault Encryption via Bitwarden SDK

All cryptographic operations on vault data (ciphers, folders, collections, sends) are performed by the native Bitwarden Rust SDK accessed via JNI (`VaultSdkSource` / `VaultSdkSourceImpl`). The app itself never performs raw crypto on vault data — it's all delegated to the SDK.

The SDK maintains per-user cryptographic state. Unlocking the vault means initializing the SDK's crypto context for a user via `initializeCrypto()` with the appropriate `InitUserCryptoMethod` (master password, biometric key, PIN key, etc.). Locking the vault means calling `clearCrypto(userId)` on the SDK, which wipes the in-memory keys.

### Vault Auto-Lock

`VaultLockManager` (789 lines) is the central security enforcer. It:
- Monitors `AppForegroundState` (foreground/background transitions)
- Monitors `AppCreationState` (app creation vs. restart)
- Manages per-user vault timeout timers based on `VaultTimeout` settings
- Handles `ACTION_SCREEN_OFF` and `ACTION_USER_PRESENT` broadcast receivers for immediate lock on screen-off
- Supports multiple timeout modes: Immediately, One Minute, Five Minutes, Fifteen Minutes, Thirty Minutes, One Hour, Four Hours, Eight Hours, On App Restart, Never
- Supports two timeout actions: Lock (keeps account, clears crypto) or Logout (removes account entirely)
- Enforces biometric validity checks — if Android biometrics change (enrolled/unenrolled), biometric unlock is invalidated

### Biometric Unlock

`BiometricsEncryptionManagerImpl` uses the Android Keystore for per-user AES-256/CBC/PKCS7Padding encryption of the biometric unlock key.

Key setup:
- Generates a `KeyGenParameterSpec` for each user, requiring biometric authentication to use the key (`setUserAuthenticationRequired(true)`)
- Keys are created with `setInvalidatedByBiometricEnrollment(true)` — biometric changes permanently invalidate the key
- Keys are user-scoped, not device-scoped — multiple accounts each have independent keystore entries
- Uses an "integrity check" system: stores a UUID representing the current biometric enrollment state; on each unlock attempt, verifies the stored value matches the current system state. If biometrics changed, the stored key is invalidated.

### PIN Unlock

PIN-protected unlocks use the Bitwarden SDK's crypto to wrap the user key with a KDF-derived PIN key. The PIN itself is never stored — only the PIN-encrypted user key envelope is persisted in unencrypted SharedPreferences.

### Screen Capture Prevention

`WindowManager.LayoutParams.FLAG_SECURE` is applied to the main window whenever `settingsRepository.isScreenCaptureAllowed == false` (which is the default). The `MainViewModel` reactively updates this flag via a Flow from `SettingsDiskSource`. This prevents screenshots, screen recording, and Recent Apps thumbnails from showing vault content.

Also: `window.decorView.filterTouchesWhenObscured = true` is set on `MainActivity` — prevents tap-jacking attacks where a malicious overlay intercepts touches.

### Keyboard Incognito Mode

`IncognitoInput` wraps the entire Compose content in a `InterceptPlatformTextInput` that adds `IME_FLAG_NO_PERSONALIZED_LEARNING` to every text field's `EditorInfo`. This forces the system keyboard into "incognito mode" — keystrokes are not used for autocomplete learning, not sent to the keyboard provider's servers, and not stored in any keyboard suggestion database.

### No Backup

The manifest sets `android:allowBackup="false"` and references `data_extraction_rules.xml` and `backup_rules.xml` that explicitly exclude the app from Android's auto-backup system. Vault data never leaves the device via OS backup mechanisms.

### Network Security

- HTTPS enforced globally; cleartext blocked by default in `network_security_config.xml`
- Authorization header is redacted from HTTP logs even on debug builds
- The custom `AuthTokenProvider` ensures tokens are not accessible outside the network layer
- Certificate pinning support via `CertificateManager` for self-hosted deployments

### Touch Interception Protection

`window.decorView.filterTouchesWhenObscured = true` at the window level prevents any touch from being processed if another window is drawn on top (e.g., a screen overlay attack).

### Credential Exchange Security

The `CredentialProviderActivity` is `exported="false"` — it can only be started by the app's own `PendingIntent`s. Its manifest comment explicitly states: "Only our own PendingIntents can launch this activity." The authenticator bridge service uses a custom permission with `protectionLevel="signature|knownSigner"` and `knownCerts` referencing known Bitwarden Authenticator app certificates.

### Clipboard Clearing

There's a `ClearClipboardFrequency` setting and corresponding logic to auto-clear clipboard contents after copying passwords/TOTP codes. The `clipboard` package under `platform/manager` handles this.

---

## 7. UI Layer

### 100% Jetpack Compose

No XML layouts anywhere in the app or library modules. The entire UI is Jetpack Compose. `AppCompatActivity` is still used as the base (for AppCompat theme support and locale management) but all rendering is via `setContent { }`.

### Design System Architecture

The design system lives in the `:ui` module under `com.bitwarden.ui.platform.theme/`.

**Custom Theme Object:** `BitwardenTheme` is a singleton object exposing three properties: `colorScheme`, `shapes`, `typography`. These are accessed via `CompositionLocal` providers set up by the `BitwardenTheme` composable.

**Color System (`BitwardenColorScheme`):** A fully custom color system that does NOT simply extend Material3's color tokens. It defines semantic color categories: `text`, `background`, `stroke`, `icon`, `filledButton`, `outlineButton`, `toggleButton`, `sliderButton`, `status`, `illustration` — each with multiple semantic sub-tokens (primary, secondary, interaction, reversed, etc.). This maps to/from Material3's `ColorScheme` via adapter functions.

**Dark Mode:** Three modes supported — System Default, Light, Dark. The `AppTheme` enum has `isDarkMode(isSystemDarkMode)` logic. Dynamic color (Material You) is also supported on API 31+ via `dynamicBitwardenColorScheme()`.

**Typography (`BitwardenTypography`):** Custom typography system built on top of Material3, mapping to/from `MaterialTheme.typography`.

**Shapes (`BitwardenShapes`):** Custom shape system.

**Component Library:** Extensive — 30+ component categories under `ui/src/.../platform/components/`:
- `appbar` — Top app bars
- `button` — `BitwardenFilledButton`, `BitwardenOutlinedButton`, `BitwardenTextButton`, icon variants
- `card` — Cards
- `dialog` — Loading dialogs, confirmation dialogs
- `dropdown` — Dropdowns
- `fab` — Floating action buttons
- `field` — Text fields with incognito input support
- `navigation` — `BitwardenBottomAppBar`, `BitwardenNavigationRail`
- `scaffold` — `BitwardenScaffold` wrapper
- `toggle` — Switches
- `tooltip` — Tooltips
- `coachmark` — User onboarding coach marks
- ... and many more

All components are prefixed with `Bitwarden` to distinguish from standard Compose/Material3 components.

### CompositionLocal for Manager Injection

UI-layer managers (biometrics, permissions, NFC, exit, intents, review) are not DI-injectable into ViewModels because they need `Activity` context. Instead, they're provided via `CompositionLocal` set up in `LocalManagerProvider`, a wrapper composable instantiated in `MainActivity`. This cleanly separates activity-dependent UI managers from the DI graph.

`CompositionLocal` keys include: `LocalBiometricsManager`, `LocalPermissionsManager`, `LocalNfcManager`, `LocalExitManager`, `LocalIntentManager`, `LocalClock`, `LocalAuthTabLaunchers`, `LocalFeatureFlagsState`, `LocalCredentialProviderCompletionManager`, `LocalAppReviewManager`, `LocalQrCodeAnalyzer`.

### Edge-to-Edge and Theme Lifecycle

`setupEdgeToEdge(appThemeFlow)` configures edge-to-edge display reactively — when the theme changes (e.g., user toggles light/dark in Settings), the edge-to-edge configuration is updated without recreating the Activity. `AppCompatDelegate.setDefaultNightMode` is called for AppCompat theme alignment. The app correctly handles in-process theme changes via Compose recomposition — no Activity recreation needed for theme changes.

### Adaptive Layout Support

`androidx.compose.material3.adaptive` is included, indicating tablet/foldable responsive layout support. `BitwardenNavigationRail` exists alongside `BitwardenBottomAppBar` for responsive nav layout switching.

### Glide for Image Loading

`bumptech.glide` is used for loading cipher icons (favicon-like icons for login items). A custom `BitwardenAppGlideModule` is configured and there's a `GlideCookieInterceptor` for passing auth cookies when fetching icons from authenticated endpoints.

---

## 8. Background Work

### WorkManager

`androidx.work.runtime.ktx` is in the dependency list. The exact WorkManager tasks were not directly inventoried but it's used for "deferrable, asynchronous tasks that must be run reliably" per the README.

### Firebase Cloud Messaging (Push Notifications)

`BitwardenFirebaseMessagingService` in `src/standard/` source set handles FCM push notifications. This is completely absent in the `fdroid` flavor — F-Droid builds have no push capability. Push management goes through `PushManager` / `PushManagerImpl`, `PushDiskSource`, and `PushService`.

Push notifications drive real-time vault sync (rather than polling). When a sync notification arrives, the vault is refreshed via `VaultSyncManager`.

### Vault Sync

`VaultSyncManager` (546 lines) coordinates vault data synchronization. It pulls data from `SyncService` (a single endpoint that returns the full vault state), decrypts via the SDK, and persists to Room. Sync can be triggered by push notifications, pull-to-refresh, or on vault unlock.

### Quick Settings Tiles

Three `TileService` implementations: `BitwardenAutofillTileService`, `BitwardenGeneratorTileService`, `BitwardenVaultTileService`. These are the legacy Xamarin service names kept for backward compatibility (the manifest uses the old class names to ensure the app is recognized as providing tile services).

### Accessibility Service

`BitwardenAccessibilityService` (referenced by legacy name in manifest) provides accessibility-based autofill as a fallback when the standard autofill framework isn't available. Configured to receive `typeWindowStateChanged` events with `flagReportViewIds|flagRetrieveInteractiveWindows`.

### Autofill Service

`BitwardenAutofillService` implements `AutofillService`. Configuration in `autofill_service_configuration.xml`.

### Credential Provider Service

`BitwardenCredentialProviderService` implements the Credential Manager `CredentialProviderService`. Handles passkey creation, passkey assertion, and password retrieval via the Android Credential Manager API.

### Organization Event Logging

`OrganizationEventManager` queues and dispatches organization audit events (e.g., cipher accessed, cipher modified). Events are stored in Room (`OrganizationEventEntity`) and batched for upload to `EventService`.

### AuthenticatorBridge IPC Service

`AuthenticatorBridgeService` is a bound service that allows the companion Bitwarden Authenticator app to securely access TOTP codes from the main app. Protected by the custom `AUTHENTICATOR_BRIDGE_SERVICE` permission with `signature|knownSigner` protection level using known Bitwarden Authenticator certificate hashes.

---

## 9. Testing

### Framework Stack

- **JUnit 5** (Jupiter + Vintage) — Unit test framework. `useJUnitPlatform()` configured in root build for all subprojects.
- **MockK** — Kotlin-idiomatic mocking library. Used universally instead of Mockito.
- **Turbine** — CashApp's Flow testing library. Used in virtually every ViewModel test via `turbineScope`, `testIn(backgroundScope)`.
- **Robolectric** — Android framework simulation for JVM tests. Allows testing Compose screens and Activity-dependent code without an emulator.
- **Compose Testing** — `createComposeRule()` used via `BaseComposeTest` for composable testing.
- **Hilt Testing** — `@HiltAndroidTest` for integration-style DI tests when needed.
- **Kotlin Coroutines Test** — `UnconfinedTestDispatcher`, `TestCoroutineScheduler`.

### Test Base Classes (in `:ui`'s testFixtures)

- `BaseViewModelTest` — Registers `MainDispatcherExtension` (JUnit 5 extension that replaces `Dispatchers.Main` with `UnconfinedTestDispatcher`). Provides the `stateEventFlow { }` suspend helper using `turbineScope`.
- `BaseComposeTest extends BaseRobolectricTest` — Sets up `createComposeRule(effectContext = dispatcher)` with an `UnconfinedTestDispatcher`. Provides `setTestContent` helper that also captures the `OnBackPressedDispatcher`.
- `BaseRobolectricTest` — Base for Robolectric-based tests.
- `MockNavHostController` — A fake `NavHostController` for testing navigation calls in composable tests.

### ViewModel Testing Pattern

ViewModel tests directly instantiate the ViewModel with MockK mocks for all dependencies. The `MainDispatcherExtension` handles coroutine test setup. Tests use `turbineScope` to collect both `stateFlow` and `eventFlow` simultaneously, asserting on state transitions and events in order.

Internal actions (from async results) are tested by setting up mock behavior to produce a value, triggering a user action, then using Turbine's `awaitItem()` to observe the resulting state update.

### Compose Testing Pattern

Compose tests extend `BaseComposeTest`. The ViewModel is typically created as a real instance with MockK mocks injected, or sometimes as a manual `hiltViewModel()` in integration tests. Navigation callbacks are passed as lambdas and tracked via captured invocations. `composeTestRule.onNode(...)` assertions follow standard Compose testing patterns with semantic matchers.

### Test Fixtures

Each library module has a `src/testFixtures/` source set providing shared fakes, fake flows, and test data builders. These are published as `testFixtures(project(":module"))` and consumed by test dependencies in `:app` and other modules. This avoids duplication of test infrastructure.

### Coverage Tooling

Kover (Kotlin Coverage) is used. Coverage is merged across all modules in a `mergedCoverage` variant. Coverage excludes are explicitly configured:
- Compose `@Preview` annotated functions
- `@OmitFromCoverage` annotated classes (for Android system callbacks, etc.)
- `@Module` Hilt modules (DI plumbing)
- Navigation files (`*.*Navigation.kt`)
- Database/DAO generated classes
- All `*.di` packages
- Model data classes
- Platform UI components and themes

### Test Parallelism

Root build configures `maxParallelForks = availableProcessors / 2`, `forkEvery = 500`, `maxHeapSize = "2g"`, JVM using parallel GC. Tests are explicitly configured for `en-US` locale.

### CI Coverage Integration

SonarCloud (`sonarcloud.io`) is configured for code quality gate analysis. Coverage reports are generated by Kover before being uploaded to Sonar. The `koverXmlReportMergedCoverage` task runs before the `sonar` task.

---

## 10. Build System

### Gradle Setup

- Gradle 9.1.x with Kotlin DSL throughout (`.kts` everywhere)
- Version Catalog (`gradle/libs.versions.toml`) for all dependency versions
- JDK 21 required
- `dependencyResolutionManagement` with `FAIL_ON_PROJECT_REPOS` — only root-level repositories allowed
- Custom GitHub Packages Maven repository for the Bitwarden Rust SDK artifacts (requires `gitHubToken` in `user.properties`)
- `localSdk` user property for development against a local SDK build (substitutes via `dependencySubstitution`)

### Build Variants

4 build types x 2 flavors = 8 variant combinations:
- `standardDebug` — Dev build for Play Store variant; `.dev` suffix; debug menu; verbose logs; no minification
- `standardRelease` — Production Play Store build; minified; ProGuard; Firebase; crash reporting
- `standardBeta` — Beta Play Store build; `.beta` suffix; minified; no debug menu
- `fdroidDebug`, `fdroidRelease`, `fdroidBeta` — F-Droid equivalents without any Google Play Services

### Signing

Debug keystore in `keystores/debug.keystore` (standard Android debug key). Release signing credentials are injected via `user.properties` / CI properties files that are NOT in source control. CI properties file (`ci.properties`) provides build metadata like `ci.info` injected as a `BuildConfig` field.

### Artifact Naming

Custom `androidComponents.onVariants` block renames APK and AAB outputs to `com.x8bit.bitwarden.apk` (standard) or `com.x8bit.bitwarden-fdroid.apk` (F-Droid). The AAB is similarly renamed for Play Console upload.

### Room Schema Export

Room schema JSON files are exported to `app/schemas/` and checked into source control. This creates a migration history and enables `AutoMigration` declarations without writing explicit SQL.

### CI/CD

Fastlane is used for build automation. Lanes for: assembling debug APKs, assembling F-Droid APKs/betas, assembling Play Store APKs/betas/releases, bundling Play Store releases, distributing to Firebase App Distribution, promoting between Play Store tracks (alpha → beta → production).

Signing credentials, keystore paths/passwords/aliases are passed as lane parameters — the Fastfile itself contains no secrets.

Script tooling: `update-sdk-version.sh` for automating SDK version bumps; `release-notes.sh` for generating changelogs; `download-artifacts.sh` for CI artifact retrieval.

### Module Compilation Optimization

`tasks.withType<JavaCompile>().configureEach { options.isFork = true }` — forks Java compilation to a separate JVM for each module, improving parallel build performance.

---

## 11. Code Quality

### Detekt Static Analysis

Detekt 1.23.8 configured with `detekt-config.yml`. Key settings:
- `maxIssues: 0` — zero-tolerance policy, any issue fails the build
- `autoCorrect: true` — auto-formats violations where possible
- `UndocumentedPublicClass: active: true` — all public classes must have KDoc
- `UndocumentedPublicFunction: active: true` — all public functions must have KDoc (ignores Hilt/Retrofit annotations)
- `CyclomaticComplexMethod` threshold: 15 (with `ignoreSingleWhenExpression: true`)
- `LargeClass` threshold: 600 lines
- `LongMethod` threshold: 60 lines (excluded from tests)
- `LongParameterList`: active (tests excluded)
- `MagicNumber` rules active (excluding tests)
- Comment rules exclude test files and `*ServiceImpl.kt` files

### Pre-Commit Hook

An optional but documented pre-commit hook runs `./gradlew -Pprecommit=true detekt` on staged files only (using `git diff --name-only --cached`). This gives fast local feedback before push.

### Code Style

A custom `bitwarden-style.xml` IntelliJ/Android Studio code style scheme is checked into `docs/`. The `STYLE_AND_BEST_PRACTICES.md` document codifies:
- 100-character line limit
- Region markers (`//region Name ... //endregion Name`) for method groups in large classes
- Expression functions preferred when naturally concise
- Type inference preferred when unambiguous
- Lambda parameters over single-method interfaces
- No exception throwing in public APIs — return `Result<T>` or domain-specific sealed classes
- Specific ordering rules for method declarations (overrides first, then grouped by modifier)
- Nested classes go AFTER the companion object
- `@Suppress` annotations allowed but must have a comment explaining why

### No Exceptions in Public APIs

The no-exceptions rule is strictly enforced across the data layer. Network calls, SDK calls, and disk operations that could fail must return `Result<T>` at the data source level and domain-specific sealed error types at the repository level. The `@Throws` annotation is not used on public interfaces.

---

## 12. Accessibility

### Accessibility Service Architecture

The app implements `AccessibilityService` as a fallback autofill mechanism. The service config (`accessibility_service.xml`) requests `typeWindowStateChanged` events with `flagReportViewIds|flagRetrieveInteractiveWindows`. The service's goal is to detect when a login form is visible and offer to fill it even when the standard autofill framework isn't available.

### Compose Semantic Tree

All Bitwarden-prefixed components in the `:ui` module follow Compose accessibility best practices. The extensive custom component library means semantic labels, roles, and states are applied centrally — when a component is correct for accessibility, all screens using it get the benefit automatically.

### Content Descriptions and Semantics

The `ui/src/.../components` library includes semantic modifier usage throughout. For example, navigation bar items, icon buttons, and interactive elements have semantic content descriptions applied at the component level.

### Accessibility Tool Declaration

The `accessibility_service.xml` includes `android:isAccessibilityTool="false"` — explicitly declaring this service is NOT an accessibility assistive technology (it's a security/autofill feature). This prevents it from appearing in accessibility settings as an assistive tool.

### Touch Event Filtering

`filterTouchesWhenObscured = true` is technically also an accessibility concern — it ensures that screen readers and overlay-based accessibility services cannot intercept sensitive input.

---

## 13. Key Patterns to Emulate

Below is a prioritized list of patterns from Bitwarden that are directly applicable to Dispatch.

### Tier 1: Critical Patterns (Adopt Immediately)

**1. BaseViewModel with State/Action/Event trinity**
This is the foundation of everything. A single, immutable State class, sealed Action and Event hierarchies, and the rule that state only changes synchronously inside `handleAction`. The `Channel<A>` queue for actions is crucial — it serializes all state mutations. Turbine makes testing trivial.

**2. Interface + Impl for all data layer classes**
Every Repository, Manager, DiskSource, and NetworkService has an interface. The `@Singleton` annotation goes on the `@Provides` function binding the interface. The Impl is constructed manually by Hilt but never injected directly. Tests mock the interface. This costs ~5 minutes to set up per class and saves enormous testing pain.

**3. No exceptions in public APIs**
`Result<T>` at the data source level, sealed domain error types at the repository level. No `@Throws`. This single rule eliminates entire classes of bugs.

**4. Navigation files pattern** (`...Route`, `NavGraphBuilder.xDestination()`, `NavController.navigateToX()`, `...Args` from `SavedStateHandle.toRoute<T>()`)
All navigation contract in one file, zero String routes escaping into the rest of the app.

**5. Two-tier navigation** (state-based at root via ViewModel, event-based within flows)
The RootNavViewModel pattern — monitoring auth state and special circumstances to determine the top-level route — means deep links, logouts, locks, and account switches Just Work without the UI needing to explicitly route.

### Tier 2: Important Patterns (Adopt in Next Sprint)

**6. `SpecialCircumstance` pattern for intent routing**
Rather than parsing intents in Activities and imperatively navigating, parse intents into a sealed `SpecialCircumstance` model, store it in a manager (a `MutableStateFlow`), and let the RootNavViewModel react to it. This makes intent handling fully unit-testable.

**7. `DataState<T>` / `LocalDataState<T>` wrapper**
A sealed Loading/Loaded/Error wrapper for repository-level StateFlows. Avoids the temptation to use `null` as the loading sentinel or to emit empty lists before data loads. The UI can pattern-match on Loading to show skeletons, Loaded to show data, Error to show error states.

**8. CompositionLocal for activity-dependent managers**
`LocalManagerProvider` composable pattern: create activity-dependent objects (BiometricsManager, PermissionsManager, etc.) at the Activity level and propagate them down via CompositionLocal. ViewModels stay pure (no Activity/Context dependencies). The composition local with a `error(...)` default crashes clearly if a consumer forgets to provide it.

**9. Custom semantic color system**
Don't just use Material3 color tokens. Define a `BitwardenColorScheme`-equivalent with semantic names: `text.primary`, `text.secondary`, `background.primary`, `background.secondary`, `stroke.divider`, `icon.primary` etc. This makes themeing self-documenting and prevents "which shade of gray was it?" confusion.

**10. Test base classes with Turbine**
`BaseViewModelTest.stateEventFlow { stateFlow, eventFlow -> ... }` makes ViewModel testing one-liner level. The `BaseComposeTest` with `MockNavHostController` makes Compose screen testing equally straightforward. These test fixtures should live in `:core` or `:ui`'s testFixtures.

### Tier 3: Aspirational Patterns (Adopt at Scale)

**11. Multi-module architecture with flavor-based feature separation**
When you have genuinely different distribution channels (open source vs. proprietary, different feature sets), the `standard/fdroid` product flavor separation is clean. Firebase/Google services in `standard`, absent in `fdroid`. Each module also separates concerns clearly — `:network` has no UI, `:ui` has no data sources.

**12. KDoc on every public class and function**
Detekt enforces this with `UndocumentedPublicClass` and `UndocumentedPublicFunction`. It feels like overhead but at scale it pays dividends in IDE tooltips, auto-generated docs, and reviewer onboarding.

**13. `OmitFromCoverage` annotation**
Rather than fighting Kover to exclude specific classes via regex patterns, define a `@OmitFromCoverage` annotation and tell coverage tooling to exclude anything annotated with it. Used for: Activities (framework integration points, tested via Robolectric), `@HiltAndroidApp` Application class, and other boilerplate. Forces developers to make a conscious decision to opt out.

**14. Pre-commit Detekt hook**
The git pre-commit hook running Detekt on staged files gives sub-5-second linting feedback before pushing. Zero configuration for each developer once the repo hook is installed.

**15. Kover + SonarCloud for coverage visibility**
Coverage merged across all modules, filtered to exclude generated code, DI modules, model classes, and preview functions. Reported to SonarCloud as quality gate. This creates organizational accountability for test coverage without forcing meaningless coverage on inherently untestable boilerplate.

---

## Appendix: Key Files for Reference

| Pattern | File Location |
|---|---|
| BaseViewModel | `/ui/src/main/.../platform/base/BaseViewModel.kt` |
| EventsEffect | `/ui/src/main/.../platform/base/util/EventsEffect.kt` |
| BitwardenTheme | `/ui/src/main/.../platform/theme/BitwardenTheme.kt` |
| BitwardenColorScheme | `/ui/src/main/.../platform/theme/color/BitwardenColorScheme.kt` |
| RootNavScreen | `/app/src/main/.../ui/platform/feature/rootnav/RootNavScreen.kt` |
| RootNavViewModel | `/app/src/main/.../ui/platform/feature/rootnav/RootNavViewModel.kt` |
| LocalManagerProvider | `/app/src/main/.../ui/platform/composition/LocalManagerProvider.kt` |
| PreferenceModule | `/data/src/main/.../datasource/disk/di/PreferenceModule.kt` |
| AuthTokenManager | `/network/src/main/.../network/interceptor/AuthTokenManager.kt` |
| PlatformNetworkModule | `/app/src/main/.../data/platform/datasource/network/di/PlatformNetworkModule.kt` |
| VaultDatabase | `/app/src/main/.../data/vault/datasource/disk/database/VaultDatabase.kt` |
| BiometricsEncryptionManagerImpl | `/app/src/main/.../data/platform/manager/BiometricsEncryptionManagerImpl.kt` |
| VaultLockManagerImpl | `/app/src/main/.../data/vault/manager/VaultLockManagerImpl.kt` |
| BaseViewModelTest | `/ui/src/testFixtures/.../platform/base/BaseViewModelTest.kt` |
| BaseComposeTest | `/ui/src/testFixtures/.../platform/base/BaseComposeTest.kt` |
| IncognitoInput | `/ui/src/main/.../platform/components/field/interceptor/NoPersonalizedLearningInterceptor.kt` |
| ARCHITECTURE.md | `/docs/ARCHITECTURE.md` |
| STYLE_AND_BEST_PRACTICES.md | `/docs/STYLE_AND_BEST_PRACTICES.md` |
