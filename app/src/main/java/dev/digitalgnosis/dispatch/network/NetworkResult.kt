package dev.digitalgnosis.dispatch.network

import retrofit2.Call
import retrofit2.CallAdapter
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * Sealed result type for all network operations in Dispatch.
 *
 * Eliminates exception-based error handling at API boundaries. Repositories return
 * [NetworkResult] at the network layer, then map to domain-specific sealed types before
 * exposing data to ViewModels.
 *
 * Usage with OkHttp (direct):
 * ```kotlin
 * val result = networkClient.getResult("sessions/")
 * when (result) {
 *     is NetworkResult.Success -> handleData(result.data)
 *     is NetworkResult.HttpError -> handleHttpError(result.code, result.message)
 *     is NetworkResult.NetworkError -> handleNetworkError(result.throwable)
 * }
 * ```
 *
 * Usage with Retrofit (declare return type as NetworkResult<T>):
 * ```kotlin
 * @GET("sessions/")
 * suspend fun getSessions(): NetworkResult<SessionsResponse>
 * ```
 */
sealed class NetworkResult<out T> {

    /** Request succeeded and the response body was successfully parsed. */
    data class Success<T>(val data: T) : NetworkResult<T>()

    /** Server returned a non-2xx HTTP status code. */
    data class HttpError(
        val code: Int,
        val message: String,
        val errorBody: String? = null,
    ) : NetworkResult<Nothing>()

    /** No response received — connection failure, timeout, or DNS error. */
    data class NetworkError(val throwable: Throwable) : NetworkResult<Nothing>()

    // ── Convenience extensions ───────────────────────────────────────────────

    /** True iff this is a [Success]. */
    val isSuccess: Boolean get() = this is Success

    /** Returns the data if [Success], otherwise null. */
    fun getOrNull(): T? = (this as? Success)?.data

    /** Returns the data if [Success], otherwise [default]. */
    fun getOrDefault(default: @UnsafeVariance T): T = (this as? Success)?.data ?: default

    /** Maps [Success] data; passes [HttpError] and [NetworkError] through unchanged. */
    inline fun <R> map(transform: (T) -> R): NetworkResult<R> = when (this) {
        is Success -> Success(transform(data))
        is HttpError -> this
        is NetworkError -> this
    }

    /** Runs [block] on [Success] data; returns this for chaining. */
    inline fun onSuccess(block: (T) -> Unit): NetworkResult<T> {
        if (this is Success) block(data)
        return this
    }

    /** Runs [block] on any error; returns this for chaining. */
    inline fun onError(block: (NetworkResult<Nothing>) -> Unit): NetworkResult<T> {
        if (this is HttpError || this is NetworkError) {
            @Suppress("UNCHECKED_CAST")
            block(this as NetworkResult<Nothing>)
        }
        return this
    }
}

// ── Retrofit CallAdapter ─────────────────────────────────────────────────────

/**
 * Retrofit [CallAdapter.Factory] that wraps responses in [NetworkResult].
 *
 * Register with Retrofit:
 * ```kotlin
 * Retrofit.Builder()
 *     .addCallAdapterFactory(NetworkResultCallAdapterFactory())
 *     ...
 *     .build()
 * ```
 *
 * Declare Retrofit interfaces with [NetworkResult] return types:
 * ```kotlin
 * @GET("sessions/")
 * suspend fun getSessions(): NetworkResult<SessionsResponse>
 * ```
 */
class NetworkResultCallAdapterFactory : CallAdapter.Factory() {

    override fun get(
        returnType: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): CallAdapter<*, *>? {
        if (getRawType(returnType) != NetworkResult::class.java) return null

        val resultType = getParameterUpperBound(0, returnType as ParameterizedType)
        return NetworkResultCallAdapter<Any>(resultType)
    }
}

private class NetworkResultCallAdapter<T>(
    private val responseType: Type,
) : CallAdapter<T, NetworkResult<T>> {

    override fun responseType(): Type = responseType

    override fun adapt(call: Call<T>): NetworkResult<T> {
        return try {
            val response = call.execute()
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    NetworkResult.Success(body)
                } else {
                    NetworkResult.HttpError(
                        code = response.code(),
                        message = "Empty response body",
                    )
                }
            } else {
                NetworkResult.HttpError(
                    code = response.code(),
                    message = response.message(),
                    errorBody = response.errorBody()?.string(),
                )
            }
        } catch (e: Exception) {
            NetworkResult.NetworkError(e)
        }
    }
}

/**
 * Suspend-compatible Retrofit call adapter for use with `suspend fun` Retrofit interfaces.
 *
 * When using `suspend fun` in a Retrofit interface, Retrofit handles coroutine integration
 * automatically — but you still need [NetworkResultCallAdapterFactory] registered so that
 * Retrofit recognizes [NetworkResult] as a valid return type and delegates exception handling.
 *
 * For the suspend path, Retrofit wraps the call in a [Callback] internally. This helper
 * converts [Response<T>] to [NetworkResult<T>] for use in repository suspend functions
 * that call Retrofit directly.
 */
fun <T> Response<T>.toNetworkResult(): NetworkResult<T> {
    return if (isSuccessful) {
        val body = body()
        if (body != null) {
            NetworkResult.Success(body)
        } else {
            NetworkResult.HttpError(code(), "Empty response body")
        }
    } else {
        NetworkResult.HttpError(
            code = code(),
            message = message(),
            errorBody = errorBody()?.string(),
        )
    }
}
