package dev.patrickgold.florisboard.secure.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.secureMessagingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SessionItem(
    val sessionId: String,
    val peerUsername: String,
    val isActive: Boolean,
    val canSend: Boolean,
    val statusText: String? = null,
)

@Composable
fun SessionOverlayPanel() {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val secureManager by context.secureMessagingManager()

    var isLoading by remember { mutableStateOf(true) }
    val sessions = remember { mutableStateListOf<SessionItem>() }

    LaunchedEffect(Unit) {
        isLoading = true
        withContext(Dispatchers.IO) {
            try {
                val repo = secureManager.secureMessagingRepository
                val result = repo.listSessions()
                val myUserId = repo.getUserId()
                result.getOrNull()?.let { list ->
                    val items = list.filter { it.isActive }.map { session ->
                        val peer = if (session.initiatorId == myUserId) {
                            session.responderUsername
                        } else {
                            session.initiatorUsername
                        }
                        val canSend = repo.canSendToSession(session)
                        SessionItem(
                            sessionId = session.sessionId,
                            peerUsername = peer,
                            isActive = session.isActive,
                            canSend = canSend,
                            statusText = if (repo.requiresSessionRecreationForSend(session)) {
                                "Recreate session on this device"
                            } else if (!canSend) {
                                "Send not ready on this device"
                            } else {
                                null
                            },
                        )
                    }
                    sessions.clear()
                    sessions.addAll(items)
                }
            } catch (_: Exception) {
                sessions.clear()
            }
            isLoading = false
        }
    }

    val prefs = context.getSharedPreferences("secure_active_session", Context.MODE_PRIVATE)
    val activeSessionId = prefs.getString("session_id", null)
    val activeRecipient = prefs.getString("recipient_name", null) ?: ""

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Secure Sessions",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { keyboardManager.activeState.isSecureSessionVisible = false },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        if (activeRecipient.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = "Active session",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = activeRecipient,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    prefs.edit().remove("session_id").remove("recipient_name").apply()
                    keyboardManager.activeState.isSecureSessionVisible = false
                },
                enabled = activeRecipient.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Clear Active")
            }
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("ui://florisboard/settings/secure-messaging")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Manage")
            }
        }

        if (isLoading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        } else if (sessions.isEmpty()) {
            Text(
                text = "No active sessions",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                items(sessions) { session ->
                    val isSelected = session.sessionId == activeSessionId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                prefs.edit()
                                    .putString("session_id", session.sessionId)
                                    .putString("recipient_name", session.peerUsername)
                                    .apply()
                                keyboardManager.activeState.isSecureSessionVisible = false
                            }
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = session.peerUsername,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (session.canSend) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (session.statusText != null) {
                                Text(
                                    text = session.statusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        if (isSelected) {
                            Text(text = "✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
