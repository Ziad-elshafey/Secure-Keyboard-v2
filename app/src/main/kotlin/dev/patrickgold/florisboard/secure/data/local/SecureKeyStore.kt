package dev.patrickgold.florisboard.secure.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.patrickgold.florisboard.secure.core.e2ee.IdentityKeyPair
import dev.patrickgold.florisboard.secure.core.e2ee.SignedPreKey

class SecureKeyStore(context: Context) {
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

    fun setActiveUser(userId: String) {
        prefs.edit().putString(KEY_ACTIVE_USER_ID, userId).apply()
    }

    fun clearActiveUser() {
        prefs.edit().remove(KEY_ACTIVE_USER_ID).apply()
    }

    fun hasActiveUser(): Boolean = prefs.contains(KEY_ACTIVE_USER_ID)

    private fun activeUserPrefix(): String {
        val userId = prefs.getString(KEY_ACTIVE_USER_ID, null)
            ?: throw IllegalStateException("Active user is not set in SecureKeyStore")
        return "u:$userId:"
    }

    private fun scopedKey(baseKey: String): String = activeUserPrefix() + baseKey

    private fun scopedSessionKey(prefix: String, sessionId: String): String =
        activeUserPrefix() + prefix + sessionId

    fun saveIdentityKeyPair(keyPair: IdentityKeyPair) {
        prefs.edit()
            .putString(scopedKey(KEY_IDENTITY_PRIVATE), encode(keyPair.privateKey))
            .putString(scopedKey(KEY_IDENTITY_PUBLIC), encode(keyPair.publicKey))
            .apply()
    }

    fun getIdentityKeyPair(): IdentityKeyPair? {
        val priv = prefs.getString(scopedKey(KEY_IDENTITY_PRIVATE), null)?.let(::decode) ?: return null
        val pub = prefs.getString(scopedKey(KEY_IDENTITY_PUBLIC), null)?.let(::decode) ?: return null
        return IdentityKeyPair(privateKey = priv, publicKey = pub)
    }

    fun hasIdentityKeys(): Boolean =
        prefs.contains(scopedKey(KEY_IDENTITY_PRIVATE)) && prefs.contains(scopedKey(KEY_IDENTITY_PUBLIC))

    fun saveSignedPreKey(preKey: SignedPreKey) {
        prefs.edit()
            .putInt(scopedKey(KEY_SPK_ID), preKey.keyId)
            .putString(scopedKey(KEY_SPK_PRIVATE), encode(preKey.privateKey))
            .putString(scopedKey(KEY_SPK_PUBLIC), encode(preKey.publicKey))
            .putString(scopedKey(KEY_SPK_SIGNATURE), encode(preKey.signature))
            .apply()
    }

    fun getSignedPreKey(): SignedPreKey? {
        val idKey = scopedKey(KEY_SPK_ID)
        val privKey = scopedKey(KEY_SPK_PRIVATE)
        val pubKey = scopedKey(KEY_SPK_PUBLIC)
        val sigKey = scopedKey(KEY_SPK_SIGNATURE)

        val id = if (prefs.contains(idKey)) prefs.getInt(idKey, 0) else return null
        val priv = prefs.getString(privKey, null)?.let(::decode) ?: return null
        val pub = prefs.getString(pubKey, null)?.let(::decode) ?: return null
        val sig = prefs.getString(sigKey, null)?.let(::decode) ?: return null
        return SignedPreKey(keyId = id, privateKey = priv, publicKey = pub, signature = sig)
    }

    fun hasSignedPreKey(): Boolean = prefs.contains(scopedKey(KEY_SPK_PRIVATE))

    fun saveSharedSecret(sessionId: String, secret: ByteArray) {
        prefs.edit()
            .putString(scopedSessionKey(PREFIX_SHARED_SECRET, sessionId), encode(secret))
            .apply()
    }

    fun getSharedSecret(sessionId: String): ByteArray? =
        prefs.getString(scopedSessionKey(PREFIX_SHARED_SECRET, sessionId), null)?.let(::decode)

    fun hasSharedSecret(sessionId: String): Boolean =
        prefs.contains(scopedSessionKey(PREFIX_SHARED_SECRET, sessionId))

    fun removeSharedSecret(sessionId: String) {
        prefs.edit()
            .remove(scopedSessionKey(PREFIX_SHARED_SECRET, sessionId))
            .apply()
    }

    fun saveEphemeralPublicKey(sessionId: String, key: ByteArray) {
        prefs.edit()
            .putString(scopedSessionKey(PREFIX_EPHEMERAL_KEY, sessionId), encode(key))
            .apply()
    }

    fun getEphemeralPublicKey(sessionId: String): ByteArray? =
        prefs.getString(scopedSessionKey(PREFIX_EPHEMERAL_KEY, sessionId), null)?.let(::decode)

    fun removeEphemeralPublicKey(sessionId: String) {
        prefs.edit()
            .remove(scopedSessionKey(PREFIX_EPHEMERAL_KEY, sessionId))
            .apply()
    }

    fun clearSessionMaterialForActiveUser() {
        val prefix = activeUserPrefix()
        val keysToRemove = prefs.all.keys.filter {
            it.startsWith(prefix + PREFIX_SHARED_SECRET) ||
                it.startsWith(prefix + PREFIX_EPHEMERAL_KEY)
        }

        if (keysToRemove.isEmpty()) return

        val editor = prefs.edit()
        keysToRemove.forEach(editor::remove)
        editor.apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun encode(data: ByteArray): String =
        Base64.encodeToString(data, Base64.NO_WRAP)

    private fun decode(encoded: String): ByteArray =
        Base64.decode(encoded, Base64.NO_WRAP)

    companion object {
        private const val PREFS_FILE = "secure_key_store"
        private const val KEY_ACTIVE_USER_ID = "active_user_id"
        private const val KEY_IDENTITY_PRIVATE = "identity_private"
        private const val KEY_IDENTITY_PUBLIC = "identity_public"
        private const val KEY_SPK_ID = "spk_id"
        private const val KEY_SPK_PRIVATE = "spk_private"
        private const val KEY_SPK_PUBLIC = "spk_public"
        private const val KEY_SPK_SIGNATURE = "spk_signature"
        private const val PREFIX_SHARED_SECRET = "shared_secret_"
        private const val PREFIX_EPHEMERAL_KEY = "ephemeral_key_"
    }
}