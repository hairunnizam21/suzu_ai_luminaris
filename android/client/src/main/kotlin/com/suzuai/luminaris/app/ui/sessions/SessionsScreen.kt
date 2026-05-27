package com.suzuai.luminaris.app.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suzuai.luminaris.app.ClientApp
import com.suzuai.luminaris.app.ui.setup.clientFieldColors
import com.suzuai.luminaris.shared.data.ReadyResponse
import com.suzuai.luminaris.shared.data.Session
import com.suzuai.luminaris.shared.net.BackendClient
import com.suzuai.luminaris.shared.theme.LuminarisColors
import com.suzuai.luminaris.shared.ui.LuminarisCard
import com.suzuai.luminaris.shared.ui.SectionHeader
import com.suzuai.luminaris.shared.ui.StatusPill
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sessions list — like Devin's Sessions tab. Tap a row to jump into Ask with
 * that session selected. "Ask" tab also shows the active session at the top.
 *
 * Active session id is held in a process-scoped holder so the Ask tab can pick
 * it up without a real navigation graph.
 */
object ActiveSession {
    var id: String? = null
    var title: String? = null
}

@Composable
fun SessionsScreen(onOpenAsk: () -> Unit) {
    val app = ClientApp.instance
    val scope = rememberCoroutineScope()
    val (url, token) = app.store.pair.collectAsState(initial = "" to "").value
    val client = remember(url, token) { BackendClient(url, token) }

    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var ready by remember { mutableStateOf<ReadyResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var newTitle by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }

    suspend fun refresh() {
        runCatching { client.ready() }.onSuccess { ready = it }
        runCatching { client.listSessions() }
            .onSuccess { sessions = it; error = null }
            .onFailure { error = "Load failed: ${it.message}" }
    }

    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect
        loading = true
        refresh()
        loading = false
    }

    Column(Modifier.fillMaxSize().background(LuminarisColors.Bg).padding(16.dp)) {
        ready?.let { r ->
            if (!r.ready) {
                LuminarisCard {
                    Column {
                        SectionHeader("Waiting for panel")
                        StatusPill(
                            ok = false,
                            label = "Backend reports not ready. Missing: ${r.missing.joinToString()}",
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Open the SuzuAI Luminaris Panel app and fill SSH + AI provider config.",
                            color = LuminarisColors.TextDim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Sessions")
            Spacer(Modifier.weight(1f))
            Button(
                colors = ButtonDefaults.buttonColors(
                    containerColor = LuminarisColors.AccentCyan,
                    contentColor = androidx.compose.ui.graphics.Color.Black,
                ),
                enabled = ready?.ready == true,
                onClick = { showNew = true },
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.padding(end = 4.dp))
                Text("New session", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(8.dp))

        when {
            loading -> Text("Loading…", color = LuminarisColors.TextDim)
            error != null && sessions.isEmpty() -> Text(error!!, color = LuminarisColors.AccentRed)
            sessions.isEmpty() -> Text(
                "No sessions yet. Tap “New session” to start.",
                color = LuminarisColors.TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(sessions) { s ->
                    SessionRow(
                        session = s,
                        onOpen = {
                            ActiveSession.id = s.id
                            ActiveSession.title = s.title
                            onOpenAsk()
                        },
                        onDelete = {
                            scope.launch {
                                runCatching { client.deleteSession(s.id) }
                                refresh()
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    if (showNew) {
        AlertDialog(
            onDismissRequest = { showNew = false },
            containerColor = LuminarisColors.Surface,
            title = { Text("New session", color = LuminarisColors.AccentCyan) },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("Title") },
                    placeholder = { Text("e.g. Decompile WhatsApp clone") },
                    singleLine = true,
                    colors = clientFieldColors(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newTitle.isNotBlank(),
                    onClick = {
                        val title = newTitle.trim()
                        showNew = false
                        newTitle = ""
                        scope.launch {
                            runCatching { client.createSession(title) }
                                .onSuccess {
                                    ActiveSession.id = it.id
                                    ActiveSession.title = it.title
                                    refresh()
                                    onOpenAsk()
                                }
                                .onFailure { error = "Create failed: ${it.message}" }
                        }
                    },
                ) { Text("Create", color = LuminarisColors.AccentCyan) }
            },
            dismissButton = {
                TextButton(onClick = { showNew = false; newTitle = "" }) {
                    Text("Cancel", color = LuminarisColors.TextDim)
                }
            },
        )
    }
}

@Composable
private fun SessionRow(session: Session, onOpen: () -> Unit, onDelete: () -> Unit) {
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        .format(Date((session.updatedAt * 1000).toLong()))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LuminarisColors.Surface)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                session.title,
                color = LuminarisColors.Text,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            )
            Text(
                date,
                color = LuminarisColors.TextDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = LuminarisColors.AccentRed)
        }
    }
}
