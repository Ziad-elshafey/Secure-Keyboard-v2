package dev.patrickgold.florisboard.secure

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.inputmethod.InputConnection
import android.view.inputmethod.ExtractedTextRequest
import android.widget.Toast
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.secure.core.DecryptCaptureState
import dev.patrickgold.florisboard.secure.core.ManagedSecureSession
import dev.patrickgold.florisboard.secure.core.SecureOperationResult
import dev.patrickgold.florisboard.secure.core.SecureSessionSelection
import dev.patrickgold.florisboard.secure.core.SecureSessionState
import dev.patrickgold.florisboard.secure.data.local.AuthTokenManager
import dev.patrickgold.florisboard.secure.data.local.SecureKeyStore
import dev.patrickgold.florisboard.secure.data.local.SecureSessionStore
import dev.patrickgold.florisboard.secure.data.remote.AuthInterceptor
import dev.patrickgold.florisboard.secure.data.remote.SecureApiService
import dev.patrickgold.florisboard.secure.data.remote.SessionResponse
import dev.patrickgold.florisboard.secure.data.remote.StegoDecodeApiService
import dev.patrickgold.florisboard.secure.data.remote.StegoEncodeApiService
import dev.patrickgold.florisboard.secure.data.remote.TokenRefreshAuthenticator
import dev.patrickgold.florisboard.secure.data.remote.UserSearchResult
import dev.patrickgold.florisboard.secure.data.repository.DecryptResult
import dev.patrickgold.florisboard.secure.data.repository.SecureMessagingRepository
import dev.patrickgold.florisboard.secure.data.repository.SendResult
import dev.patrickgold.florisboard.secure.data.repository.compression.CompressionService
import dev.patrickgold.florisboard.secure.ui.DecryptResultActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class SecureMessagingManager(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val loggingLevel = if (BuildConfig.DEBUG) {
        HttpLoggingInterceptor.Level.HEADERS
    } else {
        HttpLoggingInterceptor.Level.NONE
    }

    val tokenManager: AuthTokenManager by lazy {
        AuthTokenManager(appContext)
    }

    val keyStore: SecureKeyStore by lazy {
        SecureKeyStore(appContext)
    }

    val sessionStore: SecureSessionStore by lazy {
        SecureSessionStore(appContext)
    }

    private val authInterceptor: AuthInterceptor by lazy {
        AuthInterceptor(tokenManager)
    }

    private val secureRefreshRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(SECURE_API_BASE_URL)
            .client(
                OkHttpClient.Builder()
                    .addInterceptor(HttpLoggingInterceptor().apply { level = loggingLevel })
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build(),
            )
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    private val secureApiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(HttpLoggingInterceptor().apply { level = loggingLevel })
            .authenticator(
                TokenRefreshAuthenticator(tokenManager) {
                    secureRefreshRetrofit.create(SecureApiService::class.java)
                },
            )
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val stegoClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply { level = loggingLevel })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val secureApiService: SecureApiService by lazy {
        Retrofit.Builder()
            .baseUrl(SECURE_API_BASE_URL)
            .client(secureApiClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SecureApiService::class.java)
    }

    val stegoEncodeApiService: StegoEncodeApiService by lazy {
        Retrofit.Builder()
            .baseUrl(STEGO_ENCODE_BASE_URL)
            .client(stegoClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(StegoEncodeApiService::class.java)
    }

    val stegoDecodeApiService: StegoDecodeApiService by lazy {
        Retrofit.Builder()
            .baseUrl(STEGO_DECODE_BASE_URL)
            .client(stegoClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(StegoDecodeApiService::class.java)
    }

    val secureMessagingRepository: SecureMessagingRepository by lazy {
        CompressionService.initialize(appContext)
        SecureMessagingRepository(
            api = secureApiService,
            stegoEncodeApi = stegoEncodeApiService,
            stegoDecodeApi = stegoDecodeApiService,
            tokenManager = tokenManager,
            keyStore = keyStore,
        )
    }

    private val activeEditorInstance
        get() = appContext.editorInstance().value

    companion object {
        private const val TAG = "SecureMessagingMgr"
        private const val SECURE_API_BASE_URL = BuildConfig.SECURE_API_BASE_URL
        private const val STEGO_ENCODE_BASE_URL = BuildConfig.STEGO_ENCODE_BASE_URL
        private const val STEGO_DECODE_BASE_URL = BuildConfig.STEGO_DECODE_BASE_URL
    }

    fun isReady(): Boolean {
        return try {
            secureMessagingRepository.isLoggedIn() && sessionStore.hasActiveSession()
        } catch (_: Exception) {
            false
        }
    }

    fun isLoggedIn(): Boolean {
        return try {
            secureMessagingRepository.isLoggedIn()
        } catch (_: Exception) {
            false
        }
    }

    fun getUsername(): String? = secureMessagingRepository.getUsername()

    fun getUserId(): String? = secureMessagingRepository.getUserId()

    fun getActiveSessionSelection(): SecureSessionSelection? = sessionStore.getActiveSession()

    fun setActiveSession(sessionId: String, recipientName: String) {
        sessionStore.setActiveSession(sessionId = sessionId, recipientName = recipientName)
    }

    fun clearActiveSession() {
        sessionStore.clearActiveSession()
    }

    fun getSecureSessionState(lastOperation: SecureOperationResult? = null): SecureSessionState {
        val activeSession = sessionStore.getActiveSession()
        val loggedIn = isLoggedIn()
        return SecureSessionState(
            isLoggedIn = loggedIn,
            activeSessionId = activeSession?.sessionId,
            activeRecipientName = activeSession?.recipientName,
            isSessionReady = loggedIn && activeSession != null,
            recoveryHint = when {
                !loggedIn -> appContext.getString(R.string.secure__login_required)
                activeSession == null -> appContext.getString(R.string.secure__no_session)
                else -> null
            },
            lastOperation = lastOperation,
        )
    }

    suspend fun resolveSecureSessionState(
        lastOperation: SecureOperationResult? = null,
    ): SecureSessionState {
        val activeSession = getActiveSessionSelection()
        if (!isLoggedIn() || activeSession == null) {
            return getSecureSessionState(lastOperation)
        }

        val managedSession = listManagedSessions().getOrNull()
            ?.firstOrNull { it.sessionId == activeSession.sessionId }

        return SecureSessionState(
            isLoggedIn = true,
            activeSessionId = activeSession.sessionId,
            activeRecipientName = activeSession.recipientName,
            isSessionReady = managedSession?.canSend ?: true,
            recoveryHint = managedSession?.recoveryHint,
            lastOperation = lastOperation,
        )
    }

    suspend fun login(username: String, password: String): Result<String> {
        return secureMessagingRepository.login(username, password)
    }

    suspend fun register(username: String, password: String): Result<String> {
        return secureMessagingRepository.register(username, password)
    }

    fun logout() {
        secureMessagingRepository.logout()
        clearActiveSession()
    }

    suspend fun searchUsers(query: String): Result<List<UserSearchResult>> {
        return secureMessagingRepository.searchUsers(query)
    }

    suspend fun listManagedSessions(): Result<List<ManagedSecureSession>> {
        val activeSessionId = getActiveSessionSelection()?.sessionId
        return secureMessagingRepository.listSessions().map { sessions ->
            sessions
                .filter { it.isActive }
                .map { session ->
                    buildManagedSession(
                        session = session,
                        activeSessionId = activeSessionId,
                    )
                }
        }
    }

    suspend fun findManagedSession(peerUsername: String): ManagedSecureSession? {
        return listManagedSessions().getOrNull()?.firstOrNull {
            it.peerUsername.equals(peerUsername, ignoreCase = true)
        }
    }

    suspend fun createSession(peerUsername: String, peerUserId: String): Result<SecureSessionSelection> {
        return secureMessagingRepository.createSession(peerUsername, peerUserId).map { sessionInfo ->
            SecureSessionSelection(
                sessionId = sessionInfo.sessionId,
                recipientName = sessionInfo.peerUsername,
            ).also { selection ->
                setActiveSession(selection.sessionId, selection.recipientName)
            }
        }
    }

    suspend fun deactivateSession(sessionId: String): Result<Unit> {
        return secureMessagingRepository.deactivateSession(sessionId).onSuccess {
            if (getActiveSessionSelection()?.sessionId == sessionId) {
                clearActiveSession()
            }
        }
    }

    suspend fun encryptWithActiveSession(plaintext: String): Result<SendResult> {
        val activeSession = getActiveSessionSelection()
            ?: return Result.failure(IllegalStateException(appContext.getString(R.string.secure__no_session)))
        return encryptForSession(activeSession, plaintext)
    }

    suspend fun encryptForPeer(peerUsername: String, plaintext: String): Result<SendResult> {
        val session = findManagedSession(peerUsername)
            ?: return Result.failure(IllegalStateException("No active session with '$peerUsername'"))
        return encryptForSession(
            selection = SecureSessionSelection(session.sessionId, session.peerUsername),
            plaintext = plaintext,
        )
    }

    suspend fun encryptForSession(
        selection: SecureSessionSelection,
        plaintext: String,
    ): Result<SendResult> {
        val validationError = validateSessionForSend(selection.sessionId)
        if (validationError != null) {
            return Result.failure(IllegalStateException(validationError))
        }
        return secureMessagingRepository.sendMessage(
            sessionId = selection.sessionId,
            peerUsername = selection.recipientName,
            plaintext = plaintext,
        )
    }

    suspend fun decryptWithActiveSession(obfuscatedText: String): Result<DecryptResult> {
        val activeSession = getActiveSessionSelection()
            ?: return Result.failure(IllegalStateException(appContext.getString(R.string.secure__no_session)))
        return decryptForSender(obfuscatedText, activeSession.recipientName)
    }

    suspend fun decryptForSender(
        obfuscatedText: String,
        senderUsername: String,
    ): Result<DecryptResult> {
        return secureMessagingRepository.decryptMessage(obfuscatedText, senderUsername)
    }

    fun formatFailure(prefix: String, throwable: Throwable): String {
        return buildOperationError(prefix, throwable).message
    }

    suspend fun handleEncrypt(context: Context) {
        if (!isLoggedIn()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.secure__login_required, Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (getActiveSessionSelection() == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.secure__no_session, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val editorTarget = resolveWholeEditorTextTarget()
        if (editorTarget == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.secure__select_text_to_encrypt, Toast.LENGTH_SHORT).show()
            }
            return
        }

        withContext(Dispatchers.Main) {
            Toast.makeText(context, R.string.secure__encrypting, Toast.LENGTH_SHORT).show()
        }

        val result = withContext(Dispatchers.IO) {
            encryptWithActiveSession(editorTarget.text)
        }

        withContext(Dispatchers.Main) {
            result.onSuccess { sendResult ->
                val replaced = replaceEditorText(editorTarget.range, sendResult.obfuscatedText)
                if (replaced) {
                    copyToClipboard(context, sendResult.obfuscatedText)
                    Log.d(
                        TAG,
                        "handleEncrypt: provider=${sendResult.providerType} fallback=${sendResult.usedFallback}",
                    )
                    Toast.makeText(context, R.string.secure__encrypted_done, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, R.string.secure__no_text_field, Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                Toast.makeText(
                    context,
                    formatFailure(prefix = "Encrypt failed", throwable = e),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    suspend fun handleDecrypt(context: Context) {
        if (!isLoggedIn()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.secure__login_required, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val activeSession = getActiveSessionSelection()
        val senderUsername = activeSession?.recipientName
        if (senderUsername.isNullOrBlank()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.secure__no_session, Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (DecryptCaptureState.isServiceEnabled(context) && DecryptCaptureState.serviceInstance != null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.secure__decrypt_capture_waiting, Toast.LENGTH_SHORT).show()
            }
            DecryptCaptureState.startCapture(senderUsername) { capturedText ->
                performDecryption(context, capturedText, senderUsername)
            }
            return
        }

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        if (!clipText.isNullOrEmpty()) {
            performDecryption(context, clipText, senderUsername)
            return
        }

        withContext(Dispatchers.Main) {
            Toast.makeText(context, R.string.secure__decrypt_capture_enable_service, Toast.LENGTH_LONG).show()
        }
    }

    fun performDecryption(context: Context, ciphertext: String, recipientName: String) {
        Log.d(TAG, "performDecryption: text length=${ciphertext.length}")

        scope.launch(Dispatchers.IO) {
            val result = decryptForSender(ciphertext, recipientName)
            withContext(Dispatchers.Main) {
                result.onSuccess { decryptResult ->
                    Log.d(
                        TAG,
                        "performDecryption: provider=${decryptResult.providerType} fallback=${decryptResult.usedFallback}",
                    )
                    launchDecryptResult(context, recipientName, decryptResult.plaintext, null)
                }.onFailure { e ->
                    Log.e(TAG, "Decrypt failed: input length=${ciphertext.length}, error=${e.message}")
                    launchDecryptResult(
                        context = context,
                        recipientName = recipientName,
                        plaintext = null,
                        error = "Decrypt failed (${ciphertext.length} chars): ${e.message?.take(80)}",
                    )
                }
            }
        }
    }

    suspend fun validateSessionForSend(sessionId: String): String? {
        val session = secureMessagingRepository.listSessions().getOrNull()
            ?.firstOrNull { it.sessionId == sessionId && it.isActive }
            ?: return appContext.getString(R.string.secure__no_session)
        return sessionRecoveryHint(session)
    }

    private fun buildManagedSession(
        session: SessionResponse,
        activeSessionId: String?,
    ): ManagedSecureSession {
        val myUserId = getUserId()
        val peerUsername = if (session.initiatorId == myUserId) {
            session.responderUsername
        } else {
            session.initiatorUsername
        }
        return ManagedSecureSession(
            sessionId = session.sessionId,
            peerUsername = peerUsername,
            canSend = secureMessagingRepository.canSendToSession(session),
            recoveryHint = sessionRecoveryHint(session),
            isActiveSelection = session.sessionId == activeSessionId,
        )
    }

    private fun sessionRecoveryHint(session: SessionResponse): String? {
        if (secureMessagingRepository.canSendToSession(session)) {
            return null
        }
        return if (secureMessagingRepository.requiresSessionRecreationForSend(session)) {
            "Recreate session on this device"
        } else {
            "Send not ready on this device"
        }
    }

    private fun resolveWholeEditorTextTarget(): EditorTextTarget? {
        val inputConnection = currentInputConnection() ?: return null
        val extracted = inputConnection.getExtractedText(ExtractedTextRequest(), 0)
        val extractedText = extracted?.text?.toString()
        if (!extractedText.isNullOrEmpty()) {
            val startOffset = extracted.startOffset.coerceAtLeast(0)
            return EditorTextTarget(
                text = extractedText,
                range = EditorRange(startOffset, startOffset + extractedText.length),
            )
        }

        val activeContent = activeEditorInstance.activeContent
        val snapshotText = activeContent.text
        return if (snapshotText.isNotEmpty() && activeContent.safeEditorBounds.isValid) {
            EditorTextTarget(
                text = snapshotText,
                range = activeContent.safeEditorBounds,
            )
        } else {
            null
        }
    }

    private fun replaceEditorText(range: EditorRange, replacement: String): Boolean {
        val inputConnection = currentInputConnection() ?: return false
        inputConnection.beginBatchEdit()
        return try {
            inputConnection.finishComposingText()
            if (range.isValid) {
                inputConnection.setSelection(range.start, range.end)
            }
            inputConnection.commitText(replacement, 1)
        } finally {
            inputConnection.endBatchEdit()
        }
    }

    private fun currentInputConnection(): InputConnection? {
        return FlorisImeService.currentInputConnection()
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Secure Text", text))
    }

    private fun launchDecryptResult(
        context: Context,
        recipientName: String,
        plaintext: String?,
        error: String?,
    ) {
        val intent = Intent(context, DecryptResultActivity::class.java).apply {
            putExtra(DecryptResultActivity.EXTRA_SENDER, recipientName)
            if (plaintext != null) {
                putExtra(DecryptResultActivity.EXTRA_PLAINTEXT, plaintext)
            }
            if (error != null) {
                putExtra(DecryptResultActivity.EXTRA_ERROR, error)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun buildOperationError(prefix: String, throwable: Throwable): SecureOperationResult {
        val message = throwable.message ?: "Unknown error"
        val userFacing = when {
            message.contains("ConnectException") || message.contains("Failed to connect") ->
                "$prefix: cannot connect to server"
            message.contains("Recreate the session", ignoreCase = true) ||
                message.contains("created on another install", ignoreCase = true) ->
                "$prefix: recreate this session on this device"
            message.contains("not ready to send", ignoreCase = true) ->
                "$prefix: session is not ready on this device"
            message.contains("Secure keys unavailable", ignoreCase = true) ->
                "$prefix: log in again to restore local keys"
            message.contains("No shared secret", ignoreCase = true) ->
                "$prefix: no encryption keys available"
            message.contains("invalid obfuscated text", ignoreCase = true) ->
                "$prefix: message is not valid secure text"
            message.contains("session") && message.contains("not found", ignoreCase = true) ->
                "$prefix: no session with this user"
            else -> "$prefix: ${message.take(120)}"
        }

        return SecureOperationResult(
            isSuccess = false,
            message = userFacing,
        )
    }
}

private data class EditorTextTarget(
    val text: String,
    val range: EditorRange,
)
