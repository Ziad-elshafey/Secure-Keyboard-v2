package dev.patrickgold.florisboard.secure.data.repository

import android.util.Log
import com.google.gson.Gson
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.secure.core.e2ee.E2EEService
import dev.patrickgold.florisboard.secure.data.local.ActiveSessionStore
import dev.patrickgold.florisboard.secure.data.local.AuthTokenManager
import dev.patrickgold.florisboard.secure.data.local.SecureKeyStore
import dev.patrickgold.florisboard.secure.data.remote.CreateSessionRequest
import dev.patrickgold.florisboard.secure.data.remote.DeobfuscateRequest
import dev.patrickgold.florisboard.secure.data.remote.DuplicateSessionConflictResponse
import dev.patrickgold.florisboard.secure.data.remote.LoginRequest
import dev.patrickgold.florisboard.secure.data.remote.ObfuscateRequest
import dev.patrickgold.florisboard.secure.data.remote.RefreshTokenRequest
import dev.patrickgold.florisboard.secure.data.remote.RegisterRequest
import dev.patrickgold.florisboard.secure.data.remote.SecureApiService
import dev.patrickgold.florisboard.secure.data.remote.SessionResponse
import dev.patrickgold.florisboard.secure.data.remote.StegoDecodeApiService
import dev.patrickgold.florisboard.secure.data.remote.StegoDecodeRequest
import dev.patrickgold.florisboard.secure.data.remote.StegoEncodeApiService
import dev.patrickgold.florisboard.secure.data.remote.StegoEncodeRequest
import dev.patrickgold.florisboard.secure.data.remote.UploadKeysRequest
import dev.patrickgold.florisboard.secure.data.remote.UserSearchResult
import dev.patrickgold.florisboard.secure.data.repository.compression.CompressionService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

class SecureMessagingRepository(
    private val api: SecureApiService,
    private val stegoEncodeApi: StegoEncodeApiService,
    private val stegoDecodeApi: StegoDecodeApiService,
    private val tokenManager: AuthTokenManager,
    private val keyStore: SecureKeyStore,
    private val activeSessionStore: ActiveSessionStore,
) {
    companion object {
        private const val tag = "SecureMessagingRepo"
        const val flagRaw: Byte = 0x00
        const val flagCompressed: Byte = 0x01
        private const val defaultStegoContext = "car"
        const val localSecureIdentityMissingMessage =
            "Local secure identity missing on this device. Reuse the original installation or deactivate old sessions before creating a replacement."
        const val activeSecureSessionExistsMessage =
            "Active secure session already exists. Reuse it or deactivate it first."
        const val historicalSessionKeyMissingMessage =
            "This device no longer has the original session key for this message."
        private const val historicalDecryptOnlyMessage = "Decrypt old messages only"
        private const val recreateOnThisDeviceMessage = "Deactivate and recreate on this device"
        private const val sendNotReadyMessage = "Send not ready on this device"
        private val gson = Gson()
    }

    private val compressionEnabled: Boolean = false

    private val sessionCreationMutex = Mutex()

    suspend fun register(username: String, password: String): Result<String> = runCatching {
        val email = "$username@example.com"
        val response = api.register(RegisterRequest(username, email, password))
        val identityKeyPair = E2EEService.generateIdentityKeyPair()
        val signedPreKey = E2EEService.generateSignedPreKey(1, identityKeyPair.privateKey)

        try {
            tokenManager.saveTokens(response.accessToken, response.refreshToken)
            tokenManager.saveUserInfo(response.userId, response.username)
            keyStore.setActiveUser(response.userId)

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
        } catch (e: Exception) {
            clearLocalSecureState()
            throw e
        }

        response.userId
    }

    suspend fun login(username: String, password: String): Result<String> = runCatching {
        val response = api.login(LoginRequest(username, password))
        try {
            tokenManager.saveTokens(response.accessToken, response.refreshToken)

            val user = api.getCurrentUser()
            tokenManager.saveUserInfo(user.userId, user.username)
            keyStore.setActiveUser(user.userId)

            if (!hasLocalSecureIdentity()) {
                val keyStatus = api.getKeyStatus()
                if (keyStatus.hasIdentityKey || keyStatus.hasSignedPrekey) {
                    debugLog { "login: server keys exist but local secure identity is missing" }
                } else {
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
            }

            user.userId
        } catch (e: Exception) {
            clearLocalSecureState()
            throw e
        }
    }

    private fun hasLocalSecureIdentity(): Boolean =
        keyStore.hasIdentityKeys() && keyStore.hasSignedPreKey()

    fun isLocalSecureIdentityMissing(): Boolean =
        tokenManager.isLoggedIn() && !hasLocalSecureIdentity()

    private fun requireLocalSecureIdentityAvailable() {
        if (!hasLocalSecureIdentity()) {
            error(localSecureIdentityMissingMessage)
        }
    }

    private fun parseDuplicateSessionConflict(exception: HttpException): DuplicateSessionConflictResponse? {
        val payload = exception.response()?.errorBody()?.string()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            gson.fromJson(payload, DuplicateSessionConflictResponse::class.java)
        }.getOrNull()
    }

    fun isLoggedIn(): Boolean = tokenManager.isLoggedIn()

    fun getUsername(): String? = tokenManager.getUsername()

    fun getUserId(): String? = tokenManager.getUserId()

    suspend fun logout(): Result<Unit> = runCatching {
        var remoteFailure: Throwable? = null
        if (tokenManager.isLoggedIn()) {
            runCatching { api.logout() }
                .onFailure { remoteFailure = it }
        }
        clearLocalSecureState()
        remoteFailure?.let { throw it }
    }

    suspend fun searchUsers(query: String): Result<List<UserSearchResult>> = runCatching {
        api.searchUsers(query)
    }

    suspend fun createSession(peerUsername: String, peerUserId: String): Result<SessionInfo> =
        sessionCreationMutex.withLock {
            runCatching {
                requireLocalSecureIdentityAvailable()
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

                val session = try {
                    api.createSession(
                        CreateSessionRequest(
                            peerUsername = peerUsername,
                            ephemeralPublicKey = E2EEService.toBase64(x3dhInitResult.ephemeralPublicKey),
                        ),
                    )
                } catch (e: HttpException) {
                    if (e.code() == 409) {
                        val conflict = parseDuplicateSessionConflict(e)
                        if (conflict != null) {
                            val conflictPeerUsername = if (conflict.initiatorId == myUserId) {
                                conflict.responderUsername
                            } else {
                                conflict.initiatorUsername
                            }
                            if (keyStore.hasSharedSecret(conflict.existingSessionId)) {
                                debugLog { "createSession: reusing existing active session already cached locally" }
                                return@runCatching SessionInfo(
                                    sessionId = conflict.existingSessionId,
                                    peerUsername = conflictPeerUsername,
                                    reusedExisting = true,
                                )
                            }
                            throw DuplicateSessionConflictException(conflict)
                        }
                    }
                    throw e
                }

                val weAreInitiator = session.initiatorId == myUserId
                debugLog { "createSession: derived role for current device" }

                val sharedSecret = if (weAreInitiator) {
                    keyStore.saveEphemeralPublicKey(session.sessionId, x3dhInitResult.ephemeralPublicKey)
                    debugLog { "createSession: initiator material persisted locally" }
                    x3dhInitResult.sharedSecret
                } else {
                    val initiatorEphPub = fetchInitiatorEphemeralKeyForSession(session.sessionId, peerUsername)

                    val signedPreKey = keyStore.getSignedPreKey()
                        ?: error("No signed pre-key found - cannot respond to X3DH")

                    E2EEService.x3dhRespond(
                        signedPreKeyPrivate = signedPreKey.privateKey,
                        ephemeralPublicKey = initiatorEphPub,
                    ).also {
                        debugLog { "createSession: responder shared secret derived" }
                    }
                }

                keyStore.saveSharedSecret(session.sessionId, sharedSecret)

                SessionInfo(
                    sessionId = session.sessionId,
                    peerUsername = peerUsername,
                )
            }
        }

    suspend fun listSessions(activeOnly: Boolean = true): Result<List<SessionResponse>> = runCatching {
        api.listSessions(activeOnly = activeOnly)
    }

    suspend fun deactivateSession(sessionId: String): Result<Unit> = runCatching {
        api.deactivateSession(sessionId)
        keyStore.removeEphemeralPublicKey(sessionId)
        activeSessionStore.clearIfSessionMatches(sessionId)
    }

    suspend fun sendMessage(sessionId: String, peerUsername: String, plaintext: String): Result<SendResult> = runCatching {
        requireLocalSecureIdentityAvailable()
        val sharedSecret = getOrEstablishSharedSecretForSend(sessionId, peerUsername)

        val counterResp = api.getNextCounter(sessionId)
        val counter = counterResp.counter
        debugLog { "sendMessage: acquired outbound counter" }

        val payload = buildPayload(plaintext)
        val encrypted = E2EEService.encryptBytes(sharedSecret, payload, counter)
        val packed = E2EEService.packCiphertextWithNonceAndCounter(encrypted.ciphertext, encrypted.nonce, counter)
        val packedBits = packed.toBitString()

        debugLog { "sendMessage: packed authenticated payload" }

        val obfuscatedText = try {
            stegoEncodeApi.encode(
                StegoEncodeRequest(
                    context = buildStegoContext(),
                    bits = packedBits,
                ),
            ).text
        } catch (e: Exception) {
            warnLog("Modal stego encode failed, falling back to server obfuscation", e)
            api.obfuscate(
                ObfuscateRequest(
                    ciphertextB64 = E2EEService.toBase64(packed),
                    peerUsername = peerUsername,
                    sessionId = sessionId,
                ),
            ).obfuscatedText
        }

        SendResult(obfuscatedText = obfuscatedText)
    }

    suspend fun decryptMessage(
        obfuscatedText: String,
        senderUsername: String,
        preferredSessionId: String? = null,
    ): Result<String> = runCatching {
        requireLocalSecureIdentityAvailable()
        val packed = decodePackedMessage(
            obfuscatedText = obfuscatedText,
            senderUsername = senderUsername,
            preferredSessionId = preferredSessionId,
        )

        val sessions = api.listSessions(activeOnly = preferredSessionId.isNullOrBlank())
        val myUserId = tokenManager.getUserId()
            ?: error("Not logged in - no user ID available")
        val matchingSessions = sessions.filter { session ->
            peerUsernameForSession(session, myUserId).equals(senderUsername, ignoreCase = true)
        }
        val session = if (!preferredSessionId.isNullOrBlank()) {
            matchingSessions.firstOrNull { it.sessionId == preferredSessionId }
                ?: run {
                    activeSessionStore.clearIfSessionMatches(preferredSessionId)
                    error("No session found with $senderUsername")
                }
        } else {
            when (matchingSessions.size) {
                0 -> error("No active session found with $senderUsername")
                1 -> matchingSessions.single()
                else -> error("Multiple active sessions found with $senderUsername. Select the intended session first.")
            }
        }

        val sharedSecret = getOrEstablishSharedSecretForReceive(session)
        val envelope = E2EEService.unpackMessageEnvelope(packed)
        val payload = if (envelope.usesAead) {
            E2EEService.decryptToBytes(
                sharedSecret = sharedSecret,
                ciphertext = envelope.ciphertext,
                nonce = checkNotNull(envelope.nonce),
                counter = envelope.counter,
            )
        } else {
            E2EEService.chacha20Decrypt(envelope.ciphertext, sharedSecret, envelope.counter)
        }
        val plaintext = parsePayload(payload)
        debugLog { "decryptMessage: completed using ${if (envelope.usesAead) "authenticated" else "legacy"} format" }
        plaintext
    }.onFailure { e ->
        errorLog("decryptMessage failed", e)
    }

    fun canSendToSession(session: SessionResponse): Boolean {
        if (isLocalSecureIdentityMissing()) return false
        if (!session.isActive) return false
        if (keyStore.hasSharedSecret(session.sessionId)) return true

        val myUserId = tokenManager.getUserId() ?: return false
        return session.responderId == myUserId && keyStore.hasSignedPreKey() && session.lastCounter == 0
    }

    fun requiresSessionRecreationForSend(session: SessionResponse): Boolean {
        if (isLocalSecureIdentityMissing()) return false
        if (!session.isActive) return false
        val myUserId = tokenManager.getUserId() ?: return false
        if (keyStore.hasSharedSecret(session.sessionId)) return false
        return session.initiatorId == myUserId ||
            (session.responderId == myUserId && session.lastCounter > 0)
    }

    fun canDecryptHistoricalSession(session: SessionResponse): Boolean =
        !session.isActive && !isLocalSecureIdentityMissing() && keyStore.hasSharedSecret(session.sessionId)

    fun canSelectSession(session: SessionResponse): Boolean {
        if (isLocalSecureIdentityMissing()) return false
        return if (session.isActive) {
            canSendToSession(session)
        } else {
            canDecryptHistoricalSession(session)
        }
    }

    fun describeSessionStatus(session: SessionResponse): String? {
        if (isLocalSecureIdentityMissing()) {
            return localSecureIdentityMissingMessage
        }
        if (!session.isActive) {
            return if (keyStore.hasSharedSecret(session.sessionId)) {
                historicalDecryptOnlyMessage
            } else {
                historicalSessionKeyMissingMessage
            }
        }
        if (requiresSessionRecreationForSend(session)) {
            return recreateOnThisDeviceMessage
        }
        if (!canSendToSession(session)) {
            return sendNotReadyMessage
        }
        return null
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

            val ephemeralPub = fetchInitiatorEphemeralKeyForSession(sessionId, peerUsername)
            val sharedSecret = E2EEService.x3dhRespond(
                signedPreKeyPrivate = signedPreKey.privateKey,
                ephemeralPublicKey = ephemeralPub,
            )

            keyStore.saveSharedSecret(sessionId, sharedSecret)
            debugLog { "sendMessage: recovered responder shared secret" }
            return sharedSecret
        }

        if (session.initiatorId == myUserId) {
            error("Session with $peerUsername was created on another install or this device lost its local keys. Recreate the session.")
        }

        error("Cannot use session $sessionId for the current user")
    }

    private suspend fun getOrEstablishSharedSecretForReceive(session: SessionResponse): ByteArray {
        val cached = keyStore.getSharedSecret(session.sessionId)
        if (cached != null) return cached

        if (!session.isActive) {
            error(historicalSessionKeyMissingMessage)
        }

        val signedPreKey = keyStore.getSignedPreKey()
            ?: error("No signed pre-key found - cannot respond to X3DH")

        val ephemeralPub = fetchInitiatorEphemeralKeyForSession(
            sessionId = session.sessionId,
            peerUsername = null,
        )
        val sharedSecret = E2EEService.x3dhRespond(
            signedPreKeyPrivate = signedPreKey.privateKey,
            ephemeralPublicKey = ephemeralPub,
        )

        keyStore.saveSharedSecret(session.sessionId, sharedSecret)
        return sharedSecret
    }

    private fun buildPayload(plaintext: String): ByteArray {
        val rawBytes = plaintext.toByteArray(Charsets.UTF_8)

        if (compressionEnabled) {
            try {
                val compressed = CompressionService.compress(plaintext)
                if (compressed.isNotEmpty() && compressed.size < rawBytes.size) {
                    debugLog { "buildPayload: compressed payload selected" }

                    return ByteArray(1 + compressed.size).also {
                        it[0] = flagCompressed
                        System.arraycopy(compressed, 0, it, 1, compressed.size)
                    }
                }
            } catch (e: Exception) {
                warnLog("Compression failed, falling back to raw payload", e)
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
                debugLog { "parsePayload: decompressing payload" }
                CompressionService.decompress(data)
            }
            flagRaw -> String(data, Charsets.UTF_8)
            else -> error("Unsupported payload flag 0x%02X".format(flag))
        }
    }

    private fun ByteArray.toBitString(): String =
        joinToString(separator = "") { byte ->
            String.format("%8s", (byte.toInt() and 0xFF).toString(2)).replace(' ', '0')
        }

    private fun bitStringToByteArray(bits: String): ByteArray {
        val normalizedBits = bits.filterNot { it.isWhitespace() }
        require(normalizedBits.isNotBlank()) { "Decoded bitstring is empty" }
        require(normalizedBits.all { it == '0' || it == '1' }) {
            "Decoded bitstring contains invalid chars"
        }
        require(normalizedBits.length % 8 == 0) {
            "Decoded bitstring length must be a multiple of 8, got ${normalizedBits.length}"
        }

        return ByteArray(normalizedBits.length / 8) { index ->
            normalizedBits.substring(index * 8, index * 8 + 8).toInt(2).toByte()
        }
    }

    private fun buildStegoContext(): String = defaultStegoContext

    private suspend fun decodePackedMessage(
        obfuscatedText: String,
        senderUsername: String,
        preferredSessionId: String?,
    ): ByteArray {
        decodeLocalBase64PackedMessage(obfuscatedText)?.let {
            debugLog { "decodePackedMessage: decoded compact base64 transport locally" }
            return it
        }

        val modalPacked = runCatching {
            validatePackedMessage(
                bitStringToByteArray(stegoDecodeApi.decode(StegoDecodeRequest(text = obfuscatedText)).bits),
            )
        }.getOrElse { e ->
            warnLog("Modal stego decode failed, falling back to server deobfuscation", e)
            return validatePackedMessage(
                E2EEService.fromBase64(
                    api.deobfuscate(
                        DeobfuscateRequest(
                            obfuscatedText = obfuscatedText,
                            senderUsername = senderUsername,
                            sessionId = preferredSessionId,
                        ),
                    ).ciphertextB64,
                ),
            )
        }

        return modalPacked
    }

    private fun decodeLocalBase64PackedMessage(obfuscatedText: String): ByteArray? {
        val compactText = obfuscatedText.trim()
        if (compactText.isEmpty()) return null
        if (compactText.any { it.isWhitespace() }) return null

        return runCatching {
            validatePackedMessage(E2EEService.fromBase64(compactText))
        }.getOrNull()
    }

    private fun validatePackedMessage(packed: ByteArray): ByteArray {
        E2EEService.unpackMessageEnvelope(packed)
        return packed
    }

    private fun clearLocalSecureState() {
        activeSessionStore.clear()
        if (keyStore.hasActiveUser()) {
            keyStore.clearSessionMaterialForActiveUser()
            keyStore.clearActiveUser()
        }
        tokenManager.clearAll()
    }

    private fun peerUsernameForSession(session: SessionResponse, myUserId: String): String =
        if (session.initiatorId == myUserId) {
            session.responderUsername
        } else {
            session.initiatorUsername
        }

    private suspend fun fetchInitiatorEphemeralKeyForSession(
        sessionId: String,
        peerUsername: String?,
    ): ByteArray {
        return try {
            val ephemeralData = api.getEphemeralKey(sessionId)
            E2EEService.fromBase64(ephemeralData.ephemeralPublicKey)
        } catch (_: Exception) {
            val peerLabel = peerUsername?.takeIf { it.isNotBlank() } ?: "this peer"
            error("Session with $peerLabel must be recreated on this device")
        }
    }

    private inline fun debugLog(message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message())
        }
    }

    private fun warnLog(message: String, throwable: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
    }

    private fun errorLog(message: String, throwable: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}

data class SendResult(
    val obfuscatedText: String,
)

data class SessionInfo(
    val sessionId: String,
    val peerUsername: String,
    val reusedExisting: Boolean = false,
)

class DuplicateSessionConflictException(
    val conflict: DuplicateSessionConflictResponse,
) : IllegalStateException(conflict.detail)
