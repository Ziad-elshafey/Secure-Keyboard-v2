package dev.patrickgold.florisboard.secure.data.remote

import dev.patrickgold.florisboard.secure.data.local.AuthTokenManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val tokenManager: AuthTokenManager,
    private val apiProvider: (() -> SecureApiService)? = null,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val path = original.url.encodedPath

        if (AUTH_PATHS.any { path.contains(it) }) {
            return chain.proceed(original)
        }

        val token = resolveAccessToken()
        return if (!token.isNullOrBlank()) {
            chain.proceed(
                original.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build(),
            )
        } else {
            chain.proceed(original)
        }
    }

    private fun resolveAccessToken(): String? {
        val currentToken = tokenManager.getAccessToken()
        val refreshToken = tokenManager.getRefreshToken()
        val currentTokenUsable = !currentToken.isNullOrBlank() && !tokenManager.isAccessTokenExpired()

        if (currentTokenUsable) {
            return currentToken
        }

        if (refreshToken.isNullOrBlank() || apiProvider == null) {
            return currentToken
        }

        return try {
            val newTokens = runBlocking {
                apiProvider().refreshToken(RefreshTokenRequest(refreshToken))
            }
            tokenManager.saveTokens(newTokens.accessToken, newTokens.refreshToken)
            newTokens.accessToken
        } catch (_: Exception) {
            if (currentToken.isNullOrBlank() || tokenManager.isAccessTokenExpired()) {
                tokenManager.clearAll()
                null
            } else {
                currentToken
            }
        }
    }

    companion object {
        private val AUTH_PATHS = listOf(
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/oauth/google/start",
            "/api/auth/oauth/google/exchange",
            "/api/auth/refresh",
        )
    }
}
