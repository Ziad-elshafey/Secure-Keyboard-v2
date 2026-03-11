package dev.patrickgold.florisboard.secure.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.secure.SecureMessagingManager
import dev.patrickgold.florisboard.secure.data.remote.SessionResponse
import dev.patrickgold.florisboard.secureMessagingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialog-style Activity that appears in Android's text selection floating toolbar.
 *
 * Registered with PROCESS_TEXT intent filter so "Encrypt" and "Decrypt"
 * appear when user selects text in any app.
 */
class SecureTextActionActivity : ComponentActivity() {

    private val secureManager: SecureMessagingManager by lazy { secureMessagingManager().value }
    private val repo get() = secureManager.secureMessagingRepository

    private var isEncryptMode = true
    private var selectedText = ""
    private var isReadOnly = false

    // UI references
    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvSelectedText: TextView
    private lateinit var etUsername: EditText
    private lateinit var btnAction: Button
    private lateinit var btnCancel: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        selectedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: ""
        isReadOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        val componentName = componentName.className
        isEncryptMode = !componentName.contains("Decrypt", ignoreCase = true)

        buildUI()

        // Silent encrypt if active session exists
        if (isEncryptMode && selectedText.isNotEmpty()) {
            val prefs = getSharedPreferences("secure_active_session", MODE_PRIVATE)
            val savedSessionId = prefs.getString("session_id", null)
            val savedRecipient = prefs.getString("recipient_name", null)
            if (!savedSessionId.isNullOrEmpty() && !savedRecipient.isNullOrEmpty()) {
                doSilentEncrypt(savedSessionId, savedRecipient)
                return
            }
        }

        // Silent decrypt if active session exists
        if (!isEncryptMode && selectedText.isNotEmpty()) {
            val prefs = getSharedPreferences("secure_active_session", MODE_PRIVATE)
            val savedRecipient = prefs.getString("recipient_name", null)
            if (!savedRecipient.isNullOrEmpty()) {
                doSilentDecrypt(savedRecipient)
                return
            }
        }

        setupUI()
    }

    private fun buildUI() {
        val padding = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        tvTitle = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
        }
        root.addView(tvTitle)

        tvSelectedText = TextView(this).apply {
            textSize = 14f
            setPadding(0, padding / 2, 0, padding / 2)
            maxLines = 5
        }
        root.addView(tvSelectedText)

        etUsername = EditText(this).apply {
            setPadding(0, padding / 4, 0, padding / 4)
        }
        root.addView(etUsername)

        tvStatus = TextView(this).apply {
            textSize = 13f
            setPadding(0, padding / 4, 0, padding / 4)
        }
        root.addView(tvStatus)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        btnCancel = Button(this).apply {
            text = getString(android.R.string.cancel)
            setOnClickListener { finish() }
        }
        buttonRow.addView(btnCancel)

        btnAction = Button(this).apply {
            setOnClickListener { performAction() }
        }
        buttonRow.addView(btnAction)

        root.addView(buttonRow)
        setContentView(root)
    }

    private fun doSilentEncrypt(sessionId: String, recipientUsername: String) {
        tvSelectedText.visibility = View.GONE
        etUsername.visibility = View.GONE
        btnAction.visibility = View.GONE
        btnCancel.visibility = View.GONE
        tvTitle.text = getString(R.string.secure__encrypting)
        tvStatus.text = "Encrypting for $recipientUsername..."

        lifecycleScope.launch(Dispatchers.IO) {
            val validationError = validateSessionForSend(sessionId)
            if (validationError != null) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = validationError
                    btnCancel.visibility = View.VISIBLE
                    btnCancel.setOnClickListener { finish() }
                }
                return@launch
            }

            val result = repo.sendMessage(sessionId, recipientUsername, selectedText)
            withContext(Dispatchers.Main) {
                result.onSuccess { sendResult ->
                    if (!isReadOnly) {
                        val resultIntent = Intent().apply {
                            putExtra(Intent.EXTRA_PROCESS_TEXT, sendResult.obfuscatedText)
                        }
                        setResult(RESULT_OK, resultIntent)
                        finish()
                    } else {
                        tvStatus.text = getString(R.string.secure__encrypted_done)
                        copyToClipboard(sendResult.obfuscatedText)
                        btnCancel.visibility = View.VISIBLE
                        btnCancel.text = "Done"
                        btnCancel.setOnClickListener { finish() }
                    }
                }.onFailure { e ->
                    tvStatus.text = "Failed: ${simplifyError(e)}"
                    btnCancel.visibility = View.VISIBLE
                    btnCancel.setOnClickListener { finish() }
                }
            }
        }
    }

    private fun doSilentDecrypt(senderUsername: String) {
        tvSelectedText.visibility = View.GONE
        etUsername.visibility = View.GONE
        btnAction.visibility = View.GONE
        btnCancel.visibility = View.GONE
        tvTitle.text = "Decrypting..."
        tvStatus.text = "Decrypting from $senderUsername..."

        lifecycleScope.launch(Dispatchers.IO) {
            val result = repo.decryptMessage(selectedText, senderUsername)
            withContext(Dispatchers.Main) {
                result.onSuccess { plaintext ->
                    if (!isReadOnly) {
                        val resultIntent = Intent().apply {
                            putExtra(Intent.EXTRA_PROCESS_TEXT, plaintext)
                        }
                        setResult(RESULT_OK, resultIntent)
                        finish()
                    } else {
                        tvStatus.text = "Decrypted! Copied to clipboard."
                        copyToClipboard(plaintext)
                        btnCancel.visibility = View.VISIBLE
                        btnCancel.text = "Done"
                        btnCancel.setOnClickListener { finish() }
                    }
                }.onFailure { e ->
                    tvStatus.text = simplifyError(e)
                    btnCancel.visibility = View.VISIBLE
                    btnCancel.text = "Close"
                    btnCancel.setOnClickListener { finish() }
                }
            }
        }
    }

    private fun setupUI() {
        if (!repo.isLoggedIn()) {
            tvTitle.text = "Not Logged In"
            tvSelectedText.text = "Open the app settings to log in first."
            etUsername.visibility = View.GONE
            btnAction.visibility = View.GONE
            return
        }

        tvSelectedText.text = selectedText

        if (isEncryptMode) {
            tvTitle.text = "Encrypt"
            etUsername.hint = "Recipient's username"
            btnAction.text = "Encrypt"
        } else {
            tvTitle.text = "Decrypt"
            etUsername.hint = "Sender's username"
            btnAction.text = "Decrypt"
        }
    }

    private fun performAction() {
        val username = etUsername.text.toString().trim()
        if (username.isEmpty()) {
            tvStatus.text = "Enter a username first"
            return
        }

        btnAction.isEnabled = false
        tvStatus.text = if (isEncryptMode) "Encrypting..." else "Decrypting..."

        if (isEncryptMode) {
            doEncrypt(username)
        } else {
            doDecrypt(username)
        }
    }

    private fun doEncrypt(recipientUsername: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val session = findSessionForPeer(recipientUsername)
            if (session == null) {
                withContext(Dispatchers.Main) {
                    btnAction.isEnabled = true
                    tvStatus.text = "No active session with '$recipientUsername'"
                }
                return@launch
            }

            val validationError = validateSessionForSend(session.sessionId, session)
            if (validationError != null) {
                withContext(Dispatchers.Main) {
                    btnAction.isEnabled = true
                    tvStatus.text = validationError
                }
                return@launch
            }

            val result = repo.sendMessage(session.sessionId, recipientUsername, selectedText)

            withContext(Dispatchers.Main) {
                btnAction.isEnabled = true
                result.onSuccess { sendResult ->
                    if (!isReadOnly) {
                        val resultIntent = Intent().apply {
                            putExtra(Intent.EXTRA_PROCESS_TEXT, sendResult.obfuscatedText)
                        }
                        setResult(RESULT_OK, resultIntent)
                        finish()
                    } else {
                        tvStatus.text = "Encrypted! (copied to clipboard)"
                        copyToClipboard(sendResult.obfuscatedText)
                    }
                }.onFailure { e ->
                    tvStatus.text = simplifyError(e)
                }
            }
        }
    }

    private fun doDecrypt(senderUsername: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = repo.decryptMessage(selectedText, senderUsername)
            withContext(Dispatchers.Main) {
                btnAction.isEnabled = true
                result.onSuccess { plaintext ->
                    tvStatus.text = "Decrypted successfully"
                    tvSelectedText.text = plaintext
                }.onFailure { e ->
                    tvStatus.text = simplifyError(e)
                }
            }
        }
    }

    private suspend fun findSessionForPeer(peerUsername: String): SessionResponse? {
        val sessions = repo.listSessions().getOrNull() ?: return null
        val myUserId = repo.getUserId()

        return sessions.filter { it.isActive }.firstOrNull { session ->
            val peer = if (session.initiatorId == myUserId) {
                session.responderUsername
            } else {
                session.initiatorUsername
            }
            peer.equals(peerUsername, ignoreCase = true)
        }
    }

    private suspend fun validateSessionForSend(
        sessionId: String,
        session: SessionResponse? = null,
    ): String? {
        val resolvedSession = session ?: repo.listSessions().getOrNull()?.firstOrNull {
            it.sessionId == sessionId && it.isActive
        }

        if (resolvedSession == null) {
            return "Active session not found"
        }

        if (repo.canSendToSession(resolvedSession)) {
            return null
        }

        return if (repo.requiresSessionRecreationForSend(resolvedSession)) {
            "Session must be recreated on this device"
        } else {
            "Session is not ready to send from this device"
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Encrypted", text))
    }

    private fun simplifyError(e: Throwable): String {
        val msg = e.message ?: "Unknown error"
        return when {
            msg.contains("ConnectException") || msg.contains("Failed to connect") ->
                "Cannot connect to server"
            msg.contains("Recreate the session", ignoreCase = true) ||
                msg.contains("created on another install", ignoreCase = true) ->
                "Session must be recreated on this device"
            msg.contains("not ready to send", ignoreCase = true) ->
                "Session is not ready to send from this device"
            msg.contains("Secure keys unavailable", ignoreCase = true) ->
                "Local secure keys are missing - log in again"
            msg.contains("No shared secret") ->
                "No encryption keys — start a conversation first"
            msg.contains("session") && msg.contains("not found", ignoreCase = true) ->
                "No session with this user"
            else -> msg.take(120)
        }
    }
}
