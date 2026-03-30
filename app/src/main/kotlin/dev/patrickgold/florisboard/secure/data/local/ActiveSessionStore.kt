package dev.patrickgold.florisboard.secure.data.local

import android.content.Context
import android.content.SharedPreferences

class ActiveSessionStore(context: Context) {
    private val prefs: SharedPreferences by lazy {
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }

    fun getForUser(userId: String?): ActiveSessionSelection? {
        if (userId.isNullOrBlank()) {
            clear()
            return null
        }

        val ownerUserId = prefs.getString(KEY_OWNER_USER_ID, null)
        val sessionId = prefs.getString(KEY_SESSION_ID, null)
        val recipientName = prefs.getString(KEY_RECIPIENT_NAME, null)

        if (ownerUserId.isNullOrBlank() || sessionId.isNullOrBlank() || recipientName.isNullOrBlank()) {
            if (prefs.all.isNotEmpty()) {
                clear()
            }
            return null
        }

        if (ownerUserId != userId) {
            clear()
            return null
        }

        return ActiveSessionSelection(
            ownerUserId = ownerUserId,
            sessionId = sessionId,
            recipientName = recipientName,
        )
    }

    fun saveForUser(userId: String, sessionId: String, recipientName: String) {
        prefs.edit()
            .putString(KEY_OWNER_USER_ID, userId)
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_RECIPIENT_NAME, recipientName)
            .commitOrThrow()
    }

    fun reconcileForUser(
        userId: String?,
        activeSessions: Map<String, String>,
    ): ActiveSessionSelection? {
        val selection = getForUser(userId) ?: return null
        val currentRecipient = activeSessions[selection.sessionId]
        if (currentRecipient.isNullOrBlank()) {
            clear()
            return null
        }

        if (currentRecipient != selection.recipientName) {
            saveForUser(selection.ownerUserId, selection.sessionId, currentRecipient)
            return selection.copy(recipientName = currentRecipient)
        }

        return selection
    }

    fun clearIfSessionMatches(sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        if (prefs.getString(KEY_SESSION_ID, null) == sessionId) {
            clear()
        }
    }

    fun clear() {
        if (prefs.all.isEmpty()) return
        prefs.edit().clear().commitOrThrow()
    }

    private fun SharedPreferences.Editor.commitOrThrow() {
        check(commit()) { "Failed to persist active secure session" }
    }

    companion object {
        private const val PREFS_FILE = "secure_active_session"
        private const val KEY_OWNER_USER_ID = "owner_user_id"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_RECIPIENT_NAME = "recipient_name"
    }
}

data class ActiveSessionSelection(
    val ownerUserId: String,
    val sessionId: String,
    val recipientName: String,
)
