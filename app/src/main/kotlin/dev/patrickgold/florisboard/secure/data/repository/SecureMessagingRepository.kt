package dev.patrickgold.florisboard.secure.data.repository

import dev.patrickgold.florisboard.secure.core.StegoProviderType
import dev.patrickgold.florisboard.secure.core.e2ee.E2EEService
import dev.patrickgold.florisboard.secure.data.local.AuthTokenManager
import dev.patrickgold.florisboard.secure.data.local.SecureKeyStore
import dev.patrickgold.florisboard.secure.data.remote.CreateSessionRequest
import dev.patrickgold.florisboard.secure.data.remote.DeobfuscateRequest
import dev.patrickgold.florisboard.secure.data.remote.LoginRequest
import dev.patrickgold.florisboard.secure.data.remote.ObfuscateRequest
import dev.patrickgold.florisboard.secure.data.remote.RefreshTokenRequest
import dev.patrickgold.florisboard.secure.data.remote.RegisterRequest
import dev.patrickgold.florisboard.secure.data.remote.SecureApiService
import dev.patrickgold.florisboard.secure.data.remote.SessionResponse
import dev.patrickgold.florisboard.secure.data.remote.UploadKeysRequest
import dev.patrickgold.florisboard.secure.data.remote.UserSearchResult
import dev.patrickgold.florisboard.secure.data.repository.compression.CompressionService
import android.util.Log

class SecureMessagingRepository(
    private val api: SecureApiService,
    private val tokenManager: AuthTokenManager,
    private val keyStore: SecureKeyStore,
) {
    companion object {
        private const val tag = "SecureMessagingRepo"
        const val flagRaw: Byte = 0x00
        const val flagCompressed: Byte = 0x01
    }

    private val compressionEnabled: Boolean by lazy {
        try {
            CompressionService.compress("test")
            true
        } catch (_: Exception) {
            Log.w(tag, "Compression vocab unavailable - falling back to raw mode")
            false
        }
    }

    suspend fun register(username: String, password: String): Result<String> = runCatching {
        val email = "$username@example.com"
        val response = api.register(RegisterRequest(username, email, password))
        tokenManager.saveTokens(response.accessToken, response.refreshToken)
        tokenManager.saveUserInfo(response.userId, response.username)
        keyStore.setActiveUser(response.userId)

        val identityKeyPair = E2EEService.generateIdentityKeyPair()
        val signedPreKey = E2EEService.generateSignedPreKey(1, identityKeyPair.privateKey)

        api.uploadKeys(
            UploadKeysRequest(
                identityKeyPublic = E2EEService.toBase64(identityKeyPair.publicKey),
                signedPrekeyPublic = E2EEService.toBase64(signedPreKey.publicKey),
                signedPrekeySignature = E2EEService.toBase64(signedPreKey.signature),
                signedPrekeyId = signedPreKey.keyId,
            ),
        )

        keyStore.saveIdentityKeyPair(identityKeyPair)
        keyStore.saveSignedPreKey(signedPreKey)

        response.userId
    }

    suspend fun login(username: String, password: String): Result<String> = runCatching {
        val response = api.login(LoginRequest(username, password))
        tokenManager.saveTokens(response.accessToken, response.refreshToken)

        val user = api.getCurrentUser()
        tokenManager.saveUserInfo(user.userId, user.username)
        keyStore.setActiveUser(user.userId)

        if (!keyStore.hasIdentityKeys()) {
            val identityKeyPair = E2EEService.generateIdentityKeyPair()
            val signedPreKey = E2EEService.generateSignedPreKey(1, identityKeyPair.privateKey)

            api.uploadKeys(
                UploadKeysRequest(
                    identityKeyPublic = E2EEService.toBase64(identityKeyPair.publicKey),
                    signedPrekeyPublic = E2EEService.toBase64(signedPreKey.publicKey),
                    signedPrekeySignature = E2EEService.toBase64(signedPreKey.signature),
                    signedPrekeyId = signedPreKey.keyId,
                ),
            )

            keyStore.saveIdentityKeyPair(identityKeyPair)
            keyStore.saveSignedPreKey(signedPreKey)
        }

        user.userId
    }

    fun isLoggedIn(): Boolean = tokenManager.isLoggedIn()

    fun getUsername(): String? = tokenManager.getUsername()

    fun getUserId(): String? = tokenManager.getUserId()

    fun logout() {
        if (keyStore.hasActiveUser()) {
            keyStore.clearSessionMaterialForActiveUser()
            keyStore.clearActiveUser()
        }
        tokenManager.clearAll()
    }

    suspend fun searchUsers(query: String): Result<List<UserSearchResult>> = runCatching {
        api.searchUsers(query)
    }

    suspend fun createSession(peerUsername: String, peerUserId: String): Result<SessionInfo> = runCatching {
        val myUserId = tokenManager.getUserId()
            ?: error("Not logged in - no user ID available")

        val bundle = api.getKeyBundle(peerUserId)
        val identityKeyPub = E2EEService.fromBase64(bundle.identityKeyPublic)
        val signedPreKeyPub = E2EEService.fromBase64(bundle.signedPrekeyPublic)
        val signature = E2EEService.fromBase64(bundle.signedPrekeySignature)

        check(E2EEService.ed25519Verify(identityKeyPub, signedPreKeyPub, signature)) {
            "Recipient's signed pre-key signature is invalid - possible MITM"
        }

        val x3dhInitResult = E2EEService.x3dhInitiate(recipientSignedPreKeyPublic = signedPreKeyPub)

        val session = api.createSession(
            CreateSessionRequest(
                peerUsername = peerUsername,
                ephemeralPublicKey = E2EEService.toBase64(x3dhInitResult.ephemeralPublicKey),
            ),
        )

        if (keyStore.hasSharedSecret(session.sessionId)) {
            Log.d(tag, "createSession: shared secret already cached for ${session.sessionId}")
            return@runCatching SessionInfo(sessionId = session.sessionId, peerUsername = peerUsername)
        }

        val weAreInitiator = session.initiatorId == myUserId
        Log.d(tag, "createSession: weAreInitiator=$weAreInitiator (myId=$myUserId, initiatorId=${session.initiatorId})")

        val sharedSecret = if (weAreInitiator) {
            keyStore.saveEphemeralPublicKey(session.sessionId, x3dhInitResult.ephemeralPublicKey)
            Log.d(tag, "createSession: initiator path - saved ephemeral + shared secret")
            x3dhInitResult.sharedSecret
        } else {
            val ephemeralData = api.getEphemeralKey(session.sessionId)
            val initiatorEphPub = E2EEService.fromBase64(ephemeralData.ephemeralPublicKey)

            val signedPreKey = keyStore.getSignedPreKey()
                ?: error("No signed pre-key found - cannot respond to X3DH")

            E2EEService.x3dhRespond(
                signedPreKeyPrivate = signedPreKey.privateKey,
                ephemeralPublicKey = initiatorEphPub,
            ).also {
                Log.d(tag, "createSession: responder path - derived shared secret via x3dhRespond")
            }
        }

        keyStore.saveSharedSecret(session.sessionId, sharedSecret)

        SessionInfo(sessionId = session.sessionId, peerUsername = peerUsername)
    }

    suspend fun listSessions(): Result<List<SessionResponse>> = runCatching {
        api.listSessions()
    }

    suspend fun deactivateSession(sessionId: String): Result<Unit> = runCatching {
        api.deactivateSession(sessionId)
        keyStore.removeSharedSecret(sessionId)
        keyStore.removeEphemeralPublicKey(sessionId)
    }

    suspend fun sendMessage(sessionId: String, peerUsername: String, plaintext: String): Result<SendResult> = runCatching {
        val sharedSecret = getOrEstablishSharedSecretForSend(sessionId, peerUsername)

        val counterResp = api.getNextCounter(sessionId)
        val counter = counterResp.counter
        Log.d(tag, "sendMessage: got counter=$counter for session=$sessionId peer=$peerUsername")

        val payload = buildPayload(plaintext)
        val ciphertext = E2EEService.chacha20Encrypt(payload, sharedSecret, counter)
        val packed = E2EEService.packCiphertextWithCounter(ciphertext, counter)

        Log.d(
            tag,
            "sendMessage: raw=${plaintext.toByteArray().size}B, payload=${payload.size}B (flag=0x%02X), cipher=${ciphertext.size}B, packed=${packed.size}B, counter=$counter".format(payload[0]),
        )

        val sendResult = obfuscateWithServer(packed, peerUsername)
        Log.d(
            tag,
            "sendMessage: obfuscated through server provider=${sendResult.providerType} fallback=${sendResult.usedFallback}",
        )
        sendResult
    }

    suspend fun decryptMessage(obfuscatedText: String, senderUsername: String): Result<DecryptResult> = runCatching {
        Log.d(tag, "decryptMessage: sender=$senderUsername")

        val decoded = deobfuscateWithServer(obfuscatedText, senderUsername)
        Log.d(
            tag,
            "decryptMessage: deobfuscated through server provider=${decoded.providerType} fallback=${decoded.usedFallback}",
        )

        val sessions = api.listSessions(activeOnly = true)
        val session = sessions.firstOrNull { s ->
            s.initiatorUsername == senderUsername || s.responderUsername == senderUsername
        } ?: error("No active session found with $senderUsername")

        val sharedSecret = getOrEstablishSharedSecretForReceive(session.sessionId)
        val (ciphertext, counter) = E2EEService.unpackCiphertextAndCounter(decoded.packedCiphertext)
        Log.d(tag, "decryptMessage: packed=${decoded.packedCiphertext.size}B ciphertext=${ciphertext.size}B counter=$counter")

        val payload = E2EEService.chacha20Decrypt(ciphertext, sharedSecret, counter)
        val plaintext = parsePayload(payload)
        Log.d(tag, "decryptMessage: success plaintext=${plaintext.take(50)}...")
        DecryptResult(
            plaintext = plaintext,
            providerType = decoded.providerType,
            usedFallback = decoded.usedFallback,
        )
    }.onFailure { e ->
        Log.e(tag, "decryptMessage: failed", e)
    }

    fun canSendToSession(session: SessionResponse): Boolean {
        if (keyStore.hasSharedSecret(session.sessionId)) return true

        val myUserId = tokenManager.getUserId() ?: return false
        return session.responderId == myUserId && keyStore.hasSignedPreKey()
    }

    fun requiresSessionRecreationForSend(session: SessionResponse): Boolean {
        val myUserId = tokenManager.getUserId() ?: return false
        return !keyStore.hasSharedSecret(session.sessionId) && session.initiatorId == myUserId
    }

    private suspend fun getOrEstablishSharedSecretForSend(sessionId: String, peerUsername: String): ByteArray {
        val cached = keyStore.getSharedSecret(sessionId)
        if (cached != null) return cached

        val session = api.getSession(sessionId)
        val myUserId = tokenManager.getUserId()
            ?: error("Not logged in - no user ID available")

        if (session.responderId == myUserId) {
            val signedPreKey = keyStore.getSignedPreKey()
                ?: error("Secure keys unavailable on this device - log in again")

            val ephemeralData = api.getEphemeralKey(sessionId)
            val ephemeralPub = E2EEService.fromBase64(ephemeralData.ephemeralPublicKey)
            val sharedSecret = E2EEService.x3dhRespond(
                signedPreKeyPrivate = signedPreKey.privateKey,
                ephemeralPublicKey = ephemeralPub,
            )

            keyStore.saveSharedSecret(sessionId, sharedSecret)
            Log.d(tag, "sendMessage: recovered responder shared secret for session=$sessionId")
            return sharedSecret
        }

        if (session.initiatorId == myUserId) {
            error("Session with $peerUsername was created on another install or this device lost its local keys. Recreate the session.")
        }

        error("Cannot use session $sessionId for the current user")
    }

    private suspend fun getOrEstablishSharedSecretForReceive(sessionId: String): ByteArray {
        val cached = keyStore.getSharedSecret(sessionId)
        if (cached != null) return cached

        val ephemeralData = api.getEphemeralKey(sessionId)
        val signedPreKey = keyStore.getSignedPreKey()
            ?: error("No signed pre-key found - cannot respond to X3DH")

        val ephemeralPub = E2EEService.fromBase64(ephemeralData.ephemeralPublicKey)
        val sharedSecret = E2EEService.x3dhRespond(
            signedPreKeyPrivate = signedPreKey.privateKey,
            ephemeralPublicKey = ephemeralPub,
        )

        keyStore.saveSharedSecret(sessionId, sharedSecret)
        return sharedSecret
    }

    private fun buildPayload(plaintext: String): ByteArray {
        val rawBytes = plaintext.toByteArray(Charsets.UTF_8)

        if (compressionEnabled) {
            try {
                val compressed = CompressionService.compress(plaintext)
                if (compressed.isNotEmpty() && compressed.size < rawBytes.size) {
                    val bitsPerWord = CompressionService.getBitsPerWord(plaintext, compressed.size)
                    val savings = CompressionService.getSavingsPercent(rawBytes.size, compressed.size)
                    Log.d(tag, "Compression: ${rawBytes.size}B -> ${compressed.size}B (%.1f%% saved, %.1f bits/word)".format(savings, bitsPerWord))

                    return ByteArray(1 + compressed.size).also {
                        it[0] = flagCompressed
                        System.arraycopy(compressed, 0, it, 1, compressed.size)
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Compression failed, falling back to raw", e)
            }
        }

        return ByteArray(1 + rawBytes.size).also {
            it[0] = flagRaw
            System.arraycopy(rawBytes, 0, it, 1, rawBytes.size)
        }
    }

    private fun parsePayload(payload: ByteArray): String {
        require(payload.isNotEmpty()) { "Empty payload after decryption" }

        val flag = payload[0]
        val data = payload.copyOfRange(1, payload.size)

        return when (flag) {
            flagCompressed -> {
                Log.d(tag, "Decompressing ${data.size}B payload")
                CompressionService.decompress(data)
            }
            flagRaw -> String(data, Charsets.UTF_8)
            else -> {
                Log.w(tag, "Unknown payload flag 0x%02X - treating as raw".format(flag))
                String(data, Charsets.UTF_8)
            }
        }
    }

    private suspend fun obfuscateWithServer(
        packedCiphertext: ByteArray,
        peerUsername: String,
    ): SendResult {
        val response = api.obfuscate(
            ObfuscateRequest(
                ciphertextB64 = E2EEService.toBase64(packedCiphertext),
                peerUsername = peerUsername,
            ),
        )
        return SendResult(
            obfuscatedText = response.obfuscatedText,
            providerType = response.provider.toStegoProviderType(),
            usedFallback = response.fallbackUsed,
        )
    }

    private suspend fun deobfuscateWithServer(
        obfuscatedText: String,
        senderUsername: String,
    ): PackedDecryptResult {
        val response = api.deobfuscate(DeobfuscateRequest(obfuscatedText, senderUsername))
        val packedCiphertext = E2EEService.fromBase64(response.ciphertextB64)
        require(packedCiphertext.size >= 3) {
            "Server returned incomplete packed ciphertext (${packedCiphertext.size} bytes)"
        }
        return PackedDecryptResult(
            packedCiphertext = packedCiphertext,
            providerType = response.provider.toStegoProviderType(),
            usedFallback = response.fallbackUsed,
        )
    }

    private fun String?.toStegoProviderType(): StegoProviderType {
        return if (this?.contains("modal", ignoreCase = true) == true) {
            StegoProviderType.MODAL
        } else {
            StegoProviderType.SERVER
        }
    }
}

data class SendResult(
    val obfuscatedText: String,
    val providerType: StegoProviderType,
    val usedFallback: Boolean,
)

data class DecryptResult(
    val plaintext: String,
    val providerType: StegoProviderType,
    val usedFallback: Boolean,
)

private data class PackedDecryptResult(
    val packedCiphertext: ByteArray,
    val providerType: StegoProviderType,
    val usedFallback: Boolean,
)

data class SessionInfo(
    val sessionId: String,
    val peerUsername: String,
)
