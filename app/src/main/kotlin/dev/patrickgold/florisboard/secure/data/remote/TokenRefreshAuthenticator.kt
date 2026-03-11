package dev.patrickgold.florisboard.secure.data.remote

import dev.patrickgold.florisboard.secure.data.local.AuthTokenManager
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

class TokenRefreshAuthenticator(
    private val tokenManager: AuthTokenManager,
    private val apiProvider: () -> SecureApiService,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.header("X-Token-Refreshed") != null) {
            return null
        }

        val refreshToken = tokenManager.getRefreshToken() ?: return null

        return try {
            val newTokens = runBlocking {
                apiProvider().refreshToken(RefreshTokenRequest(refreshToken))
            }

            tokenManager.saveTokens(newTokens.accessToken, newTokens.refreshToken)

            response.request.newBuilder()
                .header("Authorization", "Bearer ${newTokens.accessToken}")
                .header("X-Token-Refreshed", "true")
                .build()
        } catch (_: Exception) {
            tokenManager.clearAll()
            null
        }
    }
}