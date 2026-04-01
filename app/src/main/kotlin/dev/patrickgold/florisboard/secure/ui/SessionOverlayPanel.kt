package dev.patrickgold.florisboard.secure.ui

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.secure.core.ActiveSecureContact
import dev.patrickgold.florisboard.secure.core.SecureContact
import dev.patrickgold.florisboard.secureMessagingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SessionOverlayPanel() {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val secureManager by context.secureMessagingManager()

    var isLoading by remember { mutableStateOf(true) }
    var activeContact by remember { mutableStateOf<ActiveSecureContact?>(secureManager.getActiveContactSelection()) }
    var contacts by remember { mutableStateOf(emptyList<SecureContact>()) }

    fun setActiveContact(contact: SecureContact) {
        val active = ActiveSecureContact(
            userId = contact.userId,
            username = contact.username,
            displayName = contact.displayName,
        )
        secureManager.setActiveContact(active)
        activeContact = active
    }

    LaunchedEffect(Unit) {
        isLoading = true
        val result = withContext(Dispatchers.IO) { secureManager.listContacts() }
        result.onSuccess { savedContacts ->
            contacts = savedContacts
            activeContact = secureManager.getActiveContactSelection()
        }.onFailure {
            contacts = emptyList()
        }
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = context.getString(R.string.secure__contacts_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { keyboardManager.activeState.isSecureSessionVisible = false },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close secure contacts panel",
                    modifier = Modifier.size(16.dp),
                )
            }
            TextButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("ui://florisboard/settings/secure-messaging")
                        addCategory(Intent.CATEGORY_BROWSABLE)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                },
            ) {
                Text("Manage")
            }
        }

        if (isLoading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        } else if (contacts.isEmpty()) {
            Text(
                text = context.getString(R.string.secure__no_contacts),
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
                items(contacts) { contact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                setActiveContact(contact)
                                keyboardManager.activeState.isSecureSessionVisible = false
                            }
                            .background(
                                if (activeContact?.username.equals(contact.username, ignoreCase = true)) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.Transparent
                                },
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (activeContact?.username.equals(contact.username, ignoreCase = true)) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = contact.username,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (!contact.displayName.isNullOrBlank() &&
                                !contact.displayName.equals(contact.username, ignoreCase = true)
                            ) {
                                Text(
                                    text = contact.displayName.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (activeContact?.username.equals(contact.username, ignoreCase = true)) {
                            Text(
                                text = context.getString(R.string.secure__active_status),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
