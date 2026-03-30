package dev.patrickgold.florisboard.secure.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.patrickgold.florisboard.secure.core.SecureSessionSelection

class SecureSessionStore(context: Context) {
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

    fun getActiveSession(): SecureSessionSelection? {
        val sessionId = prefs.getString(KEY_SESSION_ID, null) ?: return null
        val recipientName = prefs.getString(KEY_RECIPIENT_NAME, null) ?: return null
        return SecureSessionSelection(sessionId = sessionId, recipientName = recipientName)
    }

    fun setActiveSession(sessionId: String, recipientName: String) {
        prefs.edit()
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_RECIPIENT_NAME, recipientName)
            .apply()
    }

    fun clearActiveSession() {
        prefs.edit()
            .remove(KEY_SESSION_ID)
            .remove(KEY_RECIPIENT_NAME)
            .apply()
    }

    fun hasActiveSession(): Boolean = getActiveSession() != null

    companion object {
        private const val PREFS_FILE = "secure_active_session_store"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_RECIPIENT_NAME = "recipient_name"
    }
}
