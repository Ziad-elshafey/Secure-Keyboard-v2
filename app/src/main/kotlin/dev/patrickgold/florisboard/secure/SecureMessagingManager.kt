package dev.patrickgold.florisboard.secure

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.view.inputmethod.ExtractedTextRequest
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.secure.core.DecryptCaptureState
import dev.patrickgold.florisboard.secure.core.ActiveSecureContact
import dev.patrickgold.florisboard.secure.core.ManagedSecureSession
import dev.patrickgold.florisboard.secure.core.SecureContact
import dev.patrickgold.florisboard.secure.core.SecureOperationResult
import dev.patrickgold.florisboard.secure.core.SecureSessionSelection
import dev.patrickgold.florisboard.secure.core.SecureSessionState
import dev.patrickgold.florisboard.secure.data.local.AuthTokenManager
import dev.patrickgold.florisboard.secure.data.local.SecureContactStore
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
import kotlin.math.max

class SecureMessagingManager(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var secureStatusPopup: PopupWindow? = null
    private var secureLoadingPopup: PopupWindow? = null
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

    val contactStore: SecureContactStore by lazy {
        SecureContactStore(appContext)
    }

    private val authInterceptor: AuthInterceptor by lazy {
        AuthInterceptor(tokenManager) {
            secureRefreshRetrofit.create(SecureApiService::class.java)
        }
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
            contactStore = contactStore,
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
            secureMessagingRepository.isLoggedIn() && sessionStore.hasActiveContact()
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

    fun getActiveContactSelection(): ActiveSecureContact? = sessionStore.getActiveContact()

    fun setActiveContact(contact: ActiveSecureContact) {
        sessionStore.setActiveContact(contact)
    }

    fun clearActiveContact() {
        sessionStore.clearActiveContact()
    }

    fun getActiveSessionSelection(): SecureSessionSelection? = sessionStore.getActiveSession()

    fun setActiveSession(sessionId: String, recipientName: String) {
        sessionStore.setActiveSession(sessionId = sessionId, recipientName = recipientName)
        setActiveContact(
            ActiveSecureContact(
                userId = "",
                username = recipientName,
                displayName = null,
            ),
        )
    }

    fun clearActiveSession() {
        clearActiveContact()
    }

    fun getSecureSessionState(lastOperation: SecureOperationResult? = null): SecureSessionState {
        val activeContact = sessionStore.getActiveContact()
        val loggedIn = isLoggedIn()
        return SecureSessionState(
            isLoggedIn = loggedIn,
            activeSessionId = null,
            activeRecipientName = activeContact?.username,
            isSessionReady = loggedIn && activeContact != null,
            recoveryHint = when {
                !loggedIn -> appContext.getString(R.string.secure__login_required)
                activeContact == null -> appContext.getString(R.string.secure__no_session)
                else -> null
            },
            lastOperation = lastOperation,
        )
    }

    suspend fun resolveSecureSessionState(
        lastOperation: SecureOperationResult? = null,
    ): SecureSessionState {
        val activeContact = getActiveContactSelection()
        if (!isLoggedIn() || activeContact == null) {
            return getSecureSessionState(lastOperation)
        }

        return SecureSessionState(
            isLoggedIn = true,
            activeSessionId = null,
            activeRecipientName = activeContact.username,
            isSessionReady = true,
            recoveryHint = null,
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
        clearActiveContact()
    }

    suspend fun searchUsers(query: String): Result<List<UserSearchResult>> {
        return secureMessagingRepository.searchUsers(query)
    }

    suspend fun addContactFromSearchResult(user: UserSearchResult): Result<SecureContact> {
        return secureMessagingRepository.addContactFromSearchResult(user)
    }

    suspend fun listContacts(): Result<List<SecureContact>> {
        return secureMessagingRepository.listContacts()
    }

    suspend fun removeContact(username: String): Result<Unit> {
        return secureMessagingRepository.removeContact(username)
    }

    suspend fun ensureSessionForContact(contact: ActiveSecureContact): Result<SecureSessionSelection> {
        return secureMessagingRepository.ensureSessionForContact(contact)
    }

    suspend fun listManagedSessions(): Result<List<ManagedSecureSession>> {
        val activeUsername = getActiveContactSelection()?.username
        return secureMessagingRepository.listSessions().map { sessions ->
            sessions
                .filter { it.isActive }
                .map { session ->
                    buildManagedSession(
                        session = session,
                        activeSessionId = activeUsername,
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
                setActiveContact(
                    ActiveSecureContact(
                        userId = peerUserId,
                        username = selection.recipientName,
                        displayName = null,
                    ),
                )
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
        val activeContact = getActiveContactSelection()
            ?: return Result.failure(IllegalStateException(appContext.getString(R.string.secure__no_session)))
        return ensureSessionForContact(activeContact).fold(
            onSuccess = { session -> encryptForSession(session, plaintext) },
            onFailure = { error -> Result.failure(error) },
        )
    }

    suspend fun encryptForPeer(peerUsername: String, plaintext: String): Result<SendResult> {
        val session = findManagedSession(peerUsername)
            ?: return Result.failure(IllegalStateException("No active session with '$peerUsername'"))
        return encryptForSession(
            selection = SecureSessionSelection(session.sessionId, session.peerUsername),
            plaintext = plaintext,
        )
    }

    suspend fun encryptForUser(peerUsername: String, plaintext: String): Result<SendResult> {
        return secureMessagingRepository.sendMessageToUser(peerUsername, plaintext)
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
        val activeSession = getActiveContactSelection()
            ?: return Result.failure(IllegalStateException(appContext.getString(R.string.secure__no_session)))
        return decryptForSender(obfuscatedText, activeSession.username)
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

        if (getActiveContactSelection() == null) {
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
            showOperationLoadingOverlay(appContext.getString(R.string.secure__encrypting))
        }

        val result = withContext(Dispatchers.IO) {
            encryptWithActiveSession(editorTarget.text)
        }

        withContext(Dispatchers.Main) {
            result.onSuccess { sendResult ->
                hideOperationLoadingOverlay()
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
                hideOperationLoadingOverlay()
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

        val activeSession = getActiveContactSelection()
        val senderUsername = activeSession?.username
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

        scope.launch(Dispatchers.Main) {
            showOperationLoadingOverlay(appContext.getString(R.string.secure__decrypting))
            val result = withContext(Dispatchers.IO) {
                decryptForSender(ciphertext, recipientName)
            }
            result.onSuccess { decryptResult ->
                hideOperationLoadingOverlay()
                Log.d(
                    TAG,
                    "performDecryption: provider=${decryptResult.providerType} fallback=${decryptResult.usedFallback}",
                )
                val replaced = replaceCiphertextInEditor(ciphertext, decryptResult.plaintext)
                copyToClipboard(context, decryptResult.plaintext)
                val preview = decryptResult.plaintext.let {
                    if (it.length > 180) it.take(180) + "..." else it
                }
                val message = if (replaced) {
                    appContext.getString(R.string.secure__decrypted_preview_replaced, preview)
                } else {
                    appContext.getString(R.string.secure__decrypted_preview_copied, preview)
                }
                showSecureStatusBanner(message)
            }.onFailure { e ->
                hideOperationLoadingOverlay()
                Log.e(TAG, "Decrypt failed: input length=${ciphertext.length}, error=${e.message}")
                showSecureStatusBanner(formatFailure(prefix = "Decrypt failed", throwable = e), isError = true)
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
            isActiveSelection = peerUsername.equals(activeSessionId, ignoreCase = true),
        )
    }

    private fun sessionRecoveryHint(session: SessionResponse): String? {
        if (secureMessagingRepository.canSendToSession(session)) {
            return null
        }
        return if (secureMessagingRepository.requiresSessionRecreationForSend(session)) {
            "Session will be re-keyed automatically on first send"
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

    private fun replaceCiphertextInEditor(ciphertext: String, plaintext: String): Boolean {
        val target = resolveWholeEditorTextTarget() ?: return false
        if (ciphertext.isBlank()) return false
        val index = target.text.indexOf(ciphertext)
        if (index < 0) return false
        val rangeStart = target.range.start + index
        val rangeEnd = rangeStart + ciphertext.length
        return replaceEditorText(EditorRange(rangeStart, rangeEnd), plaintext)
    }

    private fun currentInputConnection(): InputConnection? {
        return FlorisImeService.currentInputConnection()
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Secure Text", text))
    }

    private fun showOperationLoadingOverlay(message: String) {
        val anchor = FlorisImeService.currentDecorView()
        if (anchor == null) {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            return
        }
        hideOperationLoadingOverlay()

        val context = anchor.context
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = panelDrawable()
        }
        val progress = ProgressBar(context).apply {
            isIndeterminate = true
        }
        val messageView = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            text = message
            setPadding(dp(10), 0, 0, 0)
        }
        container.addView(progress)
        container.addView(messageView)

        val popup = PopupWindow(
            container,
            max(dp(220), (anchor.width * 0.64f).toInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false,
        ).apply {
            isOutsideTouchable = false
            isTouchable = false
            elevation = dp(2).toFloat()
        }
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, dp(26))
        secureLoadingPopup = popup
    }

    private fun hideOperationLoadingOverlay() {
        secureLoadingPopup?.dismiss()
        secureLoadingPopup = null
    }

    private fun showSecureStatusBanner(message: String, isError: Boolean = false) {
        val anchor = FlorisImeService.currentDecorView()
        if (anchor == null) {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            return
        }
        secureStatusPopup?.dismiss()

        val context = anchor.context
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
            background = panelDrawable(isError = isError)
        }
        val messageView = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            text = message
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = Button(context).apply {
            text = appContext.getString(R.string.secure__dismiss)
            isAllCaps = false
            setOnClickListener {
                secureStatusPopup?.dismiss()
                secureStatusPopup = null
            }
        }
        container.addView(messageView)
        container.addView(close)

        val popup = PopupWindow(
            container,
            max(dp(280), (anchor.width * 0.92f).toInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            elevation = dp(2).toFloat()
        }
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, dp(26))
        secureStatusPopup = popup
    }

    private fun panelDrawable(isError: Boolean = false): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (isError) 0xCCB3261E.toInt() else 0xCC1F2937.toInt())
            setStroke(dp(1), 0x66FFFFFF)
        }
    }

    private fun dp(value: Int): Int {
        return (value * appContext.resources.displayMetrics.density).toInt()
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
