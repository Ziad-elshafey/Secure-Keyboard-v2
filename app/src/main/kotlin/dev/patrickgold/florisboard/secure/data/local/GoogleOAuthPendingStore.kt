package dev.patrickgold.florisboard.secure.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.patrickgold.florisboard.secure.auth.GoogleOAuthCoordinator

/**
 * Persists in-flight Google OAuth PKCE data so the callback still works if the
 * app process is restarted while the user is in the system browser.
 */
class GoogleOAuthPendingStore(context: Context) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun save(request: GoogleOAuthCoordinator.PendingRequest) {
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_STATE, request.state)
            .putString(KEY_CODE_VERIFIER, request.codeVerifier)
            .putString(KEY_REDIRECT_URI, request.redirectUri)
            .putLong(KEY_STARTED_AT, request.startedAtMillis)
            .apply()
    }

    fun load(): GoogleOAuthCoordinator.PendingRequest? {
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return null
        val state = prefs.getString(KEY_STATE, null) ?: return null
        val codeVerifier = prefs.getString(KEY_CODE_VERIFIER, null) ?: return null
        val redirectUri = prefs.getString(KEY_REDIRECT_URI, null) ?: return null
        val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
        return GoogleOAuthCoordinator.PendingRequest(
            state = state,
            codeVerifier = codeVerifier,
            codeChallenge = GoogleOAuthCoordinator.createCodeChallenge(codeVerifier),
            redirectUri = redirectUri,
            startedAtMillis = startedAt,
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_FILE = "google_oauth_pending"
        private const val KEY_ACTIVE = "active"
        private const val KEY_STATE = "state"
        private const val KEY_CODE_VERIFIER = "code_verifier"
        private const val KEY_REDIRECT_URI = "redirect_uri"
        private const val KEY_STARTED_AT = "started_at"
    }
}
