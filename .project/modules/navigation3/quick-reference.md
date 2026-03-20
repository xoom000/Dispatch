# Navigation 3 — Quick Reference for Dispatch

**Source:** Context7 (developer.android.com/guide/navigation/navigation-3)
**Version:** 1.0.0 (stable)
**Date pulled:** 2026-03-20

---

## Key Differences from Navigation Compose (Nav2)

| Concept | Nav2 (Current) | Nav3 |
|---------|----------------|------|
| Host | `NavHost` | `NavDisplay` |
| Controller | `NavController` / `rememberNavController()` | `NavBackStack` / `rememberNavBackStack(startKey)` |
| Routes | `composable<Route> { }` in NavGraphBuilder | `entry<Key> { key -> }` in `entryProvider { }` |
| Type safety | `@Serializable` route data classes | `@Serializable` + `NavKey` interface |
| Back handling | `navController.popBackStack()` | `backStack.removeLastOrNull()` |
| Navigate | `navController.navigate(Route)` | `backStack.add(Key)` |
| Scenes (adaptive) | Separate NavHost per pane | `SceneStrategy` + `ListDetailScene` / `TwoPaneScene` |
| ViewModel scoping | Scoped to NavBackStackEntry | `rememberScoped { }` in entry |

---

## Dependencies (libs.versions.toml)

```toml
[versions]
nav3Core = "1.0.0"
lifecycleViewmodelNav3 = "2.10.0"
kotlinSerialization = "2.2.21"
kotlinxSerializationCore = "1.9.0"
material3AdaptiveNav3 = "1.3.0-alpha06"

[libraries]
androidx-navigation3-runtime = { module = "androidx.navigation3:navigation3-runtime", version.ref = "nav3Core" }
androidx-navigation3-ui = { module = "androidx.navigation3:navigation3-ui", version.ref = "nav3Core" }
androidx-lifecycle-viewmodel-navigation3 = { module = "androidx.lifecycle:lifecycle-viewmodel-navigation3", version.ref = "lifecycleViewmodelNav3" }
kotlinx-serialization-core = { module = "org.jetbrains.kotlinx:kotlinx-serialization-core", version.ref = "kotlinxSerializationCore" }
androidx-material3-adaptive-navigation3 = { group = "androidx.compose.material3.adaptive", name = "adaptive-navigation3", version.ref = "material3AdaptiveNav3" }

[plugins]
jetbrains-kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlinSerialization" }
```

---

## Core Pattern

### Define Keys (Routes)

```kotlin
@Serializable
data object Home : NavKey

@Serializable
data class Messages(val threadId: String, val department: String) : NavKey

@Serializable
data object Settings : NavKey
```

Keys implement `NavKey` (marker interface). Use `data object` for no-arg destinations, `data class` for parameterized.

### Set Up NavDisplay

```kotlin
@Composable
fun DispatchApp() {
    val backStack = rememberNavBackStack(Home)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Home> {
                HomeScreen(
                    onNavigateToMessages = { threadId, dept ->
                        backStack.add(Messages(threadId, dept))
                    }
                )
            }
            entry<Messages> { key ->
                MessagesScreen(
                    threadId = key.threadId,
                    department = key.department,
                    onBack = { backStack.removeLastOrNull() }
                )
            }
            entry<Settings> {
                SettingsScreen()
            }
        }
    )
}
```

### Navigate

```kotlin
// Add to back stack
backStack.add(Messages("thread123", "engineering"))

// Go back
backStack.removeLastOrNull()

// Avoid duplicates
val key = Messages("thread123", "engineering")
if (!backStack.contains(key)) {
    backStack.add(key)
}
```

---

## Adaptive Layout with Scenes

Nav3's killer feature — list-detail and two-pane layouts are built-in, not hacked on top.

```kotlin
val backStack = rememberNavBackStack(ConversationList)
val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>()

NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    sceneStrategy = listDetailStrategy,
    entryProvider = entryProvider {
        entry<ConversationList>(
            metadata = ListDetailScene.listPane()
        ) {
            // This goes in the list pane on wide screens
        }
        entry<ConversationDetail>(
            metadata = ListDetailScene.detailPane()
        ) { detail ->
            // This goes in the detail pane on wide screens
        }
        entry<Profile> {
            // No metadata = single pane always
        }
    }
)
```

- Window > 600dp: list and detail render side by side
- Window < 600dp: standard stack navigation
- No code changes needed — the SceneStrategy handles it

---

## ViewModel Scoping

```kotlin
entry<Messages> { key ->
    // ViewModel scoped to this entry — survives recomposition, dies on back
    val viewModel = rememberScoped { MessagesViewModel(key.threadId) }
    MessagesScreen(viewModel)
}
```

---

## Migration Path for Dispatch

Current Dispatch uses Nav2 (`NavHost` + `NavController` + `composable<Route>`).
Sprint 2 just added typed `*Navigation.kt` files with `@Serializable` routes.

To migrate to Nav3:
1. Add Nav3 dependencies alongside Nav2
2. Change route classes to implement `NavKey`
3. Replace `NavHost` with `NavDisplay`
4. Replace `rememberNavController()` with `rememberNavBackStack(startKey)`
5. Replace `composable<Route> { }` with `entry<Key> { key -> }`
6. Replace `navController.navigate()` with `backStack.add()`
7. Replace `navController.popBackStack()` with `backStack.removeLastOrNull()`
8. Add `ListDetailSceneStrategy` for adaptive list-detail on tablets
9. Remove Nav2 dependencies

The typed routes from Sprint 2 translate almost 1:1 — just add `: NavKey` to each route class.
