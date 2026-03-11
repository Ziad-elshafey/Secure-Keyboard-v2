package dev.patrickgold.florisboard.app.settings.secure

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.secure.data.remote.SessionResponse
import dev.patrickgold.florisboard.secure.data.remote.UserSearchResult
import dev.patrickgold.florisboard.secureMessagingManager
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.compose.stringRes

private data class ManagedSessionRow(
    val sessionId: String,
    val peerName: String,
    val canSend: Boolean,
    val statusText: String?,
)

@Composable
fun SecureMessagingScreen() = FlorisScreen {
    title = stringRes(R.string.settings__secure_messaging__title)

    val context = LocalContext.current
    val secureManager by context.secureMessagingManager()
    val scope = rememberCoroutineScope()

    content {
        val repo = secureManager.secureMessagingRepository
        val prefs = remember(context) {
            context.getSharedPreferences("secure_active_session", Context.MODE_PRIVATE)
        }

        var isLoggedIn by remember { mutableStateOf(repo.isLoggedIn()) }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var statusMessage by remember { mutableStateOf("") }
        var isLoading by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }
        var searchResults by remember { mutableStateOf(emptyList<UserSearchResult>()) }
        var sessions by remember { mutableStateOf(emptyList<ManagedSessionRow>()) }
        var isSearching by remember { mutableStateOf(false) }
        var isRefreshingSessions by remember { mutableStateOf(false) }
        var activeSessionId by remember { mutableStateOf(prefs.getString("session_id", null)) }
        var activeRecipientName by remember { mutableStateOf(prefs.getString("recipient_name", null)) }

        fun setActiveSession(sessionId: String, peerName: String) {
            prefs.edit()
                .putString("session_id", sessionId)
                .putString("recipient_name", peerName)
                .apply()
            activeSessionId = sessionId
            activeRecipientName = peerName
        }

        fun clearActiveSession() {
            prefs.edit().remove("session_id").remove("recipient_name").apply()
            activeSessionId = null
            activeRecipientName = null
        }

        fun mapSessions(sessionList: List<SessionResponse>): List<ManagedSessionRow> {
            val myUserId = repo.getUserId()
            return sessionList.filter { it.isActive }.map { session ->
                val peerName = if (session.initiatorId == myUserId) {
                    session.responderUsername
                } else {
                    session.initiatorUsername
                }
                val canSend = repo.canSendToSession(session)
                ManagedSessionRow(
                    sessionId = session.sessionId,
                    peerName = peerName,
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
        }

        fun reloadSessions() {
            scope.launch {
                isRefreshingSessions = true
                val result = withContext(Dispatchers.IO) { repo.listSessions() }
                isRefreshingSessions = false
                result.onSuccess {
                    sessions = mapSessions(it)
                }.onFailure { e ->
                    statusMessage = "Failed to load sessions: ${e.message?.take(80)}"
                }
            }
        }

        LaunchedEffect(isLoggedIn) {
            if (isLoggedIn) {
                reloadSessions()
            } else {
                sessions = emptyList()
                searchResults = emptyList()
                clearActiveSession()
            }
        }

        PreferenceGroup(title = stringRes(R.string.settings__secure_messaging__login_title)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isLoggedIn) {
                    val currentUser = repo.getUsername() ?: "Unknown"
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Connected account",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                text = currentUser,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            repo.logout()
                            isLoggedIn = false
                            statusMessage = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringRes(R.string.settings__secure_messaging__logout))
                    }
                } else {
                    Text(
                        text = stringRes(R.string.settings__secure_messaging__login_summary_logged_out),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringRes(R.string.settings__secure_messaging__username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringRes(R.string.settings__secure_messaging__password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = {
                                if (username.isBlank() || password.isBlank()) return@Button
                                isLoading = true
                                statusMessage = ""
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        repo.login(username.trim(), password)
                                    }
                                    isLoading = false
                                    result.onSuccess {
                                        isLoggedIn = true
                                        statusMessage = "Logged in successfully"
                                        password = ""
                                    }.onFailure { e ->
                                        statusMessage = "Login failed: ${e.message?.take(80)}"
                                    }
                                }
                            },
                            enabled = !isLoading,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringRes(R.string.settings__secure_messaging__login_button))
                        }
                        OutlinedButton(
                            onClick = {
                                if (username.isBlank() || password.isBlank()) return@OutlinedButton
                                isLoading = true
                                statusMessage = ""
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        repo.register(username.trim(), password)
                                    }
                                    isLoading = false
                                    result.onSuccess {
                                        statusMessage = "Registered! You can now log in."
                                    }.onFailure { e ->
                                        statusMessage = "Register failed: ${e.message?.take(80)}"
                                    }
                                }
                            },
                            enabled = !isLoading,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringRes(R.string.settings__secure_messaging__register_button))
                        }
                    }
                }

                if (statusMessage.isNotEmpty()) {
                    val isError = statusMessage.startsWith("Login failed") ||
                        statusMessage.startsWith("Register failed") ||
                        statusMessage.startsWith("Failed") ||
                        statusMessage.startsWith("Deactivate failed")
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isError) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                        ),
                    ) {
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isError) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }

        if (isLoggedIn) {
            PreferenceGroup(title = "Session Controls") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val activeRecipient = activeRecipientName
                    if (!activeRecipient.isNullOrBlank()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "Active session",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Text(
                                    text = activeRecipient,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "No active session selected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    OutlinedButton(
                        onClick = { clearActiveSession() },
                        enabled = !activeRecipient.isNullOrBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Clear Active Session")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                if (searchQuery.isBlank()) return@Button
                                isSearching = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        repo.searchUsers(searchQuery.trim())
                                    }
                                    isSearching = false
                                    result.onSuccess { users ->
                                        searchResults = users.filter { it.userId != repo.getUserId() }
                                        if (searchResults.isEmpty()) {
                                            statusMessage = "No users found for '$searchQuery'"
                                        }
                                    }.onFailure { e ->
                                        statusMessage = "Search failed: ${e.message?.take(80)}"
                                        searchResults = emptyList()
                                    }
                                }
                            },
                            enabled = !isSearching,
                            modifier = Modifier.height(56.dp),
                        ) {
                            Text(if (isSearching) "Searching" else "Search")
                        }
                    }

                    if (searchResults.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            searchResults.forEach { user ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = user.username, style = MaterialTheme.typography.bodyMedium)
                                            if (!user.displayName.isNullOrBlank()) {
                                                Text(
                                                    text = user.displayName,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        Button(
                                            onClick = {
                                                isLoading = true
                                                scope.launch {
                                                    val result = withContext(Dispatchers.IO) {
                                                        repo.createSession(user.username, user.userId)
                                                    }
                                                    isLoading = false
                                                    result.onSuccess { sessionInfo ->
                                                        setActiveSession(sessionInfo.sessionId, sessionInfo.peerUsername)
                                                        statusMessage = "Session ready with ${sessionInfo.peerUsername}"
                                                        searchQuery = ""
                                                        searchResults = emptyList()
                                                        reloadSessions()
                                                    }.onFailure { e ->
                                                        statusMessage = "Failed to start session: ${e.message?.take(80)}"
                                                    }
                                                }
                                            },
                                            enabled = !isLoading,
                                        ) {
                                            Text("Start Session")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { reloadSessions() },
                        enabled = !isRefreshingSessions,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (isRefreshingSessions) "Refreshing sessions" else "Refresh Sessions")
                    }

                    if (sessions.isEmpty()) {
                        Text(
                            text = if (isRefreshingSessions) "Loading sessions..." else "No sessions yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            sessions.forEach { session ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (session.sessionId == activeSessionId) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                    ),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(text = session.peerName, style = MaterialTheme.typography.bodyMedium)
                                        if (session.statusText != null) {
                                            Text(
                                                text = session.statusText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Button(
                                                onClick = { setActiveSession(session.sessionId, session.peerName) },
                                                modifier = Modifier.weight(1f),
                                            ) {
                                                Text(if (session.sessionId == activeSessionId) "Active" else "Set Active")
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    isLoading = true
                                                    scope.launch {
                                                        val result = withContext(Dispatchers.IO) {
                                                            repo.deactivateSession(session.sessionId)
                                                        }
                                                        isLoading = false
                                                        result.onSuccess {
                                                            if (session.sessionId == activeSessionId) {
                                                                clearActiveSession()
                                                            }
                                                            statusMessage = "Closed session with ${session.peerName}"
                                                            reloadSessions()
                                                        }.onFailure { e ->
                                                            statusMessage = "Deactivate failed: ${e.message?.take(80)}"
                                                        }
                                                    }
                                                },
                                                enabled = !isLoading,
                                                modifier = Modifier.weight(1f),
                                            ) {
                                                Text("Deactivate")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        PreferenceGroup(title = stringRes(R.string.settings__secure_messaging__accessibility_title)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringRes(R.string.settings__secure_messaging__accessibility_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open Accessibility Settings")
                }
            }
        }
    }
}
