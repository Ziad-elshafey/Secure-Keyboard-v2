package dev.patrickgold.florisboard.secure.data.remote

import com.google.gson.annotations.SerializedName

data class RegisterRequest(
    @SerializedName("username") val username: String,
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String,
    @SerializedName("display_name") val displayName: String? = null,
)

data class LoginRequest(
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String,
)

data class RefreshTokenRequest(
    @SerializedName("refresh_token") val refreshToken: String,
)

data class RegisterResponse(
    @SerializedName("user_id") val userId: String,
    @SerializedName("username") val username: String,
    @SerializedName("email") val email: String,
    @SerializedName("display_name") val displayName: String?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("token_type") val tokenType: String = "bearer",
)

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("token_type") val tokenType: String = "bearer",
    @SerializedName("expires_in") val expiresIn: Int = 86400,
)

data class UserProfileResponse(
    @SerializedName("user_id") val userId: String,
    @SerializedName("username") val username: String,
    @SerializedName("email") val email: String,
    @SerializedName("display_name") val displayName: String?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("last_seen_at") val lastSeenAt: String?,
    @SerializedName("is_active") val isActive: Boolean,
)

data class UserSearchResult(
    @SerializedName("user_id") val userId: String,
    @SerializedName("username") val username: String,
    @SerializedName("display_name") val displayName: String?,
    @SerializedName("is_active") val isActive: Boolean,
)

data class UploadKeysRequest(
    @SerializedName("identity_key_public") val identityKeyPublic: String,
    @SerializedName("signed_prekey_public") val signedPrekeyPublic: String,
    @SerializedName("signed_prekey_signature") val signedPrekeySignature: String,
    @SerializedName("signed_prekey_id") val signedPrekeyId: Int,
)

data class PreKeyBundleResponse(
    @SerializedName("user_id") val userId: String,
    @SerializedName("username") val username: String,
    @SerializedName("identity_key_public") val identityKeyPublic: String,
    @SerializedName("signed_prekey_public") val signedPrekeyPublic: String,
    @SerializedName("signed_prekey_signature") val signedPrekeySignature: String,
    @SerializedName("signed_prekey_id") val signedPrekeyId: Int,
)

data class KeyStatusResponse(
    @SerializedName("user_id") val userId: String,
    @SerializedName("username") val username: String,
    @SerializedName("has_identity_key") val hasIdentityKey: Boolean,
    @SerializedName("has_signed_prekey") val hasSignedPrekey: Boolean,
    @SerializedName("signed_prekey_id") val signedPrekeyId: Int?,
    @SerializedName("keys_uploaded_at") val keysUploadedAt: String?,
)

data class CreateSessionRequest(
    @SerializedName("peer_username") val peerUsername: String,
    @SerializedName("ephemeral_public_key") val ephemeralPublicKey: String,
)

data class SessionResponse(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("initiator_id") val initiatorId: String,
    @SerializedName("initiator_username") val initiatorUsername: String,
    @SerializedName("responder_id") val responderId: String,
    @SerializedName("responder_username") val responderUsername: String,
    @SerializedName("last_counter") val lastCounter: Int,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("is_active") val isActive: Boolean,
)

data class CounterResponse(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("counter") val counter: Int,
)

data class ObfuscateRequest(
    @SerializedName("ciphertext_b64") val ciphertextB64: String,
    @SerializedName("peer_username") val peerUsername: String,
)

data class ObfuscateResponse(
    @SerializedName("obfuscated_text") val obfuscatedText: String,
    @SerializedName("provider") val provider: String? = null,
    @SerializedName("fallback_used") val fallbackUsed: Boolean = false,
    @SerializedName("fallback_reason") val fallbackReason: String? = null,
    @SerializedName("obfuscation_version") val obfuscationVersion: String? = null,
)

data class DeobfuscateRequest(
    @SerializedName("obfuscated_text") val obfuscatedText: String,
    @SerializedName("sender_username") val senderUsername: String,
)

data class DeobfuscateResponse(
    @SerializedName("ciphertext_b64") val ciphertextB64: String,
    @SerializedName("provider") val provider: String? = null,
    @SerializedName("fallback_used") val fallbackUsed: Boolean = false,
    @SerializedName("fallback_reason") val fallbackReason: String? = null,
    @SerializedName("obfuscation_version") val obfuscationVersion: String? = null,
)

data class EphemeralKeyResponse(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("ephemeral_public_key") val ephemeralPublicKey: String,
)
