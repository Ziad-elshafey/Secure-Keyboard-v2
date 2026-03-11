package dev.patrickgold.florisboard.secure.data.remote

import dev.patrickgold.florisboard.secure.data.local.AuthTokenManager
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val tokenManager: AuthTokenManager,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val path = original.url.encodedPath

        if (AUTH_PATHS.any { path.contains(it) }) {
            return chain.proceed(original)
        }

        val token = tokenManager.getAccessToken()
        return if (token != null) {
            chain.proceed(
                original.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build(),
            )
        } else {
            chain.proceed(original)
        }
    }

    companion object {
        private val AUTH_PATHS = listOf(
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh",
        )
    }
}