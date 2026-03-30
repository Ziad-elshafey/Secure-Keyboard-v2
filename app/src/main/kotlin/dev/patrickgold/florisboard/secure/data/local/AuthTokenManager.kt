package dev.patrickgold.florisboard.secure.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

class AuthTokenManager(context: Context) {
    private val prefs: SharedPreferences by lazy {
        EncryptedPrefsFactory.create(context.applicationContext, PREFS_FILE)
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun saveTokens(accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .commitOrThrow()
    }

    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)

    fun getUsername(): String? = prefs.getString(KEY_USERNAME, null)

    fun saveUserInfo(userId: String, username: String) {
        prefs.edit()
            .putString(KEY_USER_ID, userId)
            .putString(KEY_USERNAME, username)
            .commitOrThrow()
    }

    fun isLoggedIn(): Boolean = !getAccessToken().isNullOrBlank()

    fun isAccessTokenExpired(): Boolean {
        val token = getAccessToken() ?: return true
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return true
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP))
            val expMatch = Regex("\"exp\"\\s*:\\s*(\\d+)").find(payload)
            val exp = expMatch?.groupValues?.get(1)?.toLongOrNull() ?: return true
            val nowSecs = System.currentTimeMillis() / 1000
            nowSecs >= exp
        } catch (_: Exception) {
            true
        }
    }

    fun clearAll() {
        prefs.edit().clear().commitOrThrow()
    }

    fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .commitOrThrow()
    }

    private fun SharedPreferences.Editor.commitOrThrow() {
        check(commit()) { "Failed to persist auth token data" }
    }

    companion object {
        private const val PREFS_FILE = "secure_auth_tokens"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
    }
}
