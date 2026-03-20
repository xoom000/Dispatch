# Navigation Compose — Quick Reference

**What:** Type-safe navigation for Jetpack Compose. Manages back stack, arguments, deep links.
**Context7 ID:** `/websites/developer_android_guide` (query: "Navigation Compose NavHost")
**Source:** https://developer.android.com/guide/navigation/design
**Version:** 2.8.4

## Gradle Dependencies

```kotlin
dependencies {
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")  // For hiltViewModel()
}
```

## Type-Safe Routes (2.8+)

```kotlin
// Define routes as serializable objects/data classes
@Serializable
object Home

@Serializable
object Settings

@Serializable
data class Conversation(val sessionId: String)

@Serializable
data class Profile(val userId: String, val name: String)
```

## NavHost Setup

```kotlin
@Composable
fun DispatchNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Home,
        modifier = modifier
    ) {
        composable<Home> {
            HomeScreen(
                onNavigateToConversation = { sessionId ->
                    navController.navigate(Conversation(sessionId))
                },
                onNavigateToSettings = {
                    navController.navigate(Settings)
                }
            )
        }

        composable<Conversation> { backStackEntry ->
            val route: Conversation = backStackEntry.toRoute()
            ConversationScreen(
                sessionId = route.sessionId,
                onBack = { navController.popBackStack() }
            )
        }

        composable<Settings> {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
```

## Navigation Patterns

```kotlin
// Simple navigate
navController.navigate(Conversation("session-123"))

// Navigate with back stack management
navController.navigate(Home) {
    popUpTo<Home> {
        inclusive = true   // Pop Home itself
        saveState = true   // Save state for restoration
    }
    restoreState = true    // Restore previously saved state
    launchSingleTop = true // Don't create duplicate if already on top
}

// Pop back
navController.popBackStack()

// Pop to specific destination
navController.popBackStack<Home>(inclusive = false)
```

## Passing Navigation Events Down

```kotlin
// CORRECT — pass lambdas, not NavController
@Composable
fun ConversationScreen(
    sessionId: String,
    onBack: () -> Unit,
    onNavigateToProfile: (String) -> Unit
) {
    // Use onBack and onNavigateToProfile as click handlers
}

// WRONG — don't pass NavController to child composables
// This causes recomposition issues and tight coupling
```

## Key Gotchas

- **Type-safe routes require serialization plugin** — Routes use `@Serializable`, needs `kotlin("plugin.serialization")`
- **`toRoute<T>()`** — Extract route data from `backStackEntry.toRoute()`. Available in 2.8+
- **Don't navigate during composition** — Navigation calls must be in callbacks (onClick, LaunchedEffect), never during composable body
- **Pass lambdas, not NavController** — Child composables should receive `onNavigate` lambdas, not the NavController directly
- **`launchSingleTop`** — Prevents duplicate destinations on rapid clicks
- **`popUpTo` + `inclusive`** — Use to clear back stack when navigating to root. `inclusive = true` pops the target too
- **Hilt ViewModels in navigation** — Use `hiltViewModel()` inside composable destinations. It's scoped to the NavBackStackEntry
