package dev.patrickgold.florisboard.secure

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.inputmethod.ExtractedTextRequest
import android.widget.Toast
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.secure.core.DecryptCaptureState
import dev.patrickgold.florisboard.secure.data.local.ActiveSessionSelection
import dev.patrickgold.florisboard.secure.data.local.ActiveSessionStore
import dev.patrickgold.florisboard.secure.data.local.AuthTokenManager
import dev.patrickgold.florisboard.secure.data.local.SecureKeyStore
import dev.patrickgold.florisboard.secure.data.remote.AuthInterceptor
import dev.patrickgold.florisboard.secure.data.remote.SecureApiService
import dev.patrickgold.florisboard.secure.data.remote.SessionResponse
import dev.patrickgold.florisboard.secure.data.remote.StegoDecodeApiService
import dev.patrickgold.florisboard.secure.data.remote.StegoEncodeApiService
import dev.patrickgold.florisboard.secure.data.remote.TokenRefreshAuthenticator
import dev.patrickgold.florisboard.secure.data.repository.SecureMessagingRepository
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
    private val loggingLevel = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE

    val tokenManager: AuthTokenManager by lazy {
        AuthTokenManager(appContext)
    }

    val keyStore: SecureKeyStore by lazy {
        SecureKeyStore(appContext)
    }

    val activeSessionStore: ActiveSessionStore by lazy {
        ActiveSessionStore(appContext)
    }

    private val authInterceptor: AuthInterceptor by lazy {
        AuthInterceptor(tokenManager)
    }

    private val secureRefreshRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.SECURE_API_BASE_URL)
            .client(
                OkHttpClient.Builder()
                    .addInterceptor(buildLoggingInterceptor())
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
            .addInterceptor(buildLoggingInterceptor())
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
            .addInterceptor(buildLoggingInterceptor())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val secureApiService: SecureApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.SECURE_API_BASE_URL)
            .client(secureApiClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SecureApiService::class.java)
    }

    val stegoEncodeApiService: StegoEncodeApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.STEGO_ENCODE_BASE_URL)
            .client(stegoClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(StegoEncodeApiService::class.java)
    }

    val stegoDecodeApiService: StegoDecodeApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.STEGO_DECODE_BASE_URL)
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
            activeSessionStore = activeSessionStore,
        )
    }

    companion object {
        private const val TAG = "SecureMessagingMgr"
        private const val historicalSelectionMessage = "Selected session only decrypts old messages"
        private const val recreateSessionMessage = "Encrypt failed: recreate this session on this device"
        private const val sendNotReadyMessage = "Encrypt failed: session is not ready on this device"
    }

    fun isReady(): Boolean {
        return try {
            secureMessagingRepository.isLoggedIn() && getActiveSession() != null
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

    fun getActiveSession(): ActiveSessionSelection? {
        return activeSessionStore.getForUser(tokenManager.getUserId())
    }

    fun setActiveSession(sessionId: String, recipientName: String) {
        val userId = tokenManager.getUserId()
        if (userId.isNullOrBlank()) {
            activeSessionStore.clear()
            return
        }
        activeSessionStore.saveForUser(userId, sessionId, recipientName)
    }

    fun clearActiveSession() {
        activeSessionStore.clear()
    }

    fun reconcileActiveSession(sessionList: List<SessionResponse>): ActiveSessionSelection? {
        val myUserId = tokenManager.getUserId()
        val selectableSessions = sessionList
            .associate { session ->
                session.sessionId to peerUsernameForSession(session, myUserId)
            }
        return activeSessionStore.reconcileForUser(myUserId, selectableSessions)
    }

    suspend fun handleEncrypt(context: Context) {
        if (!isLoggedIn()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.secure__login_required, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val activeSession = getActiveSession()
        if (activeSession == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.secure__no_session, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val sessionValidationError = validateActiveSessionForSend(activeSession.sessionId)
        if (sessionValidationError != null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, sessionValidationError, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val ic = FlorisImeService.currentInputConnection()
        if (ic == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.secure__no_text_field, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val plaintext = extracted?.text?.toString()?.trim() ?: ""

        if (plaintext.isEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.secure__type_message_hint, Toast.LENGTH_SHORT).show()
            }
            return
        }

        withContext(Dispatchers.Main) {
            Toast.makeText(context, R.string.secure__encrypting, Toast.LENGTH_SHORT).show()
        }

        val result = withContext(Dispatchers.IO) {
            secureMessagingRepository.sendMessage(activeSession.sessionId, activeSession.recipientName, plaintext)
        }

        withContext(Dispatchers.Main) {
            result.onSuccess { sendResult ->
                FlorisImeService.currentInputConnection()?.apply {
                    performContextMenuAction(android.R.id.selectAll)
                    commitText(sendResult.obfuscatedText, 1)
                }
                Toast.makeText(context, R.string.secure__encrypted_done, Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                val message = when {
                    e.message?.contains("Recreate the session", ignoreCase = true) == true -> {
                        "Encrypt failed: recreate this session on this device"
                    }
                    e.message?.contains("Secure keys unavailable", ignoreCase = true) == true -> {
                        "Encrypt failed: log in again to restore local keys"
                    }
                    else -> {
                        "Encrypt failed: ${e.message?.take(80)}"
                    }
                }
                Toast.makeText(
                    context,
                    simplifyRepositoryError(message),
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

        val activeSession = getActiveSession()
        if (activeSession == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.secure__no_session, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val recipientName = activeSession.recipientName

        // Try accessibility capture mode if the service is enabled
        if (DecryptCaptureState.isServiceEnabled(context) && DecryptCaptureState.serviceInstance != null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.secure__decrypt_capture_waiting, Toast.LENGTH_SHORT).show()
            }
            DecryptCaptureState.startCapture(recipientName) { capturedText ->
                performDecryption(context, capturedText, activeSession.sessionId, recipientName)
            }
            return
        }

        // Fallback: clipboard-based decrypt
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()

        if (clipText.isNullOrEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.secure__decrypt_capture_enable_service, Toast.LENGTH_LONG).show()
            }
            return
        }

        performDecryption(context, clipText, activeSession.sessionId, recipientName)
    }

    fun performDecryption(
        context: Context,
        ciphertext: String,
        sessionId: String?,
        recipientName: String,
    ) {
        debugLog { "performDecryption requested" }

        scope.launch(Dispatchers.IO) {
            val result = secureMessagingRepository.decryptMessage(
                obfuscatedText = ciphertext,
                senderUsername = recipientName,
                preferredSessionId = sessionId,
            )
            withContext(Dispatchers.Main) {
                result.onSuccess { plaintext ->
                    val intent = Intent(context, DecryptResultActivity::class.java).apply {
                        putExtra(DecryptResultActivity.EXTRA_SENDER, recipientName)
                        putExtra(DecryptResultActivity.EXTRA_PLAINTEXT, plaintext)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }.onFailure { e ->
                    debugLog { "performDecryption failed" }
                    val intent = Intent(context, DecryptResultActivity::class.java).apply {
                        putExtra(
                            DecryptResultActivity.EXTRA_ERROR,
                            "Decrypt failed (${ciphertext.length} chars): ${simplifyRepositoryError(e.message)}",
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        }
    }

    private suspend fun validateActiveSessionForSend(sessionId: String): String? {
        val sessions = secureMessagingRepository.listSessions(activeOnly = false).getOrNull()
            ?: return appContext.getString(R.string.secure__no_session)
        val session = sessions.firstOrNull { it.sessionId == sessionId }
            ?: run {
                clearActiveSession()
                return appContext.getString(R.string.secure__no_session)
            }
        reconcileActiveSession(sessions)

        return validateSessionForSend(session)
    }

    private fun validateSessionForSend(session: SessionResponse): String? {
        if (secureMessagingRepository.isLocalSecureIdentityMissing()) {
            return SecureMessagingRepository.localSecureIdentityMissingMessage
        }
        if (!session.isActive) {
            return historicalSelectionMessage
        }
        if (secureMessagingRepository.canSendToSession(session)) {
            return null
        }

        return if (secureMessagingRepository.requiresSessionRecreationForSend(session)) {
            recreateSessionMessage
        } else {
            sendNotReadyMessage
        }
    }

    private fun simplifyRepositoryError(message: String?): String {
        val raw = message?.take(120).orEmpty()
        return when {
            raw.contains(SecureMessagingRepository.localSecureIdentityMissingMessage, ignoreCase = true) ->
                SecureMessagingRepository.localSecureIdentityMissingMessage
            raw.contains(SecureMessagingRepository.historicalSessionKeyMissingMessage, ignoreCase = true) ->
                SecureMessagingRepository.historicalSessionKeyMissingMessage
            raw.contains(historicalSelectionMessage, ignoreCase = true) ->
                historicalSelectionMessage
            raw.contains("Recreate the session", ignoreCase = true) ||
                raw.contains("created on another install", ignoreCase = true) ->
                recreateSessionMessage
            raw.contains("not ready on this device", ignoreCase = true) ->
                sendNotReadyMessage
            else -> raw
        }
    }

    private fun buildLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            level = loggingLevel
            redactHeader("Authorization")
            redactHeader("Cookie")
        }
    }

    private inline fun debugLog(message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message())
        }
    }

    private fun peerUsernameForSession(session: SessionResponse, myUserId: String?): String {
        return if (session.initiatorId == myUserId) {
            session.responderUsername
        } else {
            session.initiatorUsername
        }
    }
}
