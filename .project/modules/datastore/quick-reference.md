# DataStore Preferences — Quick Reference

**What:** Modern replacement for SharedPreferences. Async, Flow-based, coroutine-safe. No blocking reads.
**Context7 ID:** `/websites/developer_android_jetpack_androidx` (query: "DataStore Preferences")
**Source:** https://developer.android.com/topic/libraries/architecture/datastore
**Version:** 1.2.1 (note: 1.1.1 in index, updated)

## Gradle Dependencies

```kotlin
dependencies {
    implementation("androidx.datastore:datastore-preferences:1.2.1")
}
```

## Setup

```kotlin
// Create at top level (outside class) for singleton
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
```

## Define Keys

```kotlin
object PreferenceKeys {
    val SERVER_URL = stringPreferencesKey("server_url")
    val API_KEY = stringPreferencesKey("api_key")
    val DARK_MODE = booleanPreferencesKey("dark_mode")
    val FONT_SIZE = intPreferencesKey("font_size")
    val VOLUME = floatPreferencesKey("volume")
    val LAST_SYNC = longPreferencesKey("last_sync")
    val SELECTED_TAGS = stringSetPreferencesKey("selected_tags")
}
```

## Read (Flow-based)

```kotlin
// In ViewModel or Repository
val serverUrl: Flow<String> = context.dataStore.data
    .map { preferences ->
        preferences[PreferenceKeys.SERVER_URL] ?: "https://default.example.com"
    }

// Collect in Compose
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle(initialValue = "")
}
```

## Write

```kotlin
// In ViewModel or Repository
suspend fun updateServerUrl(url: String) {
    context.dataStore.edit { preferences ->
        preferences[PreferenceKeys.SERVER_URL] = url
    }
}

suspend fun clearAll() {
    context.dataStore.edit { it.clear() }
}
```

## Read Once (not Flow)

```kotlin
suspend fun getApiKey(): String? {
    val preferences = context.dataStore.data.first()
    return preferences[PreferenceKeys.API_KEY]
}
```

## Key Gotchas

- **No main thread blocking** — Unlike SharedPreferences, DataStore NEVER blocks. All reads are Flow-based, all writes are suspend
- **Create ONCE at top level** — `by preferencesDataStore()` must be a top-level extension property or in a singleton. Creating multiple instances for the same file WILL corrupt data
- **Type-specific keys** — Use `stringPreferencesKey()`, `intPreferencesKey()`, etc. The generic `preferencesKey<T>()` is deprecated
- **Migration from SharedPreferences** — Pass `produceMigrations` parameter: `preferencesDataStore(name = "settings", produceMigrations = { listOf(SharedPreferencesMigration(it, "old_prefs")) })`
- **Error handling** — Wrap `data` flow with `.catch { emit(emptyPreferences()) }` for IOException handling
- **No complex types** — Preferences DataStore only supports primitives and string sets. For complex objects, use Proto DataStore or serialize to JSON string
