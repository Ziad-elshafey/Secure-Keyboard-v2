package dev.patrickgold.florisboard.secure.data.repository

import dev.patrickgold.florisboard.secure.core.StegoProviderType
import dev.patrickgold.florisboard.secure.core.ActiveSecureContact
import dev.patrickgold.florisboard.secure.core.SecureContact
import dev.patrickgold.florisboard.secure.core.SecureSessionSelection
import dev.patrickgold.florisboard.secure.core.e2ee.E2EEService
import dev.patrickgold.florisboard.secure.data.local.AuthTokenManager
import dev.patrickgold.florisboard.secure.data.local.SecureContactStore
import dev.patrickgold.florisboard.secure.data.local.SecureKeyStore
import dev.patrickgold.florisboard.secure.data.remote.CreateSessionRequest
import dev.patrickgold.florisboard.secure.data.remote.DeobfuscateRequest
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
import android.util.Log

class SecureMessagingRepository(
    private val api: SecureApiService,
    private val stegoEncodeApi: StegoEncodeApiService,
    private val stegoDecodeApi: StegoDecodeApiService,
    private val tokenManager: AuthTokenManager,
    private val keyStore: SecureKeyStore,
    private val contactStore: SecureContactStore,
) {
    companion object {
        private const val tag = "SecureMessagingRepo"
        const val flagRaw: Byte = 0x00
        const val flagCompressed: Byte = 0x01
        private const val defaultStegoContext = "car"
    }

    /** Off: custom arithmetic compression disabled; raw UTF-8 payloads only (0x00). */
    private val compressionEnabled: Boolean = false

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

        val keyStatus = api.getKeyStatus()
        if (!keyStore.hasIdentityKeys() || !keyStatus.hasIdentityKey || !keyStatus.hasSignedPrekey) {
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
            keyStore.clearActiveUser()
        }
        tokenManager.clearAll()
    }

    suspend fun searchUsers(query: String): Result<List<UserSearchResult>> = runCatching {
        api.searchUsers(query)
    }

    suspend fun addContactFromSearchResult(user: UserSearchResult): Result<SecureContact> = runCatching {
        val activeUserId = tokenManager.getUserId()
            ?: error("Not logged in - cannot add contact")
        val verified = api.searchUsers(user.username)
            .firstOrNull { it.userId == user.userId && it.username.equals(user.username, ignoreCase = true) }
            ?: error("User no longer available: ${user.username}")
        val contact = SecureContact(
            userId = verified.userId,
            username = verified.username,
            displayName = verified.displayName,
        )
        contactStore.upsertContact(activeUserId, contact)
        contact
    }

    suspend fun listContacts(): Result<List<SecureContact>> = runCatching {
        val activeUserId = tokenManager.getUserId()
            ?: error("Not logged in - cannot list contacts")
        contactStore.listContacts(activeUserId)
    }

    suspend fun removeContact(username: String): Result<Unit> = runCatching {
        val activeUserId = tokenManager.getUserId()
            ?: error("Not logged in - cannot remove contact")
        contactStore.removeContact(activeUserId, username)
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
        val packedBits = packed.toBitString()

        Log.d(
            tag,
            "sendMessage: raw=${plaintext.toByteArray().size}B, payload=${payload.size}B (flag=0x%02X), cipher=${ciphertext.size}B, packed=${packed.size}B, counter=$counter".format(payload[0]),
        )

        runCatching {
            obfuscateWithServer(packed, peerUsername)
        }.fold(
            onSuccess = { sendResult ->
                Log.d(
                    tag,
                    "sendMessage: obfuscated through server provider=${sendResult.providerType} fallback=${sendResult.usedFallback}",
                )
                sendResult
            },
            onFailure = { e ->
                Log.w(tag, "Server obfuscation failed (${e.message}) - falling back to Modal encode")
                obfuscateWithModal(packedBits)
            },
        )
    }

    suspend fun sendMessageToUser(peerUsername: String, plaintext: String): Result<SendResult> = runCatching {
        val myUsername = tokenManager.getUsername()
        require(!peerUsername.equals(myUsername, ignoreCase = true)) { "Cannot send a message to yourself." }

        val sessions = api.listSessions(activeOnly = true)
        val existing = sessions
            .filter { s ->
                (s.initiatorUsername.equals(peerUsername, ignoreCase = true)
                    || s.responderUsername.equals(peerUsername, ignoreCase = true))
                    && !s.initiatorUsername.equals(s.responderUsername, ignoreCase = true)
            }
            .sortedByDescending { it.createdAt }
            .firstOrNull()

        val sessionId: String
        if (existing != null) {
            sessionId = existing.sessionId
            if (keyStore.getSharedSecret(sessionId) == null) {
                getOrEstablishSharedSecretForSend(sessionId, peerUsername)
            }
        } else {
            val users = api.searchUsers(peerUsername)
            val target = users.firstOrNull { it.username.equals(peerUsername, ignoreCase = true) }
                ?: error("User '$peerUsername' not found.")
            val session = createSession(peerUsername, target.userId).getOrThrow()
            sessionId = session.sessionId
        }

        sendMessage(sessionId, peerUsername, plaintext).getOrThrow()
    }

    suspend fun ensureSessionForContact(contact: ActiveSecureContact): Result<SecureSessionSelection> = runCatching {
        val peerUsername = contact.username
        val activeSessions = api.listSessions(activeOnly = true)
        val existing = activeSessions
            .filter { s ->
                (s.initiatorUsername.equals(peerUsername, ignoreCase = true)
                    || s.responderUsername.equals(peerUsername, ignoreCase = true))
                    && !s.initiatorUsername.equals(s.responderUsername, ignoreCase = true)
            }
            .sortedByDescending { it.createdAt }
            .firstOrNull()

        if (existing != null) {
            if (keyStore.getSharedSecret(existing.sessionId) == null) {
                getOrEstablishSharedSecretForSend(existing.sessionId, peerUsername)
            }
            return@runCatching SecureSessionSelection(
                sessionId = existing.sessionId,
                recipientName = peerUsername,
            )
        }

        val resolvedUser = if (contact.userId.isNotBlank()) {
            UserSearchResult(
                userId = contact.userId,
                username = contact.username,
                displayName = contact.displayName,
                isActive = true,
            )
        } else {
            api.searchUsers(peerUsername)
                .firstOrNull { it.username.equals(peerUsername, ignoreCase = true) }
                ?: error("User '$peerUsername' not found.")
        }

        val session = createSession(resolvedUser.username, resolvedUser.userId).getOrThrow()
        SecureSessionSelection(
            sessionId = session.sessionId,
            recipientName = session.peerUsername,
        )
    }

    suspend fun decryptMessage(obfuscatedText: String, senderUsername: String): Result<DecryptResult> = runCatching {
        Log.d(tag, "decryptMessage: sender=$senderUsername")

        val decoded = runCatching {
            deobfuscateWithServer(obfuscatedText, senderUsername)
        }.fold(
            onSuccess = { decryptResult ->
                Log.d(
                    tag,
                    "decryptMessage: deobfuscated through server provider=${decryptResult.providerType} fallback=${decryptResult.usedFallback}",
                )
                decryptResult
            },
            onFailure = { e ->
                Log.w(tag, "Server deobfuscation failed (${e.message}) - falling back to Modal decode")
                deobfuscateWithModal(obfuscatedText)
            },
        )

        val sessions = api.listSessions(activeOnly = true)
        val candidates = sessions
            .filter { s -> s.initiatorUsername == senderUsername || s.responderUsername == senderUsername }
            .sortedByDescending { it.createdAt }

        check(candidates.isNotEmpty()) { "No active session found with $senderUsername" }
        Log.d(tag, "decryptMessage: ${candidates.size} candidate session(s) for sender=$senderUsername")

        var lastError: Throwable? = null
        for (session in candidates) {
            try {
                val sharedSecret = getOrEstablishSharedSecretForReceive(session.sessionId)
                val (ciphertext, counter) = E2EEService.unpackCiphertextAndCounter(decoded.packedCiphertext)
                Log.d(tag, "decryptMessage: trying session=${session.sessionId} ciphertext=${ciphertext.size}B counter=$counter")
                val payload = E2EEService.chacha20Decrypt(ciphertext, sharedSecret, counter)
                val plaintext = parsePayload(payload)
                Log.d(tag, "decryptMessage: success session=${session.sessionId} plaintext=${plaintext.take(50)}...")
                return@runCatching DecryptResult(
                    plaintext = plaintext,
                    providerType = decoded.providerType,
                    usedFallback = decoded.usedFallback,
                )
            } catch (e: Exception) {
                Log.w(tag, "decryptMessage: session=${session.sessionId} failed: ${e.message}")
                lastError = e
            }
        }
        error("Failed to decrypt message from $senderUsername with ${candidates.size} active session(s): ${lastError?.message}")
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
            runCatching { api.deactivateSession(sessionId) }
            val peerId = session.responderId
            val resolvedPeerUsername = session.responderUsername
            val newSession = createSession(resolvedPeerUsername, peerId).getOrThrow()
            return keyStore.getSharedSecret(newSession.sessionId)
                ?: error("Failed to establish shared secret after session re-key")
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

    /** Server Modal path expects 2-byte BE length + packed (ciphertext||counter). */
    private fun framePackedForServerObfuscation(packed: ByteArray): ByteArray {
        val n = packed.size
        require(n in 1..65535) { "packed size $n out of range for 16-bit length prefix" }
        return byteArrayOf((n shr 8).toByte(), (n and 0xFF).toByte()) + packed
    }

    /** Remove framing when `size == 2 + declaredLength`; else pass through (Modal / legacy). */
    private fun unpackServerObfuscationCiphertextBytes(data: ByteArray): ByteArray {
        if (data.size < 3) return data
        val declared = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        if (declared in 1..65535 && data.size == 2 + declared) {
            return data.copyOfRange(2, 2 + declared)
        }
        return data
    }

    private suspend fun obfuscateWithServer(
        packedCiphertext: ByteArray,
        peerUsername: String,
    ): SendResult {
        val framed = framePackedForServerObfuscation(packedCiphertext)
        val response = api.obfuscate(
            ObfuscateRequest(
                ciphertextB64 = E2EEService.toBase64(framed),
                peerUsername = peerUsername,
            ),
        )
        return SendResult(
            obfuscatedText = response.obfuscatedText,
            providerType = response.provider.toStegoProviderType(),
            usedFallback = response.fallbackUsed,
        )
    }

    private suspend fun obfuscateWithModal(packedBits: String): SendResult {
        val obfuscatedText = stegoEncodeApi.encode(
            StegoEncodeRequest(
                context = buildStegoContext(),
                bits = packedBits,
            ),
        ).text
        return SendResult(
            obfuscatedText = obfuscatedText,
            providerType = StegoProviderType.MODAL,
            usedFallback = true,
        )
    }

    private suspend fun deobfuscateWithServer(
        obfuscatedText: String,
        senderUsername: String,
    ): PackedDecryptResult {
        val response = api.deobfuscate(DeobfuscateRequest(obfuscatedText, senderUsername))
        val raw = E2EEService.fromBase64(response.ciphertextB64)
        val packedCiphertext = unpackServerObfuscationCiphertextBytes(raw)
        require(packedCiphertext.size >= 3) {
            "Server returned incomplete packed ciphertext (${packedCiphertext.size} bytes)"
        }
        return PackedDecryptResult(
            packedCiphertext = packedCiphertext,
            providerType = response.provider.toStegoProviderType(),
            usedFallback = response.fallbackUsed,
        )
    }

    private suspend fun deobfuscateWithModal(obfuscatedText: String): PackedDecryptResult {
        return PackedDecryptResult(
            packedCiphertext = bitStringToByteArray(
                stegoDecodeApi.decode(StegoDecodeRequest(text = obfuscatedText)).bits,
            ),
            providerType = StegoProviderType.MODAL,
            usedFallback = true,
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
