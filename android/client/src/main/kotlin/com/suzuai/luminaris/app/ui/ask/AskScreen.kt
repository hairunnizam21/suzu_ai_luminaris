package com.suzuai.luminaris.app.ui.ask

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suzuai.luminaris.app.ClientApp
import com.suzuai.luminaris.app.ui.sessions.ActiveSession
import com.suzuai.luminaris.app.ui.setup.clientFieldColors
import com.suzuai.luminaris.shared.data.ChatMessage
import com.suzuai.luminaris.shared.data.ChatRequest
import com.suzuai.luminaris.shared.data.StreamEvent
import com.suzuai.luminaris.shared.data.ToolCall
import com.suzuai.luminaris.shared.data.ToolResult
import com.suzuai.luminaris.shared.net.BackendClient
import com.suzuai.luminaris.shared.theme.LuminarisColors
import kotlinx.coroutines.launch

/**
 * The actual chat surface. One LazyColumn of messages + a sticky composer at
 * the bottom. While the agent streams `delta` events we accumulate them into
 * a buffer assistant message; `tool_call` / `tool_result` events render as
 * separate compact rows.
 */
@Composable
fun AskScreen() {
    val app = ClientApp.instance
    val scope = rememberCoroutineScope()
    val (url, token) = app.store.pair.collectAsState(initial = "" to "").value
    val client = remember(url, token) { BackendClient(url, token) }

    val sessionId = ActiveSession.id
    val sessionTitle = ActiveSession.title

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var streaming by remember { mutableStateOf(false) }
    var assistantBuffer by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(sessionId, url) {
        messages.clear()
        assistantBuffer = ""
        error = null
        if (sessionId == null || url.isBlank()) return@LaunchedEffect
        runCatching { client.getMessages(sessionId) }
            .onSuccess { messages.addAll(it) }
            .onFailure { error = "Load failed: ${it.message}" }
    }

    LaunchedEffect(messages.size, assistantBuffer) {
        val target = (messages.size - 1).coerceAtLeast(0)
        runCatching { listState.animateScrollToItem(target) }
    }

    Column(Modifier.fillMaxSize().background(LuminarisColors.Bg)) {
        if (sessionId == null) {
            Box(Modifier.fillMaxSize().padding(24.dp)) {
                Text(
                    "Pick or create a session in the Sessions tab.",
                    color = LuminarisColors.TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Column
        }

        Text(
            sessionTitle ?: "Session $sessionId",
            color = LuminarisColors.AccentCyan,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            itemsIndexed(messages) { _, m -> MessageView(m) }
            if (assistantBuffer.isNotEmpty()) {
                item { MessageView(ChatMessage(role = "assistant", content = assistantBuffer)) }
            }
            if (error != null) {
                item {
                    Text(
                        error!!,
                        color = LuminarisColors.AccentRed,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }

        Composer(
            value = input,
            onChange = { input = it },
            sending = streaming,
            onSend = {
                val text = input.trim()
                if (text.isEmpty() || streaming) return@Composer
                input = ""
                error = null
                messages.add(ChatMessage(role = "user", content = text))
                streaming = true
                assistantBuffer = ""
                scope.launch {
                    runCatching {
                        client.streamChat(ChatRequest(sessionId = sessionId, content = text)).collect { ev ->
                            applyEvent(ev, messages,
                                appendDelta = { assistantBuffer += it },
                                flushAssistant = {
                                    if (assistantBuffer.isNotEmpty()) {
                                        messages.add(ChatMessage(role = "assistant", content = assistantBuffer))
                                        assistantBuffer = ""
                                    }
                                })
                        }
                    }.onFailure {
                        error = "Stream failed: ${it.message}"
                    }
                    if (assistantBuffer.isNotEmpty()) {
                        messages.add(ChatMessage(role = "assistant", content = assistantBuffer))
                        assistantBuffer = ""
                    }
                    streaming = false
                }
            },
        )
    }
}

private fun applyEvent(
    ev: StreamEvent,
    messages: MutableList<ChatMessage>,
    appendDelta: (String) -> Unit,
    flushAssistant: () -> Unit,
) {
    when (ev.type) {
        "delta" -> ev.delta?.let { appendDelta(it) }
        "tool_call" -> {
            flushAssistant()
            val tc: ToolCall = ev.toolCall ?: return
            messages.add(ChatMessage(role = "tool", content = "→ ${tc.name}(${tc.args.take(120)})", toolCall = tc))
        }
        "tool_result" -> {
            val tr: ToolResult = ev.toolResult ?: return
            val prefix = if (tr.isError) "✗" else "✓"
            messages.add(ChatMessage(role = "tool", content = "$prefix ${tr.output.take(400)}", toolResult = tr))
        }
        "error" -> {
            flushAssistant()
            messages.add(ChatMessage(role = "assistant", content = "Error: ${ev.message.orEmpty()}"))
        }
        "done" -> flushAssistant()
        else -> Unit
    }
}

@Composable
private fun MessageView(m: ChatMessage) {
    when (m.role) {
        "user" -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(LuminarisColors.UserBubble)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(m.content, color = LuminarisColors.Text)
                }
            }
        }
        "assistant" -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(LuminarisColors.Surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(m.content, color = LuminarisColors.Text)
            }
        }
        else -> Box(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(LuminarisColors.ToolBubble)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    m.content,
                    color = LuminarisColors.TextDim,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LuminarisColors.Surface)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text("Ask the agent…", color = LuminarisColors.TextDim) },
            singleLine = false,
            maxLines = 4,
            colors = clientFieldColors(),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.padding(end = 6.dp))
        IconButton(
            onClick = onSend,
            enabled = !sending && value.isNotBlank(),
        ) {
            Icon(
                Icons.Outlined.Send,
                contentDescription = "Send",
                tint = if (sending || value.isBlank()) LuminarisColors.Muted else LuminarisColors.AccentCyan,
            )
        }
    }
}
