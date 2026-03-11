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

    companion object {
        private const val TAG = "SecureMessagingMgr"
        private const val SECURE_API_BASE_URL = "http://10.0.2.2:8000/"
        private const val STEGO_ENCODE_BASE_URL = "https://modalcd--encode.modal.run/"
        private const val STEGO_DECODE_BASE_URL = "https://modalcd--decode.modal.run/"
    }

    fun isReady(): Boolean {
        return try {
            secureMessagingRepository.isLoggedIn() && getActiveSessionId() != null
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

    private fun getActiveSessionId(): String? {
        val prefs = appContext.getSharedPreferences("secure_active_session", Context.MODE_PRIVATE)
        return prefs.getString("session_id", null)
    }

    private fun getActiveRecipientName(): String? {
        val prefs = appContext.getSharedPreferences("secure_active_session", Context.MODE_PRIVATE)
        return prefs.getString("recipient_name", null)
    }

    suspend fun handleEncrypt(context: Context) {
        if (!isLoggedIn()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.secure__login_required, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val sessionId = getActiveSessionId()
        val recipientName = getActiveRecipientName()

        if (sessionId.isNullOrEmpty() || recipientName.isNullOrEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.secure__no_session, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val sessionValidationError = validateActiveSessionForSend(sessionId)
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
            secureMessagingRepository.sendMessage(sessionId, recipientName, plaintext)
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
                    message,
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

        val recipientName = getActiveRecipientName()

        if (recipientName.isNullOrEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.secure__no_session, Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Try accessibility capture mode if the service is enabled
        if (DecryptCaptureState.isServiceEnabled(context) && DecryptCaptureState.serviceInstance != null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.secure__decrypt_capture_waiting, Toast.LENGTH_SHORT).show()
            }
            DecryptCaptureState.startCapture(recipientName) { capturedText ->
                performDecryption(context, capturedText, recipientName)
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

        performDecryption(context, clipText, recipientName)
    }

    fun performDecryption(context: Context, ciphertext: String, recipientName: String) {
        Log.d(TAG, "performDecryption: text length=${ciphertext.length}")

        scope.launch(Dispatchers.IO) {
            val result = secureMessagingRepository.decryptMessage(ciphertext, recipientName)
            withContext(Dispatchers.Main) {
                result.onSuccess { plaintext ->
                    val intent = Intent(context, DecryptResultActivity::class.java).apply {
                        putExtra(DecryptResultActivity.EXTRA_SENDER, recipientName)
                        putExtra(DecryptResultActivity.EXTRA_PLAINTEXT, plaintext)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }.onFailure { e ->
                    Log.e(TAG, "Decrypt failed: input length=${ciphertext.length}, error=${e.message}")
                    val intent = Intent(context, DecryptResultActivity::class.java).apply {
                        putExtra(
                            DecryptResultActivity.EXTRA_ERROR,
                            "Decrypt failed (${ciphertext.length} chars): ${e.message?.take(80)}",
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        }
    }

    private suspend fun validateActiveSessionForSend(sessionId: String): String? {
        val session = secureMessagingRepository.listSessions().getOrNull()
            ?.firstOrNull { it.sessionId == sessionId && it.isActive }
            ?: return appContext.getString(R.string.secure__no_session)

        return validateSessionForSend(session)
    }

    private fun validateSessionForSend(session: SessionResponse): String? {
        if (secureMessagingRepository.canSendToSession(session)) {
            return null
        }

        return if (secureMessagingRepository.requiresSessionRecreationForSend(session)) {
            "Encrypt failed: recreate this session on this device"
        } else {
            "Encrypt failed: session is not ready on this device"
        }
    }
}