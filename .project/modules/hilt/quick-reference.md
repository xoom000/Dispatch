# Hilt (Dagger) — Quick Reference

**What:** Compile-time dependency injection for Android. Generates Dagger setup code, reduces boilerplate.
**Context7 ID:** `/websites/dagger_dev_hilt`
**Source:** https://dagger.dev/hilt/
**Version:** 2.57.1

## Gradle Dependencies

```kotlin
// build.gradle.kts (project)
plugins {
    id("com.google.dagger.hilt.android") version "2.57.1" apply false
}

// build.gradle.kts (app)
plugins {
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation("com.google.dagger:hilt-android:2.57.1")
    ksp("com.google.dagger:hilt-android-compiler:2.57.1")
}
```

## Core Annotations

### @HiltAndroidApp — Application class
```kotlin
@HiltAndroidApp
class DispatchApplication : Application()
```

### @AndroidEntryPoint — Activity/Fragment injection
```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var repository: MessageRepository
}
```

### @HiltViewModel — ViewModel injection
```kotlin
@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val repository: MessageRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel()

// Usage in Compose:
@Composable
fun ConversationScreen(viewModel: ConversationViewModel = hiltViewModel())
```

### @Module + @InstallIn — Provide dependencies
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor())
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://example.com/")
            .client(okHttpClient)
            .addConverterFactory(Json.asConverterFactory("application/json".toMediaType()))
            .build()
    }
}
```

### @Binds — Interface binding
```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindMessageRepository(
        impl: MessageRepositoryImpl
    ): MessageRepository
}
```

## Component Hierarchy (Scopes)

- `SingletonComponent` → `@Singleton` — App lifetime
- `ActivityComponent` → `@ActivityScoped` — Activity lifetime
- `ViewModelComponent` → `@ViewModelScoped` — ViewModel lifetime
- `FragmentComponent` → `@FragmentScoped` — Fragment lifetime
- `ServiceComponent` → `@ServiceScoped` — Service lifetime

## Key Gotchas

- `@AndroidEntryPoint` requires the parent class to also be `@AndroidEntryPoint` (or `@HiltAndroidApp` for Application)
- Injection happens in `super.onCreate()` — fields aren't available before that call
- `@HiltViewModel` needs `@Inject constructor` — Hilt creates the ViewModel, not you
- `hiltViewModel()` in Compose requires `androidx.hilt:hilt-navigation-compose` dependency
- KSP replaces kapt for Hilt as of 2.48+ — use `ksp()` not `kapt()`
- Don't mix `kapt` and `ksp` for Hilt — pick one
