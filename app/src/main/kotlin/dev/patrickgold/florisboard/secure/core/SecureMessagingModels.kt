package dev.patrickgold.florisboard.secure.core

data class SecureSessionSelection(
    val sessionId: String,
    val recipientName: String,
)

data class SecureContact(
    val userId: String,
    val username: String,
    val displayName: String? = null,
    val addedAtEpochMs: Long = System.currentTimeMillis(),
)

data class ActiveSecureContact(
    val userId: String,
    val username: String,
    val displayName: String? = null,
)

data class ManagedSecureSession(
    val sessionId: String,
    val peerUsername: String,
    val canSend: Boolean,
    val recoveryHint: String? = null,
    val isActiveSelection: Boolean = false,
)

data class SecureSessionState(
    val isLoggedIn: Boolean = false,
    val activeSessionId: String? = null,
    val activeRecipientName: String? = null,
    val isSessionReady: Boolean = false,
    val recoveryHint: String? = null,
    val lastOperation: SecureOperationResult? = null,
)

data class SecureOperationResult(
    val isSuccess: Boolean,
    val message: String,
    val providerType: StegoProviderType? = null,
    val usedFallback: Boolean = false,
)

enum class StegoProviderType {
    SERVER,
    MODAL,
}
