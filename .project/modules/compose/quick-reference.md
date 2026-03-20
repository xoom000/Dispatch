# Jetpack Compose Material3 — Quick Reference

**What it is:** Jetpack Compose is Android's modern declarative UI toolkit. Material3 (Material You) is the design system layer built on top of Compose, providing theming, color roles, motion, and pre-built components aligned with the Material Design 3 spec.

**Context7 library IDs:**
- Components & guides: `/websites/developer_android_develop_ui_compose_components`
- API reference: `/websites/developer_android_reference_kotlin_androidx_compose_material3`

**BOM version used in this doc:** `2026.01.01`

---

## 1. Gradle Dependencies (using BOM)

The BOM pins all Compose library versions — you do not specify individual version numbers.

```kotlin
// build.gradle.kts (app module)

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.01.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Material3
    implementation("androidx.compose.material3:material3")

    // Core UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Material Icons (extended set is large — import selectively in production)
    implementation("androidx.compose.material:material-icons-core")
    // implementation("androidx.compose.material:material-icons-extended")

    // Activity integration
    implementation("androidx.activity:activity-compose:1.10.0")

    // Debug / tooling
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
```

```kotlin
// build.gradle.kts (project-level) — ensure you have the Compose plugin
plugins {
    id("com.android.application") version "8.9.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
```

---

## 2. Scaffold with TopAppBar

`Scaffold` provides standard layout slots (topBar, bottomBar, FAB, snackbar host). **Always apply `innerPadding` to the content** — see gotchas section.

```kotlin
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyScreen(onNavigateUp: () -> Unit) {
    var pressCount by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Screen") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Navigate up")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                ),
                actions = {
                    IconButton(onClick = { /* menu action */ }) {
                        Icon(Icons.Default.Add, contentDescription = "Add item")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    text = "Bottom bar"
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { pressCount++ }) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        },
        snackbarHost = { SnackbarHost(it) }
    ) { innerPadding ->
        // innerPadding accounts for top/bottom bars — must be applied
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("FAB pressed $pressCount times")
        }
    }
}
```

---

## 3. LazyColumn with Items

```kotlin
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class ListItem(val id: Int, val label: String)

@Composable
fun ItemList(items: List<ListItem>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Fixed header (not recycled)
        item {
            Text(
                text = "Header",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // items — use key= for stable identity; prevents unnecessary recompositions
        items(items = items, key = { it.id }) { item ->
            ListItemRow(item = item)
        }

        // itemsIndexed when you need the index
        // itemsIndexed(items, key = { _, item -> item.id }) { index, item -> ... }
    }
}

@Composable
private fun ListItemRow(item: ListItem) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = item.label,
            modifier = Modifier.padding(16.dp)
        )
    }
}
```

---

## 4. Material3 Theming

### 4a. App Theme Entry Point

```kotlin
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Define custom light and dark color schemes
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6650A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    // ... additional roles as needed
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color uses wallpaper colors on Android 12+ (API 31+)
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,   // define in a separate Typography.kt
        content = content
    )
}
```

### 4b. Using Theme Colors and Typography

```kotlin
@Composable
fun ThemedComponent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Text(
            text = "Headline",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
```

---

## 5. State Management

### remember vs rememberSaveable

| | `remember` | `rememberSaveable` |
|---|---|---|
| Survives recomposition | Yes | Yes |
| Survives config change / process death | No | Yes (if value is saveable) |
| Custom saver needed | No | Only for non-primitive types |

```kotlin
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
fun StateExamples() {
    // remember — lost on rotation
    var count by remember { mutableIntStateOf(0) }

    // rememberSaveable — survives rotation (Int is automatically saved)
    var savedCount by rememberSaveable { mutableIntStateOf(0) }

    // State object (unpack with by delegation or .value)
    val text = rememberSaveable { mutableStateOf("") }

    // List — wrap in snapshot-aware SnapshotStateList
    val items = remember { mutableStateListOf<String>() }

    // Map
    val map = remember { mutableStateMapOf<String, Int>() }

    // Derived state — only recomposes dependents when result actually changes
    val isValid by remember { derivedStateOf { text.value.length >= 3 } }

    Column {
        Button(onClick = { count++ }) { Text("Count: $count") }
        Button(onClick = { savedCount++ }) { Text("Saved: $savedCount") }
        OutlinedTextField(
            value = text.value,
            onValueChange = { text.value = it },
            label = { Text("Name") }
        )
        if (!isValid) Text("Name must be at least 3 characters")
    }
}
```

### rememberSaveable with a Custom Saver

```kotlin
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

data class User(val id: Int, val name: String)

val UserSaver = Saver<User, List<Any>>(
    save = { listOf(it.id, it.name) },
    restore = { User(it[0] as Int, it[1] as String) }
)

@Composable
fun CustomSaverExample() {
    var user by rememberSaveable(stateSaver = UserSaver) {
        mutableStateOf(User(1, "Alice"))
    }
}
```

---

## 6. Common Components

### Button

```kotlin
import androidx.compose.material3.*

@Composable
fun ButtonExamples() {
    // Filled (primary CTA)
    Button(onClick = { /* action */ }) {
        Text("Confirm")
    }

    // Outlined
    OutlinedButton(onClick = { /* action */ }) {
        Text("Cancel")
    }

    // Text button (low emphasis)
    TextButton(onClick = { /* action */ }) {
        Text("Learn more")
    }

    // Filled Tonal (secondary CTA)
    FilledTonalButton(onClick = { /* action */ }) {
        Text("Save Draft")
    }

    // Elevated
    ElevatedButton(onClick = { /* action */ }) {
        Text("Share")
    }

    // Disabled state — same across all variants
    Button(onClick = {}, enabled = false) {
        Text("Disabled")
    }
}
```

### TextField

```kotlin
import androidx.compose.material3.*
import androidx.compose.runtime.*

@Composable
fun TextFieldExamples() {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    // Filled variant (default)
    TextField(
        value = text,
        onValueChange = {
            text = it
            error = it.isBlank()
        },
        label = { Text("Email") },
        placeholder = { Text("user@example.com") },
        isError = error,
        supportingText = {
            if (error) Text("Required field", color = MaterialTheme.colorScheme.error)
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(Modifier.height(8.dp))

    // Outlined variant
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Password") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
}
```

### Card

```kotlin
import androidx.compose.material3.*

@Composable
fun CardExamples() {
    // Filled card (default)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Card Title", style = MaterialTheme.typography.titleMedium)
            Text("Card body text.", style = MaterialTheme.typography.bodyMedium)
        }
    }

    // Elevated card
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(16.dp)
    ) {
        Text("Elevated Card", modifier = Modifier.padding(16.dp))
    }

    // Outlined card
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(16.dp)
    ) {
        Text("Outlined Card", modifier = Modifier.padding(16.dp))
    }

    // Clickable card
    Card(
        onClick = { /* handle click */ },
        modifier = Modifier.fillMaxWidth().padding(16.dp)
    ) {
        Text("Clickable Card", modifier = Modifier.padding(16.dp))
    }
}
```

### Surface

```kotlin
import androidx.compose.material3.*

@Composable
fun SurfaceExample() {
    // Surface provides color, shape, elevation, and content color
    Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp,
        shadowElevation = 2.dp
    ) {
        Text(
            text = "Surface content",
            modifier = Modifier.padding(16.dp),
            // Color automatically set to onSurfaceVariant by Surface
        )
    }

    // Clickable surface
    Surface(
        onClick = { /* handle click */ },
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = MaterialTheme.shapes.small,
    ) {
        Text("Clickable Surface", modifier = Modifier.padding(16.dp))
    }
}
```

### ModalBottomSheet

```kotlin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetExample() {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false  // allows partially expanded state
    )
    val scope = rememberCoroutineScope()
    var showSheet by remember { mutableStateOf(false) }

    Button(onClick = { showSheet = true }) {
        Text("Open Sheet")
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState
        ) {
            // Sheet content — add bottom padding for nav bar insets
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .navigationBarsPadding()
            ) {
                Text("Sheet Title", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) showSheet = false
                        }
                    }
                ) {
                    Text("Dismiss")
                }
            }
        }
    }
}
```

### FloatingActionButton

```kotlin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*

@Composable
fun FABExamples() {
    // Standard FAB
    FloatingActionButton(onClick = { /* action */ }) {
        Icon(Icons.Default.Add, contentDescription = "Add")
    }

    // Small FAB
    SmallFloatingActionButton(onClick = { /* action */ }) {
        Icon(Icons.Default.Add, contentDescription = "Add")
    }

    // Large FAB
    LargeFloatingActionButton(onClick = { /* action */ }) {
        Icon(Icons.Default.Add, contentDescription = "Add")
    }

    // Extended FAB with label (expands/collapses based on scroll)
    var expanded by remember { mutableStateOf(true) }
    ExtendedFloatingActionButton(
        onClick = { /* action */ },
        expanded = expanded,
        icon = { Icon(Icons.Default.Edit, contentDescription = "Edit") },
        text = { Text("Compose") }
    )
}
```

### IconButton

```kotlin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*

@Composable
fun IconButtonExamples() {
    // Standard icon button
    IconButton(onClick = { /* action */ }) {
        Icon(Icons.Default.Favorite, contentDescription = "Like")
    }

    // Toggle icon button
    var checked by remember { mutableStateOf(false) }
    IconToggleButton(
        checked = checked,
        onCheckedChange = { checked = it }
    ) {
        if (checked) {
            Icon(Icons.Filled.Favorite, contentDescription = "Remove from favorites")
        } else {
            Icon(Icons.Outlined.FavoriteBorder, contentDescription = "Add to favorites")
        }
    }

    // Filled icon button (prominent)
    FilledIconButton(onClick = { /* action */ }) {
        Icon(Icons.Default.Favorite, contentDescription = "Like")
    }

    // Outlined icon button
    OutlinedIconButton(onClick = { /* action */ }) {
        Icon(Icons.Default.Favorite, contentDescription = "Like")
    }
}
```

---

## 7. Modifiers Cheat Sheet

Modifiers are applied in order — order matters (especially for padding vs background).

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// padding — space inside or outside the composable
Modifier.padding(16.dp)                          // all sides
Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
Modifier.padding(start = 8.dp, top = 4.dp)

// size
Modifier.fillMaxWidth()                          // match parent width
Modifier.fillMaxHeight()
Modifier.fillMaxSize()                           // both axes
Modifier.width(200.dp)
Modifier.height(56.dp)
Modifier.size(48.dp)                             // width = height
Modifier.widthIn(min = 100.dp, max = 300.dp)    // constrained

// clip — clips drawing and touch to a shape (apply BEFORE background)
Modifier.clip(RoundedCornerShape(8.dp))
Modifier.clip(CircleShape)
Modifier.clip(MaterialTheme.shapes.medium)

// background — fill color (apply AFTER clip for correct shape)
Modifier.background(Color.Blue)
Modifier.background(MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp))

// clickable
Modifier.clickable { /* handle click */ }
Modifier.clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,  // suppress ripple
    onClick = { /* handle click */ }
)

// weight — distribute space in Row/Column (like Android's layout_weight)
// only valid inside RowScope or ColumnScope
Row {
    Box(Modifier.weight(1f))  // takes 1 share of remaining space
    Box(Modifier.weight(2f))  // takes 2 shares of remaining space
}

// offset — visual shift without affecting layout
Modifier.offset(x = 8.dp, y = 0.dp)

// shadow and z-index
Modifier.shadow(elevation = 4.dp, shape = RoundedCornerShape(8.dp))
Modifier.zIndex(1f)

// alpha / visibility
Modifier.alpha(0.5f)

// semantics (accessibility)
Modifier.semantics { contentDescription = "Profile picture" }

// combined example — order: clip → background → padding → clickable
Modifier
    .clip(RoundedCornerShape(12.dp))
    .background(MaterialTheme.colorScheme.surface)
    .padding(16.dp)
    .clickable { /* action */ }
```

---

## 8. Key Gotchas

### 8a. Always Apply innerPadding from Scaffold

`Scaffold` passes a `PaddingValues` lambda parameter (`innerPadding`) to its content block. If you do not apply it, your content will be obscured by the top/bottom bars.

```kotlin
// WRONG — content hidden behind TopAppBar
Scaffold(...) { _ ->
    LazyColumn { ... }
}

// CORRECT — apply innerPadding
Scaffold(...) { innerPadding ->
    LazyColumn(
        contentPadding = innerPadding  // for LazyColumn/LazyRow
    ) { ... }
}

// CORRECT — for Column or Box
Scaffold(...) { innerPadding ->
    Column(modifier = Modifier.padding(innerPadding)) { ... }
}
```

### 8b. ExperimentalMaterial3Api Opt-In

Several Material3 APIs are still annotated `@ExperimentalMaterial3Api` and require explicit opt-in. Without it, the code will not compile.

```kotlin
// Option 1: per-composable (preferred for surgical scope)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyScreen() {
    TopAppBar(...)
    ModalBottomSheet(...)
}

// Option 2: per-file — add at the top of the file, before package declaration
@file:OptIn(ExperimentalMaterial3Api::class)

// Option 3: module-wide — build.gradle.kts (use sparingly)
kotlinOptions {
    freeCompilerArgs += "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
}
```

Common APIs that require opt-in (as of BOM 2026.01.01):
- `TopAppBar`, `CenterAlignedTopAppBar`, `LargeTopAppBar`, `MediumTopAppBar`
- `ModalBottomSheet`, `rememberModalBottomSheetState`
- `ExposedDropdownMenuBox`
- `TooltipBox`, `rememberTooltipState`

### 8c. Recomposition Traps

**Lambda captures and unstable types** cause unnecessary recompositions. Use `remember` and stable types to minimize them.

```kotlin
// TRAP — new lambda allocated every recomposition, forces child recomposition
@Composable
fun Parent(items: List<Item>) {
    items.forEach { item ->
        ChildItem(
            item = item,
            onClick = { handleClick(item) }  // new lambda every time
        )
    }
}

// BETTER — key the composable so lambdas are stable per item
@Composable
fun Parent(items: List<Item>) {
    items.forEach { item ->
        key(item.id) {
            ChildItem(
                item = item,
                onClick = { handleClick(item) }
            )
        }
    }
}
```

**Reading state outside composition** causes the wrong scope to recompose:

```kotlin
// TRAP — reads scrollOffset in composition scope; entire composable recomposes on scroll
@Composable
fun BadExample(scrollState: ScrollState) {
    val offset = scrollState.value  // read here = recompose entire function on scroll
    Box(Modifier.offset(y = (-offset).dp)) { ... }
}

// BETTER — defer read into lambda; only the Box's draw pass re-executes
@Composable
fun BetterExample(scrollState: ScrollState) {
    Box(Modifier.graphicsLayer { translationY = -scrollState.value.toFloat() }) { ... }
}
```

**Using unstable collections** (plain `List`, `Map`) prevents skipping:

```kotlin
// TRAP — List<T> is unstable; Compose cannot skip this composable
@Composable
fun ItemList(items: List<String>) { ... }

// BETTER — use @Immutable or ImmutableList from kotlinx.collections.immutable
@Immutable
data class ItemListState(val items: List<String>)

@Composable
fun ItemList(state: ItemListState) { ... }
```

**`derivedStateOf` for computed values** — avoids recomputing on every recomposition:

```kotlin
// TRAP — recalculates on every recomposition regardless of whether 'items' changed
@Composable
fun Counter(items: List<String>) {
    val count = items.size  // always re-evaluated
}

// CORRECT — only re-evaluates (and triggers recomposition) when items.size actually changes
@Composable
fun Counter(items: List<String>) {
    val count by remember { derivedStateOf { items.size } }
}
```

---

## Quick API Reference

| Component | Import suffix | Experimental? |
|---|---|---|
| `Scaffold` | `material3.*` | No |
| `TopAppBar` | `material3.*` | Yes |
| `BottomAppBar` | `material3.*` | No |
| `FloatingActionButton` | `material3.*` | No |
| `ExtendedFloatingActionButton` | `material3.*` | No |
| `Button` / `OutlinedButton` / `TextButton` | `material3.*` | No |
| `TextField` / `OutlinedTextField` | `material3.*` | No |
| `Card` / `ElevatedCard` / `OutlinedCard` | `material3.*` | No |
| `Surface` | `material3.*` | No |
| `ModalBottomSheet` | `material3.*` | Yes |
| `rememberModalBottomSheetState` | `material3.*` | Yes |
| `IconButton` / `FilledIconButton` | `material3.*` | No |
| `IconToggleButton` | `material3.*` | No |
| `MaterialTheme` | `material3.*` | No |
| `lightColorScheme` / `darkColorScheme` | `material3.*` | No |
| `dynamicLightColorScheme` / `dynamicDarkColorScheme` | `material3.*` | No |
| `LazyColumn` / `LazyRow` | `foundation.lazy.*` | No |
| `remember` / `mutableStateOf` | `runtime.*` | No |
| `rememberSaveable` | `runtime.saveable.*` | No |
