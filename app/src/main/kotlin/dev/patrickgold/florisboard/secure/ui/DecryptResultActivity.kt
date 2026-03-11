package dev.patrickgold.florisboard.secure.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity

class DecryptResultActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val senderName = intent.getStringExtra(EXTRA_SENDER) ?: "Unknown"
        val plaintext = intent.getStringExtra(EXTRA_PLAINTEXT)
        val error = intent.getStringExtra(EXTRA_ERROR)

        if (error != null) {
            AlertDialog.Builder(this)
                .setTitle("Decrypt Failed")
                .setMessage(error)
                .setPositiveButton("OK") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Decrypted Message")
                .setMessage("From: $senderName\n\n$plaintext")
                .setPositiveButton("OK") { _, _ -> finish() }
                .setNeutralButton("Copy") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("Decrypted Message", plaintext),
                    )
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .setOnCancelListener { finish() }
                .show()
        }
    }

    companion object {
        const val EXTRA_SENDER = "sender_name"
        const val EXTRA_PLAINTEXT = "plaintext"
        const val EXTRA_ERROR = "error"
    }
}
