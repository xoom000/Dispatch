# Auth Token Refresh Pattern — Future Implementation Guide

**Date:** 2026-03-20
**Status:** DOCUMENTATION ONLY — not implemented
**Gap:** GAP-S5
**Context:** Dispatch currently uses static API key auth (X-API-Key header via `FileBridgeAuthInterceptor`).
If we ever move to token-based auth (JWT access + refresh tokens), this document describes the
exact pattern to implement, sourced from the Bitwarden Android reference.

---

## Current State

`FileBridgeAuthInterceptor` adds a static `X-API-Key` header to every OkHttp request.
The key is a build-time constant from `local.properties` → `BuildConfig.FB_API_KEY`.

**No token rotation. No expiry. No refresh.**

This is intentional for now — static API key auth is simpler and File Bridge doesn't issue JWTs.

---

## When to Implement

Implement this pattern if/when:
- File Bridge switches from API key to JWT-based auth
- A user-specific access token (short-lived, with refresh) is introduced
- The Dispatch backend starts issuing `access_token` + `refresh_token` pairs

---

## The Bitwarden Pattern (Reference: `AuthTokenManager`)

Bitwarden's `AuthTokenManager` implements three roles simultaneously:
1. `okhttp3.Interceptor` — adds `Authorization: Bearer <token>` to every request
2. `okhttp3.Authenticator` — handles 401 responses by refreshing the token and retrying
3. Custom `TokenProvider` — exposes the token to other system components

### Proactive Refresh (Interceptor path)

Before attaching the access token to an outgoing request, check if it expires within 5 minutes.
If so, refresh synchronously before proceeding.

```kotlin
class DispatchAuthTokenManager(
    private val tokenStore: TokenStore,             // encrypted SharedPrefs
    private val refreshProvider: TokenRefreshProvider,
) : Interceptor, Authenticator {

    @Synchronized
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenStore.getAccessToken()

        // Proactive: refresh if expiring within 5 minutes
        val refreshedToken = if (token.isExpiringWithin(minutes = 5)) {
            refreshProvider.refreshAccessTokenSynchronously() ?: token
        } else {
            token
        }

        val request = chain.request().newBuilder()
            .header("Authorization", "Bearer ${refreshedToken.value}")
            .build()

        return chain.proceed(request)
    }

    @Synchronized
    override fun authenticate(route: Route?, response: Response): Request? {
        // Reactive: handle 401 from server (token was rejected)
        val currentToken = parseTokenFromResponse(response)

        // Guard: if we already retried once, stop (avoids infinite loop)
        if (response.priorResponse != null) return null

        val refreshed = refreshProvider.refreshAccessTokenSynchronously()
            ?: return null  // refresh failed — let the 401 propagate

        tokenStore.saveAccessToken(refreshed)

        return response.request.newBuilder()
            .header("Authorization", "Bearer ${refreshed.value}")
            .build()
    }
}
```

### Key Implementation Details

**Thread safety:** Both `intercept()` and `authenticate()` are `@Synchronized`.
Concurrent requests during token refresh will queue behind the lock — only one refresh fires.
This is correct; the alternative (a Mutex + coroutine) requires async OkHttp which complicates things.

**Token storage:** Store `access_token` and `refresh_token` in `EncryptedSharedPreferences`.
Never store in plain SharedPrefs or DataStore without encryption.
Reference: `dev.digitalgnosis.dispatch.config.TokenManager` already uses DataStore — migrate
sensitive tokens to `EncryptedSharedPreferences` (Jetpack Security, `MasterKey`).

**Authenticator guard:** Check `response.priorResponse != null` before retrying.
Without this guard, a permanently invalid refresh token causes an infinite 401 loop.

**JWT expiry parsing:** The access token is a JWT. Parse expiry from the `exp` claim in the payload:
```kotlin
fun String.jwtExpiry(): Instant {
    val payload = split(".")[1]
    val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING)
    val json = JSONObject(String(decoded))
    return Instant.ofEpochSecond(json.getLong("exp"))
}

fun AccessToken.isExpiringWithin(minutes: Long): Boolean =
    jwtExpiry().isBefore(Instant.now().plusSeconds(minutes * 60))
```

**Separate OkHttp clients:** Apply `DispatchAuthTokenManager` ONLY to authenticated OkHttp clients.
Unauthenticated endpoints (token fetch, health check) must use a plain client without this interceptor.
Bitwarden uses `authenticatedApiRetrofit` vs `unauthenticatedApiRetrofit` for this separation.

---

## Integration with Hilt DI

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object DispatchNetworkModule {

    @Provides @Singleton
    fun provideAuthTokenManager(
        tokenStore: TokenStore,
        refreshProvider: TokenRefreshProvider,
    ): DispatchAuthTokenManager = DispatchAuthTokenManager(tokenStore, refreshProvider)

    @Provides @Singleton @AuthenticatedClient
    fun provideAuthenticatedOkHttp(
        authTokenManager: DispatchAuthTokenManager,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authTokenManager)      // proactive refresh
        .authenticator(authTokenManager)       // reactive refresh on 401
        .build()

    @Provides @Singleton @UnauthenticatedClient
    fun provideUnauthenticatedOkHttp(): OkHttpClient = OkHttpClient.Builder().build()
}
```

Use `@Qualifier` annotations (`@AuthenticatedClient`, `@UnauthenticatedClient`) to distinguish
the two clients in Hilt injection.

---

## Migration Path from Static API Key

1. Continue using `FileBridgeAuthInterceptor` (static API key) until File Bridge issues JWTs.
2. When JWTs are introduced:
   - Implement `TokenStore` backed by `EncryptedSharedPreferences`
   - Implement `TokenRefreshProvider` calling the File Bridge `/auth/refresh` endpoint
   - Implement `DispatchAuthTokenManager` as above
   - Replace `FileBridgeAuthInterceptor` with `DispatchAuthTokenManager` in `BaseFileBridgeClient`
   - Keep a separate unauthenticated OkHttp client for the token refresh endpoint itself
3. Update `TokenManager` to persist refresh tokens via `EncryptedSharedPreferences`
   (currently only stores session data in plain DataStore)

---

## References

- Bitwarden `AuthTokenManager`: `/home/xoom000/AndroidStudioProjects/bitwarden-android/network/src/main/kotlin/.../network/interceptor/AuthTokenManager.kt`
- Bitwarden architecture audit: `.project/2026-03-20-bitwarden-architecture-audit.md` § 4. Networking
- Current Dispatch interceptor: `app/src/main/java/dev/digitalgnosis/dispatch/network/FileBridgeAuthInterceptor.kt`
- Current token storage: `app/src/main/java/dev/digitalgnosis/dispatch/config/TokenManager.kt`
