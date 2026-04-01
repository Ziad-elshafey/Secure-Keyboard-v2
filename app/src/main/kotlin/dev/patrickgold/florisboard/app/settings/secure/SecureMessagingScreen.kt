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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.secure.core.ActiveSecureContact
import dev.patrickgold.florisboard.secure.core.SecureContact
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
        val contentHorizontalPadding = 16.dp
        val sectionVerticalPadding = 8.dp
        val sectionSpacing = 12.dp
        val cardContentPadding = 12.dp
        val compactRowSpacing = 8.dp
        val primaryControlHeight = 56.dp
        val secondaryControlHeight = 40.dp
        val actionButtonHeight = 36.dp
        val searchButtonWidth = 88.dp
        val searchButtonHeight = 48.dp

        var isLoggedIn by remember { mutableStateOf(secureManager.isLoggedIn()) }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var statusMessage by remember { mutableStateOf("") }
        var isLoading by remember { mutableStateOf(false) }
        var isSearching by remember { mutableStateOf(false) }
        var isRefreshingContacts by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }
        var searchResults by remember { mutableStateOf(emptyList<UserSearchResult>()) }
        var contacts by remember { mutableStateOf(emptyList<SecureContact>()) }
        var activeContact by remember { mutableStateOf<ActiveSecureContact?>(secureManager.getActiveContactSelection()) }

        fun setActiveContact(contact: SecureContact) {
            val active = ActiveSecureContact(
                userId = contact.userId,
                username = contact.username,
                displayName = contact.displayName,
            )
            secureManager.setActiveContact(active)
            activeContact = active
        }

        fun clearActiveContact() {
            secureManager.clearActiveContact()
            activeContact = null
        }

        fun reloadContacts() {
            scope.launch {
                isRefreshingContacts = true
                val result = withContext(Dispatchers.IO) { secureManager.listContacts() }
                isRefreshingContacts = false
                result.onSuccess { savedContacts ->
                    contacts = savedContacts
                    activeContact = secureManager.getActiveContactSelection()
                }.onFailure { e ->
                    statusMessage = secureManager.formatFailure("Failed to load contacts", e)
                }
            }
        }

        LaunchedEffect(isLoggedIn) {
            if (isLoggedIn) {
                activeContact = secureManager.getActiveContactSelection()
                reloadContacts()
            } else {
                contacts = emptyList()
                searchResults = emptyList()
                clearActiveContact()
            }
        }

        PreferenceGroup(title = stringRes(R.string.settings__secure_messaging__login_title)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = contentHorizontalPadding, vertical = sectionVerticalPadding),
                verticalArrangement = Arrangement.spacedBy(sectionSpacing),
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
                                .padding(cardContentPadding),
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
                            clearActiveContact()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(secondaryControlHeight),
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(primaryControlHeight),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringRes(R.string.settings__secure_messaging__password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(primaryControlHeight),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(compactRowSpacing),
                        verticalAlignment = Alignment.CenterVertically,
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
                            modifier = Modifier
                                .weight(1f)
                                .height(secondaryControlHeight),
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
                            modifier = Modifier
                                .weight(1f)
                                .height(secondaryControlHeight),
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
            PreferenceGroup(title = stringRes(R.string.secure__contacts_title)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = contentHorizontalPadding, vertical = sectionVerticalPadding),
                    verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                ) {
                    if (!activeContact?.username.isNullOrBlank()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(cardContentPadding),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(compactRowSpacing),
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = stringRes(R.string.secure__active_contact),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    Text(
                                        text = activeContact!!.username,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    if (!activeContact!!.displayName.isNullOrBlank() &&
                                        !activeContact!!.displayName.equals(activeContact!!.username, ignoreCase = true)
                                    ) {
                                        Text(
                                            text = activeContact!!.displayName!!,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = { clearActiveContact() },
                                    enabled = activeContact != null,
                                    modifier = Modifier.height(actionButtonHeight),
                                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                                ) {
                                    Text("Clear")
                                }
                            }
                        }
                    } else {
                        Text(
                            text = stringRes(R.string.secure__no_session),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(compactRowSpacing),
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .height(primaryControlHeight),
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
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .width(96.dp)
                                .height(searchButtonHeight),
                        ) {
                            Text(
                                text = if (isSearching) "Searching" else "Search",
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }

                    if (searchResults.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(compactRowSpacing)) {
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
                                            .padding(cardContentPadding),
                                        horizontalArrangement = Arrangement.spacedBy(compactRowSpacing),
                                        verticalAlignment = Alignment.CenterVertically,
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
                                                        secureManager.addContactFromSearchResult(user)
                                                    }
                                                    isLoading = false
                                                    result.onSuccess { contact ->
                                                        setActiveContact(contact)
                                                        statusMessage = "Added ${contact.username} to contacts"
                                                        searchQuery = ""
                                                        searchResults = emptyList()
                                                        reloadContacts()
                                                    }.onFailure { e ->
                                                        statusMessage = secureManager.formatFailure("Failed to add contact", e)
                                                    }
                                                }
                                            },
                                            enabled = !isLoading,
                                            modifier = Modifier.height(secondaryControlHeight),
                                        ) {
                                            Text(stringRes(R.string.secure__add_contact))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { reloadContacts() },
                        enabled = !isRefreshingContacts,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(secondaryControlHeight),
                    ) {
                        Text(
                            if (isRefreshingContacts) {
                                stringRes(R.string.secure__refreshing_contacts)
                            } else {
                                stringRes(R.string.secure__refresh_contacts)
                            },
                        )
                    }

                    if (contacts.isEmpty()) {
                        Text(
                            text = if (isRefreshingContacts) {
                                stringRes(R.string.secure__loading_contacts)
                            } else {
                                stringRes(R.string.secure__no_contacts)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(compactRowSpacing)) {
                            contacts.forEach { contact ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (activeContact?.username.equals(contact.username, ignoreCase = true)) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(cardContentPadding),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(compactRowSpacing),
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(
                                                text = contact.username,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            if (!contact.displayName.isNullOrBlank() &&
                                                !contact.displayName.equals(contact.username, ignoreCase = true)
                                            ) {
                                                Text(
                                                    text = contact.displayName.orEmpty(),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            RadioButton(
                                                selected = activeContact?.username.equals(contact.username, ignoreCase = true),
                                                onClick = {
                                                    setActiveContact(contact)
                                                    statusMessage = "Active contact set to ${contact.username}"
                                                },
                                            )
                                            IconButton(
                                                onClick = {
                                                    isLoading = true
                                                    scope.launch {
                                                        val result = withContext(Dispatchers.IO) {
                                                            secureManager.removeContact(contact.username)
                                                        }
                                                        isLoading = false
                                                        result.onSuccess {
                                                            if (activeContact?.username.equals(contact.username, ignoreCase = true)) {
                                                                clearActiveContact()
                                                            }
                                                            statusMessage = "Removed ${contact.username} from contacts"
                                                            reloadContacts()
                                                        }.onFailure { e ->
                                                            statusMessage = secureManager.formatFailure("Remove contact failed", e)
                                                        }
                                                    }
                                                },
                                                enabled = !isLoading,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = stringRes(R.string.secure__remove_contact),
                                                )
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
                    .padding(horizontal = contentHorizontalPadding, vertical = sectionVerticalPadding),
                verticalArrangement = Arrangement.spacedBy(sectionSpacing),
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(secondaryControlHeight),
                ) {
                    Text("Open Accessibility Settings")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
