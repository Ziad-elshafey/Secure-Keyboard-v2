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
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.secure.SecureMessagingManager
import dev.patrickgold.florisboard.secure.core.SecureSessionSelection
import dev.patrickgold.florisboard.secureMessagingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SecureTextActionActivity : ComponentActivity() {
    private val secureManager: SecureMessagingManager by lazy { secureMessagingManager().value }

    private var isEncryptMode = true
    private var selectedText = ""
    private var isReadOnly = false

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
        isEncryptMode = !componentName.className.contains("Decrypt", ignoreCase = true)

        buildUi()

        val activeSession = secureManager.getActiveSessionSelection()
        if (selectedText.isNotEmpty() && activeSession != null) {
            if (isEncryptMode) {
                doSilentEncrypt(activeSession)
            } else {
                doSilentDecrypt(activeSession)
            }
            return
        }

        setupUi()
    }

    private fun buildUi() {
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

    private fun setupUi() {
        if (!secureManager.isLoggedIn()) {
            tvTitle.text = "Not Logged In"
            tvSelectedText.text = getString(R.string.secure__login_required)
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
        tvStatus.text = if (isEncryptMode) {
            getString(R.string.secure__encrypting)
        } else {
            "Decrypting..."
        }

        if (isEncryptMode) {
            doEncrypt(username)
        } else {
            doDecrypt(username)
        }
    }

    private fun doSilentEncrypt(activeSession: SecureSessionSelection) {
        showBusyState("Encrypting for ${activeSession.recipientName}...")
        lifecycleScope.launch(Dispatchers.IO) {
            val result = secureManager.encryptForSession(activeSession, selectedText)
            withContext(Dispatchers.Main) {
                result.onSuccess { sendResult ->
                    deliverResult(sendResult.obfuscatedText, copiedMessage = getString(R.string.secure__encrypted_done))
                }.onFailure { e ->
                    showFailure(secureManager.formatFailure("Encrypt failed", e))
                }
            }
        }
    }

    private fun doSilentDecrypt(activeSession: SecureSessionSelection) {
        showBusyState("Decrypting from ${activeSession.recipientName}...")
        lifecycleScope.launch(Dispatchers.IO) {
            val result = secureManager.decryptForSender(selectedText, activeSession.recipientName)
            withContext(Dispatchers.Main) {
                result.onSuccess { decryptResult ->
                    deliverResult(decryptResult.plaintext, copiedMessage = "Decrypted and copied to clipboard.")
                }.onFailure { e ->
                    showFailure(secureManager.formatFailure("Decrypt failed", e))
                }
            }
        }
    }

    private fun doEncrypt(recipientUsername: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = secureManager.encryptForPeer(recipientUsername, selectedText)
            withContext(Dispatchers.Main) {
                btnAction.isEnabled = true
                result.onSuccess { sendResult ->
                    deliverResult(sendResult.obfuscatedText, copiedMessage = getString(R.string.secure__encrypted_done))
                }.onFailure { e ->
                    tvStatus.text = secureManager.formatFailure("Encrypt failed", e)
                }
            }
        }
    }

    private fun doDecrypt(senderUsername: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = secureManager.decryptForSender(selectedText, senderUsername)
            withContext(Dispatchers.Main) {
                btnAction.isEnabled = true
                result.onSuccess { decryptResult ->
                    if (!isReadOnly) {
                        setResult(
                            RESULT_OK,
                            Intent().apply {
                                putExtra(Intent.EXTRA_PROCESS_TEXT, decryptResult.plaintext)
                            },
                        )
                        finish()
                    } else {
                        tvStatus.text = "Decrypted and copied to clipboard."
                        copyToClipboard(decryptResult.plaintext)
                    }
                }.onFailure { e ->
                    tvStatus.text = secureManager.formatFailure("Decrypt failed", e)
                }
            }
        }
    }

    private fun showBusyState(status: String) {
        tvSelectedText.visibility = View.GONE
        etUsername.visibility = View.GONE
        btnAction.visibility = View.GONE
        btnCancel.visibility = View.GONE
        tvTitle.text = if (isEncryptMode) getString(R.string.secure__encrypting) else "Decrypting..."
        tvStatus.text = status
    }

    private fun showFailure(message: String) {
        tvStatus.text = message
        btnCancel.visibility = View.VISIBLE
        btnCancel.text = "Done"
        btnCancel.setOnClickListener { finish() }
    }

    private fun deliverResult(text: String, copiedMessage: String) {
        if (!isReadOnly) {
            setResult(
                RESULT_OK,
                Intent().apply {
                    putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                },
            )
            finish()
            return
        }

        tvStatus.text = copiedMessage
        copyToClipboard(text)
        btnCancel.visibility = View.VISIBLE
        btnCancel.text = "Done"
        btnCancel.setOnClickListener { finish() }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Secure Text", text))
    }
}
