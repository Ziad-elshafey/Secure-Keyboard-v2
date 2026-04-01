package dev.patrickgold.florisboard.secure.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.patrickgold.florisboard.secure.core.SecureContact
import org.json.JSONObject
import java.util.Locale

class SecureContactStore(context: Context) {
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

    fun listContacts(activeUserId: String): List<SecureContact> {
        val usernames = prefs.getStringSet(userContactsKey(activeUserId), emptySet()) ?: emptySet()
        return usernames.mapNotNull { normalized ->
            prefs.getString(userContactItemKey(activeUserId, normalized), null)?.let { decodeContact(it) }
        }.sortedBy { it.username.lowercase(Locale.ROOT) }
    }

    fun upsertContact(activeUserId: String, contact: SecureContact) {
        val normalized = normalizeUsername(contact.username)
        val contactsKey = userContactsKey(activeUserId)
        val usernames = (prefs.getStringSet(contactsKey, emptySet()) ?: emptySet()).toMutableSet()
        usernames.add(normalized)
        prefs.edit()
            .putStringSet(contactsKey, usernames)
            .putString(userContactItemKey(activeUserId, normalized), encodeContact(contact))
            .apply()
    }

    fun removeContact(activeUserId: String, username: String) {
        val normalized = normalizeUsername(username)
        val contactsKey = userContactsKey(activeUserId)
        val usernames = (prefs.getStringSet(contactsKey, emptySet()) ?: emptySet()).toMutableSet()
        usernames.remove(normalized)
        prefs.edit()
            .putStringSet(contactsKey, usernames)
            .remove(userContactItemKey(activeUserId, normalized))
            .apply()
    }

    fun getContact(activeUserId: String, username: String): SecureContact? {
        val normalized = normalizeUsername(username)
        val payload = prefs.getString(userContactItemKey(activeUserId, normalized), null) ?: return null
        return decodeContact(payload)
    }

    fun clearContactsForActiveUser(activeUserId: String) {
        val usernames = prefs.getStringSet(userContactsKey(activeUserId), emptySet()) ?: emptySet()
        val editor = prefs.edit().remove(userContactsKey(activeUserId))
        usernames.forEach { normalized ->
            editor.remove(userContactItemKey(activeUserId, normalized))
        }
        editor.apply()
    }

    private fun userContactsKey(activeUserId: String): String = "u:$activeUserId:contacts"

    private fun userContactItemKey(activeUserId: String, normalizedUsername: String): String =
        "u:$activeUserId:contact:$normalizedUsername"

    private fun normalizeUsername(username: String): String = username.trim().lowercase(Locale.ROOT)

    private fun encodeContact(contact: SecureContact): String {
        return JSONObject()
            .put("user_id", contact.userId)
            .put("username", contact.username)
            .put("display_name", contact.displayName)
            .put("added_at_epoch_ms", contact.addedAtEpochMs)
            .toString()
    }

    private fun decodeContact(value: String): SecureContact? {
        return runCatching {
            val json = JSONObject(value)
            SecureContact(
                userId = json.getString("user_id"),
                username = json.getString("username"),
                displayName = json.optString("display_name").ifBlank { null },
                addedAtEpochMs = json.optLong("added_at_epoch_ms", System.currentTimeMillis()),
            )
        }.getOrNull()
    }

    companion object {
        private const val PREFS_FILE = "secure_contacts_store"
    }
}
