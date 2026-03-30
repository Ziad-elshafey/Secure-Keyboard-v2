package dev.patrickgold.florisboard.app.settings.secure

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
import dev.patrickgold.florisboard.secure.core.ManagedSecureSession
import dev.patrickgold.florisboard.secure.core.SecureSessionSelection
import dev.patrickgold.florisboard.secure.data.remote.UserSearchResult
import dev.patrickgold.florisboard.secureMessagingManager
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.compose.stringRes

@Composable
fun SecureMessagingScreen() = FlorisScreen {
    title = stringRes(R.string.settings__secure_messaging__title)

    val context = LocalContext.current
    val secureManager by context.secureMessagingManager()
    val scope = rememberCoroutineScope()

    content {
        var isLoggedIn by remember { mutableStateOf(secureManager.isLoggedIn()) }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var statusMessage by remember { mutableStateOf("") }
        var isLoading by remember { mutableStateOf(false) }
        var isSearching by remember { mutableStateOf(false) }
        var isRefreshingSessions by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }
        var searchResults by remember { mutableStateOf(emptyList<UserSearchResult>()) }
        var activeSession by remember { mutableStateOf<SecureSessionSelection?>(secureManager.getActiveSessionSelection()) }
        var sessions by remember { mutableStateOf(emptyList<ManagedSecureSession>()) }
        var sessionRecoveryHint by remember { mutableStateOf<String?>(null) }

        fun applyActiveSession(selection: SecureSessionSelection?) {
            activeSession = selection
            sessions = sessions.map { session ->
                session.copy(isActiveSelection = session.sessionId == selection?.sessionId)
            }
        }

        fun setActiveSession(selection: SecureSessionSelection) {
            secureManager.setActiveSession(selection.sessionId, selection.recipientName)
            applyActiveSession(selection)
            sessionRecoveryHint = sessions.firstOrNull { it.sessionId == selection.sessionId }?.recoveryHint
        }

        fun clearActiveSession() {
            secureManager.clearActiveSession()
            applyActiveSession(null)
            sessionRecoveryHint = null
        }

        fun reloadSessions() {
            scope.launch {
                isRefreshingSessions = true
                val result = withContext(Dispatchers.IO) { secureManager.listManagedSessions() }
                isRefreshingSessions = false
                result.onSuccess { managedSessions ->
                    sessions = managedSessions
                    val refreshedActiveSession = secureManager.getActiveSessionSelection()
                    applyActiveSession(refreshedActiveSession)
                    sessionRecoveryHint = managedSessions
                        .firstOrNull { it.sessionId == refreshedActiveSession?.sessionId }
                        ?.recoveryHint
                }.onFailure { e ->
                    statusMessage = secureManager.formatFailure("Failed to load sessions", e)
                }
            }
        }

        LaunchedEffect(isLoggedIn) {
            if (isLoggedIn) {
                applyActiveSession(secureManager.getActiveSessionSelection())
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
                                text = secureManager.getUsername() ?: "Unknown",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            secureManager.logout()
                            isLoggedIn = false
                            statusMessage = ""
                            clearActiveSession()
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
                                        secureManager.login(username.trim(), password)
                                    }
                                    isLoading = false
                                    result.onSuccess {
                                        isLoggedIn = true
                                        password = ""
                                        statusMessage = "Logged in successfully"
                                    }.onFailure { e ->
                                        statusMessage = secureManager.formatFailure("Login failed", e)
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
                                        secureManager.register(username.trim(), password)
                                    }
                                    isLoading = false
                                    result.onSuccess {
                                        statusMessage = "Registered! You can now log in."
                                    }.onFailure { e ->
                                        statusMessage = secureManager.formatFailure("Register failed", e)
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
                    if (!activeSession?.recipientName.isNullOrBlank()) {
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
                                    text = activeSession!!.recipientName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                if (!sessionRecoveryHint.isNullOrBlank()) {
                                    Text(
                                        text = sessionRecoveryHint!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
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
                        enabled = activeSession != null,
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
                                        secureManager.searchUsers(searchQuery.trim())
                                    }
                                    isSearching = false
                                    result.onSuccess { users ->
                                        searchResults = users.filter { it.userId != secureManager.getUserId() }
                                        if (searchResults.isEmpty()) {
                                            statusMessage = "No users found for '$searchQuery'"
                                        }
                                    }.onFailure { e ->
                                        statusMessage = secureManager.formatFailure("Search failed", e)
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
                                                        secureManager.createSession(user.username, user.userId)
                                                    }
                                                    isLoading = false
                                                    result.onSuccess { selection ->
                                                        setActiveSession(selection)
                                                        statusMessage = "Session ready with ${selection.recipientName}"
                                                        searchQuery = ""
                                                        searchResults = emptyList()
                                                        reloadSessions()
                                                    }.onFailure { e ->
                                                        statusMessage = secureManager.formatFailure("Failed to start session", e)
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
                                        containerColor = if (session.isActiveSelection) {
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
                                        Text(text = session.peerUsername, style = MaterialTheme.typography.bodyMedium)
                                        val recoveryHint = session.recoveryHint
                                        if (!recoveryHint.isNullOrBlank()) {
                                            Text(
                                                text = recoveryHint,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Button(
                                                onClick = {
                                                    setActiveSession(
                                                        SecureSessionSelection(
                                                            sessionId = session.sessionId,
                                                            recipientName = session.peerUsername,
                                                        ),
                                                    )
                                                },
                                                modifier = Modifier.weight(1f),
                                            ) {
                                                Text(if (session.isActiveSelection) "Active" else "Set Active")
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    isLoading = true
                                                    scope.launch {
                                                        val result = withContext(Dispatchers.IO) {
                                                            secureManager.deactivateSession(session.sessionId)
                                                        }
                                                        isLoading = false
                                                        result.onSuccess {
                                                            if (session.isActiveSelection) {
                                                                clearActiveSession()
                                                            }
                                                            statusMessage = "Closed session with ${session.peerUsername}"
                                                            reloadSessions()
                                                        }.onFailure { e ->
                                                            statusMessage = secureManager.formatFailure("Deactivate failed", e)
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
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open Accessibility Settings")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
