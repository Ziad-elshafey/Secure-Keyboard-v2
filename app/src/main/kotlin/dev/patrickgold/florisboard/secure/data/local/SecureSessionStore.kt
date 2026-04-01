package dev.patrickgold.florisboard.secure.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.patrickgold.florisboard.secure.core.ActiveSecureContact
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

    fun getActiveContact(): ActiveSecureContact? {
        val userId = prefs.getString(KEY_CONTACT_USER_ID, null)
        val username = prefs.getString(KEY_CONTACT_USERNAME, null)
        if (!userId.isNullOrBlank() && !username.isNullOrBlank()) {
            return ActiveSecureContact(
                userId = userId,
                username = username,
                displayName = prefs.getString(KEY_CONTACT_DISPLAY_NAME, null),
            )
        }

        // Legacy migration path: hydrate contact selection from session selection if present.
        val legacyRecipient = prefs.getString(KEY_RECIPIENT_NAME, null)
        if (!legacyRecipient.isNullOrBlank()) {
            val migrated = ActiveSecureContact(
                userId = "",
                username = legacyRecipient,
                displayName = null,
            )
            setActiveContact(migrated)
            return migrated
        }
        return null
    }

    fun setActiveContact(contact: ActiveSecureContact) {
        prefs.edit()
            .putString(KEY_CONTACT_USER_ID, contact.userId)
            .putString(KEY_CONTACT_USERNAME, contact.username)
            .putString(KEY_CONTACT_DISPLAY_NAME, contact.displayName)
            .apply()
    }

    fun clearActiveContact() {
        prefs.edit()
            .remove(KEY_CONTACT_USER_ID)
            .remove(KEY_CONTACT_USERNAME)
            .remove(KEY_CONTACT_DISPLAY_NAME)
            .remove(KEY_SESSION_ID)
            .remove(KEY_RECIPIENT_NAME)
            .apply()
    }

    fun hasActiveContact(): Boolean = getActiveContact() != null

    fun getActiveSession(): SecureSessionSelection? {
        val sessionId = prefs.getString(KEY_SESSION_ID, null) ?: return null
        val recipientName = prefs.getString(KEY_RECIPIENT_NAME, null) ?: return null
        return SecureSessionSelection(sessionId = sessionId, recipientName = recipientName)
    }

    fun setActiveSession(sessionId: String, recipientName: String) {
        prefs.edit()
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_RECIPIENT_NAME, recipientName)
            .putString(KEY_CONTACT_USERNAME, recipientName)
            .apply()
    }

    fun clearActiveSession() {
        clearActiveContact()
    }

    fun hasActiveSession(): Boolean = getActiveSession() != null

    companion object {
        private const val PREFS_FILE = "secure_active_session_store"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_RECIPIENT_NAME = "recipient_name"
        private const val KEY_CONTACT_USER_ID = "active_contact_user_id"
        private const val KEY_CONTACT_USERNAME = "active_contact_username"
        private const val KEY_CONTACT_DISPLAY_NAME = "active_contact_display_name"
    }
}
