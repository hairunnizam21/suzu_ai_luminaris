package com.suzuai.luminaris.app.ui.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suzuai.luminaris.app.ClientApp
import com.suzuai.luminaris.app.ui.sessions.ActiveSession
import com.suzuai.luminaris.app.ui.sessions.AgentState
import com.suzuai.luminaris.app.ui.setup.clientFieldColors
import com.suzuai.luminaris.shared.data.AgentEvent
import com.suzuai.luminaris.shared.data.ChatMessage
import com.suzuai.luminaris.shared.data.ChatRequest
import com.suzuai.luminaris.shared.net.BackendClient
import com.suzuai.luminaris.shared.theme.LuminarisColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Chat surface with background-task polling, animations, and live token usage.
 *
 * Architecture:
 *  * POST /v1/chat kicks off the agent loop server-side (returns immediately).
 *  * A polling loop hits GET /v1/sessions/{id}/events?since=<seq> every ~700ms.
 *  * Even if this APK is backgrounded/killed, the agent keeps running. On reopen,
 *    polling resumes from the last known seq — no events lost.
 */
@Composable
fun AskScreen() {
    val app = ClientApp.instance
    val scope = rememberCoroutineScope()
    val (url, _token) = app.store.pair.collectAsState(initial = "" to "").value
    val client = remember(url) { BackendClient(url, "") }

    val sessionId = ActiveSession.id
    val sessionTitle = ActiveSession.title

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var assistantBuffer by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var agentStatus by remember { mutableStateOf("idle") }
    var tokensIn by remember { mutableIntStateOf(0) }
    var tokensOut by remember { mutableIntStateOf(0) }
    var lastSeq by remember { mutableIntStateOf(0) }
    var polling by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Load history on session open.
    LaunchedEffect(sessionId, url) {
        messages.clear()
        assistantBuffer = ""
        error = null
        lastSeq = 0
        tokensIn = 0
        tokensOut = 0
        agentStatus = "idle"
        if (sessionId == null || url.isBlank()) return@LaunchedEffect
        runCatching { client.getMessages(sessionId) }
            .onSuccess { messages.addAll(it) }
            .onFailure { error = "Load failed: ${it.message}" }
        // Catch up to latest events seq.
        runCatching { client.pollEvents(sessionId, 0, 1) }
            .onSuccess {
                lastSeq = it.lastSeq
                agentStatus = it.status
                tokensIn = it.tokensIn
                tokensOut = it.tokensOut
                if (it.running) polling = true
            }
    }

    // Polling coroutine: drains events as they appear.
    LaunchedEffect(polling, sessionId, url) {
        if (!polling || sessionId == null || url.isBlank()) return@LaunchedEffect
        try {
            while (isActive && polling) {
                val resp = try {
                    client.pollEvents(sessionId, lastSeq)
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    delay(2000)
                    continue
                }
                agentStatus = resp.status
                tokensIn = resp.tokensIn
                tokensOut = resp.tokensOut
                AgentState.status = resp.status
                AgentState.tokensIn = resp.tokensIn
                AgentState.tokensOut = resp.tokensOut
                for (ev in resp.events) {
                    applyEvent(ev, messages,
                        appendDelta = { assistantBuffer += it },
                        flushAssistant = {
                            if (assistantBuffer.isNotEmpty()) {
                                messages.add(ChatMessage(role = "assistant", content = assistantBuffer))
                                assistantBuffer = ""
                            }
                        },
                    )
                }
                lastSeq = resp.lastSeq
                if (!resp.running) {
                    if (assistantBuffer.isNotEmpty()) {
                        messages.add(ChatMessage(role = "assistant", content = assistantBuffer))
                        assistantBuffer = ""
                    }
                    polling = false
                    sending = false
                }
                delay(700)
            }
        } catch (_: CancellationException) { /* normal */ }
    }

    // Auto-scroll on new content.
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

        // ===== Title + status pill + token counter =====
        Row(
            Modifier
                .fillMaxWidth()
                .background(LuminarisColors.Surface)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                sessionTitle ?: "Session",
                color = LuminarisColors.AccentCyan,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            StatusPillAnimated(agentStatus)
            Spacer(Modifier.width(10.dp))
            TokenCounter(tokensIn, tokensOut)
        }

        // ===== Messages =====
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            itemsIndexed(messages) { idx, m ->
                AnimatedBubble(idx) { MessageView(m) }
            }
            if (assistantBuffer.isNotEmpty()) {
                item {
                    AnimatedBubble(messages.size) {
                        AssistantBubble(assistantBuffer, typing = true)
                    }
                }
            }
            if (error != null) {
                item {
                    Text(error!!, color = LuminarisColors.AccentRed, modifier = Modifier.padding(8.dp))
                }
            }
        }

        Composer(
            value = input,
            onChange = { input = it },
            sending = sending,
            onSend = {
                val text = input.trim()
                if (text.isEmpty() || sending) return@Composer
                input = ""
                error = null
                messages.add(ChatMessage(role = "user", content = text))
                sending = true
                assistantBuffer = ""
                scope.launch {
                    runCatching {
                        client.startChat(ChatRequest(sessionId = sessionId, content = text))
                        polling = true
                    }.onFailure {
                        error = "Send failed: ${it.message}"
                        sending = false
                    }
                }
            },
        )
    }
}

// ========================================================================
// Events → messages
// ========================================================================

private fun applyEvent(
    ev: AgentEvent,
    messages: MutableList<ChatMessage>,
    appendDelta: (String) -> Unit,
    flushAssistant: () -> Unit,
) {
    when (ev.type) {
        "delta" -> ev.delta?.let { appendDelta(it) }
        "tool_call" -> {
            flushAssistant()
            val tc = ev.toolCall ?: return
            messages.add(ChatMessage(role = "tool", content = "→ ${tc.name}(${tc.args.take(120)})", toolCall = tc))
        }
        "tool_result" -> {
            val tr = ev.toolResult ?: return
            val prefix = if (tr.isError) "✗" else "✓"
            messages.add(ChatMessage(role = "tool", content = "$prefix exit=${if (tr.isError) 1 else 0}\n${tr.output.take(400)}", toolResult = tr))
        }
        "error" -> {
            flushAssistant()
            messages.add(ChatMessage(role = "assistant", content = "Error: ${ev.message.orEmpty()}"))
        }
        "done" -> flushAssistant()
        else -> Unit
    }
}

// ========================================================================
// UI Components with animations
// ========================================================================

/** Animated three-dot status: Typing… / Running tool… / Idle */
@Composable
private fun StatusPillAnimated(status: String) {
    val transition = rememberInfiniteTransition(label = "dots")
    val dotAlpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "dotAlpha",
    )
    val (label, color) = when (status) {
        "typing" -> "Typing" to LuminarisColors.AccentCyan
        "tool" -> "Running tool" to LuminarisColors.AccentAmber
        "error" -> "Error" to LuminarisColors.AccentRed
        else -> "Idle" to LuminarisColors.Muted
    }
    val isActive = status == "typing" || status == "tool"
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (isActive) {
            Box(
                Modifier
                    .size(6.dp)
                    .alpha(dotAlpha)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            if (isActive) "$label…" else label,
            color = color,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        )
    }
}

/** Live token counter: ↑in · ↓out */
@Composable
private fun TokenCounter(tokensIn: Int, tokensOut: Int) {
    if (tokensIn == 0 && tokensOut == 0) return
    Text(
        "↑${fmtTokens(tokensIn)} ↓${fmtTokens(tokensOut)}",
        color = LuminarisColors.TextDim,
        style = MaterialTheme.typography.labelSmall,
    )
}

private fun fmtTokens(n: Int): String = when {
    n < 1000 -> "$n"
    n < 100_000 -> "${n / 1000}.${(n % 1000) / 100}k"
    else -> "${n / 1000}k"
}

/** Fade-in + slide-up entrance for each message bubble. */
@Composable
private fun AnimatedBubble(index: Int, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 },
    ) {
        content()
    }
}

/** Assistant text bubble with optional caret blink. */
@Composable
private fun AssistantBubble(text: String, typing: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "caret")
    val caretAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "caretAlpha",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(LuminarisColors.Surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row {
                Text(text, color = LuminarisColors.Text)
                if (typing) {
                    Text("▌", color = LuminarisColors.AccentCyan.copy(alpha = caretAlpha))
                }
            }
        }
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
        "assistant" -> AssistantBubble(m.content, typing = false)
        else -> ToolBubbleView(m)
    }
}

/** Tool call / result bubble with spinner or icon. */
@Composable
private fun ToolBubbleView(m: ChatMessage) {
    val isCall = m.toolCall != null
    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(LuminarisColors.ToolBubble)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isCall) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.5.dp,
                    color = LuminarisColors.AccentAmber,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                m.content,
                color = if (m.toolResult?.isError == true) LuminarisColors.AccentRed else LuminarisColors.TextDim,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
            )
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
                Icons.AutoMirrored.Outlined.Send,
                contentDescription = "Send",
                tint = if (sending || value.isBlank()) LuminarisColors.Muted else LuminarisColors.AccentCyan,
            )
        }
    }
}
